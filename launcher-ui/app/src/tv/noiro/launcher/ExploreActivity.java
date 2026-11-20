package tv.noiro.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Explore navigation, search controls and result sources for the shared Home catalog view. */
public class ExploreActivity extends Activity {
    static final String EXTRA_SECTION = "section";
    private static final String HOME = "home", SEARCH = "search", LIST = "watchlist", TOP = "top", MOVIES = "movie",
            SHOWS = "tv", KIDS = "kids", ANIME = "anime", CATS = "categories", SETTINGS = "settings", SERVICE = "service:",
            SAVED = "f:", LAUNCHER_HOME = "launcher-home";
    /** The menu's items the person can move or hide, in their first order (services and saved filters follow). */
    private static final String[] MOVABLE = {TOP, MOVIES, SHOWS, KIDS, ANIME, CATS};
    private static final int INSET = 142, SIDE_RAIL = 68, SIDE_WIDTH = 366;

    /**
     * Categories: key, Portuguese, English, movie genre, show genre, keyword, origin country, original language (to
     * filter search results, which TMDB doesn't filter).
     */
    private static final String[][] GENRES = {
        {"acao", "Ação", "Action", "28", "10759", null, null, null},
        {"aventura", "Aventura", "Adventure", "12", "10759", null, null, null},
        {"anime", "Anime", "Anime", "16", "16", "210024", null, "ja"},
        {"animacao", "Animação", "Animation", "16", "16", null, null, null},
        {"comedia", "Comédia", "Comedy", "35", "35", null, null, null},
        {"crime", "Crime", "Crime", "80", "80", null, null, null},
        {"documentarios", "Documentários", "Documentaries", "99", "99", null, null, null},
        {"drama", "Drama", "Drama", "18", "18", null, null, null},
        {"familia", "Família", "Family", "10751", "10751", null, null, null},
        {"fantasia", "Fantasia", "Fantasy", "14", "10765", null, null, null},
        {"ficcao", "Ficção científica", "Sci-fi", "878", "10765", null, null, null},
        {"guerra", "Guerra", "War", "10752", "10768", null, null, null},
        {"historia", "História", "History", "36", null, null, null, null},
        {"infantil", "Infantil", "Kids", "10751", "10762", null, null, null},
        {"misterio", "Mistério", "Mystery", "9648", "9648", null, null, null},
        {"musica", "Música", "Music", "10402", null, null, null, null},
        {"faroeste", "Faroeste", "Western", "37", "37", null, null, null},
        {"reality", "Reality", "Reality", null, "10764", null, null, null},
        {"romance", "Romance", "Romance", "10749", null, null, null, null},
        {"suspense", "Suspense", "Thriller", "53", "9648", null, null, null},
        {"terror", "Terror", "Horror", "27", null, null, null, null},
        {"novela", "Novelas", "Soaps", null, "10766", null, null, null},
        {"doramas", "Doramas", "K-dramas", null, "18", null, "KR", "ko"},
        {"nacionais", "Nacionais", "Turkish", null, null, null, "TR", "tr"},
    };

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(4);
    private float k;
    private FrameLayout root;
    private TvUi.Scroll scroll, sidePanel;
    private LinearLayout page, rows, menu, menuMovable;
    private TextView status;
    private View shade, overlay;
    private String current = HOME;
    private boolean menuOpen, resumedOnce;
    private View beforeMenu, beforeOverlay;
    private int generation;
    private final Map<String, LinearLayout> menuItems = new LinkedHashMap<>();
    private KodiHomeView catalog;
    private boolean canEditResults;
    private View editFocus;
    private List<Section> sections = new ArrayList<>();
    private List<Service> services;

    /** A streaming service with an app: TMDB lists some more than once ("with ads"), so one entry per app. */
    private static final class Service {
        final String key, name, ids, logo;
        Service(String key, String name, String ids, String logo) {
            this.key = key;
            this.name = name;
            this.ids = ids;
            this.logo = logo;
        }
    }

    private static final class Section {
        final String title;
        final Callable<List<Tmdb.Title>> load;
        String morePath, moreKind;
        Runnable more;
        boolean personal;

        Section(String title, Callable<List<Tmdb.Title>> load) {
            this.title = title;
            this.load = load;
        }

        Section more(String path, String kind) {
            morePath = path;
            moreKind = kind;
            return this;
        }

        Section more(Runnable r) {
            more = r;
            return this;
        }

        Section personal() {
            personal = true;
            return this;
        }
    }

    /** What search and categories show: type, categories (all of them), release year, and words (search only). */
    private static final class Filter {
        String kind = "all", year = "", query = "", sort = "popularity";
        final Set<String> genres = new LinkedHashSet<>();
        boolean services;   // only the person's services (the catalogs)

        Filter copy() {
            Filter f = fromJson(json());
            f.query = query;
            f.services = services;
            return f;
        }

        String json() {
            try {
                return new JSONObject().put("kind", kind).put("year", year).put("sort", sort).put("genres", new JSONArray(genres)).toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        static Filter fromJson(String text) {
            Filter f = new Filter();
            try {
                JSONObject o = new JSONObject(text);
                f.kind = o.optString("kind", "all");
                f.year = o.optString("year", "");
                f.sort = o.optString("sort", "popularity");
                JSONArray g = o.optJSONArray("genres");
                for (int i = 0; g != null && i < g.length(); i++) f.genres.add(g.getString(i));
            } catch (Exception ignored) {
            }
            return f;
        }

        static Filter of(String kind, String... genres) {
            Filter f = new Filter();
            f.kind = kind;
            f.services = true;
            for (String g : genres) f.genres.add(g);
            return f;
        }

        boolean empty() {
            return kind.equals("all") && year.isEmpty() && genres.isEmpty();
        }

        String label() {
            List<String> parts = new ArrayList<>();
            for (String g : genres) {
                String[] d = genre(g);
                if (d != null) parts.add(Ui.tr(d[1], d[2]));
            }
            if (!year.isEmpty()) parts.add(yearLabel(year));
            if (kind.equals("movie")) parts.add(Ui.tr("Filmes", "Movies"));
            if (kind.equals("tv")) parts.add(Ui.tr("Séries", "Shows"));
            return parts.isEmpty() ? Ui.tr("Tudo", "Everything") : TextUtils.join(" · ", parts);
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        Images.init(this);
        k = TvUi.k(this);
        String section = getIntent().getStringExtra(EXTRA_SECTION);
        if (LIST.equals(section)) {
            RemoteKeys.run(this, "tmdb:watchlist");
            finish();
            return;
        }
        root = new FrameLayout(this);
        root.setBackgroundColor(TvUi.BG);
        scroll = new TvUi.Scroll(this);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setClipChildren(false);
        page.setPadding(0, 0, 0, Math.round(80 * k));
        scroll.addView(page);
        root.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        catalog = new KodiHomeView(this, this::open, this::backFromResults, this::openMenu, this::showResultControls);
        catalog.setVisibility(View.GONE);
        root.addView(catalog, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0xDA080B11, 0x70080B11, 0x28080B11}));
        shade.setVisibility(View.GONE);
        root.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        buildMenu();
        setContentView(root);
        if (!Tmdb.hasKey()) {
            sidePanel.setVisibility(View.GONE);
            header(Ui.tr("Explorar", "Explore"), Ui.tr("Gerencie sua conexão em noiro.tv e atualize Stremio & Noiro.",
                    "Manage your connection at noiro.tv, then refresh Stremio & Noiro."));
            return;
        }
        io.execute(() -> {
            services();
            main.post(() -> { if (!isFinishing() && !isDestroyed()) fillMenu(); });
        });
        if (SEARCH.equals(section)) showPage(section);
        else showPage(HOME);
    }

    @Override protected void onResume() {
        super.onResume();
        if (catalog != null) catalog.resume();
        if (resumedOnce && Tmdb.hasKey()) {   // back from a title page: the list, the picks and what was seen may have changed
            refreshPersonal();
        }
        resumedOnce = true;
    }

    @Override protected void onPause() {
        if (catalog != null) catalog.pause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        io.shutdownNow();
        if (catalog != null) catalog.destroy();
        super.onDestroy();
    }

    private void buildMenu() {
        sidePanel = new TvUi.Scroll(this);
        sidePanel.setBackground(TvUi.round(0xEA151A23, Math.round(32 * k)));
        sidePanel.setClipToOutline(true);
        menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(0, Math.round(10 * k), 0, Math.round(10 * k));
        sidePanel.addView(menu);
        FrameLayout.LayoutParams area = new FrameLayout.LayoutParams(Math.round(SIDE_RAIL * k),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL);
        area.setMargins(Math.round(32 * k), Math.round(32 * k), 0, Math.round(32 * k));
        root.addView(sidePanel, area);

        menuItem(menu, SEARCH, false, null);
        menuItem(menu, LAUNCHER_HOME, false, null);
        menuItem(menu, LIST, false, null);
        menuItem(menu, HOME, true, null);
        menuMovable = new LinearLayout(this);
        menuMovable.setOrientation(LinearLayout.VERTICAL);
        menu.addView(menuMovable);
        menuItem(menu, SETTINGS, true, null);
        fillMenu();
    }

    /** The movable part, in the person's order, without what they hid. */
    private void fillMenu() {
        String focusedId = null;
        View focus = getCurrentFocus();
        for (View item : menuItems.values()) {
            if (item == focus && item.getParent() == menuMovable) focusedId = (String) item.getTag();
        }
        for (int i = 0; i < menuMovable.getChildCount(); i++) {
            menuItems.remove((String) menuMovable.getChildAt(i).getTag());
        }
        menuMovable.removeAllViews();
        Set<String> hidden = hidden();
        for (String id : order()) {
            if (hidden.contains(id)) continue;
            Service service = id.startsWith(SERVICE) ? service(id) : null;
            menuItem(menuMovable, id, false, service == null ? null : service.logo);
        }
        setMenuExpanded(menuOpen);
        if (menuOpen && focusedId != null) {
            menuItems.getOrDefault(focusedId, menuItems.get(SEARCH)).requestFocus();
        }
    }

    private void menuItem(LinearLayout into, String id, boolean startsGroup, String logo) {
        String name = label(id);
        LinearLayout item = new LinearLayout(this);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setFocusableInTouchMode(true);
        item.setContentDescription(name);
        item.setTag(id);
        FrameLayout iconArea = new FrameLayout(this);
        int railSize = Math.round(SIDE_RAIL * k);
        if (logo == null || logo.isEmpty()) {
            TvUi.Icon icon = new TvUi.Icon(this, menuIcon(id), 24 * k);
            iconArea.addView(icon, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            ImageView image = new ImageView(this);
            image.setBackground(TvUi.round(TvUi.CARD, 8 * k));
            image.setClipToOutline(true);
            Images.load(image, Tmdb.LOGO + logo);
            int size = Math.round(34 * k);
            iconArea.addView(image, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
        }
        item.addView(iconArea, new LinearLayout.LayoutParams(railSize, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView caption = TvUi.text(this, k, 27, Ui.medium, TvUi.INK);
        caption.setText(name);
        caption.setSingleLine(true);
        caption.setEllipsize(TextUtils.TruncateAt.END);
        caption.setPadding(0, 0, Math.round(16 * k), 0);
        caption.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        item.addView(caption, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        item.setOnFocusChangeListener((view, focused) -> styleItem(item));
        item.setOnClickListener(view -> chooseMenuPage(id));
        if (into == menuMovable) {
            item.setOnLongClickListener(view -> {
                boolean saved = id.startsWith(SAVED);
                TvUi.confirm(this, saved ? Ui.tr("Remover “", "Remove “") + name + Ui.tr("” do menu?", "” from the menu?")
                                : Ui.tr("Ocultar “", "Hide “") + name + Ui.tr("” do menu? Volta em Ajustes.", "”? It comes back in Settings."),
                        saved ? Ui.tr("Remover", "Remove") : Ui.tr("Ocultar", "Hide"), () -> {
                            if (saved) removeSaved(id);
                            else setHidden(id, true);
                            fillMenu();
                        });
                return true;
            });
        }
        LinearLayout.LayoutParams area = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(58 * k));
        if (startsGroup) area.topMargin = Math.round(19 * k);
        into.addView(item, area);
        menuItems.put(id, item);
        styleItem(item);
    }

    private static String menuIcon(String id) {
        switch (id) {
            case SEARCH: return "search";
            case LAUNCHER_HOME: return "home";
            case LIST: return "check";
            case SHOWS: return "tv";
            case HOME: case CATS: return "apps";
            case TOP: return "sparkles";
            case MOVIES: return "play";
            case KIDS: return "person";
            case SETTINGS: return "settings";
            default: return id.startsWith(SAVED) ? "tune" : "tv";
        }
    }

    private void chooseMenuPage(String id) {
        if (LAUNCHER_HOME.equals(id)) {
            RemoteKeys.run(this, "home");
            finish();
        } else if (LIST.equals(id)) {
            closeMenu(true);
            RemoteKeys.run(this, "tmdb:watchlist");
        } else if (id.equals(current)) {
            closeMenu(true);
        } else {
            closeMenu(false);
            showPage(id);
        }
    }

    private void styleItem(LinearLayout item) {
        boolean focused = item.hasFocus(), selected = current.equals(item.getTag());
        int color = focused ? 0xFF151A23 : selected ? 0xFF92D6FF : 0xFFADB4C0;
        item.setBackground(focused ? TvUi.round(TvUi.INK, Math.round(18 * k))
                : selected ? TvUi.round(0xFF24364A, Math.round(18 * k)) : null);
        View icon = ((FrameLayout) item.getChildAt(0)).getChildAt(0);
        if (icon instanceof TvUi.Icon) ((TvUi.Icon) icon).color(color);
        TextView name = (TextView) item.getChildAt(1);
        name.setTextColor(focused || selected ? color : TvUi.INK);
        name.setTypeface(selected || focused ? Ui.semibold : Ui.medium);
    }

    private void restyleMenu() {
        for (LinearLayout item : menuItems.values()) styleItem(item);
    }

    private void setMenuExpanded(boolean expanded) {
        menuOpen = expanded;
        shade.setVisibility(expanded ? View.VISIBLE : View.GONE);
        sidePanel.setDescendantFocusability(expanded ? ViewGroup.FOCUS_AFTER_DESCENDANTS : ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        ViewGroup.LayoutParams size = sidePanel.getLayoutParams();
        size.width = Math.round((expanded ? SIDE_WIDTH : SIDE_RAIL) * k);
        sidePanel.setLayoutParams(size);
        for (Map.Entry<String, LinearLayout> entry : menuItems.entrySet()) {
            String id = entry.getKey();
            LinearLayout item = entry.getValue();
            boolean onRail = SEARCH.equals(id) || LAUNCHER_HOME.equals(id) || LIST.equals(id);
            item.setVisibility(expanded || onRail ? View.VISIBLE : View.GONE);
            item.getChildAt(1).setVisibility(expanded ? View.VISIBLE : View.GONE);
            LinearLayout.LayoutParams area = (LinearLayout.LayoutParams) item.getLayoutParams();
            area.leftMargin = area.rightMargin = expanded ? Math.round(12 * k) : 0;
            item.setLayoutParams(area);
        }
        menuMovable.setVisibility(expanded ? View.VISIBLE : View.GONE);
        sidePanel.setVisibility(expanded || !catalogVisible() ? View.VISIBLE : View.GONE);
        if (!expanded) sidePanel.top();
    }

    private void openMenu() {
        if (menuOpen) return;
        beforeMenu = getCurrentFocus();
        setMenuExpanded(true);
        menuItems.getOrDefault(current, menuItems.get(SEARCH)).requestFocus();
        scroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        catalog.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
    }

    /** Restores the previously focused control when the viewer dismisses the menu. */
    private void closeMenu(boolean restore) {
        if (!menuOpen) return;
        View target = beforeMenu;
        beforeMenu = null;
        setMenuExpanded(false);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        catalog.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        if (restore && (target == null || target == catalog || !target.isShown() || !target.requestFocus())) focusPage();
    }

    private void moveMenuFocus(int step) {
        List<View> entries = new ArrayList<>();
        for (int i = 0; i < menu.getChildCount(); i++) {
            View child = menu.getChildAt(i);
            if (child == menuMovable) {
                for (int j = 0; j < menuMovable.getChildCount(); j++) entries.add(menuMovable.getChildAt(j));
            } else entries.add(child);
        }
        int at = entries.indexOf(getCurrentFocus()) + step;
        if (at >= 0 && at < entries.size()) entries.get(at).requestFocus();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (overlay != null) {
            if (code == KeyEvent.KEYCODE_BACK) {
                if (!down) closeOverlay();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        if (menuOpen) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (!down && !e.isCanceled()) closeMenu(true);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) return true;
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (down) moveMenuFocus(code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        if (catalogVisible()) {
            if (code == KeyEvent.KEYCODE_BACK) {
                if (!down && !e.isCanceled() && !catalog.back()) backFromResults();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT && down && Tmdb.hasKey()) {
            View focused = getCurrentFocus();
            boolean atStart = focused instanceof EditText
                    && ((EditText) focused).getSelectionStart() <= 0
                    && ((EditText) focused).getSelectionEnd() <= 0;
            if (focused == null || atStart || focused.focusSearch(View.FOCUS_LEFT) == null) {
                openMenu();
                return true;
            }
        }
        if (code == KeyEvent.KEYCODE_BACK && !HOME.equals(current) && Tmdb.hasKey()) {
            if (!down && !e.isCanceled()) {
                if (SEARCH.equals(current) && SEARCH.equals(getIntent().getStringExtra(EXTRA_SECTION))) finish();
                else showPage(HOME);
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    // MARK: - The menu's order, hidden items and saved filters (kept on the TV)

    private SharedPreferences prefs() {
        return getSharedPreferences("explore", MODE_PRIVATE);
    }

    private List<String> readList(String key) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs().getString(key, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    private void writeList(String key, List<String> list) {
        prefs().edit().putString(key, new JSONArray(list).toString()).apply();
    }

    private JSONObject saved() {
        try {
            return new JSONObject(prefs().getString("saved", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** Every movable item, in the person's order; ones they never placed (a new service, a new save) go last. */
    private List<String> order() {
        List<String> all = new ArrayList<>();
        for (String id : MOVABLE) all.add(id);
        for (java.util.Iterator<String> it = saved().keys(); it.hasNext(); ) all.add(it.next());
        if (services != null) for (Service s : services) all.add(SERVICE + s.key);
        List<String> out = new ArrayList<>();
        for (String id : readList("order")) if (all.contains(id) && !out.contains(id)) out.add(id);
        for (String id : all) if (!out.contains(id)) out.add(id);
        return out;
    }

    private Set<String> hidden() {
        return new HashSet<>(readList("hidden"));
    }

    private void setHidden(String id, boolean hide) {
        Set<String> h = hidden();
        if (hide) h.add(id); else h.remove(id);
        writeList("hidden", new ArrayList<>(h));
    }

    private void move(String id, int by) {
        List<String> o = order();
        int i = o.indexOf(id), j = i + by;
        if (i < 0 || j < 0 || j >= o.size()) return;
        o.set(i, o.get(j));
        o.set(j, id);
        writeList("order", o);
    }

    private void saveFilter(String id, String name, Filter f) {
        try {
            JSONObject all = saved();
            all.put(id, new JSONObject().put("name", name).put("filter", f.json()));
            prefs().edit().putString("saved", all.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private void removeSaved(String id) {
        JSONObject all = saved();
        all.remove(id);
        prefs().edit().putString("saved", all.toString()).apply();
    }

    private String label(String id) {
        switch (id) {
            case TOP: return Ui.tr("Top 10 do dia", "Today's top 10");
            case MOVIES: return Ui.tr("Filmes", "Movies");
            case SHOWS: return Ui.tr("Séries", "Shows");
            case KIDS: return Ui.tr("Infantil", "Kids");
            case ANIME: return Ui.tr("Animes", "Anime");
            case CATS: return Ui.tr("Categorias", "Categories");
            case SEARCH: return Ui.tr("Buscar", "Search");
            case LAUNCHER_HOME: return Ui.tr("Início", "Home");
            case HOME: return Ui.tr("Explorar", "Explore");
            case LIST: return Ui.tr("Minha lista", "My list");
            case SETTINGS: return Ui.tr("Ajustes", "Settings");
            default:
                if (id.startsWith(SAVED)) {
                    JSONObject s = saved().optJSONObject(id);
                    return s == null ? id : s.optString("name");
                }
                Service s = id.startsWith(SERVICE) ? service(id) : null;
                return s != null ? s.name : id;
        }
    }

    private List<String> recent() {
        return readList("recent");
    }

    private void remember(String q) {
        if (q == null || q.trim().length() < 2) return;
        List<String> list = recent();
        list.removeIf(x -> x.equalsIgnoreCase(q.trim()));
        list.add(0, q.trim());
        writeList("recent", list.size() > 10 ? new ArrayList<>(list.subList(0, 10)) : list);
    }

    // MARK: - Pages

    private void showPage(String id) {
        if (LIST.equals(id)) {
            RemoteKeys.run(this, "tmdb:watchlist");
            return;
        }
        current = id;
        int gen = ++generation;
        feed = null;
        sections = java.util.Collections.emptyList();
        currentTab = null;
        filterKey = null;
        editFocus = null;
        closeOverlay();
        main.removeCallbacks(searchSoon);
        main.removeCallbacks(tabSoon);
        page.removeAllViews();
        scroll.top();
        restyleMenu();
        canEditResults = id.equals(SEARCH) || id.equals(CATS) || id.startsWith(SAVED)
                || id.equals(MOVIES) || id.equals(SHOWS) || id.equals(KIDS) || id.equals(ANIME);
        catalog.startResults(label(id), canEditResults);
        setCatalogVisible(!canEditResults && !SETTINGS.equals(id));
        if (!catalogVisible()) header(label(id), subtitle(id));
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipChildren(false);
        rows.setClipToPadding(false);
        rows.setPadding(Math.round(INSET * k), 0, 0, 0);
        LinearLayout.LayoutParams rowsLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        page.addView(rows, rowsLp);
        status = TvUi.text(this, k, 26, Ui.regular, TvUi.DIM);
        status.setPadding(0, Math.round(30 * k), 0, 0);
        status.setText(Ui.tr("Carregando…", "Loading…"));
        rows.addView(status);
        switch (id) {
            case SEARCH: buildFilterPage(true, null); return;
            case CATS: buildFilterPage(false, null); return;
            case SETTINGS: buildSettings(); return;
            case MOVIES: case SHOWS: case KIDS: case ANIME: buildCatalog(id); return;
            default:
                if (id.startsWith(SAVED)) {
                    buildFilterPage(false, id);
                    return;
                }
        }
        io.execute(() -> {
            List<Section> list = sections(id);
            main.post(() -> {
                if (gen != generation || isFinishing()) return;
                sections = list;
                load(gen, list, false);
            });
        });
    }

    private boolean catalogVisible() { return catalog != null && catalog.getVisibility() == View.VISIBLE; }

    private void setCatalogVisible(boolean visible) {
        catalog.setVisibility(visible ? View.VISIBLE : View.GONE);
        scroll.setVisibility(visible ? View.GONE : View.VISIBLE);
        sidePanel.setVisibility(visible && !menuOpen ? View.GONE : View.VISIBLE);
        if (visible && !menuOpen) catalog.focusResults();
    }

    private void showCatalogResults() {
        if (!catalogVisible()) editFocus = getCurrentFocus();
        setCatalogVisible(true);
    }

    private void showResultControls() {
        setCatalogVisible(false);
        View target = currentTab == null ? editFocus : tabBar.getChildAt(tab);
        scroll.post(() -> {
            if (catalogVisible() || menuOpen || overlay != null) return;
            if (target == null || !target.isShown() || !target.requestFocus()) focusPage();
        });
    }

    private void backFromResults() {
        if (canEditResults) showResultControls();
        else if (!HOME.equals(current)) showPage(HOME);
        else finish();
    }

    private String subtitle(String id) {
        switch (id) {
            case SEARCH: return Ui.tr("Filmes, séries e pessoas · filtre por tipo, categoria e ano", "Movies, shows and people · filter by type, category and year");
            case CATS: return Ui.tr("Escolha categorias e ano no filtro; salve para ter no menu", "Pick categories and year in the filter; save it to the menu");
            case SETTINGS: return Ui.tr("Conta, serviços e a ordem do menu", "Account, services and the menu's order");
            case MOVIES: case SHOWS: case KIDS: case ANIME: return Ui.tr("Filmes e séries nos seus serviços", "Movies and series on your services");
            default: return id.startsWith(SAVED) ? Ui.tr("Filtro salvo", "Saved filter") : Ui.tr("Filmes e séries", "Movies and series");
        }
    }

    private void header(String title, String sub) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Math.round(INSET * k), Math.round(56 * k), 0, Math.round(10 * k));
        TextView h = TvUi.text(this, k, 60, Ui.displaySemibold, TvUi.INK);
        h.setText(title);
        box.addView(h);
        TextView s = TvUi.text(this, k, 26, Ui.regular, TvUi.DIM);
        s.setText(sub);
        s.setPadding(0, Math.round(12 * k), 0, 0);
        box.addView(s);
        page.addView(box, 0);
    }

    private void load(int gen, List<Section> list, boolean personalOnly) {
        int[] left = {0};
        for (Section s : list) if (!personalOnly || s.personal) left[0]++;
        for (int i = 0; i < list.size(); i++) {
            final int slot = i;
            final Section s = list.get(i);
            if (personalOnly && !s.personal) continue;
            io.execute(() -> {
                List<Tmdb.Title> titles;
                try {
                    titles = s.load.call();
                } catch (Exception e) {
                    AppLog.w("explore " + s.title + ": " + e.getMessage());
                    titles = new ArrayList<>();
                }
                final List<Tmdb.Title> got = titles;
                main.post(() -> {
                    if (gen != generation || isFinishing()) return;
                    place(slot, s, got);
                    if (personalOnly) return;
                    left[0]--;
                    if (left[0] == 0) catalog.resultMessage(Ui.tr("Nenhum título disponível.", "No titles available."));
                });
            });
        }
    }

    private void refreshPersonal() {
        if (current.equals(HOME)) load(generation, sections, true);
    }

    private void place(int slot, Section section, List<Tmdb.Title> titles) {
        catalog.resultCatalog(slot, section.title, titles, null, moreOf(section));
    }

    private Runnable moreOf(Section s) {
        if (s.more != null) return s.more;
        if (s.morePath != null) return () -> GridActivity.open(this, s.title, s.morePath, s.moreKind);
        return null;
    }

    private void open(Tmdb.Title t) {
        if (current.equals(SEARCH) && filter != null && !filter.query.isEmpty()) remember(filter.query);
        DetailActivity.open(this, t.kind, t.id);
    }

    private void focusPage() {
        if (menuOpen || overlay != null) return;
        if (catalogVisible()) {
            catalog.focusResults();
            return;
        }
        for (int i = 0; i < rows.getChildCount(); i++) {
            View v = rows.getChildAt(i);
            if (v != status && v.requestFocus()) return;
        }
    }

    // MARK: - Catalog choices

    private int tab;
    private LinearLayout tabBar;
    private Tab currentTab;
    private Runnable tabShow;
    private final Runnable tabSoon = () -> { if (tabShow != null) tabShow.run(); };

    /** Pill tabs; focusing one picks it after a short pause, like a TV app's tabs. */
    private void tabs(String[] labels, java.util.function.IntConsumer show) {
        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setClipChildren(false);
        tabBar.setPadding(Math.round(INSET * k), Math.round(20 * k), Math.round(96 * k), Math.round(10 * k));
        for (int i = 0; i < labels.length; i++) {
            final int which = i;
            TextView t = TvUi.text(this, k, 28, Ui.medium, TvUi.DIM);
            t.setText(labels[i]);
            t.setPadding(Math.round(32 * k), Math.round(16 * k), Math.round(32 * k), Math.round(16 * k));
            t.setFocusableInTouchMode(true);
            t.setOnFocusChangeListener((v, has) -> {
                styleTabs();
                if (has && which != tab) {
                    tab = which;
                    tabShow = () -> show.accept(which);
                    main.removeCallbacks(tabSoon);
                    main.postDelayed(tabSoon, 260);
                }
            });
            t.setOnClickListener(v -> {
                main.removeCallbacks(tabSoon);
                show.accept(which);
                showCatalogResults();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Math.round(14 * k);
            tabBar.addView(t, lp);
        }
        TvUi.HScroll bar = new TvUi.HScroll(this, Math.round(260 * k));
        bar.addView(tabBar);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = -Math.round(INSET * k);
        rows.addView(bar, lp);
        tab = 0;
        styleTabs();
        tabBar.getChildAt(0).requestFocus();
    }

    private void styleTabs() {
        for (int i = 0; i < tabBar.getChildCount(); i++) {
            TextView t = (TextView) tabBar.getChildAt(i);
            boolean focused = t.hasFocus(), here = i == tab;
            t.setBackground(focused ? TvUi.round(TvUi.INK, 40 * k) : here ? TvUi.round(0x33FFFFFF, 40 * k) : null);
            t.setTextColor(focused ? 0xFF14161A : here ? TvUi.INK : TvUi.DIM);
            t.setTypeface(here || focused ? Ui.semibold : Ui.medium);
        }
    }

    private TextView note(String text) {
        TextView t = TvUi.text(this, k, 26, Ui.regular, TvUi.DIM);
        t.setText(text);
        t.setPadding(0, Math.round(24 * k), 0, 0);
        return t;
    }

    /** A catalog tab: a TMDB list path, or a filter. */
    private static final class Tab {
        final String label, path, kind;
        final Filter filter;
        Tab(String label, String path, String kind) {
            this.label = label;
            this.path = path;
            this.kind = kind;
            this.filter = null;
        }
        Tab(String label, Filter filter) {
            this.label = label;
            this.path = null;
            this.kind = null;
            this.filter = filter;
        }
    }

    /** Movies, Shows, Kids and Anime, like the TV catalogs people know: tabs of kinds over a grid of posters. */
    private void buildCatalog(String id) {
        status.setVisibility(View.GONE);
        List<Tab> list = new ArrayList<>();
        switch (id) {
            case MOVIES:
                list.add(new Tab(Ui.tr("Em alta", "Trending"), "/trending/movie/week", "movie"));
                list.add(new Tab(Ui.tr("Lançamentos", "New"), sorted(Filter.of("movie"), "new")));
                list.add(new Tab(Ui.tr("Mais votados", "Top rated"), sorted(Filter.of("movie"), "votes")));
                for (String g : new String[] {"acao", "comedia", "drama", "terror", "suspense", "ficcao", "romance", "animacao", "documentarios", "nacionais"}) {
                    list.add(new Tab(genreName(g), Filter.of("movie", g)));
                }
                break;
            case SHOWS:
                list.add(new Tab(Ui.tr("Em alta", "Trending"), "/trending/tv/week", "tv"));
                list.add(new Tab(Ui.tr("No ar", "On the air"), "/tv/on_the_air", "tv"));
                list.add(new Tab(Ui.tr("Mais votadas", "Top rated"), sorted(Filter.of("tv"), "votes")));
                for (String g : new String[] {"drama", "comedia", "crime", "ficcao", "misterio", "reality", "doramas", "novela", "nacionais"}) {
                    list.add(new Tab(genreName(g), Filter.of("tv", g)));
                }
                break;
            case KIDS:
                list.add(new Tab(Ui.tr("Tudo", "All"), Filter.of("all", "infantil")));
                list.add(new Tab(Ui.tr("Desenhos", "Cartoons"), Filter.of("tv", "animacao", "infantil")));
                list.add(new Tab(Ui.tr("Filmes", "Movies"), Filter.of("movie", "infantil")));
                list.add(new Tab(Ui.tr("Animação", "Animation"), Filter.of("movie", "animacao", "familia")));
                list.add(new Tab(Ui.tr("Mais votados", "Top rated"), sorted(Filter.of("all", "infantil"), "votes")));
                break;
            default:   // ANIME
                list.add(new Tab(Ui.tr("Em alta", "Trending"), Filter.of("all", "anime")));
                list.add(new Tab(Ui.tr("Séries", "Shows"), Filter.of("tv", "anime")));
                list.add(new Tab(Ui.tr("Filmes", "Movies"), Filter.of("movie", "anime")));
                list.add(new Tab(Ui.tr("Lançamentos", "New"), sorted(Filter.of("all", "anime"), "new")));
                for (String g : new String[] {"acao", "comedia", "romance", "fantasia", "misterio"}) {
                    list.add(new Tab(genreName(g), Filter.of("all", "anime", g)));
                }
                list.add(new Tab(Ui.tr("Mais votados", "Top rated"), sorted(Filter.of("all", "anime"), "votes")));
        }
        String[] labels = new String[list.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = list.get(i).label;
        tabs(labels, which -> showCatalogTab(list.get(which)));
        showCatalogTab(list.get(0));
        showCatalogResults();
    }

    private static Filter sorted(Filter f, String sort) {
        f.sort = sort;
        return f;
    }

    private void showCatalogTab(Tab tab) {
        if (currentTab == tab) return;
        currentTab = tab;
        Pager pager = tab.path != null ? p -> Tmdb.titles(Tmdb.get(tab.path + (tab.path.contains("?") ? "&" : "?") + "page=" + p), tab.kind)
                : p -> fetch(tab.filter, p);
        startFeed(tab.label, pager);
    }

    // MARK: - Paged results

    private interface Pager { List<Tmdb.Title> page(int p) throws Exception; }

    private Feed feed;

    private final class Feed {
        final String heading;
        final Pager pager;
        final Map<String, Tmdb.Title> titles = new LinkedHashMap<>();
        int page;
        boolean loading, done;

        Feed(String heading, Pager pager) {
            this.heading = heading;
            this.pager = pager;
        }

        void more() {
            if (loading || done || page >= 25 || this != feed) return;
            loading = true;
            int want = page + 1;
            io.execute(() -> {
                List<Tmdb.Title> found;
                try { found = pager.page(want); }
                catch (Exception e) { found = null; }
                List<Tmdb.Title> list = found;
                main.post(() -> {
                    if (this != feed || isFinishing() || isDestroyed()) return;
                    loading = false;
                    if (list == null) {
                        if (titles.isEmpty()) filterKey = null;
                        catalog.resultMessage(Ui.tr("Não deu para carregar.", "Couldn't load results."));
                        return;
                    }
                    page = want;
                    done = list.isEmpty();
                    int previous = titles.size();
                    for (Tmdb.Title title : list) titles.putIfAbsent(title.key(), title);
                    if (titles.size() > previous) {
                        catalog.resultCatalog(0, heading, new ArrayList<>(titles.values()), this::more, null);
                    }
                    if (titles.isEmpty() && done) catalog.resultMessage(Ui.tr("Nada encontrado.", "Nothing found."));
                    if (titles.size() < 21 && !done) more();
                });
            });
        }
    }

    private void startFeed(String heading, Pager pager) {
        catalog.startResults(heading, true);
        feed = new Feed(heading, pager);
        feed.more();
    }

    // MARK: - Search and categories: the same filter

    private Filter filter;
    private String filterKey;
    private String savedId;
    private LinearLayout chips;
    private final Runnable searchSoon = this::runFilter;

    /** `search`: words plus the filter; otherwise only the filter, which can be saved to the menu. */
    private void buildFilterPage(boolean search, String saved) {
        status.setVisibility(View.GONE);
        savedId = saved;
        filter = saved != null ? Filter.fromJson(saved().optJSONObject(saved) == null ? "{}" : saved().optJSONObject(saved).optString("filter"))
                : new Filter();
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setClipChildren(false);
        bar.setPadding(0, Math.round(16 * k), 0, 0);
        View first;
        if (search) {
            EditText field = new EditText(this);
            field.setSingleLine(true);
            field.setTextSize(TypedValue.COMPLEX_UNIT_PX, 34 * k);
            field.setTextColor(TvUi.INK);
            field.setHintTextColor(TvUi.FAINT);
            field.setTypeface(Ui.medium);
            field.setHint(Ui.tr("Filme, série ou pessoa", "Movie, show or person"));
            field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
            field.setPadding(Math.round(36 * k), Math.round(22 * k), Math.round(36 * k), Math.round(22 * k));
            field.setBackground(TvUi.round(0x1FFFFFFF, 40 * k));
            field.setOnFocusChangeListener((v, has) -> v.setBackground(TvUi.round(has ? 0x47FFFFFF : 0x1FFFFFFF, 40 * k)));
            field.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {
                    filter.query = s.toString().trim();
                    main.removeCallbacks(searchSoon);
                    main.postDelayed(searchSoon, 600);
                }
            });
            field.setOnEditorActionListener((v, action, event) -> {
                ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(v.getWindowToken(), 0);
                main.removeCallbacks(searchSoon);
                remember(filter.query);
                runFilter();
                showCatalogResults();
                return true;
            });
            bar.addView(field, new LinearLayout.LayoutParams(Math.round(1000 * k), ViewGroup.LayoutParams.WRAP_CONTENT));
            first = field;
        } else {
            first = null;
        }
        LinearLayout filterButton = TvUi.iconButton(this, k, "tune", Ui.tr("Filtros", "Filters"), v -> openFilter());
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fl.leftMargin = search ? Math.round(20 * k) : 0;
        bar.addView(filterButton, fl);
        if (!search) {
            TextView save = TvUi.button(this, k, saved != null ? Ui.tr("Salvar alterações", "Save changes") : Ui.tr("Salvar no menu", "Save to menu"),
                    v -> askSave());
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sl.leftMargin = Math.round(20 * k);
            bar.addView(save, sl);
        }
        rows.addView(bar);
        chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.VERTICAL);
        chips.setClipChildren(false);
        rows.addView(chips);
        if (search && !recent().isEmpty()) {
            TvUi.Flow recent = new TvUi.Flow(this, Math.round(14 * k), Math.round(14 * k));
            recent.setPadding(Math.round(6 * k), Math.round(22 * k), Math.round(INSET * k), 0);
            TextView label = TvUi.text(this, k, 24, Ui.medium, TvUi.FAINT);
            label.setText(Ui.tr("Recentes", "Recent"));
            label.setPadding(0, Math.round(14 * k), Math.round(6 * k), 0);
            recent.addView(label);
            EditText field = (EditText) first;
            for (String q : recent()) {
                TextView b = TvUi.button(this, k, q, v -> field.setText(q));
                b.setTextSize(TypedValue.COMPLEX_UNIT_PX, 24 * k);
                recent.addView(b);
            }
            rows.addView(recent, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        TextView showResults = TvUi.button(this, k, Ui.tr("Ver resultados", "Show results"), v -> {
            main.removeCallbacks(searchSoon);
            runFilter();
            showCatalogResults();
        });
        LinearLayout.LayoutParams resultButton = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        resultButton.topMargin = Math.round(28 * k);
        rows.addView(showResults, resultButton);
        showChips();
        runFilter();
        View initial = first != null ? first : filterButton;
        initial.post(() -> {
            if (initial.isShown() && !menuOpen && overlay == null) initial.requestFocus();
        });
    }

    /** The filter's parts as chips under the bar; OK on one takes it off. */
    private void showChips() {
        chips.removeAllViews();
        if (filter.empty()) return;
        TvUi.Flow flow = new TvUi.Flow(this, Math.round(14 * k), Math.round(14 * k));
        flow.setPadding(Math.round(6 * k), Math.round(22 * k), Math.round(INSET * k), 0);
        if (!filter.kind.equals("all")) {
            flow.addView(TvUi.toggle(this, k, filter.kind.equals("movie") ? Ui.tr("Filmes", "Movies") : Ui.tr("Séries", "Shows"), true, v -> {
                filter.kind = "all";
                filterChanged();
            }));
        }
        for (String g : new ArrayList<>(filter.genres)) {
            flow.addView(TvUi.toggle(this, k, genreName(g), true, v -> {
                filter.genres.remove(g);
                filterChanged();
            }));
        }
        if (!filter.year.isEmpty()) {
            flow.addView(TvUi.toggle(this, k, yearLabel(filter.year), true, v -> {
                filter.year = "";
                filterChanged();
            }));
        }
        chips.addView(flow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void filterChanged() {
        boolean focusInChips = chips.hasFocus();
        showChips();
        runFilter();
        if (focusInChips) focusPage();
    }

    /** New results: words go to TMDB's search (then the filter is applied here); without words, discover. */
    private void runFilter() {
        Filter selected = filter.copy();
        String key = selected.json() + "|" + selected.query;
        if (key.equals(filterKey)) return;
        filterKey = key;
        String heading = label(current);
        if (selected.query.isEmpty() && selected.empty() && current.equals(SEARCH)) {
            startFeed(Ui.tr("Em alta na semana", "Trending this week"),
                    p -> Tmdb.titles(Tmdb.get("/trending/all/week?page=" + p), "movie"));
            return;
        }
        startFeed(selected.query.isEmpty() ? heading : selected.query, p -> fetch(selected, p));
    }

    /** One page of a filter's titles: shows and movies mixed, or TMDB's search narrowed to the filter. */
    private List<Tmdb.Title> fetch(Filter f, int page) throws Exception {
        if (!f.query.isEmpty()) {   // three of TMDB's pages per page here: the filter may leave few of each
            List<Tmdb.Title> out = new ArrayList<>();
            String path = "/search/multi?include_adult=false&query=" + URLEncoder.encode(f.query, "UTF-8") + "&page=";
            for (int p = (page - 1) * 3 + 1; p <= page * 3; p++) {
                JSONObject o = Tmdb.get(path + p);
                for (Tmdb.Title t : Tmdb.titles(o, "movie")) if (matches(f, t)) out.add(t);
                if (p >= o.optInt("total_pages", 1)) break;
            }
            return out;
        }
        List<Tmdb.Title> tv = new ArrayList<>(), movies = new ArrayList<>();
        String tvParams = f.kind.equals("movie") ? null : params(f, "tv");
        String movieParams = f.kind.equals("tv") ? null : params(f, "movie");
        if (tvParams != null) tv = Tmdb.titles(Tmdb.get(discoverPath("tv", tvParams + "&page=" + page)), "tv");
        if (movieParams != null) movies = Tmdb.titles(Tmdb.get(discoverPath("movie", movieParams + "&page=" + page)), "movie");
        return mix(tv, movies);
    }

    /** Discover parameters for one type, or null when a chosen category has nothing of that type. */
    private String params(Filter f, String kind) {
        boolean movie = kind.equals("movie");
        List<String> ids = new ArrayList<>(), keywords = new ArrayList<>(), origins = new ArrayList<>();
        for (String key : f.genres) {
            String[] g = genre(key);
            if (g == null) continue;
            String id = movie ? g[3] : g[4];
            if (id == null && g[5] == null && g[6] == null) return null;
            if (id != null && !ids.contains(id)) ids.add(id);
            if (g[5] != null) keywords.add(g[5]);
            if (g[6] != null) origins.add(g[6]);
        }
        StringBuilder p = new StringBuilder();
        if (!ids.isEmpty()) p.append("&with_genres=").append(TextUtils.join(",", ids));
        if (!keywords.isEmpty()) p.append("&with_keywords=").append(TextUtils.join("|", keywords));
        if (!origins.isEmpty()) p.append("&with_origin_country=").append(TextUtils.join("|", origins));
        String[] range = yearRange(f.year);
        String field = movie ? "primary_release_date" : "first_air_date";
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
        if (range != null) p.append("&").append(field).append(".gte=").append(range[0]).append("&").append(field).append(".lte=").append(range[1]);
        switch (f.sort) {
            case "new":
                p.append("&sort_by=").append(field).append(".desc&").append(field).append(".lte=").append(today).append("&vote_count.gte=10");
                break;
            case "votes":
                p.append("&sort_by=vote_average.desc&vote_count.gte=").append(movie ? 500 : 200);
                break;
            default:
                p.append("&sort_by=popularity.desc&vote_count.gte=").append(range != null ? 5 : 20);
        }
        if (f.services) p.append(servicesParam());
        return p.toString();
    }

    /** The filter applied to a search result (TMDB's search takes no filters). */
    private static boolean matches(Filter f, Tmdb.Title t) {
        if (!f.kind.equals("all") && !f.kind.equals(t.kind)) return false;
        if (t.kind.equals("person")) return f.empty();
        String[] range = yearRange(f.year);
        if (range != null && (t.date.length() < 10 || t.date.compareTo(range[0]) < 0 || t.date.compareTo(range[1]) > 0)) return false;
        for (String key : f.genres) {
            String[] g = genre(key);
            if (g == null) continue;
            String id = t.kind.equals("movie") ? g[3] : g[4];
            if (id != null) {
                boolean has = false;
                for (int x : t.genres) has |= String.valueOf(x).equals(id);
                if (!has) return false;
            }
            if (g[7] != null && !g[7].equals(t.lang)) return false;
            if (id == null && g[7] == null) return false;
        }
        return true;
    }

    private static String[] genre(String key) {
        for (String[] g : GENRES) if (g[0].equals(key)) return g;
        return null;
    }

    private static String genreName(String key) {
        String[] g = genre(key);
        return g == null ? key : Ui.tr(g[1], g[2]);
    }

    /** "" (any), a year, a decade ("2010s") or "old" (before 1990). */
    private static List<String> years() {
        List<String> out = new ArrayList<>();
        int now = Calendar.getInstance().get(Calendar.YEAR);
        for (int y = now; y > now - 7; y--) out.add(String.valueOf(y));
        out.add("2010s");
        out.add("2000s");
        out.add("1990s");
        out.add("old");
        return out;
    }

    private static String yearLabel(String y) {
        if (y.equals("old")) return Ui.tr("Antes de 1990", "Before 1990");
        if (y.endsWith("s")) return Ui.tr ? y.substring(0, 4) + "'ler" : Ui.tr("Anos ", "The ") + y.substring(0, 4) + (Ui.tr("", "s"));
        return y;
    }

    private static String[] yearRange(String y) {
        if (y == null || y.isEmpty()) return null;
        if (y.equals("old")) return new String[] {"1900-01-01", "1989-12-31"};
        int from = Integer.parseInt(y.substring(0, 4));
        int to = y.endsWith("s") ? from + 9 : from;
        return new String[] {from + "-01-01", to + "-12-31"};
    }

    /** The filter panel, over the page on the right: type, categories (several), release; Apply or Clear. */
    private void openFilter() {
        if (overlay != null) return;
        beforeOverlay = getCurrentFocus();
        Filter draft = filter.copy();
        FrameLayout layer = new FrameLayout(this);
        View dim = new View(this);
        dim.setBackgroundColor(0x99000000);
        layer.addView(dim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TvUi.Scroll panelScroll = new TvUi.Scroll(this);
        panelScroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[] {0xF512151C, 0xFC12151C}));
        panelScroll.setElevation(24 * k);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClipChildren(false);
        panel.setPadding(Math.round(56 * k), Math.round(56 * k), Math.round(56 * k), Math.round(56 * k));
        TextView h = TvUi.text(this, k, 48, Ui.displaySemibold, TvUi.INK);
        h.setText(Ui.tr("Filtros", "Filters"));
        panel.addView(h);
        panel.addView(panelLabel(Ui.tr("Tipo", "Type")));
        TvUi.Flow kinds = panelFlow();
        String[][] kindOptions = {{"all", Ui.tr("Tudo", "All")}, {"movie", Ui.tr("Filmes", "Movies")}, {"tv", Ui.tr("Séries", "Shows")}};
        List<TextView> kindChips = new ArrayList<>();
        for (String[] o : kindOptions) {
            TextView c = TvUi.toggle(this, k, o[1], draft.kind.equals(o[0]), v -> {
                draft.kind = o[0];
                for (TextView other : kindChips) if (other != v && Boolean.TRUE.equals(other.getTag())) other.performClick();
                v.setTag(true);
            });
            kindChips.add(c);
            kinds.addView(c);
        }
        panel.addView(kinds);
        panel.addView(panelLabel(Ui.tr("Categorias (uma ou mais)", "Categories (one or more)")));
        TvUi.Flow cats = panelFlow();
        for (String[] g : GENRES) {
            cats.addView(TvUi.toggle(this, k, Ui.tr(g[1], g[2]), draft.genres.contains(g[0]), v -> {
                if (Boolean.TRUE.equals(v.getTag())) draft.genres.add(g[0]); else draft.genres.remove(g[0]);
            }));
        }
        panel.addView(cats);
        panel.addView(panelLabel(Ui.tr("Lançamento", "Release")));
        TvUi.Flow yrs = panelFlow();
        List<TextView> yearChips = new ArrayList<>();
        List<String> options = new ArrayList<>();
        options.add("");
        options.addAll(years());
        for (String y : options) {
            TextView c = TvUi.toggle(this, k, y.isEmpty() ? Ui.tr("Qualquer ano", "Any year") : yearLabel(y), draft.year.equals(y), v -> {
                draft.year = y;
                for (TextView other : yearChips) if (other != v && Boolean.TRUE.equals(other.getTag())) other.performClick();
                v.setTag(true);
            });
            yearChips.add(c);
            yrs.addView(c);
        }
        panel.addView(yrs);
        TvUi.Flow buttons = panelFlow();
        buttons.setPadding(0, Math.round(40 * k), 0, 0);
        TextView apply = TvUi.button(this, k, Ui.tr("Aplicar", "Apply"), v -> {
            String query = filter.query;
            filter = draft;
            filter.query = query;
            closeOverlay();
            filterChanged();
            showCatalogResults();
        });
        buttons.addView(apply);
        buttons.addView(TvUi.button(this, k, Ui.tr("Limpar", "Clear"), v -> {
            String query = filter.query;
            filter = new Filter();
            filter.query = query;
            closeOverlay();
            filterChanged();
        }));
        panel.addView(buttons);
        panelScroll.addView(panel);
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(Math.round(1040 * k), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
        layer.addView(panelScroll, pl);
        overlay = layer;
        root.addView(layer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        layer.setAlpha(0);
        layer.animate().alpha(1).setDuration(180).start();
        kinds.getChildAt(0).requestFocus();
    }

    private TextView panelLabel(String text) {
        TextView t = TvUi.text(this, k, 24, Ui.semibold, TvUi.FAINT);
        t.setText(text.toUpperCase(Locale.ROOT));
        t.setLetterSpacing(0.1f);
        t.setPadding(0, Math.round(34 * k), 0, Math.round(16 * k));
        return t;
    }

    private TvUi.Flow panelFlow() {
        TvUi.Flow f = new TvUi.Flow(this, Math.round(14 * k), Math.round(14 * k));
        f.setPadding(Math.round(4 * k), 0, 0, 0);
        f.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return f;
    }

    private void closeOverlay() {
        if (overlay == null) return;
        View layer = overlay;
        overlay = null;
        root.removeView(layer);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (beforeOverlay != null && beforeOverlay.isAttachedToWindow()) beforeOverlay.requestFocus();
    }

    /** Categories: saves the filter as a menu item (a name the person can change). */
    private void askSave() {
        if (filter.empty()) {
            Toast.makeText(this, Ui.tr("Escolha algo no filtro antes de salvar.", "Pick something in the filter first."), Toast.LENGTH_SHORT).show();
            return;
        }
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setText(savedId != null ? label(savedId) : filter.label());
        new AlertDialog.Builder(this, R.style.Noiro_MenuDialog)
                .setTitle(Ui.tr("Nome no menu", "Name in the menu"))
                .setView(name)
                .setPositiveButton(Ui.tr("Salvar", "Save"), (d, w) -> {
                    String n = name.getText().toString().trim();
                    String id = savedId != null ? savedId : SAVED + System.currentTimeMillis();
                    saveFilter(id, n.isEmpty() ? filter.label() : n, filter);
                    fillMenu();
                    Toast.makeText(this, Ui.tr("Salvo no menu", "Saved to the menu"), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(Ui.tr("Cancelar", "Cancel"), null)
                .show();
    }

    // MARK: - Settings: account, services and the menu's order

    private void buildSettings() {
        status.setVisibility(View.GONE);
        TvUi.Flow buttons = new TvUi.Flow(this, Math.round(18 * k), Math.round(18 * k));
        buttons.setPadding(Math.round(6 * k), Math.round(30 * k), Math.round(INSET * k), 0);
        buttons.addView(TvUi.button(this, k, "Stremio & Noiro", v -> startActivity(new Intent(this, OnboardingActivity.class).putExtra("manage", true))));
        buttons.addView(TvUi.button(this, k, Ui.tr("Serviços e apps", "Services and apps"), v -> startActivity(new Intent(this, ServicesActivity.class))));
        rows.addView(buttons, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView h = TvUi.text(this, k, 32, Ui.semibold, TvUi.INK);
        h.setText(Ui.tr("Menu lateral", "Side menu"));
        h.setPadding(0, Math.round(50 * k), 0, Math.round(8 * k));
        rows.addView(h);
        rows.addView(note(Ui.tr("Buscar, Início e Minha lista ficam sempre no topo. OK num item: mover, ocultar ou remover.",
                "Search, Home and My list always stay on top. OK on an item: move, hide or remove.")));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, Math.round(20 * k), 0, 0);
        rows.addView(list);
        fillOrganizer(list, null);
        buttons.getChildAt(0).requestFocus();
    }

    private void fillOrganizer(LinearLayout list, String focusId) {
        list.removeAllViews();
        Set<String> hid = hidden();
        List<String> ids = order();
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            int at = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Math.round(28 * k), Math.round(18 * k), Math.round(28 * k), Math.round(18 * k));
            TextView num = TvUi.text(this, k, 24, Ui.medium, TvUi.FAINT);
            num.setText(String.valueOf(i + 1));
            num.setMinWidth(Math.round(54 * k));
            row.addView(num);
            TextView name = TvUi.text(this, k, 28, Ui.medium, hid.contains(id) ? TvUi.FAINT : TvUi.INK);
            name.setText(label(id));
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView state = TvUi.text(this, k, 24, Ui.regular, TvUi.DIM);
            state.setText(hid.contains(id) ? Ui.tr("Oculto", "Hidden") : id.startsWith(SAVED) ? Ui.tr("Filtro salvo", "Saved filter")
                    : id.startsWith(SERVICE) ? Ui.tr("Serviço", "Service") : "");
            row.addView(state);
            TvUi.softFocus(row, k, 18 * k);
            row.setOnClickListener(v -> {
                List<String> labels = new ArrayList<>();
                List<Runnable> actions = new ArrayList<>();
                if (at > 0) {
                    labels.add(Ui.tr("Mover para cima", "Move up"));
                    actions.add(() -> move(id, -1));
                }
                if (at < ids.size() - 1) {
                    labels.add(Ui.tr("Mover para baixo", "Move down"));
                    actions.add(() -> move(id, 1));
                }
                boolean isHidden = hidden().contains(id);
                labels.add(isHidden ? Ui.tr("Mostrar no menu", "Show in the menu") : Ui.tr("Ocultar do menu", "Hide from the menu"));
                actions.add(() -> setHidden(id, !isHidden));
                if (id.startsWith(SAVED)) {
                    labels.add(Ui.tr("Remover", "Remove"));
                    actions.add(() -> removeSaved(id));
                }
                labels.add(Ui.tr("Cancelar", "Cancel"));
                actions.add(null);
                int[] xy = new int[2];
                v.getLocationInWindow(xy);
                Rect anchor = new Rect(xy[0] + v.getWidth() - Math.round(220 * k), xy[1], xy[0] + v.getWidth(), xy[1] + v.getHeight());
                TvUi.popover(this, v, anchor, label(id), labels.toArray(new String[0]), which -> {
                    Runnable r = actions.get(which);
                    if (r == null) return;
                    r.run();
                    fillMenu();
                    fillOrganizer(list, id);
                });
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.round(1100 * k), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Math.round(10 * k);
            list.addView(row, lp);
            if (id.equals(focusId)) row.post(row::requestFocus);
        }
    }

    // MARK: - Explore catalogs

    private List<Section> sections(String id) {
        List<Section> out = new ArrayList<>();
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
        switch (id) {
            case TOP:
                out.add(new Section(Ui.tr("Top 10 séries hoje", "Top 10 shows today"), () -> top(Tmdb.titles(Tmdb.get("/trending/tv/day"), "tv"))));
                out.add(new Section(Ui.tr("Top 10 filmes hoje", "Top 10 movies today"), () -> top(Tmdb.titles(Tmdb.get("/trending/movie/day"), "movie"))));
                return out;
            case HOME: {
                out.add(new Section(Ui.tr("Minha lista", "My list"), () -> Tmdb.watchlist(this)).personal().more(() -> showPage(LIST)));
                out.add(new Section(Ui.tr("Recomendado para você", "Recommended for you"), this::forYou).personal());
                List<Tmdb.Title> seen = Tmdb.seen(this);
                Tmdb.Title last = seen.isEmpty() ? null : seen.get(0);
                if (last != null) {
                    out.add(new Section(Ui.tr("Porque você viu ", "Because you watched ") + last.name,
                            () -> fresh(Tmdb.titles(Tmdb.get("/" + last.kind + "/" + last.id + "/recommendations"), last.kind)))
                            .personal().more("/" + last.kind + "/" + last.id + "/recommendations", last.kind));
                }
                out.add(new Section(Ui.tr("Em alta hoje", "Trending today"), () -> Tmdb.titles(Tmdb.get("/trending/all/day"), "movie"))
                        .more("/trending/all/day", "movie"));
                out.add(new Section(Ui.tr("Séries em alta na semana", "Trending shows this week"), () -> mine("tv", Tmdb.titles(Tmdb.get("/trending/tv/week"), "tv")))
                        .more(() -> showPage(SHOWS)));
                out.add(new Section(Ui.tr("Filmes em alta na semana", "Trending movies this week"), () -> mine("movie", Tmdb.titles(Tmdb.get("/trending/movie/week"), "movie")))
                        .more(() -> showPage(MOVIES)));
                int n = 0;
                for (Service s : services()) {
                    if (n++ >= 6) break;
                    out.add(new Section(Ui.tr("Em alta na ", "Trending on ") + s.name, () -> mix(
                            discover("tv", "&with_watch_providers=" + s.ids + "&sort_by=popularity.desc"),
                            discover("movie", "&with_watch_providers=" + s.ids + "&sort_by=popularity.desc")))
                            .more(() -> showPage(SERVICE + s.key)));
                }
                out.add(new Section(Ui.tr("Animes em alta", "Trending anime"), () -> fetch(Filter.of("all", "anime"), 1)).more(() -> showPage(ANIME)));
                out.add(new Section(Ui.tr("Para as crianças", "For the kids"), () -> fetch(Filter.of("all", "infantil"), 1)).more(() -> showPage(KIDS)));
                out.add(new Section(Ui.tr("Favoritos", "Favorites"), () -> Tmdb.accountList(this, "favorite")).personal());
                out.add(new Section(Ui.tr("Suas notas", "Your ratings"), () -> Tmdb.accountList(this, "rated")).personal());
                out.add(new Section(Ui.tr("Pessoas em alta", "Trending people"), () -> Tmdb.titles(Tmdb.get("/trending/person/week"), "person"))
                        .more("/trending/person/week", "person"));
                return out;
            }
            default: {
                Service s = service(id);
                if (s == null) return out;
                String p = "&with_watch_providers=" + s.ids;
                out.add(new Section(Ui.tr("Séries em alta", "Trending shows"), () -> discover("tv", p + "&sort_by=popularity.desc"))
                        .more(discoverPath("tv", p + "&sort_by=popularity.desc"), "tv"));
                out.add(new Section(Ui.tr("Filmes em alta", "Trending movies"), () -> discover("movie", p + "&sort_by=popularity.desc"))
                        .more(discoverPath("movie", p + "&sort_by=popularity.desc"), "movie"));
                String fresh = p + "&sort_by=primary_release_date.desc&primary_release_date.lte=" + today + "&vote_count.gte=20";
                out.add(new Section(Ui.tr("Filmes recém-chegados", "New movies"), () -> discover("movie", fresh)).more(discoverPath("movie", fresh), "movie"));
                String newShows = p + "&sort_by=first_air_date.desc&first_air_date.lte=" + today + "&vote_count.gte=10";
                out.add(new Section(Ui.tr("Séries novas", "New shows"), () -> discover("tv", newShows)).more(discoverPath("tv", newShows), "tv"));
                String best = p + "&sort_by=vote_average.desc&vote_count.gte=500";
                out.add(new Section(Ui.tr("Séries mais bem avaliadas", "Top rated shows"), () -> discover("tv", best)).more(discoverPath("tv", best), "tv"));
                out.add(new Section(Ui.tr("Filmes mais bem avaliados", "Top rated movies"), () -> discover("movie", best)).more(discoverPath("movie", best), "movie"));
                return out;
            }
        }
    }

    private static List<Tmdb.Title> top(List<Tmdb.Title> list) {
        return list.size() > 10 ? new ArrayList<>(list.subList(0, 10)) : list;
    }

    // MARK: - Services and TMDB helpers

    /** The services with an app (or all, with "show all"), one per app, most watched first. Asked once. */
    private synchronized List<Service> services() {
        if (services != null) return services;
        List<Service> out = new ArrayList<>();
        try {
            Map<String, Service> byApp = new LinkedHashMap<>();
            Map<String, StringBuilder> ids = new HashMap<>();
            for (Providers.Provider p : Providers.usable(this)) {
                String app = Providers.app(this, p.id, p.name);
                String key = app != null ? app : "p" + p.id;
                if (!byApp.containsKey(key)) {
                    byApp.put(key, new Service(key, p.name, "", p.logo));
                    ids.put(key, new StringBuilder());
                }
                StringBuilder b = ids.get(key);
                b.append(b.length() == 0 ? "" : "|").append(p.id);
            }
            for (Service s : byApp.values()) out.add(new Service(s.key, s.name, ids.get(s.key).toString(), s.logo));
        } catch (Exception e) {
            AppLog.w("explore services: " + e.getMessage());
            return out;   // not kept: the next page asks again
        }
        if (out.size() > 12) out = new ArrayList<>(out.subList(0, 12));
        services = out;
        return out;
    }

    private Service service(String pageId) {
        if (services == null) return null;
        String key = pageId.startsWith(SERVICE) ? pageId.substring(SERVICE.length()) : pageId;
        for (Service s : services) if (s.key.equals(key)) return s;
        return null;
    }

    /** Only the person's services, as discover parameters ("" when there are none; region only with "show all"). */
    private String servicesParam() {
        if (Prefs.showAllProviders()) return "&watch_region=" + Tmdb.REGION;
        StringBuilder ids = new StringBuilder();
        for (Service s : services()) ids.append(ids.length() == 0 ? "" : "|").append(s.ids);
        return ids.length() == 0 ? "" : "&watch_region=" + Tmdb.REGION + "&with_watch_providers=" + ids
                + "&with_watch_monetization_types=flatrate";
    }

    private static String discoverPath(String kind, String params) {
        String region = params.contains("watch_region") ? "" : "&watch_region=" + Tmdb.REGION;
        return "/discover/" + kind + "?include_adult=false" + region + params;
    }

    private List<Tmdb.Title> discover(String kind, String params) throws Exception {
        return Tmdb.titles(Tmdb.get(discoverPath(kind, params)), kind);
    }

    /** Trending, kept to the person's services unless "show all" is on. */
    private List<Tmdb.Title> mine(String kind, List<Tmdb.Title> trending) throws Exception {
        if (Prefs.showAllProviders()) return trending;
        String services = servicesParam();
        if (services.isEmpty()) return trending;
        Set<Integer> ok = new HashSet<>();
        for (int page = 1; page <= 2; page++) {
            for (Tmdb.Title t : Tmdb.titles(Tmdb.get("/discover/" + kind + "?sort_by=popularity.desc&page=" + page + services), kind)) ok.add(t.id);
        }
        List<Tmdb.Title> out = new ArrayList<>();
        for (Tmdb.Title t : trending) if (ok.contains(t.id)) out.add(t);
        return out.size() >= 5 ? out : trending;
    }

    /** From what was seen and saved: each one's recommendations, the ones that come up most often first. */
    private List<Tmdb.Title> forYou() throws Exception {
        List<Tmdb.Title> seeds = new ArrayList<>(Tmdb.seen(this));
        try { seeds.addAll(Tmdb.watchlist(this)); } catch (Exception ignored) {}
        Set<String> skip = new HashSet<>();
        for (Tmdb.Title t : seeds) skip.add(t.key());
        Map<String, Tmdb.Title> byKey = new LinkedHashMap<>();
        Map<String, Integer> count = new HashMap<>();
        int used = 0;
        for (Tmdb.Title s : seeds) {
            if (used++ >= 8) break;
            for (Tmdb.Title t : Tmdb.titles(Tmdb.get("/" + s.kind + "/" + s.id + "/recommendations"), s.kind)) {
                if (skip.contains(t.key())) continue;
                byKey.putIfAbsent(t.key(), t);
                count.merge(t.key(), 1, Integer::sum);
            }
        }
        List<Tmdb.Title> out = new ArrayList<>(byKey.values());
        out.sort((a, b) -> {
            int byCount = Integer.compare(count.get(b.key()), count.get(a.key()));
            return byCount != 0 ? byCount : Double.compare(b.vote, a.vote);
        });
        return out.size() > 40 ? new ArrayList<>(out.subList(0, 40)) : out;
    }

    private List<Tmdb.Title> fresh(List<Tmdb.Title> list) {
        Set<String> seen = new HashSet<>();
        for (Tmdb.Title t : Tmdb.seen(this)) seen.add(t.key());
        List<Tmdb.Title> out = new ArrayList<>();
        for (Tmdb.Title t : list) if (!seen.contains(t.key())) out.add(t);
        return out;
    }

    private static List<Tmdb.Title> mix(List<Tmdb.Title> a, List<Tmdb.Title> b) {
        List<Tmdb.Title> out = new ArrayList<>();
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            if (i < a.size()) out.add(a.get(i));
            if (i < b.size()) out.add(b.get(i));
        }
        return out;
    }

    private boolean stopped;

    @Override protected void onStop() {
        super.onStop();
        stopped = true;
        TvUi.releaseImages(getWindow().getDecorView());   // covered: its pictures go, the cache and the disk keep them
    }

    @Override protected void onStart() {
        super.onStart();
        if (stopped) TvUi.restoreImages(getWindow().getDecorView());
        stopped = false;
    }
}
