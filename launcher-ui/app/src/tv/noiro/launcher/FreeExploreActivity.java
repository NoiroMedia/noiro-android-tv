package tv.noiro.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Public catalog browsing and search, with the same TV catalog layout as Home. */
public class FreeExploreActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private KodiHomeView results;
    private int generation;
    private String query = "";
    private AccountState owner;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Ui.init(this);
        Images.init(this);
        owner = NoiroAccount.current();
        results = new KodiHomeView(this, title -> {}, this::finish, this::search, this::search);
        results.setResultCardOpener(card -> FreeDetailActivity.open(this, card));
        setContentView(results);
        load("");
        if (getIntent().getBooleanExtra("search", false)) results.post(this::search);
    }

    private boolean current(AccountState expected) {
        AccountState account = NoiroAccount.current();
        return expected.signedIn() ? account.owns(expected.authKey, expected.uid) : !account.signedIn();
    }

    private void search() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setHint(Ui.tr("Buscar filmes e séries", "Search movies and series"));
        input.setText(query);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.Noiro_MenuDialog)
                .setTitle(Ui.tr("Buscar", "Search")).setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(Ui.tr("Descobrir", "Discover"), (d, which) -> load(""))
                .setPositiveButton(Ui.tr("Buscar", "Search"), (d, which) -> load(input.getText().toString())).create();
        input.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            dialog.dismiss(); load(input.getText().toString()); return true;
        });
        dialog.show();
        input.requestFocus();
    }

    private void load(String value) {
        query = value.trim();
        if (query.length() > 120) query = query.substring(0, 120);
        String searched = query;
        int run = ++generation;
        AccountState expected = owner;
        results.startResults(query.isEmpty() ? Ui.tr("Descobrir", "Discover") : query, true);
        io.execute(() -> {
            try {
                List<StudioHome.Row> rows = searched.isEmpty() ? FreeCatalog.rows() : FreeCatalog.search(searched);
                main.post(() -> {
                    if (run != generation || isFinishing() || isDestroyed() || !current(expected)) return;
                    int slot = 0;
                    for (StudioHome.Row row : rows) results.resultCards(slot++, row.name, row.cards, null, null);
                    results.resultMessage(Ui.tr("Nenhum título encontrado.", "No titles found."));
                    results.focusResults();
                });
            } catch (Exception unavailable) {
                main.post(() -> {
                    if (run == generation && !isFinishing() && !isDestroyed() && current(expected))
                        results.resultMessage("Couldn't load catalogs. Open Search to try again.");
                });
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        if (!current(owner)) { owner = NoiroAccount.current(); load(query); }
        results.resume();
    }
    @Override protected void onPause() { results.pause(); super.onPause(); }
    @Override protected void onDestroy() { generation++; io.shutdownNow(); results.destroy(); super.onDestroy(); }
    @Override public void onBackPressed() { if (!results.back()) super.onBackPressed(); }
}
