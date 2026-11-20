package tv.noiro.launcher;

/** Keeps an absolute selected row inside the available settings viewport. */
final class SettingsViewport {
    private SettingsViewport() {}

    static int visibleRows(float height, float rowHeight, float gap) {
        return Math.max(1, (int) Math.floor((height + gap) / (rowHeight + gap)));
    }

    static int firstRow(int first, int selected, int count, int visible) {
        int maxFirst = Math.max(0, count - visible);
        first = Math.max(0, Math.min(first, maxFirst));
        if (selected < first) first = selected;
        else if (selected >= first + visible) first = selected - visible + 1;
        return Math.max(0, Math.min(first, maxFirst));
    }
}
