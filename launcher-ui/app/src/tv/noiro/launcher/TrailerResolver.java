package tv.noiro.launcher;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** On-device trailer resolution. Expiring media addresses never leave memory. */
final class TrailerResolver {
    static String youtubeKey(JSONObject title) {
        JSONObject videos=title.optJSONObject("videos");JSONArray values=videos==null?null:videos.optJSONArray("results");
        for(int i=0;values!=null&&i<values.length();i++){
            JSONObject item=values.optJSONObject(i);
            if(item!=null&&"YouTube".equals(item.optString("site"))&&"Trailer".equals(item.optString("type"))
                    &&item.optString("key").matches("[A-Za-z0-9_-]{11}"))return item.optString("key");
        }
        return null;
    }
    static final class Result {
        final String video, audio, userAgent;
        Result(String video, String audio, String userAgent) {
            this.video = video; this.audio = audio; this.userAgent = userAgent;
        }
    }
    interface Verifier { boolean playable(Result result) throws Exception; }
    interface Transport { JSONObject post(String url, JSONObject body, String userAgent) throws Exception; }
    private static final String[][] CLIENTS = {
        {"IOS", "21.02.3", "5", "com.google.ios.youtube/21.02.3 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"},
        {"ANDROID", "21.02.35", "3", "com.google.android.youtube/21.02.35 (Linux; U; Android 11) gzip"},
        {"TVHTML5_SIMPLY_EMBEDDED_PLAYER", "2.0", "85", "Mozilla/5.0 (PlayStation; PlayStation 4/12.00) AppleWebKit/605.1.15 Version/16.0 Safari/605.1.15"}
    };
    static Result resolve(String youtube, String imdb) throws Exception {
        return resolve(youtube, imdb, TrailerResolver::post, TrailerResolver::playable);
    }
    static Result resolve(String youtube, String imdb, Transport transport, Verifier verifier) throws Exception {
        if (youtube == null || !youtube.matches("[A-Za-z0-9_-]{11}")) throw new IOException("Invalid trailer identity");
        // The same title can have a native, muxed official trailer on IMDb.
        if (imdb != null && imdb.matches("tt[0-9]{5,12}")) try {
            JSONObject body = new JSONObject().put("query", "query($id:ID!){title(id:$id){primaryVideos(first:10){edges{node{name{value} playbackURLs{mimeType url videoDefinition}}}}}}")
                    .put("variables", new JSONObject().put("id", imdb));
            Result result = imdb(transport.post("https://api.graphql.imdb.com/", body, "Mozilla/5.0"));
            if (result != null && verifier.playable(result)) return result;
        } catch (Exception unavailable) { /* Playback failure is shown inside the launcher. */ }
        for (String[] client : CLIENTS) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            try {
                JSONObject identity = new JSONObject().put("clientName", client[0]).put("clientVersion", client[1])
                        .put("userAgent", client[3]).put("hl", "en").put("gl", "US");
                if (client[0].equals("IOS")) identity.put("deviceMake", "Apple").put("deviceModel", "iPhone16,2")
                        .put("osName", "iPhone").put("osVersion", "18.3.2.22D82");
                if (client[0].equals("ANDROID")) identity.put("androidSdkVersion", 30).put("osName", "Android").put("osVersion", "11");
                if (client[0].startsWith("TV")) identity.put("clientScreen", "EMBED");
                JSONObject body = new JSONObject().put("videoId", youtube).put("contentCheckOk", true).put("racyCheckOk", true)
                        .put("context", new JSONObject().put("client", identity));
                JSONObject response = transport.post("https://www.youtube.com/youtubei/v1/player?prettyPrint=false", body, client[3]);
                JSONObject status = response.optJSONObject("playabilityStatus"), details = response.optJSONObject("videoDetails");
                if (status == null || !"OK".equals(status.optString("status")) || details == null
                        || !youtube.equals(details.optString("videoId"))) continue;
                Result result = select(response.optJSONObject("streamingData"), client[3]);
                if (result != null && verifier.playable(result)) return result;
            } catch (InterruptedException interrupted) { throw interrupted; }
            catch (Exception unavailable) { /* Try the next client; never log signed response data. */ }
        }
        throw new IOException("Trailer unavailable");
    }
    static boolean mediaUrl(String raw, String domain) {
        try {
            URI uri = new URI(raw); String host = uri.getHost();
            return "https".equals(uri.getScheme()) && uri.getRawUserInfo() == null && uri.getFragment() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443) && host != null
                    && (host.equals(domain) || host.endsWith("." + domain)) && raw.length() <= 16000;
        } catch (Exception malformed) { return false; }
    }
    private static String plain(JSONObject format) {
        if (format == null || format.has("signatureCipher") || format.has("cipher")) return "";
        String url = format.optString("url"); return mediaUrl(url, "googlevideo.com") ? url : "";
    }
    static Result select(JSONObject data, String userAgent) {
        if (data == null) return null;
        JSONObject muxed = null, video = null, audio = null;
        JSONArray formats = data.optJSONArray("formats"), adaptive = data.optJSONArray("adaptiveFormats");
        for (int i = 0; formats != null && i < Math.min(100, formats.length()); i++) {
            JSONObject f = formats.optJSONObject(i);
            if (f == null || plain(f).isEmpty() || !f.optString("mimeType").startsWith("video/mp4")
                    || (f.optInt("itag") != 18 && f.optInt("itag") != 22)) continue;
            if (muxed == null || f.optInt("height") > muxed.optInt("height")) muxed = f;
        }
        for (int i = 0; adaptive != null && i < Math.min(200, adaptive.length()); i++) {
            JSONObject f = adaptive.optJSONObject(i); if (f == null || plain(f).isEmpty()) continue;
            String mime = f.optString("mimeType");
            if (mime.startsWith("audio/mp4") && mime.contains("mp4a") && (audio == null || f.optLong("bitrate") > audio.optLong("bitrate"))) audio = f;
            if (mime.startsWith("video/mp4") && mime.contains("avc1") && f.optInt("height") > 0 && f.optInt("height") <= 1080
                    && (video == null || f.optInt("height") > video.optInt("height")
                        || f.optInt("height") == video.optInt("height") && f.optLong("bitrate") > video.optLong("bitrate"))) video = f;
        }
        if (video != null && audio != null && (muxed == null || video.optInt("height") > muxed.optInt("height")))
            return new Result(plain(video), plain(audio), userAgent);
        return muxed == null ? null : new Result(plain(muxed), "", userAgent);
    }
    static Result imdb(JSONObject response) {
        try {
            JSONArray edges = response.getJSONObject("data").getJSONObject("title").getJSONObject("primaryVideos").getJSONArray("edges");
            for (int i = 0; i < Math.min(10, edges.length()); i++) {
                JSONObject node = edges.getJSONObject(i).getJSONObject("node"), name = node.optJSONObject("name");
                String label = name == null ? "" : name.optString("value").toLowerCase(java.util.Locale.ROOT);
                if (!label.contains("trailer") && !label.contains("teaser")) continue;
                JSONArray urls = node.optJSONArray("playbackURLs"); String best = ""; int height = 0;
                for (int j = 0; urls != null && j < Math.min(30, urls.length()); j++) {
                    JSONObject p = urls.optJSONObject(j); if (p == null) continue;
                    String digits = p.optString("videoDefinition").replaceAll("[^0-9]", "");
                    int h; try { h = Integer.parseInt(digits); } catch (Exception bad) { continue; }
                    String url = p.optString("url");
                    if (h > height && h <= 1080 && "video/mp4".equals(p.optString("mimeType")) && mediaUrl(url, "media-imdb.com")) { best = url; height = h; }
                }
                if (!best.isEmpty()) return new Result(best, "", "Mozilla/5.0");
            }
        } catch (Exception unavailable) { }
        return null;
    }
    private static boolean playable(Result result) throws Exception {
        return readableEnd(result.video, result.userAgent) && (result.audio.isEmpty() || readableEnd(result.audio, result.userAgent));
    }
    private static boolean readableEnd(String url, String userAgent) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        java.util.regex.Matcher length = java.util.regex.Pattern.compile("(?:[?&])clen=([0-9]{1,14})(?:&|$)").matcher(url);
        long size = length.find() ? Long.parseLong(length.group(1)) : 0;
        HttpURLConnection connection = (HttpURLConnection)new URL(url).openConnection();
        connection.setConnectTimeout(4000); connection.setReadTimeout(4000); connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", userAgent);
        connection.setRequestProperty("Range", size > 0 ? "bytes=" + Math.max(0, size - 32) + "-" + (size - 1) : "bytes=0-31");
        try {
            int status = connection.getResponseCode();
            if (status != 200 && status != 206) return false;
            try (InputStream input = connection.getInputStream()) { return input.read() >= 0; }
        } finally { connection.disconnect(); }
    }
    private static JSONObject post(String url, JSONObject body, String userAgent) throws Exception {
        HttpURLConnection connection = (HttpURLConnection)new URL(url).openConnection();
        connection.setConnectTimeout(5000); connection.setReadTimeout(5000); connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("POST"); connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json"); connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", userAgent);
        if (url.contains("imdb.com")) { connection.setRequestProperty("Origin", "https://www.imdb.com"); connection.setRequestProperty("Referer", "https://www.imdb.com/"); }
        try {
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8); connection.setFixedLengthStreamingMode(bytes.length);
            try (java.io.OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            if (connection.getResponseCode() != 200) throw new IOException("Trailer metadata unavailable");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    if (output.size() + count > 2 * 1024 * 1024) throw new IOException("Trailer metadata too large");
                    output.write(buffer, 0, count);
                }
                return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally { connection.disconnect(); }
    }
}
