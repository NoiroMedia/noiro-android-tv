"""Exercise indexed subtitle previews before a complete embedded track is available."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "launcher-ui/app/src/tv/noiro/launcher"
JAVA = Path(os.environ["JAVA_HOME"]) / "bin" if os.environ.get("JAVA_HOME") else None


def java_tool(name):
    return str(JAVA / name) if JAVA else shutil.which(name)


class EmbeddedSubtitleIndexTests(unittest.TestCase):
    @unittest.skipUnless(java_tool("javac"), "Java compiler unavailable")
    def test_first_window_partial_index_and_cache_reuse(self):
        stub = """package tv.noiro.launcher;
import java.util.List;
final class DirectSubtitles {
    static String sourceLanguage(String value) { return value.equals("eng") ? "en" : value; }
}
final class TimedSubtitles {
    static final class Cue {
        final long start, end;
        final String text;
        Cue(long start, long end, String text) {
            this.start = start;
            this.end = end;
            this.text = text;
        }
    }
}
final class EmbeddedSubtitleReader {
    static int rank(String language, String target, boolean forced, boolean sdh, boolean preferred) { return 0; }
    static final class Result {
        final List<TimedSubtitles.Cue> cues;
        final boolean translated;
        Result(List<TimedSubtitles.Cue> cues, String language) { this(cues, language, false); }
        Result(List<TimedSubtitles.Cue> cues, String language, boolean translated) {
            this.cues = cues;
            this.translated = translated;
        }
    }
}
"""
        fixture = r"""package tv.noiro.launcher;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

public final class MatroskaWindowHarness {
    private static final int CLUSTERS = 8, PER_CLUSTER = 20, CUE_COUNT = CLUSTERS * PER_CLUSTER;
    private static final int PLAYHEAD_MS = 70_000;

    public static void main(String[] args) throws Exception {
        byte[] complete = fixture(true);
        AtomicInteger ranges = new AtomicInteger();
        List<Integer> previewRanges = new ArrayList<>();
        List<List<TimedSubtitles.Cue>> windows = new ArrayList<>();
        EmbeddedSubtitleReader.Result result = MatroskaSubtitleIndex.read(
                new RangeReader(complete, ranges), () -> new RangeReader(complete, ranges), "hr", PLAYHEAD_MS,
                (timing, count, kind) -> {
                    check(count == CUE_COUNT && kind.equals("range") && timing.matches("[0-9a-f]{64}"),
                            "shortcut must fingerprint the complete source");
                    return CompletableFuture.completedFuture(null);
                },
                preview -> {
                    previewRanges.add(ranges.get());
                    windows.add(preview.cues);
                },
                progress -> {});
        check(result != null && !result.translated && result.cues.size() == CUE_COUNT, "complete track");
        check(result.cues.get(0).start == 0 && result.cues.get(CUE_COUNT - 1).start == 159_000,
                "chronological final track");
        check(windows.size() == 2 && windows.get(0).size() == 8 && windows.get(1).size() == 32,
                "bounded preview windows");
        check(windows.get(0).get(0).start == 62_000 && windows.get(1).get(0).start == 70_000,
                "playhead-first extraction");
        check(previewRanges.get(0) < ranges.get() / 2, "first window must use fewer ranges than the full pass");
        check(ranges.get() < CUE_COUNT, "nearby samples must reuse fetched ranges and cluster headers");
        System.out.printf("indexed first window after %d mocked Range requests, entire track after %d%n",
                previewRanges.get(0), ranges.get());

        byte[] partial = fixture(false);
        windows.clear();
        result = MatroskaSubtitleIndex.read(new RangeReader(partial, new AtomicInteger()),
                () -> new RangeReader(partial, new AtomicInteger()), "hr", PLAYHEAD_MS,
                (timing, count, kind) -> { throw new AssertionError("partial index cannot query cache"); },
                preview -> windows.add(preview.cues), progress -> {});
        check(result == null && windows.size() == 1 && windows.get(0).get(0).start == 62_000,
                "index without frame count gives a preview without claiming completeness");

        AtomicInteger sampleReaders = new AtomicInteger();
        List<TimedSubtitles.Cue> stored = Arrays.asList(new TimedSubtitles.Cue(0, 500, "saved"));
        windows.clear();
        result = MatroskaSubtitleIndex.read(new RangeReader(complete, new AtomicInteger()), () -> {
            sampleReaders.incrementAndGet();
            return new RangeReader(complete, new AtomicInteger());
        }, "hr", PLAYHEAD_MS, (timing, count, kind) -> CompletableFuture.completedFuture(stored),
                preview -> windows.add(preview.cues), progress -> {});
        check(result != null && result.translated && result.cues == stored
                && windows.isEmpty() && sampleReaders.get() == 0, "saved track wins before sample reads");
    }

    private static final class RangeReader implements MatroskaSubtitleIndex.Reader {
        private final byte[] media;
        private final AtomicInteger requests;
        private int from = -1, to = -1;

        RangeReader(byte[] media, AtomicInteger requests) { this.media = media; this.requests = requests; }

        @Override public long length() { return media.length; }

        @Override public byte[] bytes(long position, int count) {
            int start = (int) position;
            if (count == 0) return new byte[0];
            if (start < from || start + count > to) {
                from = start;
                to = Math.min(media.length, from + Math.max(count, 8 * 1024));
                requests.incrementAndGet();
            }
            return Arrays.copyOfRange(media, start, start + count);
        }
    }

    private static byte[] fixture(boolean tagged) {
        byte[] tracks = element(0x1654AE6B, element(0xAE, join(
                number(0xD7, 1), number(0x73C5, 7), number(0x83, 17),
                text(0x86, "S_TEXT/UTF8"), text(0x22B59C, "eng"))));
        byte[] tags = tagged ? element(0x1254C367, element(0x7373, join(
                element(0x63C0, number(0x63C5, 7)),
                element(0x67C8, join(text(0x45A3, "NUMBER_OF_FRAMES"), text(0x4487, "" + CUE_COUNT)))))) : new byte[0];
        ArrayList<byte[]> clusters = new ArrayList<>();
        long[] offsets = new long[CLUSTERS];
        int[] relative = new int[CUE_COUNT];
        int cursor = 0;
        for (int i = 0; i < CLUSTERS; i++) {
            ByteArrayOutputStream cluster = new ByteArrayOutputStream();
            cluster.writeBytes(number(0xE7, i * PER_CLUSTER * 1000));
            for (int j = 0; j < PER_CLUSTER; j++) {
                // Sparse elements between samples stand in for video payload that must be skipped.
                cluster.writeBytes(element(0xEC, new byte[3000]));
                relative[i * PER_CLUSTER + j] = cluster.size();
                byte[] block = join(new byte[]{(byte) 0x81, (byte) ((j * 1000) >>> 8), (byte) (j * 1000), 0},
                        ("Line " + (i * PER_CLUSTER + j)).getBytes(StandardCharsets.UTF_8));
                cluster.writeBytes(element(0xA0, join(element(0xA1, block), number(0x9B, 500))));
            }
            byte[] encoded = element(0x1F43B675, cluster.toByteArray());
            offsets[i] = cursor;
            cursor += encoded.length;
            clusters.add(encoded);
        }
        int dataOffset = tracks.length + tags.length + cues(offsets, relative, 0).length;
        return join(element(0x1A45DFA3, new byte[0]), element(0x18538067, join(
                tracks, tags, cues(offsets, relative, dataOffset), join(clusters.toArray(new byte[0][])))));
    }

    private static byte[] cues(long[] offsets, int[] relative, int dataOffset) {
        ByteArrayOutputStream points = new ByteArrayOutputStream();
        for (int i = 0; i < CUE_COUNT; i++) {
            points.writeBytes(element(0xBB, join(number(0xB3, i * 1000), element(0xB7, join(
                    number(0xF7, 1), number(0xF1, offsets[i / PER_CLUSTER] + dataOffset),
                    number(0xF0, relative[i]), number(0xB2, 500))))));
        }
        return element(0x1C53BB6B, points.toByteArray());
    }

    private static byte[] number(int id, long value) { return element(id, ByteBuffer.allocate(8).putLong(value).array()); }

    private static byte[] text(int id, String value) { return element(id, value.getBytes(StandardCharsets.UTF_8)); }

    private static byte[] element(int id, byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = (31 - Integer.numberOfLeadingZeros(id)) / 8; i >= 0; i--) out.write(id >>> (8 * i));
        int length = 1;
        while (body.length >= (1L << (7 * length)) - 1) length++;
        long size = (1L << (7 * length)) | body.length;
        for (int i = length - 1; i >= 0; i--) out.write((int) (size >>> (8 * i)));
        out.writeBytes(body);
        return out.toByteArray();
    }

    private static byte[] join(byte[]... items) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] item : items) out.writeBytes(item);
        return out.toByteArray();
    }

    private static void check(boolean success, String label) {
        if (!success) throw new AssertionError(label);
    }
}
"""
        with tempfile.TemporaryDirectory() as tmp:
            stub_file = Path(tmp) / "SubtitleStubs.java"
            harness_file = Path(tmp) / "MatroskaWindowHarness.java"
            stub_file.write_text(stub)
            harness_file.write_text(fixture)
            subprocess.run(
                [java_tool("javac"), "-d", tmp, str(SOURCE / "MatroskaSubtitleIndex.java"), str(stub_file), str(harness_file)],
                check=True,
            )
            subprocess.run(
                [java_tool("java"), "-cp", tmp, "tv.noiro.launcher.MatroskaWindowHarness"],
                check=True,
            )
