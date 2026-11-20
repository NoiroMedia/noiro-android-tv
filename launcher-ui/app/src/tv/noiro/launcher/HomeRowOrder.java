package tv.noiro.launcher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Personal progress follows activity order; discovery rows keep their browsing position. */
final class HomeRowOrder {
    private HomeRowOrder() {}

    static <T> List<T> reconcile(String slot, List<T> previous, List<T> incoming, Function<T, String> key) {
        Map<String, T> remaining = new LinkedHashMap<>();
        for (T item : incoming) remaining.putIfAbsent(key.apply(item), item);
        List<T> ordered = new ArrayList<>();
        if (!"continue".equals(slot)) {
            for (T item : previous) {
                T updated = remaining.remove(key.apply(item));
                if (updated != null) ordered.add(updated);
            }
        }
        ordered.addAll(remaining.values());
        return ordered;
    }
}
