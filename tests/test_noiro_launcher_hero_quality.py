"""The hero badge ranks real release labels and never guesses an episode."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = ROOT / 'launcher-ui/app/src/tv/noiro/launcher'


class HeroQualityTests(unittest.TestCase):
    def test_release_ranking_compatibility_and_exact_episode(self):
        java = Path(os.environ['JAVA_HOME']) / 'bin'
        with tempfile.TemporaryDirectory() as tmp:
            harness = Path(tmp) / 'HeroQualityHarness.java'
            harness.write_text(r'''package tv.noiro.launcher;

public class HeroQualityHarness {
    static final ReleaseQuality.Playback ALL = (height, codec, range) -> true;
    static final ReleaseQuality.Playback SONY_TEST = (height, codec, range) ->
            height <= 2160 && !codec.equals("video/av01") &&
                    range != ReleaseQuality.Range.DV && range != ReleaseQuality.Range.HDR10_PLUS;

    static void check(boolean pass) { if (!pass) throw new AssertionError(); }
    static ReleaseQuality parse(String release) { return ReleaseQuality.parse(release, "", "", ALL); }

    public static void main(String[] args) {
        ReleaseQuality web = parse("Episode.S02E04.1080P-WEB-DL.H264");
        ReleaseQuality disc = parse("Episode.S02E04.BluRay-1080P.x264");
        ReleaseQuality hdr = parse("Episode.S02E04.2160p.WEB-DL.HDR.HEVC");
        check(web.label().equals("1080p · WEB-DL"));
        check(disc.label().equals("1080p · BluRay") && disc.compareTo(web) > 0);
        check(hdr.label().equals("4K HDR · WEB-DL") && hdr.compareTo(disc) > 0);
        check(parse("Episode.1080p.WEBRip").compareTo(web) < 0);
        check(parse("Episode.1080p.HDR.WEB-DL").compareTo(disc) > 0);
        check(parse("Episode.4K").label().equals("4K"));
        check(parse("Episode.2160p.HDR10+.BluRay").label().equals("4K HDR10+ · BluRay"));
        check(ReleaseQuality.parse("Episode.2160p.DV.HEVC", "", "", SONY_TEST) == null);
        check(ReleaseQuality.parse("Episode.2160p.DV.HDR10.HEVC", "", "", SONY_TEST)
                .label().equals("4K HDR10"));
        check(ReleaseQuality.parse("Episode.2160p.WEB-DL.AV1", "", "", SONY_TEST) == null);
        check(ReleaseQuality.parse("Episode.1080p.WEB-DL", "4K add-on", "", SONY_TEST)
                .label().equals("1080p · WEB-DL"));
        check(ReleaseQuality.parse("Episode.2160p", "", "Not cached", ALL) == null);
        check(parse("Show.2025.S02E04") == null);

        check(ReleaseQuality.videoId("series", "tt1234567", "tt1234567:2:4").equals("tt1234567:2:4"));
        check(ReleaseQuality.videoId("series", "tt1234567", "tt1234567:2:5").equals("tt1234567:2:5"));
        check(ReleaseQuality.videoId("series", "tt1234567", "").isEmpty());
        check(ReleaseQuality.videoId("series", "tt1234567", "tt7654321:2:4").isEmpty());
        check(ReleaseQuality.videoId("series", "tt1234567", "tt1234567:2:0").isEmpty());
        check(ReleaseQuality.videoId("movie", "tt1234567", "").equals("tt1234567"));
        check(ReleaseQuality.videoId("movie", "tt1234567", "tt1234567:2:4").isEmpty());
    }
}''')
            subprocess.run([str(java/'javac'), '-d', tmp, str(PACKAGE/'ReleaseQuality.java'),
                            str(PACKAGE/'UpNextPolicy.java'), str(harness)], check=True)
            subprocess.run([str(java/'java'), '-cp', tmp, 'tv.noiro.launcher.HeroQualityHarness'], check=True)
