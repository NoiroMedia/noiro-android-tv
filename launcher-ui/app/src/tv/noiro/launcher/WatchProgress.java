package tv.noiro.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Durable local history and a separate bounded retry queue for optional website progress. */
final class WatchProgress {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Object FLUSH = new Object();
    private static SharedPreferences store;
    private static SharedPreferences history;
    private static boolean standalone;
    static synchronized void init(Context context) {
        standalone = AppIdentity.standalone(context);
        if (store == null) store = context.getApplicationContext().getSharedPreferences("watch-progress", Context.MODE_PRIVATE);
        if (history == null) history = context.getApplicationContext().getSharedPreferences("watch-progress-local", Context.MODE_PRIVATE);
    }
    private static String scope(AccountState owner) throws Exception {
        byte[] bytes = java.security.MessageDigest.getInstance("SHA-256")
                .digest((owner.uid + "\n" + owner.authKey).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder key = new StringBuilder(); for (byte b : bytes) key.append(String.format(java.util.Locale.US, "%02x", b & 255));
        return key.toString();
    }
    static synchronized void clear() { if (store != null) store.edit().clear().apply(); }
    // Local history belongs to a Stremio UID, not its rotating credential. Signing out
    // removes the session and outbox; history is inaccessible until that UID signs in again.
    static synchronized JSONArray local(AccountState owner) {
        JSONArray out = new JSONArray();
        if (history == null || !NoiroAccount.current().owns(owner.authKey, owner.uid)) return out;
        try {
            JSONObject saved = new JSONObject(history.getString("uid:" + owner.uid, "{}"));
            java.util.Iterator<String> keys = saved.keys();
            while (keys.hasNext() && out.length() < 500) {
                JSONObject event = saved.optJSONObject(keys.next());
                if (event != null) out.put(event);
            }
        } catch (Exception unavailable) { /* A malformed local record cannot prevent playback. */ }
        return out;
    }
    private static void bound(JSONObject values, int limit) throws Exception {
        while (values.length() > limit) {
            String oldest = null; long at = Long.MAX_VALUE;
            java.util.Iterator<String> keys = values.keys();
            while (keys.hasNext()) {
                String key = keys.next(); JSONObject event = values.optJSONObject(key);
                long time = event == null ? 0 : event.optLong("eventAt");
                if (oldest == null || time < at) { at = time; oldest = key; }
            }
            values.remove(oldest);
        }
    }
    static boolean record(Context context, AccountState owner, String type, String video, String title,
                       long position, long duration, boolean ended) {
        if (!NoiroAccount.current().owns(owner.authKey, owner.uid) || duration <= 0 || position < 0) return false;
        String id = video.split(":")[0];
        if (!UpNextPolicy.valid(type, id, video)) return false;
        init(context);
        try {
            boolean complete = UpNextPolicy.completed(position, duration, ended);
            JSONObject state = new JSONObject().put("video_id", video).put("timeOffset", ended ? duration : Math.min(position, duration))
                    .put("duration", duration).put("flaggedWatched", "movie".equals(type) && complete ? 1 : 0);
            JSONObject event = new JSONObject().put("eventId", java.util.UUID.randomUUID().toString()).put("eventAt", System.currentTimeMillis())
                    .put("item", new JSONObject().put("_id", id).put("type", type).put("name", title == null ? id : title).put("state", state));
            synchronized (WatchProgress.class) {
                if (!NoiroAccount.current().owns(owner.authKey, owner.uid)) return false;
                if (AppIdentity.standalone(context)) {
                    String localKey = "uid:" + owner.uid;
                    JSONObject saved = new JSONObject(history.getString(localKey, "{}"));
                    saved.put(type + ":" + id, new JSONObject(event.toString()).put("completed", complete));
                    bound(saved, 500);
                    if (!history.edit().putString(localKey, saved.toString()).commit()) return false;
                    if (!NoiroAccount.ready()) return true;
                }
                String key = scope(owner); JSONObject queue = new JSONObject(store.getString(key, "{}"));
                queue.put(type + ":" + id, event);
                // One newest event per title; never keep an unbounded history.
                bound(queue, 100);
                if (!store.edit().putString(key, queue.toString()).commit()) return false;
            }
            IO.execute(() -> { try { flush(owner); } catch (Exception unavailable) { /* Retry on Home refresh or next playback checkpoint. */ } });
            return true;
        } catch (Exception unavailable) { return false; /* Playback is independent of storage availability. */ }
    }
    static void flush(AccountState owner) throws Exception {
        synchronized (FLUSH) {
            if (store == null || !NoiroAccount.current().owns(owner.authKey, owner.uid)) return;
            if (standalone && !NoiroAccount.ready()) return;
            String key = scope(owner); JSONArray changes = new JSONArray();
            synchronized (WatchProgress.class) {
                JSONObject queue = new JSONObject(store.getString(key, "{}")); java.util.Iterator<String> keys = queue.keys();
                while (keys.hasNext() && changes.length() < 20) changes.put(queue.getJSONObject(keys.next()));
            }
            if (changes.length() == 0) return;
            if (!NoiroAccount.current().owns(owner.authKey, owner.uid)) return;
            if (standalone && !NoiroAccount.ready()) return;
            JSONObject response = NoiroAccount.progress(new JSONObject().put("changes", changes), owner);
            JSONArray accepted = response.optJSONArray("accepted");
            if (accepted == null || !NoiroAccount.current().owns(owner.authKey, owner.uid)) return;
            synchronized (WatchProgress.class) {
                JSONObject queue = new JSONObject(store.getString(key, "{}"));
                for (int i = 0; i < changes.length(); i++) {
                    JSONObject event = changes.getJSONObject(i), item = event.getJSONObject("item"); String name = item.getString("type") + ":" + item.getString("_id");
                    for (int j = 0; j < accepted.length(); j++) if (event.getString("eventId").equals(accepted.getString(j))
                            && queue.optJSONObject(name) != null && event.getString("eventId").equals(queue.getJSONObject(name).optString("eventId"))) queue.remove(name);
                }
                store.edit().putString(key, queue.toString()).commit();
            }
        }
    }
}
