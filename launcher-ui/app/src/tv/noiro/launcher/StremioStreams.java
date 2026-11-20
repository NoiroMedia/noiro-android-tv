package tv.noiro.launcher;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/** Reads only the signed-in viewer's installed add-ons; never sends Stremio auth to an add-on. */
final class StremioStreams {
    static final class Source {
        final String addon, name, description, url, externalUrl;
        final String filename, videoHash, videoSize;
        final java.util.Map<String, String> headers = new java.util.HashMap<>();
        final boolean needsStremio;
        Source(String addon, JSONObject stream) {
            this.addon = addon;
            name = stream.optString("name", "Stream");
            description = stream.optString("description", stream.optString("title", ""));
            url = stream.optString("url"); externalUrl = stream.optString("externalUrl");
            JSONObject hints = stream.optJSONObject("behaviorHints");
            filename = hints == null ? "" : hints.optString("filename");
            videoHash = hints == null ? "" : hints.optString("videoHash");
            videoSize = hints == null ? "" : hints.optString("videoSize");
            JSONObject proxy = hints == null ? null : hints.optJSONObject("proxyHeaders");
            JSONObject requestHeaders = proxy == null ? null : proxy.optJSONObject("request");
            boolean unsupported = false;
            if (requestHeaders != null) for (java.util.Iterator<String> names = requestHeaders.keys(); names.hasNext();) {
                String name = names.next(), value = requestHeaders.optString(name);
                if (PlaybackPolicy.allowedHeader(name, value)) headers.put(name, value); else unsupported = true;
            }
            needsStremio = !StreamPolicy.web(url, true) || unsupported;
        }
    }
    static final class Result {
        final List<Source> sources = new ArrayList<>();
        int addons, failures;
    }
    static boolean supports(JSONObject manifest, String type, String id) {
        return supportsResource(manifest, "stream", type, id);
    }
    static boolean supportsResource(JSONObject manifest, String resourceName, String type, String id) {
        if (manifest == null) return false;
        JSONArray types = manifest.optJSONArray("types");
        if (!contains(types, type)) return false;
        JSONArray resources = manifest.optJSONArray("resources");
        for (int i = 0; resources != null && i < resources.length(); i++) {
            Object resource = resources.opt(i);
            if (resourceName.equals(resource)) return true;
            if (!(resource instanceof JSONObject)) continue;
            JSONObject r = (JSONObject) resource;
            if (!resourceName.equals(r.optString("name"))) continue;
            if (r.has("types") && !contains(r.optJSONArray("types"), type)) continue;
            JSONArray prefixes = r.optJSONArray("idPrefixes");
            if (prefixes != null) {
                boolean match = false;
                for (int n = 0; n < prefixes.length(); n++) if (id.startsWith(prefixes.optString(n))) match = true;
                if (!match) continue;
            }
            return true;
        }
        return false;
    }
    private static boolean contains(JSONArray values, String value) {
        for (int i = 0; values != null && i < values.length(); i++) if (value.equals(values.optString(i))) return true;
        return false;
    }
    static Result load(AccountState owner, String type, String id) throws Exception {
        JSONObject response = AccountHttp.call(StremioLogin.API + "addonCollectionGet",
                new JSONObject().put("authKey", owner.authKey), "");
        if (response.has("error") && !response.isNull("error")) throw new AccountHttp.Failure("authentication_required");
        JSONObject collection = response.optJSONObject("result");
        if (collection == null || collection.optJSONArray("addons") == null) throw new AccountHttp.Failure("service_unavailable");
        JSONArray entries = collection.optJSONArray("addons");
        Result result = new Result();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Future<List<Source>>> requests = new ArrayList<>();
        try {
            for (int i = 0; i < Math.min(entries.length(), 64); i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry == null) continue;
                JSONObject manifest = entry.optJSONObject("manifest");
                if (!supports(manifest, type, id)) continue;
                final String endpoint;
                try { endpoint = StreamPolicy.endpoint(entry.optString("transportUrl"), type, id); }
                catch (Exception unsupported) { result.failures++; continue; }
                final String name = manifest.optString("name", "Add-on");
                result.addons++;
                requests.add(pool.submit(() -> {
                    JSONObject data = AccountHttp.call(endpoint, null, "");
                    JSONArray streams = data.optJSONArray("streams");
                    if (streams == null) throw new AccountHttp.Failure("service_unavailable");
                    List<Source> sources = new ArrayList<>();
                    for (int n = 0; n < Math.min(streams.length(), 200); n++) {
                        JSONObject stream = streams.optJSONObject(n);
                        if (stream == null) continue;
                        if (StreamPolicy.web(stream.optString("url"), false) || StreamPolicy.web(stream.optString("externalUrl"), true)
                                || stream.optString("infoHash").matches("[0-9a-fA-F]{40}") || !stream.optString("ytId").isEmpty())
                            sources.add(new Source(name, stream));
                    }
                    return sources;
                }));
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(35);
            for (Future<List<Source>> request : requests) {
                try { result.sources.addAll(request.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)); }
                catch (InterruptedException cancelled) { throw cancelled; }
                catch (Exception unavailable) { request.cancel(true); result.failures++; }
            }
            return result;
        } finally { pool.shutdownNow(); }
    }
}
