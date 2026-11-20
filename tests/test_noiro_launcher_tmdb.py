"""Credential formatting checks shared by both TMDB clients."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class TmdbCredentialTests(unittest.TestCase):
    def test_keys_tokens_and_malformed_input(self):
        java = Path(os.environ["JAVA_HOME"]) / "bin"
        source = ROOT / "launcher-ui/app/src/tv/noiro/launcher/TmdbCredential.java"
        with tempfile.TemporaryDirectory() as tmp:
            harness = Path(tmp) / "CredentialHarness.java"
            harness.write_text(r'''package tv.noiro.launcher;
public class CredentialHarness {
    static void check(boolean pass) { if (!pass) throw new AssertionError(); }
    public static void main(String[] args) {
        String key = "0123456789abcdef0123456789abcdef";
        check(TmdbCredential.normalize(null).isEmpty());
        check(!TmdbCredential.validInput(" "));
        check(!TmdbCredential.validInput("a b"));
        check(!TmdbCredential.validInput("a\nb"));
        check(TmdbCredential.validInput(key));
        check(!TmdbCredential.bearer(key));
        check(key.equals(TmdbCredential.normalize("  " + key + "  ")));
        check("eyJheader.payload.signature".equals(TmdbCredential.normalize(" bearer eyJheader.payload.signature ")));
        check(TmdbCredential.validInput("Bearer eyJheader.payload.signature"));
        check(TmdbCredential.bearer(TmdbCredential.normalize("Bearer eyJheader.payload.signature")));
        System.out.println("PASS: keys, bearer tokens, trimming and malformed input");
    }
}''')
            subprocess.run([str(java / "javac"), "-d", tmp, str(source), str(harness)], check=True)
            subprocess.run([str(java / "java"), "-cp", tmp, "tv.noiro.launcher.CredentialHarness"], check=True)
