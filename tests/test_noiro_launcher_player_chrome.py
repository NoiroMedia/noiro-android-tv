"""Exercise player timing and saved defaults without a device, network or media source."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "launcher-ui/app/src/tv/noiro/launcher"


class PlayerChromeTests(unittest.TestCase):
    def test_audio_preference_expiry_preserves_free_manual_selection_across_rebuilds(self):
        self.run_java("AudioLanguageHarness", r'''package tv.noiro.launcher;
public class AudioLanguageHarness {
    static void check(String expected, String actual, String why) {
        if (!expected.equals(actual)) throw new AssertionError(why + ": " + actual);
    }
    public static void main(String[] args) {
        check("", PlayerChromePolicy.audioLanguage("hr", "", false, false), "free initial playback ignores saved preference");
        check("hr", PlayerChromePolicy.audioLanguage("hr", "", false, true), "current membership enables saved preference");
        for (String transition : new String[]{"source change", "retry", "decoder rebuild", "background return"}) {
            check("", PlayerChromePolicy.audioLanguage("hr", "hr", false, false), transition + " cannot reuse expired automatic choice");
            check("en", PlayerChromePolicy.audioLanguage("hr", "en", true, false), transition + " preserves free manual choice");
            check("hr", PlayerChromePolicy.audioLanguage("hr", "en", false, true), transition + " gives the saved preference priority over automatic fallback");
            check("en", PlayerChromePolicy.audioLanguage("", "en", false, true), transition + " retains automatic choice when no default is saved");
            check("en", PlayerChromePolicy.audioLanguage("hr", "en", true, true), transition + " respects an explicit manual choice over the saved default");
        }
        check("bs", PlayerChromePolicy.audioLanguage("bs", "en", false, true), "English fallback cannot override a saved Bosnian preference on another source");
        check("bs", PlayerChromePolicy.audioLanguage("bs", "", false, true), "new episode with cleared track state uses the saved default");
        check("", PlayerChromePolicy.audioLanguage("bs", "", false, false), "new free episode with cleared track state uses automatic selection");
        check("hr", PlayerChromePolicy.audioLanguage("hr", "", false, true), "renewed membership restores preference");
        check("", PlayerChromePolicy.audioLanguage("hr", "", true, false), "missing manual language cannot unlock saved preference");
        check("hr", PlayerChromePolicy.audioLanguage("hr", "und", false, true), "unknown remembered language falls back to saved preference");
        check("", PlayerChromePolicy.audioLanguage(null, null, false, true), "missing preferences use automatic selection");
        check("", PlayerChromePolicy.audioLanguage("https://private.invalid", "https://private.invalid", true, true), "invalid values cannot reach track preference");
        System.out.println("PASS: Premium automatic language and free manual audio survive rebuilds safely");
    }
}''')

    def test_sleep_expiry_cancels_old_menu_resume_intents(self):
        self.run_java("DialogHarness", r'''package tv.noiro.launcher;
public class DialogHarness {
    static void check(boolean value, String why) { if (!value) throw new AssertionError(why); }
    public static void main(String[] args) {
        PlayerChromePolicy.DialogPlayback menus = new PlayerChromePolicy.DialogPlayback();
        long audio = menus.capture(true);
        check(menus.shouldResume(audio), "ordinary Audio dialog restores playing state");
        long pausedAudio = menus.capture(false);
        check(!menus.shouldResume(pausedAudio), "Audio opened while paused stays paused");
        long settings = menus.capture(true), positionEditor = settings;
        menus.revoke(); // Sleep timer expires while an old paused menu remains open.
        check(!menus.shouldResume(audio), "Audio dismissal must not undo Sleep pause");
        check(!menus.shouldResume(settings), "Subtitle settings must not undo Sleep pause");
        check(!menus.shouldResume(positionEditor), "position editor keeps the revoked settings intent");
        long handoffAfterExpiry = settings;
        check(!menus.shouldResume(handoffAfterExpiry), "opening position after expiry must not create a new play intent");
        long intentionalPlayThenMenu = menus.capture(true);
        check(menus.shouldResume(intentionalPlayThenMenu), "new user playback can resume normally after a menu");
        menus.revoke(); // Player released or replaced.
        check(!menus.shouldResume(intentionalPlayThenMenu), "old dialogs cannot start a replacement player");
        System.out.println("PASS: sleep pause survives Audio, subtitle settings and position handoff");
    }
}''')

    def test_sleep_and_end_time_across_pause_speed_and_invalid_positions(self):
        self.run_java("ChromeHarness", r'''package tv.noiro.launcher;
public class ChromeHarness {
    static void check(boolean value, String why) { if (!value) throw new AssertionError(why); }
    public static void main(String[] args) {
        // A paused video does not extend a five-minute sleep timer: it uses elapsed time.
        long opened = 80_000, deadline = PlayerChromePolicy.sleepDeadline(opened, 5);
        check(!PlayerChromePolicy.sleepExpired(deadline, opened + 299_999), "must not pause early");
        check(PlayerChromePolicy.sleepExpired(deadline, opened + 300_000), "exact deadline pauses");
        check(PlayerChromePolicy.sleepExpired(deadline, opened + 600_000), "late tick still pauses");
        check(!PlayerChromePolicy.sleepExpired(PlayerChromePolicy.sleepDeadline(opened, 0), Long.MAX_VALUE), "Off stays off");
        check(PlayerChromePolicy.sleepDeadline(opened, -1) == 0, "invalid minutes must be Off");
        check(PlayerChromePolicy.sleepDeadline(opened, 181) == 0, "out of bounds timer must be Off");
        check(PlayerChromePolicy.sleepDeadline(-1, 5) == 0, "invalid clock must be Off");
        long far = PlayerChromePolicy.sleepDeadline(Long.MAX_VALUE - 1, 180);
        check(far == Long.MAX_VALUE && !PlayerChromePolicy.sleepExpired(far, Long.MAX_VALUE - 1), "overflow cannot cause immediate pause");

        check(PlayerChromePolicy.remaining(-12, 60_000) == 60_000, "preroll position clamps to zero");
        check(PlayerChromePolicy.remaining(80_000, 60_000) == 0, "past end has no negative remainder");
        check(PlayerChromePolicy.remaining(12, -9223372036854775807L) == 0, "unknown duration has no end time");
        check(PlayerChromePolicy.wallRemaining(30_000, 150_000, .5f) == 240_000, "half speed ends four minutes later");
        check(PlayerChromePolicy.wallRemaining(30_000, 150_000, 2f) == 60_000, "double speed ends one minute later");
        check(PlayerChromePolicy.wallRemaining(30_000, 150_000, .25f) == 480_000, "preserve native quarter speed");
        for (float corrupt : new float[]{0f, -1f, .1f, 99f, Float.NaN, Float.POSITIVE_INFINITY})
            check(PlayerChromePolicy.wallRemaining(30_000, 150_000, corrupt) == 120_000, "invalid speed falls back to normal");
        check("1:01:01".equals(PlayerChromePolicy.time(3_661_000)), "long movie clock");
        check("0:00".equals(PlayerChromePolicy.time(-1)), "no negative display time");
        check(PlayerChromePolicy.aspect(-1) == 0 && PlayerChromePolicy.aspect(99) == 0, "corrupt aspect falls back to fit");
        check("audio/eac3".equals(PlayerChromePolicy.mime("audio/eac3")) && "hr-HR".equals(PlayerChromePolicy.language("hr-HR")), "valid technical fields remain visible");
        check("Unknown format".equals(PlayerChromePolicy.mime("https://example.invalid/private?token=secret")) && "".equals(PlayerChromePolicy.language("https://example.invalid/private")), "untrusted Format text cannot expose a URL in Info");
        System.out.println("PASS: sleep deadlines, paused time, speed-aware end time and corrupt values");
    }
}''')

    def test_existing_users_keep_classic_and_choices_survive_new_context(self):
        self.run_java("PreferencesHarness", r'''package tv.noiro.launcher;
import android.content.Context;
public class PreferencesHarness {
    static void check(boolean value, String why) { if (!value) throw new AssertionError(why); }
    public static void main(String[] args) {
        Context existing = new Context();
        check(!PlayerUiPreferences.noiro(existing), "existing users must retain Classic");
        check(PlayerUiPreferences.speed(existing) == 1f && PlayerUiPreferences.aspect(existing) == 0, "original playback defaults");
        PlayerUiPreferences.noiro(existing, true); PlayerUiPreferences.speed(existing, .25f); PlayerUiPreferences.aspect(existing, 1);
        Context reopened = new Context();
        check(PlayerUiPreferences.noiro(reopened), "Noiro choice must survive reopening");
        check(PlayerUiPreferences.speed(reopened) == .25f && PlayerUiPreferences.aspect(reopened) == 1, "speed and crop survive reopening");
        PlayerUiPreferences.noiro(reopened, false);
        check(!PlayerUiPreferences.noiro(existing), "Classic fallback must be reversible");
        reopened.getSharedPreferences("settings", 0).edit().putString("player_speed", "NaN").putString("player_aspect", "broken").putString("player_appearance", "unknown").apply();
        check(PlayerUiPreferences.speed(reopened) == 1f && PlayerUiPreferences.aspect(reopened) == 0 && !PlayerUiPreferences.noiro(reopened), "corrupt saved choices recover safely");
        System.out.println("PASS: Classic upgrade default, durable choices and reversible appearance");
    }
}''', preferences=True)

    def run_java(self, name, source, preferences=False):
        java = Path(os.environ["JAVA_HOME"]) / "bin"
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            harness = root / (name + ".java")
            harness.write_text(source)
            sources = [SOURCE / "PlayerChromePolicy.java", harness]
            if preferences:
                android = root / "android/content"
                android.mkdir(parents=True)
                (android / "SharedPreferences.java").write_text('''package android.content;
public interface SharedPreferences {
    String getString(String key, String fallback);
    Editor edit();
    interface Editor { Editor putString(String key, String value); void apply(); }
}''')
                (android / "Context.java").write_text('''package android.content;
import java.util.HashMap;
public class Context {
    public static final int MODE_PRIVATE = 0;
    private static final HashMap<String, String> values = new HashMap<>();
    public SharedPreferences getSharedPreferences(String name, int mode) {
        if (!"settings".equals(name)) throw new AssertionError("unexpected preference file");
        return new SharedPreferences() {
            public String getString(String key, String fallback) { return values.getOrDefault(key, fallback); }
            public Editor edit() { return new Editor() {
                public Editor putString(String key, String value) { values.put(key, value); return this; }
                public void apply() { }
            }; }
        };
    }
}''')
                sources += [SOURCE / "PlayerUiPreferences.java", android / "SharedPreferences.java", android / "Context.java"]
            subprocess.run([str(java / "javac"), "-d", tmp, *map(str, sources)], check=True)
            subprocess.run([str(java / "java"), "-cp", tmp, "tv.noiro.launcher." + name], check=True)
