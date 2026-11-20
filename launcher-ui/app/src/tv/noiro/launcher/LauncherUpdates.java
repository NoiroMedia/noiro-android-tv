package tv.noiro.launcher;

import android.app.Activity;
import android.app.AppOpsManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.lang.ref.WeakReference;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/** Private GitHub releases are proxied by Noiro; repository credentials never reach the TV. */
final class LauncherUpdates {
    static final String ENDPOINT = "https://noiro.tv/api/stremio-for-kodi/v1/launcher-update";
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean busy;
    static volatile String status = "Check for a new version.";
    private static volatile long readyBuild;
    interface Listener { void changed(); }
    private static WeakReference<Listener> listener = new WeakReference<>(null);
    static void listen(Listener next) { listener = new WeakReference<>(next); }
    static boolean available(Context c) { return !AppIdentity.standalone(c); }
    static boolean enabled(Context c) { return available(c) && c.getSharedPreferences("updates",0).getBoolean("automatic",true); }
    static void enabled(Context c, boolean on) { if (available(c)) c.getSharedPreferences("updates",0).edit().putBoolean("automatic",on).apply(); }
    static File apk(Context c) { return new File(c.getCacheDir(),"launcher-update.apk"); }
    static boolean ready(Context c) { return available(c) && c.getSharedPreferences("updates",0).getLong("readyBuild",0) > 0 && apk(c).isFile(); }
    static boolean busy() { return busy; }
    private static void status(String text) {
        status = text;
        MAIN.post(() -> { Listener l = listener.get(); if (l != null) l.changed(); });
    }
    static void automatic(Activity activity) {
        Context c = activity.getApplicationContext();
        if (!enabled(c) || System.currentTimeMillis() - c.getSharedPreferences("updates",0).getLong("checked",0) < 6 * 3600_000L) return;
        WeakReference<Activity> owner = new WeakReference<>(activity);
        check(c, () -> {
            Activity a = owner.get();
            if (a == null || a.isFinishing() || a.isDestroyed() || !a.hasWindowFocus() || !ready(c)) return;
            new AlertDialog.Builder(a).setTitle("Launcher update ready")
                .setMessage("A new Noiro release is downloaded and verified.")
                .setPositiveButton("Updates", (d,w) -> a.startActivity(new Intent(a,UpdatesActivity.class)))
                .setNegativeButton("Later",null).show();
        });
    }
    static synchronized void check(Context context, Runnable finished) {
        // The launcher feed contains a different package. Never request or download it for the TV app.
        if (!available(context)) {
            status("Noiro's separate update channel is not published yet.");
            if (finished != null) MAIN.post(finished);
            return;
        }
        if (busy) return;
        busy = true;
        Context c = context.getApplicationContext();
        status("Checking for updates…");
        WORK.execute(() -> {
            File part = new File(c.getCacheDir(),"launcher-update.part");
            try {
                String token = NoiroAccount.updateToken();
                JSONObject release = AccountHttp.call(ENDPOINT,null,token);
                long build = release.getLong("build"), size = release.getLong("size");
                String version = release.getString("version"), sha = release.getString("sha256");
                if (build <= 0 || size <= 0 || size > 100L*1024*1024 || !sha.matches("[a-f0-9]{64}") || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) throw new IOException();
                long installed = c.getPackageManager().getPackageInfo(c.getPackageName(),0).getLongVersionCode();
                if (build <= installed) {
                    readyBuild = 0; c.getSharedPreferences("updates",0).edit().remove("readyBuild").apply(); apk(c).delete();
                    status("You're up to date (" + NoiroAccount.APP_VERSION + ").");
                } else {
                    if (!apk(c).isFile() || !sha.equals(hash(apk(c)))) {
                        readyBuild = 0; c.getSharedPreferences("updates",0).edit().remove("readyBuild").apply(); apk(c).delete();
                        status("Downloading " + version + "…");
                        download(token,part,size);
                        if (!sha.equals(hash(part))) throw new IOException();
                        verify(c,part,build,version);
                        if (!part.renameTo(apk(c))) throw new IOException();
                    } else verify(c,apk(c),build,version);
                    readyBuild = build;
                    c.getSharedPreferences("updates",0).edit().putLong("readyBuild",build).apply();
                    status("Version " + version + " is ready to install.");
                }
                c.getSharedPreferences("updates",0).edit().putLong("checked",System.currentTimeMillis()).apply();
            } catch (Exception failure) {
                readyBuild = 0; c.getSharedPreferences("updates",0).edit().remove("readyBuild").apply(); apk(c).delete();
                status("Unable to check or download an update. Check your connection and Noiro sign-in, then retry.");
                // Back off automatic checks after failures without blocking manual retry.
                c.getSharedPreferences("updates",0).edit().putLong("checked",System.currentTimeMillis()-5*3600_000L).apply();
            } finally {
                part.delete(); busy = false;
                MAIN.post(() -> { Listener l = listener.get(); if(l != null) l.changed(); if(finished != null) finished.run(); });
            }
        });
    }
    private static void download(String token, File file, long expected) throws Exception {
        HttpURLConnection connection = (HttpURLConnection)new URL(ENDPOINT + "/download").openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(10000); connection.setReadTimeout(30000);
        connection.setRequestProperty("Authorization","Bearer " + token);
        try {
            if (connection.getResponseCode() != 200) throw new IOException();
            try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(file)) {
                byte[] buffer = new byte[32768]; int count; long total = 0, reported = 0;
                while ((count = in.read(buffer)) != -1) {
                    total += count;
                    if (total > expected) throw new IOException();
                    out.write(buffer,0,count);
                    if (total-reported > 1024*1024) { status("Downloading update… " + total*100/expected + "%"); reported=total; }
                }
                if (total != expected) throw new IOException();
                out.getFD().sync();
            }
        } finally { connection.disconnect(); }
    }
    private static String hash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try(InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[32768]; int count;
            while((count=in.read(buffer))!=-1) digest.update(buffer,0,count);
        }
        StringBuilder hex=new StringBuilder(); for(byte b:digest.digest()) hex.append(String.format(java.util.Locale.ROOT,"%02x",b & 255));
        return hex.toString();
    }
    private static Set<String> signers(PackageInfo info) {
        Set<String> result = new HashSet<>();
        if(info != null && info.signingInfo != null) for(Signature s:info.signingInfo.getApkContentsSigners()) result.add(s.toCharsString());
        return result;
    }
    private static void verify(Context c, File file, long build, String version) throws Exception {
        PackageManager pm=c.getPackageManager();
        PackageInfo update=pm.getPackageArchiveInfo(file.getAbsolutePath(),PackageManager.GET_SIGNING_CERTIFICATES);
        PackageInfo current=pm.getPackageInfo(c.getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES);
        if(update==null || !c.getPackageName().equals(update.packageName) || update.getLongVersionCode()!=build ||
            build<=current.getLongVersionCode() || !version.equals(update.versionName) || signers(current).isEmpty() || !signers(current).equals(signers(update))) throw new IOException();
    }
    static boolean installPermission(Context c) {
        if (c.getPackageManager().canRequestPackageInstalls()) return true;
        // Some TV firmware reports a stale PackageManager result after its source switch is enabled.
        AppOpsManager ops = (AppOpsManager)c.getSystemService(Context.APP_OPS_SERVICE);
        if (ops == null) return false;
        try { return ops.checkOpNoThrow("android:request_install_packages",
                android.os.Process.myUid(), c.getPackageName()) == AppOpsManager.MODE_ALLOWED; }
        catch (RuntimeException ignored) { return false; }
    }
    static void install(Activity a) {
        if (!ready(a)) return;
        if (!installPermission(a)) {
            status("Allow installation from Noiro, then return here to continue.");
            try { a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+a.getPackageName()))); }
            catch (android.content.ActivityNotFoundException failure) { status("Open the TV security settings and allow installation from Noiro."); }
            return;
        }
        Intent intent=new Intent(Intent.ACTION_INSTALL_PACKAGE);
        intent.setDataAndType(Uri.parse("content://"+a.getPackageName()+".updates/launcher-update.apk"),"application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { a.startActivity(intent); }
        catch (android.content.ActivityNotFoundException failure) { status("The TV's package installer is unavailable."); }
    }
}
