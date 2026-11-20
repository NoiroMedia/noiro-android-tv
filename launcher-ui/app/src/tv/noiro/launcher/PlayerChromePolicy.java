package tv.noiro.launcher;

/** UI choices and timer arithmetic, independent of Android and the playback engine. */
final class PlayerChromePolicy {
    /** Paused menus may restore playback only while their original intent is still valid. */
    static final class DialogPlayback {
        private long generation;
        long capture(boolean playing) { return playing ? generation : -1; }
        boolean shouldResume(long token) { return token >= 0 && token == generation; }
        void revoke() { generation++; }
    }
    static final float[] SPEEDS = {.25f, .5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f};
    static final String[] ASPECTS = {"Fit", "Fill (crop)", "Stretch"};
    static float speed(float value) {
        for (float allowed : SPEEDS) if (Float.compare(value, allowed) == 0) return allowed;
        return 1f;
    }
    static int aspect(int value) { return value >= 0 && value < ASPECTS.length ? value : 0; }
    static String mime(String value) {
        return value != null && value.matches("(?:audio|video|application)/[A-Za-z0-9.+_-]{1,64}") ? value : "Unknown format";
    }
    static String language(String value) {
        return value != null && !value.equals("und") && value.matches("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8}){0,3}") ? value : "";
    }
    /** Manual tracks survive rebuilds for everyone; automatic language preference requires current access. */
    static String audioLanguage(String saved, String remembered, boolean manual, boolean premium) {
        String selected = language(remembered);
        if (manual && !selected.isEmpty()) return selected;
        if (!premium) return "";
        String preferred = language(saved);
        return preferred.isEmpty() ? selected : preferred;
    }
    static long sleepDeadline(long elapsed, int minutes) {
        if (minutes < 1 || minutes > 180 || elapsed < 0) return 0;
        long delta = minutes * 60_000L;
        return elapsed > Long.MAX_VALUE - delta ? Long.MAX_VALUE : elapsed + delta;
    }
    static boolean sleepExpired(long deadline, long elapsed) { return deadline > 0 && elapsed >= deadline; }
    static long remaining(long position, long duration) {
        return duration > 0 ? Math.max(0, duration - Math.max(0, Math.min(position, duration))) : 0;
    }
    static long wallRemaining(long position, long duration, float speed) {
        return (long)Math.ceil(remaining(position, duration) / (double)speed(speed));
    }
    static String time(long millis) {
        long seconds = Math.max(0, millis) / 1000, minutes = seconds / 60;
        return minutes >= 60 ? String.format(java.util.Locale.US, "%d:%02d:%02d", minutes / 60, minutes % 60, seconds % 60)
                : String.format(java.util.Locale.US, "%d:%02d", minutes, seconds % 60);
    }
}
