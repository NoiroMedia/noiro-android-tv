"""Execute actual SRT/WebVTT timing code without Android or account credentials."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]

class SubtitleTimingTests(unittest.TestCase):
    def test_source_timings_unicode_and_seek(self):
        java = Path(os.environ['JAVA_HOME']) / 'bin'
        src = ROOT / 'launcher-ui/app/src/tv/noiro/launcher'
        with tempfile.TemporaryDirectory() as tmp:
            harness = Path(tmp) / 'CaptionHarness.java'
            harness.write_text(r'''package tv.noiro.launcher;
import java.util.List;
public class CaptionHarness {
  static void check(boolean b) { if (!b) throw new AssertionError(); }
  public static void main(String[] args) {
    List<TimedSubtitles.Cue> srt = TimedSubtitles.parse("1\r\n00:00:01,100 --> 00:00:02,500\r\nĆao &amp; <i>hello</i>\r\n\r\n2\r\n00:00:04,000 --> 00:00:05,000\r\nДруги ред\r\n");
    check(srt.size() == 2);
    check(TimedSubtitles.at(srt, 1099).isEmpty());
    check(TimedSubtitles.at(srt, 1100).equals("Ćao & hello"));
    check(TimedSubtitles.at(srt, 2500).isEmpty());
    check(TimedSubtitles.at(srt, 4500).equals("Други ред"));
    check(TimedSubtitles.at(srt, 1200).equals("Ćao & hello"));
    List<TimedSubtitles.Cue> preview = java.util.Arrays.asList(
        new TimedSubtitles.Cue(1100, 2500, "preview"),
        new TimedSubtitles.Cue(9000, 10000, "ahead of the complete job"));
    List<TimedSubtitles.Cue> partial = TimedSubtitles.prefer(srt.subList(0, 1), preview);
    check(partial.size() == 2);
    check(TimedSubtitles.at(partial, 1200).equals("Ćao & hello"));
    check(TimedSubtitles.at(partial, 9500).equals("ahead of the complete job"));
    List<TimedSubtitles.Cue> vtt = TimedSubtitles.parse("WEBVTT\n\ncaption-id\n01:02.010 --> 01:04.020 align:center\nfirst\nsecond\n\n00:01.000 --> 00:02.000\nearlier\n");
    check(vtt.size() == 2 && TimedSubtitles.at(vtt, 62010).equals("first\nsecond"));
    check(TimedSubtitles.parse("WEBVTT\n\n").isEmpty());
    check(TimedSubtitles.parse("00:00:02.000 --> 00:00:01.000\ninvalid\n").isEmpty());
    check(StreamPolicy.resourceEndpoint("https://noiro.tv/addons/subtitles/config/manifest.json", "subtitles", "series", "tt1234567:2:1").equals("https://noiro.tv/addons/subtitles/config/subtitles/series/tt1234567%3A2%3A1.json"));
  }
}''')
            subprocess.run([str(java / 'javac'), '-encoding', 'UTF-8', '-d', tmp, str(src / 'TimedSubtitles.java'), str(src / 'StreamPolicy.java'), str(harness)], check=True)
            subprocess.run([str(java / 'java'), '-cp', tmp, 'tv.noiro.launcher.CaptionHarness'], check=True)
