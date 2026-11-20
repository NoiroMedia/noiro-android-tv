package tv.noiro.launcher;

import android.content.Context;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Read-only projection of the account's published Studio Home and selected Plex rows. */
final class StudioHome {
    private static final String API = "https://noiro.tv/api/stremio-for-kodi/v1/";
    private static final int ROW_LIMIT = 60;
    private static final ExecutorService DISCOVER_ID_LOOKUPS = Executors.newFixedThreadPool(3);

    static final class Profile {
        final String id, name;
        Profile(String id, String name) { this.id = id; this.name = name; }
    }
    static final class Card {
        final String id, name, type, image, background, logo, detail, summary;
        final boolean landscape, portraitArtwork;
        final JSONObject entry, metadata;
        final Tmdb.Title savedTitle;

        Card(Tmdb.Title title, String poster, String backdrop) {
            this(title, poster, backdrop, savedMetadata(title));
        }

        Card(Tmdb.Title title, String poster, String backdrop, JSONObject metadata) {
            this("tmdb:" + title.id, title.name, title.kind.equals("tv") ? "series" : title.kind,
                    poster, backdrop, "", "", title.overview, false, false, null, metadata, title);
        }

        Card(String id, String name, String type, String image, String background, String logo, String detail,
             String summary, boolean landscape, JSONObject entry) {
            this(id, name, type, image, background, logo, detail, summary, landscape, false, entry, null);
        }

        Card(String id, String name, String type, String image, String background, String logo, String detail,
             String summary, boolean landscape, boolean portraitArtwork, JSONObject entry, JSONObject metadata) {
            this(id, name, type, image, background, logo, detail, summary, landscape, portraitArtwork, entry, metadata, null);
        }

        private Card(String id, String name, String type, String image, String background, String logo, String detail,
                     String summary, boolean landscape, boolean portraitArtwork, JSONObject entry, JSONObject metadata,
                     Tmdb.Title savedTitle) {
            this.savedTitle = savedTitle;
            this.id = id;
            this.name = name;
            this.type = type;
            this.image = image;
            this.background = background;
            this.logo = logo;
            this.detail = detail;
            this.summary = summary;
            this.landscape = landscape;
            this.portraitArtwork = portraitArtwork;
            this.entry = entry;
            this.metadata = metadata;
        }
        String key() { return type + ":" + id; }
        boolean folder() { return entry != null || type.equals("service") || type.equals("genre"); }
        boolean title() { return type.equals("movie") || type.equals("series"); }
    }
    static final class Row {
        final String name;
        final boolean discover;
        String catalogId = "";
        boolean freeToWatch;
        final List<Card> cards = new ArrayList<>();
        Row(String name) { this(name, false); }
        Row(String name, boolean discover) {
            this.name = name;
            this.discover = discover;
        }
    }
    static final class Home {
        final List<Row> rows = new ArrayList<>();
        final List<Card> catalogs = new ArrayList<>();
        final Map<Object, Integer> order = new LinkedHashMap<>();

        boolean hasPublishedRows() { return !order.isEmpty(); }
    }

    private StudioHome() {}

    static String selection(Context c, AccountState owner) {
        return c.getSharedPreferences("studio-home", Context.MODE_PRIVATE).getString(owner.uid, "main");
    }

    static void select(Context c, AccountState owner, String id) {
        if (owner.signedIn() && validProfile(id) && current(owner))
            c.getSharedPreferences("studio-home", Context.MODE_PRIVATE).edit().putString(owner.uid, id).apply();
    }

    static boolean current(AccountState owner) {
        return NoiroAccount.current().owns(owner.authKey, owner.uid);
    }

    static List<Profile> profiles(AccountState owner) throws Exception {
        JSONObject payload = request(owner, "studio-profiles", "");
        if (!owner.uid.equals(payload.optString("stremioUid"))) throw new AccountHttp.Failure("identity_mismatch");
        List<Profile> profiles = new ArrayList<>();
        for (JSONObject item : objects(payload.optJSONArray("profiles"))) {
            String id = item.optString("id"), name = value(item, "name");
            if (validProfile(id) && !name.isEmpty()) profiles.add(new Profile(id, name));
        }
        return profiles;
    }

    static Home read(AccountState owner, String profile) throws Exception {
        return studioRows(request(owner, "studio-home", query("viewerId", profile)));
    }

    static Home studioRows(JSONObject response) {
        Home home = new Home();
        if (!response.optBoolean("active")) return home;
        Map<String, Row> collections = new LinkedHashMap<>();
        Map<String, JSONObject> oldFolders = new LinkedHashMap<>();
        JSONObject runtime = response.optJSONObject("runtimeProfile");
        if (runtime != null) {
            for (JSONObject collection : objects(runtime.optJSONArray("collections"))) {
                for (JSONObject folder : objects(collection.optJSONArray("folders"))) {
                    JSONObject metadata = new JSONObject();
                    try {
                        metadata.put("id", collection.optString("id")).put("name", collection.optString("name"))
                                .put("folder", folder.optString("name")).put("cover", folder.optString("cover"))
                                .put("logo", folder.optString("logo"));
                    } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
                    oldFolders.put("studio-folder:" + folder.optString("id"), metadata);
                }
            }
        }
        for (JSONObject entry : objects(response.optJSONArray("rows"))) {
            String id = value(entry, "id"), label = value(entry, "label");
            if (id.isEmpty() || label.isEmpty()) continue;
            JSONObject collection = entry.optJSONObject("collection");
            if (collection == null) collection = oldFolders.get(id);
            if (collection == null) {
                Card card = new Card(id, label, "catalog", "", "", "", "", "", landscape(entry), entry);
                home.catalogs.add(card);
                home.order.put(card, home.order.size());
                continue;
            }
            String group = value(collection, "id"), name = value(collection, "name");
            String folder = value(collection, "folder");
            if (group.isEmpty() || name.isEmpty() || folder.isEmpty()) continue;
            Row row = collections.get(group);
            if (row == null) {
                row = new Row(name);
                row.catalogId = group;
                collections.put(group, row);
                home.rows.add(row);
                home.order.put(row, home.order.size());
            }
            row.cards.add(new Card(id, folder, "folder", art(collection, "cover"), art(collection, "cover"),
                    art(collection, "logo"), "", "", true, entry));
        }
        return home;
    }

    static Row catalog(AccountState owner, String profile, Card card) throws Exception {
        Map<String, List<Card>> sources = new LinkedHashMap<>();
        JSONArray ids = card.entry.optJSONArray("sourceRows");
        boolean failed = false;
        for (int i = 0; ids != null && i < ids.length(); i++) {
            String id = ids.optString(i);
            if (id.isEmpty() || sources.containsKey(id) || !current(owner)) continue;
            try {
                JSONObject data = request(owner, "studio-home", query("viewerId", profile)
                        + "&" + query("row", card.id) + "&" + query("sourceId", id));
                if (!data.optString("row").equals(card.id) || !data.optString("sourceId").equals(id)) continue;
                if (data.optBoolean("unavailable")) failed = true;
                sources.put(id, titles(data.optJSONArray("items"), landscape(card.entry)));
            } catch (AccountHttp.Failure e) {
                failed = true;
                sources.put(id, new ArrayList<>());
                if (e.code.equals("authentication_required") || e.code.equals("identity_mismatch")) throw e;
            }
        }
        Row row = new Row(card.name);
        JSONObject expression = card.entry.optJSONObject("composition");
        row.cards.addAll(expression == null ? combine(new ArrayList<>(sources.values()), "interleave")
                : compose(expression, sources));
        if (row.cards.isEmpty() && failed) throw new AccountHttp.Failure("service_unavailable");
        return row;
    }

    private static List<Card> compose(JSONObject expression, Map<String, List<Card>> sources) {
        String source = value(expression, "source");
        if (!source.isEmpty()) return sources.getOrDefault(source, new ArrayList<>());
        List<List<Card>> groups = new ArrayList<>();
        for (JSONObject member : objects(expression.optJSONArray("members"))) groups.add(compose(member, sources));
        return combine(groups, expression.optString("strategy"));
    }

    private static List<Card> combine(List<List<Card>> groups, String strategy) {
        List<Card> ordered = new ArrayList<>();
        if (strategy.equals("sequential")) {
            for (List<Card> group : groups) ordered.addAll(group);
        } else {
            int max = 0;
            for (List<Card> group : groups) max = Math.max(max, group.size());
            for (int index = 0; index < max; index++) {
                for (List<Card> group : groups) if (index < group.size()) ordered.add(group.get(index));
            }
        }
        Set<String> common = null;
        if (strategy.equals("consensus")) {
            for (List<Card> group : groups) {
                Set<String> keys = new HashSet<>();
                for (Card card : group) keys.add(card.key());
                if (common == null) common = keys;
                else common.retainAll(keys);
            }
        }
        List<Card> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Card card : ordered) {
            String key = card.key();
            if ((common == null || common.contains(key)) && seen.add(key)) result.add(card);
            if (result.size() == ROW_LIMIT) break;
        }
        return result;
    }

    static List<Row> plex(AccountState owner, String service) throws Exception {
        String browse = service.isEmpty() ? "home" : service;
        JSONObject data = request(owner, "plex-catalogs", query("browse", browse));
        if (!service.isEmpty() && !data.optString("layout").equals("discover") && service.startsWith("plex:service:")) {
            data = request(owner, "plex-catalogs", query("service", service));
        }
        return plexRows(data);
    }

    static List<Row> plexRows(JSONObject data) {
        boolean combined = data.optString("layout").equals("discover");
        boolean landscape = combined || data.optString("artwork").equals("landscape");
        List<Row> rows = new ArrayList<>();
        for (JSONObject item : objects(data.optJSONArray("rows"))) {
            String name = value(item, "label");
            if (name.isEmpty()) continue;
            boolean discover = !item.optString("id").startsWith("pms:");
            Row row = new Row(combined ? name : (discover ? "Plex Discover" : "Plex") + " · " + name, discover);
            row.catalogId = item.optString("id", name);
            row.freeToWatch = item.optString("source").equals("free");
            row.cards.addAll(titles(item.optJSONArray("items"), landscape));
            if (!row.cards.isEmpty()) rows.add(row);
        }
        if (!combined) rows.sort(java.util.Comparator.comparing(row -> row.discover));
        if (combined && android.util.Log.isLoggable("NoiroHomeCatalogs", android.util.Log.DEBUG)) {
            long free = rows.stream().filter(row -> row.freeToWatch).count();
            android.util.Log.d("NoiroHomeCatalogs", "Catalog rows: " + rows.size() + "; Free to Watch: " + free);
        }
        return rows;
    }

    static JSONObject plexDetail(AccountState owner, Card card) throws Exception {
        if (card.detail.isEmpty()) return null;
        return request(owner, "plex-catalogs", query("detail", card.detail)).optJSONObject("hero");
    }

    static JSONObject lookupDiscoverArtwork(AccountState owner, List<String> keys) throws Exception {
        JSONObject result = request(owner, "plex-catalogs", query("discoverArtwork", String.join(",", keys)));
        return result.optJSONObject("artwork");
    }

    static String discoverKey(Card card) {
        if (card.title()) {
            String imdb = imdbIdentity(card);
            if (!imdb.isEmpty()) return card.type + ":" + imdb;
            if (card.id.matches("tmdb:[1-9][0-9]{0,9}")) return card.key();
        } else if (card.type.equals("trailer")) {
            // The parent is shared by other trailers and seasons of this title.
            String clip = value(card.metadata, "plexDetailId");
            if (clip.matches("[a-f0-9]{24}")) return "trailer:plex:" + clip;
        } else {
            return "";
        }
        return discoverDetailKey(card);
    }

    static String discoverDetailKey(Card card) {
        if (!card.title() && !card.type.equals("trailer")) return "";
        if (card.detail.matches("[a-f0-9]{24}")) return card.type + ":plex:" + card.detail;
        return card.title() && card.id.matches("plex:[a-f0-9]{24}") ? card.key() : "";
    }

    private static String imdbIdentity(Card card) {
        if (card.id.matches("tt[0-9]{5,12}")) return card.id;
        String imdb = value(card.metadata, "imdbId");
        if (!imdb.matches("tt[0-9]{5,12}")) imdb = value(card.metadata, "imdb_id");
        return imdb.matches("tt[0-9]{5,12}") ? imdb : "";
    }

    private static String discoverTmdbIdentity(String key) {
        String[] parts = key.split(":");
        String kind = parts[0].equals("series") ? "tv" : "movie";
        try {
            JSONObject ids = Tmdb.get("/" + kind + "/" + parts[2] + "/external_ids");
            String imdb = value(ids, "imdb_id");
            return imdb.matches("tt[0-9]{5,12}") ? parts[0] + ":" + imdb : "";
        } catch (Exception ignored) { return ""; }
    }

    static JSONObject resolveDiscoverArtwork(AccountState owner, List<String> keys,
                                            Map<String, String> detailKeys) throws Exception {
        Map<String, CompletableFuture<String>> tmdbIds = new LinkedHashMap<>();
        for (String key : keys) {
            if (key.matches("(?:movie|series):tmdb:[1-9][0-9]{0,9}")) {
                tmdbIds.put(key, CompletableFuture.supplyAsync(() -> discoverTmdbIdentity(key), DISCOVER_ID_LOOKUPS));
            }
        }
        Map<String, String> identities = new LinkedHashMap<>();
        Set<String> queries = new LinkedHashSet<>();
        for (String key : keys) {
            String identity = tmdbIds.containsKey(key) ? tmdbIds.get(key).join() : key;
            identities.put(key, identity);
            String detail = detailKeys.getOrDefault(key, "");
            if (!detail.isEmpty()) queries.add(detail);
            if (!identity.isEmpty()) queries.add(identity);
        }
        JSONObject resolved = new JSONObject();
        if (queries.isEmpty()) return resolved;
        JSONObject response = lookupDiscoverArtwork(owner, new ArrayList<>(queries));
        if (response == null) return resolved;
        Map<String, JSONObject> artwork = new LinkedHashMap<>();
        for (String key : keys) {
            String identity = identities.get(key);
            String detail = detailKeys.getOrDefault(key, "");
            // Prefer the known movie or show detail over an IMDb search result.
            // Trailers prefer their own artwork and can inherit missing parent art.
            if (key.startsWith("trailer:")) {
                mergeDiscover(artwork, key, discoverFields(response.optJSONObject(identity)));
                mergeDiscover(artwork, key, discoverFields(response.optJSONObject(detail)));
            } else {
                mergeDiscover(artwork, key, discoverFields(response.optJSONObject(detail)));
                mergeDiscover(artwork, key, discoverFields(response.optJSONObject(identity)));
            }
        }
        for (Map.Entry<String, JSONObject> entry : artwork.entrySet()) resolved.put(entry.getKey(), entry.getValue());
        return resolved;
    }

    static String trailer(AccountState owner, Card card, String browse) throws Exception {
        return safeImage(request(owner, "plex-catalogs", "",
                new JSONObject().put("action", "play_trailer").put("id", card.id).put("browse", browse)).optString("url"));
    }

    private static List<Card> titles(JSONArray data, boolean landscape) {
        List<Card> result = new ArrayList<>();
        for (JSONObject item : objects(data)) {
            String id = value(item, "id"), name = value(item, "name"), type = value(item, "type");
            boolean service = type.equals("service"), trailer = type.equals("trailer");
            if (name.isEmpty() || !(id.matches("tt[0-9]{5,12}")
                    || id.matches("plex:[a-zA-Z0-9_.:-]{1,160}"))) continue;
            if (!service && !trailer && !type.equals("genre") && !type.equals("movie") && !type.equals("series")) continue;
            boolean wide = landscape || service || trailer;
            String image = service ? art(item, "serviceLogo") : wide ? art(item, "landscape") : art(item, "poster");
            if (image.isEmpty() && wide && !service) image = art(item, "background");
            String background = art(item, "background");
            if (background.isEmpty() && wide && !service) background = image;
            // Some Discover and Studio titles supply only a poster.
            String poster = art(item, "poster");
            boolean portraitArtwork = wide && (type.equals("movie") || type.equals("series") || trailer)
                    && image.isEmpty() && !poster.isEmpty();
            if (portraitArtwork) image = poster;
            String logo = art(item, "logo");
            if (logo.isEmpty() && wide && !service) logo = art(item, "landscapeLogo");
            String detail = value(item, "plexDetailId");
            if (trailer && !value(item, "plexParentId").isEmpty()) detail = value(item, "plexParentId");
            result.add(new Card(id, name, type, image, background, logo,
                    detail, value(item, "description"), wide, portraitArtwork, null, item));
            if (result.size() == ROW_LIMIT) break;
        }
        return result;
    }

    static final class Hero {
        final String background, logo, description, rating;
        final JSONObject discover;
        final List<String> tags = new ArrayList<>();

        Hero(JSONObject... sources) {
            this(null, sources);
        }

        Hero(JSONObject discover, JSONObject[] sources) {
            this.discover = discover;
            JSONObject meta = new JSONObject();
            try {
                for (String key : new String[]{"background", "logo", "description", "imdbRating",
                        "released", "releaseInfo", "year", "genres", "runtime"}) {
                    for (JSONObject source : sources) {
                        if (source == null || value(source, key).isEmpty()) continue;
                        if (key.equals("genres") && (source.optJSONArray(key) == null
                                || source.optJSONArray(key).length() == 0)) continue;
                        meta.put(key, source.opt(key));
                        break;
                    }
                }
            } catch (org.json.JSONException invalid) {
                throw new IllegalArgumentException("Invalid hero metadata", invalid);
            }
            background = art(meta, "background");
            logo = art(meta, "logo");
            description = value(meta, "description");
            String score = value(meta, "imdbRating");
            rating = score.matches("(?:[0-9](?:\\.[0-9])?|10(?:\\.0)?)") ? score : "";
            String date = value(meta, "released");
            if (date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}(?:T.*)?")) {
                tags.add(date.substring(0, 4));
            } else {
                String year = value(meta, "releaseInfo");
                if (year.isEmpty()) year = value(meta, "year");
                if (!year.isEmpty()) tags.add(year);
            }
            JSONArray genres = meta.optJSONArray("genres");
            List<String> names = new ArrayList<>();
            for (int i = 0; genres != null && i < Math.min(2, genres.length()); i++) {
                String genre = genres.optString(i);
                if (!genre.isEmpty()) names.add(genre);
            }
            if (!names.isEmpty()) tags.add(android.text.TextUtils.join(", ", names));
            String runtime = value(meta, "runtime");
            java.util.regex.Matcher minutes = java.util.regex.Pattern.compile("([0-9]{1,4}) min(?:s)?").matcher(runtime);
            if (minutes.matches()) {
                int length = Integer.parseInt(minutes.group(1));
                if (length > 0) tags.add(length >= 60 ? (length / 60) + " h " + (length % 60) + " min" : length + " min");
            } else if (!runtime.isEmpty()) tags.add(runtime);
        }
    }

    private static JSONObject savedMetadata(Tmdb.Title title) {
        try {
            return new JSONObject().put("year", title.year).put("description", title.overview);
        } catch (org.json.JSONException invalid) {
            throw new IllegalArgumentException(invalid);
        }
    }

    private static Hero savedHero(Card card) {
        Tmdb.Title title = card.savedTitle;
        try {
            JSONObject details = Tmdb.get("/" + title.kind + "/" + title.id + "?append_to_response=external_ids");
            JSONObject metadata = savedMetadata(title);
            metadata.put("description", value(details, "overview"));
            metadata.put("released", value(details, title.kind.equals("movie") ? "release_date" : "first_air_date"));
            JSONArray genres = new JSONArray();
            for (JSONObject genre : objects(details.optJSONArray("genres"))) genres.put(value(genre, "name"));
            metadata.put("genres", genres);
            int runtime = details.optInt("runtime");
            if (runtime > 0) metadata.put("runtime", runtime + " min");
            String imdb = value(details.optJSONObject("external_ids"), "imdb_id");
            if (imdb.isEmpty()) imdb = value(details, "imdb_id");
            JSONObject identity = imdb.matches("tt[0-9]{5,12}") ? new JSONObject().put("imdbId", imdb) : null;
            return new Hero(identity, new JSONObject[]{metadata, card.metadata});
        } catch (Exception ignored) {
            return new Hero(card.metadata);
        }
    }

    static Hero hero(AccountState owner, Card card) {
        if (card.savedTitle != null) return savedHero(card);
        JSONObject plex = null, cinemeta = null;
        if (!card.detail.isEmpty()) {
            try { plex = plexDetail(owner, card); } catch (Exception ignored) { }
        }
        String imdb = value(plex, "imdbId");
        if (imdb.isEmpty()) imdb = card.id;
        String type = value(plex, "mediaType");
        if (type.isEmpty()) type = card.type;
        if (imdb.matches("tt[0-9]{5,12}") && (type.equals("series") || type.equals("movie"))
                && (plex == null || value(plex, "description").isEmpty())) {
            try {
                JSONObject meta = AccountHttp.call("https://v3-cinemeta.strem.io/meta/" + type + "/" + imdb + ".json",
                        null, "", 6500).optJSONObject("meta");
                if (meta != null && imdb.equals(value(meta, "id"))) cinemeta = meta;
            } catch (AccountHttp.Failure ignored) { }
        }
        return new Hero(plex, new JSONObject[]{plex, card.metadata, cinemeta});
    }

    static Map<String, JSONObject> discoverArtwork(List<Row> rows) {
        Map<String, JSONObject> images = new LinkedHashMap<>();
        // The Discover row wins over Discover art attached to a Plex library
        // item, regardless of the order in which rows are displayed on Home.
        for (boolean direct : new boolean[]{true, false}) {
            for (Row row : rows) {
                if (row.discover != direct || row.freeToWatch) continue;
                for (Card card : row.cards) {
                    if (card.metadata == null || (!card.title() && !card.type.equals("trailer"))) continue;
                    if (!direct && !"discover".equals(card.metadata.optString("plexArtwork"))) continue;
                    JSONObject fields = discoverFields(card.metadata);
                    mergeDiscover(images, card.key(), fields);
                    String identity = discoverKey(card);
                    if (!identity.isEmpty()) mergeDiscover(images, identity, fields);
                    String detail = discoverDetailKey(card);
                    if (card.title() && !detail.isEmpty()) mergeDiscover(images, detail, fields);
                }
            }
        }
        return images;
    }

    static JSONObject discoverFields(JSONObject item) {
        JSONObject fields = new JSONObject();
        for (String field : new String[]{"poster", "landscape", "background", "logo", "landscapeLogo"}) {
            String url = art(item, field);
            // Enriched library rows can still carry artwork outside Discover.
            if (!DiscoverArtworkCache.publicAsset(url)) continue;
            try { fields.put(field, url); }
            catch (org.json.JSONException invalid) { throw new IllegalArgumentException(invalid); }
        }
        if (fields.has("landscape") && "thumb".equals(value(item, "landscapeKind"))) {
            try { fields.put("landscapeKind", "thumb"); }
            catch (org.json.JSONException invalid) { throw new IllegalArgumentException(invalid); }
        }
        return fields;
    }

    static void mergeDiscover(Map<String, JSONObject> index, String key, JSONObject fields) {
        if (fields.length() == 0) return;
        JSONObject image = index.computeIfAbsent(key, ignored -> new JSONObject());
        try {
            String incoming = art(fields, "landscape");
            if (!incoming.isEmpty() && (!image.has("landscape")
                    || landscapeCover(image).isEmpty() && !landscapeCover(fields).isEmpty())) {
                image.put("landscape", incoming);
                if ("thumb".equals(value(fields, "landscapeKind"))) image.put("landscapeKind", "thumb");
                else image.remove("landscapeKind");
            }
            for (java.util.Iterator<String> it = fields.keys(); it.hasNext();) {
                String field = it.next();
                if (!field.equals("landscape") && !field.equals("landscapeKind") && !image.has(field)) {
                    image.put(field, fields.optString(field));
                }
            }
        } catch (org.json.JSONException invalid) {
            throw new IllegalArgumentException(invalid);
        }
    }

    static String landscapeCover(JSONObject artwork) {
        String cover = art(artwork, "landscape");
        return "thumb".equals(value(artwork, "landscapeKind")) || !cover.equals(art(artwork, "background"))
                ? cover : "";
    }

    static String cardArtwork(JSONObject artwork, boolean landscape) {
        if (!landscape) return art(artwork, "poster");
        String wide = art(artwork, "landscape");
        return wide.isEmpty() ? art(artwork, "background") : wide;
    }

    static JSONObject tmdbMatch(String imdb, String kind) throws Exception {
        if (!imdb.matches("tt[0-9]{5,12}")) return null;
        JSONArray matches = Tmdb.get("/find/" + imdb + "?external_source=imdb_id")
                .optJSONArray(kind.equals("tv") ? "tv_results" : "movie_results");
        return matches == null ? null : matches.optJSONObject(0);
    }

    static String backdrop(Card card) throws Exception {
        String kind = card.type.equals("series") ? "tv" : "movie";
        JSONObject match = card.id.matches("tmdb:[1-9][0-9]{0,9}")
                ? Tmdb.get("/" + kind + "/" + card.id.substring("tmdb:".length()))
                : tmdbMatch(imdbIdentity(card), kind);
        String path = value(match, "backdrop_path");
        return path.matches("/[A-Za-z0-9_.-]+") ? Tmdb.BACKDROP + path : "";
    }

    private static final long LOGO_TTL_MS = 6 * 3600_000L;
    private static final Map<String, String> STREAMING_LOGOS = new LinkedHashMap<>();
    private static long logosLoadedAt;

    static synchronized String streamingLogo(Card card) throws Exception {
        long now = System.currentTimeMillis();
        if (now - logosLoadedAt >= LOGO_TTL_MS) {
            JSONArray providers = Tmdb.get("/watch/providers/movie").optJSONArray("results");
            if (providers == null) return "";
            STREAMING_LOGOS.clear();
            for (JSONObject provider : objects(providers)) {
                String path = value(provider, "logo_path");
                if (!path.matches("/[A-Za-z0-9_.-]+")) continue;
                STREAMING_LOGOS.putIfAbsent(serviceName(value(provider, "provider_name")), Tmdb.POSTER + path);
            }
            logosLoadedAt = now;
        }
        String name = serviceName(card.name.split(" · ", 2)[0]);
        return STREAMING_LOGOS.getOrDefault(name, "");
    }

    static String serviceName(String name) {
        String normalized = name.toLowerCase(java.util.Locale.ROOT).replaceFirst("^amazon ", "")
                .replace(" plus", "+");
        if (normalized.equals("max")) return "hbo max";
        if (normalized.equals("apple tv")) return "apple tv+";
        if (normalized.equals("peacock premium")) return "peacock";
        return normalized;
    }

    private static boolean landscape(JSONObject value) {
        return "landscape".equals(value.optString("shape"));
    }

    private static String query(String key, String value) { return key + "=" + Uri.encode(value); }
    private static boolean validProfile(String id) { return id.matches("main|[a-f0-9]{32}"); }

    private static JSONObject request(AccountState owner, String path, String query) throws Exception {
        return request(owner, path, query, null);
    }

    private static JSONObject request(AccountState owner, String path, String query, JSONObject body) throws Exception {
        if (!current(owner)) throw new AccountHttp.Failure("identity_mismatch");
        String token = NoiroAccount.updateToken(owner);
        JSONObject reply = AccountHttp.call(API + path + (query.isEmpty() ? "" : "?" + query), body, token, 25000);
        if (!current(owner)) throw new AccountHttp.Failure("identity_mismatch");
        return reply;
    }

    private static List<JSONObject> objects(JSONArray array) {
        List<JSONObject> objects = new ArrayList<>();
        for (int i = 0; array != null && i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null) objects.add(item);
        }
        return objects;
    }

    static String art(JSONObject data, String field) { return safeImage(value(data, field)); }

    private static String value(JSONObject data, String field) {
        return data == null || data.isNull(field) ? "" : data.optString(field).trim();
    }

    private static String safeImage(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                    && value.length() <= 4096 ? value : "";
        } catch (IllegalArgumentException invalid) {
            return "";
        }
    }
}
