package tv.noiro.launcher;

/** Select source detail by rendered size; cap each decode to one 4K frame. */
final class ArtworkPolicy {
    static int[] requestDimensions(int width, int height, boolean catalog) {
        if (catalog && width >= 200 && width > height) {
            // Preserve fine lettering on landscape covers, including 1080p TV
            // app surfaces enlarged by a 4K display. Decode memory stays bounded.
            int targetWidth = Math.min(1920, Math.max(1280, width * 2));
            int targetHeight = Math.min(1080, Math.round(targetWidth * height / (float) width));
            return new int[]{targetWidth, Math.max(1, targetHeight)};
        }
        return new int[]{Math.min(3840, Math.round(width * 1.1f)),
                Math.min(2160, Math.round(height * 1.1f))};
    }

    static String url(String url, int width, boolean catalog) {
        if (catalog && width > 780 && url != null && url.startsWith("https://image.tmdb.org/t/p/")) {
            return url.replaceFirst("/t/p/[^/]+/", "/t/p/original/");
        }
        return url(url, width);
    }

    static String url(String url, int width) {
        if (url == null || !url.startsWith("https://image.tmdb.org/t/p/")) return url;
        // Deliberately blurred Explore backgrounds retain their small source.
        if (url.contains("/w300/") || url.contains("/w92/")) return url;
        String size;
        if (url.contains("/original/")) size = url.endsWith(".png") ? (width > 500 ? "original" : "w500") : width > 1280 ? "original" : "w1280";
        else size = width <= 185 ? "w185" : width <= 342 ? "w342" : width <= 500 ? "w500" : "w780";
        return url.replaceFirst("/t/p/[^/]+/", "/t/p/" + size + "/");
    }
    static int[] dimensions(int width, int height, int targetW, int targetH, long heap) {
        double scale = Math.min(1d, Math.max(targetW / (double)width, targetH / (double)height));
        long budget = Math.min(3840L * 2160, Math.max(1024L * 1024, heap / 32));
        scale = Math.min(scale, Math.sqrt(budget / ((double)width * height)));
        return new int[]{Math.max(1,(int)Math.floor(width*scale)), Math.max(1,(int)Math.floor(height*scale))};
    }
}
