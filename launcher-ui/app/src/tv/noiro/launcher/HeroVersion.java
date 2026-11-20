package tv.noiro.launcher;

import android.app.Activity;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;
import android.view.Display;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/** Check the visible hero, and cache labels without retaining playback URLs or headers. */
final class HeroVersion {
    private final Activity activity;
    private final Consumer<String> display;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final LruCache<String, Cached> cache = new LruCache<>(64);
    private AccountState owner = AccountState.EMPTY;
    private String requested = "";
    private Runnable scheduled;
    private Future<?> running;
    private int revision;
    private boolean active;

    private static final class Cached {
        final String label;
        final long expiresAt;

        Cached(String label, boolean complete) {
            this.label = label;
            expiresAt = SystemClock.elapsedRealtime() + (complete && !label.isEmpty() ? 600_000L : 60_000L);
        }
    }

    HeroVersion(Activity activity, Consumer<String> display) {
        this.activity = activity;
        this.display = display;
    }

    void resume() { active = true; }

    void pause() {
        active = false;
        cancel();
    }

    void clear() {
        cache.evictAll();
        cancel();
        display.accept("");
    }

    void destroy() {
        pause();
        worker.shutdownNow();
    }

    private void cancel() {
        revision++;
        requested = "";
        if (scheduled != null) main.removeCallbacks(scheduled);
        if (running != null) running.cancel(true);
        scheduled = null;
        running = null;
    }

    void show(AccountState account, String type, String videoId) {
        if (!owner.owns(account.authKey, account.uid)) {
            clear();
            owner = account;
        }
        String key = active && NoiroAccount.ready() && StudioHome.current(account) && !videoId.isEmpty()
                ? type + "/" + videoId : "";
        if (!key.equals(requested)) {
            cancel();
            requested = key;
            display.accept("");
        }
        if (key.isEmpty()) return;
        Cached cached = cache.get(key);
        if (cached != null && cached.expiresAt > SystemClock.elapsedRealtime()) {
            display.accept(cached.label);
            return;
        }
        if (scheduled != null || running != null) return;
        display.accept("");
        int run = revision;
        scheduled = () -> {
            scheduled = null;
            running = worker.submit(() -> {
                Cached result = lookup(account, type, videoId);
                if (Thread.currentThread().isInterrupted()) return;
                main.post(() -> {
                    if (run != revision || !active || activity.isDestroyed() || activity.isFinishing()) return;
                    running = null;
                    if (!NoiroAccount.ready() || !StudioHome.current(account)) return;
                    cache.put(key, result);
                    display.accept(result.label);
                });
            });
        };
        main.postDelayed(scheduled, 450);
    }

    private Cached lookup(AccountState account, String type, String videoId) {
        try {
            StremioStreams.Result streams = StremioStreams.load(account, type, videoId);
            return new Cached(bestLabel(activity.getWindowManager().getDefaultDisplay(), streams.sources), streams.failures == 0);
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            return new Cached("", false);
        } catch (Exception unavailable) {
            return new Cached("", false);
        }
    }

    static String bestLabel(Display display, java.util.List<StremioStreams.Source> sources) {
        if (sources.isEmpty()) return "";
        try {
            ReleaseQuality.Playback playback = new DevicePlayback(display);
            ReleaseQuality best = null;
            for (StremioStreams.Source source : sources) {
                if (source.needsStremio) continue;
                ReleaseQuality candidate = ReleaseQuality.parse(source.filename, source.name, source.description, playback);
                if (candidate != null && (best == null || candidate.compareTo(best) > 0)) best = candidate;
            }
            return best == null ? "" : best.label();
        } catch (RuntimeException unavailable) {
            return "";
        }
    }

    private static final class DevicePlayback implements ReleaseQuality.Playback {
        private final MediaCodecInfo[] codecs;
        private final int[] hdr;
        private final int maxHeight;
        private final Map<String, Boolean> sizes = new HashMap<>();

        DevicePlayback(Display display) {
            codecs = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
            Display.HdrCapabilities capabilities = display.getHdrCapabilities();
            hdr = capabilities == null ? new int[0] : capabilities.getSupportedHdrTypes();
            int height = display.getMode().getPhysicalHeight();
            for (Display.Mode mode : display.getSupportedModes()) height = Math.max(height, mode.getPhysicalHeight());
            maxHeight = height;
        }

        @Override public boolean supports(int height, String codec, ReleaseQuality.Range range) {
            if (height > maxHeight) return false;
            switch (range) {
                case DV: return hdr(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION)
                        && decoder("video/dolby-vision", height);
                case HDR10_PLUS:
                    if (!hdr(Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS)) return false;
                    break;
                case HDR10:
                case HDR:
                    if (!hdr(Display.HdrCapabilities.HDR_TYPE_HDR10)) return false;
                    break;
                case HLG:
                    if (!hdr(Display.HdrCapabilities.HDR_TYPE_HLG)) return false;
                    break;
                default: break;
            }
            return codec.isEmpty() || decoder(codec, height);
        }

        private boolean hdr(int kind) {
            for (int type : hdr) if (type == kind) return true;
            return false;
        }

        private boolean decoder(String mime, int height) {
            return sizes.computeIfAbsent(mime + "/" + height, key -> {
                for (MediaCodecInfo codec : codecs) {
                    if (codec.isEncoder()) continue;
                    try {
                        MediaCodecInfo.VideoCapabilities video = codec.getCapabilitiesForType(mime).getVideoCapabilities();
                        int width = (height * 16 / 9 + 1) & ~1;
                        if (video != null && (height == 0 || video.isSizeSupported(width, height))) return true;
                    } catch (IllegalArgumentException unsupported) { /* This decoder handles another codec. */ }
                }
                return false;
            });
        }
    }
}
