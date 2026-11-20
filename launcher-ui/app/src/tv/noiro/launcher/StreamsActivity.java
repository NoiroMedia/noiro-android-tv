package tv.noiro.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** A fixed movie or episode hero above one selectable row of Stremio sources. */
public class StreamsActivity extends Activity {
    private static final int SOURCES_PER_PAGE = 32;
    private static final Pattern LINK = Pattern.compile("(?i)\\b(?:[a-z][a-z0-9+.-]*://|magnet:)\\S+");
    private static final Pattern LINE_BREAK = Pattern.compile("\\s*\\R\\s*");

    /** Display metadata only. Playback sources and request headers stay in memory. */
    static final class Hero {
        final String name, context, backdrop, logo, metadata, imdbRating, synopsis;

        Hero(String name, String context, String backdrop, String logo, String metadata, String imdbRating, String synopsis) {
            this.name = displayText(name, 240);
            this.context = displayText(context, 240);
            this.backdrop = StreamPolicy.web(backdrop, true) ? backdrop : "";
            this.logo = StreamPolicy.web(logo, true) ? logo : "";
            this.metadata = displayText(metadata, 500);
            this.imdbRating = displayText(imdbRating, 16);
            this.synopsis = displayText(synopsis, 4000);
        }

        private void put(Intent intent) {
            Bundle values = new Bundle();
            values.putString("name", name);
            values.putString("context", context);
            values.putString("backdrop", backdrop);
            values.putString("logo", logo);
            values.putString("metadata", metadata);
            values.putString("imdbRating", imdbRating);
            values.putString("synopsis", synopsis);
            intent.putExtra("hero", values);
        }

        private static Hero read(Intent intent, String title, String context) {
            Bundle values = intent.getBundleExtra("hero");
            if (values == null) return new Hero(title, context, "", "", "", "", "");
            return new Hero(prefer(values.getString("name", ""), title), prefer(values.getString("context", ""), context),
                    values.getString("backdrop"), values.getString("logo"), values.getString("metadata"),
                    values.getString("imdbRating"), values.getString("synopsis"));
        }
    }

    static void open(Context c, String kind, int tmdbId, int season, int episode, String title) {
        open(c, kind, tmdbId, season, episode, title, null);
    }

    static void open(Context c, String kind, int tmdbId, int season, int episode, String title, Hero hero) {
        Intent intent = new Intent(c, StreamsActivity.class).putExtra("kind", kind).putExtra("id", tmdbId)
                .putExtra("season", season).putExtra("episode", episode).putExtra("title", title);
        if (hero != null) hero.put(intent);
        c.startActivity(intent);
    }

    static void resume(Context c, UpNext.Item item, String title) {
        resume(c, item, title, null);
    }

    static void resume(Context c, UpNext.Item item, String title, Hero hero) {
        if (!UpNextPolicy.valid(item.type, item.id, item.videoId)) return;
        Intent intent = new Intent(c, StreamsActivity.class).putExtra("type", item.type).putExtra("videoId", item.videoId)
                .putExtra("title", title).putExtra("positionMs", item.next ? 0 : item.positionMs)
                .putExtra("resumePercent", item.next ? 0 : item.progress);
        if (hero != null) hero.put(intent);
        c.startActivity(intent);
    }

    /** Save only a typed title selection across login, never playback URLs, headers or arbitrary intents. */
    static Intent loginIntent(Context context, Intent selection) {
        Intent login = new Intent(context, OnboardingActivity.class);
        Intent target = continuation(context, selection);
        if (target != null) login.putExtra("after_login", "streams").putExtra("stream_selection", target.getExtras());
        return login;
    }

    static Intent afterLogin(Context context, Intent login) {
        if (!"streams".equals(login.getStringExtra("after_login"))) return null;
        try {
            Bundle selection = login.getBundleExtra("stream_selection");
            return selection == null ? null : continuation(context, new Intent().putExtras(selection));
        } catch (RuntimeException malformed) { return null; }
    }

    private static Intent continuation(Context context, Intent selection) {
        String type = "movie".equals(selection.getStringExtra("kind"))
                || "movie".equals(selection.getStringExtra("type")) ? "movie" : "series";
        String pointer = selection.getStringExtra("videoId");
        Intent target = new Intent(context, StreamsActivity.class);
        if (pointer != null) {
            if (!UpNextPolicy.valid(type, pointer.split(":", 2)[0], pointer)) return null;
            target.putExtra("type", type).putExtra("videoId", pointer);
        } else {
            int id = selection.getIntExtra("id", 0), season = selection.getIntExtra("season", 0), episode = selection.getIntExtra("episode", 0);
            if (id <= 0 || season < 0 || season > 999 || episode < 0 || episode > 9999
                    || "series".equals(type) && episode == 0) return null;
            target.putExtra("kind", "movie".equals(type) ? "movie" : "tv").putExtra("id", id)
                    .putExtra("season", season).putExtra("episode", episode);
        }
        String title = displayText(selection.getStringExtra("title"), 400);
        target.putExtra("title", title);
        long position = Math.max(0, Math.min(7L * 24 * 3600_000L, selection.getLongExtra("positionMs", 0)));
        double percent = selection.getDoubleExtra("resumePercent", 0);
        target.putExtra("positionMs", position).putExtra("resumePercent", Double.isFinite(percent) ? Math.max(0, Math.min(100, percent)) : 0);
        Hero.read(selection, title, "movie".equals(type) ? "Movie" : "Episode").put(target);
        return target;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<StudioHome.Card> cards = new ArrayList<>();
    private KodiHomeView results;
    private TextView sourcePreview;
    private int generation;
    private Thread worker, artworkWorker;
    private AccountState owner;
    private String type, videoId, title, catalogLabel;
    private boolean loading;
    private Hero hero;
    private List<StremioStreams.Source> sources = Collections.emptyList();

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Ui.init(this);
        owner = NoiroAccount.current();
        if (!AppAccess.ready(this)) {
            startActivity(loginIntent(this, getIntent()));
            finish();
            return;
        }
        Intent intent = getIntent();
        title = prefer(displayText(intent.getStringExtra("title"), 400), streamsLabel());
        type = "movie".equals(intent.getStringExtra("kind")) || "movie".equals(intent.getStringExtra("type"))
                ? "movie" : "series";
        String context = "movie".equals(type) ? Ui.tr("Filme", "Movie") : Ui.tr("Episódio", "Episode");
        hero = Hero.read(intent, title, context);
        results = new KodiHomeView(this, this::openSource, this::finish, null, null);
        results.setResultLandscapeOnly();
        results.setResultPrimaryIcon("apps");
        results.setResultVersionBadge("");
        float k = TvUi.k(this);
        sourcePreview = TvUi.text(this, k, 24, Ui.medium, TvUi.INK);
        sourcePreview.setGravity(Gravity.CENTER_VERTICAL);
        sourcePreview.setMaxLines(2);
        sourcePreview.setEllipsize(TextUtils.TruncateAt.END);
        sourcePreview.setPadding(Math.round(22 * k), Math.round(12 * k), Math.round(22 * k), Math.round(12 * k));
        sourcePreview.setBackground(TvUi.round(0xE8182533, 14 * k));
        sourcePreview.setVisibility(View.INVISIBLE);
        sourcePreview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        results.setResultFocusDetails(sourcePreview);
        results.setResultTileDecoration(this::decorateSourceTile);
        setContentView(results);
        results.startResults(streamsLabel(), false);
        StudioHome.Card featured = heroCard(hero);
        results.pinResultHero(featured, heroDetails(featured), hero.context, streamsLabel(), this::selectSources, this::showOptions);
        load();
        loadArtworkFallback();
    }

    private boolean current() {
        return !isFinishing() && !isDestroyed() && NoiroAccount.current().owns(owner.authKey, owner.uid);
    }

    private static String streamsLabel() {
        return Ui.tr("Fontes", "Sources");
    }

    private void selectSources() {
        if (loading || !sources.isEmpty()) results.focusResults();
        else load();
    }

    private void load() {
        if (loading || !current()) return;
        loading = true;
        int run = ++generation;
        cards.clear();
        sources = Collections.emptyList();
        setSourcePreview(null, "");
        results.setResultPrimaryLabel(streamsLabel());
        results.setResultVersionBadge("");
        results.resultCards(0, streamsLabel(), cards, null, null);
        refreshHero(hero);
        results.resultMessage(Ui.tr("Buscando fontes dos seus add-ons do Stremio…", "Finding streams from your Stremio add-ons…"));
        worker = new Thread(() -> {
            try {
                String pointer = resolveVideoId();
                main.post(() -> {
                    if (current() && generation == run) videoId = pointer;
                });
                StremioStreams.Result loaded = StremioStreams.load(owner, type, pointer);
                String quality = HeroVersion.bestLabel(getWindowManager().getDefaultDisplay(), loaded.sources);
                main.post(() -> {
                    if (current() && generation == run) show(loaded, quality);
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (!current() || generation != run) return;
                    loading = false;
                    showMessage(error instanceof IllegalArgumentException
                            ? Ui.tr("Este título não tem um ID de filme ou episódio compatível.",
                                    "This title has no supported IMDb movie or episode ID.")
                            : Ui.tr("Não foi possível carregar seus add-ons. Verifique a conexão e a conta do Stremio.",
                                    "Couldn't load your add-ons. Check your connection and Stremio sign-in."));
                });
            }
        }, "stremio-streams");
        worker.start();
    }

    private String resolveVideoId() throws Exception {
        Intent intent = getIntent();
        String pointer = intent.getStringExtra("videoId");
        if (pointer != null) {
            if (!UpNextPolicy.valid(type, pointer.split(":", 2)[0], pointer)) throw new IllegalArgumentException();
            return pointer;
        }
        int id = intent.getIntExtra("id", 0), episode = intent.getIntExtra("episode", 0);
        if (id <= 0 || ("series".equals(type) && episode <= 0)) throw new IllegalArgumentException();
        JSONObject external = Tmdb.get("/" + tmdbKind() + "/" + id + "/external_ids");
        return StreamPolicy.videoId(external.optString("imdb_id"), intent.getIntExtra("season", 0), episode);
    }

    private String tmdbKind() {
        return "movie".equals(type) ? "movie" : "tv";
    }

    private void show(StremioStreams.Result result, String quality) {
        loading = false;
        sources = result.sources;
        results.setResultVersionBadge(quality);
        if (sources.isEmpty()) {
            showMessage(result.addons == 0
                    ? Ui.tr("Nenhum add-on compatível. Gerencie seus add-ons do Stremio e tente novamente.",
                            "No compatible stream add-ons found. Manage your Stremio add-ons and retry.")
                    : Ui.tr("Nenhuma fonte encontrada para este título.", "No streams found for this title."));
            return;
        }
        catalogLabel = streamsLabel() + " · " + sources.size();
        if (result.failures > 0) catalogLabel += " · " + result.failures + Ui.tr(" add-ons indisponíveis", " add-ons unavailable");
        appendSources(0);
    }

    private void showMessage(String message) {
        results.setResultPrimaryLabel(Ui.tr("Tentar novamente", "Retry"));
        refreshHero(hero);
        results.resultMessage(message);
    }

    private void appendSources(int expectedCount) {
        if (!current() || cards.size() != expectedCount) return;
        int end = Math.min(sources.size(), expectedCount + SOURCES_PER_PAGE);
        for (int index = expectedCount; index < end; index++) {
            StremioStreams.Source source = sources.get(index);
            ReleaseQuality advertised = ReleaseQuality.parse(source.filename, source.name, source.description,
                    (height, codec, range) -> true);
            String name = advertised == null ? firstLine(sourceText(source.name, 100)) : advertised.label();
            String addon = prefer(firstLine(sourceText(source.addon, 60)), Ui.tr("Add-on", "Add-on"));
            String heading = name.isEmpty() || addon.equals(name) ? addon : addon + " · " + name;
            String description = sourceDescription(source);
            Tmdb.Title entry = new Tmdb.Title("stream", index + 1, heading, "", "", "", 0);
            entry.overview = description;
            String action = source.needsStremio ? Ui.tr("Abrir no Stremio", "Open in Stremio")
                    : Ui.tr("Assistir no Noiro", "Play in Noiro");
            cards.add(new StudioHome.Card(entry, "", "", new JSONObject(Map.of("action", action))));
        }
        results.resultCards(0, catalogLabel, cards, end < sources.size() ? () -> appendSources(end) : null, null);
    }

    private static String sourceDescription(StremioStreams.Source source) {
        return prefer(sourceText(source.description, 1200), sourceText(source.filename, 400));
    }

    private void decorateSourceTile(TvUi.CatalogTile tile, Tmdb.Title entry) {
        StremioStreams.Source source = sources.get(entry.id - 1);
        String filename = sourceText(source.filename, 600);
        String name = sourceText(source.name, 600);
        String overview = entry.overview;
        String fullLabel = prefer(filename, overview.length() >= name.length() ? overview : name);
        String preview = LINE_BREAK.matcher(fullLabel).replaceAll(" · ");
        if (preview.length() <= 45 && (entry.name.contains(preview) || overview.contains(preview))) preview = "";
        String focusText = preview;
        if (!focusText.isEmpty() && !entry.name.contains(fullLabel) && !overview.contains(fullLabel)) {
            tile.setContentDescription(tile.getContentDescription() + "\n" + focusText);
        }
        tile.setOnTileFocusChange((view, focused) -> {
            if (focused) setSourcePreview(view, focusText);
            else if (sourcePreview.getTag() == view) setSourcePreview(null, "");
        });
        tile.setOnLongClickListener(view -> {
            showSourceInfo(entry);
            return true;
        });
    }

    private void setSourcePreview(View tile, String text) {
        sourcePreview.setTag(tile);
        sourcePreview.setText(text);
        sourcePreview.setVisibility(text.isEmpty() ? View.INVISIBLE : View.VISIBLE);
    }

    private void showSourceInfo(Tmdb.Title entry) {
        if (!current()) return;
        int index = entry.id - 1;
        if (index < 0 || index >= sources.size()) return;
        StremioStreams.Source source = sources.get(index);
        List<String> lines = new ArrayList<>();
        String name = sourceText(source.name, 200);
        if (!name.isEmpty() && !entry.name.contains(name)) lines.add(name);
        String filename = sourceText(source.filename, 600);
        if (!filename.isEmpty() && !entry.overview.contains(filename) && !filename.equals(name)) lines.add(filename);
        if (!entry.overview.isEmpty()) lines.add(entry.overview);
        if (lines.isEmpty()) lines.add(source.needsStremio ? Ui.tr("Abrir no Stremio", "Open in Stremio")
                : Ui.tr("Assistir no Noiro", "Play in Noiro"));
        new AlertDialog.Builder(this, R.style.Noiro_MenuDialog)
                .setTitle(entry.name).setMessage(TextUtils.join("\n\n", lines))
                .setPositiveButton("OK", null).show();
    }

    private void loadArtworkFallback() {
        Intent intent = getIntent();
        String pointer = intent.getStringExtra("videoId");
        boolean continuingEpisode = "series".equals(type) && pointer != null;
        Hero initial = hero;
        if (!continuingEpisode && !initial.backdrop.isEmpty() && !initial.metadata.isEmpty()) return;
        artworkWorker = new Thread(() -> {
            try {
                Hero replacement = lookupHero(intent, pointer, continuingEpisode, initial);
                main.post(() -> {
                    if (current() && replacement != null) refreshHero(replacement);
                });
            } catch (Exception ignored) { /* The inherited hero and the source picker remain available. */ }
        }, "stremio-title-artwork");
        artworkWorker.start();
    }

    private Hero lookupHero(Intent intent, String pointer, boolean continuingEpisode, Hero initial) throws Exception {
        int id = intent.getIntExtra("id", 0);
        int season = intent.getIntExtra("season", 0), episode = intent.getIntExtra("episode", 0);
        if (id <= 0 && pointer != null) {
            String[] parts = pointer.split(":");
            if (!UpNextPolicy.valid(type, parts[0], pointer)) return null;
            JSONObject match = StudioHome.tmdbMatch(parts[0], tmdbKind());
            if (match != null) id = match.optInt("id");
            if (continuingEpisode) {
                season = Integer.parseInt(parts[1]);
                episode = Integer.parseInt(parts[2]);
            }
        }
        if (id <= 0) return null;
        String path = "/" + tmdbKind() + "/" + id;
        JSONObject data = Tmdb.get(path);
        String mediaBackdrop = KodiHomeView.titleArtwork(data.optString("backdrop_path"), Tmdb.BACKDROP);
        if ("movie".equals(type)) {
            return new Hero(initial.name, initial.context, prefer(initial.backdrop, mediaBackdrop), initial.logo,
                    prefer(initial.metadata, movieMetadata(data)), initial.imdbRating,
                    prefer(initial.synopsis, data.optString("overview", "")));
        }
        if (episode <= 0) return null;
        JSONObject exact = Tmdb.get(path + "/season/" + season + "/episode/" + episode);
        String still = KodiHomeView.titleArtwork(exact.optString("still_path"), Tmdb.BACKDROP);
        String backdrop = continuingEpisode ? prefer(still, prefer(initial.backdrop, mediaBackdrop))
                : prefer(initial.backdrop, prefer(still, mediaBackdrop));
        String context = initial.context;
        String name = initial.name;
        if (continuingEpisode) {
            context = prefer(data.optString("name"), context) + " · " + UpNextPolicy.episode(pointer);
            name = prefer(exact.optString("name"), name);
        }
        String synopsis = exact.optString("overview", "");
        return new Hero(name, context, backdrop, continuingEpisode ? "" : initial.logo,
                continuingEpisode ? episodeMetadata(exact) : prefer(initial.metadata, episodeMetadata(exact)),
                continuingEpisode ? "" : initial.imdbRating,
                continuingEpisode ? synopsis : prefer(initial.synopsis, synopsis));
    }

    private static String movieMetadata(JSONObject data) {
        List<String> details = new ArrayList<>();
        String date = data.optString("release_date");
        if (date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) details.add(date.substring(0, 4));
        JSONArray genres = data.optJSONArray("genres");
        List<String> names = new ArrayList<>();
        for (int i = 0; genres != null && i < Math.min(2, genres.length()); i++) {
            JSONObject genre = genres.optJSONObject(i);
            if (genre != null) names.add(genre.optString("name"));
        }
        if (!names.isEmpty()) details.add(TextUtils.join(", ", names));
        int runtime = data.optInt("runtime");
        if (runtime > 0) details.add(runtime + " min");
        return TextUtils.join(" · ", details);
    }

    private static String episodeMetadata(JSONObject data) {
        List<String> details = new ArrayList<>();
        String airDate = data.optString("air_date");
        if (airDate.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) details.add(airDate);
        int runtime = data.optInt("runtime");
        if (runtime > 0) details.add(runtime + " min");
        return TextUtils.join(" · ", details);
    }

    private StudioHome.Card heroCard(Hero info) {
        String kind = "movie".equals(type) ? "movie" : "episode";
        int id = getIntent().getIntExtra("id", 0);
        JSONObject metadata = new JSONObject(Map.of("description", info.synopsis, "year", info.metadata,
                "imdbRating", info.imdbRating, "logo", info.logo));
        // The displayed artwork is already resolved at the caller or by this screen's exact title lookup.
        return new StudioHome.Card("stream-title:" + id, info.name, kind, "", info.backdrop, info.logo,
                "", info.synopsis, true, false, null, metadata);
    }

    private static StudioHome.Hero heroDetails(StudioHome.Card card) {
        return new StudioHome.Hero(card.metadata);
    }

    private void refreshHero(Hero info) {
        hero = info;
        StudioHome.Card featured = heroCard(info);
        results.updatePinnedResultHero(featured, heroDetails(featured), info.context);
    }

    private static String prefer(String value, String fallback) {
        return value == null || value.isEmpty() || value.equals("null") ? fallback : value;
    }

    private static String displayText(String value, int max) {
        if (value == null || value.equals("null")) return "";
        String text = value.trim();
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private static String sourceText(String value, int max) {
        return value == null ? "" : displayText(LINK.matcher(value).replaceAll(""), max);
    }

    private static String firstLine(String value) {
        return value.split("\\R", 2)[0].trim();
    }

    private void showOptions() {
        if (!current()) return;
        new AlertDialog.Builder(this, R.style.Noiro_MenuDialog)
                .setTitle(streamsLabel())
                .setItems(new String[]{Ui.tr("Tentar novamente", "Retry")}, (dialog, index) -> {
                    if (loading && worker != null) worker.interrupt();
                    loading = false;
                    load();
                }).show();
    }

    private void openSource(Tmdb.Title card) {
        if (!current()) { finish(); return; }
        int index = card.id - 1;
        if (index < 0 || index >= sources.size() || videoId == null) return;
        StremioStreams.Source source = sources.get(index);
        if (!source.needsStremio) {
            NoiroPlayerActivity.open(this, source, type, videoId, title,
                    getIntent().getLongExtra("positionMs", 0), getIntent().getDoubleExtra("resumePercent", 0), sources);
        } else if (StreamPolicy.web(source.externalUrl, true)) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(source.externalUrl))); }
            catch (Exception missing) { Toast.makeText(this, "No app can open this source.", Toast.LENGTH_LONG).show(); }
        } else openStremio();
    }

    private void openStremio() {
        if (!current() || videoId == null) return;
        try {
            String imdb = videoId.split(":")[0];
            String link = "stremio:///detail/" + type + "/" + imdb + "/" + videoId;
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage("com.stremio.one"));
        } catch (Exception missing) { Toast.makeText(this, "Install Stremio to open this source type.", Toast.LENGTH_LONG).show(); }
    }

    @Override protected void onResume() {
        super.onResume();
        if (results == null) return;
        if (!current()) { finish(); return; }
        results.resume();
    }

    @Override protected void onPause() {
        if (results != null) results.pause();
        super.onPause();
    }

    @Override public void onBackPressed() {
        if (results == null || !results.back()) super.onBackPressed();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused && results != null && (results.findFocus() == null || results.findFocus() == results)) {
            results.focusResults();
        }
    }

    @Override protected void onDestroy() {
        generation++;
        if (worker != null) worker.interrupt();
        if (artworkWorker != null) artworkWorker.interrupt();
        if (results != null) results.destroy();
        super.onDestroy();
    }
}
