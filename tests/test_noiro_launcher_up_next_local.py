"""Exercise the launcher's local-only dismissal policy without Android or a TV."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class UpNextLocalTests(unittest.TestCase):
    def test_local_mark_unmark_is_title_specific(self):
        java = Path(os.environ['JAVA_HOME']) / 'bin'
        source = ROOT / 'launcher-ui/app/src/tv/noiro/launcher/UpNextLocalPolicy.java'
        with tempfile.TemporaryDirectory() as tmp:
            harness = Path(tmp) / 'UpNextLocalHarness.java'
            harness.write_text(r'''package tv.noiro.launcher;
import java.util.*;
public class UpNextLocalHarness {
  static void check(boolean value) { if (!value) throw new AssertionError(); }
  public static void main(String[] args) {
    Set<String> values = new HashSet<>();
    values = UpNextLocalPolicy.setHidden(values, "movie", "tt1234567", 12, true);
    check(UpNextLocalPolicy.isHidden(values, "movie", "tt1234567"));
    check(!UpNextLocalPolicy.isHidden(values, "movie", "tt7654321"));
    check(!UpNextLocalPolicy.isHidden(values, "series", "tt1234567"));
    values = UpNextLocalPolicy.setHidden(values, "series", "tt1234567", 32, true);
    check(values.size() == 2);
    values = UpNextLocalPolicy.clearTitle(values, "movie", 12);
    check(!UpNextLocalPolicy.isHidden(values, "movie", "tt1234567"));
    check(UpNextLocalPolicy.isHidden(values, "series", "tt1234567"));
    values = UpNextLocalPolicy.setHidden(values, "series", "tt1234567", 32, false);
    check(values.isEmpty());
    values = UpNextLocalPolicy.setHidden(values, "movie", "tt1234567", 0, true);
    check(UpNextLocalPolicy.isHidden(values, "movie", "tt1234567"));
    values = UpNextLocalPolicy.setHidden(values, "movie", "tt1234567", 12, true);
    check(values.size() == 1 && values.contains("movie:tt1234567|12"));
    check(UpNextLocalPolicy.clearTitle(values, "tv", 12).size() == 1);
    check(UpNextLocalPolicy.clearTitle(values, "movie", 12).isEmpty());
  }
}''')
            subprocess.run([str(java / 'javac'), '-d', tmp, str(source), str(harness)], check=True)
            subprocess.run([str(java / 'java'), '-cp', tmp, 'tv.noiro.launcher.UpNextLocalHarness'], check=True)
