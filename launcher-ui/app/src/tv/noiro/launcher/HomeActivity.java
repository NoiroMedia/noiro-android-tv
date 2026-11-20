package tv.noiro.launcher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;

/** Catalog entry screen, shared by the original launcher and the standalone TV app. */
public class HomeActivity extends Activity {
    private KodiHomeView catalogHome;
    private AppsDialog apps;
    private int prefsVersion = Prefs.version;
    private String homeLanguage;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        homeLanguage = Ui.language;
        if (requireSignIn()) return;
        catalogHome = new KodiHomeView(this, this::showApps);
        setContentView(catalogHome);
        getWindow().setBackgroundDrawable(null);
    }

    @Override protected void onResume() {
        super.onResume();
        if (isFinishing() || requireSignIn()) return;
        TvConfig.apply(this);
        if (prefsVersion != Prefs.version) {
            prefsVersion = Prefs.version;
            if (!AppIdentity.standalone(this)) HomeData.get(this).settingsChanged();
            Ui.applyLanguage();
            if (!homeLanguage.equals(Ui.language)) {
                recreate();
                return;
            }
        }
        if (catalogHome != null) catalogHome.resume();
        openFrom(getIntent());
        LauncherUpdates.automatic(this);
        if (NoiroAccount.configured() && NoiroAccount.beginRefresh()) {
            new Thread(() -> {
                try { NoiroAccount.sync(); } catch (Exception unavailable) { /* Keep a previously verified offline session on transient failures. */ }
                finally { NoiroAccount.endRefresh(); }
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && !requireSignIn() && catalogHome != null)
                        catalogHome.resume();
                });
            }, "noiro-account-refresh").start();
        }
    }

    private boolean requireSignIn() {
        if (AppAccess.canBrowse(this)) return false;
        Intent onboarding = new Intent(this, OnboardingActivity.class)
                .putExtra("resume_home", NoiroAccount.current().signedIn())
                .putExtra("open", getIntent().getStringExtra("open"))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(onboarding);
        finish();
        return true;
    }

    @Override protected void onPause() {
        if (catalogHome != null) catalogHome.pause();
        super.onPause();
    }

    private void showApps() {
        if (AppIdentity.standalone(this)) return;
        if (apps != null && apps.isShowing()) return;
        apps = new AppsDialog(this);
        apps.show();
    }

    /** Reopening the app returns to the catalog entry screen. */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (requireSignIn()) return;
        if (apps != null) apps.dismiss();
        if (catalogHome != null && !"watchlist".equals(intent.getStringExtra("open"))) catalogHome.returnHome();
    }

    /** `--es open explore|watchlist`: Explore from the computer (adb), as the remote's buttons open it. */
    private void openFrom(Intent intent) {
        String what = intent == null ? null : intent.getStringExtra("open");
        if (what == null) return;
        intent.removeExtra("open");
        if (what.equals("explore")) RemoteKeys.run(this, "tmdb:explore");
        else if (what.equals("watchlist") && catalogHome != null) catalogHome.openMyList();
    }

    @Override protected void onDestroy() {
        if (apps != null) apps.dismiss();
        if (catalogHome != null) catalogHome.destroy();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (catalogHome != null && catalogHome.back()) return;
        if (AppIdentity.standalone(this)) super.onBackPressed();
        // The original launcher remains at its root because it owns Android Home.
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (!AppIdentity.standalone(this) && RemoteKeys.onKey(this, event)) return true;
        return super.dispatchKeyEvent(event);
    }

}
