"""Run the built Prefs/Ui language logic with minimal Android stand-ins, without a TV.

These checks cover locale/preference behavior, not Android rendering or remote navigation.
"""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class NoiroLanguageTests(unittest.TestCase):
    def test_default_saved_language_and_automatic(self):
        sdk = Path(os.environ["ANDROID_HOME"])
        android = sorted((sdk / "platforms").glob("android-*/android.jar"))[-1]
        java = Path(os.environ["JAVA_HOME"]) / "bin"
        classes = ROOT / "launcher-ui/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes"
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            files = {
                "android/content/Context.java": r'''package android.content;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
public class Context {
    public static final int MODE_PRIVATE = 0;
    public String packageName = "tv.noiro.launcher";
    public String getPackageName() { return packageName; }
    public java.io.File getFilesDir() { return new java.io.File(System.getProperty("java.io.tmpdir")); }
    public final Map<String,Object> values = new HashMap<>();
    private final SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(
        getClass().getClassLoader(), new Class[]{SharedPreferences.Editor.class}, (p,m,a) -> {
            if (m.getName().startsWith("put")) { values.put((String)a[0], a[1]); return p; }
            if (m.getName().equals("apply")) return null;
            if (m.getName().equals("commit")) return true;
            throw new UnsupportedOperationException(m.getName());
        });
    private final SharedPreferences prefs = (SharedPreferences) Proxy.newProxyInstance(
        getClass().getClassLoader(), new Class[]{SharedPreferences.class}, (p,m,a) -> {
            if (m.getName().equals("contains")) return values.containsKey(a[0]);
            if (m.getName().equals("getString") || m.getName().equals("getBoolean")) return values.getOrDefault(a[0], a[1]);
            if (m.getName().equals("edit")) return editor;
            throw new UnsupportedOperationException(m.getName());
        });
    public Context getApplicationContext() { return this; }
    public SharedPreferences getSharedPreferences(String name, int mode) { return prefs; }
}''',
                "android/os/Build.java": "package android.os; public class Build { public static class VERSION { public static int SDK_INT = 30; } }",
                "android/os/SystemClock.java": "package android.os; public class SystemClock { public static long now = 1000; public static long elapsedRealtime() { return now; } }",
                "android/graphics/Rect.java": "package android.graphics; public class Rect { public Rect() {} }",
                "android/graphics/RectF.java": "package android.graphics; public class RectF { public RectF() {} }",
                "tv/noiro/launcher/LanguageHarness.java": r'''package tv.noiro.launcher;
import java.util.Locale;
import android.content.Context;
public class LanguageHarness {
    private static void accountField(String name, Object value) {
        try { java.lang.reflect.Field field = NoiroAccount.class.getDeclaredField(name); field.setAccessible(true); field.set(null, value); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private static void check(boolean pass, String message) {
        if (!pass) throw new AssertionError(message);
    }
    private static void audioEntitlements(Context context, AccountState connected) {
        context.packageName = "tv.noiro.androidtv";
        context.values.put("player_audio_language", "hr");
        check(Prefs.defaultAudioLanguageAvailable() && "hr".equals(Prefs.defaultAudioLanguage()), "A current lease applies an existing audio language");
        check(Prefs.setDefaultAudioLanguage("  bos  ") && "bs".equals(Prefs.defaultAudioLanguage()), "Entitled save normalizes the selected language");
        int revision = Prefs.version;
        android.os.SystemClock.now = 301000;
        check(!Prefs.defaultAudioLanguageAvailable() && Prefs.defaultAudioLanguage().isEmpty(), "Pending picker loses Premium access at the exact lease deadline");
        check(!Prefs.setDefaultAudioLanguage("fr") && !Prefs.setDefaultAudioLanguage(""), "Expired picker cannot replace or clear a saved language");
        check("bs".equals(context.values.get("player_audio_language")) && Prefs.version == revision, "Rejected stale saves preserve the saved preference and version");
        accountField("lease", SupporterLease.grant(true, 300, android.os.SystemClock.now));
        check(Prefs.defaultAudioLanguageAvailable() && "bs".equals(Prefs.defaultAudioLanguage()), "Renewal restores the same saved language");
        accountField("leaseOwner", new AccountState("other-auth", connected.uid, "Other session", connected.tmdbKey, 1));
        check(!Prefs.defaultAudioLanguageAvailable() && !Prefs.setDefaultAudioLanguage("de") && Prefs.defaultAudioLanguage().isEmpty(), "A different authentication session cannot reuse the current owner's lease");
        accountField("leaseOwner", new AccountState(connected.authKey, "other-uid", "Other account", connected.tmdbKey, 1));
        check(!Prefs.defaultAudioLanguageAvailable() && !Prefs.setDefaultAudioLanguage("de"), "A different account UID cannot reuse the current owner's lease");
        check("bs".equals(context.values.get("player_audio_language")) && Prefs.version == revision, "Owner mismatches never overwrite the original preference");
        accountField("leaseOwner", connected);
        check("bs".equals(Prefs.defaultAudioLanguage()), "Restoring the rightful owner enables the preserved preference");
        context.packageName = "tv.noiro.launcher";
        check(Prefs.defaultAudioLanguageAvailable(), "Legacy identity uses the same strict membership boundary");
        android.os.SystemClock.now = 1000;
        accountField("lease", SupporterLease.grant(true, 300, android.os.SystemClock.now));
    }
    public static void main(String[] args) {
        Locale.setDefault(new Locale("tr", "TR"));
        Context fresh = new Context();
        Prefs.init(fresh);
        check("en".equals(Ui.language), "Fresh installation on Turkish TV must use English");
        check("Settings".equals(Ui.tr("Ajustes", "Settings")), "English text selected");
        Prefs.setLanguage("tr");
        check("tr".equals(Ui.language), "Turkish remains selectable");
        check("Ayarlar".equals(Ui.tr("Ajustes", "Settings")), "Turkish translation retained");
        Prefs.init(fresh);
        check("tr".equals(Ui.language), "Saved Turkish selection survives initialization");
        for (String language : new String[]{"pt", "es", "de", "ru", "en"}) {
            Prefs.setLanguage(language);
            Prefs.init(fresh);
            check(language.equals(Ui.language), "Saved selection survives: " + language);
        }
        check(Prefs.tmdbKey().isEmpty() && !Tmdb.hasKey(), "No configured key on fresh installation");
        Prefs.setTmdbKey("  eyJexample.read.token  ");
        check(Prefs.tmdbKey().isEmpty() && !Tmdb.hasKey(), "Legacy local token cannot bypass Supporter verification");
        Prefs.init(fresh);
        check(!Tmdb.hasKey() && "eyJexample.read.token".equals(fresh.values.get("tmdb_api_key")), "Legacy value is preserved but never authorizes access");
        AccountState connected = new AccountState("test-auth", "test-uid", "Test", "a".repeat(32), 1);
        accountField("state", connected);
        check(!NoiroAccount.ready(), "Encrypted account snapshot alone does not authorize a new process");
        accountField("leaseOwner", connected);
        accountField("lease", SupporterLease.grant(true, 300, android.os.SystemClock.now));
        check(NoiroAccount.ready() && Prefs.tmdbKey().equals(connected.tmdbKey) && Tmdb.hasKey(), "A current account-bound lease enables its cloud key");
        audioEntitlements(fresh, connected);
        accountField("leaseOwner", new AccountState("other-auth", "test-uid", "Test", "a".repeat(32), 1));
        check(!NoiroAccount.ready() && Prefs.tmdbKey().isEmpty(), "Another authentication session cannot reuse a lease");
        accountField("leaseOwner", connected);
        android.os.SystemClock.now = 301000;
        check(!NoiroAccount.ready() && Prefs.tmdbKey().isEmpty() && !Tmdb.hasKey(), "Elapsed-time expiry disables cached cloud credentials");
        Prefs.setTmdbKey("");
        check(!Tmdb.hasKey(), "Removing the key disables the client");
        fresh.packageName = "tv.noiro.androidtv";
        Prefs.setTmdbKey("  Bearer eyJexample.personal.key  ");
        check(Prefs.tmdbKey().equals("eyJexample.personal.key") && Tmdb.hasKey(), "Standalone app accepts its user's optional local key without cloud membership");
        check(!NoiroAccount.ready(), "A local metadata key never grants hosted Noiro access");
        Prefs.setTmdbKey("");
        check(!Tmdb.hasKey(), "Removing the local key removes its independent metadata access");
        fresh.packageName = "tv.noiro.launcher";
        Prefs.setLanguage("auto");
        check("tr".equals(Ui.language), "Automatic still follows Turkish TV locale");
        Locale.setDefault(Locale.JAPAN);
        Ui.applyLanguage();
        check("en".equals(Ui.language), "Unsupported automatic locale falls back to English");
        System.out.println("PASS: English first run, all saved choices, Turkish translation and automatic fallback");
    }
}''',
            }
            sources = []
            for name, text in files.items():
                f = base / name
                f.parent.mkdir(parents=True, exist_ok=True)
                f.write_text(text)
                sources.append(str(f))
            cp = os.pathsep.join([str(base), str(classes), str(android)])
            # Compile the current access boundary rather than trusting a previously built APK's classes.
            source = ROOT / "launcher-ui/app/src/tv/noiro/launcher"
            sources += [str(source / name) for name in ("Prefs.java", "NoiroAccount.java", "SupporterLease.java", "SupporterDiscovery.java", "AccountState.java")]
            subprocess.run([str(java / "javac"), "-encoding", "UTF-8", "-cp", cp, "-d", str(base), *sources], check=True)
            subprocess.run([str(java / "java"), "-cp", cp, "tv.noiro.launcher.LanguageHarness"], check=True)
