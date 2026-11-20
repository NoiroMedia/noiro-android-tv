package tv.noiro.launcher;

import android.app.Activity;
import android.app.Dialog;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Installed apps and TV inputs, opened as a drawer over the catalog Home. */
final class AppsDialog extends Dialog {
    private final Activity activity;
    private final float k;
    private LinearLayout content;
    private int generation;

    AppsDialog(Activity activity) {
        super(activity, R.style.Noiro_MenuDialog);
        this.activity = activity;
        k = TvUi.k(activity);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Window window = getWindow();
        window.requestFeature(Window.FEATURE_NO_TITLE);
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setDimAmount(0.65f);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(size(36), size(28), size(20), size(30));
        root.setBackground(TvUi.round(0xFF151A23, size(28)));
        TextView heading = TvUi.text(activity, k, 41, Ui.displaySemibold, TvUi.INK);
        heading.setText(Ui.tr("Todos os Apps", "All Apps"));
        heading.setPadding(0, 0, 0, size(24));
        root.addView(heading);

        TvUi.Scroll scroll = new TvUi.Scroll(activity);
        content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        root.addView(scroll);
        setContentView(root);
        reload(null);
    }

    @Override public void show() {
        super.show();
        getWindow().setLayout(size(1600), size(830));
    }

    private void reload(String focusPackage) {
        int request = ++generation;
        new Thread(() -> {
            List<HomeApps.Item> shelf = HomeApps.shelf(activity, false);
            List<HomeApps.Item> installed = HomeApps.others(activity);
            java.util.Set<String> pinned = new java.util.HashSet<>();
            for (HomeApps.Item item : shelf) pinned.add(item.pkg);
            installed.removeIf(item -> pinned.contains(item.pkg));
            List<HomeApps.Item> inputs = HomeApps.allInputs(activity);
            List<HomeApps.Item> shortcuts = HomeApps.drawerShortcuts();
            shortcuts.removeIf(item -> item.kind != HomeApps.WIFI && item.kind != HomeApps.ANDROID_SETTINGS);
            inputs.addAll(shortcuts);
            activity.runOnUiThread(() -> {
                if (!isShowing() || generation != request || activity.isFinishing()) return;
                content.removeAllViews();
                addSection(Ui.tr("Prateleira", "Shelf"), shelf);
                addSection("", installed);
                addSection(Ui.tr("Atalhos", "Shortcuts"), inputs);
                View focus = focusPackage == null ? null : content.findViewWithTag(focusPackage);
                if (focus != null) focus.requestFocus();
                else content.requestFocus(View.FOCUS_DOWN);
            });
        }, "noiro-apps").start();
    }

    private void addSection(String name, List<HomeApps.Item> items) {
        if (items.isEmpty()) return;
        if (!name.isEmpty()) {
            TextView header = TvUi.text(activity, k, 25, Ui.semibold, TvUi.DIM);
            header.setText(name);
            header.setPadding(0, size(8), 0, size(12));
            content.addView(header);
        }
        LinearLayout line = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % 6 == 0) {
                line = new LinearLayout(activity);
                content.addView(line);
            }
            HomeApps.Item item = items.get(i);
            LinearLayout card = new LinearLayout(activity);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setPadding(size(8), size(8), size(8), size(8));
            card.setContentDescription(item.label);
            card.setTag(item.pkg);

            if (item.tile != null || item.logo != null) {
                ImageView image = new ImageView(activity);
                image.setScaleType(item.tile != null ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
                image.setImageBitmap(item.tile != null ? item.tile : item.logo);
                if (item.tile == null) image.setPadding(size(27), size(24), size(27), size(24));
                card.addView(image, new LinearLayout.LayoutParams(-1, size(108)));
            } else {
                card.addView(new TvUi.Icon(activity, item.symbol, 40 * k), new LinearLayout.LayoutParams(-1, size(108)));
            }
            TextView label = TvUi.text(activity, k, 20, Ui.medium, TvUi.INK);
            label.setText(item.label);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            card.addView(label, new LinearLayout.LayoutParams(-1, size(38)));

            TvUi.softFocus(card, k, size(14));
            card.setOnClickListener(v -> {
                if (HomeApps.open(activity, item, v, 0, 0, v.getWidth(), v.getHeight())) dismiss();
                else Toast.makeText(activity, Ui.tr("Não deu para abrir ", "Couldn't open ") + item.label, Toast.LENGTH_SHORT).show();
            });
            if (item.pkg != null) card.setOnLongClickListener(v -> {
                showAppOptions(v, item);
                return true;
            });
            LinearLayout.LayoutParams area = new LinearLayout.LayoutParams(size(240), size(165));
            area.rightMargin = size(14);
            area.bottomMargin = size(14);
            line.addView(card, area);
        }
    }

    private void showAppOptions(View card, HomeApps.Item item) {
        android.graphics.Rect bounds = new android.graphics.Rect();
        card.getDrawingRect(bounds);
        int[] location = new int[2];
        card.getLocationOnScreen(location);
        bounds.offset(location[0], location[1]);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        List<String> shelf = HomeApps.shelfPackages(activity);
        if (shelf.size() > 1 && shelf.contains(item.pkg)) {
            labels.add(Ui.tr("Mover", "Move"));
            actions.add(() -> moveShelfApp(item, shelf));
        }
        labels.add(Ui.tr("Logo e cor", "Logo and color"));
        actions.add(() -> CustomTiles.edit(activity, item.pkg, item.label, () -> reload(item.pkg)));
        labels.add(Ui.tr("Configurações do app", "App settings"));
        actions.add(() -> openAppSettings(item.pkg));
        labels.add(Ui.tr("Sair", "Close"));
        actions.add(() -> {});
        TvUi.popover(activity, card, bounds, item.label, labels.toArray(new String[0]),
                choice -> actions.get(choice).run(), false, true);
    }

    private void moveShelfApp(HomeApps.Item item, List<String> shelf) {
        String[] positions = new String[shelf.size()];
        for (int i = 0; i < positions.length; i++) positions[i] = String.valueOf(i + 1);
        new AlertDialog.Builder(activity, R.style.Noiro_MenuDialog)
                .setTitle(Ui.tr("Mover", "Move") + " · " + item.label)
                .setSingleChoiceItems(positions, shelf.indexOf(item.pkg), (dialog, position) -> {
                    dialog.dismiss();
                    shelf.remove(item.pkg);
                    shelf.add(position, item.pkg);
                    Prefs.setShelf(shelf);
                    reload(item.pkg);
                }).show();
    }

    private void openAppSettings(String pkg) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)));
        } catch (RuntimeException unavailable) {
            Toast.makeText(activity, Ui.tr("Esta TV não abre os ajustes do app.", "This TV can't open the app's settings."), Toast.LENGTH_SHORT).show();
        }
    }

    private int size(int value) { return Math.round(value * k); }
}
