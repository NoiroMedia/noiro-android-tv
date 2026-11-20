package tv.noiro.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Explicit, reversible Home setup. Merely opening this screen never changes system settings. */
public class HomeSetupActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private HomeRecordStore records;
    private TextView state, message, connect, activate, restore, system;
    private EditText port;
    private CheckBox allowDisable;
    private LinearLayout localPanel;
    private boolean busy, checked;
    private float k;

    private String text(int id) { return Ui.string(this, id); }
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (AppIdentity.standalone(this)) { finish(); return; }
        Ui.init(this);
        records = new HomeRecordStore(this);
        k = TvUi.k(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(TvUi.BG);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Math.round(200 * k), Math.round(55 * k), Math.round(200 * k), Math.round(65 * k));
        scroll.addView(content);
        TextView title = TvUi.text(this, k, 50, Ui.displaySemibold, TvUi.INK);
        title.setText(text(R.string.home_setup_title));
        content.addView(title);
        TextView help = TvUi.text(this, k, 26, Ui.regular, TvUi.DIM);
        help.setText(text(R.string.home_setup_help));
        help.setPadding(0, Math.round(18 * k), 0, Math.round(18 * k));
        content.addView(help);
        state = TvUi.text(this, k, 27, Ui.medium, TvUi.INK);
        content.addView(state);
        system = add(content, text(R.string.home_system_choose), v -> chooseSystem());
        add(content, text(R.string.home_guided_setup), v -> {
            localPanel.setVisibility(localPanel.getVisibility() == android.view.View.VISIBLE ? android.view.View.GONE : android.view.View.VISIBLE);
        });
        localPanel = new LinearLayout(this);
        localPanel.setOrientation(LinearLayout.VERTICAL);
        localPanel.setVisibility(android.view.View.GONE);
        content.addView(localPanel);
        add(localPanel, text(R.string.home_system_settings), v -> openSettings(Settings.ACTION_HOME_SETTINGS));
        add(localPanel, text(R.string.home_developer_settings), v -> openSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        TextView local = TvUi.text(this, k, 26, Ui.regular, TvUi.DIM);
        local.setText(text(R.string.home_local_help));
        local.setPadding(0, Math.round(20 * k), 0, Math.round(10 * k));
        localPanel.addView(local);
        TextView portLabel = TvUi.text(this, k, 24, Ui.regular, TvUi.DIM);
        portLabel.setText(text(R.string.home_debug_port));
        localPanel.addView(portLabel);
        port = new EditText(this);
        port.setSingleLine(true);
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        port.setTextColor(TvUi.INK);
        port.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 27 * k);
        port.setText("5555");
        port.setContentDescription(text(R.string.home_debug_port));
        localPanel.addView(port);
        connect = add(localPanel, text(R.string.home_check_connection), v -> run(false, false));
        allowDisable = new CheckBox(this);
        allowDisable.setText(text(R.string.home_disable_optional));
        allowDisable.setTextColor(TvUi.INK);
        allowDisable.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 25 * k);
        localPanel.addView(allowDisable);
        activate = add(localPanel, text(R.string.home_local_activate), v -> confirm(false));
        restore = add(content, text(R.string.home_restore), v -> confirm(true));
        message = TvUi.text(this, k, 25, Ui.regular, TvUi.DIM);
        message.setPadding(0, Math.round(18 * k), 0, Math.round(18 * k));
        content.addView(message);
        add(content, text(R.string.home_done), v -> { if (!busy) finish(); });
        setContentView(scroll);
        update();
        system.requestFocus();
    }
    @Override protected void onResume() {
        super.onResume();
        if (state != null) update();
    }
    private TextView add(LinearLayout content, String label, android.view.View.OnClickListener action) {
        TextView button = TvUi.button(this, k, label, action);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(10 * k);
        content.addView(button, lp);
        return button;
    }
    private String current() {
        ResolveInfo info = getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
        return info == null || info.activityInfo == null ? "" : new ComponentName(info.activityInfo.packageName, info.activityInfo.name).flattenToShortString();
    }
    private void update() {
        String home = current();
        state.setText(text(HomeSwitch.isNoiro(home) ? R.string.home_active : R.string.home_not_active));
        connect.setEnabled(!busy);
        system.setEnabled(!busy);
        port.setEnabled(!busy);
        allowDisable.setEnabled(!busy);
        activate.setEnabled(!busy && checked && !HomeSwitch.isNoiro(home));
        restore.setEnabled(!busy && records.read() != null);
        activate.setAlpha(activate.isEnabled() ? 1f : 0.4f);
        restore.setAlpha(restore.isEnabled() ? 1f : 0.4f);
    }
    private void openSettings(String action) {
        if (busy) return;
        try { startActivity(new Intent(action)); }
        catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
            catch (Exception unavailable) { message.setText(text(R.string.home_settings_unavailable)); }
        }
    }
    private void chooseSystem() {
        if (busy || AppIdentity.standalone(this)) return;
        try {
            String home = current();
            if (records.read() == null && HomeSwitch.validOriginal(home)) records.write(new HomeSwitch.Record(home, ""));
            if (Build.VERSION.SDK_INT >= 29) {
                RoleManager roles = getSystemService(RoleManager.class);
                if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
                    startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), 501);
                    return;
                }
            }
            openSettings(Settings.ACTION_HOME_SETTINGS);
        } catch (Exception e) { message.setText(text(R.string.home_settings_unavailable)); }
    }
    private void confirm(boolean restoring) {
        if (busy) return;
        boolean disable = allowDisable.isChecked();
        String body = text(restoring ? R.string.home_restore_confirm : disable ? R.string.home_activate_disable_confirm : R.string.home_activate_confirm);
        new AlertDialog.Builder(this)
                .setTitle(text(restoring ? R.string.home_restore : R.string.home_local_activate))
                .setMessage(body)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, which) -> run(true, restoring)).show();
    }
    private void run(boolean changing, boolean restoring) {
        if (busy || AppIdentity.standalone(this)) return;
        final int number;
        try { number = Integer.parseInt(port.getText().toString().trim()); if (number < 1 || number > 65535) throw new NumberFormatException(); }
        catch (NumberFormatException invalid) { message.setText(text(R.string.home_invalid_port)); return; }
        final boolean mayDisable = allowDisable.isChecked();
        busy = true;
        update();
        message.setText(text(R.string.home_connecting));
        new Thread(() -> {
            String result;
            boolean success = false;
            try (LocalAdb adb = new LocalAdb(this, number)) {
                if (!"2000".equals(adb.run("id -u").trim())) throw new java.io.IOException("The connection is not a TV debugging shell.");
                HomeSwitch operation = new HomeSwitch(adb, records, !AppIdentity.standalone(this));
                if (changing) {
                    if (restoring) operation.restore(); else operation.activate(mayDisable);
                    result = text(restoring ? R.string.home_restored : R.string.home_activated);
                } else result = text(R.string.home_connection_ready);
                success = true;
            } catch (Exception e) {
                result = e.getMessage() == null ? text(R.string.home_connection_failed) : e.getMessage();
            }
            final String outcome = result;
            final boolean connected = success;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                busy = false;
                checked = connected;
                update();
                message.setText(outcome);
                if (connected && !changing) activate.requestFocus();
            });
        }, "noiro-home-setup").start();
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (busy && event.getKeyCode() == KeyEvent.KEYCODE_BACK) return true;
        return super.dispatchKeyEvent(event);
    }
}
