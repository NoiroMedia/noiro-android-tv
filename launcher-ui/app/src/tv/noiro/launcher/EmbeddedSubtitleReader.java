package tv.noiro.launcher;

import android.net.Uri;

import androidx.media3.common.C;
import androidx.media3.common.DataReader;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.text.Cue;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.SeekPoint;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.text.CueDecoder;
import androidx.media3.extractor.text.CuesWithTiming;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Previews and demuxes embedded text without decoding audio or video. */
final class EmbeddedSubtitleReader {
    private static final int MAX_SAMPLE_BYTES = 256 * 1024;
    private static final int MAX_CUES = 10_000;
    private static final int FIRST_WINDOW = 4, SECOND_WINDOW = 32;
    private static final int PREVIEW_READ_BYTES = 512 * 1024, FULL_READ_BYTES = 8 * 1024 * 1024;
    private static final long PREVIEW_LOOKBACK_MS = 8_000;
    private static final long SEEK_PREVIEW_AFTER_MS = 45_000;
    private static final long SEEK_PREVIEW_TIMEOUT_MS = 5_000;
    private static final long SEEK_PREVIEW_BYTES = 32L * 1024 * 1024;
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)");

    static final class Result {
        final List<TimedSubtitles.Cue> cues;
        final String language;
        final boolean translated;
        Result(List<TimedSubtitles.Cue> cues, String language) { this(cues, language, false); }
        Result(List<TimedSubtitles.Cue> cues, String language, boolean translated) {
            this.cues = cues;
            this.language = language;
            this.translated = translated;
        }
    }

    static Result read(StremioStreams.Source source, String target, long durationMs, long positionMs,
                       MatroskaSubtitleIndex.CacheLookup cache, Consumer<Result> preview, Consumer<Integer> progress) throws IOException {
        if (!StreamPolicy.web(source.url, true)) throw new IOException("unsupported_source");
        Input index = new Input(source, 32 * 1024);
        PreviewWindow window = new PreviewWindow(target, positionMs, preview);
        try {
            Result indexed = MatroskaSubtitleIndex.read(index, () -> new Input(index, 8 * 1024),
                    target, positionMs, cache, window::indexed, progress);
            if (indexed != null) return indexed;
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException();
        } catch (IOException | ArithmeticException unavailable) {
            index.checkCancelled();
        }
        return scan(index, target, durationMs, window, progress, positionMs >= SEEK_PREVIEW_AFTER_MS);
    }

    private static Result scan(Input resolved, String target, long durationMs, PreviewWindow preview,
                               Consumer<Integer> progress, boolean allowPreviewSeek) throws IOException {
        Input input = new Input(resolved, preview.batches == 0 ? PREVIEW_READ_BYTES : FULL_READ_BYTES);
        Extractor[] extractors = new DefaultExtractorsFactory().setTextTrackTranscodingEnabled(true)
                .createExtractors(Uri.parse(input.source.url), Collections.emptyMap());
        boolean resumeFromStart = false;
        try {
            Extractor selected = null;
            for (Extractor extractor : extractors) {
                try {
                    if (extractor.sniff(input)) {
                        selected = extractor;
                        break;
                    }
                } catch (EOFException shortHeader) {
                    // A different container can have a shorter header.
                } finally {
                    input.resetPeekPosition();
                }
            }
            if (selected == null) return null;
            Output output = new Output();
            selected.init(output);
            PositionHolder seek = new PositionHolder();
            int last = -1;
            long previewDeadline = android.os.SystemClock.elapsedRealtime() + 15_000;
            for (;;) {
                input.checkCancelled();
                int result = selected.read(input, seek);
                if (result == Extractor.RESULT_END_OF_INPUT) break;
                if (output.ready && output.tracks.stream().noneMatch(track -> track.capture)) return null;
                if (result == Extractor.RESULT_SEEK) input.move(seek.position);
                if (allowPreviewSeek && output.ready && output.seekMap != null) {
                    allowPreviewSeek = false;
                    SeekPoint point = preview.seekPoint(output.seekMap);
                    if (point != null) {
                        try {
                            previewAt(selected, output, resolved, point, preview);
                        } catch (IOException | RuntimeException unavailable) {
                            input.checkCancelled();
                            // Full extraction can continue when the container cannot seek reliably.
                        }
                        // A fresh demuxer preserves every cue before the exploratory seek.
                        resumeFromStart = true;
                        break;
                    }
                }
                preview.accept(output, false, false);
                if (preview.batches > 0 || android.os.SystemClock.elapsedRealtime() >= previewDeadline)
                    input.window = FULL_READ_BYTES;
                int percent = durationMs > 0 ? (int) Math.min(99, output.latestCue * 100.0 / durationMs) : 0;
                if (percent > last) { last = percent; progress.accept(percent); }
            }
            if (!resumeFromStart) {
                Track complete = output.best(target, true);
                return complete == null ? null : new Result(complete.cues, complete.language);
            }
        } catch (IllegalStateException invalid) {
            throw new IOException("embedded_subtitle_unreadable");
        } finally {
            for (Extractor extractor : extractors) extractor.release();
        }
        return scan(resolved, target, durationMs, preview, progress, false);
    }

    private static void previewAt(Extractor extractor, Output output, Input resolved, SeekPoint point,
                                  PreviewWindow preview) throws IOException {
        Input input = new Input(resolved, 256 * 1024, (int) SEEK_PREVIEW_TIMEOUT_MS);
        input.move(point.position);
        extractor.seek(point.position, point.timeUs);
        for (Track track : output.tracks) {
            track.pending.reset();
            track.cues.clear();
        }
        PositionHolder seek = new PositionHolder();
        long deadline = android.os.SystemClock.elapsedRealtime() + SEEK_PREVIEW_TIMEOUT_MS;
        while (android.os.SystemClock.elapsedRealtime() < deadline
                && input.getPosition() - point.position < SEEK_PREVIEW_BYTES) {
            input.checkCancelled();
            int result = extractor.read(input, seek);
            if (result == Extractor.RESULT_END_OF_INPUT) break;
            if (result == Extractor.RESULT_SEEK) input.move(seek.position);
            if (preview.accept(output, true, false)) return;
        }
        preview.accept(output, true, true);
    }

    private static final class PreviewWindow {
        final String target;
        final long positionMs, startMs;
        final Consumer<Result> receive;
        int batches, next;
        private long startAfter = -1;

        PreviewWindow(String target, long positionMs, Consumer<Result> receive) {
            this.target = target;
            this.positionMs = Math.max(0, positionMs);
            startMs = Math.max(0, this.positionMs - PREVIEW_LOOKBACK_MS);
            this.receive = receive;
        }

        void indexed(Result result) {
            if (result.cues.isEmpty() || batches >= 2) return;
            for (TimedSubtitles.Cue cue : result.cues) startAfter = Math.max(startAfter, cue.start);
            batches++;
            receive.accept(result);
        }

        SeekPoint seekPoint(SeekMap map) {
            if (batches != 0 || !map.isSeekable()) return null;
            SeekPoint point = map.getSeekPoints(startMs * 1000).first;
            return point.position > 0 && point.timeUs > 0 && point.timeUs <= positionMs * 1000 ? point : null;
        }

        boolean accept(Output output, boolean exploratory, boolean flushSmallWindow) {
            if (!output.ready || batches >= 2) return false;
            Track track = output.best(target, false);
            if (track == null) return false;
            List<TimedSubtitles.Cue> cues = track.cues;
            int offset = exploratory ? 0 : next;
            while (offset < cues.size()) {
                TimedSubtitles.Cue cue = cues.get(offset);
                if (cue.end > startMs && cue.start > startAfter) break;
                offset++;
            }
            if (!exploratory) next = offset;
            int limit = batches == 0 ? FIRST_WINDOW : SECOND_WINDOW;
            int available = cues.size() - offset;
            if (flushSmallWindow) limit = Math.min(limit, available);
            if (limit == 0 || available < limit) return false;
            List<TimedSubtitles.Cue> nearby = new ArrayList<>(cues.subList(offset, offset + limit));
            if (!exploratory) next += limit;
            indexed(new Result(nearby, track.language));
            return true;
        }
    }

    private static final class Output implements ExtractorOutput {
        final List<Track> tracks = new ArrayList<>();
        boolean ready;
        long latestCue;
        SeekMap seekMap;

        Track best(String target, boolean requireCues) {
            Track best = null;
            for (Track track : tracks) {
                if (!track.capture || requireCues && track.cues.isEmpty()) continue;
                if (best == null || track.rank(target) < best.rank(target)) best = track;
            }
            return best;
        }

        @Override public TrackOutput track(int id, int type) {
            Track track = new Track(this, type == C.TRACK_TYPE_TEXT);
            tracks.add(track);
            return track;
        }
        @Override public void endTracks() { ready = true; }
        @Override public void seekMap(SeekMap map) { seekMap = map; }
    }

    private static final class Track implements TrackOutput {
        final Output output;
        final boolean text;
        final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        final List<TimedSubtitles.Cue> cues = new ArrayList<>();
        final CueDecoder decoder = new CueDecoder();
        boolean capture, sdh, forced, defaultTrack;
        String language = "und";

        Track(Output output, boolean text) { this.output = output; this.text = text; }

        @Override public void format(Format format) {
            capture = text && MimeTypes.APPLICATION_MEDIA3_CUES.equals(format.sampleMimeType);
            language = DirectSubtitles.sourceLanguage(format.language);
            if ("und".equals(language) && format.label != null && format.label.toLowerCase(java.util.Locale.ROOT).contains("english"))
                language = "en";
            forced = (format.selectionFlags & C.SELECTION_FLAG_FORCED) != 0;
            defaultTrack = (format.selectionFlags & C.SELECTION_FLAG_DEFAULT) != 0;
            sdh = (format.roleFlags & (C.ROLE_FLAG_CAPTION | C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND)) != 0
                    || (format.label != null && format.label.toLowerCase(java.util.Locale.ROOT).contains("sdh"));
        }

        int rank(String target) {
            return EmbeddedSubtitleReader.rank(language, target, forced, sdh, defaultTrack);
        }

        @Override public int sampleData(DataReader reader, int length, boolean allowEnd, int part) throws IOException {
            if (!capture && reader instanceof ExtractorInput) return ((ExtractorInput) reader).skip(length);
            int limit = Math.min(length, 8192);
            byte[] buffer = new byte[limit];
            int read = reader.read(buffer, 0, limit);
            if (read == -1 && !allowEnd) throw new EOFException();
            if (read > 0 && capture) append(buffer, 0, read);
            return read;
        }

        @Override public void sampleData(ParsableByteArray data, int length, int part) {
            if (capture) append(data.getData(), data.getPosition(), length);
            data.skipBytes(length);
        }

        private void append(byte[] data, int offset, int length) {
            if (pending.size() + length > MAX_SAMPLE_BYTES) throw new IllegalStateException();
            pending.write(data, offset, length);
        }

        @Override public void sampleMetadata(long timeUs, int flags, int size, int offset, CryptoData crypto) {
            if (!capture) return;
            byte[] bytes = pending.toByteArray();
            int start = bytes.length - offset - size;
            if (start < 0 || crypto != null) throw new IllegalStateException();
            CuesWithTiming sample = decoder.decode(timeUs, bytes, start, size);
            pending.reset();
            pending.write(bytes, bytes.length - offset, offset);
            if (sample.startTimeUs < 0 || sample.durationUs <= 0) return;
            List<String> lines = new ArrayList<>();
            for (Cue cue : sample.cues) if (cue.text != null && cue.text.length() > 0) lines.add(cue.text.toString());
            if (lines.isEmpty()) return;
            if (cues.size() >= MAX_CUES) throw new IllegalStateException();
            long from = sample.startTimeUs / 1000, to = (sample.startTimeUs + sample.durationUs) / 1000;
            if (to > from) {
                cues.add(new TimedSubtitles.Cue(from, to, String.join("\n", lines)));
                output.latestCue = Math.max(output.latestCue, to);
            }
        }
    }

    static int rank(String language, String target, boolean forced, boolean sdh, boolean preferred) {
        int rank = forced ? 100 : 0;
        if (!language.equals("en")) rank += language.equals(target) ? 10 : language.equals("und") ? 30 : 20;
        return rank + (sdh ? 2 : 0) + (preferred ? 0 : 1);
    }

    private static final class RangeIdentity {
        private String etag, modified;

        synchronized String validator() {
            return etag != null && !etag.startsWith("W/") ? etag : modified;
        }

        synchronized void verify(String responseTag, String responseModified) throws IOException {
            if (etag != null && responseTag != null && !etag.equals(responseTag)
                    || modified != null && responseModified != null && !modified.equals(responseModified)) {
                throw new IOException("embedded_range_entity_changed");
            }
            if (etag == null) etag = responseTag;
            if (modified == null) modified = responseModified;
        }
    }

    private static final class Input implements ExtractorInput, MatroskaSubtitleIndex.Reader {
        private int window;
        private final int readTimeout;
        private final byte[] header;
        private final RangeIdentity identity;
        private final StremioStreams.Source source;
        private final URL origin;
        private URL endpoint;
        private long position, peek, length, cachedAt;
        private byte[] cached = new byte[0];

        Input(StremioStreams.Source source, int window) throws IOException {
            this.source = source;
            this.window = window;
            readTimeout = 15_000;
            origin = new URL(source.url);
            endpoint = origin;
            identity = new RangeIdentity();
            load(0);
            header = cached;
        }

        Input(Input resolved, int window) {
            this(resolved, window, 15_000);
        }

        Input(Input resolved, int window, int readTimeout) {
            source = resolved.source;
            origin = resolved.origin;
            endpoint = resolved.endpoint;
            length = resolved.length;
            this.window = window;
            this.readTimeout = readTimeout;
            header = resolved.header;
            identity = resolved.identity;
        }

        private void load(long offset) throws IOException {
            checkCancelled();
            URL url = endpoint;
            for (int redirect = 0; redirect < 4; redirect++) {
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                try {
                    connection.setInstanceFollowRedirects(false);
                    connection.setConnectTimeout(Math.min(8000, readTimeout));
                    connection.setReadTimeout(readTimeout);
                    connection.setRequestProperty("User-Agent", "NoiroTV-Launcher/" + NoiroAccount.APP_VERSION);
                    if (sameOrigin(origin, url)) {
                        source.headers.forEach((name, value) -> {
                            if (PlaybackPolicy.allowedHeader(name, value)) connection.setRequestProperty(name, value);
                        });
                    }
                    connection.setRequestProperty("Accept-Encoding", "identity");
                    long requestedEnd = length > 0 ? Math.min(length - 1, offset + window - 1) : offset + window - 1;
                    connection.setRequestProperty("Range", "bytes=" + offset + "-" + requestedEnd);
                    String validator = identity.validator();
                    if (validator != null && sameOrigin(endpoint, url)) {
                        connection.setRequestProperty("If-Range", validator);
                    }
                    int status = connection.getResponseCode();
                    if (status >= 300 && status < 400) {
                        String location = connection.getHeaderField("Location");
                        if (location == null) throw new IOException("embedded_range_unavailable");
                        url = new URL(url, location);
                        if (!StreamPolicy.web(url.toString(), true)) throw new IOException("embedded_range_unavailable");
                        continue;
                    }
                    String header = connection.getHeaderField("Content-Range");
                    Matcher range = CONTENT_RANGE.matcher(header == null ? "" : header);
                    if (status != 206 || !range.matches()) throw new IOException("embedded_range_unavailable");
                    String encoding = connection.getHeaderField("Content-Encoding");
                    if (encoding != null && !encoding.equalsIgnoreCase("identity")) throw new IOException("embedded_range_encoding");
                    long start = Long.parseLong(range.group(1)), end = Long.parseLong(range.group(2));
                    long total = Long.parseLong(range.group(3));
                    if (start != offset || end < start || end > requestedEnd || total <= end || (length != 0 && length != total))
                        throw new IOException("embedded_range_mismatch");
                    identity.verify(connection.getHeaderField("ETag"), connection.getHeaderField("Last-Modified"));
                    byte[] bytes = new byte[(int) (end - start + 1)];
                    try (InputStream stream = connection.getInputStream()) {
                        int copied = 0;
                        while (copied < bytes.length) {
                            checkCancelled();
                            int count = stream.read(bytes, copied, bytes.length - copied);
                            if (count < 0) throw new EOFException();
                            copied += count;
                        }
                    }
                    endpoint = url;
                    length = total;
                    cachedAt = offset;
                    cached = bytes;
                    return;
                } finally { connection.disconnect(); }
            }
            throw new IOException("embedded_range_unavailable");
        }

        private static boolean sameOrigin(URL a, URL b) {
            return a.getProtocol().equals(b.getProtocol()) && a.getHost().equalsIgnoreCase(b.getHost())
                    && (a.getPort() < 0 ? a.getDefaultPort() : a.getPort()) == (b.getPort() < 0 ? b.getDefaultPort() : b.getPort());
        }

        void checkCancelled() throws IOException {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
        }

        void move(long at) throws IOException {
            if (at < 0 || at > length) throw new EOFException();
            position = peek = at;
        }

        private int copy(long at, byte[] target, int offset, int count) throws IOException {
            if (count == 0) return 0;
            if (at >= length) return -1;
            if (at < cachedAt || at >= cachedAt + cached.length) {
                if (at < header.length) {
                    int read = Math.min(count, header.length - (int) at);
                    System.arraycopy(header, (int) at, target, offset, read);
                    return read;
                }
                load(at);
            }
            int inCache = (int) (at - cachedAt);
            int read = Math.min(count, cached.length - inCache);
            System.arraycopy(cached, inCache, target, offset, read);
            return read;
        }

        @Override public long length() { return length; }
        @Override public byte[] bytes(long at, int count) throws IOException {
            if (at < 0 || count < 0 || count > 65_536 || at > length - count) throw new EOFException();
            byte[] bytes = new byte[count];
            for (int filled=0; filled<count;) {
                int read=copy(at+filled, bytes, filled, count-filled);
                if (read<=0) throw new EOFException();
                filled+=read;
            }
            return bytes;
        }

        @Override public int read(byte[] target, int offset, int count) throws IOException {
            int read = copy(position, target, offset, count);
            if (read > 0) { position += read; peek = position; }
            return read;
        }

        @Override public int peek(byte[] target, int offset, int count) throws IOException {
            int read = copy(peek, target, offset, count);
            if (read > 0) peek += read;
            return read;
        }

        private boolean fully(byte[] target, int offset, int count, boolean allowEnd, boolean peeking) throws IOException {
            for (int done = 0; done < count;) {
                int read = peeking ? peek(target, offset + done, count - done) : read(target, offset + done, count - done);
                if (read < 0) {
                    if (allowEnd && done == 0) return false;
                    throw new EOFException();
                }
                done += read;
            }
            return true;
        }

        @Override public boolean readFully(byte[] b, int off, int n, boolean allow) throws IOException { return fully(b, off, n, allow, false); }
        @Override public void readFully(byte[] b, int off, int n) throws IOException { readFully(b, off, n, false); }
        @Override public boolean peekFully(byte[] b, int off, int n, boolean allow) throws IOException { return fully(b, off, n, allow, true); }
        @Override public void peekFully(byte[] b, int off, int n) throws IOException { peekFully(b, off, n, false); }
        @Override public int skip(int count) {
            if (count == 0) return 0;
            int available = (int) Math.min(count, length - position);
            position += available;
            peek = position;
            return available == 0 ? -1 : available;
        }
        @Override public boolean skipFully(int count, boolean allow) throws IOException {
            if (length - position >= count) { position += count; peek = position; return true; }
            if (allow && position == length) return false;
            throw new EOFException();
        }
        @Override public void skipFully(int count) throws IOException { skipFully(count, false); }
        @Override public boolean advancePeekPosition(int count, boolean allow) throws IOException {
            if (length - peek >= count) { peek += count; return true; }
            if (allow && peek == length) return false;
            throw new EOFException();
        }
        @Override public void advancePeekPosition(int count) throws IOException { advancePeekPosition(count, false); }
        @Override public void resetPeekPosition() { peek = position; }
        @Override public long getPeekPosition() { return peek; }
        @Override public long getPosition() { return position; }
        @Override public long getLength() { return length; }
        @Override public <E extends Throwable> void setRetryPosition(long at, E error) throws E { position = peek = at; throw error; }
    }
}
