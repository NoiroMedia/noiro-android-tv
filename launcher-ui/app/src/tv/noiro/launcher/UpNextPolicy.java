package tv.noiro.launcher;

/** Playback pointers contain media identity and progress, never stream URLs. */
final class UpNextPolicy {
    /** A paused/ended screen is not new viewing activity on another device. */
    static final class Checkpoints {
        private long savedPosition = -1, savedDuration = -1;
        boolean shouldRecord(long position, long duration, boolean ended, boolean periodic, boolean playing) {
            if (position < 0 || duration <= 0 || periodic && !playing) return false;
            long effectivePosition = ended ? duration : Math.min(position, duration);
            return effectivePosition != savedPosition || duration != savedDuration;
        }
        void recorded(long position, long duration, boolean ended) {
            savedPosition = ended ? duration : Math.min(position, duration);
            savedDuration = duration;
        }
    }
    static boolean valid(String type, String id, String video) {
        if (id == null || !id.matches("tt[0-9]{5,12}") || video == null) return false;
        return "movie".equals(type) ? video.equals(id)
                : "series".equals(type) && video.matches(id + ":[0-9]{1,3}:[0-9]{1,4}")
                    && Integer.parseInt(video.split(":")[2]) > 0;
    }
    static long start(long position, double percent, long duration, boolean next) {
        if (next) return 0;
        long value = position > 0 ? position : duration > 0 && Double.isFinite(percent)
                ? Math.round(duration * Math.max(0, Math.min(100, percent)) / 100) : 0;
        return duration > 0 ? Math.max(0, Math.min(value, duration - 1000)) : Math.max(0, value);
    }
    static boolean completed(long position, long duration, boolean ended) {
        return ended || duration > 0 && position >= duration * .9;
    }
    static String episode(String video) {
        String[] parts = video.split(":");
        return parts.length == 3 ? String.format(java.util.Locale.US, "S%02d E%02d", Integer.parseInt(parts[1]), Integer.parseInt(parts[2])) : "";
    }
}
