"""Execute resume pointer and completion rules without Android or a live account."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class UpNextTests(unittest.TestCase):
    def test_exact_resume_percent_fallback_next_episode_and_identity(self):
        java = Path(os.environ['JAVA_HOME']) / 'bin'
        source = ROOT / 'launcher-ui/app/src/tv/noiro/launcher/UpNextPolicy.java'
        with tempfile.TemporaryDirectory() as tmp:
            harness = Path(tmp) / 'UpNextHarness.java'
            harness.write_text('''package tv.noiro.launcher;
public class UpNextHarness {
  static void check(boolean value) { if (!value) throw new AssertionError(); }
  public static void main(String[] args) {
    check(UpNextPolicy.valid("movie","tt1234567","tt1234567"));
    check(UpNextPolicy.valid("series","tt1234567","tt1234567:2:1"));
    check(!UpNextPolicy.valid("series","tt1234567","tt7654321:2:1"));
    check(!UpNextPolicy.valid("series","tt1234567","tt1234567"));
    check(!UpNextPolicy.valid("movie","tt1234567","tt1234567:2:1"));
    check(!UpNextPolicy.valid("series","tt1234567","tt1234567:1:0"));
    check(UpNextPolicy.start(600000, 50, 1800000, false) == 600000);
    check(UpNextPolicy.start(0, 25, 2400000, false) == 600000);
    check(UpNextPolicy.start(600000, 25, 2400000, true) == 0);
    check(UpNextPolicy.start(0, Double.NaN, 2400000, false) == 0);
    check(UpNextPolicy.start(9999999, 50, 2400000, false) == 2399000);
    check(!UpNextPolicy.completed(890, 1000, false));
    check(UpNextPolicy.completed(900, 1000, false));
    check(UpNextPolicy.completed(0, 0, true));
    check(UpNextPolicy.episode("tt1234567:2:1").equals("S02 E01"));
    // Paused TV timers and a later exit must not replace newer activity from
    // another device with a fresh timestamp for this unchanged old position.
    UpNextPolicy.Checkpoints points = new UpNextPolicy.Checkpoints();
    check(!points.shouldRecord(600000,1800000,false,true,false));
    check(points.shouldRecord(600000,1800000,false,true,true));
    points.recorded(600000,1800000,false);
    check(points.shouldRecord(601000,1800000,false,false,false)); // pause saves final advancement
    points.recorded(601000,1800000,false);
    for(int timer=0;timer<1000;timer++) check(!points.shouldRecord(601000,1800000,false,true,false));
    check(!points.shouldRecord(601000,1800000,false,false,false)); // exit after a long pause
    check(!points.shouldRecord(601000,1800000,false,true,true)); // buffering does not advance progress
    check(points.shouldRecord(602000,1800000,false,true,true)); // genuine playback resumes
    // Failed durable storage leaves the snapshot unacknowledged, so exit retries.
    check(points.shouldRecord(602000,1800000,false,false,false));
    points.recorded(602000,1800000,false);
    check(points.shouldRecord(0,1800000,false,false,false)); // intentional restart/seek backward
    points.recorded(0,1800000,false);
    check(points.shouldRecord(1799990,1800000,true,false,false));
    points.recorded(1799990,1800000,true);
    check(!points.shouldRecord(1800000,1800000,false,true,false)); // ended timer
    check(!points.shouldRecord(1800000,1800000,true,false,false)); // ended exit
  }
}''')
            subprocess.run([str(java/'javac'), '-d', tmp, str(source), str(harness)], check=True)
            subprocess.run([str(java/'java'), '-cp', tmp, 'tv.noiro.launcher.UpNextHarness'], check=True)
