package tv.noiro.launcher;

import android.app.Activity;
import android.os.Bundle;
import android.widget.*;

/** TV remote accessible update status, automatic checks, and Android installer handoff. */
public class UpdatesActivity extends Activity implements LauncherUpdates.Listener {
    private TextView status;
    private Button check, install;
    private boolean awaitingInstallPermission;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); Ui.init(this);
        awaitingInstallPermission = state != null && state.getBoolean("awaitingInstallPermission", false);
        float k=TvUi.k(this);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(TvUi.BG); root.setPadding((int)(110*k),(int)(65*k),(int)(110*k),(int)(45*k));
        TextView title=TvUi.text(this,k,48,Ui.displaySemibold,TvUi.INK); title.setText("Updates"); root.addView(title);
        TextView info=TvUi.text(this,k,26,Ui.regular,TvUi.DIM);
        info.setText("Installed: " + NoiroAccount.APP_VERSION + "\n" + (LauncherUpdates.available(this)
                ? "Updates come from published stable GitHub Releases. Android asks you to confirm installation."
                : "The separate Noiro update channel is not published yet.")); root.addView(info);
        Switch automatic=new Switch(this); automatic.setText("Automatically check and download updates"); automatic.setTextColor(TvUi.INK);
        automatic.setChecked(LauncherUpdates.enabled(this)); automatic.setEnabled(LauncherUpdates.available(this)); automatic.setOnCheckedChangeListener((b,on)->LauncherUpdates.enabled(this,on)); root.addView(automatic);
        status=TvUi.text(this,k,28,Ui.regular,TvUi.INK); status.setPadding(0,(int)(25*k),0,(int)(25*k)); root.addView(status);
        check=new Button(this); check.setText("Check for updates"); check.setOnClickListener(v->LauncherUpdates.check(this,null)); root.addView(check);
        install=new Button(this); install.setText("Install update"); install.setOnClickListener(v->{
            awaitingInstallPermission = !LauncherUpdates.installPermission(this);
            LauncherUpdates.install(this);
        }); root.addView(install);
        setContentView(root); check.requestFocus();
        if (!LauncherUpdates.available(this)) LauncherUpdates.check(this, null);
        changed();
    }
    @Override protected void onResume() {
        super.onResume(); LauncherUpdates.listen(this); changed();
        if (awaitingInstallPermission && LauncherUpdates.ready(this) && LauncherUpdates.installPermission(this)) {
            awaitingInstallPermission = false;
            LauncherUpdates.install(this);
        }
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("awaitingInstallPermission", awaitingInstallPermission);
        super.onSaveInstanceState(state);
    }
    @Override protected void onPause() { LauncherUpdates.listen(null); super.onPause(); }
    @Override public void changed() {
        status.setText(LauncherUpdates.status); check.setEnabled(!LauncherUpdates.busy());
        install.setEnabled(!LauncherUpdates.busy() && LauncherUpdates.ready(this));
    }
}
