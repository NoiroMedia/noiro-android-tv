package tv.noiro.launcher;

/** Bounded, native-size card textures on 4K-capable Android TV UI surfaces. */
final class BackdropQualityPolicy {
    private BackdropQualityPolicy() {}
    static int renderWidth(int uiWidth, long maxHeap) {
        return uiWidth >= 3840 && maxHeap >= 384L * 1024 * 1024 ? 3840 : 1920;
    }
    static int scale(int renderWidth) { return renderWidth / 1920; }
    static int cacheLimit(boolean light, int renderWidth) {
        return renderWidth >= 3840 ? (light ? 4 : 2) : (light ? 10 : 4);
    }
}
