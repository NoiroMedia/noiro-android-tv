package tv.noiro.launcher;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Transactional Home handover; deliberately independent of Android for failure-path tests. */
final class HomeSwitch {
    static final String PACKAGE = "tv.noiro.launcher";
    static final String COMPONENT = PACKAGE + "/.HomeActivity";
    static final String QUERY = "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME";
    private static final Set<String> STOCK = new HashSet<>(Arrays.asList(
            "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx",
            "com.google.android.leanbacklauncher", "com.mitv.tvhome"));

    interface Shell { String run(String command) throws Exception; }
    interface Store {
        Record read();
        void write(Record record) throws Exception;
        void clear() throws Exception;
    }
    static final class Record {
        final String original, disabled;
        Record(String original, String disabled) {
            this.original = original;
            this.disabled = disabled;
        }
    }
    private final Shell shell;
    private final Store store;
    private final boolean launcherEnabled;
    HomeSwitch(Shell shell, Store store) { this(shell, store, true); }
    HomeSwitch(Shell shell, Store store, boolean launcherEnabled) {
        this.shell = shell; this.store = store; this.launcherEnabled = launcherEnabled;
    }

    static String component(String output) {
        for (String line : output.split("\\r?\\n")) {
            line = line.trim();
            if (line.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+/[A-Za-z0-9_.]+")) return line;
        }
        return "";
    }
    static String packageOf(String component) {
        return component.contains("/") ? component.substring(0, component.indexOf('/')) : "";
    }
    static String expanded(String component) {
        return component.replace("/.", "/" + packageOf(component) + ".");
    }
    static boolean isNoiro(String component) {
        return COMPONENT.equals(component) || (PACKAGE + "/tv.noiro.launcher.HomeActivity").equals(component);
    }
    static boolean validOriginal(String home) {
        String pkg = packageOf(home);
        return component(home).equals(home) && !home.isEmpty() && !isNoiro(home)
                && !pkg.equals("android") && !pkg.equals("com.android.settings")
                && !pkg.equals("com.android.tv.settings") && !pkg.equals("com.android.provision")
                && !pkg.equals("com.google.android.tungsten.setupwraith")
                && !home.contains("ResolverActivity") && !home.contains("FallbackHome")
                && !home.toLowerCase(java.util.Locale.ROOT).matches(".*(factory|setupwizard).*");
    }
    private void requireShell() throws Exception {
        if (!"2000".equals(shell.run("id -u").trim())) throw new IOException("A TV debugging connection is required.");
    }
    String current() throws Exception { return component(shell.run(QUERY)); }
    private void select(String home) throws Exception {
        if (!component(home).equals(home)) throw new IOException("Invalid Home component.");
        shell.run("cmd package set-home-activity --user 0 " + home);
    }
    void activate(boolean allowDisable) throws Exception {
        if (!launcherEnabled) throw new IOException("Home switching is unavailable in the standalone TV app.");
        requireShell();
        String original = current();
        if (isNoiro(original)) return;
        if (!validOriginal(original))
            throw new IOException("Choose an original Home in system settings first.");
        Record old = store.read();
        if (old != null && (!expanded(old.original).equals(expanded(original)) || !old.disabled.isEmpty()))
            throw new IOException("Restore the saved Home before starting a new handover.");
        store.write(new Record(original, ""));
        try {
            select(COMPONENT);
            if (isNoiro(current())) return;
            String pkg = packageOf(original);
            if (!allowDisable || !STOCK.contains(pkg))
                throw new IOException("This TV did not accept the Home change. Use system Home settings.");
            String wifi = shell.run("cmd package query-activities --brief -a android.settings.WIFI_SETTINGS");
            String network = shell.run("cmd package query-activities --brief -a android.settings.WIRELESS_SETTINGS");
            String info = shell.run("dumpsys package " + pkg);
            if (wifi.contains(pkg + "/") || network.contains(pkg + "/") || info.contains("android.permission.TV_INPUT_HARDWARE"))
                throw new IOException("The original Home also provides network or TV input services. It will stay enabled.");
            // Persist before disabling so interruption always leaves recovery information.
            store.write(new Record(original, pkg));
            shell.run("pm disable-user --user 0 " + pkg);
            select(COMPONENT);
            if (!isNoiro(current())) throw new IOException("Noiro did not become the default Home.");
        } catch (Exception failure) {
            try { restore(); }
            catch (Exception rollback) {
                throw new IOException("Home change failed. Reconnect and use Restore original Home; recovery information is saved.");
            }
            throw failure;
        }
    }
    void restore() throws Exception {
        if (!launcherEnabled) throw new IOException("Home switching is unavailable in the standalone TV app.");
        requireShell();
        Record saved = store.read();
        if (saved == null) throw new IOException("No previous Home is saved.");
        if (!validOriginal(saved.original))
            throw new IOException("The saved Home is invalid. Use system Home settings.");
        if (!saved.disabled.isEmpty()) {
            if (!STOCK.contains(saved.disabled) || !packageOf(saved.original).equals(saved.disabled))
                throw new IOException("The saved restore record is invalid.");
            shell.run("pm enable --user 0 " + saved.disabled);
        }
        select(saved.original);
        String actual = current();
        if (!actual.equals(saved.original)) {
            String expanded = saved.original.replace("/.", "/" + packageOf(saved.original) + ".");
            if (!actual.equals(expanded)) throw new IOException("Restore could not be verified. Recovery information is retained.");
        }
        store.clear();
    }
}
