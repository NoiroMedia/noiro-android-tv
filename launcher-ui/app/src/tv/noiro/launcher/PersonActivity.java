package tv.noiro.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A person's page: photo, what they do, biography, and their movies and shows, the best known first. */
public class PersonActivity extends Activity {
    private static final String ID = "id";

    static void open(Context c, int id) {
        Intent i = new Intent(c, PersonActivity.class).putExtra(ID, id);
        if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private KodiHomeView results;
    private Thread loader;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        int id = getIntent().getIntExtra(ID, 0);
        results = new KodiHomeView(this, title -> DetailActivity.open(this, title.kind, title.id),
                this::finish, null, null);
        float scale = TvUi.k(this);
        results.setResultTileDecoration((tile, title) -> tile.overlayCaption(scale, 23));
        setContentView(results);
        results.startResults(Ui.tr("Pessoa", "Person"), false);
        loader = new Thread(() -> {
            try {
                String path = "/person/" + id;
                JSONObject person = Tmdb.get(path + "?append_to_response=combined_credits");
                String biography = text(person, "biography");
                if (biography.isEmpty()) {
                    try { biography = text(Tmdb.get(path + "?language=en-US"), "biography"); }
                    catch (Exception ignored) { /* The credits and portrait are still available. */ }
                }
                String bio = biography;
                main.post(() -> {
                    if (!isFinishing() && !isDestroyed()) show(id, person, bio);
                });
            } catch (Exception unavailable) {
                main.post(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        results.resultMessage(Ui.tr("Não deu para abrir esta pessoa.", "Couldn't open this person."));
                    }
                });
            }
        }, "noiro-person");
        loader.start();
    }

    private void show(int id, JSONObject person, String biography) {
        String name = text(person, "name");
        String headshot = text(person, "profile_path");
        Tmdb.Title profile = new Tmdb.Title("person", id, name, headshot, "", "", 0);
        String noBio = Ui.tr("Sem biografia no TMDB.", "No biography on TMDB.");
        profile.overview = biography.isEmpty() ? noBio : biography;
        StudioHome.Card portrait = new StudioHome.Card(profile, KodiHomeView.titleArtwork(headshot, Tmdb.POSTER), "");
        StudioHome.Hero hero = new StudioHome.Hero(portrait.metadata);

        String dept = text(person, "known_for_department");
        if (!dept.isEmpty()) hero.tags.add(department(dept));
        String birth = text(person, "birthday");
        if (!birth.isEmpty()) hero.tags.add(Ui.tr("Nascimento: ", "Born: ") + date(birth));
        String place = text(person, "place_of_birth");
        if (!place.isEmpty()) hero.tags.add(place);
        String death = text(person, "deathday");
        if (!death.isEmpty()) hero.tags.add(Ui.tr("Morte: ", "Died: ") + date(death));

        String label = biography.isEmpty() ? Ui.tr("Informações", "Information") : Ui.tr("Biografia", "Biography");
        String fullProfile = hero.tags.isEmpty() ? profile.overview
                : TextUtils.join("\n", hero.tags) + "\n\n" + profile.overview;
        results.pinResultHero(portrait, hero, Ui.tr("Pessoa", "Person"), label,
                () -> new AlertDialog.Builder(this, R.style.Noiro_MenuDialog)
                        .setTitle(name).setMessage(fullProfile).setPositiveButton("OK", null).show(), null);

        JSONObject credits = person.optJSONObject("combined_credits");
        Set<String> seen = new HashSet<>();
        List<Tmdb.Title> movies = new ArrayList<>(), shows = new ArrayList<>();
        for (Tmdb.Title title : collect(credits == null ? null : credits.optJSONArray("cast"), seen)) {
            (title.kind.equals("movie") ? movies : shows).add(title);
        }
        List<Tmdb.Title> crew = collect(credits == null ? null : credits.optJSONArray("crew"), seen);
        results.resultCatalog(0, Ui.tr("Filmes", "Movies"), movies, null, null);
        results.resultCatalog(1, Ui.tr("Séries", "Shows"), shows, null, null);
        results.resultCatalog(2, Ui.tr("Por trás das câmeras", "Behind the camera"), crew, null, null);
        if (movies.isEmpty() && shows.isEmpty() && crew.isEmpty()) {
            results.resultMessage(Ui.tr("Nenhum crédito disponível.", "No credits available."));
        }
    }

    /** Credits as titles, most popular first, each title once. */
    private static List<Tmdb.Title> collect(JSONArray list, Set<String> seen) {
        List<JSONObject> sorted = new ArrayList<>();
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject credit = list.optJSONObject(i);
            if (credit != null) sorted.add(credit);
        }
        sorted.sort((a, b) -> Double.compare(b.optDouble("popularity", 0), a.optDouble("popularity", 0)));
        List<Tmdb.Title> titles = new ArrayList<>();
        for (JSONObject credit : sorted) {
            String kind = text(credit, "media_type");
            boolean movie = kind.equals("movie");
            if (!movie && !kind.equals("tv")) continue;
            int id = credit.optInt("id");
            String name = text(credit, movie ? "title" : "name");
            if (id <= 0 || name.isEmpty() || !seen.add(kind + ":" + id)) continue;
            String date = text(credit, movie ? "release_date" : "first_air_date");
            Tmdb.Title title = new Tmdb.Title(kind, id, name, text(credit, "poster_path"),
                    text(credit, "backdrop_path"), date.length() >= 4 ? date.substring(0, 4) : "",
                    credit.optDouble("vote_average", 0));
            title.overview = text(credit, "overview");
            titles.add(title);
        }
        return titles;
    }

    private static String text(JSONObject object, String key) {
        return object.isNull(key) ? "" : object.optString(key).trim();
    }

    private static String department(String d) {
        switch (d) {
            case "Acting": return Ui.tr("Atuação", "Acting");
            case "Directing": return Ui.tr("Direção", "Directing");
            case "Writing": return Ui.tr("Roteiro", "Writing");
            case "Production": return Ui.tr("Produção", "Production");
            default: return d;
        }
    }

    private static String date(String iso) {
        String[] p = iso.split("-");
        return p.length == 3 ? p[2] + "/" + p[1] + "/" + p[0] : iso;
    }

    @Override protected void onResume() {
        super.onResume();
        if (results != null) results.resume();
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
