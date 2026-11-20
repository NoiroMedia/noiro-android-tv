package tv.noiro.launcher;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Projects saved viewing activity without changing recency or inventing episode successors. */
final class LocalContinue {
    private LocalContinue() {}

    private static final class Event {
        String type, id, video, name;
        long position, duration, at;
        boolean completed;
    }

    private static List<Event> events(JSONArray saved) {
        List<Event> out = new ArrayList<>();
        for (int i = 0; saved != null && i < Math.min(500, saved.length()); i++) {
            JSONObject doc = saved.optJSONObject(i);
            JSONObject item = doc == null ? null : doc.optJSONObject("item");
            JSONObject state = item == null ? null : item.optJSONObject("state");
            if (state == null) continue;
            Event event = new Event();
            event.type = item.optString("type"); event.id = item.optString("_id");
            event.video = state.optString("video_id");
            if (!UpNextPolicy.valid(event.type, event.id, event.video)) continue;
            event.name = item.optString("name", event.id);
            event.duration = state.optLong("duration");
            event.position = Math.max(0, Math.min(event.duration, state.optLong("timeOffset")));
            event.at = doc.optLong("eventAt");
            if (event.duration <= 0 || event.at <= 0) continue;
            event.completed = doc.optBoolean("completed") || UpNextPolicy.completed(event.position, event.duration, false);
            out.add(event);
        }
        out.sort(Comparator.comparingLong((Event e) -> e.at).reversed().thenComparing(e -> e.type + ":" + e.id));
        return out;
    }

    static List<String> completedSeries(JSONArray saved) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (Event event : events(saved)) if (event.completed && "series".equals(event.type)) ids.add(event.id);
        return new ArrayList<>(ids);
    }

    static java.util.Set<String> mediaKeys(JSONArray saved) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (Event event : events(saved)) keys.add(("series".equals(event.type) ? "tv:" : "movie:") + event.id);
        return keys;
    }

    static JSONArray project(JSONArray saved, Map<String, List<PlayerSessionPolicy.Episode>> rosters, long now) {
        JSONArray out = new JSONArray();
        for (Event event : events(saved)) {
            String video = event.video;
            boolean next = false;
            long position = event.position, duration = event.duration;
            if (event.completed) {
                if (!"series".equals(event.type)) continue;
                List<PlayerSessionPolicy.Episode> roster = rosters.get(event.id);
                if (roster == null) continue;
                PlayerSessionPolicy.Episode successor = PlayerSessionPolicy.adjacent(roster, video, 1, now);
                if (successor == null) continue;
                video = successor.id; next = true; position = 0; duration = 0;
            } else if (position == 0) continue;
            try {
                out.put(new JSONObject().put("type", event.type).put("id", event.id).put("videoId", video)
                        .put("name", event.name).put("upNext", next).put("positionMs", position)
                        .put("durationMs", duration).put("progress", duration > 0 ? 100.0 * position / duration : 0)
                        .put("eventAt", event.at));
            } catch (Exception malformed) { /* Ignore one bad local record; keep the remaining feed. */ }
        }
        return out;
    }
}
