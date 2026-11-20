"""Execute account identity and QR trust rules without Android services."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class AccountPolicyTests(unittest.TestCase):
    def test_identity_and_qr_rules(self):
        java = Path(os.environ['JAVA_HOME']) / 'bin'
        source = ROOT / 'launcher-ui/app/src/tv/noiro/launcher'
        with tempfile.TemporaryDirectory() as tmp:
            harness = Path(tmp) / 'AccountHarness.java'
            harness.write_text('''package tv.noiro.launcher;
public class AccountHarness {
  static void check(boolean value) { if (!value) throw new AssertionError(); }
  public static void main(String[] args) {
    check(!AccountState.EMPTY.ready());
    AccountState pending = new AccountState("session", "uid", "label", "", 0);
    check(pending.signedIn() && !pending.ready());
    AccountState ready = new AccountState("session", "uid", "label", "personal-key", 1);
    check(ready.ready() && ready.owns("session", "uid"));
    check(!ready.owns("other", "uid") && !ready.owns("session", "other"));
    check(LoginPolicy.trustedLink("https://link.stremio.com/?code=test"));
    for (String url : new String[]{"http://link.stremio.com", "https://link.stremio.com.evil.test", "https://evil@link.stremio.com", "https://link.stremio.com:8443", "broken"}) check(!LoginPolicy.trustedLink(url));
    check(StreamPolicy.videoId("tt1254207", 0, 0).equals("tt1254207"));
    check(StreamPolicy.videoId("tt0944947", 2, 3).equals("tt0944947:2:3"));
    check(StreamPolicy.videoId("tt0944947", 0, 1).equals("tt0944947:0:1"));
    try { StreamPolicy.videoId("tmdb:1", 0, 0); throw new AssertionError(); } catch (IllegalArgumentException expected) {}
    check(StreamPolicy.endpoint("https://addon.example/config/manifest.json", "series", "tt0944947:2:3").equals("https://addon.example/config/stream/series/tt0944947%3A2%3A3.json"));
    check(!StreamPolicy.web("file:///secret", false));
    check(!StreamPolicy.web("https://user:password@host.test/path", true));
    check(!StreamPolicy.web("http://addon.example/manifest.json", true));
    check(StreamPolicy.web("https://addon.example/manifest.json", true));
    try { StreamPolicy.endpoint("https://addon.example/manifest.json?key=secret", "movie", "tt1254207"); throw new AssertionError(); } catch (IllegalArgumentException expected) {}
    check(PlaybackPolicy.seek(5000, -10000, 60000) == 0);
    check(PlaybackPolicy.seek(55000, 10000, 60000) == 59000);
    check(PlaybackPolicy.seek(5000, 10000, -1) == 15000);
    check(PlaybackPolicy.allowedHeader("User-Agent", "Noiro"));
    check(!PlaybackPolicy.allowedHeader("Authorization", "secret"));
    check(!PlaybackPolicy.allowedHeader("Origin", "bad\\r\\nInjected: value"));
    check(!PlaybackPolicy.message(2004).contains("http"));
    check(!LoginPolicy.expired(599999) && LoginPolicy.expired(600000));
    check(LoginPolicy.validUid("stremio-uid") && !LoginPolicy.validUid("bad uid") && !LoginPolicy.validUid(""));
  }
}''')
            subprocess.run([str(java / 'javac'), '-d', tmp, str(source / 'AccountState.java'), str(source / 'LoginPolicy.java'), str(source / 'StreamPolicy.java'), str(source / 'PlaybackPolicy.java'), str(harness)], check=True)
            subprocess.run([str(java / 'java'), '-cp', tmp, 'tv.noiro.launcher.AccountHarness'], check=True)
