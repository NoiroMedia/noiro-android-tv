"""Run the real language policy against mixed embedded/add-on language conventions."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class SubtitleSelectionTests(unittest.TestCase):
    def test_priorities_aliases_five_language_limit_and_gemini_consent(self):
        java = Path(os.environ['JAVA_HOME']) / 'bin'
        source = ROOT / 'launcher-ui/app/src/tv/noiro/launcher/SubtitleSelection.java'
        with tempfile.TemporaryDirectory() as tmp:
            harness = Path(tmp) / 'SelectionHarness.java'
            harness.write_text(r'''package tv.noiro.launcher;
import java.util.*;
public class SelectionHarness {
  static void check(boolean value) { if (!value) throw new AssertionError(); }
  public static void main(String[] args) {
    List<String> preferred = SubtitleSelection.languages("bos,hr-HR,srp,en_US,deu,fr");
    check(preferred.equals(Arrays.asList("bs","hr","sr","en","de")));
    check(SubtitleSelection.languages("und,invalid,bos,bs,eng,en").equals(Arrays.asList("bs","en")));
    check(SubtitleSelection.rank(preferred,"hrv") == 1);
    check(SubtitleSelection.rank(preferred,"ger") == 4);
    check(SubtitleSelection.rank(preferred,null) == Integer.MAX_VALUE);
    check(SubtitleSelection.languages("").isEmpty());
    List<String> langs = Arrays.asList("eng", "hrv", "bos", "bs", "bs");
    List<Boolean> noiro = Arrays.asList(true, true, false, true, true);
    List<Boolean> ai = Arrays.asList(false, false, false, true, false);
    // Preferred language outranks source ordering; existing Noiro beats another add-on and AI.
    check(SubtitleSelection.addon(preferred,langs,noiro,ai,false) == 4);
    check(SubtitleSelection.addon(preferred,langs,noiro,ai,true) == 4);
    // AI cannot start without consent, but an opted-in preferred target outranks fallback English.
    check(SubtitleSelection.addon(preferred,Arrays.asList("en","bs"),Arrays.asList(false,true),Arrays.asList(false,true),false) == 0);
    check(SubtitleSelection.addon(preferred,Arrays.asList("en","bs"),Arrays.asList(false,true),Arrays.asList(false,true),true) == 1);
    check(SubtitleSelection.addon(preferred,Arrays.asList("ja","und"),Arrays.asList(true,false),Arrays.asList(false,false),true) == -1);
    check(SubtitleSelection.addon(Collections.emptyList(),langs,noiro,ai,true) == -1);
  }
}''')
            subprocess.run([str(java / 'javac'), '-encoding', 'UTF-8', '-d', tmp, str(source), str(harness)], check=True)
            subprocess.run([str(java / 'java'), '-cp', tmp, 'tv.noiro.launcher.SelectionHarness'], check=True)
