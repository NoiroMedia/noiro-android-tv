package tv.noiro.launcher;

/** Pure playback rules; secrets and URLs are never error messages. */
final class PlaybackPolicy {
    static long seek(long position, long delta, long duration) {
        long next = Math.max(0, position + delta);
        return duration > 0 ? Math.min(next, Math.max(0, duration - 1000)) : next;
    }
    static boolean allowedHeader(String name, String value) {
        return name != null && value != null && value.length() <= 2048 && !value.contains("\r") && !value.contains("\n")
                && (name.equalsIgnoreCase("User-Agent") || name.equalsIgnoreCase("Referer") || name.equalsIgnoreCase("Origin"));
    }
    static String message(int code) {
        if (code == 2004) return "The source rejected this request or is no longer available. Try another stream.";
        if (code == 2001 || code == 2002) return "Couldn't reach this stream. Check your connection or choose another source.";
        if (code >= 3000 && code < 5000) return "This source's video or audio format isn't supported on this TV. Choose another stream or open it in Stremio.";
        return "This stream couldn't play. Retry, choose another source or open it in Stremio.";
    }
}
