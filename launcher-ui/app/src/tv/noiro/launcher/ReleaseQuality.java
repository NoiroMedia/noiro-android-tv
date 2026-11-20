package tv.noiro.launcher;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Normalizes and ranks one advertised release at a time. */
final class ReleaseQuality implements Comparable<ReleaseQuality> {
    interface Playback {
        boolean supports(int height, String codec, Range range);
    }

    enum Range {
        SDR(""), HLG("HLG"), HDR("HDR"), HDR10("HDR10"), HDR10_PLUS("HDR10+"), DV("Dolby Vision");
        final String label;
        Range(String label) { this.label = label; }
    }

    private enum Source {
        UNKNOWN(""), HDTV("HDTV"), WEB_RIP("WEBRip"), WEB_DL("WEB-DL"), BLU_RAY("BluRay"), REMUX("BluRay Remux");
        final String label;
        Source(String label) { this.label = label; }
    }

    private static final Pattern RESOLUTION = word("(4320|2160|1440|1080|720|576|540|480|360)p|4k|uhd");
    private static final Pattern DV = word("dolby[ ._-]*vision|dovi|dv");
    private static final Pattern HDR10_PLUS = word("hdr10(?:\\+|[ ._-]*plus)");
    private static final Pattern HDR10 = word("hdr10(?!\\+|[ ._-]*plus)");
    private static final Pattern HDR = word("hdr");
    private static final Pattern HLG = word("hlg");
    private static final Pattern BLU_RAY = word("blu[ ._-]*ray|b[dr][ ._-]*rip|b[dr]remux");
    private static final Pattern REMUX = word("remux|b[dr]remux");
    private static final Pattern WEB_DL = word("web[ ._-]*dl");
    private static final Pattern WEB_RIP = word("web[ ._-]*rip");
    private static final Pattern HDTV = word("hdtv");
    private static final Pattern AVC = word("[xh][ ._-]*264|avc");
    private static final Pattern HEVC = word("[xh][ ._-]*265|hevc");
    private static final Pattern AV1 = word("av1");
    private static final Pattern VP9 = word("vp[ ._-]*9");
    private static final Pattern UNAVAILABLE = word("uncached|not[ ._-]*cached|download[ ._-]*to[ ._-]*debrid");

    private final int height;
    private final Range range;
    private final Source source;

    private ReleaseQuality(int height, Range range, Source source) {
        this.height = height;
        this.range = range;
        this.source = source;
    }

    static ReleaseQuality parse(String filename, String name, String description, Playback device) {
        String metadata = name + "\n" + description;
        if (UNAVAILABLE.matcher(metadata).find()) return null;
        String tags = filename + "\n" + metadata;
        int height = height(filename);
        if (height == 0) height = height(metadata);
        String codec = codec(filename);
        if (codec.isEmpty()) codec = codec(metadata);
        Range range = range(tags, height, codec, device);
        Source source = source(filename);
        if (source == Source.UNKNOWN) source = source(metadata);
        return range == null || height == 0 && range == Range.SDR && source == Source.UNKNOWN
                ? null : new ReleaseQuality(height, range, source);
    }

    private static Pattern word(String token) {
        return Pattern.compile("(?i)(?<![a-z0-9])(?:" + token + ")(?![a-z0-9])");
    }

    private static int height(String text) {
        Matcher match = RESOLUTION.matcher(text);
        if (!match.find()) return 0;
        return match.group(1) == null ? 2160 : Integer.parseInt(match.group(1));
    }

    private static String codec(String tags) {
        if (HEVC.matcher(tags).find()) return "video/hevc";
        if (AVC.matcher(tags).find()) return "video/avc";
        if (AV1.matcher(tags).find()) return "video/av01";
        if (VP9.matcher(tags).find()) return "video/x-vnd.on2.vp9";
        return "";
    }

    private static Range range(String tags, int height, String codec, Playback device) {
        boolean dv = DV.matcher(tags).find();
        boolean plus = HDR10_PLUS.matcher(tags).find();
        boolean hdr10 = HDR10.matcher(tags).find();
        boolean hdr = HDR.matcher(tags).find();
        boolean hlg = HLG.matcher(tags).find();
        if (dv && device.supports(height, codec, Range.DV)) return Range.DV;
        if (plus && device.supports(height, codec, Range.HDR10_PLUS)) return Range.HDR10_PLUS;
        if (hdr10 && device.supports(height, codec, Range.HDR10)) return Range.HDR10;
        if (hdr && device.supports(height, codec, Range.HDR)) return Range.HDR;
        if (hlg && device.supports(height, codec, Range.HLG)) return Range.HLG;
        return !dv && !plus && !hdr10 && !hdr && !hlg && device.supports(height, codec, Range.SDR)
                ? Range.SDR : null;
    }

    private static Source source(String tags) {
        if (BLU_RAY.matcher(tags).find()) return REMUX.matcher(tags).find() ? Source.REMUX : Source.BLU_RAY;
        if (WEB_DL.matcher(tags).find()) return Source.WEB_DL;
        if (WEB_RIP.matcher(tags).find()) return Source.WEB_RIP;
        return HDTV.matcher(tags).find() ? Source.HDTV : Source.UNKNOWN;
    }

    @Override public int compareTo(ReleaseQuality other) {
        int order = Integer.compare(height, other.height);
        if (order == 0) order = range.compareTo(other.range);
        return order == 0 ? source.compareTo(other.source) : order;
    }

    String label() {
        String resolution = height == 2160 ? "4K" : height > 0 ? height + "p" : "";
        String picture = resolution.isEmpty() || range.label.isEmpty()
                ? resolution + range.label : resolution + " " + range.label;
        return picture.isEmpty() || source.label.isEmpty() ? picture + source.label : picture + " · " + source.label;
    }

    static String videoId(String type, String imdb, String pointer) {
        if (!pointer.isEmpty()) return UpNextPolicy.valid(type, imdb, pointer) ? pointer : "";
        return "movie".equals(type) && UpNextPolicy.valid(type, imdb, imdb) ? imdb : "";
    }
}
