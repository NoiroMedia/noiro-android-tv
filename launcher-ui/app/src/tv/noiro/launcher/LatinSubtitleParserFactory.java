package tv.noiro.launcher;

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.text.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Normalizes textual sample bytes before Media3 parses their timings and cues. Binary formats stay intact. */
@UnstableApi
final class LatinSubtitleParserFactory implements SubtitleParser.Factory {
    private final DefaultSubtitleParserFactory delegate = new DefaultSubtitleParserFactory();
    @Override public boolean supportsFormat(Format format) { return delegate.supportsFormat(format); }
    @Override public int getCueReplacementBehavior(Format format) { return delegate.getCueReplacementBehavior(format); }
    @Override public SubtitleParser create(Format format) {
        SubtitleParser parser = delegate.create(format);
        if (!MimeTypes.APPLICATION_SUBRIP.equals(format.sampleMimeType) && !MimeTypes.TEXT_SSA.equals(format.sampleMimeType)
                && !MimeTypes.TEXT_VTT.equals(format.sampleMimeType)) return parser;
        return new SubtitleParser() {
            @Override public void parse(byte[] data, int offset, int length, OutputOptions options, Consumer<CuesWithTiming> output) {
                byte[] utf8 = SubtitleText.decode(Arrays.copyOfRange(data, offset, offset + length), "", format.language).getBytes(StandardCharsets.UTF_8);
                parser.parse(utf8, 0, utf8.length, options, output);
            }
            @Override public void reset() { parser.reset(); }
            @Override public int getCueReplacementBehavior() { return parser.getCueReplacementBehavior(); }
        };
    }
}
