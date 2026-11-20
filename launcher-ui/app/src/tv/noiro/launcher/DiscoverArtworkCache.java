package tv.noiro.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Keeps positive public Discover artwork mappings for the current account across restarts. */
final class DiscoverArtworkCache {
    // Resolve current Discover originals instead of restoring old thumbnail URLs.
    private static final String FILE = "discover-artwork-v2";
    private static final String OWNER = "owner", ARTWORK = "artwork";
    private static final int LIMIT = 512, INCOMING_LIMIT = 384, MAX_CHARS = 1_048_576;
    private static final long TTL = 7L * 24 * 60 * 60 * 1000, REFRESH_AFTER = 60L * 60 * 1000;
    private static final String[] FIELDS = {"poster", "landscape", "background", "logo", "landscapeLogo"};
    private static final Pattern IDENTITY = Pattern.compile(
            "(?:(?:movie|series):(?:tt[0-9]{5,12}|tmdb:[1-9][0-9]{0,9})"
                    + "|(?:movie|series|trailer):plex:[a-f0-9]{24})");

    private DiscoverArtworkCache() {}

    static synchronized Map<String, JSONObject> read(Context context, AccountState owner) {
        Map<String, JSONObject> result = new LinkedHashMap<>();
        if (!StudioHome.current(owner)) return result;
        SharedPreferences prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        JSONObject records = stored(prefs, ownerKey(owner));
        long now = System.currentTimeMillis();
        try {
            for (Iterator<String> keys = records.keys(); keys.hasNext() && result.size() < LIMIT;) {
                String key = keys.next();
                if (!IDENTITY.matcher(key).matches()) continue;
                JSONObject values = retained(records.optJSONObject(key), now);
                JSONObject fields = new JSONObject();
                for (String field : FIELDS) {
                    JSONArray hit = values.optJSONArray(field);
                    if (hit == null) continue;
                    fields.put(field, hit.optString(1));
                    if ("landscape".equals(field) && "thumb".equals(hit.optString(2))) {
                        fields.put("landscapeKind", "thumb");
                    }
                }
                if (fields.length() > 0) result.put(key, fields);
            }
        } catch (JSONException invalid) {
            // Keep valid records already read.
        }
        return result;
    }

    static synchronized void merge(Context context, AccountState owner, Map<String, JSONObject> updates) {
        if (updates.isEmpty() || !StudioHome.current(owner)) return;
        SharedPreferences prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        String account = ownerKey(owner);
        JSONObject previous = stored(prefs, account);
        Map<String, JSONObject> records = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        try {
            // Input is in catalog order. Leave room for mappings from other, recently visited rows.
            for (Map.Entry<String, JSONObject> update : updates.entrySet()) {
                if (records.size() >= INCOMING_LIMIT) break;
                String key = update.getKey();
                if (key == null || !IDENTITY.matcher(key).matches()) continue;
                JSONObject values = retained(previous.optJSONObject(key), now);
                boolean positive = false;
                for (String field : FIELDS) {
                    String url = StudioHome.art(update.getValue(), field);
                    if (!publicAsset(url, true)) continue;
                    positive = true;
                    JSONArray old = values.optJSONArray(field);
                    String kind = "landscape".equals(field) && "thumb".equals(update.getValue().optString("landscapeKind"))
                            ? "thumb" : "";
                    if (old == null || !url.equals(old.optString(1)) || !kind.equals(old.optString(2))
                            || now - old.optLong(0) >= REFRESH_AFTER) {
                        JSONArray record = new JSONArray().put(now).put(url);
                        if (!kind.isEmpty()) record.put(kind);
                        values.put(field, record);
                    }
                }
                if (positive) records.put(key, values);
            }
            if (records.isEmpty()) return;
            for (Iterator<String> keys = previous.keys(); keys.hasNext() && records.size() < LIMIT;) {
                String key = keys.next();
                if (records.containsKey(key) || !IDENTITY.matcher(key).matches()) continue;
                JSONObject values = retained(previous.optJSONObject(key), now);
                if (values.length() > 0) records.put(key, values);
            }

            JSONObject saved = new JSONObject();
            int remaining = MAX_CHARS - 2;
            for (Map.Entry<String, JSONObject> entry : records.entrySet()) {
                int chars = JSONObject.quote(entry.getKey()).length() + entry.getValue().toString().length() + 2;
                if (chars > remaining) continue;
                saved.put(entry.getKey(), entry.getValue());
                remaining -= chars;
            }
            String encoded = saved.toString();
            if (StudioHome.current(owner) && (!account.equals(prefs.getString(OWNER, ""))
                    || !encoded.equals(prefs.getString(ARTWORK, "")))) {
                prefs.edit().putString(OWNER, account).putString(ARTWORK, encoded).apply();
            }
        } catch (JSONException invalid) {
            // Keep the previous cache intact if new data cannot be serialized.
        }
    }

    private static JSONObject stored(SharedPreferences prefs, String account) {
        if (account.equals(prefs.getString(OWNER, ""))) {
            String encoded = prefs.getString(ARTWORK, "");
            if (!encoded.isEmpty() && encoded.length() <= MAX_CHARS) {
                try { return new JSONObject(encoded); }
                catch (JSONException invalid) { /* Start with an empty cache. */ }
            }
        }
        return new JSONObject();
    }

    private static JSONObject retained(JSONObject stored, long now) throws JSONException {
        JSONObject result = new JSONObject();
        if (stored == null) return result;
        for (String field : FIELDS) {
            JSONArray hit = stored.optJSONArray(field);
            if (hit == null) continue;
            long seen = hit.optLong(0);
            if (seen >= now - TTL && seen <= now && publicAsset(hit.optString(1), true)) {
                result.put(field, hit);
            }
        }
        return result;
    }

    private static String ownerKey(AccountState owner) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(owner.uid.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    static boolean publicAsset(String url) {
        return publicAsset(url, true);
    }

    private static boolean publicAsset(String url, boolean allowQuery) {
        if (url.isEmpty() || url.length() > 2048) return false;
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            String path = uri.getRawPath();
            if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null || uri.getPort() != -1
                    || uri.getFragment() != null || path == null || path.isEmpty()) return false;
            String query = uri.getRawQuery();
            String prefix = "artworks.thetvdb.com".equalsIgnoreCase(host) ? "/banners/"
                    : "assets.fanart.tv".equalsIgnoreCase(host) ? "/fanart/" : "";
            if (!prefix.isEmpty()) {
                return query == null && path.startsWith(prefix) && path.substring(prefix.length())
                        .matches("(?:[A-Za-z0-9][A-Za-z0-9_.-]*/)*[A-Za-z0-9][A-Za-z0-9_.-]*[.](?i:jpe?g|png|webp|avif)");
            }
            if (!"images.plex.tv".equalsIgnoreCase(host) && !"metadata-static.plex.tv".equalsIgnoreCase(host)
                    && !"image.tmdb.org".equalsIgnoreCase(host)) return false;
            if (query == null) return true;
            if (!allowQuery) return false;
            for (String item : query.split("&", -1)) {
                String[] pair = item.split("=", 2);
                if (pair.length != 2) return false;
                String value = Uri.decode(pair[1]);
                switch (pair[0]) {
                    case "url":
                        if (!"images.plex.tv".equalsIgnoreCase(host) || !"/photo".equals(uri.getPath())
                                || !publicAsset(value, false)) return false;
                        break;
                    case "size":
                        if (!value.matches("(?:small|medium|large|original)(?:-[1-9][0-9]{0,3})?|[1-9][0-9]{0,3}(?:x[1-9][0-9]{0,3})?"))
                            return false;
                        break;
                    case "width": case "height": case "scale": case "quality":
                        if (!value.matches("[1-9][0-9]{0,3}(?:\\.[0-9]{1,2})?")) return false;
                        break;
                    case "format":
                        if (!value.matches("jpg|jpeg|png|webp|avif")) return false;
                        break;
                    default:
                        return false;
                }
            }
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
