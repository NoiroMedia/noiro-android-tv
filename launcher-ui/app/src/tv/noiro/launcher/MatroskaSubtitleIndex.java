package tv.noiro.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Reads indexed UTF-8 Matroska tracks and previews trustworthy partial indexes. RFC 9559. */
final class MatroskaSubtitleIndex {
    private static final long EBML = 0x1A45DFA3L, SEGMENT = 0x18538067L, SEEK = 0x114D9B74L;
    private static final long INFO = 0x1549A966L, TRACKS = 0x1654AE6BL, CLUSTER = 0x1F43B675L;
    private static final long CUES = 0x1C53BB6BL, TAGS = 0x1254C367L;
    private static final int MAX_CUES = 10_000;
    private static final int FIRST_WINDOW = 4;
    private static final int NEXT_WINDOW = 32;

    interface Reader {
        long length();

        byte[] bytes(long at, int count) throws IOException;
    }

    interface Readers {
        Reader open() throws IOException;
    }

    interface CacheLookup {
        Future<List<TimedSubtitles.Cue>> find(String timing, int cueCount, String kind);
    }

    private static final class Element {
        final long id, data, end;
        final boolean unknown;

        Element(long id, long data, long end, boolean unknown) {
            this.id = id;
            this.data = data;
            this.end = end;
            this.unknown = unknown;
        }
    }

    private static final class Track {
        long number, uid;
        String language = "en", codec = "", name = "";
        boolean forced, preferred = true, enabled = true, text, unsupported;

        int rank(String target) {
            return EmbeddedSubtitleReader.rank(
                    language,
                    target,
                    forced,
                    name.toLowerCase(java.util.Locale.ROOT).contains("sdh"),
                    preferred);
        }
    }

    private static final class Frame {
        final long cluster, relative, time, duration;

        Frame(long cluster, long relative, long time, long duration) {
            this.cluster = cluster;
            this.relative = relative;
            this.time = time;
            this.duration = duration;
        }
    }

    static EmbeddedSubtitleReader.Result read(
            Reader reader,
            Readers readers,
            String target,
            long positionMs,
            CacheLookup cache,
            Consumer<EmbeddedSubtitleReader.Result> preview,
            Consumer<Integer> progress)
            throws IOException, InterruptedException {
        Parser parser = new Parser(reader);
        Element root = parser.element(0, reader.length());
        if (root.id != EBML) return null;
        Element segment = parser.element(root.end, reader.length());
        if (segment.id != SEGMENT) return null;
        Map<Long, Long> positions = new HashMap<>();
        long at = segment.data;
        for (int i = 0; i < 64 && at < segment.end; i++) {
            Element e = parser.element(at, segment.end);
            if (e.id == CLUSTER || e.unknown) break;
            positions.put(e.id, at);
            if (e.id == SEEK)
                for (Element entry : parser.children(e))
                    if (entry.id == 0x4DBB) {
                        long id = parser.number(entry, 0x53AB, -1),
                                offset = parser.number(entry, 0x53AC, -1);
                        if (offset >= 0 && offset < segment.end - segment.data)
                            positions.putIfAbsent(id, segment.data + offset);
                    }
            at = e.end;
        }
        if (!positions.containsKey(TRACKS) || !positions.containsKey(CUES)) return null;
        List<Track> tracks = new ArrayList<>();
        for (Element entry : parser.children(parser.element(positions.get(TRACKS), segment.end))) {
            if (entry.id != 0xAE) continue;
            Track track = new Track();
            for (Element field : parser.children(entry)) {
                if (field.id == 0xD7) track.number = parser.number(field);
                else if (field.id == 0x73C5) track.uid = parser.number(field);
                else if (field.id == 0x83) track.text = parser.number(field) == 17;
                else if (field.id == 0x86) track.codec = parser.string(field);
                else if (field.id == 0x22B59C || field.id == 0x22B59D)
                    track.language = DirectSubtitles.sourceLanguage(parser.string(field));
                else if (field.id == 0x536E) track.name = parser.string(field);
                else if (field.id == 0x55AA) track.forced = parser.number(field) != 0;
                else if (field.id == 0x88) track.preferred = parser.number(field) != 0;
                else if (field.id == 0xB9) track.enabled = parser.number(field) != 0;
                else if (field.id == 0x6D80
                        || field.id == 0x23314F
                        || (field.id == 0x56AA && parser.number(field) != 0))
                    track.unsupported = true;
            }
            if (track.text
                    && track.enabled
                    && track.number > 0
                    && (track.codec.startsWith("S_TEXT/") || track.codec.equals("S_HDMV/TEXTST")))
                tracks.add(track);
        }
        Track track =
                tracks.stream()
                        .min(Comparator.comparingInt(value -> value.rank(target)))
                        .orElse(null);
        if (track == null
                || track.uid == 0
                || track.unsupported
                || !track.codec.equals("S_TEXT/UTF8")) return null;
        long count = positions.containsKey(TAGS)
                ? frameCount(parser, parser.element(positions.get(TAGS), segment.end), track.uid)
                : -1;
        List<Frame> frames = new ArrayList<>();
        Set<String> locations = new HashSet<>();
        for (Element point : parser.children(parser.element(positions.get(CUES), segment.end))) {
            if (point.id != 0xBB) continue;
            long time = -1;
            List<Element> fields = parser.children(point);
            for (Element field : fields) {
                if (field.id == 0xB3) {
                    time = parser.number(field);
                    break;
                }
            }
            for (Element field : fields) {
                if (field.id != 0xB7) continue;
                long cueTrack = -1, cluster = -1, relative = -1, duration = -1;
                for (Element child : parser.children(field)) {
                    if (child.id == 0xF7) cueTrack = parser.number(child);
                    else if (child.id == 0xF1) cluster = parser.number(child);
                    else if (child.id == 0xF0) relative = parser.number(child);
                    else if (child.id == 0xB2) duration = parser.number(child);
                }
                if (cueTrack != track.number || cluster < 0 || cluster >= segment.end - segment.data
                        || relative < 0 || time < 0) continue;
                if (!locations.add(cluster + ":" + relative)) continue;
                if (frames.size() >= MAX_CUES) return null;
                frames.add(new Frame(segment.data + cluster, relative, time, duration));
            }
        }
        if (frames.isEmpty()) return null;
        long scale =
                positions.containsKey(INFO)
                        ? parser.number(
                                parser.element(positions.get(INFO), segment.end),
                                0x2AD7B1,
                                1_000_000)
                        : 1_000_000;
        if (scale < 1 || scale > 1_000_000_000L) return null;
        frames.sort(Comparator.comparingLong(frame -> frame.time));
        if (frames.size() != count) {
            previewPartialIndex(readers, frames, track, scale, positionMs, preview);
            return null;
        }
        Future<List<TimedSubtitles.Cue>> cached = null;
        if (frames.size() >= 24 && !track.language.equals(target)) {
            boolean fullTimings = frames.stream().allMatch(frame -> frame.duration > 0);
            StringBuilder sequence = new StringBuilder();
            for (Frame frame : frames) {
                if (sequence.length() > 0) sequence.append('\n');
                sequence.append(Math.multiplyExact(frame.time, scale) / 1_000_000);
                if (fullTimings)
                    sequence.append(':')
                            .append(
                                    Math.multiplyExact(
                                                    Math.addExact(frame.time, frame.duration),
                                                    scale)
                                            / 1_000_000);
            }
            cached =
                    cache.find(
                            sha256(sequence.toString()),
                            frames.size(),
                            fullTimings ? "range" : "start");
        }
        return readFrames(
                readers, frames, track, scale, target, positionMs, cached, preview, progress);
    }

    private static long frameCount(Parser parser, Element tags, long uid) throws IOException {
        for (Element tag : parser.children(tags)) {
            if (tag.id != 0x7373) continue;
            List<Element> entries = parser.children(tag);
            boolean match = false;
            for (Element entry : entries) {
                if (entry.id == 0x63C0 && parser.number(entry, 0x63C5, 0) == uid) {
                    match = true;
                    break;
                }
            }
            if (!match) continue;
            for (Element entry : entries) {
                if (entry.id == 0x67C8 && "NUMBER_OF_FRAMES".equals(parser.string(entry, 0x45A3))) {
                    String value = parser.string(entry, 0x4487);
                    if (value.matches("[0-9]{1,5}")) return Long.parseLong(value);
                }
            }
        }
        return -1;
    }

    private static int firstNearbyFrame(List<Frame> frames, long scale, long positionMs) {
        long nearby = Math.max(0, positionMs - 8_000);
        int from = 0, to = frames.size();
        while (from < to) {
            int mid = (from + to) >>> 1;
            if (frames.get(mid).time * (scale / 1_000_000.0) < nearby) from = mid + 1;
            else to = mid;
        }
        return from;
    }

    private static boolean startsNearPlayback(List<Frame> frames, int first, long scale, long positionMs) {
        return first < frames.size() && frames.get(first).time * (scale / 1_000_000.0) <= positionMs + 60_000;
    }

    private static void previewPartialIndex(Readers readers, List<Frame> frames, Track track,
                                            long scale, long positionMs, Consumer<EmbeddedSubtitleReader.Result> preview)
            throws IOException, InterruptedException {
        int from = firstNearbyFrame(frames, scale, positionMs);
        if (!startsNearPlayback(frames, from, scale, positionMs)) return;
        int to = Math.min(from + FIRST_WINDOW, frames.size());
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<TimedSubtitles.Cue> cues = readWindow(pool, readers, frames.subList(from, to),
                    track.number, scale, new AtomicInteger(), frames.size(), progress -> {});
            preview.accept(new EmbeddedSubtitleReader.Result(cues, track.language));
        } finally {
            pool.shutdownNow();
        }
    }

    private static String sha256(String value) {
        try {
            byte[] bytes =
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte item : bytes) {
                hex.append(Character.forDigit((item & 0xFF) >>> 4, 16));
                hex.append(Character.forDigit(item & 0x0F, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static List<TimedSubtitles.Cue> cachedCues(
            Future<List<TimedSubtitles.Cue>> cached, long timeoutMs) throws InterruptedException {
        if (cached == null) return null;
        try {
            return cached.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (ExecutionException
                | TimeoutException
                | java.util.concurrent.CancellationException unavailable) {
            return null;
        }
    }

    private static EmbeddedSubtitleReader.Result readFrames(
            Readers readers,
            List<Frame> frames,
            Track track,
            long scale,
            String target,
            long positionMs,
            Future<List<TimedSubtitles.Cue>> cached,
            Consumer<EmbeddedSubtitleReader.Result> preview,
            Consumer<Integer> progress)
            throws IOException, InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        AtomicInteger completed = new AtomicInteger();
        List<TimedSubtitles.Cue> result = new ArrayList<>(frames.size());
        try {
            List<TimedSubtitles.Cue> existing = cachedCues(cached, 0);
            if (existing != null) return new EmbeddedSubtitleReader.Result(existing, target, true);
            int first = firstNearbyFrame(frames, scale, positionMs);
            int next = first;
            while (next < frames.size() && startsNearPlayback(frames, first, scale, positionMs)) {
                int window = next == first ? FIRST_WINDOW : NEXT_WINDOW;
                int end = Math.min(next + window, frames.size());
                if (end == next) break;
                List<TimedSubtitles.Cue> cues = readWindow(pool, readers, frames.subList(next, end),
                        track.number, scale, completed, frames.size(), progress);
                result.addAll(cues);
                existing = cachedCues(cached, 0);
                if (existing != null) return new EmbeddedSubtitleReader.Result(existing, target, true);
                if (end < frames.size() || first > 0) preview.accept(new EmbeddedSubtitleReader.Result(cues, track.language));
                next = end;
            }
            result.addAll(
                    readWindow(
                            pool,
                            readers,
                            frames.subList(next, frames.size()),
                            track.number,
                            scale,
                            completed,
                            frames.size(),
                            progress));
            result.addAll(
                    readWindow(
                            pool,
                            readers,
                            frames.subList(0, first),
                            track.number,
                            scale,
                            completed,
                            frames.size(),
                            progress));
            existing = cachedCues(cached, 0);
            if (existing != null) return new EmbeddedSubtitleReader.Result(existing, target, true);
            result.sort(Comparator.comparingLong(cue -> cue.start));
            return new EmbeddedSubtitleReader.Result(result, track.language);
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<TimedSubtitles.Cue> readWindow(
            ExecutorService pool,
            Readers readers,
            List<Frame> frames,
            long track,
            long scale,
            AtomicInteger completed,
            int total,
            Consumer<Integer> progress)
            throws IOException, InterruptedException {
        int workers = Math.min(4, frames.size());
        List<Future<List<TimedSubtitles.Cue>>> work = new ArrayList<>(workers);
        for (int worker = 0; worker < workers; worker++) {
            int start = frames.size() * worker / workers;
            int end = frames.size() * (worker + 1) / workers;
            work.add(
                    pool.submit(
                            () -> {
                                Parser parser = new Parser(readers.open());
                                List<TimedSubtitles.Cue> cues = new ArrayList<>(end - start);
                                for (Frame frame : frames.subList(start, end)) {
                                    cues.add(parser.frame(frame, track, scale));
                                    int done = completed.incrementAndGet();
                                    if (done % 16 == 0)
                                        progress.accept(Math.min(99, 100 * done / total));
                                }
                                return cues;
                            }));
        }
        List<TimedSubtitles.Cue> result = new ArrayList<>(frames.size());
        try {
            for (Future<List<TimedSubtitles.Cue>> future : work) result.addAll(future.get());
        } catch (ExecutionException badIndex) {
            throw new IOException("embedded_index_unreadable", badIndex.getCause());
        }
        return result;
    }

    private static final class Parser {
        final Reader reader;
        int nodes;
        long clusterPosition = -1;
        Element lastCluster;

        Parser(Reader reader) {
            this.reader = reader;
        }

        long[] vint(long at, boolean id) throws IOException {
            int first = reader.bytes(at, 1)[0] & 255, mask = 128, size = 1;
            while (mask > 0 && (first & mask) == 0) {
                mask >>= 1;
                size++;
            }
            if (mask == 0 || id && size > 4) throw new IOException("invalid_ebml");
            long value = id ? first : first & (mask - 1);
            byte[] rest = reader.bytes(at + 1, size - 1);
            for (byte octet : rest) value = (value << 8) | (octet & 255);
            return new long[] {value, size};
        }

        Element element(long at, long limit) throws IOException {
            if (at < 0 || at >= limit || ++nodes > 180_000) throw new IOException("invalid_ebml");
            long[] id = vint(at, true), size = vint(at + id[1], false);
            long data = at + id[1] + size[1];
            boolean unknown = size[0] == (1L << (7 * size[1])) - 1;
            if (data > limit || !unknown && size[0] > limit - data)
                throw new IOException("invalid_ebml");
            return new Element(id[0], data, unknown ? limit : data + size[0], unknown);
        }

        List<Element> children(Element parent) throws IOException {
            List<Element> list = new ArrayList<>();
            for (long at = parent.data; at < parent.end; ) {
                Element child = element(at, parent.end);
                list.add(child);
                at = child.end;
            }
            return list;
        }

        long number(Element field) throws IOException {
            if (field.end - field.data < 1 || field.end - field.data > 8)
                throw new IOException("invalid_ebml");
            long value = 0;
            for (byte item : reader.bytes(field.data, (int) (field.end - field.data)))
                value = (value << 8) | (item & 255);
            return value;
        }

        long number(Element parent, long id, long fallback) throws IOException {
            for (Element child : children(parent)) if (child.id == id) return number(child);
            return fallback;
        }

        String string(Element field) throws IOException {
            if (field.end - field.data > 65_536) throw new IOException("invalid_subtitle");
            return new String(
                    reader.bytes(field.data, (int) (field.end - field.data)),
                    StandardCharsets.UTF_8);
        }

        String string(Element parent, long id) throws IOException {
            for (Element child : children(parent)) if (child.id == id) return string(child);
            return "";
        }

        TimedSubtitles.Cue frame(Frame frame, long track, long scale) throws IOException {
            if (clusterPosition != frame.cluster) {
                lastCluster = element(frame.cluster, reader.length());
                clusterPosition = frame.cluster;
            }
            Element cluster = lastCluster;
            if (cluster.id != CLUSTER || frame.relative >= cluster.end - cluster.data)
                throw new IOException("invalid_cluster");
            Element location = element(cluster.data + frame.relative, cluster.end),
                    block = location;
            long duration = frame.duration;
            if (location.id == 0xA0) {
                block = null;
                for (Element child : children(location)) {
                    if (child.id == 0xA1) block = child;
                    else if (child.id == 0x9B) {
                        long found = number(child);
                        if (duration > 0 && duration != found)
                            throw new IOException("duration_mismatch");
                        duration = found;
                    } else if (child.id == 0x75A1) throw new IOException("unsupported_block");
                }
            } else if (location.id != 0xA3) throw new IOException("invalid_block");
            if (block == null || duration <= 0) throw new IOException("invalid_duration");
            long[] number = vint(block.data, false);
            long text = block.data + number[1] + 3;
            if (number[0] != track || text >= block.end || block.end - text > 65_536)
                throw new IOException("invalid_block");
            if ((reader.bytes(text - 1, 1)[0] & 0x06) != 0) throw new IOException("laced_subtitle");
            long start = Math.multiplyExact(frame.time, scale) / 1_000_000;
            long end = Math.multiplyExact(Math.addExact(frame.time, duration), scale) / 1_000_000;
            String line =
                    new String(reader.bytes(text, (int) (block.end - text)), StandardCharsets.UTF_8)
                            .trim();
            if (end <= start || line.isEmpty()) throw new IOException("invalid_subtitle");
            return new TimedSubtitles.Cue(start, end, line);
        }
    }
}
