package tv.noiro.launcher;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Offline, read-only notices bundled in this APK. Back returns to the selected settings row. */
public class LicensesActivity extends Activity {
    private ScrollView scroll;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        float k = TvUi.k(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(TvUi.BG);
        root.setPadding(Math.round(110 * k), Math.round(55 * k), Math.round(110 * k), Math.round(45 * k));
        TextView title = TvUi.text(this, k, 48, Ui.displaySemibold, TvUi.INK);
        title.setText(Ui.string(this, R.string.licenses_title));
        root.addView(title);
        TextView hint = TvUi.text(this, k, 25, Ui.regular, TvUi.DIM);
        hint.setText(Ui.string(this, R.string.licenses_hint));
        hint.setPadding(0, Math.round(12 * k), 0, Math.round(24 * k));
        root.addView(hint);
        scroll = new ScrollView(this);
        scroll.setId(0x00ab1001);
        scroll.setFocusable(true);
        scroll.setFocusableInTouchMode(true);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        scroll.setVerticalScrollBarEnabled(true);
        TextView text = TvUi.text(this, k, 27, Ui.regular, TvUi.INK);
        text.setText(readNotices());
        text.setLineSpacing(8 * k, 1f);
        scroll.addView(text, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        scroll.requestFocus();
    }

    private String readNotices() {
        StringBuilder out = new StringBuilder(AppIdentity.name(this) + "\nCopyright (c) 2026 Noiro\nhttps://noiro.tv\n\n");
        try {
            String[] files = getAssets().list("licenses");
            if (files == null || files.length == 0) throw new IOException("No bundled licenses");
            Arrays.sort(files);
            for (String name : files) {
                out.append("\n— ").append(name).append(" —\n\n");
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        getAssets().open("licenses/" + name), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) out.append(line).append('\n');
                }
            }
        } catch (IOException e) {
            out.append("Unable to read bundled licenses. Please contact Noiro at https://noiro.tv.");
        }
        return out.toString();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                int step = Math.max(1, scroll.getHeight() / 3);
                scroll.scrollBy(0, key == KeyEvent.KEYCODE_DPAD_UP ? -step : step);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
