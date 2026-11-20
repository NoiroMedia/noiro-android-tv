package tv.noiro.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Posters, backdrops and logos for Explore and the title page: loaded in the background, kept in memory and on disk
 * (the app's cache folder, up to DISK_MAX, each file for TTL), so a row seen before shows at once without the network.
 */
final class Images {
    private Images() {}

    private static final long TTL = 7L * 24 * 3600_000, DISK_MAX = 160L * 1024 * 1024;
    /** An eighth of what the process may use, at most 24 MB: the TV has little memory and the disk holds the rest. */
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(
            (int) Math.min(24L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8)) {
        @Override protected int sizeOf(String key, Bitmap b) { return b.getByteCount(); }
    };
    private static final ThreadPoolExecutor VISIBLE = new ThreadPoolExecutor(
            2, 2, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    // Catalog fallback art can paint while a preferred source is still loading.
    private static final ThreadPoolExecutor FALLBACK = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    private static final ThreadPoolExecutor PRELOAD = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(24),
            work -> new Thread(() -> {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                work.run();
            }, "noiro-artwork-preload"));
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, Fetch> WAITING = new HashMap<>();
    private static volatile File dir;

    private static final class Request {
        final String original, url, key;
        final int width, height;

        Request(ImageView view, String original, boolean catalog) {
            this.original = original;
            int width = catalog ? view.getMaxWidth()
                    : view.getWidth() > 0 ? view.getWidth() : view.getLayoutParams() == null ? 0 : view.getLayoutParams().width;
            int height = catalog ? view.getMaxHeight()
                    : view.getHeight() > 0 ? view.getHeight() : view.getLayoutParams() == null ? 0 : view.getLayoutParams().height;
            if (width <= 0 && view.getMaxWidth() > 0 && view.getMaxWidth() < Integer.MAX_VALUE) width = view.getMaxWidth();
            if (height <= 0 && view.getMaxHeight() > 0 && view.getMaxHeight() < Integer.MAX_VALUE) height = view.getMaxHeight();
            if (width <= 0) width = view.getResources().getDisplayMetrics().widthPixels;
            if (height <= 0) height = Math.round(width * 9f / 16);
            int[] target = ArtworkPolicy.requestDimensions(width, height, catalog);
            this.width = target[0];
            this.height = target[1];
            url = ArtworkPolicy.url(original, this.width, catalog);
            key = url + "@" + this.width + "x" + this.height;
        }
    }

    private static final class Recipient {
        final WeakReference<ImageView> view;
        final BiConsumer<ImageView, Bitmap> callback;

        Recipient(ImageView view, BiConsumer<ImageView, Bitmap> callback) {
            this.view = new WeakReference<>(view);
            this.callback = callback;
        }
    }

    private static final class Fetch implements Runnable {
        final Request request;
        final List<Recipient> recipients = new ArrayList<>();
        ThreadPoolExecutor queue;
        boolean started;

        Fetch(Request request, ThreadPoolExecutor queue) {
            this.request = request;
            this.queue = queue;
        }

        @Override public void run() {
            synchronized (WAITING) {
                if (started || WAITING.get(request.key) != this) return;
                started = true;
            }
            finish(fetch(request.url, request.width, request.height));
        }

        void finish(Bitmap bitmap) {
            synchronized (WAITING) {
                if (WAITING.get(request.key) != this) return;
                if (bitmap != null) CACHE.put(request.key, bitmap);
                WAITING.remove(request.key);
            }
            if (recipients.isEmpty()) return;
            MAIN.post(() -> {
                for (Recipient recipient : recipients) {
                    ImageView target = recipient.view.get();
                    if (target != null) recipient.callback.accept(target, bitmap);
                }
            });
        }
    }

    /** The disk cache's folder; old files are dropped once per start. */
    static synchronized void init(Context c) {
        if (dir != null) return;
        // Fetch fresh originals once after the landscape quality update.
        dir = new File(c.getApplicationContext().getCacheDir(), "img-v2");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        PRELOAD.execute(() -> {
            File legacy = new File(c.getApplicationContext().getCacheDir(), "img");
            File[] previous = legacy.listFiles();
            if (previous != null) {
                for (File file : previous) if (file.isFile()) file.delete();
                legacy.delete();
            }
            prune();
        });
    }

    /** Shows the image in the view once it arrives; a view reused for another image in the meantime is left alone. */
    static void load(ImageView view, String url) {
        load(view, url, false);
    }

    static void load(ImageView view, String url, boolean retainWhileLoading) {
        if (dir == null) init(view.getContext());
        if (url == null || url.isEmpty()) {
            if (view.getTag() instanceof Request) cancel(view);
            view.setTag(null);
            view.setImageDrawable(null);
            return;
        }
        Request request = new Request(view, url, false);
        Object previous = view.getTag();
        if (previous instanceof Request && !((Request) previous).key.equals(request.key)) cancel(view);
        view.setTag(request);
        Bitmap hit = CACHE.get(request.key);
        if (hit != null) {
            view.setImageBitmap(hit);
            return;
        }
        if (!retainWhileLoading) view.setImageDrawable(null);
        request(view, request, VISIBLE, (target, bitmap) -> {
            if (bitmap != null && target.getTag() == request) target.setImageBitmap(bitmap);
        });
    }

    static void release(ImageView view) {
        if (view.getTag() instanceof Request) {
            cancel(view);
            view.setTag(new Request(view, ((Request) view.getTag()).original, false));
            view.setImageDrawable(null);
        }
    }

    static void restore(ImageView view) {
        if (view.getTag() instanceof Request && view.getDrawable() == null) {
            load(view, ((Request) view.getTag()).original);
        }
    }

    /** Discard queued work for tiles that are no longer waiting for it. Shared and running downloads may still finish. */
    static void cancel(ImageView view) {
        synchronized (WAITING) {
            Iterator<Fetch> waiting = WAITING.values().iterator();
            while (waiting.hasNext()) {
                Fetch work = waiting.next();
                work.recipients.removeIf(recipient -> {
                    ImageView target = recipient.view.get();
                    return target == null || target == view;
                });
                if (work.recipients.isEmpty() && !work.started && work.queue != PRELOAD) {
                    work.queue.remove(work);
                    waiting.remove();
                }
            }
        }
    }

    /** A weak subscriber which can paint a fallback independently of the preferred request. */
    static void request(ImageView view, String url, boolean fallback, BiConsumer<ImageView, Bitmap> callback) {
        request(view, new Request(view, url, true), fallback ? FALLBACK : VISIBLE, callback);
    }

    private static void request(ImageView view, Request request, ThreadPoolExecutor queue,
                                BiConsumer<ImageView, Bitmap> callback) {
        if (dir == null) init(view.getContext());
        Bitmap hit;
        Fetch work;
        synchronized (WAITING) {
            hit = CACHE.get(request.key);
            if (hit != null) work = null;
            else {
                work = WAITING.get(request.key);
                if (work == null) {
                    work = new Fetch(request, queue);
                    WAITING.put(request.key, work);
                    queue.execute(work);
                } else if (!work.started && (work.queue == PRELOAD || queue == FALLBACK && work.queue == VISIBLE)
                        && work.queue.remove(work)) {
                    work.queue = queue;
                    queue.execute(work);
                }
                work.recipients.add(new Recipient(view, callback));
            }
        }
        if (hit != null) callback.accept(view, hit);
    }

    static void prefetch(ImageView view, String url, boolean catalog) {
        if (url == null || url.isEmpty()) return;
        if (dir == null) init(view.getContext());
        Request request = new Request(view, url, catalog);
        synchronized (WAITING) {
            if (CACHE.get(request.key) != null || WAITING.containsKey(request.key)) return;
            Fetch work = new Fetch(request, PRELOAD);
            WAITING.put(request.key, work);
            try { PRELOAD.execute(work); }
            catch (RejectedExecutionException full) { WAITING.remove(request.key); }
        }
    }

    /** From the disk cache when fresh, otherwise downloaded and saved. */
    static Bitmap fetch(String url) { return fetch(url, 1920, 1080); }

    private static Bitmap fetch(String url, int width, int height) {
        File f = dir == null ? null : new File(dir, Integer.toHexString(url.hashCode()) + "-" + url.length());
        if (f == null) return null;
        Bitmap stale = null;
        if (f.isFile()) {
            stale = decode(f, width, height);
            if (stale != null && System.currentTimeMillis() - f.lastModified() < TTL) return stale;
        }
        File temp = null;
        try {
            temp = File.createTempFile("image-", ".part", dir);
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000); c.setReadTimeout(15000);
            c.setUseCaches(false);
            c.setRequestProperty("Cache-Control", "no-cache");
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[16384]; int count, total = 0;
                while ((count = in.read(buffer)) != -1) {
                    total += count;
                    if (total > 20 * 1024 * 1024) throw new java.io.IOException("image too large");
                    out.write(buffer, 0, count);
                }
            } finally { c.disconnect(); }
            Bitmap result = decode(temp, width, height);
            if (result != null) { synchronized (Images.class) { temp.renameTo(f); } }
            return result == null ? stale : result;
        } catch (Exception | OutOfMemoryError failure) {
            return stale;
        } finally { if (temp != null) temp.delete(); }
    }

    static Bitmap decode(File file, int width, int height) {
        try {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file), (decoder, info, source) -> {
                int[] size = ArtworkPolicy.dimensions(info.getSize().getWidth(), info.getSize().getHeight(), width, height, Runtime.getRuntime().maxMemory());
                decoder.setTargetSize(size[0], size[1]);
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                decoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_DEFAULT);
            });
        } catch (java.io.IOException | RuntimeException | OutOfMemoryError unavailable) { return null; }
    }

    /** Drops files well past their TTL, then the oldest until the folder fits in DISK_MAX. */
    private static void prune() {
        File[] files = dir.listFiles();
        if (files == null) return;
        long now = System.currentTimeMillis(), total = 0;
        List<File> kept = new ArrayList<>();
        for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            if (now - f.lastModified() > 2 * TTL) f.delete();
            else {
                kept.add(f);
                total += f.length();
            }
        }
        if (total <= DISK_MAX) return;
        File[] byAge = kept.toArray(new File[0]);
        Arrays.sort(byAge, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : byAge) {
            if (total <= DISK_MAX * 3 / 4) break;
            total -= f.length();
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    static void trim() { CACHE.evictAll(); }
}
