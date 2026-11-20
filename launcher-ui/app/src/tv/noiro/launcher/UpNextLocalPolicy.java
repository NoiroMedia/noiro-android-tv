package tv.noiro.launcher;

import java.util.HashSet;
import java.util.Set;

/** Pure logic for account-scoped, TV-only Continue Watching dismissals. */
final class UpNextLocalPolicy {
    private UpNextLocalPolicy() {}
    private static String prefix(String type, String id) { return type + ":" + id + "|"; }

    static boolean isHidden(Set<String> values, String type, String id) {
        String prefix = prefix(type, id);
        for (String value : values) if (value.startsWith(prefix)) return true;
        return false;
    }

    static Set<String> setHidden(Set<String> source, String type, String id, int tmdbId, boolean hidden) {
        Set<String> result = new HashSet<>(source);
        String prefix = prefix(type, id);
        result.removeIf(value -> value.startsWith(prefix));
        if (hidden) result.add(prefix + Math.max(0, tmdbId));
        return result;
    }

    static Set<String> clearTitle(Set<String> source, String kind, int tmdbId) {
        Set<String> result = new HashSet<>(source);
        if (tmdbId <= 0) return result;
        String type = "tv".equals(kind) ? "series:" : "movie:";
        result.removeIf(value -> value.startsWith(type) && value.endsWith("|" + tmdbId));
        return result;
    }
}
