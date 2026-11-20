package tv.noiro.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Free Stremio approval; a Noiro cloud connection is a separate, optional step. */
public class OnboardingActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile int generation;
    private volatile boolean active;
    private Thread worker;
    private LinearLayout content;
    private float k;
    private boolean manage;
    private boolean cloudInfo, cloudLogin;
    private String cloudFailure = "";
    private int cloudLink;

    private String text(int id) { return Ui.string(this, id); }
    private String loginTitle() { return AppIdentity.standalone(this) ? "Connect Stremio" : text(R.string.login_title); }
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Ui.init(this);
        k = TvUi.k(this);
        manage = getIntent().getBooleanExtra("manage", false);
        cloudInfo = AppIdentity.standalone(this) && getIntent().getBooleanExtra("cloud_info", false);
        if (saved != null) {
            cloudInfo = saved.getBoolean("cloud_info", false);
            cloudLogin = saved.getBoolean("cloud_login", false);
            cloudLink = Math.max(0, Math.min(2, saved.getInt("cloud_link", 0)));
            cloudFailure = saved.getString("cloud_failure", "");
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(TvUi.BG);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Math.round(150 * k), Math.round(55 * k), Math.round(150 * k), Math.round(55 * k));
        scroll.addView(content);
        setContentView(scroll);
    }
    @Override protected void onResume() {
        super.onResume(); active = true; begin();
    }
    @Override protected void onPause() {
        active = false; generation++;
        if (worker != null) worker.interrupt();
        super.onPause();
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        manage = intent.getBooleanExtra("manage", false);
        cloudInfo = AppIdentity.standalone(this) && intent.getBooleanExtra("cloud_info", false);
        cloudLogin = false;
        cloudFailure = "";
        if (active) begin();
    }
    private boolean current(int run) { return active && run == generation && !isFinishing() && !isDestroyed(); }
    private void begin() {
        generation++;
        if (AppIdentity.standalone(this) && (cloudInfo || cloudLogin && NoiroAccount.current().signedIn())) {
            cloudLogin = false;
            showCloudInfo();
            return;
        }
        if (AppIdentity.standalone(this) && NoiroAccount.current().signedIn()) {
            if (!manage && StreamsActivity.afterLogin(this, getIntent()) != null) openHome();
            else showConnected();
        } else if (NoiroAccount.ready()) showConnected();
        else if (NoiroAccount.current().signedIn()) sync();
        else login();
    }
    private TextView line(String value, int size, int color) {
        TextView view = TvUi.text(this, k, size, size >= 40 ? Ui.displaySemibold : Ui.regular, color);
        view.setText(value);
        view.setPadding(0, Math.round(10 * k), 0, Math.round(10 * k));
        content.addView(view); return view;
    }
    private TextView button(String label, android.view.View.OnClickListener action) {
        TextView button = TvUi.button(this, k, label, action);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(12 * k); content.addView(button, lp); return button;
    }
    private void help() {
        if (AppIdentity.standalone(this)) button("Browse without signing in", v -> openBrowse());
        if (AppIdentity.standalone(this)) button("About Noiro cloud (optional)", v -> openCloudInfo());
        button(text(R.string.licenses_title), v -> startActivity(new Intent(this, LicensesActivity.class)));
        if (!AppIdentity.standalone(this))
            button(text(R.string.login_home_setup), v -> startActivity(new Intent(this, HomeSetupActivity.class)));
        button(text(R.string.login_tv_settings), v -> {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
            catch (Exception missing) { line(text(R.string.login_tv_settings_missing), 24, TvUi.DIM); }
        });
    }
    private void login() {
        final int run = ++generation;
        content.removeAllViews();
        line(loginTitle(), 54, TvUi.INK);
        if (AppIdentity.standalone(this)) line("Connect your free Stremio account to use your own add-ons. No Noiro membership is required.", 27, TvUi.DIM);
        final TextView status = line(text(R.string.login_creating), 28, TvUi.DIM);
        TextView retry = button(text(R.string.login_new_code), v -> login());
        help(); retry.requestFocus();
        worker = new Thread(() -> {
            try {
                StremioLogin.Code code = StremioLogin.create();
                Bitmap qr = render(Qr.encode(code.url), Math.round(340 * k));
                main.post(() -> {
                    if (!current(run)) return;
                    content.removeAllViews();
                    line(loginTitle(), 54, TvUi.INK);
                    line(AppIdentity.standalone(this)
                            ? "Scan this code and approve Stremio sign-in. Your own add-ons and basic playback are free; no Noiro membership is required."
                            : text(R.string.login_scan_help), 27, TvUi.DIM);
                    LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
                    ImageView image = new ImageView(this); image.setImageBitmap(qr);
                    image.setContentDescription(text(R.string.login_qr_description));
                    row.addView(image, new LinearLayout.LayoutParams(Math.round(340 * k), Math.round(340 * k)));
                    TextView value = TvUi.text(this, k, 44, Ui.displaySemibold, TvUi.INK);
                    value.setText(code.value + "\n\nhttps://link.stremio.com");
                    value.setPadding(Math.round(55 * k), 0, 0, 0); row.addView(value);
                    content.addView(row);
                    TextView fresh = button(text(R.string.login_new_code), v -> login());
                    help(); fresh.requestFocus();
                });
                long started = SystemClock.elapsedRealtime(); int failures = 0;
                while (current(run) && !LoginPolicy.expired(SystemClock.elapsedRealtime() - started)) {
                    String token;
                    try { token = StremioLogin.poll(code); failures = 0; }
                    catch (AccountHttp.Failure failure) {
                        if (!failure.code.equals("service_unavailable") && !failure.code.equals("rate_limited")) throw failure;
                        if (++failures >= 5) throw failure;
                        Thread.sleep(2000); continue;
                    }
                    if (token != null) {
                        if (!current(run)) return;
                        AccountState account = StremioLogin.exchange(token);
                        if (!current(run)) return;
                        main.post(() -> {
                            if (!current(run)) return;
                            try {
                                NoiroAccount.remember(account);
                                if (AppIdentity.standalone(this)) {
                                    if (cloudLogin) { cloudLogin = false; cloudInfo = true; showCloudInfo(); }
                                    else if (StreamsActivity.afterLogin(this, getIntent()) != null) openHome();
                                    else showConnected();
                                } else sync();
                            }
                            catch (Exception storage) { line(text(R.string.login_storage_failed), 28, TvUi.DIM); }
                        });
                        return;
                    }
                    Thread.sleep(2000);
                }
                if (current(run)) throw new AccountHttp.Failure("code_expired");
            } catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); }
            catch (Exception failure) {
                main.post(() -> {
                    if (!current(run)) return;
                    content.removeAllViews();
                    line(loginTitle(), 54, TvUi.INK);
                    line(text(R.string.login_code_failed), 28, TvUi.DIM);
                    button(text(R.string.login_new_code), v -> login()).requestFocus(); help();
                });
            }
        }, "stremio-qr-login");
        worker.start();
    }
    private void sync() {
        final int run = ++generation;
        content.removeAllViews();
        line(text(R.string.login_connecting_noiro), 50, TvUi.INK);
        line(text(R.string.login_sync_help), 28, TvUi.DIM);
        help();
        worker = new Thread(() -> {
            try {
                NoiroAccount.sync();
                main.post(() -> {
                    if (!current(run)) return;
                    if (AppIdentity.standalone(this) && cloudInfo) {
                        cloudFailure = "";
                        showCloudInfo();
                        return;
                    }
                    if (!manage && getIntent().getBooleanExtra("resume_home", false)) {
                        openHome();
                    } else showConnected();
                });
            } catch (Exception failure) {
                final String code = failure instanceof AccountHttp.Failure ? ((AccountHttp.Failure) failure).code : "service_unavailable";
                main.post(() -> {
                    if (!current(run)) return;
                    if (AppIdentity.standalone(this) && NoiroAccount.current().signedIn()) {
                        cloudFailure = code;
                        showCloudInfo();
                        return;
                    }
                    content.removeAllViews();
                    line(text(R.string.login_connecting_noiro), 50, TvUi.INK);
                    int message = code.equals("supporter_required") ? R.string.login_supporter_required
                            : code.equals("account_link_required") ? R.string.login_link_noiro
                            : code.equals("tmdb_not_connected") || code.equals("tmdb_reconnect_required") ? R.string.login_link_tmdb
                            : code.equals("server_update_required") ? R.string.login_server_update
                            : code.equals("account_unavailable") ? R.string.login_account_unavailable
                            : code.equals("authentication_required") ? R.string.login_session_expired
                            : R.string.login_sync_failed;
                    line(text(message), 29, TvUi.DIM);
                    button(text(R.string.login_retry), v -> begin()).requestFocus();
                    button(text(R.string.login_sign_out), v -> confirmSignOut()); help();
                });
            }
        }, "noiro-account-sync");
        worker.start();
    }
    private void showConnected() {
        content.removeAllViews();
        boolean standalone = AppIdentity.standalone(this);
        line(standalone ? "Stremio connected" : text(R.string.login_connected), 52, TvUi.INK);
        line(NoiroAccount.current().label, 28, TvUi.DIM);
        line(standalone ? "Basic browsing and playback with your own add-ons are free. Noiro cloud services are optional."
                : text(R.string.login_ready_help), 28, TvUi.DIM);
        button(text(R.string.login_start), v -> {
            openHome();
        }).requestFocus();
        button(standalone ? NoiroAccount.ready() ? "Manage Noiro cloud (optional)" : "Noiro cloud (optional)"
                : text(R.string.login_refresh), v -> { if (standalone) openCloudInfo(); else sync(); });
        if (standalone) button("TMDB metadata (optional)", v -> startActivity(new Intent(this, TmdbSetupActivity.class)));
        if (!AppIdentity.standalone(this))
            button(text(R.string.login_home_setup), v -> startActivity(new Intent(this, HomeSetupActivity.class)));
        if (manage || standalone) {
            button(text(R.string.login_sign_out), v -> confirmSignOut());
        }
    }

    private void openCloudInfo() {
        generation++;
        if (worker != null) worker.interrupt();
        cloudLogin = false;
        cloudFailure = "";
        cloudLink = NoiroAccount.ready() ? 1 : 0;
        showCloudInfo();
    }

    private void showCloudInfo() {
        cloudInfo = true;
        content.removeAllViews();
        line("Noiro cloud · optional", 48, TvUi.INK);
        line("Manage Home and profiles on Noiro.tv, add eligible Plex rows, sync Main profile watching progress and player settings, save a default audio language, and translate supported subtitles with AI. Connect the services you want to use.", 25, TvUi.DIM);
        line("Basic browsing and playback with your own add-ons stay free. Cloud access uses a Noiro Supporter membership and the required account connections.", 24, TvUi.DIM);
        if (NoiroAccount.ready()) line("Noiro cloud connected", 27, TvUi.INK);
        if (!cloudFailure.isEmpty()) line(cloudMessage(cloudFailure), 25, TvUi.INK);

        String[] pages = {"Membership plans", "Connect Stremio", "TMDB setup"};
        String[] urls = {"https://noiro.tv/?page=Supporters",
                "https://noiro.tv/?page=Account&tab=connections&provider=Stremio",
                "https://noiro.tv/?page=Account&tab=overview"};
        String url = urls[cloudLink];
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView qr = new ImageView(this);
        qr.setImageBitmap(render(Qr.encode(url), Math.round(230 * k)));
        qr.setContentDescription("Noiro " + pages[cloudLink] + " QR code");
        row.addView(qr, new LinearLayout.LayoutParams(Math.round(230 * k), Math.round(230 * k)));
        TextView steps = TvUi.text(this, k, 24, Ui.regular, TvUi.INK);
        steps.setText("1. Sign in at noiro.tv on your phone. Already Supporter or Lifetime? Use your existing paid account.\n"
                + "2. New customers can create an account and open Supporters to see current plans. Existing members do not need to buy again.\n"
                + "3. In Account → Connections, link the same Stremio account used on this TV.\n"
                + "4. In Account → Overview, open Configure Main → Setup → API keys & OAuth → TMDB. Verify and save your key.\n"
                + "5. Return here and select Check cloud connection. Plex setup is separate in Account → Plex.");
        steps.setPadding(Math.round(30 * k), 0, 0, 0);
        row.addView(steps, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        content.addView(row);
        line("Scan with your phone · " + pages[cloudLink] + " · noiro.tv", 22, TvUi.DIM);
        line("AI translation supports selected languages and depends on service availability. If existing paid access is missing, contact Support on Noiro.tv before buying again.", 21, TvUi.DIM);
        button("Choose website page", v -> new AlertDialog.Builder(this)
                .setTitle("Open on your phone").setSingleChoiceItems(pages, cloudLink, (dialog, which) -> {
                    dialog.dismiss(); cloudLink = which; showCloudInfo();
                }).setNegativeButton(android.R.string.cancel, null).show());
        button(NoiroAccount.current().signedIn() ? "Check cloud connection" : "Connect free Stremio account first", v -> {
            if (NoiroAccount.current().signedIn()) sync();
            else { cloudInfo = false; cloudLogin = true; login(); }
        }).requestFocus();
        button("Continue using Noiro free", v -> openHome());
        button("Back", v -> leaveCloudInfo());
    }

    private String cloudMessage(String code) {
        switch (code) {
            case "supporter_required": return "No active Supporter access was found. Check your membership on Noiro.tv. Existing customers should sign in with their original account before choosing a new plan.";
            case "account_link_required": return "Link the same Stremio account in Noiro.tv → Account → Connections, then check again.";
            case "identity_mismatch": return "The cloud account does not match this TV. In Noiro.tv → Account → Connections, link the same Stremio account used here, then check again.";
            case "tmdb_not_connected":
            case "tmdb_reconnect_required": return "Finish TMDB setup in Noiro.tv → Account → Overview → Configure Main → Setup → API keys & OAuth, then check again.";
            case "authentication_required": return "The cloud connection could not be approved. Check your Noiro account and linked Stremio connection, then retry. Your free Stremio session is still available.";
            case "account_unavailable": return "This Noiro account is unavailable. Check your account on Noiro.tv or contact support there.";
            case "server_update_required": return "The Noiro cloud service needs an update. Try again later; free app access is still available.";
            case "rate_limited": return "Too many connection checks. Wait a moment, then try again.";
            default: return "Couldn't reach Noiro cloud. Check your internet connection and try again. You can keep using Noiro free.";
        }
    }

    private void leaveCloudInfo() {
        generation++;
        cloudInfo = cloudLogin = false;
        cloudFailure = "";
        begin();
    }

    @Override protected void onSaveInstanceState(Bundle saved) {
        saved.putBoolean("cloud_info", cloudInfo);
        saved.putBoolean("cloud_login", cloudLogin);
        saved.putInt("cloud_link", cloudLink);
        saved.putString("cloud_failure", cloudFailure);
        super.onSaveInstanceState(saved);
    }
    private void openHome() {
        Intent continuation = StreamsActivity.afterLogin(this, getIntent());
        if (AppAccess.ready(this) && continuation != null) {
            startActivity(continuation);
            finish();
            return;
        }
        openBrowse();
    }

    private void openBrowse() {
        Intent intent = new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (!manage) intent.putExtra("open", getIntent().getStringExtra("open"));
        startActivity(intent);
        finish();
    }

    private void confirmSignOut() {
        new AlertDialog.Builder(this).setTitle(text(R.string.login_sign_out))
                .setMessage(text(R.string.login_sign_out_confirm))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    try { NoiroAccount.signOut(); cloudInfo = cloudLogin = false; cloudFailure = ""; generation++; login(); }
                    catch (Exception failure) { line(text(R.string.login_storage_failed), 26, TvUi.DIM); }
                }).show();
    }
    @Override public void onBackPressed() {
        if (AppIdentity.standalone(this) && cloudLogin) { openCloudInfo(); return; }
        if (AppIdentity.standalone(this) && cloudInfo) { leaveCloudInfo(); return; }
        if (AppIdentity.standalone(this) || NoiroAccount.ready()) super.onBackPressed();
        else line(text(R.string.login_required), 26, TvUi.DIM);
    }
    private static Bitmap render(Qr qr, int size) {
        int modules = qr.size + 8, scale = Math.max(1, size / modules), side = modules * scale;
        Bitmap image = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        image.eraseColor(Color.WHITE);
        for (int y = 0; y < qr.size; y++) for (int x = 0; x < qr.size; x++) if (qr.dark(x, y))
            for (int dy = 0; dy < scale; dy++) for (int dx = 0; dx < scale; dx++)
                image.setPixel((x + 4) * scale + dx, (y + 4) * scale + dy, Color.BLACK);
        return image;
    }
}
