package tv.noiro.launcher;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The actual app settings navigation; unrelated launcher controls never enter this model. */
final class AppSettingsModel {
    enum Page { MAIN, APPEARANCE }
    enum Row { APPEARANCE, SERVICES, ACCOUNT, WHERE_TO_WATCH, TV_SETTINGS, LICENSES, UPDATES,
        LANGUAGE, PLAYER_APPEARANCE }

    private static final List<Row> MAIN = Collections.unmodifiableList(Arrays.asList(
            Row.APPEARANCE, Row.SERVICES, Row.ACCOUNT, Row.WHERE_TO_WATCH,
            Row.TV_SETTINGS, Row.LICENSES, Row.UPDATES));
    private static final List<Row> APPEARANCE = Collections.unmodifiableList(Arrays.asList(
            Row.LANGUAGE, Row.PLAYER_APPEARANCE));
    private Page page = Page.MAIN;
    private int focus;

    Page page() { return page; }
    List<Row> rows() { return page == Page.MAIN ? MAIN : APPEARANCE; }
    int focus() { return focus; }
    Row selected() { return rows().get(focus); }
    static int maximumRows() { return Math.max(MAIN.size(), APPEARANCE.size()); }

    void focus(int row) {
        focus = Math.max(0, Math.min(rows().size() - 1, row));
    }

    void move(int direction) { focus(focus + Integer.signum(direction)); }

    boolean openSelected() {
        if (selected() != Row.APPEARANCE) return false;
        page = Page.APPEARANCE;
        focus = 0;
        return true;
    }

    boolean back() {
        if (page == Page.MAIN) return false;
        page = Page.MAIN;
        focus = MAIN.indexOf(Row.APPEARANCE);
        return true;
    }

    static String cycle(String[] choices, String current, int direction) {
        if (choices.length == 0) throw new IllegalArgumentException("No settings choices");
        int index = Arrays.asList(choices).indexOf(current);
        if (index < 0) return choices[0];
        return choices[(index + Integer.signum(direction) + choices.length) % choices.length];
    }
}
