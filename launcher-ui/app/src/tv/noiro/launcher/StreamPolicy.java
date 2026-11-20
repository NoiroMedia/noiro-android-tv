package tv.noiro.launcher;

import java.net.URI;
import java.net.URLEncoder;

/** Protocol identities and URL validation; no account secrets belong in UI text. */
final class StreamPolicy {
    static String videoId(String imdb, int season, int episode) {
        if (imdb == null || !imdb.matches("tt[0-9]{5,12}")) throw new IllegalArgumentException("missing_imdb");
        if (season < 0 || episode < 0 || (episode == 0 && season != 0)) throw new IllegalArgumentException("invalid_episode");
        return episode == 0 ? imdb : imdb + ":" + season + ":" + episode;
    }
    static boolean web(String value, boolean httpsOnly) {
        try {
            URI u = new URI(value);
            return value.length() <= 8192 && u.getHost() != null && u.getUserInfo() == null
                    && ("https".equalsIgnoreCase(u.getScheme()) || (!httpsOnly && "http".equalsIgnoreCase(u.getScheme())));
        } catch (Exception invalid) { return false; }
    }
    static String endpoint(String manifest, String type, String id) {
        return resourceEndpoint(manifest, "stream", type, id);
    }
    static String resourceEndpoint(String manifest, String resource, String type, String id) {
        if (!(resource.equals("stream") || resource.equals("subtitles"))) throw new IllegalArgumentException("invalid_resource");
        if (!web(manifest, true) || !("movie".equals(type) || "series".equals(type))) throw new IllegalArgumentException("invalid_addon");
        URI uri = URI.create(manifest);
        String path = uri.getRawPath();
        if (uri.getRawQuery() != null || uri.getRawFragment() != null || path == null || !path.endsWith("/manifest.json"))
            throw new IllegalArgumentException("invalid_addon");
        try { return manifest.substring(0, manifest.length() - "manifest.json".length()) + resource + "/" + type + "/" + URLEncoder.encode(id, "UTF-8").replace("+", "%20") + ".json"; }
        catch (Exception invalid) { throw new IllegalArgumentException("invalid_addon"); }
    }
}
