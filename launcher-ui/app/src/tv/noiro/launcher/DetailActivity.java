package tv.noiro.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A movie's or a show's page: what it is (rating, certification, director or creators), where it streams in the
 * region, what to do with it (watch, My list, seen, favorite, rate, add to a list, trailer), cast, seasons,
 * collection, keywords, reviews and similar titles.
 */
public class DetailActivity extends Activity {
    private static final String KIND = "kind", ID = "id", IMDB_RATING = "imdb_rating";
    private static final int PROVIDERS_SLOT = 6;

    static void open(Context c, String kind, int id) {
        open(c, kind, id, "");
    }

    static void open(Context c, String kind, int id, String imdbRating) {
        if ("person".equals(kind)) {
            PersonActivity.open(c, id);
            return;
        }
        Intent i = new Intent(c, DetailActivity.class).putExtra(KIND, kind).putExtra(ID, id).putExtra(IMDB_RATING, imdbRating);
        if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Runnable> cardActions = new HashMap<>();
    private KodiHomeView results;
    private String kind, imdbId, trailerKey, fullInfo;
    private int id;
    private Tmdb.Title title;
    private JSONObject providerRegion;
    private List<Tmdb.Title> seasonCards = Collections.emptyList();
    private List<StudioHome.Card> providerCards = Collections.emptyList();
    private boolean providersVisible;
    private volatile boolean inList, favorite;
    private volatile int myRating;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        kind = getIntent().getStringExtra(KIND);
        id = getIntent().getIntExtra(ID, 0);
        results = new KodiHomeView(this, this::openCard, this::finish, null, null);
        setContentView(results);
        results.startResults(Ui.tr("Detalhes", "Details"), false);
        new Thread(() -> {
            try {
                boolean movie = "movie".equals(kind);
                String extra = movie ? "release_dates,credits" : "content_ratings,aggregate_credits";
                JSONObject o = Tmdb.get("/" + kind + "/" + id + "?append_to_response=watch/providers,recommendations,videos,reviews,keywords,external_ids," + extra);
                JSONObject collection = null;
                JSONObject belongs = o.optJSONObject("belongs_to_collection");
                if (belongs != null) {
                    try { collection = Tmdb.get("/collection/" + belongs.getInt("id")); } catch (Exception ignored) {}
                }
                JSONArray reviews = o.optJSONObject("reviews") == null ? null : o.optJSONObject("reviews").optJSONArray("results");
                if (reviews == null || reviews.length() == 0) {
                    try { reviews = Tmdb.get("/" + kind + "/" + id + "/reviews?language=en-US").optJSONArray("results"); } catch (Exception ignored) {}
                }
                JSONObject states = Tmdb.states(this, kind, id);
                boolean saved = Tmdb.signedIn(this) ? states.optBoolean("watchlist") : Tmdb.inWatchlist(this, kind, id);
                JSONObject col = collection;
                JSONArray rev = reviews;
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    inList = saved;
                    favorite = states.optBoolean("favorite");
                    myRating = Tmdb.ratingOf(states);
                    show(o, col, rev);
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        results.resultMessage(Ui.tr("Não deu para abrir este título: ", "Couldn't open this title: ") + e.getMessage());
                    }
                });
            }
        }, "noiro-title-details").start();
    }

    private void show(JSONObject details, JSONObject collection, JSONArray reviews) {
        boolean movie = kind.equals("movie");
        String name = details.optString(movie ? "title" : "name");
        String date = details.optString(movie ? "release_date" : "first_air_date");
        String year = date.length() >= 4 ? date.substring(0, 4) : "";
        double vote = details.optDouble("vote_average", 0);
        String cert = certification(details);
        String imdbRating = getIntent().getStringExtra(IMDB_RATING);
        title = new Tmdb.Title(kind, id, name, details.optString("poster_path"),
                details.optString("backdrop_path"), year, vote);
        title.overview = details.optString("overview");

        JSONObject external = details.optJSONObject("external_ids");
        imdbId = details.optString("imdb_id", "");
        if (!imdbId.matches("tt[0-9]{5,12}") && external != null) imdbId = external.optString("imdb_id");
        if (!imdbId.matches("tt[0-9]{5,12}")) imdbId = "";
        trailerKey = TrailerResolver.youtubeKey(details);
        fullInfo = joinInfo(people(details, movie), status(details, movie), details.optString("tagline"), title.overview);

        JSONArray names = new JSONArray();
        JSONArray genres = details.optJSONArray("genres");
        for (int i = 0; genres != null && i < genres.length(); i++) {
            JSONObject genre = genres.optJSONObject(i);
            if (genre != null) names.put(genre.optString("name"));
        }
        JSONObject metadata = new JSONObject(Map.of(
                "year", year, "genres", names, "description", title.overview,
                "imdbRating", imdbRating == null ? "" : imdbRating,
                "runtime", duration(details, movie)));
        StudioHome.Hero hero = new StudioHome.Hero(new JSONObject(Map.of("imdbId", imdbId)), new JSONObject[] {metadata});
        if (!cert.isEmpty()) hero.tags.add(0, cert);
        StudioHome.Card featured = new StudioHome.Card(title, KodiHomeView.titleArtwork(title.poster, Tmdb.POSTER),
                KodiHomeView.titleArtwork(title.backdrop, Tmdb.BACKDROP), metadata);

        seasonCards = movie ? Collections.emptyList() : seasons(details);
        String label = movie ? "Stremio streams" : seasonCards.isEmpty()
                ? Ui.tr("Opções", "Options") : Ui.tr("Temporadas", "Seasons");
        Runnable primary = movie ? () -> StreamsActivity.open(this, "movie", id, 0, 0, title.name, results.snapshotHero(title))
                : seasonCards.isEmpty() ? this::showOptions : results::focusResults;
        results.pinResultHero(featured, hero, movie ? Ui.tr("Filme", "Movie") : Ui.tr("Série", "Show"),
                label, primary, this::showOptions);

        results.resultCatalog(0, Ui.tr("Temporadas", "Seasons"), seasonCards, null, null);
        JSONObject recs = details.optJSONObject("recommendations");
        if (recs != null) {
            results.resultCatalog(1, Ui.tr("Você também pode gostar", "You may also like"), Tmdb.titles(recs, kind), null,
                    () -> GridActivity.open(this, Ui.tr("Parecidos com ", "Like ") + name,
                            "/" + kind + "/" + id + "/recommendations", kind));
        }
        results.resultCatalog(2, Ui.tr("Elenco", "Cast"), cast(details, movie), null, null);
        if (collection != null) {
            List<Tmdb.Title> parts = Tmdb.titles(collection, "parts", "movie");
            parts.sort(java.util.Comparator.comparing(part -> part.year));
            if (parts.size() > 1) results.resultCatalog(3, collection.optString("name"), parts, null, null);
        }
        results.resultCatalog(4, Ui.tr("Notas e resenhas", "Ratings & Reviews"),
                ratingsAndReviews(imdbRating, cert, vote, details.optInt("vote_count"), reviews), null, null);
        results.resultCatalog(5, Ui.tr("Palavras-chave", "Keywords"), keywords(details, movie), null, null);

        JSONObject offers = details.optJSONObject("watch/providers");
        JSONObject regions = offers == null ? null : offers.optJSONObject("results");
        providerRegion = regions == null ? null : regions.optJSONObject(Tmdb.REGION);
        List<StudioHome.Card> providers = whereToWatch(providerRegion);
        if (providers.isEmpty()) {
            Tmdb.Title noOffers = textCard("rating", 4,
                    Ui.tr("Sem serviços de streaming na região.", "No streaming services in this region."));
            providers.add(new StudioHome.Card(noOffers, "", ""));
        }
        providerCards = providers;
        applyWatchVisibility();
    }

    private static String duration(JSONObject details, boolean movie) {
        if (movie) {
            int runtime = details.optInt("runtime");
            return runtime > 0 ? runtime + " min" : "";
        }
        int count = details.optInt("number_of_seasons");
        return count == 0 ? "" : count == 1 ? Ui.tr("1 temporada", "1 season")
                : count + Ui.tr(" temporadas", " seasons");
    }

    private static String joinInfo(String... lines) {
        List<String> nonempty = new ArrayList<>();
        for (String line : lines) if (!line.isEmpty()) nonempty.add(line);
        return TextUtils.join("\n\n", nonempty);
    }

    private void applyWatchVisibility() {
        boolean visible = Prefs.whereToWatch();
        if (title == null || visible == providersVisible) return;
        providersVisible = visible;
        results.resultCards(PROVIDERS_SLOT, Ui.string(this, R.string.where_to_watch_title),
                visible ? providerCards : Collections.emptyList(), null, null);
    }

    private void openSeason(int season) {
        SeasonActivity.open(this, id, season, title.name, imdbId);
    }

    private void openCard(Tmdb.Title card) {
        Runnable action = cardActions.get(card.key());
        if (action != null) action.run();
        else if ("season".equals(card.kind)) openSeason(card.id);
        else if (MediaMenu.supported(card.kind) || "person".equals(card.kind)) DetailActivity.open(this, card.kind, card.id);
    }

    private void showOptions() {
        Map<String, Runnable> options = new java.util.LinkedHashMap<>();
        if (!fullInfo.isEmpty()) options.put(Ui.tr("Informações", "Information"), () -> showText(title.name, fullInfo));
        if (Prefs.whereToWatch()) {
            String[] app = firstApp(providerRegion);
            if (app != null) options.put(Ui.tr("Assistir em ", "Watch on ") + app[1], () -> watch(app[0]));
        }
        if (trailerKey != null) options.put("Trailer", () -> playTrailer(trailerKey, imdbId));
        options.put(inList ? Ui.tr("Remover da minha lista", "Remove from My List") : Ui.tr("Adicionar à minha lista", "Add to My List"), this::toggleList);
        options.put(Tmdb.isSeen(this, kind, id) ? Ui.tr("Marcar como não assistido", "Mark unwatched")
                : Ui.tr("Marcar como assistido", "Mark watched"), this::toggleSeen);
        if (Tmdb.signedIn(this)) {
            options.put(favorite ? Ui.tr("Remover dos favoritos", "Remove from favorites") : Ui.tr("Favoritar", "Add to favorites"), this::toggleFavorite);
            options.put(myRating > 0 ? Ui.tr("Sua nota: ", "Your rating: ") + myRating : Ui.tr("Avaliar", "Rate"), this::rate);
            if (kind.equals("movie")) options.put(Ui.tr("Adicionar a uma lista", "Add to a list"), this::addToList);
        }
        String[] labels = options.keySet().toArray(new String[0]);
        new AlertDialog.Builder(this, R.style.Noiro_MenuDialog)
                .setTitle(title.name).setItems(labels, (dialog, index) -> options.get(labels[index]).run()).show();
    }

    private void showText(String label, String message) {
        new AlertDialog.Builder(this, R.style.Noiro_MenuDialog)
                .setTitle(label).setMessage(message).setPositiveButton("OK", null).show();
    }

    private String certification(JSONObject o) {
        try {
            if (kind.equals("movie")) {
                JSONArray results = o.getJSONObject("release_dates").getJSONArray("results");
                for (int i = 0; i < results.length(); i++) {
                    JSONObject r = results.getJSONObject(i);
                    if (!Tmdb.REGION.equals(r.optString("iso_3166_1"))) continue;
                    JSONArray dates = r.getJSONArray("release_dates");
                    for (int j = 0; j < dates.length(); j++) {
                        String c = dates.getJSONObject(j).optString("certification");
                        if (!c.isEmpty()) return c.equals("L") ? Ui.tr("Livre", "All ages") : c + "+";
                    }
                }
            } else {
                JSONArray results = o.getJSONObject("content_ratings").getJSONArray("results");
                for (int i = 0; i < results.length(); i++) {
                    JSONObject r = results.getJSONObject(i);
                    if (Tmdb.REGION.equals(r.optString("iso_3166_1"))) {
                        String c = r.optString("rating");
                        return c.equals("L") ? Ui.tr("Livre", "All ages") : c.isEmpty() ? "" : c + "+";
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String people(JSONObject o, boolean movie) {
        List<String> names = new ArrayList<>();
        if (movie) {
            JSONArray crew = o.optJSONObject("credits") == null ? null : o.optJSONObject("credits").optJSONArray("crew");
            for (int i = 0; crew != null && i < crew.length(); i++) {
                JSONObject c = crew.optJSONObject(i);
                if (c != null && "Director".equals(c.optString("job")) && !names.contains(c.optString("name"))) names.add(c.optString("name"));
            }
            return names.isEmpty() ? "" : Ui.tr("Direção: ", "Directed by ") + TextUtils.join(", ", names);
        }
        JSONArray created = o.optJSONArray("created_by");
        for (int i = 0; created != null && i < created.length(); i++) names.add(created.optJSONObject(i).optString("name"));
        JSONArray networks = o.optJSONArray("networks");
        String net = networks != null && networks.length() > 0 ? networks.optJSONObject(0).optString("name") : "";
        String who = names.isEmpty() ? "" : Ui.tr("Criação: ", "Created by ") + TextUtils.join(", ", names);
        return net.isEmpty() ? who : who.isEmpty() ? net : who + "  ·  " + net;
    }

    /** For a show still running: the next episode's date, or that it ended. */
    private static String status(JSONObject o, boolean movie) {
        if (movie) return "";
        JSONObject next = o.optJSONObject("next_episode_to_air");
        if (next != null) {
            String d = next.optString("air_date");
            String when = d.length() == 10 ? d.substring(8) + "/" + d.substring(5, 7) : d;
            return Ui.tr("Próximo episódio: ", "Next episode: ") + "T" + next.optInt("season_number") + "E" + next.optInt("episode_number") + " · " + when;
        }
        String s = o.optString("status");
        if (s.equals("Ended")) return Ui.tr("Série encerrada", "Ended");
        if (s.equals("Canceled")) return Ui.tr("Série cancelada", "Canceled");
        return "";
    }

    private static List<Tmdb.Title> seasons(JSONObject o) {
        List<Tmdb.Title> out = new ArrayList<>();
        JSONArray list = o.optJSONArray("seasons");
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject s = list.optJSONObject(i);
            if (s == null) continue;
            int n = s.optInt("season_number");
            String label = n == 0 ? Ui.tr("Especiais", "Specials") : Ui.tr("Temporada ", "Season ") + n;
            String poster = s.optString("poster_path");
            out.add(new Tmdb.Title("season", n, label + " · " + s.optInt("episode_count") + Ui.tr(" ep.", " ep."),
                    "null".equals(poster) ? "" : poster, "", "", 0));
        }
        out.sort(java.util.Comparator.comparingInt(season -> season.id == 0 ? Integer.MAX_VALUE : season.id));
        return out;
    }

    private static List<Tmdb.Title> cast(JSONObject o, boolean movie) {
        List<Tmdb.Title> out = new ArrayList<>();
        JSONObject credits = o.optJSONObject(movie ? "credits" : "aggregate_credits");
        JSONArray list = credits == null ? null : credits.optJSONArray("cast");
        for (int i = 0; list != null && i < list.length() && out.size() < 60; i++) {
            JSONObject p = list.optJSONObject(i);
            if (p == null) continue;
            String face = p.optString("profile_path");
            String role = movie ? p.optString("character") : roles(p);
            out.add(new Tmdb.Title("person", p.optInt("id"), p.optString("name") + (role.isEmpty() ? "" : " · " + role),
                    "null".equals(face) ? "" : face, "", role, 0));
        }
        return out;
    }

    private static String roles(JSONObject p) {
        JSONArray roles = p.optJSONArray("roles");
        return roles != null && roles.length() > 0 ? roles.optJSONObject(0).optString("character") : "";
    }

    private List<Tmdb.Title> keywords(JSONObject details, boolean movie) {
        JSONObject keywords = details.optJSONObject("keywords");
        JSONArray entries = keywords == null ? null : keywords.optJSONArray(movie ? "keywords" : "results");
        List<Tmdb.Title> cards = new ArrayList<>();
        for (int i = 0; entries != null && i < Math.min(12, entries.length()); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) continue;
            String word = entry.optString("name");
            int keyword = entry.optInt("id");
            Tmdb.Title card = textCard("keyword", keyword, word);
            cards.add(card);
            cardActions.put(card.key(), () -> GridActivity.open(this, Ui.tr("Com o tema “", "Tagged “") + word + "”",
                    "/discover/" + kind + "?with_keywords=" + keyword + "&sort_by=popularity.desc", kind));
        }
        return cards;
    }

    private List<Tmdb.Title> ratingsAndReviews(String imdb, String cert, double vote, int votes, JSONArray reviews) {
        List<Tmdb.Title> cards = new ArrayList<>();
        if (imdb != null && !imdb.isEmpty()) cards.add(textCard("rating", 1, "IMDb · " + imdb + " / 10"));
        if (vote > 0 && votes > 0) {
            String score = String.format(Ui.locale(), "TMDB · %.1f / 10", vote);
            String count = java.text.NumberFormat.getIntegerInstance(Ui.locale()).format(votes);
            cards.add(textCard("rating", 2, score + "\n" + count + " " + Ui.tr("votos", "votes")));
        }
        if (!cert.isEmpty()) cards.add(textCard("rating", 3, Ui.tr("Classificação etária", "Age Rating") + " · " + cert));
        for (int i = 0; reviews != null && i < Math.min(12, reviews.length()); i++) {
            JSONObject review = reviews.optJSONObject(i);
            if (review == null) continue;
            String body = review.optString("content").trim();
            if (body.isEmpty()) continue;
            String author = review.optString("author");
            JSONObject authorDetails = review.optJSONObject("author_details");
            double score = authorDetails == null ? 0 : authorDetails.optDouble("rating", 0);
            String reviewLabel = score > 0 ? author + String.format(Ui.locale(), " · ★ %.0f / 10", score) : author;
            Tmdb.Title card = textCard("review", i, reviewLabel + "\n" + body.replaceAll("\\s+", " "));
            cards.add(card);
            cardActions.put(card.key(), () -> showText(author, body));
        }
        return cards;
    }

    private static Tmdb.Title textCard(String type, int number, String name) {
        return new Tmdb.Title(type, number, name, "", "", "", 0);
    }

    /** The first subscription or free service with an app on the TV: {package, service name}. */
    private String[] firstApp(JSONObject region) {
        if (region == null) return null;
        for (String type : new String[] {"flatrate", "free", "ads"}) {
            JSONArray list = region.optJSONArray(type);
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject p = list.optJSONObject(i);
                String app = Providers.app(this, p.optInt("provider_id"), p.optString("provider_name"));
                if (app != null) return new String[] {app, p.optString("provider_name")};
            }
        }
        return null;
    }

    private List<StudioHome.Card> whereToWatch(JSONObject region) {
        List<StudioHome.Card> cards = new ArrayList<>();
        String[][] types = {{"flatrate", Ui.tr("Assinatura", "Subscription")}, {"free", Ui.tr("Grátis", "Free")},
                {"ads", Ui.tr("Grátis com anúncios", "Free with ads")}, {"rent", Ui.tr("Aluguel", "Rent")}, {"buy", Ui.tr("Compra", "Buy")}};
        Set<String> added = new HashSet<>();
        for (String[] type : types) {
            JSONArray list = region == null ? null : region.optJSONArray(type[0]);
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject offer = list.optJSONObject(i);
                if (offer == null) continue;
                int provider = offer.optInt("provider_id");
                if (!added.add(provider + type[0])) continue;
                String name = offer.optString("provider_name");
                String logo = KodiHomeView.titleArtwork(offer.optString("logo_path"), Tmdb.LOGO);
                String app = Providers.app(this, provider, name);
                Tmdb.Title card = textCard("provider", cards.size(), name + " · " + type[1]);
                cards.add(new StudioHome.Card(card, logo, ""));
                cardActions.put(card.key(), () -> {
                    if (app != null) watch(app);
                    else Toast.makeText(this, Ui.tr("Escolha o app em Ajustes › Serviços.",
                            "Choose an app in Settings › Services."), Toast.LENGTH_LONG).show();
                });
            }
        }
        return cards;
    }

    /** Opens the title in the app (a Wikidata deep link), or else the app's home. */
    private void watch(String pkg) {
        if (title != null) Tmdb.setSeen(this, title, true);
        String name = title == null ? "" : title.name;
        new Thread(() -> {
            String url = DeepLinks.find(this, kind, id, name).get(pkg);
            main.post(() -> { if (!DeepLinks.open(this, pkg, url)) RemoteKeys.run(this, pkg); });
        }).start();
    }

    private void background(String failure, Task task) {
        new Thread(() -> {
            try {
                task.run();
                main.post(() -> Toast.makeText(this, Ui.tr("Salvo", "Saved"), Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                main.post(() -> Toast.makeText(this, failure + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private interface Task { void run() throws Exception; }

    private void toggleList() {
        if (title == null) return;
        boolean want = !inList;
        background(Ui.tr("Não deu para salvar: ", "Couldn't save: "), () -> {
            Tmdb.setWatchlist(this, title, want);
            inList = want;
        });
    }

    private void toggleSeen() {
        if (title == null) return;
        boolean watched = !Tmdb.isSeen(this, kind, id);
        Tmdb.setSeen(this, title, watched);
        String message = watched ? Ui.tr("Marcado como assistido", "Marked watched")
                : Ui.tr("Marcado como não assistido", "Marked unwatched");
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void toggleFavorite() {
        if (title == null) return;
        boolean want = !favorite;
        background(Ui.tr("Não deu para favoritar: ", "Couldn't favorite: "), () -> {
            Tmdb.setFavorite(this, title, want);
            favorite = want;
        });
    }

    private void rate() {
        String[] items = new String[myRating > 0 ? 11 : 10];
        for (int i = 0; i < 10; i++) items[i] = "★ " + (10 - i);
        if (myRating > 0) items[10] = Ui.tr("Remover minha nota", "Remove my rating");
        new AlertDialog.Builder(this, R.style.Noiro_MenuDialog)
                .setTitle(Ui.tr("Sua nota para ", "Your rating for ") + title.name)
                .setItems(items, (d, which) -> background(Ui.tr("Não deu para salvar a nota: ", "Couldn't save the rating: "), () -> {
                    if (which == 10) {
                        Tmdb.unrate(this, title);
                        myRating = 0;
                    } else {
                        Tmdb.rate(this, title, 10 - which);
                        Tmdb.setSeen(this, title, true);
                        myRating = 10 - which;
                    }
                }))
                .show();
    }

    private void addToList() {
        new Thread(() -> {
            try {
                List<String[]> lists = Tmdb.lists(this);
                main.post(() -> pickList(lists));
            } catch (Exception e) {
                main.post(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void pickList(List<String[]> lists) {
        String[] items = new String[lists.size() + 1];
        for (int i = 0; i < lists.size(); i++) items[i] = lists.get(i)[1] + "  (" + lists.get(i)[2] + ")";
        items[lists.size()] = "+  " + Ui.tr("Nova lista…", "New list…");
        new AlertDialog.Builder(this, R.style.Noiro_MenuDialog)
                .setTitle(Ui.tr("Adicionar a uma lista", "Add to a list"))
                .setItems(items, (d, which) -> {
                    if (which < lists.size()) {
                        String list = lists.get(which)[0], name = lists.get(which)[1];
                        background(Ui.tr("Não deu para adicionar: ", "Couldn't add: "), () -> {
                            Tmdb.addToList(this, list, id);
                            main.post(() -> Toast.makeText(this, Ui.tr("Adicionado a ", "Added to ") + name, Toast.LENGTH_SHORT).show());
                        });
                        return;
                    }
                    EditText field = new EditText(this);
                    field.setSingleLine(true);
                    field.setHint(Ui.tr("Nome da lista", "List name"));
                    new AlertDialog.Builder(this, R.style.Noiro_MenuDialog)
                            .setTitle(Ui.tr("Nova lista", "New list"))
                            .setView(field)
                            .setPositiveButton(Ui.tr("Criar", "Create"), (d2, w2) -> background(Ui.tr("Não deu para criar: ", "Couldn't create: "), () -> {
                                String list = Tmdb.createList(this, field.getText().toString().trim());
                                Tmdb.addToList(this, list, id);
                                main.post(() -> Toast.makeText(this, Ui.tr("Lista criada", "List created"), Toast.LENGTH_SHORT).show());
                            }))
                            .setNegativeButton(Ui.tr("Cancelar", "Cancel"), null)
                            .show();
                })
                .show();
    }

    private Thread trailerWorker;
    private int trailerGeneration;
    private void playTrailer(String key, String imdb) {
        if (trailerWorker != null && trailerWorker.isAlive()) return;
        final int run = ++trailerGeneration;
        final AccountState owner = NoiroAccount.current();
        final String name = title == null ? "Trailer" : title.name + " · Trailer";
        Toast.makeText(this, "Loading trailer…", Toast.LENGTH_SHORT).show();
        trailerWorker = new Thread(() -> {
            try {
                TrailerResolver.Result source = TrailerResolver.resolve(key, imdb);
                main.post(() -> {
                    if (run != trailerGeneration || isFinishing() || isDestroyed() || !NoiroAccount.current().owns(owner.authKey, owner.uid)) return;
                    NoiroPlayerActivity.openTrailer(this, source, key, name);
                });
            } catch (Exception unavailable) {
                main.post(() -> {
                    if (run == trailerGeneration && !isFinishing() && !isDestroyed())
                        Toast.makeText(this, "Couldn't load this trailer. Please try again later.", Toast.LENGTH_LONG).show();
                });
            }
        }, "noiro-trailer");
        trailerWorker.start();
    }

    @Override protected void onResume() {
        super.onResume();
        if (results != null) {
            results.resume();
            applyWatchVisibility();
        }
    }

    @Override protected void onPause() {
        if (results != null) results.pause();
        super.onPause();
    }

    @Override protected void onStop() {
        super.onStop();
        trailerGeneration++;
        if (trailerWorker != null) trailerWorker.interrupt();
    }

    @Override protected void onDestroy() {
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
