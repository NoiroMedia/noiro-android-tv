package tv.noiro.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Device-local API configuration. TMDB account approval is a separate, optional step. */
public class TmdbSetupActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private EditText input;
    private TextView status, save, account, remove;
    private boolean validating;

    private String text(int id) { return Ui.string(this, id); }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        float k = TvUi.k(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(TvUi.BG);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Math.round(220 * k), Math.round(60 * k), Math.round(220 * k), Math.round(60 * k));
        scroll.addView(content);
        TextView title = TvUi.text(this, k, 52, Ui.displaySemibold, TvUi.INK);
        title.setText(text(R.string.tmdb_setup_title));
        content.addView(title);
        TextView help = TvUi.text(this, k, 27, Ui.regular, TvUi.DIM);
        help.setText(AppIdentity.standalone(this)
                ? "Optional: use your own TMDB API key for additional metadata. Basic browsing does not require a key or Noiro membership."
                : text(R.string.tmdb_setup_help));
        help.setPadding(0, Math.round(20 * k), 0, Math.round(20 * k));
        content.addView(help);
        input = new EditText(this);
        input.setSingleLine(true);
        input.setTextColor(TvUi.INK);
        input.setHintTextColor(TvUi.DIM);
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 29 * k);
        input.setHint(text(R.string.tmdb_key_hint));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        input.setSaveEnabled(false);
        content.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(80 * k)));
        status = TvUi.text(this, k, 26, Ui.regular, TvUi.DIM);
        status.setPadding(0, Math.round(16 * k), 0, Math.round(12 * k));
        content.addView(status);
        save = TvUi.button(this, k, text(R.string.tmdb_save), v -> validate());
        addButton(content, save, k);
        account = TvUi.button(this, k, text(R.string.tmdb_account), v -> startActivity(new Intent(this, TmdbLoginActivity.class)));
        addButton(content, account, k);
        remove = TvUi.button(this, k, text(R.string.tmdb_remove), v -> new AlertDialog.Builder(this)
                .setTitle(text(R.string.tmdb_remove))
                .setMessage(text(R.string.tmdb_remove_help))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    Tmdb.credentialsChanged(this);
                    Prefs.setTmdbKey("");
                    input.setText("");
                    update();
                }).show());
        addButton(content, remove, k);
        addButton(content, TvUi.button(this, k, text(R.string.tmdb_done), v -> finish()), k);
        setContentView(scroll);
        update();
        input.requestFocus();
    }

    @Override protected void onResume() {
        super.onResume();
        if (status != null && !validating) update();
    }

    private void addButton(LinearLayout content, TextView button, float k) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = Math.round(10 * k);
        content.addView(button, params);
    }

    private void update() {
        boolean managed = !AppIdentity.standalone(this) && !NoiroAccount.tmdbKey().isEmpty();
        input.setEnabled(!managed && !validating);
        save.setEnabled(!managed && !validating);
        save.setAlpha(save.isEnabled() ? 1f : 0.4f);
        status.setText(text(managed ? R.string.login_tmdb_managed : Tmdb.hasKey() ? R.string.tmdb_configured : R.string.tmdb_not_configured));
        account.setEnabled(Tmdb.hasKey() && !validating);
        remove.setEnabled(!Prefs.localTmdbKey().isEmpty() && !validating && !managed);
        account.setAlpha(account.isEnabled() ? 1f : 0.4f);
        remove.setAlpha(remove.isEnabled() ? 1f : 0.4f);
    }

    private void validate() {
        if (validating || !AppIdentity.standalone(this) && !NoiroAccount.tmdbKey().isEmpty()) return;
        String candidate = TmdbCredential.normalize(input.getText().toString());
        if (!TmdbCredential.validInput(candidate)) {
            status.setText(text(R.string.tmdb_invalid_input));
            input.requestFocus();
            return;
        }
        validating = true;
        input.setEnabled(false);
        save.setEnabled(false);
        account.setEnabled(false);
        remove.setEnabled(false);
        status.setText(text(R.string.tmdb_validating));
        new Thread(() -> {
            boolean valid;
            try { Tmdb.validateKey(candidate); valid = true; }
            catch (Exception ignored) { valid = false; }
            final boolean success = valid;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                validating = false;
                input.setEnabled(true);
                save.setEnabled(true);
                if (success) {
                    if (!candidate.equals(Prefs.tmdbKey())) Tmdb.credentialsChanged(this);
                    Prefs.setTmdbKey(candidate);
                    input.setText("");
                    update();
                    status.setText(text(R.string.tmdb_saved));
                    save.requestFocus();
                } else {
                    update();
                    status.setText(text(R.string.tmdb_validation_failed));
                    input.requestFocus();
                }
            });
        }, "tmdb-key-validation").start();
    }
}
