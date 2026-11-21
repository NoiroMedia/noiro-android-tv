package tv.noiro.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** The system Home: a fixed hero over remote-navigable rows of titles and folders. */
final class KodiHomeView extends FrameLayout {
    private static final int BG = 0xFF0B101A, BLUE = 0xFF2DAEFF;
    private static final int RAIL = 68, INSET = 142, ROW_TOP = 696;
    private static final int FOCUS_DETAILS_HEIGHT = 92, FOCUS_DETAILS_GAP = 12;
    // Four landscape cards and part of the next one.
    private static final int CARD_WIDTH = 378, CARD_HEIGHT = 213, GAP = 18;
    private static final int POSTER_WIDTH = 198, POSTER_HEIGHT = 297;
    private static final int POSTER_ROW_TOP = ROW_TOP - (POSTER_HEIGHT - CARD_HEIGHT);
    private static final int DISCOVER_BATCH = 6;
    private static final long ARTWORK_RETRY = 10_000;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private final Activity activity;
    private final TvUi.OnTitle resultOpener;
    private final Runnable resultBack;
    private final Map<String, Runnable> resultPages = new LinkedHashMap<>(), resultMore = new LinkedHashMap<>();
    private View resultEditItem, resultMoreItem, resultLayoutItem, resultFocusDetails;
    private boolean resultsEditable, resultLandscapeOnly;
    private StudioHome.Card pinnedHero;
    private Runnable pinnedAction, pinnedOptions;
    private String resultPrimaryLabel, resultPrimaryIcon, resultVersionBadge;
    private java.util.function.BiConsumer<TvUi.CatalogTile, Tmdb.Title> resultTileDecoration;
    private final float k;
    private final ExecutorService io = Executors.newFixedThreadPool(4);
    private final ExecutorService trailerIo = Executors.newFixedThreadPool(2);
    private final TrailerPlaybackPolicy trailerRequests = new TrailerPlaybackPolicy();
    private Future<?> trailerWork;
    private final ExecutorService artworkIo = Executors.newFixedThreadPool(3);
    private final ExecutorService discoverIo = Executors.newFixedThreadPool(2);
    private final ExecutorService discoverCacheIo = Executors.newSingleThreadExecutor();
    private final android.util.LruCache<String, String> catalogArtwork = new android.util.LruCache<>(160);
    private final android.util.LruCache<String, Long> catalogArtworkPending = new android.util.LruCache<>(160);
    private final android.util.LruCache<String, android.graphics.Bitmap> providerArtwork = new android.util.LruCache<>(16);
    private final Map<String, String> plexWideArtwork = new LinkedHashMap<>();
    private Map<String, JSONObject> discoverArtwork = new LinkedHashMap<>();
    private final android.util.LruCache<String, JSONObject> resolvedDiscover = new android.util.LruCache<>(384);
    private final android.util.LruCache<String, JSONObject> savedDiscover = new android.util.LruCache<>(512);
    private final android.util.LruCache<String, Long> discoverRetry = new android.util.LruCache<>(384);
    private final Runnable loadVisibleDiscover = this::loadVisibleDiscover;
    private boolean directDiscoverRunning, tmdbDiscoverRunning, resumed;
    private int artworkEpoch;
    private final Map<TvUi.CatalogTile, StudioHome.Card> titleTiles = new java.util.WeakHashMap<>();
    private final Map<TvUi.CatalogTile, StudioHome.Card> streamingTiles = new java.util.WeakHashMap<>();
    private final LinearLayout rows;
    private final ImageView backdrop, heroLogo;
    private final LayoutParams backdropArea;
    private final TextView heroSource, title, metadata, imdbBadge, rating, versionBadge, synopsis, profileButton, status, nextCatalog;
    private final LinearLayout ratingLine, progressLine, heroInfo;
    private final TextView heroInfoGlyph;
    private final HeroVersion heroVersion;
    private String heroCatalog = "";
    private boolean hasNavigated;
    private final android.util.LruCache<String, StudioHome.Hero> heroDetails = new android.util.LruCache<>(96);
    private StudioHome.Card heroCard;
    private Recs.Pick heroPick;
    private final LinearLayout heroAction;
    private final TextView heroActionLabel, progressLabel;
    private final ProgressBar heroProgress;
    private final FrameLayout menu;
    private final View menuScrim;
    private final View homeMenuItem;
    private final Map<String, View> menuDestinations = new LinkedHashMap<>();
    private boolean posters;
    private final LinearLayout menuItems;
    private View menuReturn;
    private boolean menuOpen, keepHero;
    private CatalogRow activeRow, parkedArtworkRow;
    private boolean publishedStudio;
    private final ArrayDeque<Page> history = new ArrayDeque<>();
    private final Map<String, View> sections = new LinkedHashMap<>();
    private final Map<String, String> sectionKeys = new LinkedHashMap<>();
    private final Map<String, Integer> plexOrder = new LinkedHashMap<>();
    private final Map<String, Integer> studioOrder = new LinkedHashMap<>();
    private final android.util.LruCache<String, PagePosition> positions = new android.util.LruCache<>(12);
    private final Recs recs;
    private final Recs.Listener recListener = this::showRecommendations;
    private final UpNext upNext;
    private java.util.function.Consumer<StudioHome.Card> resultCardOpener;
    private boolean loadedPublicCatalogs, loadedCloud;
    private final HomeCatalogFallback publicFallback = new HomeCatalogFallback();
    private final List<StudioHome.Profile> profiles = new ArrayList<>();
    private AccountState owner = AccountState.EMPTY;
    private String profile = "main";
    private Page page;
    private volatile int generation;
    private long checkedAt;
    private String heroImage = "", heroLogoImage = "", heroArtworkIdentity = "";
    private android.graphics.Bitmap heroProviderArtwork;

    private static final class Page {
        final List<View> rows = new ArrayList<>();
        final String title, browse, path;
        boolean media;
        String menuSection = "home";
        int listRequest;
        PagePosition homePosition, mediaPosition;
        CatalogRow activeRow;
        View heroFocus;
        StudioHome.Card hero;
        Recs.Pick pick;
        String heroCatalog;
        Page(String title, String browse, String path) {
            this.title = title;
            this.browse = browse;
            this.path = path;
        }

        String positionKey() { return media ? path + "/media" : path; }
    }

    private static final class CardPosition {
        final Object key;
        final int index, scrollX;

        CardPosition(Object key, int index, int scrollX) {
            this.key = key;
            this.index = index;
            this.scrollX = scrollX;
        }
    }

    private static final class PagePosition {
        final Map<String, CardPosition> cards = new LinkedHashMap<>();
        String active;
    }

    private final class CatalogRow extends LinearLayout {
        final StudioHome.Row model;
        final Map<String, Recs.Pick> picks;
        final String name;
        final List<StudioHome.Card> titles;
        final TvUi.Row cards;
        final TvUi.HScroll scroll;
        final String key;
        View selected;
        int artworkFrom, artworkTo;

        CatalogRow(String key, StudioHome.Row row, Map<String, Recs.Pick> picks) {
            super(activity);
            this.key = key;
            model = row;
            this.picks = picks;
            name = row.name;
            titles = row.cards;
            setOrientation(VERTICAL);
            setVisibility(GONE);
            cards = new TvUi.Row(activity);
            scroll = new TvUi.HScroll(activity, scale(8));
        }

        CardPosition position() {
            int index = selected == null ? 0 : cards.indexOfChild((View) selected.getParent());
            return new CardPosition(selected == null ? null : selected.getTag(), Math.max(0, index), scroll.savedPosition());
        }

        void restore(CardPosition mark) {
            if (mark == null || cards.getChildCount() == 0) return;
            selected = mark.key == null ? null : findViewWithTag(mark.key);
            if (selected == null) {
                selected = ((ViewGroup) cards.getChildAt(Math.min(mark.index, cards.getChildCount() - 1))).getChildAt(0);
            }
            scroll.restorePosition(mark.scrollX, selected);
        }

        int focusedIndex() {
            return selected == null ? 0 : Math.max(0, cards.indexOfChild((View) selected.getParent()));
        }

        TvUi.CatalogTile tile(int index) {
            return (TvUi.CatalogTile) ((ViewGroup) cards.getChildAt(index)).getChildAt(0);
        }

        int[] artworkWindow() {
            int focus = focusedIndex();
            int visibleWidth = scroll.getWidth() > 0 ? scroll.getWidth() : scale(1920 - INSET);
            View focused = cards.getChildAt(focus);
            if (focused == null) return new int[]{0, 0};
            boolean laidOut = cards.getChildAt(titles.size() - 1).getRight() > 0 && scroll.getWidth() > 0
                    && !scroll.isLayoutRequested() && !cards.isLayoutRequested();
            if (!laidOut) {
                int from = focus, to = focus + 1;
                int left = 0, right = 0, gap = scale(GAP);
                while (from > 0 && left < visibleWidth) left += scale(cardWidth(titles.get(--from))) + gap;
                while (to < titles.size() && right < visibleWidth) right += scale(cardWidth(titles.get(to++))) + gap;
                return new int[]{from, to};
            }
            int left = scroll.savedPosition();
            int margin = scale(8);
            if (focused.getRight() > left + visibleWidth - margin) {
                left = focused.getRight() - visibleWidth + margin;
            } else if (focused.getLeft() < left + margin) {
                left = focused.getLeft() - margin;
            }
            left = Math.max(0, Math.min(left, cards.getWidth() - visibleWidth));
            int from = focus, to = focus + 1;
            while (from > 0 && cards.getChildAt(from - 1).getRight() > left) from--;
            while (to < titles.size() && cards.getChildAt(to).getLeft() < left + visibleWidth) to++;
            return new int[]{Math.max(0, from - 1), Math.min(titles.size(), to + 1)};
        }

        void parkArtwork() {
            for (int i = artworkFrom; i < artworkTo; i++) tile(i).cancelPendingArtwork();
        }

        void releaseArtwork() {
            for (int i = artworkFrom; i < artworkTo; i++) tile(i).clearArtwork();
            artworkFrom = artworkTo = 0;
        }

        void loadVisibleCards() {
            int focus = focusedIndex();
            int[] window = artworkWindow();
            int from = window[0], to = window[1];
            for (int i = artworkFrom; i < artworkTo; i++) {
                if (i < from || i >= to) tile(i).clearArtwork();
            }
            artworkFrom = from;
            artworkTo = to;
            // The focused card and the cards to its right are the first visible work.
            for (int i = focus; i < to; i++) loadArtwork(i);
            for (int i = focus - 1; i >= from; i--) loadArtwork(i);
        }

        private void loadArtwork(int index) {
            StudioHome.Card card = titles.get(index);
            TvUi.CatalogTile tile = tile(index);
            if (streamingTiles.containsKey(tile)) {
                loadStreamingArtwork(tile, card);
            } else applyCatalogArtwork(tile, card, false);
        }

        boolean focusCard() {
            View target = selected != null ? selected : firstCard();
            return target != null && target.requestFocus();
        }

        View firstCard() {
            return cards.getChildCount() == 0 ? null : ((ViewGroup) cards.getChildAt(0)).getChildAt(0);
        }
    }

    KodiHomeView(Activity activity, Runnable openApps) {
        this(activity, openApps, null, null, null, null);
    }

    KodiHomeView(Activity activity, TvUi.OnTitle open, Runnable back, Runnable browse, Runnable edit) {
        this(activity, null, open, back, browse, edit);
    }

    private KodiHomeView(Activity activity, Runnable openApps, TvUi.OnTitle open, Runnable back,
                         Runnable browse, Runnable edit) {
        super(activity);
        this.activity = activity;
        this.resultOpener = open;
        this.resultBack = back;
        this.k = TvUi.k(activity);
        posters = activity.getSharedPreferences("home-layout", Activity.MODE_PRIVATE).getBoolean("posters", false);
        setBackgroundColor(BG);
        setFocusableInTouchMode(true);
        setDescendantFocusability(FOCUS_BEFORE_DESCENDANTS);

        backdrop = new ImageView(activity);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdropArea = new LayoutParams(scale(1490), scale(838), Gravity.TOP | Gravity.RIGHT);
        addView(backdrop, backdropArea);
        addFade(GradientDrawable.Orientation.LEFT_RIGHT, backdropArea, BG, 0xDC0B101A, 0x520B101A, 0x000B101A);
        addFade(GradientDrawable.Orientation.TOP_BOTTOM, backdropArea, 0x000B101A, 0x080B101A, 0xB60B101A, BG);

        LinearLayout copy = new LinearLayout(activity);
        copy.setOrientation(LinearLayout.VERTICAL);
        LayoutParams copySize = new LayoutParams(scale(820), WRAP);
        copySize.leftMargin = scale(INSET);
        copySize.topMargin = scale(80);
        addView(copy, copySize);

        heroSource = text(23, Ui.medium, 0xFFBCCBDC, 1);
        heroSource.setGravity(Gravity.CENTER_VERTICAL);
        copy.addView(heroSource, new LinearLayout.LayoutParams(MATCH, scale(34)));

        title = text(53, Ui.displaySemibold, TvUi.INK, 2);
        title.setGravity(Gravity.CENTER_VERTICAL);
        heroLogo = new ImageView(activity) {
            @Override public void setImageDrawable(android.graphics.drawable.Drawable image) {
                super.setImageDrawable(image);
                title.setAlpha(image == null ? 1 : 0);
                setImportantForAccessibility(image == null ? IMPORTANT_FOR_ACCESSIBILITY_NO : IMPORTANT_FOR_ACCESSIBILITY_YES);
            }

            @Override public void setImageBitmap(android.graphics.Bitmap image) {
                super.setImageBitmap(image);
                title.setAlpha(image == null ? 1 : 0);
                setImportantForAccessibility(image == null ? IMPORTANT_FOR_ACCESSIBILITY_NO : IMPORTANT_FOR_ACCESSIBILITY_YES);
            }
        };
        heroLogo.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        heroLogo.setScaleType(ImageView.ScaleType.FIT_START);
        FrameLayout titleBlock = new FrameLayout(activity);
        titleBlock.addView(title, new LayoutParams(MATCH, MATCH));
        titleBlock.addView(heroLogo, new LayoutParams(scale(500), MATCH));
        LinearLayout.LayoutParams titleArea = new LinearLayout.LayoutParams(MATCH, scale(110));
        titleArea.topMargin = scale(18);
        copy.addView(titleBlock, titleArea);

        metadata = text(24, Ui.medium, 0xFFD3DCE7, 1);
        LinearLayout.LayoutParams metadataArea = new LinearLayout.LayoutParams(MATCH, WRAP);
        metadataArea.topMargin = scale(22);
        copy.addView(metadata, metadataArea);

        ratingLine = new LinearLayout(activity);
        ratingLine.setGravity(Gravity.CENTER_VERTICAL);
        imdbBadge = text(20, Ui.semibold, 0xFF171817, 1);
        imdbBadge.setText("IMDb");
        imdbBadge.setPadding(scale(7), scale(3), scale(7), scale(3));
        imdbBadge.setBackground(TvUi.round(0xFFF5C518, scale(4)));
        ratingLine.addView(imdbBadge);
        rating = text(22, Ui.medium, TvUi.INK, 1);
        LinearLayout.LayoutParams scoreArea = new LinearLayout.LayoutParams(WRAP, WRAP);
        scoreArea.leftMargin = scale(10);
        ratingLine.addView(rating, scoreArea);
        versionBadge = text(20, Ui.semibold, 0xFFD8EFFF, 1);
        versionBadge.setPadding(scale(12), scale(4), scale(12), scale(4));
        GradientDrawable versionBackground = TvUi.round(0xFF162C40, scale(5));
        versionBackground.setStroke(scale(1), 0xFF486B88);
        versionBadge.setBackground(versionBackground);
        LinearLayout.LayoutParams versionArea = new LinearLayout.LayoutParams(WRAP, WRAP);
        ratingLine.addView(versionBadge, versionArea);
        versionBadge.setVisibility(GONE);
        heroVersion = new HeroVersion(activity, this::setVersionBadge);
        LinearLayout.LayoutParams ratingArea = new LinearLayout.LayoutParams(MATCH, scale(34));
        ratingArea.topMargin = scale(7);
        copy.addView(ratingLine, ratingArea);

        synopsis = text(27, Ui.regular, 0xFFD7E0EA, 3);
        synopsis.setLineSpacing(scale(2), 1f);
        LinearLayout.LayoutParams synopsisSize = new LinearLayout.LayoutParams(scale(800), WRAP);
        synopsisSize.topMargin = scale(30);
        copy.addView(synopsis, synopsisSize);

        progressLine = new LinearLayout(activity);
        progressLine.setGravity(Gravity.CENTER_VERTICAL);
        heroProgress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        heroProgress.setMax(1000);
        heroProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(BLUE));
        heroProgress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF36465B));
        progressLine.addView(heroProgress, new LinearLayout.LayoutParams(scale(246), scale(9)));
        progressLabel = text(20, Ui.medium, 0xFFD7E0EA, 1);
        LinearLayout.LayoutParams progressTextArea = new LinearLayout.LayoutParams(WRAP, WRAP);
        progressTextArea.leftMargin = scale(14);
        progressLine.addView(progressLabel, progressTextArea);
        LinearLayout.LayoutParams progressArea = new LinearLayout.LayoutParams(MATCH, scale(30));
        progressArea.topMargin = scale(30);
        copy.addView(progressLine, progressArea);

        heroAction = TvUi.iconButton(activity, k * .8f, "play", Ui.tr("Detalhes", "Details"), v -> {
            if (pinnedHero != null) pinnedAction.run();
            else if (heroCard != null) open(heroCard, heroPick);
        });
        heroActionLabel = (TextView) heroAction.getChildAt(1);
        heroAction.setOnFocusChangeListener((view, focused) -> {
            heroAction.setBackground(pill(focused ? 0xFF92D6FF : BLUE, focused));
            ((TvUi.Icon) heroAction.getChildAt(0)).color(BG);
            heroActionLabel.setTextColor(BG);
        });
        heroAction.getOnFocusChangeListener().onFocusChange(heroAction, false);
        LinearLayout actions = new LinearLayout(activity);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.addView(heroAction);
        heroInfo = TvUi.iconButton(activity, k * .8f, "", "", v -> {
            if (pinnedOptions != null) pinnedOptions.run();
            else {
                Recs.Pick pick = heroPick;
                if (pick != null && pick.id > 0) {
                    DetailActivity.open(activity, pick.kind, pick.id, rating.getText().toString());
                }
            }
        });
        heroInfo.removeAllViews();
        heroInfoGlyph = text(29, Ui.semibold, TvUi.INK, 1);
        heroInfoGlyph.setText("ⓘ");
        heroInfoGlyph.setGravity(Gravity.CENTER);
        heroInfo.addView(heroInfoGlyph, new LinearLayout.LayoutParams(scale(30), scale(30)));
        heroInfo.setBackground(TvUi.round(0xFF334458, scale(32)));
        heroInfo.setOnFocusChangeListener((view, focused) -> {
            heroInfo.setBackground(pill(focused ? 0xFF41556D : 0xFF334458, focused));
        });
        heroInfo.setContentDescription(Ui.tr("Detalhes", "Details"));
        LinearLayout.LayoutParams infoArea = new LinearLayout.LayoutParams(WRAP, WRAP);
        infoArea.leftMargin = scale(14);
        actions.addView(heroInfo, infoArea);
        LinearLayout.LayoutParams actionArea = new LinearLayout.LayoutParams(WRAP, WRAP);
        actionArea.topMargin = scale(12);
        copy.addView(actions, actionArea);

        rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        LayoutParams rowArea = new LayoutParams(MATCH, WRAP);
        rowArea.topMargin = scale(catalogTop());
        rowArea.leftMargin = scale(INSET);
        addView(rows, rowArea);

        nextCatalog = text(25, Ui.medium, 0xFFBAC5D2, 1);
        nextCatalog.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        LayoutParams nextArea = new LayoutParams(MATCH, scale(46));
        nextArea.leftMargin = scale(INSET);
        nextArea.topMargin = scale(ROW_TOP + 310);
        nextArea.rightMargin = scale(34);
        addView(nextCatalog, nextArea);

        status = text(24, Ui.regular, TvUi.DIM, 2);

        menuScrim = new View(activity);
        menuScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xDA080B11, 0x70080B11, 0x28080B11}));
        menuScrim.setVisibility(GONE);
        addView(menuScrim, new LayoutParams(MATCH, MATCH));

        menu = new FrameLayout(activity);
        menu.setBackground(TvUi.round(0xEA151A23, scale(32)));
        menu.setPadding(0, scale(10), 0, scale(10));
        menuItems = new LinearLayout(activity);
        menuItems.setOrientation(LinearLayout.VERTICAL);
        menu.addView(menuItems, new LayoutParams(MATCH, WRAP));
        LayoutParams menuArea = new LayoutParams(scale(RAIL), WRAP, Gravity.LEFT | Gravity.CENTER_VERTICAL);
        menuArea.leftMargin = scale(32);
        addView(menu, menuArea);

        if (resultOpener != null) {
            profileButton = null;
            homeMenuItem = (View) addMenuItem("back", Ui.tr("Voltar", "Back"), true, false, () -> {
                leaveMenu();
                resultBack.run();
            }).getParent();
            if (browse != null) addMenuItem("apps", Ui.tr("Explorar", "Explore"), true, false, () -> {
                leaveMenu();
                browse.run();
            });
            if (edit != null) resultEditItem = (View) addMenuItem("tune", Ui.tr("Filtros", "Filters"), true, false, () -> {
                leaveMenu();
                edit.run();
            }).getParent();
            resultMoreItem = (View) addMenuItem("apps", Ui.tr("Ver mais", "See more"), false, false, () -> {
                Runnable action = activeRow == null ? null : resultMore.get(activeRow.key);
                leaveMenu();
                if (action != null) action.run();
            }).getParent();
            resultLayoutItem = (View) addMenuItem("layout", Ui.tr("Visual dos catálogos", "Catalog Layout"),
                    false, true, this::chooseLayout).getParent();
            setMenuExpanded(false);
            recs = null;
            upNext = null;
        } else {
            profileButton = addMenuItem("person", "Main", false, false, this::chooseProfile);
            addMenuItem("search", Ui.tr("Buscar", "Search"), true, false, () -> openExplore("search"));
            homeMenuItem = addDestination("home", "home", Ui.tr("Início", "Home"), true, false, this::returnHome);
            addDestination("discover", "play", Ui.tr("Descobrir", "Discover"), false, false,
                    () -> openBrowse(Browse.DISCOVER));
            addDestination("watchlist", "check", Ui.tr("Minha lista", "My list"), true, true, this::openMyList);
            addDestination("services", "tv", Ui.tr("Meus serviços", "My Services"), false, false,
                    () -> openBrowse(Browse.SERVICES));
            addDestination("genres", "apps", Ui.tr("Gêneros", "Genres"), false, false,
                    () -> openBrowse(Browse.GENRES));
            addDestination("media", "live_tv", Ui.tr("Mídia", "Media"), false, false, this::openMedia);
            if (!AppIdentity.standalone(activity)) {
                addMenuItem("apps", Ui.tr("Aplicativos", "Apps"), false, true, () -> {
                    leaveMenu();
                    openApps.run();
                });
            }
            addMenuItem("layout", Ui.tr("Visual dos catálogos", "Catalog Layout"), false, false, this::chooseLayout);
            addMenuItem("settings", Ui.tr("Configurações", "Settings"), false, false, this::openSettings);
            setMenuExpanded(false);
            recs = Recs.get(activity);
            upNext = new UpNext(activity, this::showContinueWatching);
            recs.addListener(recListener);
        }
        page = new Page("", "home", "home");
        showHero(null, "");
        requestFocus();
    }

    void resume() {
        resumed = true;
        AccountState resumedOwner = NoiroAccount.current();
        if (!owner.owns(resumedOwner.authKey, resumedOwner.uid)) cancelTrailer();
        boolean savedLayout = !resultLandscapeOnly
                && activity.getSharedPreferences("home-layout", Activity.MODE_PRIVATE).getBoolean("posters", false);
        if (posters != savedLayout) {
            posters = savedLayout;
            resizeCatalogs();
            discoverRetry.evictAll();
        }
        heroVersion.resume();
        if (resultOpener != null) {
            owner = NoiroAccount.current();
            if (heroCard != null) {
                loadHero(heroCard);
                refreshVersionBadge(heroCard, heroDetails.get(heroCacheKey(heroCard)));
            }
            queueDiscoverLookup();
            return;
        }
        upNext.resume();
        recs.refreshIfStale();
        AccountState current = NoiroAccount.current();
        String selected = NoiroAccount.ready() ? StudioHome.selection(activity, current) : "main";
        if (!catalogOwnerCurrent(owner) || !profile.equals(selected)
                || loadedPublicCatalogs != usingPublicCatalogs() || loadedCloud != NoiroAccount.ready()) {
            owner = current;
            profile = selected;
            profiles.clear();
            load();
        } else if (history.isEmpty() && android.os.SystemClock.elapsedRealtime() - checkedAt > 60_000) {
            refreshCloud();
        }
        if (isMyList()) refreshMyList();
        setMenuExpanded(menuOpen);
        if (heroCard != null) refreshVersionBadge(heroCard, heroDetails.get(heroCacheKey(heroCard)));
        if (activeRow != null) activeRow.loadVisibleCards();
        queueDiscoverLookup();
    }

    void pause() {
        resumed = false;
        cancelTrailer();
        removeCallbacks(loadVisibleDiscover);
        heroVersion.pause();
        if (upNext != null) upNext.pause();
    }

    void destroy() {
        cancelTrailer();
        trailerIo.shutdownNow();
        generation++;
        artworkEpoch++;
        removeCallbacks(loadVisibleDiscover);
        heroVersion.destroy();
        if (upNext != null) upNext.destroy();
        if (recs != null) recs.removeListener(recListener);
        artworkIo.shutdownNow();
        discoverIo.shutdownNow();
        discoverCacheIo.shutdown();
        io.shutdownNow();
    }

    void startResults(String heading, boolean editable) {
        cancelTrailer();
        generation++;
        resultsEditable = editable;
        pinnedHero = null;
        setPortraitBackdrop(false);
        pinnedAction = null;
        pinnedOptions = null;
        requestFocus();
        setMenuExpanded(false);
        menuReturn = null;
        sections.clear();
        sectionKeys.clear();
        resultPages.clear();
        resultMore.clear();
        rows.removeAllViews();
        activeRow = null;
        heroPick = null;
        heroCatalog = "";
        hasNavigated = false;
        page = new Page(heading, "results", "results");
        showHero(null, "");
        showNextCatalog();
        resultMessage(Ui.tr("Carregando…", "Loading…"));
    }

    void setResultCardOpener(java.util.function.Consumer<StudioHome.Card> opener) {
        resultCardOpener = opener;
    }

    void setResultPrimaryLabel(String label) {
        resultPrimaryLabel = label;
    }

    void setResultPrimaryIcon(String icon) {
        resultPrimaryIcon = icon;
    }

    void setResultLandscapeOnly() {
        resultLandscapeOnly = true;
        if (posters) {
            posters = false;
            resizeCatalogs();
        }
        setMenuExpanded(menuOpen);
    }

    void setResultFocusDetails(View view) {
        if (resultFocusDetails != null) removeView(resultFocusDetails);
        resultFocusDetails = view;
        LayoutParams area = new LayoutParams(MATCH, scale(FOCUS_DETAILS_HEIGHT));
        area.leftMargin = scale(INSET);
        area.rightMargin = scale(34);
        area.topMargin = scale(catalogTop() - FOCUS_DETAILS_HEIGHT - FOCUS_DETAILS_GAP);
        view.setFocusable(false);
        addView(view, indexOfChild(rows), area);
    }

    void setResultTileDecoration(java.util.function.BiConsumer<TvUi.CatalogTile, Tmdb.Title> decorate) {
        resultTileDecoration = decorate;
    }

    void pinResultHero(StudioHome.Card card, StudioHome.Hero details, String context,
                       String actionLabel, Runnable action, Runnable options) {
        pinnedAction = java.util.Objects.requireNonNull(action);
        pinnedOptions = options;
        resultPrimaryLabel = actionLabel;
        if (status.getParent() == rows) rows.removeView(status);
        updatePinnedResultHero(card, details, context);
        heroAction.requestFocus();
    }

    void updatePinnedResultHero(StudioHome.Card card, StudioHome.Hero details, String context) {
        pinnedHero = card;
        boolean portrait = card.type.equals("person") && !card.image.isEmpty();
        if (backdrop.getClipToOutline() != portrait) setPortraitBackdrop(portrait);
        heroDetails.put(heroCacheKey(card), details);
        heroCatalog = context;
        showHero(card, "");
        queueDiscoverLookup();
    }

    void setResultVersionBadge(String label) {
        resultVersionBadge = label;
        if (label != null) heroVersion.show(owner, "", "");
        refreshVersionBadge(heroCard, heroCard == null ? null : heroDetails.get(heroCacheKey(heroCard)));
    }

    private void setPortraitBackdrop(boolean portrait) {
        if (portrait) {
            LayoutParams area = new LayoutParams(scale(352), scale(528), Gravity.TOP | Gravity.RIGHT);
            area.topMargin = scale(52);
            area.rightMargin = scale(190);
            backdrop.setLayoutParams(area);
            backdrop.setBackground(TvUi.round(BG, scale(20)));
        } else {
            backdrop.setLayoutParams(backdropArea);
            backdrop.setBackground(null);
        }
        backdrop.setClipToOutline(portrait);
    }

    void resultCatalog(int slot, String label, List<Tmdb.Title> titles, Runnable nextPage, Runnable more) {
        List<StudioHome.Card> cards = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Tmdb.Title title : titles) {
            if (!seen.add(title.key())) continue;
            String posterBase = title.kind.equals("person") ? Tmdb.PROFILE : Tmdb.POSTER;
            cards.add(new StudioHome.Card(title, titleArtwork(title.poster, posterBase),
                    titleArtwork(title.backdrop, Tmdb.BACKDROP)));
        }
        resultCards(slot, label, cards, nextPage, more);
    }

    void resultCards(int slot, String label, List<StudioHome.Card> cards, Runnable nextPage, Runnable more) {
        String key = String.format(java.util.Locale.ROOT, "results:%03d", slot);
        if (nextPage != null) resultPages.put(key, nextPage);
        else resultPages.remove(key);
        if (more != null) resultMore.put(key, more);
        else resultMore.remove(key);
        StudioHome.Row catalog = new StudioHome.Row(label);
        catalog.cards.addAll(cards);
        if (!cards.isEmpty() && status.getParent() == rows) rows.removeView(status);
        put(key, catalog);
    }

    void resultMessage(String message) {
        if (activeRow != null) return;
        status.setText(message);
        if (status.getParent() == null) rows.addView(status);
    }

    void focusResults() {
        if (activeRow == null) {
            if (pinnedHero == null || !heroAction.requestFocus()) requestFocus();
        } else {
            activeRow.restore(activeRow.position());
            if (!activeRow.focusCard()) requestFocus();
        }
    }

    void returnHome() {
        while (back()) { /* Return to the last position on Home. */ }
    }

    boolean back() {
        cancelTrailer();
        if (menuOpen) {
            leaveMenu();
            return true;
        }
        if (history.isEmpty()) {
            if (!page.media) return false;
            switchHomeScope(false);
            return true;
        }
        savePagePosition();
        generation++;
        requestFocus();
        page = history.pop();
        setMenuExpanded(false);
        rows.removeAllViews();
        activeRow = page.activeRow;
        for (View row : page.rows) rows.addView(row);
        heroPick = page.pick;
        heroCatalog = page.heroCatalog;
        showHero(page.hero, page.hero == null ? "" : page.hero.background);
        if (activeRow != null) activeRow.restore(activeRow.position());
        if (page.heroFocus == null || !page.heroFocus.isShown() || !page.heroFocus.requestFocus()) {
            selectRow(activeRow, false);
        } else showNextCatalog();
        if (history.isEmpty() && checkedAt == 0) refreshCloud();
        return true;
    }

    private void savePagePosition() {
        if (activeRow == null) return;
        PagePosition position = new PagePosition();
        position.active = activeRow.key;
        for (View child : page.rows) {
            if (child instanceof CatalogRow) {
                CatalogRow row = (CatalogRow) child;
                if (canBrowse(row)) position.cards.put(row.key, row.position());
            }
        }
        if (!history.isEmpty()) positions.put(page.path, position);
        else if (page.media) page.mediaPosition = position;
        else page.homePosition = position;
    }

    private GradientDrawable pill(int color, boolean focused) {
        GradientDrawable background = TvUi.round(color, scale(24));
        if (focused) background.setStroke(scale(3), TvUi.INK);
        return background;
    }

    private View addDestination(String section, String symbol, String label, boolean onRail, boolean startsGroup,
                                Runnable action) {
        View item = (View) addMenuItem(symbol, label, onRail, startsGroup, action).getParent();
        menuDestinations.put(section, item);
        return item;
    }

    private TextView addMenuItem(String symbol, String label, boolean onRail, boolean startsGroup, Runnable action) {
        LinearLayout item = new LinearLayout(activity);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setFocusable(true);
        item.setFocusableInTouchMode(true);
        item.setContentDescription(label);
        item.setTag(new boolean[]{onRail, startsGroup});
        View icon;
        if (symbol.equals("layout") || symbol.equals("back")) {
            TextView glyph = text(26, Ui.medium, TvUi.INK, 1);
            glyph.setText(symbol.equals("back") ? "‹" : "▥");
            glyph.setGravity(Gravity.CENTER);
            icon = glyph;
        } else icon = new TvUi.Icon(activity, symbol, 24 * k);
        icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        item.addView(icon, new LinearLayout.LayoutParams(scale(RAIL), MATCH));
        TextView name = text(27, Ui.medium, TvUi.INK, 1);
        name.setText(label);
        name.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        item.addView(name, new LinearLayout.LayoutParams(0, WRAP, 1));
        menuItems.addView(item, new LinearLayout.LayoutParams(MATCH, scale(58)));
        styleMenuItem(item);
        item.setOnFocusChangeListener((view, focused) -> {
            if (focused) {
                if (!menuOpen) setMenuExpanded(true);
                item.setContentDescription(name.getText());
            }
            styleMenuItem(item);
        });
        item.setOnClickListener(v -> action.run());
        return name;
    }

    private String activeDestination() {
        if (page == null) return "home";
        return page.media ? "media" : page.menuSection;
    }

    private void styleMenuItem(LinearLayout item) {
        boolean focused = item.hasFocus();
        boolean selected = item == menuDestinations.get(activeDestination());
        int color = focused ? 0xFF151A23 : selected ? 0xFF92D6FF : 0xFFADB4C0;
        item.setBackground(focused ? TvUi.round(TvUi.INK, scale(18))
                : selected ? TvUi.round(0xFF24364A, scale(18)) : null);
        View icon = item.getChildAt(0);
        if (icon instanceof TvUi.Icon) ((TvUi.Icon) icon).color(color);
        else ((TextView) icon).setTextColor(color);
        ((TextView) item.getChildAt(1)).setTextColor(focused || selected ? color : TvUi.INK);
    }

    private void setMenuExpanded(boolean expanded) {
        menuOpen = expanded;
        menuScrim.setVisibility(expanded ? VISIBLE : GONE);
        ViewGroup.LayoutParams size = menu.getLayoutParams();
        size.width = scale(expanded ? 366 : RAIL);
        menu.setLayoutParams(size);
        for (int i = 0; i < menuItems.getChildCount(); i++) {
            LinearLayout item = (LinearLayout) menuItems.getChildAt(i);
            boolean[] position = (boolean[]) item.getTag();
            boolean visible = expanded || position[0];
            if (profileButton != null && item == profileButton.getParent()) visible &= profiles.size() > 1;
            if (item == menuDestinations.get("discover") || item == menuDestinations.get("services")
                    || item == menuDestinations.get("genres")) visible &= profile.equals("main");
            if (AppIdentity.standalone(activity) && !NoiroAccount.ready()
                    && (item == menuDestinations.get("services") || item == menuDestinations.get("genres")
                    || item == menuDestinations.get("media"))) visible = false;
            if (item == resultLayoutItem) visible &= !resultLandscapeOnly;
            if (item == resultEditItem) visible &= resultsEditable;
            if (item == resultMoreItem) visible &= activeRow != null && resultMore.containsKey(activeRow.key);
            item.setVisibility(visible ? VISIBLE : GONE);
            item.getChildAt(1).setVisibility(expanded ? VISIBLE : GONE);
            LinearLayout.LayoutParams box = (LinearLayout.LayoutParams) item.getLayoutParams();
            box.leftMargin = box.rightMargin = expanded ? scale(12) : 0;
            box.topMargin = expanded && position[1] ? scale(19) : 0;
            item.setLayoutParams(box);
            styleMenuItem(item);
        }
    }

    private void enterMenu(View from) {
        menuReturn = from == heroAction || from == heroInfo ? from : null;
        View destination = menuDestinations.get(activeDestination());
        if (destination != null) {
            setMenuExpanded(true);
            if (destination.requestFocus()) return;
        }
        homeMenuItem.requestFocus();
    }

    private enum Browse {
        DISCOVER("discover", "Descobrir", "Discover", ""),
        SERVICES("services", "Meus serviços", "My Services", "service"),
        GENRES("genres", "Gêneros", "Genres", "genre");

        final String section, portuguese, english, kind;

        Browse(String section, String portuguese, String english, String kind) {
            this.section = section;
            this.portuguese = portuguese;
            this.english = english;
            this.kind = kind;
        }

        String label() { return Ui.tr(portuguese, english); }
    }

    private void openBrowse(Browse destination) {
        if (menuOpen) leaveMenu();
        if (usingPublicCatalogs()) {
            activity.startActivity(new Intent(activity, FreeExploreActivity.class));
            return;
        }
        if (AppIdentity.standalone(activity) && !NoiroAccount.ready()) {
            openExplore("home");
            return;
        }
        if (destination.section.equals(activeDestination()) && page.path.endsWith("/" + destination.section)) return;
        returnHome();
        List<StudioHome.Row> homeRows = new ArrayList<>();
        for (String key : plexOrder.keySet()) {
            CatalogRow row = (CatalogRow) sections.get(key);
            if (row != null) homeRows.add(row.model);
        }
        enterPage(destination.label(), "home", destination.section, destination.section);
        showHero(null, "");
        List<StudioHome.Row> catalog = browseRows(homeRows, destination);
        if (!catalog.isEmpty()) {
            renderPage(catalog);
            return;
        }
        status.setText(Ui.tr("Carregando…", "Loading…"));
        rows.addView(status);
        int gen = generation;
        AccountState expected = owner;
        io.execute(() -> {
            try {
                List<StudioHome.Row> loaded = browseRows(StudioHome.plex(expected, ""), destination);
                main(gen, expected, () -> renderPage(loaded));
            } catch (Exception unavailable) {
                main(gen, expected, () -> status.setText(Ui.tr("Catálogo indisponível.", "Catalog unavailable.")));
            }
        });
    }

    private static List<StudioHome.Row> browseRows(List<StudioHome.Row> rows, Browse destination) {
        List<StudioHome.Row> catalog = new ArrayList<>();
        if (destination.kind.isEmpty()) {
            for (StudioHome.Row row : rows) {
                if (!row.discover) continue;
                for (StudioHome.Card card : row.cards) {
                    if (card.title() || card.type.equals("trailer")) {
                        catalog.add(row);
                        break;
                    }
                }
            }
        } else {
            StudioHome.Row folders = new StudioHome.Row(destination.label(), true);
            folders.catalogId = destination.section;
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (StudioHome.Row row : rows) {
                for (StudioHome.Card card : row.cards) {
                    if (card.type.equals(destination.kind) && seen.add(card.key())) folders.cards.add(card);
                }
            }
            if (!folders.cards.isEmpty()) catalog.add(folders);
        }
        return catalog;
    }

    private static final String[][] SAVED_LISTS = {
            {"list", "Minha lista", "My list"}, {"favorite", "Favoritos", "Favorites"},
            {"seen", "Já assisti", "Seen"}, {"rated", "Suas notas", "Your ratings"}
    };

    private boolean isMyList() { return "watchlist".equals(page.browse); }

    void openMyList() {
        if (menuOpen) leaveMenu();
        if (isMyList()) return;
        enterPage(Ui.tr("Minha lista", "My list"), "watchlist", "watchlist", "watchlist");
        showHero(null, "");
        status.setText(Ui.tr("Carregando…", "Loading…"));
        rows.addView(status);
        refreshMyList();
    }

    private void refreshMyList() {
        if (usingPublicCatalogs()) {
            Map<String, StudioHome.Row> local = new LinkedHashMap<>();
            StudioHome.Row row = new StudioHome.Row(Ui.tr("Minha lista", "My list"));
            row.catalogId = "list";
            row.cards.addAll(FreeWatchlist.cards(activity));
            local.put("list", row);
            showMyLists(local);
            return;
        }
        Page listPage = page;
        int request = ++listPage.listRequest;
        int gen = generation;
        AccountState expected = owner;
        io.execute(() -> {
            Map<String, StudioHome.Row> loaded = new LinkedHashMap<>();
            for (String[] list : SAVED_LISTS) {
                if (generation != gen || !StudioHome.current(expected)) return;
                String key = list[0];
                try {
                    List<Tmdb.Title> titles;
                    switch (key) {
                        case "list": titles = Tmdb.watchlist(activity); break;
                        case "seen": titles = Tmdb.seen(activity); break;
                        default: titles = Tmdb.accountList(activity, key);
                    }
                    StudioHome.Row row = new StudioHome.Row(Ui.tr(list[1], list[2]));
                    row.catalogId = key;
                    java.util.Set<String> ids = new java.util.HashSet<>();
                    for (Tmdb.Title title : titles) {
                        if (title.id <= 0 || !MediaMenu.supported(title.kind) || !ids.add(title.key())) continue;
                        row.cards.add(new StudioHome.Card(title, titleArtwork(title.poster, Tmdb.POSTER),
                                titleArtwork(title.backdrop, Tmdb.BACKDROP)));
                    }
                    loaded.put(key, row);
                } catch (Exception ignored) {
                    // An unavailable account list can keep the last successful result.
                }
            }
            main(gen, expected, () -> {
                if (page == listPage && request == listPage.listRequest) showMyLists(loaded);
            });
        });
    }

    private void showMyLists(Map<String, StudioHome.Row> loaded) {
        Map<String, CatalogRow> previous = new LinkedHashMap<>();
        for (View child : page.rows) {
            if (child instanceof CatalogRow) {
                CatalogRow row = (CatalogRow) child;
                previous.put(row.key, row);
            }
        }
        List<StudioHome.Row> catalog = new ArrayList<>();
        List<String> oldKeys = new ArrayList<>(), newKeys = new ArrayList<>();
        for (CatalogRow row : previous.values()) {
            oldKeys.add(row.key);
            oldKeys.add(catalogFingerprint(row.model, row.picks));
        }
        for (String[] list : SAVED_LISTS) {
            String key = list[0];
            CatalogRow old = previous.get(key);
            StudioHome.Row row = loaded.get(key);
            if (row == null && old != null) row = old.model;
            if (row == null || row.cards.isEmpty()) continue;
            if (old != null && row != old.model) row = keepCardOrder(key, old, row);
            catalog.add(row);
            newKeys.add(key);
            newKeys.add(catalogFingerprint(row, java.util.Collections.emptyMap()));
        }
        if (activeRow != null && oldKeys.equals(newKeys)) return;
        savePagePosition();
        if (!menuOpen) requestFocus();
        rows.removeAllViews();
        activeRow = null;
        for (StudioHome.Row row : catalog) {
            rows.addView(renderRow(row.catalogId, row, new LinkedHashMap<>(), previous.get(row.catalogId)));
        }
        if (catalog.isEmpty()) {
            heroPick = null;
            heroCatalog = "";
            showHero(null, "");
        }
        finishPage(loaded.containsKey("list")
                ? Ui.tr("Nada aqui ainda. Abra um título para marcar.", "Nothing here yet. Open a title to add it.")
                : Ui.tr("Não foi possível carregar a lista.", "Couldn't load My list."));
    }

    private void openMedia() {
        if (menuOpen) leaveMenu();
        while (!history.isEmpty()) back();
        if (!page.media) switchHomeScope(true);
    }

    private void switchHomeScope(boolean media) {
        cancelTrailer();
        savePagePosition();
        page.media = media;
        setMenuExpanded(false);
        CatalogRow selected = restorePagePosition();
        if (selected != null) {
            selectRow(selected, true);
        } else {
            selectRow(null, false);
            requestFocus();
            heroPick = null;
            heroCatalog = "";
            showHero(null, "");
        }
    }

    private boolean canBrowse(CatalogRow row) {
        String key = row.key;
        return !page.media || key.equals("continue")
                || key.startsWith("plex:pms:") || key.startsWith("studio:");
    }

    private CatalogRow findCatalog(int start, int step) {
        for (int i = start; i >= 0 && i < rows.getChildCount(); i += step) {
            View child = rows.getChildAt(i);
            if (child instanceof CatalogRow && canBrowse((CatalogRow) child)) return (CatalogRow) child;
        }
        return null;
    }

    private void chooseLayout() {
        String[] choices = {Ui.tr("Horizontal", "Landscape"), Ui.tr("Pôsteres", "Posters")};
        new AlertDialog.Builder(activity, R.style.Noiro_MenuDialog)
                .setTitle(Ui.tr("Visual dos catálogos", "Catalog Layout"))
                .setSingleChoiceItems(choices, posters ? 1 : 0, (dialog, choice) -> {
                    dialog.dismiss();
                    boolean next = choice == 1;
                    if (posters != next) {
                        posters = next;
                        activity.getSharedPreferences("home-layout", Activity.MODE_PRIVATE).edit().putBoolean("posters", posters).apply();
                        resizeCatalogs();
                        discoverRetry.evictAll();
                        if (activeRow != null) activeRow.loadVisibleCards();
                        queueDiscoverLookup();
                    }
                    leaveMenu();
                }).setOnCancelListener(dialog -> leaveMenu()).show();
    }

    private int catalogTop() { return posters ? POSTER_ROW_TOP : ROW_TOP; }

    private void resizeCatalogs() {
        LayoutParams position = (LayoutParams) rows.getLayoutParams();
        position.topMargin = scale(catalogTop());
        rows.setLayoutParams(position);
        if (resultFocusDetails != null) {
            LayoutParams details = (LayoutParams) resultFocusDetails.getLayoutParams();
            details.topMargin = scale(catalogTop() - FOCUS_DETAILS_HEIGHT - FOCUS_DETAILS_GAP);
            resultFocusDetails.setLayoutParams(details);
        }
        for (Map.Entry<TvUi.CatalogTile, StudioHome.Card> item : titleTiles.entrySet()) {
            TvUi.CatalogTile tile = item.getKey();
            StudioHome.Card card = item.getValue();
            if ((!card.title() && card.savedTitle == null) || tile.getParent() == null) continue;
            View cell = (View) tile.getParent();
            ViewGroup.LayoutParams area = cell.getLayoutParams();
            area.width = scale(cardWidth(card));
            area.height = scale(cardHeight(card));
            cell.setLayoutParams(area);
            tile.artwork.setMaxWidth(area.width);
            tile.artwork.setMaxHeight(area.height);
        }
        for (View row : page.rows) if (row instanceof CatalogRow) resizeRow((CatalogRow) row);
        for (Page previous : history) {
            for (View row : previous.rows) if (row instanceof CatalogRow) resizeRow((CatalogRow) row);
        }
    }

    private void resizeRow(CatalogRow row) {
        View scroll = (View) row.cards.getParent();
        ViewGroup.LayoutParams size = scroll.getLayoutParams();
        size.height = scale(posters ? POSTER_HEIGHT : CARD_HEIGHT);
        scroll.setLayoutParams(size);
    }

    private void leaveMenu() {
        View target = menuReturn;
        menuReturn = null;
        keepHero = true;
        boolean restored = target != null && target.isShown() && target.requestFocus();
        if (!restored && (activeRow == null || !activeRow.focusCard())) requestFocus();
        keepHero = false;
        setMenuExpanded(false);
    }

    private void openSettings() {
        leaveMenu();
        activity.startActivity(new Intent(activity, SettingsActivity.class));
    }

    private void openExplore(String section) {
        leaveMenu();
        if (usingPublicCatalogs()) {
            activity.startActivity(new Intent(activity, FreeExploreActivity.class)
                    .putExtra("search", "search".equals(section)));
            return;
        }
        activity.startActivity(new Intent(activity, ExploreActivity.class)
                .putExtra(ExploreActivity.EXTRA_SECTION, section)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_MENU) {
            View focused = findFocus();
            if (focused != null && focused.isLongClickable()) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)
                    focused.performLongClick();
                return true;
            }
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN &&
                (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER)) hasNavigated = true;
        if (key != KeyEvent.KEYCODE_DPAD_LEFT && key != KeyEvent.KEYCODE_DPAD_RIGHT
                && key != KeyEvent.KEYCODE_DPAD_UP && key != KeyEvent.KEYCODE_DPAD_DOWN) {
            return super.dispatchKeyEvent(event);
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        hasNavigated = true;
        View focused = findFocus();
        if (focused != null && inside(focused, menuItems)) {
            int index = menuItems.indexOfChild(focused);
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT) leaveMenu();
            if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
                int step = key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
                for (int next = index + step; next >= 0 && next < menuItems.getChildCount(); next += step) {
                    if (menuItems.getChildAt(next).requestFocus()) break;
                }
            }
        } else if (activeRow != null && focused != null && inside(focused, activeRow.cards)) {
            if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                moveBetweenCards(focused, key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
            } else {
                int step = key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
                CatalogRow next = findCatalog(rows.indexOfChild(activeRow) + step, step);
                if (next != null) selectRow(next, true);
                else if (step < 0 && heroAction.isShown()) heroAction.requestFocus();
            }
        } else if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (focused == heroInfo) heroAction.requestFocus();
            else enterMenu(focused);
        } else if (key == KeyEvent.KEYCODE_DPAD_DOWN && activeRow != null) {
            selectRow(activeRow, focused != heroAction && focused != heroInfo);
        } else if (key == KeyEvent.KEYCODE_DPAD_RIGHT && heroAction.isShown()) {
            if (focused == heroAction && heroInfo.isShown()) heroInfo.requestFocus();
            else if (focused != heroInfo) heroAction.requestFocus();
        }
        return true;
    }

    private void moveBetweenCards(View focused, int step) {
        TvUi.Row line = activeRow.cards;
        int next = line.indexOfChild((View) focused.getParent()) + step;
        if (next < 0) enterMenu(focused);
        else if (next < line.getChildCount()) ((ViewGroup) line.getChildAt(next)).getChildAt(0).requestFocus();
    }

    private void selectRow(CatalogRow row, boolean updateHero) {
        CatalogRow old = activeRow;
        if (old != row) parkArtwork(old, row);
        activeRow = row;
        if (row != null) {
            boolean previousKeepHero = keepHero;
            keepHero = true;
            try {
                // Restoring visibility can focus the first card before requestFocus runs.
                row.setVisibility(VISIBLE);
                row.loadVisibleCards();
                row.focusCard();
            } finally {
                keepHero = previousKeepHero;
            }
            // Successful requestFocus also includes an already-focused target, with no focus callback.
            if (updateHero && row.firstCard() != null) preview(row, row.titles.get(row.focusedIndex()));
        }
        if (old != null && old != row) old.setVisibility(GONE);
        showNextCatalog();
        queueDiscoverLookup();
    }

    private void parkArtwork(CatalogRow old, CatalogRow next) {
        if (parkedArtworkRow != null && parkedArtworkRow != next) parkedArtworkRow.releaseArtwork();
        parkedArtworkRow = old;
        if (old != null) old.parkArtwork();
    }

    private void load() {
        cancelTrailer();
        generation++;
        loadedPublicCatalogs = usingPublicCatalogs();
        loadedCloud = NoiroAccount.ready();
        parkArtwork(null, null);
        catalogArtwork.evictAll();
        catalogArtworkPending.evictAll();
        history.clear();
        sections.clear();
        sectionKeys.clear();
        plexOrder.clear();
        studioOrder.clear();
        positions.evictAll();
        plexWideArtwork.clear();
        discoverArtwork.clear();
        resolvedDiscover.evictAll();
        savedDiscover.evictAll();
        discoverRetry.evictAll();
        artworkEpoch++;
        directDiscoverRunning = false;
        tmdbDiscoverRunning = false;
        restoreDiscoverCache();
        titleTiles.clear();
        streamingTiles.clear();
        heroDetails.evictAll();
        heroVersion.clear();
        publishedStudio = false;
        hasNavigated = false;
        heroCatalog = "";
        requestFocus();
        menuReturn = null;
        rows.removeAllViews();
        activeRow = null;
        heroPick = null;
        page = new Page("", "home", "home");
        setMenuExpanded(false);
        profileButton.setText(profileName());
        showHero(null, "");
        refreshCloud();
        showContinueWatching();
        showRecommendations();
    }

    private int beginCatalogRefresh() {
        int run = ++generation;
        // Catalog refresh invalidates old async work while leaving the focused card in place.
        if (heroCard != null) loadHero(heroCard);
        return run;
    }

    private void refreshCloud() {
        if (!history.isEmpty()) return;
        checkedAt = android.os.SystemClock.elapsedRealtime();
        int gen = beginCatalogRefresh();
        AccountState expected = owner;
        String selected = profile;
        boolean cloud = NoiroAccount.ready();
        publicFallback.begin(gen, selected, usingPublicCatalogs(), cloud, publishedStudio);
        if (AppIdentity.standalone(activity) && !cloud) {
            requestPublicFallback(gen, expected, selected);
            return;
        }
        if (!owner.signedIn()) return;
        io.execute(() -> {
            try {
                List<StudioHome.Profile> available = StudioHome.profiles(expected);
                StudioHome.Profile match = null;
                for (StudioHome.Profile candidate : available) if (candidate.id.equals(selected)) match = candidate;
                final StudioHome.Profile verified = match;
                main(gen, expected, () -> {
                    profiles.clear();
                    profiles.addAll(available);
                    if (verified == null && !selected.equals("main") && !available.isEmpty()) {
                        StudioHome.select(activity, expected, "main");
                        profile = "main";
                        load();
                    } else {
                        profileButton.setText(profileName());
                        ((View) profileButton.getParent()).setContentDescription(profileName());
                        setMenuExpanded(menuOpen);
                    }
                });
                if (verified == null && !selected.equals("main")) return;
                StudioHome.Home home = StudioHome.read(expected, selected);
                main(gen, expected, () -> applyStudio(home, gen, expected, selected));
            } catch (Exception ignored) {
                // Preserve a known custom Home. Public metadata can still fill an unconfigured Main profile.
                main(gen, expected, () -> {
                    publicFallback.studioResolved(gen, selected, publishedStudio);
                    requestPublicFallback(gen, expected, selected);
                });
            }
        });
        if (!selected.equals("main")) return;
        io.execute(() -> {
            try {
                List<StudioHome.Row> plex = StudioHome.plex(expected, "");
                main(gen, expected, () -> {
                    updateStreamingArtwork(plex);
                    updateDiscoverArtwork(StudioHome.discoverArtwork(plex));
                    applyPlexRows(plex);
                });
            } catch (Exception ignored) {
                // Plex is optional and has independent access from Studio.
            }
        });
    }

    private boolean usingPublicCatalogs() {
        return AppIdentity.standalone(activity) && !Tmdb.hasKey();
    }

    /** Anonymous catalog work has a separate scope; cloud ownership still requires a real session. */
    private boolean catalogOwnerCurrent(AccountState expected) {
        AccountState current = NoiroAccount.current();
        return expected.signedIn() ? current.owns(expected.authKey, expected.uid)
                : AppIdentity.standalone(activity) && !current.signedIn();
    }

    private void requestPublicFallback(int gen, AccountState expected, String selected) {
        if (publicFallback.request(gen, selected)) refreshPublicCatalogs(gen, expected, selected);
    }

    private void refreshPublicCatalogs(int gen, AccountState expected, String selected) {
        if (!history.isEmpty()) return;
        status.setText(Ui.tr("Carregando…", "Loading…"));
        if (rows.getChildCount() == 0) rows.addView(status);
        io.execute(() -> {
            try {
                List<StudioHome.Row> catalogs = FreeCatalog.rows();
                main(gen, expected, () -> {
                    if (!usingPublicCatalogs() || !history.isEmpty() || !profile.equals(selected)
                            || !publicFallback.accepts(gen, selected)) return;
                    if (status.getParent() == rows) rows.removeView(status);
                    for (StudioHome.Row row : catalogs) put("free:" + row.catalogId, row);
                    if (rows.getChildCount() == 0) {
                        status.setText("No titles available. Open Discover to try again.");
                        rows.addView(status);
                    }
                });
            } catch (Exception unavailable) {
                main(gen, expected, () -> {
                    if (!history.isEmpty() || !profile.equals(selected) || !publicFallback.accepts(gen, selected)) return;
                    status.setText("Couldn't load catalogs. Check your connection and open Discover to retry.");
                    if (rows.getChildCount() == 0) rows.addView(status);
                });
            }
        });
    }

    private void applyPlexRows(List<StudioHome.Row> catalogs) {
        Map<String, StudioHome.Row> incoming = new LinkedHashMap<>();
        for (StudioHome.Row row : catalogs) incoming.put("plex:" + row.catalogId, row);
        refreshOrder(plexOrder, incoming.keySet());
        for (Map.Entry<String, StudioHome.Row> entry : incoming.entrySet()) put(entry.getKey(), entry.getValue());
        rememberRows();
    }

    private void refreshOrder(Map<String, Integer> order, java.util.Set<String> incoming) {
        for (String key : new ArrayList<>(order.keySet())) {
            if (incoming.contains(key)) continue;
            order.remove(key);
            removeSection(key);
        }
        int index = 0;
        for (String key : order.keySet()) order.put(key, index++);
        for (String key : incoming) if (!order.containsKey(key)) order.put(key, index++);
    }

    private void removeSection(String key) {
        View old = sections.remove(key);
        sectionKeys.remove(key);
        if (old == null) return;
        int index = rows.indexOfChild(old);
        rows.removeView(old);
        if (old == activeRow) {
            activeRow = null;
            CatalogRow next = findCatalog(index, 1);
            if (next == null) next = findCatalog(index - 1, -1);
            if (next != null) activateAfterRefresh(next);
            else {
                heroPick = null;
                if (pinnedHero == null) {
                    heroCatalog = "";
                    showHero(null, "");
                }
            }
        }
    }

    private void activateAfterRefresh(CatalogRow row) {
        if (menuOpen || heroAction.hasFocus() || heroInfo.hasFocus() || resultOpener != null && !isShown()) {
            if (activeRow != row) {
                parkArtwork(activeRow, row);
                if (activeRow != null) activeRow.setVisibility(GONE);
            }
            activeRow = row;
            row.setVisibility(VISIBLE);
            row.loadVisibleCards();
            View selected = row.selected == null ? row.firstCard() : row.selected;
            if (selected != null) preview(row, row.titles.get(row.cards.indexOfChild((View) selected.getParent())));
            queueDiscoverLookup();
        } else selectRow(row, true);
    }

    private void applyStudio(StudioHome.Home home, int gen, AccountState expected, String selected) {
        publishedStudio = home.hasPublishedRows();
        publicFallback.studioResolved(gen, selected, publishedStudio);
        if (publishedStudio) {
            removeSection("recommendations");
            for (String slot : new ArrayList<>(sections.keySet())) if (slot.startsWith("free:")) removeSection(slot);
        }
        else showRecommendations();
        java.util.Set<String> incoming = new java.util.LinkedHashSet<>();
        for (Object entry : home.order.keySet()) {
            if (entry instanceof StudioHome.Row) incoming.add("studio:group:" + ((StudioHome.Row) entry).catalogId);
            else incoming.add("studio:row:" + ((StudioHome.Card) entry).id);
        }
        refreshOrder(studioOrder, incoming);
        for (StudioHome.Row row : home.rows) put("studio:group:" + row.catalogId, row);
        for (StudioHome.Card card : home.catalogs) {
            String slot = "studio:row:" + card.id;
            io.execute(() -> {
                try {
                    if (Thread.currentThread().isInterrupted() || !StudioHome.current(expected)) return;
                    StudioHome.Row row = StudioHome.catalog(expected, selected, card);
                    main(gen, expected, () -> put(slot, row));
                } catch (Exception ignored) {
                    // Other selected catalogs remain independently available.
                }
            });
        }
        rememberRows();
        requestPublicFallback(gen, expected, selected);
    }

    private void chooseProfile() {
        if (profiles.isEmpty()) {
            refreshCloud();
            Toast.makeText(activity, Ui.tr("Carregando perfis…", "Loading profiles…"), Toast.LENGTH_SHORT).show();
            return;
        }
        List<StudioHome.Profile> choices = new ArrayList<>(profiles);
        String[] names = new String[choices.size()];
        int checked = -1;
        for (int i = 0; i < names.length; i++) {
            StudioHome.Profile item = choices.get(i);
            names[i] = item.name;
            if (item.id.equals(profile)) checked = i;
        }
        AccountState expected = owner;
        new AlertDialog.Builder(activity, R.style.Noiro_MenuDialog).setTitle(Ui.tr("Perfil", "Profile"))
                .setSingleChoiceItems(names, checked, (dialog, index) -> {
                    dialog.dismiss();
                    if (!StudioHome.current(expected)) return;
                    if (profile.equals(choices.get(index).id)) {
                        leaveMenu();
                        return;
                    }
                    profile = choices.get(index).id;
                    StudioHome.select(activity, expected, profile);
                    load();
                }).setOnCancelListener(dialog -> leaveMenu()).show();
    }

    private String profileName() {
        for (StudioHome.Profile item : profiles) if (item.id.equals(profile)) return item.name;
        return profile.equals("main") ? "Main" : Ui.tr("Perfil", "Profile");
    }

    private void showContinueWatching() {
        if (!history.isEmpty() || !profile.equals("main")) return;
        putPicks("continue", Ui.tr("Continue assistindo", "Continue Watching"), upNext.items());
    }

    private void showRecommendations() {
        if (publishedStudio || !history.isEmpty() || !profile.equals("main")) return;
        putPicks("recommendations", Ui.tr("Recomendado para você", "Recommended for you"), recs.now());
    }

    private void putPicks(String key, String label, List<Recs.Pick> picks) {
        StudioHome.Row row = new StudioHome.Row(label);
        Map<String, Recs.Pick> mapping = new LinkedHashMap<>();
        for (Recs.Pick pick : picks) {
            if (pick.continuing == null && pick.id <= 0) continue;
            if (pick.continuing != null && row.cards.size() < 8) upNext.enrich(pick);
            String id = pick.continuing == null
                    ? (pick.imdb.isEmpty() ? "tmdb:" + pick.id : pick.imdb) : pick.continuing.id;
            StudioHome.Card card = new StudioHome.Card(id, pick.title, pick.kind.equals("tv") ? "series" : "movie",
                    titleArtwork(pick.poster, Tmdb.POSTER), titleArtwork(pick.backdrop, Tmdb.BACKDROP),
                    titleArtwork(pick.logo, Tmdb.BACKDROP), "", "", false, null);
            if (mapping.putIfAbsent(card.key(), pick) == null) row.cards.add(card);
        }
        put(key, row, mapping);
    }

    static String titleArtwork(String path, String prefix) {
        if (path == null || path.isEmpty() || "null".equals(path)) return "";
        return path.startsWith("/") ? prefix + path : path;
    }

    private void showNextCatalog() {
        CatalogRow next = activeRow == null ? null : findCatalog(rows.indexOfChild(activeRow) + 1, 1);
        nextCatalog.setText(next == null ? "" : next.name);
    }

    private void put(String slot, StudioHome.Row row) { put(slot, row, new LinkedHashMap<>()); }

    private void put(String slot, StudioHome.Row row, Map<String, Recs.Pick> picks) {
        CatalogRow old = (CatalogRow) sections.get(slot);
        if (row.cards.isEmpty()) {
            removeSection(slot);
            rememberRows();
            return;
        }
        if (old != null) row = keepCardOrder(slot, old, row);
        String fingerprint = catalogFingerprint(row, picks);
        if (fingerprint.equals(sectionKeys.get(slot))) return;
        sectionKeys.put(slot, fingerprint);
        boolean replacingActive = old != null && old == activeRow;
        CatalogRow view = renderRow(slot, row, picks, old);
        int index = old == null ? 0 : rows.indexOfChild(old);
        if (old == null) for (String key : sections.keySet()) if (compareSlots(key, slot) < 0) index++;
        rows.addView(view, index);
        sections.put(slot, view);
        if (old != null) view.restore(old.position());
        boolean firstOnStartup = index == 0 && !hasNavigated && !menuOpen;
        if (canBrowse(view) && (replacingActive || activeRow == null || firstOnStartup)) activateAfterRefresh(view);
        if (old != null) rows.removeView(old);
        rememberRows();
    }

    private static String catalogFingerprint(StudioHome.Row row, Map<String, Recs.Pick> picks) {
        StringBuilder fingerprint = new StringBuilder(row.name);
        for (StudioHome.Card card : row.cards) {
            fingerprint.append('|').append(card.key()).append(card.name).append(card.image)
                    .append(card.background).append(card.logo).append(card.summary).append(card.landscape)
                    .append(card.portraitArtwork).append(card.metadata);
            if (card.entry != null) fingerprint.append(card.entry);
            Recs.Pick pick = picks.get(card.key());
            if (pick != null && pick.continuing != null) {
                UpNext.Item progress = pick.continuing;
                fingerprint.append(progress.videoId).append(progress.next).append(progress.positionMs)
                        .append(progress.durationMs).append(progress.progress);
            }
        }
        return fingerprint.toString();
    }

    private StudioHome.Row keepCardOrder(String slot, CatalogRow old, StudioHome.Row row) {
        StudioHome.Row stable = new StudioHome.Row(row.name, row.discover);
        stable.catalogId = row.catalogId;
        stable.freeToWatch = row.freeToWatch;
        stable.cards.addAll(HomeRowOrder.reconcile(slot, old.titles, row.cards, StudioHome.Card::key));
        return stable;
    }

    private void rememberRows() {
        if (activeRow != null && activeRow.getParent() != rows) activeRow = null;
        page.rows.clear();
        for (int i = 0; i < rows.getChildCount(); i++) page.rows.add(rows.getChildAt(i));
        showNextCatalog();
    }

    private int compareSlots(String left, String right) {
        int index = Integer.compare(priority(left), priority(right));
        if (index != 0) return index;
        if (left.startsWith("plex:")) {
            return Integer.compare(plexOrder.getOrDefault(left, Integer.MAX_VALUE),
                    plexOrder.getOrDefault(right, Integer.MAX_VALUE));
        }
        if (left.startsWith("studio:")) {
            return Integer.compare(studioOrder.getOrDefault(left, Integer.MAX_VALUE),
                    studioOrder.getOrDefault(right, Integer.MAX_VALUE));
        }
        return left.compareTo(right);
    }

    private static int priority(String slot) {
        if (slot.equals("continue")) return 0;
        if (slot.startsWith("plex:")) return 1;
        if (slot.startsWith("studio:")) return 2;
        return 4;
    }

    private CatalogRow renderRow(String key, StudioHome.Row model, Map<String, Recs.Pick> picks, View previousRow) {
        CatalogRow box = new CatalogRow(key, model, picks);
        TextView heading = text(29, Ui.semibold, TvUi.INK, 1);
        heading.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        heading.setText(model.name);
        box.addView(heading, new LinearLayout.LayoutParams(MATCH, scale(58)));
        View divider = new View(activity);
        divider.setBackgroundColor(0x514C6C86);
        LinearLayout.LayoutParams dividerSize = new LinearLayout.LayoutParams(MATCH, scale(2));
        dividerSize.rightMargin = scale(24);
        dividerSize.bottomMargin = scale(20);
        box.addView(divider, dividerSize);

        TvUi.Row line = box.cards;
        line.setGravity(Gravity.CENTER_VERTICAL);
        for (StudioHome.Card card : model.cards) {
            Recs.Pick pick = picks.get(card.key());
            boolean streamingFolder = card.type.equals("folder") && model.name.equals("Streaming");
            boolean fit = !(posters && card.title()) && portraitFallback(card);
            TvUi.CatalogTile previous = previousRow == null ? null : previousRow.findViewWithTag(card.key());
            boolean logo = card.type.equals("service") || card.type.equals("provider");
            TvUi.CatalogTile tile = TvUi.catalogTile(activity, k, card.name, logo, fit, previous);
            tile.artwork.setMaxWidth(scale(cardWidth(card)));
            tile.artwork.setMaxHeight(scale(cardHeight(card)));
            if (card.type.equals("genre") || card.type.equals("keyword") || card.type.equals("rating")
                    || card.type.equals("review")) {
                tile.caption.setGravity(Gravity.CENTER);
                tile.caption.setTypeface(Ui.semibold);
                tile.caption.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 28 * k);
                if (card.type.equals("review")) tile.caption.setMaxLines(4);
                tile.caption.setLayoutParams(new FrameLayout.LayoutParams(MATCH, MATCH));
            }
            if (card.type.equals("season") || card.type.equals("episode")) {
                tile.overlayCaption(k, card.type.equals("season") ? 19 : 23);
            }
            if (card.type.equals("stream")) {
                String action = card.metadata == null ? "" : card.metadata.optString("action");
                tile.streamDetails(k, card.summary, action);
            }
            FrameLayout cell = new FrameLayout(activity);
            cell.addView(tile, new FrameLayout.LayoutParams(MATCH, MATCH));
            LinearLayout.LayoutParams cellSize = new LinearLayout.LayoutParams(scale(cardWidth(card)), scale(cardHeight(card)));
            if (line.getChildCount() > 0) cellSize.leftMargin = scale(GAP);
            line.addView(cell, cellSize);
            tile.setTag(card.key());
            tile.setOnClickListener(v -> open(card, pick));
            if (usingPublicCatalogs() && card.title()) {
                tile.setOnLongClickListener(v -> {
                    AccountState expected = owner;
                    List<String> labels = new ArrayList<>();
                    labels.add("Open details");
                    labels.add(FreeWatchlist.contains(activity, card) ? "Remove from My list" : "Add to My list");
                    if (pick != null && pick.continuing != null) labels.add("Hide from Continue Watching on this TV");
                    TvUi.popover(activity, v, MediaMenu.anchor(v), card.name, labels.toArray(new String[0]), which -> {
                        if (activity.isFinishing() || activity.isDestroyed() || !catalogOwnerCurrent(expected)) return;
                        if (which == 0) FreeDetailActivity.open(activity, card);
                        else if (which == 1) {
                            FreeWatchlist.toggle(activity, card);
                            if (isMyList()) refreshMyList();
                        } else if (pick != null && pick.continuing != null
                                && UpNext.setHidden(activity, expected, pick.continuing, 0, true)) showContinueWatching();
                    }, false, true);
                    return true;
                });
            } else if (card.savedTitle != null && MediaMenu.supported(card.savedTitle.kind)) {
                tile.setOnLongClickListener(v -> {
                    MediaMenu.show(activity, v, MediaMenu.anchor(v), card.savedTitle, null, false, true, () -> {
                        if (isMyList()) refreshMyList();
                    }, snapshotHero(card.savedTitle));
                    return true;
                });
            } else if (pick != null && MediaMenu.supported(pick.kind)
                    && (pick.id > 0 || pick.continuing != null)) {
                tile.setOnLongClickListener(v -> {
                    Tmdb.Title title = new Tmdb.Title(pick.kind, pick.id, pick.title,
                            pick.poster, pick.backdrop, "", pick.rating);
                    MediaMenu.show(activity, v, MediaMenu.anchor(v), title, pick.continuing, false, true, () -> {
                        if (pick.continuing != null) showContinueWatching();
                        else showRecommendations();
                    }, card == heroCard && pick == heroPick ? snapshotHero() : null);
                    return true;
                });
            }
            if (resultTileDecoration != null && card.savedTitle != null) {
                resultTileDecoration.accept(tile, card.savedTitle);
            }
            tile.setForeground(null);
            tile.setOnFocusChangeListener((view, focused) -> {
                GradientDrawable rim = focused ? TvUi.round(0x00000000, 14 * k) : null;
                if (rim != null) rim.setStroke(scale(4), TvUi.INK);
                tile.setForeground(rim);
                if (!focused) return;
                box.selected = tile;
                if (!keepHero) preview(box, card);
                box.loadVisibleCards();
                queueDiscoverLookup();
                Runnable more = resultPages.get(box.key);
                if (more != null && box.position().index >= box.titles.size() - 5) post(more);
            });
            if (streamingFolder) {
                streamingTiles.put(tile, card);
            } else if (card.title() || card.type.equals("trailer") || card.savedTitle != null) {
                titleTiles.put(tile, card);
            }
        }
        box.scroll.setClipChildren(true);
        box.scroll.addView(line);
        box.addView(box.scroll, new LinearLayout.LayoutParams(MATCH, scale(posters ? POSTER_HEIGHT : CARD_HEIGHT)));
        return box;
    }

    private void preview(CatalogRow row, StudioHome.Card card) {
        if (pinnedHero != null) return;
        heroPick = row.picks.get(card.key());
        heroCatalog = row.name;
        showHero(card, catalogArtwork.get(card.key()));
        loadHero(card);
        if (heroPick != null && heroPick.continuing != null) upNext.enrich(heroPick);
    }

    private int cardWidth(StudioHome.Card card) {
        if (card.type.equals("person") || card.type.equals("season")) return posters ? POSTER_WIDTH : 142;
        if (card.type.equals("service")) return CARD_HEIGHT;
        if (card.type.equals("genre")) return 258;
        return posters && card.title() ? POSTER_WIDTH : CARD_WIDTH;
    }

    private int cardHeight(StudioHome.Card card) {
        if (card.type.equals("genre")) return 120;
        return posters && (card.title() || card.type.equals("person") || card.type.equals("season"))
                ? POSTER_HEIGHT : CARD_HEIGHT;
    }

    private static boolean portraitFallback(StudioHome.Card card) {
        return card.portraitArtwork || !card.landscape && card.background.isEmpty() && card.title();
    }

    private String posterArtwork(StudioHome.Card card) {
        if (!card.title() && !card.type.equals("trailer")) return "";
        String art = StudioHome.art(card.metadata, "poster");
        if (art.isEmpty() && (portraitFallback(card) || !card.landscape || card.metadata == null)) art = card.image;
        return art;
    }

    private void applyCatalogArtwork(TvUi.CatalogTile tile, StudioHome.Card card, boolean prefetch) {
        if (card.type.equals("genre") || card.type.equals("stream")) return;
        boolean portrait = posters && card.title();
        boolean logo = card.type.equals("service") || card.type.equals("provider");
        String poster = posterArtwork(card);
        String wide = catalogArtwork.get(card.key());
        if (wide == null || wide.isEmpty()) wide = card.type.equals("episode") ? card.image : card.background;

        String discover = discoverCardArtwork(discoverArtwork, card, !portrait);
        String otherDiscover = card.title() || card.type.equals("trailer")
                ? discoverCardArtwork(discoverArtwork, card, portrait) : "";
        // Keep differently sized versions as fallbacks, even when the catalog
        // and Discover already reference the same preferred image.
        Map<String, Boolean> candidates = new LinkedHashMap<>();
        addArtwork(candidates, discover, logo);
        if (discover.isEmpty()) addArtwork(candidates, otherDiscover, logo || !portrait);
        if (portrait) addArtwork(candidates, poster, logo);
        addArtwork(candidates, wide, logo);
        addArtwork(candidates, otherDiscover, logo || !portrait);
        addArtwork(candidates, card.image, logo || !portrait && portraitFallback(card));
        addArtwork(candidates, poster, logo || !portrait);

        if (prefetch) {
            if (!candidates.isEmpty()) tile.prefetchArtwork(candidates.keySet().iterator().next());
            return;
        }
        java.util.Iterator<Map.Entry<String, Boolean>> ranked = candidates.entrySet().iterator();
        Map.Entry<String, Boolean> primary = ranked.hasNext() ? ranked.next() : null;
        Map.Entry<String, Boolean> secondary = ranked.hasNext() ? ranked.next() : null;
        Map.Entry<String, Boolean> fallback = ranked.hasNext() ? ranked.next() : null;
        boolean primaryFit = primary != null && primary.getValue();
        boolean secondaryFit = secondary != null && secondary.getValue();
        boolean fallbackFit = fallback != null && fallback.getValue();
        tile.updateArtwork(primary == null ? "" : primary.getKey(), primaryFit, captionSpace(card, portrait, primaryFit),
                secondary == null ? "" : secondary.getKey(), secondaryFit, captionSpace(card, portrait, secondaryFit),
                fallback == null ? "" : fallback.getKey(), fallbackFit, captionSpace(card, portrait, fallbackFit));
        if (!portrait && card.title() && (candidates.isEmpty() || tile.failedArtwork()
                || wide.isEmpty() && discover.isEmpty() && otherDiscover.isEmpty())) {
            loadCatalogArtwork(card);
        }
    }

    private static void addArtwork(Map<String, Boolean> candidates, String url, boolean fit) {
        if (!url.isEmpty()) candidates.putIfAbsent(url, fit);
    }

    private int captionSpace(StudioHome.Card card, boolean portrait, boolean fit) {
        return card.type.equals("provider") ? scale(65)
                : !portrait && fit && !card.type.equals("service") ? scale(46) : 0;
    }

    private void loadCatalogArtwork(StudioHome.Card card) {
        String key = card.key();
        if (catalogArtwork.get(key) != null || !NoiroAccount.ready()
                || !StudioHome.discoverKey(card).matches("(?:movie|series):(?:tt[0-9]{5,12}|tmdb:[1-9][0-9]{0,9})")) return;
        long now = android.os.SystemClock.elapsedRealtime();
        Long retryAt = catalogArtworkPending.get(key);
        if (retryAt != null && retryAt > now) return;
        catalogArtworkPending.put(key, Long.MAX_VALUE);
        int epoch = artworkEpoch;
        AccountState expected = owner;
        artworkIo.execute(() -> {
            String result = null;
            try { if (StudioHome.current(expected)) result = StudioHome.backdrop(card); }
            catch (Exception temporary) { /* Allow a later retry. */ }
            String url = result;
            activity.runOnUiThread(() -> {
                if (epoch != artworkEpoch || activity.isDestroyed() || !StudioHome.current(expected)) return;
                if (url == null) {
                    catalogArtworkPending.put(key, android.os.SystemClock.elapsedRealtime() + ARTWORK_RETRY);
                    return;
                }
                catalogArtworkPending.remove(key);
                catalogArtwork.put(key, url);
                if (url.isEmpty()) return;
                if (activeRow != null) activeRow.loadVisibleCards();
                if (heroCard == card) showHero(card, url);
            });
        });
    }

    private void updateDiscoverArtwork(Map<String, JSONObject> images) {
        discoverArtwork = images;
        if (activeRow != null) activeRow.loadVisibleCards();
        if (heroCard != null) showHero(heroCard, catalogArtwork.get(heroCard.key()));
        queueDiscoverLookup();
    }

    private String discoverCardArtwork(Map<String, JSONObject> index, StudioHome.Card card, boolean wide) {
        JSONObject saved = savedDiscover.get(StudioHome.discoverKey(card));
        if (wide) {
            String cover = selectDiscoverArtwork(index, card, StudioHome::landscapeCover);
            if (cover.isEmpty()) cover = StudioHome.landscapeCover(saved);
            if (!cover.isEmpty()) return cover;
        }
        String art = selectDiscoverArtwork(index, card, fields -> StudioHome.cardArtwork(fields, wide));
        return art.isEmpty() ? StudioHome.cardArtwork(saved, wide) : art;
    }

    private String selectDiscoverArtwork(Map<String, JSONObject> index, StudioHome.Card card,
                                         java.util.function.Function<JSONObject, String> select) {
        String key = StudioHome.discoverKey(card);
        String art = select.apply(resolvedDiscover.get(key));
        if (!art.isEmpty()) return art;
        art = select.apply(index.get(card.key()));
        if (art.isEmpty() && !key.equals(card.key())) art = select.apply(index.get(key));
        if (art.isEmpty()) art = select.apply(index.get(StudioHome.discoverDetailKey(card)));
        return art;
    }

    private void restoreDiscoverCache() {
        AccountState expected = owner;
        int epoch = artworkEpoch;
        discoverCacheIo.execute(() -> {
            Map<String, JSONObject> cached = DiscoverArtworkCache.read(activity, expected);
            activity.runOnUiThread(() -> {
                if (epoch != artworkEpoch || activity.isDestroyed() || !StudioHome.current(expected)) return;
                cached.forEach((key, fields) -> savedDiscover.put(key, combinedFields(key, savedDiscover.get(key), fields)));
                if (activeRow != null) activeRow.loadVisibleCards();
                if (heroCard != null) showHero(heroCard, catalogArtwork.get(heroCard.key()));
                queueDiscoverLookup();
            });
        });
    }

    private static JSONObject combinedFields(String key, JSONObject preferred, JSONObject fallback) {
        Map<String, JSONObject> joined = new LinkedHashMap<>();
        if (preferred != null) StudioHome.mergeDiscover(joined, key, preferred);
        if (fallback != null) StudioHome.mergeDiscover(joined, key, fallback);
        return joined.getOrDefault(key, new JSONObject());
    }

    private void rememberDiscover(String key, JSONObject fields, Map<String, JSONObject> changed) {
        if (key.isEmpty() || fields == null || fields.length() == 0) return;
        JSONObject before = savedDiscover.get(key);
        JSONObject merged = combinedFields(key, fields, before);
        if (before != null && before.toString().equals(merged.toString())) return;
        savedDiscover.put(key, merged);
        changed.put(key, merged);
    }

    private void persistDiscover(Map<String, JSONObject> changed) {
        if (changed.isEmpty() || resultOpener != null) return;
        AccountState expected = owner;
        discoverCacheIo.execute(() -> DiscoverArtworkCache.merge(activity, expected, changed));
    }

    private void queueDiscoverLookup() {
        removeCallbacks(loadVisibleDiscover);
        if (resumed) post(loadVisibleDiscover);
    }

    private void loadVisibleDiscover() {
        if (!resumed) return;
        CatalogRow row = activeRow;
        if (row == null && pinnedHero == null) return;
        long now = android.os.SystemClock.elapsedRealtime();
        List<String> direct = new ArrayList<>(), tmdb = new ArrayList<>();
        Map<String, JSONObject> changed = new LinkedHashMap<>();
        Map<String, String> detailKeys = new LinkedHashMap<>();
        if (pinnedHero != null) collectDiscover(pinnedHero, now, direct, tmdb, changed, detailKeys);
        if (row != null) {
            row.loadVisibleCards();
            collectRowDiscover(row, false, now, direct, tmdb, changed, detailKeys);
            int index = rows.indexOfChild(row);
            CatalogRow next = findCatalog(index + 1, 1);
            CatalogRow previous = findCatalog(index - 1, -1);
            if (next != null) {
                collectRowDiscover(next, true, now, direct, tmdb, changed, detailKeys);
                if (pinnedHero == null && resultOpener == null) {
                    StudioHome.Card upcoming = next.titles.get(next.focusedIndex());
                    if (upcoming.title() || upcoming.type.equals("trailer")) {
                        StudioHome.Hero meta = heroDetails.get(heroCacheKey(upcoming));
                        Images.prefetch(backdrop, heroBackdrop(upcoming, meta, catalogArtwork.get(upcoming.key())), false);
                    }
                }
            }
            if (previous != null) collectRowDiscover(previous, true, now, direct, tmdb, changed, detailKeys);
            CatalogRow later = next == null ? null : findCatalog(rows.indexOfChild(next) + 1, 1);
            if (later != null) collectRowDiscover(later, true, now, direct, tmdb, changed, detailKeys);
        }
        persistDiscover(changed);
        if (NoiroAccount.ready()) {
            if (!directDiscoverRunning && !direct.isEmpty()) requestDiscover(direct, false, detailKeys);
            if (!tmdbDiscoverRunning && !tmdb.isEmpty()) requestDiscover(tmdb, true, detailKeys);
        }
        // Retry on a stationary row as well as after a focus event.
        postDelayed(loadVisibleDiscover, ARTWORK_RETRY);
    }

    private void collectRowDiscover(CatalogRow row, boolean prefetch, long now, List<String> direct, List<String> tmdb,
                                    Map<String, JSONObject> changed, Map<String, String> detailKeys) {
        int focus = row.focusedIndex();
        int[] window = row.artworkWindow();
        int start = window[0], end = window[1];
        for (int step = 0; step < end - start; step++) {
            int i = start + (focus - start + step) % (end - start);
            StudioHome.Card card = row.titles.get(i);
            if (prefetch) {
                TvUi.CatalogTile tile = row.tile(i);
                if (streamingTiles.containsKey(tile)) tile.prefetchArtwork(streamingArtworkSource(card));
                else applyCatalogArtwork(tile, card, true);
            }
            collectDiscover(card, now, direct, tmdb, changed, detailKeys);
        }
    }

    private void collectDiscover(StudioHome.Card card, long now, List<String> direct, List<String> tmdb,
                                 Map<String, JSONObject> changed, Map<String, String> detailKeys) {
        String key = StudioHome.discoverKey(card);
        if (key.isEmpty()) return;
        JSONObject indexed = discoverArtwork.get(card.key());
        if (!key.equals(card.key())) indexed = combinedFields(key, indexed, discoverArtwork.get(key));
        String detailKey = StudioHome.discoverDetailKey(card);
        if (!detailKey.isEmpty()) indexed = combinedFields(key, indexed, discoverArtwork.get(detailKey));
        JSONObject resolved = resolvedDiscover.get(key);
        if (resolved != null) indexed = combinedFields(key, resolved, indexed);
        if (indexed != null) rememberDiscover(key, indexed, changed);
        boolean wide = card == pinnedHero || !posters || !card.title();
        String image = selectDiscoverArtwork(discoverArtwork, card, wide ? StudioHome::landscapeCover
                : fields -> StudioHome.cardArtwork(fields, false));
        if (!image.isEmpty()) return;
        Long retry = discoverRetry.get(key);
        if (retry != null && retry > now) return;
        List<String> batch = key.contains(":tmdb:") ? tmdb : direct;
        if (batch.size() < DISCOVER_BATCH && !batch.contains(key)) batch.add(key);
        if (batch.contains(key) && !detailKey.isEmpty() && !key.equals(detailKey)) detailKeys.putIfAbsent(key, detailKey);
    }

    private void requestDiscover(List<String> keys, boolean tmdb, Map<String, String> detailKeys) {
        AccountState expected = owner;
        int epoch = artworkEpoch;
        if (tmdb) tmdbDiscoverRunning = true;
        else directDiscoverRunning = true;
        discoverIo.execute(() -> {
            JSONObject response = null;
            try { response = StudioHome.resolveDiscoverArtwork(expected, keys, detailKeys); }
            catch (Exception unavailable) { /* Keep cached artwork and retry later. */ }
            JSONObject results = response;
            activity.runOnUiThread(() -> applyResolvedDiscover(epoch, expected, keys, results, tmdb));
        });
    }

    private void applyResolvedDiscover(int epoch, AccountState expected, List<String> keys, JSONObject results, boolean tmdb) {
        if (epoch != artworkEpoch || activity.isDestroyed()) return;
        if (tmdb) tmdbDiscoverRunning = false;
        else directDiscoverRunning = false;
        if (!StudioHome.current(expected)) return;
        long retry = android.os.SystemClock.elapsedRealtime() + (results == null ? ARTWORK_RETRY : 2 * 60_000);
        int matches = 0;
        Map<String, JSONObject> changed = new LinkedHashMap<>();
        for (String key : keys) {
            JSONObject fields = results == null ? null : results.optJSONObject(key);
            JSONObject artwork = StudioHome.discoverFields(fields);
            if (artwork.length() > 0) {
                resolvedDiscover.put(key, combinedFields(key, artwork, resolvedDiscover.get(key)));
                rememberDiscover(key, artwork, changed);
                matches++;
            }
            // A partial match can satisfy one layout while the other still needs a retry.
            if (StudioHome.landscapeCover(artwork).isEmpty() || StudioHome.cardArtwork(artwork, false).isEmpty()) {
                discoverRetry.put(key, retry);
            } else discoverRetry.remove(key);
        }
        persistDiscover(changed);
        if (android.util.Log.isLoggable("NoiroCardArtwork", android.util.Log.DEBUG)) {
            android.util.Log.d("NoiroCardArtwork", "Discover artwork matches: " + matches + "/" + keys.size());
        }
        if (activeRow != null) activeRow.loadVisibleCards();
        if (heroCard != null && keys.contains(StudioHome.discoverKey(heroCard))) {
            showHero(heroCard, catalogArtwork.get(heroCard.key()));
        }
        queueDiscoverLookup();
    }

    private void updateStreamingArtwork(List<StudioHome.Row> rows) {
        plexWideArtwork.clear();
        for (StudioHome.Row row : rows) {
            if (!row.discover) continue;
            for (StudioHome.Card card : row.cards) {
                if (!card.type.equals("service") || !card.image.matches(
                        "https://provider-static[.]plex[.]tv/discover/logos/[A-Za-z0-9/_-]+[.](?i:png|jpe?g|webp)")) continue;
                String name = StudioHome.serviceName(card.name);
                if (card.image.matches(".*-wide[.][A-Za-z0-9]+")) plexWideArtwork.put(name, card.image);
                else plexWideArtwork.putIfAbsent(name, card.image);
            }
        }
    }

    private static String streamingProvider(StudioHome.Card card) {
        return StudioHome.serviceName(card.name.split(" · ", 2)[0]);
    }

    private String streamingArtworkSource(StudioHome.Card card) {
        return plexWideArtwork.getOrDefault(streamingProvider(card), card.image.isEmpty() ? card.logo : card.image);
    }

    private static String streamingArtworkKey(StudioHome.Card card, String source) {
        return source.isEmpty() ? streamingProvider(card) : source;
    }

    private static final class StreamingArtworkRequest {
        final String key;
        long retryAt = Long.MAX_VALUE;

        StreamingArtworkRequest(String key) { this.key = key; }
    }

    private void loadStreamingArtwork(TvUi.CatalogTile tile, StudioHome.Card card) {
        String source = streamingArtworkSource(card);
        String key = streamingArtworkKey(card, source);
        Object pending = tile.artwork.getTag();
        if (pending instanceof StreamingArtworkRequest) {
            StreamingArtworkRequest work = (StreamingArtworkRequest) pending;
            if (key.equals(work.key) && android.os.SystemClock.elapsedRealtime() < work.retryAt) return;
        }
        StreamingArtworkRequest request = new StreamingArtworkRequest(key);
        android.graphics.Bitmap cached = providerArtwork.get(key);
        if (cached != null) {
            tile.updateArtwork(cached);
            tile.artwork.setTag(request);
            return;
        }
        tile.artwork.setTag(request);
        int epoch = artworkEpoch;
        AccountState expected = owner;
        artworkIo.execute(() -> {
            ProviderFrame result = null;
            if (StudioHome.current(expected)) {
                try { result = fetchProviderArtwork(card, source); }
                catch (Exception unavailable) { /* Keep current artwork while waiting to retry. */ }
            }
            ProviderFrame frame = result;
            activity.runOnUiThread(() -> {
                if (epoch != artworkEpoch || activity.isDestroyed() || !StudioHome.current(expected)) return;
                if (frame != null && frame.preferred) providerArtwork.put(key, frame.bitmap);
                if (tile.artwork.getTag() != request || !source.equals(streamingArtworkSource(card))) return;
                if (frame != null) {
                    tile.updateArtwork(frame.bitmap);
                    tile.artwork.setTag(request);
                    if (heroCard == card) showHero(card, card.background);
                }
                if (frame == null || !frame.preferred) {
                    request.retryAt = android.os.SystemClock.elapsedRealtime() + ARTWORK_RETRY;
                }
            });
        });
    }

    private static final class ProviderFrame {
        final android.graphics.Bitmap bitmap;
        final boolean preferred;

        ProviderFrame(android.graphics.Bitmap artwork, boolean preferred) {
            bitmap = TvUi.wideProviderArtwork(artwork);
            this.preferred = preferred;
        }
    }

    private ProviderFrame fetchProviderArtwork(StudioHome.Card card, String source) throws Exception {
        android.graphics.Bitmap original = source.isEmpty() ? null : Images.fetch(source);
        if (original != null) return new ProviderFrame(original, true);
        if (!card.image.isEmpty() && !card.image.equals(source)) original = Images.fetch(card.image);
        if (original == null && !card.logo.isEmpty() && !card.logo.equals(source) && !card.logo.equals(card.image)) {
            original = Images.fetch(card.logo);
        }
        if (original == null) {
            String url = StudioHome.streamingLogo(card);
            if (!url.isEmpty()) original = Images.fetch(url);
        }
        return original == null ? null : new ProviderFrame(original, source.isEmpty());
    }

    private void enterPage(String name, String browse, String key) {
        enterPage(name, browse, key, activeDestination());
    }

    private void enterPage(String name, String browse, String key, String section) {
        cancelTrailer();
        page.activeRow = activeRow;
        page.heroFocus = heroInfo.hasFocus() ? heroInfo : heroAction.hasFocus() ? heroAction : null;
        page.hero = heroCard;
        page.pick = heroPick;
        page.heroCatalog = heroCatalog;
        if (history.isEmpty()) checkedAt = 0;
        history.push(page);
        page = new Page(name, browse, page.positionKey() + "/" + key);
        page.menuSection = section;
        setMenuExpanded(false);
        generation++;
        requestFocus();
        rows.removeAllViews();
        activeRow = null;
        showNextCatalog();
        heroPick = null;
        heroCatalog = name;
    }

    private void open(StudioHome.Card card, Recs.Pick pick) {
        cancelTrailer();
        if (resultCardOpener != null && pick == null) {
            resultCardOpener.accept(card);
            return;
        }
        if (pick != null) {
            if (pick.continuing != null) {
                StreamsActivity.Hero hero = card == heroCard && pick == heroPick ? snapshotHero() : null;
                StreamsActivity.resume(activity, pick.continuing, pick.title, hero);
            } else DetailActivity.open(activity, pick.kind, pick.id);
            return;
        }
        if (usingPublicCatalogs() && card.title() && card.id.matches("tt[0-9]{5,12}")) {
            FreeDetailActivity.open(activity, card);
            return;
        }
        AccountState expected = owner;
        if (!catalogOwnerCurrent(expected)) return;
        if (card.savedTitle != null) {
            if (resultOpener != null) resultOpener.open(card.savedTitle);
            else DetailActivity.open(activity, card.savedTitle.kind, card.savedTitle.id);
            return;
        }
        if (!StudioHome.current(expected)) return;
        if (card.folder()) {
            enterPage(card.name, card.entry == null ? card.id : page.browse, card.key());
            showHero(card, card.image);
            status.setText(Ui.tr("Carregando…", "Loading…"));
            rows.addView(status);
            int gen = generation;
            String viewer = profile;
            io.execute(() -> {
                try {
                    List<StudioHome.Row> catalog = new ArrayList<>();
                    if (card.entry != null) catalog.add(StudioHome.catalog(expected, viewer, card));
                    else catalog.addAll(StudioHome.plex(expected, card.id));
                    main(gen, expected, () -> renderPage(catalog));
                } catch (Exception failure) {
                    main(gen, expected, () -> status.setText(Ui.tr("Catálogo indisponível.", "Catalog unavailable.")));
                }
            });
        } else if (card.type.equals("trailer")) {
            playPlexTrailer(expected, card);
        } else {
            int gen = generation;
            io.execute(() -> {
                try {
                    String imdb = card.id;
                    if (!imdb.startsWith("tt")) {
                        JSONObject hero = StudioHome.plexDetail(expected, card);
                        imdb = hero == null ? "" : hero.optString("imdbId");
                    }
                    String kind = card.type.equals("series") ? "tv" : "movie";
                    if (!imdb.matches("tt[0-9]{5,12}")) throw new IllegalStateException();
                    JSONObject match = StudioHome.tmdbMatch(imdb, kind);
                    int id = match == null ? 0 : match.optInt("id");
                    if (id <= 0) throw new IllegalStateException();
                    main(gen, expected, () -> {
                        StudioHome.Hero metadata = heroDetails.get(heroCacheKey(card));
                        DetailActivity.open(activity, kind, id, metadata == null ? "" : metadata.rating);
                    });
                } catch (Exception failure) { main(gen, expected, this::unavailable); }
            });
        }
    }

    private void cancelTrailer() {
        trailerRequests.cancel();
        if (trailerWork != null) { trailerWork.cancel(true); trailerWork = null; }
    }

    private void playPlexTrailer(AccountState expected, StudioHome.Card card) {
        TrailerPlaybackPolicy.Ticket ticket = trailerRequests.begin(page, expected);
        if (ticket == null || trailerIo.isShutdown()) return;
        String browse = page.browse;
        Toast.makeText(activity, "Loading trailer…", Toast.LENGTH_SHORT).show();
        trailerWork = trailerIo.submit(() -> {
            if (!trailerRequests.pending(ticket)) return;
            String resolved = "", failureCode = "";
            try {
                resolved = StudioHome.trailer(expected, card, browse);
                if (resolved.isEmpty()) failureCode = "trailer_unavailable";
            } catch (Exception failure) {
                failureCode = failure instanceof AccountHttp.Failure
                        ? ((AccountHttp.Failure) failure).code : "service_unavailable";
            }
            final String url = resolved, error = failureCode;
            activity.runOnUiThread(() -> {
                boolean deliver = trailerRequests.current(ticket, page, NoiroAccount.current(), resumed)
                        && !activity.isFinishing() && !activity.isDestroyed();
                if (!trailerRequests.finish(ticket)) return;
                trailerWork = null;
                if (!deliver) return;
                if (!error.isEmpty()) {
                    Toast.makeText(activity, TrailerPlaybackPolicy.failureMessage(error), Toast.LENGTH_LONG).show();
                    return;
                }
                if (!NoiroAccount.ready()) {
                    Toast.makeText(activity, TrailerPlaybackPolicy.failureMessage("supporter_required"), Toast.LENGTH_LONG).show();
                    return;
                }
                NoiroPlayerActivity.openTrailer(activity, new TrailerResolver.Result(url, "", ""), card.id, card.name);
            });
        });
    }

    private void renderPage(List<StudioHome.Row> catalog) {
        Map<String, JSONObject> childArtwork = StudioHome.discoverArtwork(catalog);
        if (!childArtwork.isEmpty()) {
            Map<String, JSONObject> combined = new LinkedHashMap<>(childArtwork);
            discoverArtwork.forEach((identity, artwork) -> StudioHome.mergeDiscover(combined, identity, artwork));
            updateDiscoverArtwork(combined);
        }
        rows.removeAllViews();
        for (StudioHome.Row row : catalog) {
            if (!row.cards.isEmpty()) {
                String key = row.catalogId.isEmpty() ? row.name : row.catalogId;
                rows.addView(renderRow(key, row, new LinkedHashMap<>(), null));
            }
        }
        finishPage(Ui.tr("Nenhum título disponível.", "No titles available."));
    }

    private void finishPage(String emptyMessage) {
        if (rows.getChildCount() == 0) {
            status.setText(emptyMessage);
            rows.addView(status);
        }
        rememberRows();
        CatalogRow chosen = restorePagePosition();
        if (chosen != null) activateAfterRefresh(chosen);
        showNextCatalog();
    }

    private CatalogRow restorePagePosition() {
        PagePosition position = history.isEmpty() ? (page.media ? page.mediaPosition : page.homePosition)
                : positions.get(page.path);
        CatalogRow chosen = null;
        for (View child : page.rows) {
            if (!(child instanceof CatalogRow)) continue;
            CatalogRow row = (CatalogRow) child;
            if (!canBrowse(row)) continue;
            if (chosen == null) chosen = row;
            if (position != null) {
                row.restore(position.cards.get(row.key));
                if (row.key.equals(position.active)) chosen = row;
            }
        }
        return chosen;
    }

    private void unavailable() {
        Toast.makeText(activity, Ui.tr("Título indisponível.", "Title unavailable."), Toast.LENGTH_SHORT).show();
    }

    private void main(int gen, AccountState expected, Runnable work) {
        activity.runOnUiThread(() -> {
            if (!activity.isFinishing() && !activity.isDestroyed() && generation == gen && catalogOwnerCurrent(expected)) work.run();
        });
    }

    private void loadHero(StudioHome.Card card) {
        String key = heroCacheKey(card);
        if ((card.savedTitle != null && !card.title())
                || (card.savedTitle == null && card.detail.isEmpty() && !card.id.matches("tt[0-9]{5,12}"))
                || heroDetails.get(key) != null) return;
        int gen = generation;
        AccountState expected = owner;
        artworkIo.execute(() -> {
            if (generation != gen || !catalogOwnerCurrent(expected)) return;
            try {
                StudioHome.Hero meta = usingPublicCatalogs() && card.title()
                        ? new StudioHome.Hero(FreeCatalog.metadata(card.type, card.id)) : StudioHome.hero(expected, card);
                if (meta == null) return;
                main(gen, expected, () -> {
                    heroDetails.put(key, meta);
                    if (heroCard == card) showHero(card, card.background);
                });
            } catch (Exception ignored) {
                // Keep the catalog's available artwork and text.
            }
        });
    }

    private static String heroCacheKey(StudioHome.Card card) {
        return card.key() + "|" + card.detail;
    }

    StreamsActivity.Hero snapshotHero(Tmdb.Title subject) {
        Tmdb.Title current = heroCard == null ? null : heroCard.savedTitle;
        return current != null && current.id == subject.id && current.kind.equals(subject.kind)
                ? snapshotHero() : null;
    }

    StreamsActivity.Hero snapshotHero() {
        if (heroCard == null) return null;
        String context = heroSource.getText().toString();
        UpNext.Item progress = heroPick == null ? null : heroPick.continuing;
        if (progress != null && UpNextPolicy.valid(progress.type, progress.id, progress.videoId)) {
            String episode = UpNextPolicy.episode(progress.videoId);
            if (!episode.isEmpty() && !context.contains(episode)) {
                context = context.isEmpty() ? episode : context + " · " + episode;
            }
        }
        return new StreamsActivity.Hero(title.getText().toString(), context, heroImage, heroLogoImage,
                metadata.getText().toString(), rating.getText().toString(), synopsis.getText().toString());
    }

    private String heroBackdrop(StudioHome.Card card, StudioHome.Hero meta, String image) {
        if (card == null) return "";
        String hero = StudioHome.art(resolvedDiscover.get(StudioHome.discoverKey(card)), "background");
        if (hero.isEmpty() && meta != null) hero = StudioHome.art(meta.discover, "background");
        if (hero.isEmpty()) hero = StudioHome.art(discoverArtwork.get(card.key()), "background");
        if (hero.isEmpty()) hero = discoverCardArtwork(discoverArtwork, card, true);
        if (hero.isEmpty()) hero = card.background;
        if (hero.isEmpty() && meta != null) hero = meta.background;
        if (hero.isEmpty() && image != null) hero = image;
        if (hero.isEmpty() && card.savedTitle != null && !card.title()) hero = card.image;
        return hero;
    }

    private void showHero(StudioHome.Card card, String image) {
        heroCard = card;
        heroAction.setVisibility(card == null ? GONE : VISIBLE);
        UpNext.Item progress = heroPick == null ? null : heroPick.continuing;
        heroInfo.setVisibility(pinnedOptions != null || progress != null && heroPick.id > 0 ? VISIBLE : GONE);
        heroInfoGlyph.setText(pinnedOptions == null ? "ⓘ" : "⋯");
        heroInfo.setContentDescription(pinnedOptions == null ? Ui.tr("Detalhes", "Details") : Ui.tr("Opções", "Options"));
        boolean showProgress = progress != null && !progress.next && progress.durationMs > 0 && progress.positionMs > 0;
        progressLine.setVisibility(showProgress ? VISIBLE : GONE);
        if (showProgress) {
            heroProgress.setProgress((int) Math.min(1000, 1000.0 * progress.positionMs / progress.durationMs));
            progressLabel.setText(progress.detail());
        }
        if (card != null) {
            String icon = resultPrimaryIcon != null ? resultPrimaryIcon
                    : card == pinnedHero && card.type.equals("person") ? "person" : "play";
            ((TvUi.Icon) heroAction.getChildAt(0)).symbol(icon);
            heroActionLabel.setText(resultPrimaryLabel != null ? resultPrimaryLabel
                    : progress != null ? Ui.tr("Continuar", "Continue")
                    : card.type.equals("trailer") ? Ui.tr("Assistir", "Watch")
                    : card.folder() ? Ui.tr("Abrir", "Open")
                    : Ui.tr("Detalhes", "Details"));
        }
        StudioHome.Hero meta = card == null ? null : heroDetails.get(heroCacheKey(card));
        if (meta == null && card != null && card.metadata != null) meta = new StudioHome.Hero(card.metadata);
        JSONObject discover = card == null ? null : discoverArtwork.get(card.key());
        JSONObject resolved = card == null ? null : resolvedDiscover.get(StudioHome.discoverKey(card));
        String episode = progress != null ? UpNextPolicy.episode(progress.videoId) : "";
        String context = resultOpener != null || isMyList() || page.title.isEmpty() || page.title.equals(heroCatalog)
                ? heroCatalog : page.title + "  ·  " + heroCatalog;
        heroSource.setText(!showProgress && !episode.isEmpty() ? context + " · " + episode : context);
        String emptyTitle = page.media ? Ui.tr("Mídia", "Media")
                : page.title.isEmpty() ? Ui.tr("Início", "Home") : page.title;
        title.setText(card == null ? emptyTitle : card.name);
        heroLogo.setContentDescription(title.getText());
        String logo = StudioHome.art(resolved, "logo");
        if (logo.isEmpty() && card != null && !card.folder()) logo = StudioHome.art(discover, "logo");
        if (logo.isEmpty() && meta != null && card != null && !card.folder()) logo = meta.logo;
        if (logo.isEmpty() && card != null && !card.folder()) logo = card.logo;
        if (!logo.equals(heroLogoImage)) {
            heroLogoImage = logo;
            Images.load(heroLogo, logo.isEmpty() ? null : logo);
        }
        metadata.setText(meta == null ? "" : TextUtils.join(" · ", meta.tags));
        metadata.setVisibility(metadata.length() == 0 ? GONE : VISIBLE);
        rating.setText(meta == null ? "" : meta.rating);
        int ratingVisibility = rating.length() == 0 ? GONE : VISIBLE;
        imdbBadge.setVisibility(ratingVisibility);
        rating.setVisibility(ratingVisibility);
        LinearLayout.LayoutParams badgeArea = (LinearLayout.LayoutParams) versionBadge.getLayoutParams();
        int gap = ratingVisibility == VISIBLE ? scale(16) : 0;
        if (badgeArea.leftMargin != gap) {
            badgeArea.leftMargin = gap;
            versionBadge.setLayoutParams(badgeArea);
        }
        updateRatingLine();
        refreshVersionBadge(card, meta);
        synopsis.setText(card == null ? "" : meta != null && !meta.description.isEmpty() ? meta.description : card.summary);
        synopsis.setVisibility(synopsis.length() == 0 ? GONE : VISIBLE);
        String hero = heroBackdrop(card, meta, image);
        android.graphics.Bitmap provider = null;
        if (card != null && card.type.equals("folder") && heroCatalog.equals("Streaming")) {
            provider = providerArtwork.get(streamingArtworkKey(card, streamingArtworkSource(card)));
            if (provider == null && activeRow != null) {
                TvUi.CatalogTile tile = activeRow.findViewWithTag(card.key());
                if (tile != null && streamingTiles.get(tile) == card
                        && tile.artwork.getDrawable() instanceof android.graphics.drawable.BitmapDrawable) {
                    provider = ((android.graphics.drawable.BitmapDrawable) tile.artwork.getDrawable()).getBitmap();
                }
            }
        }
        String identity = card == null ? "" : card.key() + (progress == null ? "" : "|" + progress.videoId);
        boolean sameTitle = !identity.isEmpty() && identity.equals(heroArtworkIdentity);
        heroArtworkIdentity = identity;
        if (hero.isEmpty() && provider == null && sameTitle && backdrop.getDrawable() != null) return;
        if (!sameTitle || !hero.equals(heroImage) || heroProviderArtwork != provider) {
            heroImage = hero;
            heroProviderArtwork = provider;
            if (provider == null) Images.load(backdrop, hero.isEmpty() ? null : hero, sameTitle);
            else {
                backdrop.setTag(null);
                backdrop.setImageBitmap(provider);
            }
        }
    }

    private void refreshVersionBadge(StudioHome.Card card, StudioHome.Hero meta) {
        if (resultVersionBadge != null) {
            setVersionBadge(resultVersionBadge);
            return;
        }
        if (card == null) {
            heroVersion.show(owner, "", "");
            return;
        }
        if (card.type.equals("episode")) {
            String videoId = card.metadata == null ? "" : card.metadata.optString("videoId");
            String imdb = videoId.split(":", 2)[0];
            heroVersion.show(owner, "series", ReleaseQuality.videoId("series", imdb, videoId));
            return;
        }
        UpNext.Item episode = heroPick == null ? null : heroPick.continuing;
        String imdb = episode != null ? episode.id : card.id;
        if (episode == null && !UpNextPolicy.valid("movie", imdb, imdb) && meta != null && meta.discover != null) {
            imdb = meta.discover.optString("imdbId");
        }
        heroVersion.show(owner, card.type, ReleaseQuality.videoId(card.type, imdb,
                episode == null ? "" : episode.videoId));
    }

    private void setVersionBadge(String label) {
        versionBadge.setText(label);
        versionBadge.setVisibility(label.isEmpty() ? GONE : VISIBLE);
        updateRatingLine();
    }

    private void updateRatingLine() {
        ratingLine.setVisibility(rating.length() > 0 || versionBadge.length() > 0 ? VISIBLE : GONE);
    }

    private void addFade(GradientDrawable.Orientation direction, LayoutParams artworkArea, int... colors) {
        View fade = new View(activity);
        fade.setBackground(new GradientDrawable(direction, colors));
        addView(fade, new LayoutParams(artworkArea.width, artworkArea.height, artworkArea.gravity));
    }

    private TextView text(int size, android.graphics.Typeface typeface, int color, int lines) {
        TextView view = TvUi.text(activity, k, size, typeface, color);
        view.setMaxLines(lines);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    private int scale(int size) { return Math.round(k * size); }

    private static boolean inside(View child, View wanted) {
        for (View v = child; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            if (v == wanted) return true;
        }
        return false;
    }
}
