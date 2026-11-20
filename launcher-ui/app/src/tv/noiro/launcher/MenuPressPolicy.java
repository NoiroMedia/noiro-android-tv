package tv.noiro.launcher;

/** A popup must consume the release/repeats belonging to the hold that opened it. */
final class MenuPressPolicy {
    private boolean opening;
    MenuPressPolicy(boolean held) { opening = held; }
    boolean consume(boolean down, int repeat) {
        if (!opening) return false;
        if (down && repeat == 0) { opening = false; return false; }
        if (!down) opening = false;
        return true;
    }
}
