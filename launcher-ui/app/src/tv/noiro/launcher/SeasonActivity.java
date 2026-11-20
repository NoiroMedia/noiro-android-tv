package tv.noiro.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A season's episodes in the fixed-hero catalog. */
public class SeasonActivity extends Activity {
    private static final String SERIES = "series", SEASON = "season", NAME = "name", IMDB = "imdb";

    static void open(Context c, int series, int season, String seriesName) {
        open(c, series, season, seriesName, "");
    }

    static void open(Context c, int series, int season, String seriesName, String imdb) {
        c.startActivity(new Intent(c, SeasonActivity.class).putExtra(SERIES, series).putExtra(SEASON, season)
                .putExtra(NAME, seriesName).putExtra(IMDB, imdb));
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Integer, TextView> seenBadges = new HashMap<>();
    private KodiHomeView results;
    private Thread loader;
    private float k;
    private int series, season;
    private String seriesName, heading, imdb;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        k = TvUi.k(this);
        series = getIntent().getIntExtra(SERIES, 0);
        season = getIntent().getIntExtra(SEASON, 1);
        seriesName = getIntent().getStringExtra(NAME);
        if (seriesName == null) seriesName = "";
        imdb = getIntent().getStringExtra(IMDB);
        String seasonName = season == 0 ? Ui.tr("Especiais", "Specials") : Ui.tr("Temporada ", "Season ") + season;
        heading = seriesName.isEmpty() ? seasonName : seriesName + " · " + seasonName;
        results = new KodiHomeView(this, this::openStreams, this::finish, null, null);
        results.setResultPrimaryLabel(Ui.tr("Escolher fontes", "Choose streams"));
        results.setResultTileDecoration(this::decorateEpisode);
        setContentView(results);
        results.startResults(heading, false);

        loader = new Thread(() -> {
            try {
                JSONObject data = Tmdb.get("/tv/" + series + "/season/" + season);
                List<StudioHome.Card> episodes = episodes(data);
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    results.resultCards(0, heading, episodes, null, null);
                    if (episodes.isEmpty()) {
                        results.resultMessage(Ui.tr("Nenhum episódio disponível.", "No episodes available."));
                    }
                });
            } catch (Exception unavailable) {
                main.post(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        results.resultMessage(Ui.tr("Não deu para abrir a temporada.", "Couldn't open the season."));
                    }
                });
            }
        }, "noiro-season");
        loader.start();
    }

    private List<StudioHome.Card> episodes(JSONObject data) throws JSONException {
        List<StudioHome.Card> cards = new ArrayList<>();
        JSONArray episodes = data.optJSONArray("episodes");
        String seasonOverview = data.optString("overview", "");
        for (int i = 0; episodes != null && i < episodes.length(); i++) {
            JSONObject episode = episodes.optJSONObject(i);
            int number = episode == null ? 0 : episode.optInt("episode_number");
            if (number <= 0) continue;

            String name = episode.optString("name");
            String still = episode.optString("still_path", "");
            if ("null".equals(still) || !still.startsWith("/")) still = "";
            String airDate = episode.optString("air_date");
            if (airDate.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                airDate = airDate.substring(8) + "/" + airDate.substring(5, 7) + "/" + airDate.substring(0, 4);
            } else airDate = "";
            double vote = episode.optDouble("vote_average", 0);
            String metadataLine = airDate;
            if (vote > 0) {
                String score = String.format(Ui.locale(), "TMDB ★ %.1f", vote);
                metadataLine = airDate.isEmpty() ? score : airDate + " · " + score;
            }
            int runtime = episode.optInt("runtime");
            String overview = episode.optString("overview", "");
            if (overview.isEmpty() && !seasonOverview.isEmpty()) {
                overview = Ui.tr("Resumo da temporada", "Season overview") + " · " + seasonOverview;
            }
            Tmdb.Title title = new Tmdb.Title("episode", number, number + ". " + name,
                    still, still, metadataLine, vote);
            title.overview = overview;
            JSONObject metadata = new JSONObject().put("year", metadataLine).put("description", overview);
            if (runtime > 0) metadata.put("runtime", runtime + " min");
            String videoId = imdb + ":" + season + ":" + number;
            if (UpNextPolicy.valid("series", imdb, videoId)) metadata.put("videoId", videoId);
            cards.add(new StudioHome.Card(title, KodiHomeView.titleArtwork(still, Tmdb.POSTER),
                    KodiHomeView.titleArtwork(still, Tmdb.BACKDROP), metadata));
        }
        return cards;
    }

    private void openStreams(Tmdb.Title episode) {
        StreamsActivity.Hero hero = results.snapshotHero(episode);
        if (hero == null) {
            hero = new StreamsActivity.Hero(episode.name, heading,
                    KodiHomeView.titleArtwork(episode.backdrop, Tmdb.BACKDROP), "", episode.year, "", episode.overview);
        }
        StreamsActivity.open(this, "tv", series, season, episode.id,
                heading + " · " + episode.name, hero);
    }

    private void decorateEpisode(View view, Tmdb.Title episode) {
        if (view instanceof FrameLayout) {
            TextView badge = TvUi.text(this, k, 24, Ui.semibold, TvUi.INK);
            badge.setText("✓");
            badge.setTag(episode.name);
            badge.setContentDescription(Ui.tr("Assistido", "Seen"));
            badge.setGravity(Gravity.CENTER);
            badge.setBackground(TvUi.round(0xD9101216, 22 * k));
            badge.setElevation(4 * k);
            int size = Math.round(44 * k);
            FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.END);
            position.topMargin = position.rightMargin = Math.round(10 * k);
            ((FrameLayout) view).addView(badge, position);
            seenBadges.put(episode.id, badge);
            updateSeenBadge(episode.id);
        }

        view.setOnLongClickListener(v -> {
            boolean seen = Tmdb.episodeSeen(this, series, season, episode.id);
            String[] actions = {Ui.tr("Escolher fontes", "Choose streams"),
                    seen ? Ui.tr("Marcar como não assistido nesta TV", "Mark as unseen on this TV")
                            : Ui.tr("Marcar como assistido nesta TV", "Mark as seen on this TV"),
                    Ui.tr("Avaliar episódio", "Rate episode")};
            TvUi.popover(this, v, MediaMenu.anchor(v), episode.name, actions, which -> {
                if (which == 0) openStreams(episode);
                else if (which == 1) {
                    Tmdb.setEpisodeSeen(this, series, season, episode.id, !seen);
                    updateSeenBadge(episode.id);
                } else rate(episode);
            }, false, true);
            return true;
        });
        view.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_MENU) return false;
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) rate(episode);
            return true;
        });
    }

    private void updateSeenBadge(int number) {
        TextView badge = seenBadges.get(number);
        if (badge == null) return;
        boolean seen = Tmdb.episodeSeen(this, series, season, number);
        badge.setVisibility(seen ? View.VISIBLE : View.GONE);
        if (badge.getParent() instanceof View) {
            String name = (String) badge.getTag();
            ((View) badge.getParent()).setContentDescription(seen ? name + " · " + Ui.tr("Assistido", "Seen") : name);
        }
    }

    private void rate(Tmdb.Title episode) {
        if (!Tmdb.signedIn(this)) {
            Toast.makeText(this, Ui.tr("As notas da conta não estão disponíveis.", "Account ratings are unavailable."),
                    Toast.LENGTH_LONG).show();
            return;
        }
        String[] items = new String[10];
        for (int i = 0; i < 10; i++) items[i] = "★ " + (10 - i);
        new AlertDialog.Builder(this, R.style.Noiro_MenuDialog)
                .setTitle(episode.name)
                .setItems(items, (dialog, which) -> new Thread(() -> {
                    try {
                        Tmdb.rateEpisode(this, series, season, episode.id, 10 - which);
                        Tmdb.setEpisodeSeen(this, series, season, episode.id, true);
                        main.post(() -> { if (!isFinishing() && !isDestroyed()) updateSeenBadge(episode.id); });
                    } catch (Exception unavailable) {
                        main.post(() -> {
                            if (!isFinishing() && !isDestroyed()) {
                                Toast.makeText(this, Ui.tr("Não deu para salvar a nota.", "Couldn't save the rating."),
                                        Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                }, "noiro-episode-rating").start())
                .show();
    }

    @Override protected void onResume() {
        super.onResume();
        if (results == null) return;
        results.resume();
        for (int number : seenBadges.keySet()) updateSeenBadge(number);
    }

    @Override protected void onPause() {
        if (results != null) results.pause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (loader != null) loader.interrupt();
        if (results != null) results.destroy();
        super.onDestroy();
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
}
