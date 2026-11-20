package tv.noiro.launcher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Account-bound progress from optional Noiro cloud or the standalone app's local history. */
final class UpNext {
    static final class Item {
        String type, id, videoId;
        long positionMs, durationMs;
        double progress;
        boolean next;
        String action() { return next ? "Play " + UpNextPolicy.episode(videoId) : "Resume"; }
        String detail() {
            String episode = UpNextPolicy.episode(videoId);
            String text = next ? "Next episode" : durationMs > positionMs && durationMs > 0
                    ? Math.max(1, (durationMs - positionMs + 59999) / 60000) + " min left"
                    : Math.round(progress) + "% watched";
            return episode.isEmpty() ? text : episode + " · " + text;
        }
    }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService artworkIo = Executors.newFixedThreadPool(2);
    private final Runnable changed;
    private final Context context;
    private static final String HIDDEN_PREFS = "up-next-local-hidden";
    private static final Object HIDDEN_LOCK = new Object();
    // Local-only dismissal; never writes watched state back to Stremio or Noiro.tv.
    private static String hiddenKey(AccountState owner) { return "uid:" + owner.uid; }
    static boolean isHidden(Context context, AccountState owner, Item item) {
        if (item == null || owner.uid.isEmpty()) return false;
        Set<String> values = context.getSharedPreferences(HIDDEN_PREFS, Context.MODE_PRIVATE)
                .getStringSet(hiddenKey(owner), java.util.Collections.emptySet());
        return UpNextLocalPolicy.isHidden(values, item.type, item.id);
    }
    static boolean setHidden(Context context, AccountState owner, Item item, int tmdbId, boolean hidden) {
        if (item == null || !NoiroAccount.current().owns(owner.authKey, owner.uid)) return false;
        synchronized (HIDDEN_LOCK) {
            android.content.SharedPreferences prefs = context.getSharedPreferences(HIDDEN_PREFS, Context.MODE_PRIVATE);
            String key = hiddenKey(owner);
            Set<String> values = UpNextLocalPolicy.setHidden(
                    prefs.getStringSet(key, java.util.Collections.emptySet()), item.type, item.id, tmdbId, hidden);
            return prefs.edit().putStringSet(key, values).commit();
        }
    }
    static void clearHiddenForTitle(Context context, AccountState owner, String kind, int tmdbId) {
        if (tmdbId <= 0 || owner.uid.isEmpty() || !NoiroAccount.current().owns(owner.authKey, owner.uid)) return;
        synchronized (HIDDEN_LOCK) {
            android.content.SharedPreferences prefs = context.getSharedPreferences(HIDDEN_PREFS, Context.MODE_PRIVATE);
            String key = hiddenKey(owner);
            Set<String> original = prefs.getStringSet(key, java.util.Collections.emptySet());
            Set<String> values = UpNextLocalPolicy.clearTitle(original, kind, tmdbId);
            if (!values.equals(original)) prefs.edit().putStringSet(key, values).apply();
        }
    }
    private List<Recs.Pick> items = new ArrayList<>();
    private AccountState owner = AccountState.EMPTY;
    private final java.util.Map<String, JSONObject> artwork = new java.util.HashMap<>();
    private final java.util.Set<String> artworkPending = new java.util.HashSet<>();
    private final java.util.Map<String, Long> artworkFailed = new java.util.HashMap<>();
    private JSONArray localEvents = new JSONArray();
    private final java.util.Map<String, List<PlayerSessionPolicy.Episode>> localEpisodes = new java.util.HashMap<>();
    private final java.util.Map<String, Long> localEpisodesUntil = new java.util.HashMap<>();
    private final java.util.Set<String> localEpisodesPending = new java.util.HashSet<>();
    private boolean busy, active, destroyed;
    boolean loaded;
    private int generation;
    private String message = "Loading Up Next…";
    UpNext(Context context, Runnable changed) { WatchProgress.init(context); this.context = context.getApplicationContext(); this.changed = changed; }
    List<Recs.Pick> items() {
        List<Recs.Pick> visible = new ArrayList<>();
        if (!NoiroAccount.current().owns(owner.authKey, owner.uid)) return visible;
        for (Recs.Pick pick : items) {
            if (isHidden(context, owner, pick.continuing)) continue;
            if (pick.id > 0 && Tmdb.isSeen(context, pick.kind, pick.id)) continue;
            visible.add(pick);
        }
        return visible;
    }
    String message() { return message; }
    private void applyArtwork(Recs.Pick pick, JSONObject media) {
        if (media.optString("id").startsWith("tt")) {
            pick.imdb = pick.continuing.id;
            pick.poster = media.optString("poster"); pick.backdrop = media.optString("background");
            pick.logo = media.optString("logo");
            return;
        }
        pick.id = media.optInt("id"); pick.poster = media.optString("poster_path", "");
        pick.backdrop = media.optString("backdrop_path", ""); pick.rating = media.optDouble("vote_average", 0); pick.ratingCount = media.optInt("vote_count");
    }
    // Fetch only visible cards. Continue pointers become playable before artwork
    // lookup, and a slow metadata service cannot block the entire personal feed.
    void enrich(Recs.Pick pick) {
        if (pick.continuing == null || destroyed || !active) return;
        String key = pick.kind + ":" + pick.continuing.id;
        JSONObject hit = artwork.get(key);
        if (hit != null) { applyArtwork(pick, hit); return; }
        if (artworkPending.contains(key) || System.currentTimeMillis() < artworkFailed.getOrDefault(key, 0L)) return;
        artworkPending.add(key); AccountState expected = owner;
        artworkIo.execute(() -> {
            JSONObject media = null;
            try {
                if (localMode(expected)) {
                    media = FreeCatalog.metadata(pick.kind.equals("tv") ? "series" : "movie", pick.continuing.id);
                } else {
                    JSONObject find = Tmdb.get("/find/" + pick.continuing.id + "?external_source=imdb_id");
                    JSONArray matches = find.optJSONArray(pick.kind.equals("tv") ? "tv_results" : "movie_results");
                    if (matches != null && matches.length() > 0) media = matches.optJSONObject(0);
                }
            } catch (Exception unavailable) { /* Resume by IMDb ID remains available. */ }
            final JSONObject result = media;
            main.post(() -> {
                if (destroyed || !NoiroAccount.current().owns(expected.authKey, expected.uid)) return;
                artworkPending.remove(key);
                if (result == null) { artworkFailed.put(key, System.currentTimeMillis() + 60_000); return; }
                artwork.put(key, result);
                for (Recs.Pick item : items) if ((item.kind + ":" + item.continuing.id).equals(key)) applyArtwork(item, result);
                changed.run();
            });
        });
    }
    void resume() { active = true; refresh(); }
    void pause() { active = false; main.removeCallbacks(poll); }
    void destroy() { destroyed = true; generation++; pause(); io.shutdownNow(); artworkIo.shutdownNow(); }
    private final Runnable poll = () -> { if (active) refresh(); };
    private void schedule() { main.removeCallbacks(poll); if (active) main.postDelayed(poll, 30_000); }
    private boolean localMode(AccountState expected) {
        return AppIdentity.standalone(context) && expected.signedIn() && !NoiroAccount.ready();
    }
    private static List<Recs.Pick> picks(JSONArray values) {
        List<Recs.Pick> out = new ArrayList<>();
        for (int i = 0; values != null && i < Math.min(500, values.length()); i++) {
            JSONObject v = values.optJSONObject(i); if (v == null) continue;
            Item item = new Item(); item.type = v.optString("type"); item.id = v.optString("id"); item.videoId = v.optString("videoId", item.id);
            if (!UpNextPolicy.valid(item.type, item.id, item.videoId)) continue;
            item.next = v.optBoolean("upNext"); item.progress = Math.max(0, Math.min(100, v.optDouble("progress", 0)));
            item.positionMs = Math.max(0, v.optLong("positionMs")); item.durationMs = Math.max(0, v.optLong("durationMs"));
            Recs.Pick p = new Recs.Pick(); p.kind = "series".equals(item.type) ? "tv" : "movie";
            p.imdb = item.id; p.title = v.optString("name", item.id); p.continuing = item; p.meta = item.detail();
            out.add(p);
        }
        return out;
    }
    private void publishLocal(AccountState expected) {
        if (destroyed || !NoiroAccount.current().owns(expected.authKey, expected.uid) || !localMode(expected)) return;
        items = picks(LocalContinue.project(localEvents, localEpisodes, System.currentTimeMillis()));
        for (Recs.Pick pick : items) {
            JSONObject hit = artwork.get(pick.kind + ":" + pick.continuing.id);
            if (hit != null) applyArtwork(pick, hit);
        }
        loaded = true;
        message = items.isEmpty() ? "Nothing to continue yet. Start watching a movie or show." : "";
        changed.run();
    }
    private void loadLocalEpisodes(AccountState expected) {
        List<String> completed = LocalContinue.completedSeries(localEvents);
        localEpisodes.keySet().retainAll(completed); localEpisodesUntil.keySet().retainAll(completed);
        long now = System.currentTimeMillis(); int requests = 0;
        for (String imdb : completed) {
            if (requests >= 8) break;
            if (localEpisodesPending.contains(imdb) || now < localEpisodesUntil.getOrDefault(imdb, 0L)) continue;
            requests++; localEpisodesPending.add(imdb);
            artworkIo.execute(() -> {
                JSONObject media = null; List<PlayerSessionPolicy.Episode> roster = null;
                try {
                    media = FreeCatalog.metadata("series", imdb);
                    roster = PlayerEpisodes.fromMetadata(imdb, media);
                } catch (Exception unavailable) { /* Preserve the completed anchor and retry metadata later. */ }
                final JSONObject metadata = media; final List<PlayerSessionPolicy.Episode> result = roster;
                main.post(() -> {
                    if (destroyed || !NoiroAccount.current().owns(expected.authKey, expected.uid)) return;
                    localEpisodesPending.remove(imdb);
                    localEpisodesUntil.put(imdb, System.currentTimeMillis() + (result == null ? 60_000 : 30 * 60_000));
                    if (result != null) {
                        localEpisodes.put(imdb, result);
                        artwork.put("tv:" + imdb, metadata);
                    }
                    publishLocal(expected);
                });
            });
        }
    }
    private void refreshLocal(AccountState expected, int run) {
        io.execute(() -> {
            JSONArray saved = WatchProgress.local(expected);
            main.post(() -> {
                if (destroyed || run != generation) return;
                busy = false;
                if (NoiroAccount.current().owns(expected.authKey, expected.uid) && localMode(expected)) {
                    java.util.Set<String> keys = LocalContinue.mediaKeys(saved);
                    artwork.keySet().retainAll(keys); artworkFailed.keySet().retainAll(keys);
                    localEvents = saved; publishLocal(expected); loadLocalEpisodes(expected);
                }
                schedule();
            });
        });
    }
    private void refresh() {
        AccountState expected = NoiroAccount.current();
        if (!expected.owns(owner.authKey, owner.uid)) {
            owner = expected; items.clear(); artwork.clear(); artworkFailed.clear(); artworkPending.clear();
            localEvents = new JSONArray(); localEpisodes.clear(); localEpisodesUntil.clear(); localEpisodesPending.clear();
            loaded = false; message = "Loading Up Next…"; changed.run();
        }
        if (busy || destroyed || !NoiroAccount.ready() && !localMode(expected)) { schedule(); return; }
        busy = true; final int run = ++generation;
        if (localMode(expected)) { refreshLocal(expected, run); return; }
        io.execute(() -> {
            try {
                // A failed upload stays queued; it must not prevent reading the other devices' progress.
                try { WatchProgress.flush(expected); } catch (Exception pending) { /* Retry next refresh. */ }
                JSONObject response = NoiroAccount.progress(null, expected);
                List<Recs.Pick> loaded = picks(response.optJSONArray("items"));
                boolean unavailable = false; JSONArray sources = response.optJSONArray("sources");
                for (int i = 0; sources != null && i < sources.length(); i++) unavailable |= "unavailable".equals(sources.getJSONObject(i).optString("status"));
                final boolean partial = unavailable;
                main.post(() -> {
                    if (destroyed || run != generation) return;
                    busy = false;
                    if (NoiroAccount.current().owns(expected.authKey, expected.uid)) {
                        UpNext.this.loaded = true;
                        items = loaded; message = loaded.isEmpty() ? partial ? "Couldn't refresh all progress. Try again shortly." : "Nothing to continue yet. Start watching a movie or show." : "";
                        changed.run();
                    }
                    schedule();
                });
            } catch (Exception unavailable) {
                main.post(() -> { if (destroyed || run != generation) return; busy = false;
                    if (NoiroAccount.current().owns(expected.authKey, expected.uid)) { loaded = true; message = "Couldn't refresh Up Next. Check your connection."; changed.run(); } schedule(); });
            }
        });
    }
}
