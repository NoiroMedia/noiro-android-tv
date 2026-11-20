package tv.noiro.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Public Cinemeta catalogs and title metadata. No account, Noiro service or private API key. */
final class FreeCatalog {
    static final String API = "https://v3-cinemeta.strem.io";
    private static final int MAX_BYTES = 1024 * 1024;
    private static final Map<String, Cached> CACHE = new LinkedHashMap<String, Cached>(16, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Cached> entry) { return size() > 16; }
    };

    private FreeCatalog() { }

    static List<StudioHome.Row> rows() throws Exception { return rows(""); }

    static List<StudioHome.Row> search(String query) throws Exception {
        String value = text(query, 180);
        return value.isEmpty() ? rows() : rows(value);
    }

    private static List<StudioHome.Row> rows(String query) throws Exception {
        List<StudioHome.Row> rows = new ArrayList<>();
        Exception failure = null;
        for (String type : new String[]{"movie", "series"}) {
            try {
                StudioHome.Row row = new StudioHome.Row(type.equals("movie")
                        ? query.isEmpty() ? Ui.tr("Filmes populares", "Popular Movies") : Ui.tr("Filmes", "Movies")
                        : query.isEmpty() ? Ui.tr("Séries populares", "Popular Series") : Ui.tr("Séries", "Series"));
                row.catalogId = "free:" + type;
                row.cards.addAll(catalog(type, query, 0));
                if (!row.cards.isEmpty()) rows.add(row);
            } catch (Exception unavailable) { failure = unavailable; }
        }
        if (rows.isEmpty() && failure != null) throw failure;
        return rows;
    }

    static List<StudioHome.Card> catalog(String type, String query, int skip) throws Exception {
        type = type(type);
        if (type.isEmpty() || skip < 0 || skip > 10000) throw new IllegalArgumentException();
        String search = text(query, 180);
        String extra = search.isEmpty() ? "skip=" + skip : "search="
                + URLEncoder.encode(search, "UTF-8").replace("+", "%20") + (skip == 0 ? "" : "&skip=" + skip);
        return parseCatalog(fetch(API + "/catalog/" + type + "/top/" + extra + ".json"), type);
    }

    static JSONObject metadata(String type, String imdb) throws Exception {
        type = type(type);
        if (type.isEmpty() || !imdb(imdb)) throw new IllegalArgumentException();
        return parseMetadata(fetch(API + "/meta/" + type + "/" + imdb + ".json"), type, imdb);
    }

    static List<StudioHome.Card> parseCatalog(JSONObject response, String type) {
        List<StudioHome.Card> cards = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        JSONArray entries = response == null ? null : response.optJSONArray("metas");
        for (int i = 0; entries != null && i < Math.min(100, entries.length()); i++) {
            StudioHome.Card card = card(entries.optJSONObject(i), type);
            if (card != null && seen.add(card.key())) cards.add(card);
        }
        return cards;
    }

    static JSONObject parseMetadata(JSONObject response, String type, String imdb) throws Exception {
        JSONObject source = response == null ? null : response.optJSONObject("meta");
        StudioHome.Card card = card(source, type);
        if (card == null || !card.id.equals(imdb)) throw new IOException("Title metadata unavailable");
        return card.metadata;
    }

    /** Convert a public meta preview or full meta to the same cards used by the existing Home. */
    static StudioHome.Card card(JSONObject source, String requestedType) {
        if (source == null) return null;
        String type = type(requestedType), declared = type(source.optString("type"));
        String id = text(source.optString("id"), 32), name = text(source.optString("name"), 240);
        if (type.isEmpty() || !source.optString("type").isEmpty() && !declared.equals(type) || !imdb(id) || name.isEmpty()) return null;
        try {
            JSONObject metadata = new JSONObject().put("id", id).put("imdbId", id).put("imdb_id", id)
                    .put("type", type).put("name", name);
            for (String key : new String[]{"description", "releaseInfo", "year", "released", "runtime", "imdbRating"}) {
                String value = text(source.optString(key), key.equals("description") ? 6000 : 120);
                if (!value.isEmpty()) metadata.put(key, value);
            }
            for (String key : new String[]{"poster", "background", "logo"}) metadata.put(key, image(source.optString(key)));
            JSONArray genres = strings(source.optJSONArray("genres"));
            if (genres.length() == 0) genres = strings(source.optJSONArray("genre"));
            metadata.put("genres", genres);
            JSONArray videos = source.optJSONArray("videos");
            if (videos != null) {
                JSONArray clean = new JSONArray();
                for (int i = 0; i < Math.min(2500, videos.length()); i++) {
                    JSONObject episode = videos.optJSONObject(i);
                    if (episode == null) continue;
                    String videoId = text(episode.optString("id"), 64);
                    if (!videoId.matches(java.util.regex.Pattern.quote(id) + ":[0-9]{1,3}:[1-9][0-9]{0,3}")) continue;
                    String[] parts = videoId.split(":");
                    String released = text(episode.optString("released"), 80);
                    if (released.isEmpty()) released = text(episode.optString("firstAired"), 80);
                    clean.put(new JSONObject().put("id", videoId).put("name", text(episode.optString("name"), 240))
                            .put("title", text(episode.optString("title"), 240))
                            .put("season", Integer.parseInt(parts[1])).put("episode", Integer.parseInt(parts[2]))
                            .put("number", Integer.parseInt(parts[2])).put("released", released)
                            .put("description", text(episode.optString("description"), 4000))
                            .put("thumbnail", image(episode.optString("thumbnail"))));
                }
                metadata.put("videos", clean);
            }
            return new StudioHome.Card(id, name, type, metadata.optString("poster"), metadata.optString("background"),
                    metadata.optString("logo"), "", metadata.optString("description"), false, false, null, metadata);
        } catch (Exception invalid) { return null; }
    }

    static String type(StudioHome.Card card) { return card == null ? "" : type(card.type); }
    static String type(String value) { return "movie".equals(value) ? "movie" : "series".equals(value) || "tv".equals(value) ? "series" : ""; }
    static boolean imdb(String value) { return value != null && value.matches("tt[0-9]{5,12}"); }

    static String image(String value) {
        if (value == null || value.length() > 2048) return "";
        try {
            URI uri = new URI(value);
            return "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getPort() == -1 && uri.getFragment() == null ? value : "";
        } catch (Exception invalid) { return ""; }
    }

    private static String text(String value, int limit) {
        if (value == null || value.equals("null")) return "";
        value = value.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").trim();
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private static JSONArray strings(JSONArray source) throws Exception {
        JSONArray result = new JSONArray();
        for (int i = 0; source != null && i < Math.min(30, source.length()); i++) {
            Object value = source.opt(i);
            if (value instanceof String && !text((String) value, 80).isEmpty()) result.put(text((String) value, 80));
        }
        return result;
    }

    private static final class Cached {
        final JSONObject data;
        final long expires;
        Cached(JSONObject data, long expires) { this.data = data; this.expires = expires; }
    }

    private static JSONObject fetch(String url) throws Exception {
        synchronized (CACHE) {
            Cached value = CACHE.get(url);
            if (value != null && value.expires > System.currentTimeMillis()) return value.data;
        }
        JSONObject data = request(url, FreeCatalog::http);
        synchronized (CACHE) { CACHE.put(url, new Cached(data, System.currentTimeMillis() + 15 * 60_000L)); }
        return data;
    }

    interface Transport { Response get(String url) throws Exception; }
    static final class Response {
        final int status;
        final String location;
        final JSONObject data;
        Response(int status, String location, JSONObject data) { this.status = status; this.location = location; this.data = data; }
    }

    /** The official popular endpoint redirects; never forward credentials or follow an arbitrary host. */
    static JSONObject request(String url, Transport transport) throws Exception {
        String target = checked(url);
        for (int redirects = 0; ; redirects++) {
            Response response = transport.get(target);
            if (response.status >= 200 && response.status < 300 && response.data != null) return response.data;
            if (response.status != 301 && response.status != 302 && response.status != 303
                    && response.status != 307 && response.status != 308 || redirects >= 2)
                throw new IOException("Public catalog unavailable");
            if (response.location == null || response.location.isEmpty()) throw new IOException("Public catalog unavailable");
            target = checked(new URI(target).resolve(response.location).toString());
        }
    }

    private static String checked(String url) throws Exception {
        URI uri = new URI(url);
        if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null || uri.getPort() != -1 || uri.getFragment() != null
                || !("v3-cinemeta.strem.io".equals(uri.getHost()) || "cinemeta-catalogs.strem.io".equals(uri.getHost())))
            throw new IOException("Public catalog unavailable");
        return url;
    }

    private static Response http(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(12000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "Noiro Android TV");
            int status = connection.getResponseCode();
            JSONObject data = null;
            if (status >= 200 && status < 300) {
                try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (output.size() + count > MAX_BYTES) throw new IOException("Public catalog unavailable");
                        output.write(buffer, 0, count);
                    }
                    data = new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
                }
            }
            return new Response(status, connection.getHeaderField("Location"), data);
        } finally { connection.disconnect(); }
    }
}
