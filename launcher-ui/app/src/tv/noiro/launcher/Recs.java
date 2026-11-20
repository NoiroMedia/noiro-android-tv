package tv.noiro.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Home movies and series discovered independently of installed provider apps; playback opens Noiro details. */
final class Recs {
    static final class Pick {
        UpNext.Item continuing;
        int id;
        String logo = "";
        String imdb = "";
        double rating;
        int ratingCount;
        String poster;          // Original portrait artwork, separate from the landscape backdrop.
        String kind;            // "movie" or "tv"
        String title, backdrop, meta = "", app, pkg;
        boolean detailed, localized;
    }

    interface Listener { void onRecs(); }

    private static final String API = "https://api.themoviedb.org/3";
    private static final String IMAGES = "https://image.tmdb.org/t/p/original";
    private static final long LIST_EVERY = 6 * 3600_000L;
    /** Bump when what is saved for each title changes (the saved list is discarded and fetched again). */
    private static final int CACHE_VERSION = 10;
    private static final int MANHA = 0, TARDE = 1, NOITE = 2;
    static final String[] PERIOD_LABEL = {"PARA ESTA MANHÃ", "PARA ESTA TARDE", "PARA ESTA NOITE"};
    static final String[] PERIOD_LABEL_RU = {"ЭТИМ УТРОМ", "СЕГОДНЯ ДНЁМ", "СЕГОДНЯ ВЕЧЕРОМ"};
    static final String[] PERIOD_LABEL_EN = {"THIS MORNING", "THIS AFTERNOON", "TONIGHT"};
    static final String[] PERIOD_LABEL_ES = {"PARA ESTA MAÑANA", "PARA ESTA TARDE", "PARA ESTA NOCHE"};
    static final String[] PERIOD_LABEL_DE = {"HEUTE MORGEN", "HEUTE NACHMITTAG", "HEUTE ABEND"};
    static final String[] PERIOD_LABEL_TR = {"BU SABAH", "BU ÖĞLEDEN SONRA", "BU AKŞAM"};
    /** Service name on TMDB (part of the name, lowercase) → TV app (HomeApps key, package). */
    private static final String[][] SERVICES = {
        {"netflix", "netflix", "com.netflix.ninja"},
        {"prime video", "prime", "com.amazon.amazonvideo.livingroom"},
        {"disney", "disney", "com.disney.disneyplus"},
        {"hulu", "hulu", "com.hulu.livingroomplus"},
        {"paramount", "paramount", "com.cbs.ott"},
        {"peacock", "peacock", "com.peacocktv.peacockandroid"},
        {"globoplay", "globoplay", "com.globo.globotv"},
        {"max", "max", "com.wbd.stream"},
        {"max", "max", "com.hbo.hbonow"},
        {"apple tv", "appletv", "com.apple.atve.androidtv.appletv"},
    };
    private static final Map<String, String> SERVICE_NAME = new HashMap<>();
    static {
        SERVICE_NAME.put("netflix", "Netflix");
        SERVICE_NAME.put("prime", "Prime Video");
        SERVICE_NAME.put("disney", "Disney+");
        SERVICE_NAME.put("hulu", "Hulu");
        SERVICE_NAME.put("paramount", "Paramount+");
        SERVICE_NAME.put("peacock", "Peacock");
        SERVICE_NAME.put("globoplay", "Globoplay");
        SERVICE_NAME.put("max", "Max");
        SERVICE_NAME.put("appletv", "Apple TV");
    }
    /** Country for "what streams where" (config.properties "tmdb_region", e.g. US, GB, BR). */
    private static final String REGION = Config.TMDB_REGION.trim().toUpperCase(Locale.ROOT);

    private static volatile Recs instance;

    /** Lets go of the pictures not on screen, only where the recommendations were loaded (the home's process). */
    static synchronized void trimLoaded() {
        if (instance != null) instance.trim();
    }

    static synchronized Recs get(Context c) {
        if (instance == null) instance = new Recs(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService ratingsIo = Executors.newSingleThreadExecutor();
    private final Map<String, RatingsEntry> ratingCache = new LinkedHashMap<>();
    private final java.util.Set<String> ratingPending = new java.util.HashSet<>();
    private volatile String selectedRatingKey = "", missingRatingsUid = "";
    private volatile long missingRatingsUntil;
    private volatile int ratingsGeneration;
    private static final class RatingsEntry {
        final List<MediaRating> ratings; final long until;
        RatingsEntry(List<MediaRating> ratings,long until){this.ratings=ratings;this.until=until;}
    }
    private final ExecutorService imageIo = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<String, Backdrop> images = new LinkedHashMap<>();   // this hour's, built and ready to draw
    private final Map<String, Long> imageFailures = new HashMap<>();
    private final java.util.Set<String> pending = new java.util.HashSet<>();
    private final Map<String, Bitmap> posters = new LinkedHashMap<>();
    private final Map<String, Bitmap> logos = new LinkedHashMap<>();
    private final Map<String, Long> logoFailures = new HashMap<>();
    private final Map<String, Long> posterFailures = new HashMap<>();
    private volatile List<Pick> picks = new ArrayList<>();
    private volatile int period = -1;
    private volatile long listAt;
    private volatile boolean fetching;
    private volatile String error = "";
    private volatile boolean light;     // light design: builds only the cards (all three tabs' cards fit in memory)
    private volatile long failedAt, keyStamp;
    private volatile String listLanguage = "";   // language used for the saved titles, genres and texts

    private Recs(Context c) {
        context = c;
        cache = c.getSharedPreferences("recs", Context.MODE_PRIVATE);
        if (cache.getInt("version", 0) != CACHE_VERSION) cache.edit().clear().putInt("version", CACHE_VERSION).apply();
        try {
            String saved = cache.getString("picks", null);
            if (saved != null) picks = parse(new JSONArray(saved));
            period = cache.getInt("period", -1);
            listAt = cache.getLong("listAt", 0);
            listLanguage = cache.getString("language", cache.getBoolean("english", false) ? "en" : "pt");
            keyStamp = cache.getLong("keyStamp", 0);
        } catch (Exception ignored) {
        }
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    static boolean hasKey(Context c) {
        return !Prefs.tmdbKey().isEmpty();
    }

    /** Last fetch, for the settings screen: {count, error, at}. Written by the home screen (another process). */
    static JSONObject status(Context c) {
        File f = new File(c.getFilesDir(), "recs-status.json");
        if (!f.exists() || !hasKey(c)) return new JSONObject();
        try (FileInputStream in = new FileInputStream(f)) {
            return new JSONObject(read(in));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void writeStatus() {
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "recs-status.json"))) {
            out.write(new JSONObject().put("count", picks.size()).put("error", error).put("at", System.currentTimeMillis())
                    .toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    String error() { return error; }

    static String periodLabel() {
        return (Ui.tr ? PERIOD_LABEL_TR : Ui.ru ? PERIOD_LABEL_RU : Ui.es ? PERIOD_LABEL_ES : Ui.de ? PERIOD_LABEL_DE : Ui.en ? PERIOD_LABEL_EN : PERIOD_LABEL)
                [period(System.currentTimeMillis())];
    }

    private static int period(long now) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        return hour >= 5 && hour < 12 ? MANHA : hour >= 12 && hour < 18 ? TARDE : NOITE;
    }

    /** This hour's picks (the first is the featured one; the arrows switch to the others). */
    List<Pick> now() {
        return now(null);
    }

    /** This hour's picks, movies only ("movie") or shows only ("tv"); null = both (the home screen tabs). Up to 24 titles for horizontal browsing. */
    List<Pick> now(String kind) {
        List<Pick> list = new ArrayList<>();
        if (!hasKey(context)) return list;    // no TMDB key: no recommendations
        for (Pick p : picks) {
            if (kind == null || kind.equals(p.kind)) list.add(p);
        }
        if (list.isEmpty()) return list;
        long hour = System.currentTimeMillis() / 3600_000L;
        // Never two from the same franchise ("Law & Order" and "Law & Order: Special…").
        List<Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < list.size() && out.size() < 24; i++) {
            Pick p = list.get((int) ((hour * 3 + i) % list.size()));
            if (seen.add(franchise(p.title))) out.add(p);
        }
        return out;
    }

    /** "Law & Order: Special Victims Unit" → "law & order" (what comes before ":" or " - "). */
    private static String franchise(String title) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        int cut = t.indexOf(':');
        if (cut < 0) cut = t.indexOf(" - ");
        return (cut > 0 ? t.substring(0, cut) : t).trim();
    }

    /** Name of the service the pick is on ("Netflix"), or empty. */
    static String serviceName(Pick p) {
        return p == null || p.app == null ? "" : SERVICE_NAME.containsKey(p.app) ? SERVICE_NAME.get(p.app) : p.app;
    }

    /** The pick's ready backdrop (or null: builds it in the background and notifies when it's ready). */
    Backdrop backdrop(Pick p) {
        if (p == null || p.backdrop == null || p.backdrop.isEmpty() || "null".equals(p.backdrop)) return null;
        final String key = p.backdrop;    // the details may swap the image midway
        synchronized (images) {
            Backdrop b = images.get(key);
            if (b != null) return b;
            Long failed = imageFailures.get(key);
            if (failed != null && System.currentTimeMillis() - failed < 60000) return null;
            if (!pending.add(key)) return null;
        }
        imageIo.execute(() -> {
            try {
                loadImage(p);
            } finally {
                synchronized (images) { pending.remove(key); }
            }
        });
        return null;
    }

    /** Artwork uses separate caches: original portrait posters and transparent title logos. */
    Bitmap poster(Pick p) { return artwork(p == null ? "" : p.poster, "posters", posters, posterFailures); }
    Bitmap logo(Pick p) { return artwork(p == null ? "" : p.logo, "logos", logos, logoFailures); }

    private Bitmap artwork(String path, String type, Map<String, Bitmap> cache, Map<String, Long> failures) {
        if (path == null || path.isEmpty() || "null".equals(path)) return null;
        final String key = type + ":" + path;
        synchronized (images) {
            Bitmap ready = cache.get(path);
            if (ready != null) return ready;
            Long failed = failures.get(path);
            if (failed != null && System.currentTimeMillis() - failed < 60000) return null;
            if (!pending.add(key)) return null;
        }
        imageIo.execute(() -> {
            File file = new File(new File(context.getCacheDir(), "recs-hq-" + type), path.replace("/", ""));
            try {
                int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
                int targetWidth = Math.min(780, Math.max(500, screenWidth * 360 / 1920));
                downloadFile(file, "https://image.tmdb.org/t/p/" + (targetWidth > 500 ? type.equals("logos") ? "original" : "w780" : "w500") + path);
                Bitmap bitmap = Images.decode(file, targetWidth, type.equals("posters") ? targetWidth * 3 / 2 : targetWidth / 2);
                if (bitmap == null) throw new IOException("unreadable artwork");
                synchronized (images) {
                    cache.put(path, bitmap);
                    while (cache.size() > 6) cache.remove(cache.keySet().iterator().next());
                    failures.remove(path);
                }
            } catch (IOException | RuntimeException | OutOfMemoryError error) {
                file.delete();
                synchronized (images) { failures.put(path, System.currentTimeMillis()); }
            } finally {
                synchronized (images) { pending.remove(key); }
                notifyListeners();
            }
        });
        return null;
    }

    static void clearRatingsCache() {
        Recs current=instance;
        if(current==null)return;
        synchronized(current.ratingCache){current.ratingsGeneration++;current.ratingCache.clear();current.ratingPending.clear();current.missingRatingsUid="";current.missingRatingsUntil=0;}
        current.notifyListeners();
    }

    /** Only the selected title is queried, after browsing settles. Missing keys/errors keep the catalog score. */
    List<MediaRating> ratings(Pick p) {
        List<MediaRating> fallback=new ArrayList<>();
        MediaRating basic=p!=null&&p.ratingCount>0?MediaRating.of("tmdb",p.rating,p.ratingCount):null;
        if(basic!=null)fallback.add(basic);
        if(p==null || p.id<=0 || !NoiroAccount.ready())return fallback;
        final String uid=NoiroAccount.current().uid,key=uid+":"+key(p);
        selectedRatingKey=key;
        if(uid.equals(missingRatingsUid)&&System.currentTimeMillis()<missingRatingsUntil)return fallback;
        final int generation=ratingsGeneration;
        synchronized(ratingCache){
            RatingsEntry entry=ratingCache.get(key);
            if(entry!=null && entry.until>System.currentTimeMillis())return entry.ratings.isEmpty()?fallback:entry.ratings;
            if(!ratingPending.add(key))return fallback;
        }
        main.postDelayed(() -> ratingsIo.execute(() -> {
            if(generation!=ratingsGeneration||!key.equals(selectedRatingKey)||!uid.equals(NoiroAccount.current().uid)){
                synchronized(ratingCache){if(generation==ratingsGeneration)ratingPending.remove(key);}return;
            }
            List<MediaRating> values=new ArrayList<>();long ttl=60_000;
            try {
                JSONObject response=NoiroAccount.ratings(p.kind,p.id);
                if(!response.optBoolean("configured")&&generation==ratingsGeneration&&uid.equals(NoiroAccount.current().uid)){
                    missingRatingsUid=uid;missingRatingsUntil=System.currentTimeMillis()+15*60_000L;
                }
                JSONArray data=response.optJSONArray("ratings");
                java.util.Set<String> seen=new java.util.HashSet<>();
                for(int i=0;data!=null && i<Math.min(16,data.length());i++){
                    JSONObject row=data.optJSONObject(i);if(row==null||row.isNull("value"))continue;
                    MediaRating value=MediaRating.of(row.optString("source"),row.optDouble("value",Double.NaN),row.optLong("votes",-1));
                    if(value!=null&&RatingMarks.supports(value.source)&&seen.add(value.source))values.add(value);
                }
                if(basic!=null&&values.stream().noneMatch(value->value.source.equals("tmdb")))values.add(basic);
                values.sort((a,b)->Integer.compare(RatingMarks.order(a.source),RatingMarks.order(b.source)));
                ttl=response.has("unavailable")?60_000:15*60_000L;
            }catch(Exception ignored){ /* Ratings never block browsing or expose credentials/provider errors. */ }
            synchronized(ratingCache){
                if(generation==ratingsGeneration&&uid.equals(NoiroAccount.current().uid)){
                    ratingCache.put(key,new RatingsEntry(values,System.currentTimeMillis()+ttl));
                    while(ratingCache.size()>64)ratingCache.remove(ratingCache.keySet().iterator().next());
                    ratingPending.remove(key);
                }
            }
            notifyListeners();
        }),350);
        return fallback;
    }

    private boolean hasImage(Pick p) {
        return p.backdrop != null && imageFile(p).exists();
    }

    private File imageFile(Pick p) {
        return new File(new File(context.getCacheDir(), "recs"), p.backdrop.replace("/", ""));
    }

    /** With a key: fetches the list if stale or the time of day (morning/afternoon/night) changed; then the details. */
    void refreshIfStale() {
        if (!hasKey(context) || fetching) return;
        long now = System.currentTimeMillis();
        long stamp = sourceStamp();   // a new key or catalog policy refetches
        boolean newKey = stamp != keyStamp;
        long sinceFail = now - failedAt;    // negative = the clock went back: don't wait
        if (!newKey && failedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        int p = period(now);
        boolean stale = newKey || p != period || now - listAt > LIST_EVERY || now < listAt || picks.isEmpty()
                || !listLanguage.equals(Ui.language);
        boolean missing = false;
        for (Pick pick : wanted()) missing |= !pick.detailed;
        if (!stale && !missing) return;
        keyStamp = stamp;
        fetching = true;
        io.execute(() -> {
            try {
                if (stale) { fetchList(p); save(); notifyListeners(); }
                // Every title opens Noiro details and Stremio sources, regardless of installed provider apps.
                int failures = 0, tried = 0;
                String last = "";
                for (Pick pick : wanted()) {
                    tried++;
                    try {
                        if (!pick.detailed) fetchDetails(pick);
                        notifyListeners(); // Visible artwork loads on its own queue, without waiting for all details.
                    } catch (Exception e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                }
                save();
                if (tried > 0 && failures == tried) throw new IOException(last);
                error = "";
                failedAt = 0;
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                failedAt = System.currentTimeMillis();
                AppLog.w("recommendations: " + error);
            } finally {
                fetching = false;
                writeStatus();
                notifyListeners();
            }
        });
    }

    /** Home screen design: the ready images are rebuilt for it (the light design uses cards, not the whole photo). */
    void setLight(boolean value) {
        if (value == light) return;
        light = value;
        synchronized (images) {
            images.clear();
            imageFailures.clear();
        }
    }

    /**
     * Screensaver: the picks with an image already on disk, starting with this hour's (the
     * featured one first), never two from the same franchise.
     */
    List<Pick> showcase() {
        List<Pick> out = new ArrayList<>();
        if (!hasKey(context)) return out;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Pick p : wanted()) if (hasImage(p) && seen.add(franchise(p.title))) out.add(p);
        for (Pick p : picks) {
            if (hasImage(p) && seen.add(franchise(p.title))) out.add(p);
        }
        return out;
    }

    /** The pick's original image if it's already on disk (the screensaver loads it in its own way), or null. */
    File readyImage(Pick p) {
        return hasImage(p) ? imageFile(p) : null;
    }

    /**
     * While the screensaver is showing: details and image for every pick in the list (not just this hour's), one at a
     * time in the background, so it has more photos. Not used otherwise.
     */
    void prefetchAll() {
        if (!hasKey(context) || fetching) return;
        long sinceFail = System.currentTimeMillis() - failedAt;
        if (failedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        List<Pick> todo = new ArrayList<>();
        for (Pick p : picks) if (!p.detailed || !hasImage(p)) todo.add(p);
        if (todo.isEmpty()) return;
        fetching = true;
        io.execute(() -> {
            int failures = 0;
            String last = "";
            try {
                // One failing title (gone from TMDB, an image that won't download) doesn't stop the others.
                for (Pick p : todo) {
                    try {
                        if (!p.detailed) fetchDetails(p);
                        download(p);
                    } catch (Exception e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                }
                save();
                if (failures == todo.size()) {
                    error = last;
                    failedAt = System.currentTimeMillis();
                    AppLog.w("recommendations (screensaver): " + last);
                }
            } finally {
                fetching = false;
                notifyListeners();
            }
        });
    }

    /** This hour's picks for the three tabs (Explore, Movies, Shows), without repeats. */
    List<Pick> wanted() {
        List<Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String kind : new String[] {null, "movie", "tv"}) {
            for (Pick p : now(kind)) if (seen.add(key(p))) out.add(p);
        }
        return out;
    }

    /** Movies and shows are numbered separately on TMDB: a pick is identified by type + number. */
    static String key(Pick p) {
        if (p.continuing != null) return "continue:" + p.continuing.videoId;
        return p.kind + ":" + p.id;
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onRecs(); });
    }

    // MARK: - TMDB

    private void fetchList(int p) throws Exception {
        // Catalog discovery is independent of Netflix/Prime/etc. Noiro resolves playback through Stremio.
        String common = "&language=" + lang() + "&include_adult=false&sort_by=popularity.desc";
        List<Pick> hotMovies = candidates("/trending/movie/week?language=" + lang(), "movie");
        List<Pick> hotShows = candidates("/trending/tv/week?language=" + lang(), "tv");
        List<Pick> newMovies = candidates("/discover/movie?vote_count.gte=30&primary_release_date.gte=" + day(-365)
                + "&primary_release_date.lte=" + day(0) + common, "movie");
        List<Pick> newShows = candidates("/discover/tv?vote_count.gte=20&air_date.gte=" + day(-90)
                + "&air_date.lte=" + day(0) + common, "tv");
        if (!Ui.en) {
            hotMovies.sort((a, b) -> Boolean.compare(b.localized, a.localized));
            hotShows.sort((a, b) -> Boolean.compare(b.localized, a.localized));
            newMovies.sort((a, b) -> Boolean.compare(b.localized, a.localized));
            newShows.sort((a, b) -> Boolean.compare(b.localized, a.localized));
        }
        // Trending first, then the new releases; movies and shows alternating, without repeats, up to 48.
        List<Pick> list = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        alternate(list, seen, hotMovies, hotShows, 48);
        alternate(list, seen, newMovies, newShows, 48);
        if (list.isEmpty()) throw new IOException("TMDB returned no recommendations");
        picks = list;
        period = p;
        listAt = System.currentTimeMillis();
        listLanguage = Ui.language;
    }

    /** A failed list must not discard the other working lists (or the existing offline cache). */
    private List<Pick> candidates(String path, String kind) {
        List<Pick> out = new ArrayList<>();
        try {
            JSONArray results = get(path).getJSONArray("results");
            for (int i = 0; i < results.length(); i++) add(out, results.getJSONObject(i), kind);
        } catch (Exception ignored) { }
        return out;
    }

    /** Language of titles and genres; the services come from the region. */
    private static String lang() {
        if (Ui.tr) return "tr-TR";
        if (Ui.ru) return "ru-RU";
        if (Ui.es) return "ES".equals(java.util.Locale.getDefault().getCountry()) ? "es-ES" : "es-MX";
        if (Ui.de) return "de-DE";   // TMDB's de-AT and de-CH have few translations of their own
        return Ui.en ? "en-US" : "pt-BR";
    }

    private static void alternate(List<Pick> out, java.util.Set<String> seen, List<Pick> a, List<Pick> b, int max) {
        for (int i = 0; i < Math.max(a.size(), b.size()) && out.size() < max; i++) {
            if (i < a.size() && seen.add(key(a.get(i)))) out.add(a.get(i));
            if (i < b.size() && out.size() < max && seen.add(key(b.get(i)))) out.add(b.get(i));
        }
    }

    /** The date `offset` days from today, as "2026-10-19" (negative = in the past). */
    private static String day(int offset) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, offset);
        return String.format(Locale.US, "%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    private static void add(List<Pick> list, JSONObject o, String kind) {
        String backdrop = o.optString("backdrop_path", "");
        if (backdrop.isEmpty() || "null".equals(backdrop)) return;
        String field = kind.equals("movie") ? "title" : "name";
        Pick p = new Pick();
        p.id = o.optInt("id");
        p.kind = kind;
        p.title = o.optString(field);
        p.backdrop = backdrop;
        p.poster = o.optString("poster_path", "");
        p.rating = o.optDouble("vote_average", 0);
        p.ratingCount = o.optInt("vote_count");
        p.localized = Ui.language.equals(o.optString("original_language"))
                || !p.title.equalsIgnoreCase(o.optString("original_" + field));
        list.add(p);
    }

    /** Genre, runtime (or seasons) and the service it's on, among the ones the TV has. */
    private void fetchDetails(Pick p) throws Exception {
        JSONObject o = get("/" + p.kind + "/" + p.id
                + "?language=" + lang() + "&append_to_response=watch/providers,images,translations,external_ids&include_image_language=null," + Ui.language + ",en");
        JSONObject ids = o.optJSONObject("external_ids");
        String imdb = ids == null ? "" : ids.optString("imdb_id");
        if (imdb.matches("tt[0-9]{5,12}")) p.imdb = imdb;
        p.title = title(p, o);
        p.rating = o.optDouble("vote_average", 0);
        p.ratingCount = o.optInt("vote_count");
        p.logo = bestLogo(o.optJSONObject("images"));
        p.poster = o.optString("poster_path", p.poster == null ? "" : p.poster);
        String field = p.kind.equals("movie") ? "title" : "name";
        p.localized = Ui.language.equals(o.optString("original_language"))
                || !p.title.equalsIgnoreCase(o.optString("original_" + field));
        String best = bestBackdrop(o.optJSONObject("images"));
        if (best != null) p.backdrop = best;
        List<String> parts = new ArrayList<>();
        JSONArray genres = o.optJSONArray("genres");
        if (p.kind.equals("tv")) parts.add(Ui.tr("Série", "Series"));
        if (genres != null && genres.length() > 0) {
            String name = genres.getJSONObject(0).optString("name");
            parts.add(Ui.en ? name : genre(name));
        }
        if (p.kind.equals("movie")) {
            int minutes = o.optInt("runtime");
            if (minutes > 0 && Ui.tr) parts.add(minutes >= 60 ? (minutes / 60) + " sa " + (minutes % 60) + " dk" : minutes + " dk");
            else if (minutes > 0 && Ui.ru) parts.add(minutes >= 60 ? (minutes / 60) + " ч " + (minutes % 60) + " мин" : minutes + " мин");
            else if (minutes > 0 && Ui.en) parts.add(minutes >= 60 ? (minutes / 60) + "h " + (minutes % 60) + "m" : minutes + "m");
            else if (minutes > 0) parts.add(minutes >= 60 ? (minutes / 60) + " h " + (minutes % 60) + " min" : minutes + " min");
        } else {
            int seasons = o.optInt("number_of_seasons");
            if (seasons > 0 && Ui.tr) parts.add(seasons + " sezon");
            else if (seasons > 0 && Ui.ru) parts.add(Russian.count(seasons, "сезон", "сезона", "сезонов"));
            else if (seasons > 0 && Ui.en) parts.add(seasons == 1 ? "1 season" : seasons + " seasons");
            else if (seasons > 0 && Ui.de) parts.add(seasons == 1 ? "1 Staffel" : seasons + " Staffeln");
            else if (seasons > 0) parts.add(seasons == 1 ? "1 temporada" : seasons + " temporadas");
        }
        JSONObject wp = o.optJSONObject("watch/providers");
        JSONObject br = wp == null ? null : wp.optJSONObject("results") == null ? null : wp.optJSONObject("results").optJSONObject(REGION);
        JSONArray flat = br == null ? null : br.optJSONArray("flatrate");
        for (int i = 0; flat != null && i < flat.length() && p.app == null; i++) {
            JSONObject fp = flat.getJSONObject(i);
            String pkg = Providers.app(context, fp.optInt("provider_id"), fp.optString("provider_name"));
            String[] s = pkg == null ? null : new String[] {fp.optString("provider_name"), fp.optString("provider_name"), pkg};
            if (s != null) {
                p.app = s[1];
                p.pkg = s[2];
            }
        }
        if (p.app != null) parts.add(SERVICE_NAME.containsKey(p.app) ? SERVICE_NAME.get(p.app) : p.app);
        p.meta = String.join(" · ", parts);
        p.detailed = true;
    }

    /**
     * Without a title in the TV's language TMDB answers with the original one, so a Korean series on a German TV reads
     * "닥터X: 하얀 마피아의 시대". Then the English title, when TMDB has one (one more request, only for those).
     */
    private String title(Pick p, JSONObject o) {
        String field = p.kind.equals("movie") ? "title" : "name";
        String own = o.optString(field, p.title);
        String origTitle = o.optString("original_" + field);
        String original = o.optString("original_language");
        if (!Ui.en && (own.isEmpty() || own.equals(origTitle))) {
            JSONObject trObj = o.optJSONObject("translations");
            JSONArray trList = trObj == null ? null : trObj.optJSONArray("translations");
            for (int i = 0; trList != null && i < trList.length(); i++) {
                JSONObject item = trList.optJSONObject(i);
                if (item != null && Ui.language.equalsIgnoreCase(item.optString("iso_639_1"))) {
                    JSONObject data = item.optJSONObject("data");
                    String localized = data == null ? "" : data.optString(field).trim();
                    if (!localized.isEmpty()) {
                        own = localized;
                        break;
                    }
                }
            }
        }
        if (Ui.en || own.isEmpty() || !own.equals(origTitle)
                || Ui.language.equals(original) || "en".equals(original)) return own;
        try {
            String english = get("/" + p.kind + "/" + p.id + "?language=en-US").optString(field);
            return english.isEmpty() ? own : english;
        } catch (Exception e) {
            return own;
        }
    }

    /** TV genres that TMDB returns in English even in pt-BR. */
    private static final String[][] GENRES_PT = {
        {"Action & Adventure", "Ação e Aventura"}, {"Sci-Fi & Fantasy", "Ficção Científica e Fantasia"},
        {"War & Politics", "Guerra e Política"}, {"Kids", "Infantil"}, {"News", "Notícias"}, {"Soap", "Novela"},
        {"Talk", "Entrevistas"},
    };
    private static final String[][] GENRES_ES = {
        {"Action & Adventure", "Acción y Aventura"}, {"Sci-Fi & Fantasy", "Ciencia ficción y Fantasía"},
        {"War & Politics", "Guerra y Política"}, {"Kids", "Infantil"}, {"News", "Noticias"},
        {"Soap", "Telenovela"}, {"Talk", "Entrevistas"},
    };
    private static final String[][] GENRES_DE = {
        {"Action & Adventure", "Action & Abenteuer"}, {"War & Politics", "Krieg & Politik"}, {"Kids", "Kinder"},
        {"News", "Nachrichten"}, {"Soap", "Seifenoper"}, {"Talk", "Talkshow"},
    };
    private static final String[][] GENRES_TR = {
        {"Action & Adventure", "Aksiyon & Macera"}, {"Sci-Fi & Fantasy", "Bilim Kurgu & Fantastik"},
        {"War & Politics", "Savaş & Politika"}, {"Kids", "Çocuk"}, {"News", "Haberler"},
        {"Soap", "Dizi"}, {"Talk", "Sohbet & Talk Show"},
    };

    private static String genre(String name) {
        if (Ui.ru) return Russian.text(name);
        for (String[] g : Ui.tr ? GENRES_TR : Ui.es ? GENRES_ES : Ui.de ? GENRES_DE : GENRES_PT) if (g[0].equalsIgnoreCase(name)) return g[1];
        return name;
    }

    /**
     * The title's best backdrop: no text written over it (our own text goes on top of it), the closest to 1920 wide
     * (larger only costs download, disk and decoding) and, on a tie, the highest rated.
     */
    /** Prefer the selected language, then English and language-neutral PNG logos. */
    static String bestLogo(JSONObject images) {
        JSONArray list = images == null ? null : images.optJSONArray("logos");
        String best = ""; double score = -1;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject logo = list.optJSONObject(i);
            if (logo == null) continue;
            String path = logo.optString("file_path"), language = logo.optString("iso_639_1");
            if (!path.toLowerCase(Locale.ROOT).endsWith(".png")) continue;
            int priority = Ui.language.equals(language) ? 3 : "en".equals(language) ? 2
                    : logo.isNull("iso_639_1") || language.isEmpty() ? 1 : 0;
            if (priority == 0) continue;
            double rank = priority * 1000000d + Math.min(logo.optInt("width"), 2000) * 100
                    + logo.optDouble("vote_average", 0);
            if (rank > score) { score = rank; best = path; }
        }
        return best;
    }

    static String ratingText(Pick p) {
        return p == null || p.ratingCount <= 0 || !Double.isFinite(p.rating) || p.rating <= 0 || p.rating > 10
                ? "" : String.format(Locale.US, "★ %.1f / 10", p.rating);
    }

    private static String bestBackdrop(JSONObject images) {
        JSONArray list = images == null ? null : images.optJSONArray("backdrops");
        String best = null;
        double bestScore = -1;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject b = list.optJSONObject(i);
            if (b == null || b.optString("file_path", "").isEmpty()) continue;
            boolean textless = b.isNull("iso_639_1");
            int width = b.optInt("width");
            double score = (textless ? 1e7 : 0) + Math.min(width, 1920) * 100 - Math.max(0, width - 1920) + b.optDouble("vote_average", 0);
            if (score > bestScore) {
                bestScore = score;
                best = b.optString("file_path");
            }
        }
        return best;
    }

    private String[] service(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        String[] first = null;
        for (String[] s : SERVICES) {
            if (!(s[0].equals("max") ? n.matches(".*\\bmax\\b.*") : n.contains(s[0]))) continue;
            if (installed(s[2])) return s;
            if (first == null) first = s;
        }
        return first;
    }

    private boolean installed(String pkg) {
        return HomeApps.launchIntent(context.getPackageManager(), pkg) != null;
    }

    private long sourceStamp() {
        return Prefs.tmdbKey().hashCode() * 31L + CACHE_VERSION;
    }

    private JSONObject get(String path) throws IOException, org.json.JSONException {
        String key = readKey();
        if (key == null) throw new IOException("no TMDB key");
        boolean bearer = TmdbCredential.bearer(key);   // read access token (v4) or API key (v3)
        String url = API + path + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + URLEncoder.encode(key, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/json");
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        try {
            int code = c.getResponseCode();
            if (code == 401) throw new IOException("TMDB rejected the key");
            if (code != 200) throw new IOException("TMDB returned HTTP " + code);
            return new JSONObject(read(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    private String readKey() {
        String key = Prefs.tmdbKey();
        return key.isEmpty() ? null : key;
    }

    // MARK: - Images (TMDB originals on disk, up to 24 so the whole list fits; this hour's kept ready in memory)

    private void download(Pick p) throws IOException {
        downloadFile(imageFile(p), IMAGES + p.backdrop);
    }

    private synchronized void downloadFile(File f, String url) throws IOException {
        if (f.exists()) return;
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        File part = new File(f.getPath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(f)) throw new IOException("couldn't save the image");
        prune(f.getParentFile());
    }

    /** Reads the original (≥ 1920 wide) and builds the backdrop (Backdrop: cards plus frosted and glass versions). */
    private void loadImage(Pick p) {
        String requestedPath = p.backdrop;
        synchronized (images) {
            if (images.containsKey(p.backdrop)) return;
        }
        File f = imageFile(p);
        try {
            download(p);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), bounds);
            int physicalWidth = context.getResources().getDisplayMetrics().widthPixels;
            int renderWidth = BackdropQualityPolicy.renderWidth(physicalWidth, Runtime.getRuntime().maxMemory());
            Bitmap b = Images.decode(f, renderWidth, renderWidth*9/16);
            if (b == null) throw new IOException("unreadable image");
            AppLog.w("backdrop \u201c" + p.title + "\u201d: " + bounds.outWidth + "×" + bounds.outHeight);
            boolean forLight = light;
            Backdrop backdrop = Backdrop.make(b, forLight, physicalWidth);
            if (forLight != light) return;   // the design changed midway: this one is stale
            synchronized (images) {
                images.put(p.backdrop, backdrop);
                imageFailures.remove(requestedPath);
                // Keep only a few 4K light-card pairs: their textures are ~4x the 1080p size.
                int capacity = BackdropQualityPolicy.cacheLimit(forLight, renderWidth);
                while (images.size() > capacity) images.remove(images.keySet().iterator().next());
            }
            notifyListeners();
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            synchronized (images) { imageFailures.put(requestedPath, System.currentTimeMillis()); }
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            AppLog.w("recommendation image: " + e.getMessage());
        }
    }

    void trim() {
        // Explore's picks stay: the home screen opens on that tab, so its cards are never gray when you come back.
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (Pick p : now(null)) if (p.backdrop != null) keep.add(p.backdrop);
        synchronized (images) {
            images.keySet().retainAll(keep);
            imageFailures.clear();
            posters.clear();
            posterFailures.clear();
            logos.clear();
            logoFailures.clear();
        }
    }

    private static void prune(File dir) {
        // A download cut off when the system stopped the app leaves its .part behind.
        long stale = System.currentTimeMillis() - 10 * 60_000L;
        File[] parts = dir.listFiles((d, name) -> name.endsWith(".part"));
        for (int i = 0; parts != null && i < parts.length; i++) {
            //noinspection ResultOfMethodCallIgnored
            if (parts[i].lastModified() < stale) parts[i].delete();
        }
        File[] files = dir.listFiles((d, name) -> !name.endsWith(".part"));
        if (files == null || files.length <= 48) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - 48; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    // MARK: - List cache

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (Pick p : picks) {
                a.put(new JSONObject().put("id", p.id).put("kind", p.kind).put("title", p.title).put("backdrop", p.backdrop)
                        .put("poster", p.poster == null ? "" : p.poster).put("logo", p.logo).put("imdb", p.imdb)
                        .put("rating", p.rating).put("ratingCount", p.ratingCount).put("meta", p.meta).put("app", p.app == null ? "" : p.app).put("pkg", p.pkg == null ? "" : p.pkg)
                        .put("detailed", p.detailed));
            }
            cache.edit().putString("picks", a.toString()).putInt("period", period).putLong("listAt", listAt)
                    .putString("language", listLanguage)
                    .putLong("keyStamp", keyStamp).putInt("version", CACHE_VERSION).apply();
        } catch (Exception ignored) {
        }
    }

    private static List<Pick> parse(JSONArray a) {
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Pick p = new Pick();
            p.id = o.optInt("id");
            p.kind = o.optString("kind");
            p.title = o.optString("title");
            p.backdrop = o.optString("backdrop");
            p.poster = o.optString("poster");
            p.logo = o.optString("logo");
            p.imdb = o.optString("imdb");
            p.rating = o.optDouble("rating", 0);
            p.ratingCount = o.optInt("ratingCount");
            p.meta = o.optString("meta");
            p.app = o.optString("app").isEmpty() ? null : o.optString("app");
            p.pkg = o.optString("pkg").isEmpty() ? null : o.optString("pkg");
            p.detailed = o.optBoolean("detailed");
            out.add(p);
        }
        return out;
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
