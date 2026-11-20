package tv.noiro.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A row's "See more" in the Home catalog layout. A TMDB path pages horizontally as focus approaches the end.
 */
public class GridActivity extends Activity {
    private static final String TITLE = "title", PATH = "path", KIND = "kind", TOKEN = "token";
    private static final int MAX_PAGES = 20;
    private static final Map<Integer, Object[]> LISTS = new HashMap<>();
    private static int next;

    /** A list already loaded; `open` is what OK on a poster does. */
    static void show(Activity a, String title, List<Tmdb.Title> titles, TvUi.OnTitle open) {
        int token;
        synchronized (LISTS) {
            token = ++next;
            LISTS.put(token, new Object[] {titles, open});
        }
        a.startActivity(new Intent(a, GridActivity.class).putExtra(TITLE, title).putExtra(TOKEN, token));
    }

    /** A TMDB list ("/discover/movie?…", a search), page after page. */
    static void open(Context c, String title, String path, String kind) {
        Intent i = new Intent(c, GridActivity.class).putExtra(TITLE, title).putExtra(PATH, path).putExtra(KIND, kind);
        if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Tmdb.Title> titles = new ArrayList<>();
    private KodiHomeView results;
    private String heading, path, kind;
    private int page, pages = 1;
    private boolean loading;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        heading = getIntent().getStringExtra(TITLE);
        path = getIntent().getStringExtra(PATH);
        kind = getIntent().getStringExtra(KIND);
        TvUi.OnTitle open = t -> DetailActivity.open(this, t.kind, t.id);
        if (path == null) {
            Object[] list;
            synchronized (LISTS) { list = LISTS.get(getIntent().getIntExtra(TOKEN, 0)); }
            if (list == null) {
                finish();
                return;
            }
            @SuppressWarnings("unchecked") List<Tmdb.Title> saved = (List<Tmdb.Title>) list[0];
            titles.addAll(saved);
            open = (TvUi.OnTitle) list[1];
        }
        results = new KodiHomeView(this, open, this::finish, null, null);
        setContentView(results);
        results.startResults(heading, false);
        if (path == null) showTitles();
        else more();
    }

    @Override protected void onResume() {
        super.onResume();
        if (results != null) results.resume();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused && results != null && (results.findFocus() == null || results.findFocus() == results)) {
            results.focusResults();
        }
    }

    @Override protected void onPause() {
        if (results != null) results.pause();
        super.onPause();
    }

    @Override public void onBackPressed() {
        if (results == null || !results.back()) super.onBackPressed();
    }

    private void showTitles() {
        results.resultCatalog(0, heading, titles, path == null ? null : this::more, null);
        if (titles.isEmpty()) results.resultMessage(Ui.tr("Nada encontrado.", "Nothing found."));
    }

    @Override protected void onDestroy() {
        if (isFinishing() && path == null) {
            synchronized (LISTS) { LISTS.remove(getIntent().getIntExtra(TOKEN, 0)); }
        }
        if (results != null) results.destroy();
        super.onDestroy();
    }

    private void more() {
        if (loading || page >= Math.min(pages, MAX_PAGES)) return;
        loading = true;
        int want = page + 1;
        new Thread(() -> {
            try {
                JSONObject o = Tmdb.get(path + (path.contains("?") ? "&" : "?") + "page=" + want);
                List<Tmdb.Title> found = Tmdb.titles(o, kind == null ? "movie" : kind);
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    page = want;
                    pages = Math.max(1, o.optInt("total_pages", 1));
                    loading = false;
                    titles.addAll(found);
                    showTitles();
                    if (titles.isEmpty()) more();
                });
            } catch (Exception e) {
                main.post(() -> {
                    loading = false;
                    if (!isFinishing() && !isDestroyed()) results.resultMessage(Ui.tr("Não deu para carregar.", "Couldn't load results."));
                });
            }
        }).start();
    }

}
