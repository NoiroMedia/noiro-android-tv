package tv.noiro.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The public IMDb/Cinemeta title path, using Noiro's existing catalog and playback UI. */
public class FreeDetailActivity extends Activity {
    static void open(Context context, StudioHome.Card card) {
        if (card != null) open(context, FreeCatalog.type(card), card.id, card.name);
    }

    static void open(Context context, String type, String imdb, String name) {
        type = FreeCatalog.type(type);
        if (type.isEmpty() || !FreeCatalog.imdb(imdb)) return;
        context.startActivity(new Intent(context, FreeDetailActivity.class).putExtra("type", type)
                .putExtra("imdb", imdb).putExtra("name", name));
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, PlayerSessionPolicy.Episode> episodeCards = new LinkedHashMap<>();
    private final Map<String, Integer> seasonCards = new LinkedHashMap<>();
    private KodiHomeView results;
    private AccountState owner;
    private String type, imdb, name;
    private JSONObject metadata;
    private StudioHome.Card title;
    private List<PlayerSessionPolicy.Episode> episodes = Collections.emptyList();
    private Thread worker;
    private int generation, selectedSeason = -1;
    private boolean loading, episodeFailure;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        Images.init(this);
        owner = NoiroAccount.current();
        type = FreeCatalog.type(getIntent().getStringExtra("type"));
        imdb = getIntent().getStringExtra("imdb");
        name = getIntent().getStringExtra("name");
        if (name == null) name = "";
        if (type.isEmpty() || !FreeCatalog.imdb(imdb)) { finish(); return; }
        results = new KodiHomeView(this, unused -> { }, this::onBackPressed, null, null);
        results.setResultCardOpener(this::openCard);
        results.setResultVersionBadge("");
        setContentView(results);
        load();
    }

    /** Guest pages remain public; an account change never reuses another person's selections. */
    private boolean current() {
        if (isFinishing() || isDestroyed()) return false;
        AccountState now = NoiroAccount.current();
        return owner.signedIn() ? now.owns(owner.authKey, owner.uid) : !now.signedIn();
    }

    private void load() {
        if (loading || !current()) return;
        loading = true;
        selectedSeason = -1;
        results.startResults(name, false);
        int run = ++generation;
        worker = new Thread(() -> {
            try {
                JSONObject details = FreeCatalog.metadata(type, imdb);
                StudioHome.Card loaded = FreeCatalog.card(details, type);
                List<PlayerSessionPolicy.Episode> roster = Collections.emptyList();
                boolean failed = false;
                if (type.equals("series")) {
                    try { roster = PlayerEpisodes.fromMetadata(imdb, details); }
                    catch (Exception unavailable) { failed = true; }
                }
                List<PlayerSessionPolicy.Episode> finalRoster = roster;
                boolean finalFailed = failed;
                main.post(() -> {
                    if (!current() || generation != run) return;
                    loading = false;
                    metadata = details;
                    title = loaded;
                    name = loaded.name;
                    episodes = finalRoster;
                    episodeFailure = finalFailed;
                    showSeasons();
                });
            } catch (Exception unavailable) {
                main.post(() -> {
                    if (!current() || generation != run) return;
                    loading = false;
                    results.resultMessage(Ui.tr("Não foi possível carregar este título. Tente novamente.",
                            "Couldn't load this title. Try again."));
                    new AlertDialog.Builder(this, R.style.Noiro_MenuDialog).setTitle(name)
                            .setMessage(Ui.tr("Verifique a conexão e tente novamente.", "Check your connection and try again."))
                            .setPositiveButton(Ui.tr("Tentar novamente", "Retry"), (dialog, which) -> load())
                            .setNegativeButton(Ui.tr("Voltar", "Back"), (dialog, which) -> finish()).show();
                });
            }
        }, "noiro-public-title");
        worker.start();
    }

    private void pin(String context, String action, Runnable primary) {
        results.pinResultHero(title, new StudioHome.Hero(metadata), context, action, primary, this::showOptions);
    }

    private void showSeasons() {
        selectedSeason = -1;
        seasonCards.clear();
        episodeCards.clear();
        results.startResults(name, false);
        if (type.equals("movie")) {
            pin(Ui.tr("Filme", "Movie"), Ui.tr("Reproduzir", "Play"), () -> play(imdb, ""));
            return;
        }
        List<StudioHome.Card> cards = new ArrayList<>();
        for (PlayerSessionPolicy.Episode episode : episodes) {
            String key = "season:" + episode.season;
            if (seasonCards.containsKey(key)) continue;
            seasonCards.put(key, episode.season);
            String label = Ui.tr("Temporada ", "Season ") + episode.season;
            cards.add(new StudioHome.Card(key, label, "season", title.image, title.background, "", "", "", false, null));
        }
        pin(Ui.tr("Série", "Show"), cards.isEmpty() ? Ui.tr("Tentar novamente", "Retry") : Ui.tr("Episódios", "Episodes"),
                cards.isEmpty() ? this::load : results::focusResults);
        results.resultCards(0, Ui.tr("Temporadas", "Seasons"), cards, null, null);
        if (cards.isEmpty()) results.resultMessage(episodeFailure
                ? Ui.tr("Não foi possível carregar os episódios. Tente novamente.", "Couldn't load episodes. Try again.")
                : Ui.tr("Nenhum episódio disponível.", "No episodes available."));
    }

    private void showEpisodes(int season) {
        selectedSeason = season;
        episodeCards.clear();
        String context = Ui.tr("Temporada ", "Season ") + season;
        results.startResults(name + " · " + context, false);
        results.setResultLandscapeOnly();
        List<StudioHome.Card> cards = new ArrayList<>();
        for (PlayerSessionPolicy.Episode episode : episodes) {
            if (episode.season != season) continue;
            JSONObject detail = episodeMetadata(episode.id);
            String synopsis = detail == null ? "" : detail.optString("description");
            String image = detail == null ? "" : detail.optString("thumbnail");
            if (image.isEmpty()) image = title.background;
            String label = episode.label();
            if (!episode.aired(System.currentTimeMillis())) label += Ui.tr(" · Ainda não exibido", " · Not aired yet");
            episodeCards.put(episode.id, episode);
            cards.add(new StudioHome.Card(episode.id, label, "episode", image, title.background, "", "", synopsis, true, null));
        }
        pin(context, Ui.tr("Episódios", "Episodes"), results::focusResults);
        results.resultCards(0, context, cards, null, null);
        if (cards.isEmpty()) results.resultMessage(Ui.tr("Nenhum episódio disponível.", "No episodes available."));
        results.focusResults();
    }

    private JSONObject episodeMetadata(String id) {
        JSONArray videos = metadata.optJSONArray("videos");
        for (int i = 0; videos != null && i < videos.length(); i++) {
            JSONObject video = videos.optJSONObject(i);
            if (video != null && id.equals(video.optString("id"))) return video;
        }
        return null;
    }

    private void openCard(StudioHome.Card card) {
        if (!current()) return;
        Integer season = seasonCards.get(card.id);
        if (season != null && card.type.equals("season")) { showEpisodes(season); return; }
        PlayerSessionPolicy.Episode episode = episodeCards.get(card.id);
        if (episode == null || !card.type.equals("episode")) return;
        if (!episode.aired(System.currentTimeMillis())) {
            Toast.makeText(this, Ui.tr("Este episódio ainda não foi exibido.", "This episode has not aired yet."), Toast.LENGTH_SHORT).show();
            return;
        }
        play(episode.id, episode.label());
    }

    private void play(String videoId, String episodeLabel) {
        if (!current() || !UpNextPolicy.valid(type, imdb, videoId)) return;
        StudioHome.Hero details = new StudioHome.Hero(metadata);
        Bundle hero = new Bundle();
        hero.putString("name", name);
        hero.putString("context", episodeLabel.isEmpty() ? Ui.tr("Filme", "Movie") : episodeLabel);
        hero.putString("backdrop", title.background);
        hero.putString("logo", title.logo);
        hero.putString("metadata", android.text.TextUtils.join(" · ", details.tags));
        hero.putString("imdbRating", details.rating);
        hero.putString("synopsis", details.description);
        startActivity(new Intent(this, StreamsActivity.class).putExtra("type", type).putExtra("videoId", videoId)
                .putExtra("title", name).putExtra("hero", hero));
    }

    private void showOptions() {
        if (title == null || !current()) return;
        boolean saved = FreeWatchlist.contains(this, title);
        String label = saved ? Ui.tr("Remover da minha lista", "Remove from My list") : Ui.tr("Minha lista", "Add to My list");
        new AlertDialog.Builder(this, R.style.Noiro_MenuDialog).setTitle(name)
                .setItems(new String[]{label, Ui.tr("Atualizar", "Refresh")}, (dialog, which) -> {
                    if (!current()) return;
                    if (which == 1) { load(); return; }
                    FreeWatchlist.toggle(this, title);
                    Toast.makeText(this, saved ? Ui.tr("Removido da lista", "Removed from My list")
                            : Ui.tr("Adicionado à lista", "Added to My list"), Toast.LENGTH_SHORT).show();
                }).show();
    }

    @Override public void onBackPressed() {
        if (results != null && results.back()) return;
        if (selectedSeason >= 0) { showSeasons(); return; }
        super.onBackPressed();
    }

    @Override protected void onResume() {
        super.onResume();
        if (results == null) return;
        AccountState now = NoiroAccount.current();
        boolean changed = owner.signedIn() ? !now.owns(owner.authKey, owner.uid) : now.signedIn();
        if (changed) {
            generation++;
            if (worker != null) worker.interrupt();
            loading = false;
            owner = now;
            metadata = null;
            title = null;
            episodes = Collections.emptyList();
            seasonCards.clear();
            episodeCards.clear();
            load();
        }
        results.resume();
    }

    @Override protected void onPause() {
        if (results != null) results.pause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        generation++;
        if (worker != null) worker.interrupt();
        if (results != null) results.destroy();
        super.onDestroy();
    }
}
