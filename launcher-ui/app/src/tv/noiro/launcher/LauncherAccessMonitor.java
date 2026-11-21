package tv.noiro.launcher;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/** Protect every foreground media screen, including a long-running player session. */
final class LauncherAccessMonitor implements Application.ActivityLifecycleCallbacks {
    private final Handler main = new Handler(Looper.getMainLooper());
    private Activity foreground;
    private Activity observedHome;
    private int observedVersion = -1;
    private boolean observedCloud;
    private final Runnable check = this::tick;
    private void tick() {
        Activity activity = foreground;
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        boolean playbackNeedsSession = !AppIdentity.standalone(activity)
                || activity instanceof StreamsActivity || activity instanceof NoiroPlayerActivity;
        if (playbackNeedsSession && !AppAccess.ready(activity)) {
            foreground = null;
            activity.startActivity(new Intent(activity, OnboardingActivity.class).putExtra("resume_home", true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
            activity.finish(); return;
        }
        if (AppIdentity.standalone(activity)) {
            boolean cloud = NoiroAccount.ready();
            if (activity instanceof HomeActivity
                    && (observedHome != activity || observedVersion != Prefs.version || observedCloud != cloud)) {
                observedHome = activity; observedVersion = Prefs.version; observedCloud = cloud;
                ((HomeActivity) activity).membershipChecked();
            }
            SupporterChecks.start(false, () -> {
                if (foreground == activity && !activity.isFinishing() && !activity.isDestroyed()) {
                    if (activity instanceof HomeActivity) ((HomeActivity) activity).membershipChecked();
                }
            });
        } else if (NoiroAccount.beginAccessRefresh()) new Thread(() -> {
            try { NoiroAccount.refreshAccess(); } catch (Exception unavailable) { /* Existing verified lease alone defines the short outage allowance. */ }
            finally { NoiroAccount.endAccessRefresh(); }
            main.post(() -> { main.removeCallbacks(check); tick(); });
        }, "noiro-supporter-access").start();
        main.removeCallbacks(check); main.postDelayed(check, 5000);
    }
    @Override public void onActivityResumed(Activity activity) {
        main.removeCallbacks(check);
        // Recovery and offline legal information must remain available without membership.
        if (activity instanceof OnboardingActivity || activity instanceof HomeSetupActivity
                || activity instanceof SettingsActivity || activity instanceof LicensesActivity) { foreground = null; return; }
        foreground = activity; tick();
    }
    @Override public void onActivityPaused(Activity activity) {
        if (foreground == activity) { foreground = null; main.removeCallbacks(check); }
        if (observedHome == activity) observedHome = null;
    }
    @Override public void onActivityCreated(Activity a, Bundle b) {}
    @Override public void onActivityStarted(Activity a) {}
    @Override public void onActivityStopped(Activity a) {}
    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
    @Override public void onActivityDestroyed(Activity a) {}
}
