package tv.noiro.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeProvider;
import android.widget.Toast;

import java.util.List;

/** App settings, retaining the media UI's glass layout without TV-launcher controls. */
public class SettingsActivity extends Activity {
    private SettingsView view;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        view = new SettingsView(this);
        setContentView(view);
        view.requestFocus();
    }

    @Override protected void onResume() {
        super.onResume();
        view.resume();
    }

    @Override protected void onPause() {
        view.pause();
        super.onPause();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    static final class SettingsView extends View implements Choreographer.FrameCallback {
        private static final float LIST_X = 1000, LIST_R = 1824, LIST_TOP = 186;
        private static final float ROW_H = 74, ROW_GAP = 8;
        private static final int INK = 0xFF1C1C1E;
        private static final String[] LANGUAGES = {"auto", "tr", "pt", "en", "es", "de", "ru"};
        private final SettingsActivity activity;
        private final AppSettingsModel model = new AppSettingsModel();
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Spring[] pop = new Spring[AppSettingsModel.maximumRows()];
        private final RectF tmp = new RectF(), rowRect = new RectF(), focusBox = new RectF();
        private final Spoken spoken;
        private final Drawable icon;
        private final String installedVersion;
        private Paint title, subtitle, version, rowLabel, rowValue, heading, white, shadow, glassRow, glassRim;
        private boolean running, okDown, backDown;
        private int width, height, firstRow;
        private float k = 1;
        private long lastNanos;

        private final Runnable poll = new Runnable() {
            @Override public void run() {
                if (!running) return;
                invalidate();
                handler.postDelayed(this, 2000);
            }
        };

        SettingsView(SettingsActivity activity) {
            super(activity);
            this.activity = activity;
            for (int i = 0; i < pop.length; i++) pop[i] = new Spring(0.28f, 0.85f, 0);
            icon = activity.getDrawable(R.drawable.icon);
            installedVersion = versionName(activity);
            setFocusable(true);
            setFocusableInTouchMode(true);
            setContentDescription(AppIdentity.name(activity));
            spoken = new Spoken(this, new Spoken.Source() {
                @Override public void items(List<Integer> out) {
                    for (int i = 1; i <= model.rows().size(); i++) out.add(i);
                }
                @Override public String text(int id) {
                    if (id < 1 || id > model.rows().size()) return "";
                    AppSettingsModel.Row row = model.rows().get(id - 1);
                    String detail = value(row);
                    return label(row) + (detail.isEmpty() ? "" : ", " + detail);
                }
                @Override public boolean bounds(int id, Rect out) {
                    ensureFocusVisible();
                    if (width == 0 || id < 1 || id > model.rows().size()
                            || id - 1 < firstRow || id - 1 >= firstRow + visibleRows()) return false;
                    rowBox(id - 1).round(out);
                    return true;
                }
                @Override public int focused() { return model.focus() + 1; }
                @Override public void focus(int id) {
                    model.focus(id - 1);
                    ensureFocusVisible();
                    invalidate();
                }
                @Override public void click(int id) {
                    model.focus(id - 1);
                    activate();
                }
            });
        }

        @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() { return spoken; }

        void resume() {
            if (running) return;
            running = true;
            lastNanos = 0;
            handler.post(poll);
            Choreographer.getInstance().postFrameCallback(this);
            spoken.readAgain();
        }

        void pause() {
            running = false;
            handler.removeCallbacks(poll);
            Choreographer.getInstance().removeFrameCallback(this);
        }

        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            for (Spring spring : pop) {
                if (!spring.settled()) { invalidate(); break; }
            }
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            this.width = width;
            this.height = height;
            k = Math.max(1, width) / 1920f;
            title = Ui.text(Ui.displaySemibold, 50 * k, 0xFFFFFFFF);
            title.setTextAlign(Paint.Align.CENTER);
            subtitle = Ui.text(Ui.regular, 26 * k, 0x99FFFFFF);
            subtitle.setTextAlign(Paint.Align.CENTER);
            version = Ui.text(Ui.regular, 22 * k, 0x66FFFFFF);
            version.setTextAlign(Paint.Align.CENTER);
            rowLabel = Ui.text(Ui.medium, 30 * k, 0xF2FFFFFF);
            rowValue = Ui.text(Ui.regular, 28 * k, 0x8CFFFFFF);
            rowValue.setTextAlign(Paint.Align.RIGHT);
            heading = Ui.text(Ui.displaySemibold, 34 * k, 0xFFFFFFFF);
            white = new Paint(Paint.ANTI_ALIAS_FLAG);
            white.setColor(0xFFF5F5F7);
            shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
            glassRow = new Paint(Paint.ANTI_ALIAS_FLAG);
            glassRow.setStyle(Paint.Style.FILL);
            glassRim = new Paint(Paint.ANTI_ALIAS_FLAG);
            glassRim.setStyle(Paint.Style.STROKE);
            glassRim.setStrokeWidth(1.2f * k);
        }

        private int visibleRows() {
            return SettingsViewport.visibleRows(height / k - 80 - LIST_TOP, ROW_H, ROW_GAP);
        }

        private void ensureFocusVisible() {
            firstRow = SettingsViewport.firstRow(firstRow, model.focus(), model.rows().size(), visibleRows());
        }

        private RectF rowBox(int row) {
            float top = LIST_TOP + (row - firstRow) * (ROW_H + ROW_GAP);
            focusBox.set(LIST_X * k, top * k, LIST_R * k, (top + ROW_H) * k);
            return focusBox;
        }

        @Override protected void onDraw(Canvas canvas) {
            if (width == 0) return;
            ensureFocusVisible();
            long now = System.nanoTime();
            float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (now - lastNanos) / 1e9f);
            lastNanos = now;
            for (int i = 0; i < pop.length; i++) {
                pop[i].target = i == model.focus() && i < model.rows().size() ? 1 : 0;
                pop[i].step(dt);
            }
            canvas.drawColor(0xFF161618);
            float center = 500 * k;
            RectF panel = new RectF(140 * k, 260 * k, 860 * k, 790 * k);
            glassRow.setColor(0x28202024);
            canvas.drawRoundRect(panel, 28 * k, 28 * k, glassRow);
            glassRim.setColor(0x26FFFFFF);
            canvas.drawRoundRect(panel, 28 * k, 28 * k, glassRim);
            if (icon != null) {
                int half = Math.round(100 * k);
                icon.setBounds(Math.round(center) - half, Math.round(400 * k) - half,
                        Math.round(center) + half, Math.round(400 * k) + half);
                icon.draw(canvas);
            }
            canvas.drawText(AppIdentity.name(getContext()), center, 612 * k, title);
            canvas.drawText(appSettingsLabel(), center, 660 * k, subtitle);
            canvas.drawText(installedVersion, center, 704 * k, version);
            canvas.drawText("Noiro · noiro.tv", center, 742 * k, version);
            canvas.drawText(model.page() == AppSettingsModel.Page.APPEARANCE ? appearanceLabel()
                    : Ui.tr("Ajustes", "Settings"), LIST_X * k + 30 * k, (LIST_TOP - 40) * k, heading);
            int end = Math.min(model.rows().size(), firstRow + visibleRows());
            for (int i = firstRow; i < end; i++) {
                rowRect.set(rowBox(i));
                float focus = drawRowBackground(canvas, i);
                rowLabel.setColor(blend(0xF2FFFFFF, INK, focus));
                float baseline = rowRect.centerY() + Ui.halfCap(rowLabel);
                AppSettingsModel.Row row = model.rows().get(i);
                canvas.drawText(label(row), rowRect.left + 30 * k, baseline, rowLabel);
                String detail = value(row);
                float right = rowRect.right - 30 * k;
                if (detail.isEmpty()) {
                    float chevron = Symbols.width(getContext(), "chevron_right", 22 * k);
                    Symbols.draw(getContext(), canvas, "chevron_right", right - chevron / 2,
                            rowRect.centerY(), 22 * k, blend(0x66FFFFFF, 0x991C1C1E, focus));
                } else {
                    rowValue.setColor(blend(0x8CFFFFFF, 0x8C1C1C1E, focus));
                    canvas.drawText(detail, right, baseline, rowValue);
                }
            }
            Paint hint = Ui.text(Ui.regular, 23 * k, 0x8CFFFFFF);
            String position = model.rows().size() > visibleRows()
                    ? (model.focus() + 1) + " / " + model.rows().size() + "  ·  " : "";
            canvas.drawText(position + Ui.tr("OK ou as setas mudam o valor.", "OK or the arrows change the value."),
                    LIST_X * k + 30 * k, height - 38 * k, hint);
        }

        private String label(AppSettingsModel.Row row) {
            switch (row) {
                case APPEARANCE: return appearanceLabel();
                case SERVICES: return Ui.tr("Serviços", "Services");
                case ACCOUNT: return Ui.string(getContext(), R.string.login_account_title);
                case WHERE_TO_WATCH: return Ui.string(getContext(), R.string.where_to_watch_title);
                case TV_SETTINGS: return Ui.tr("Ajustes da TV", "TV Settings");
                case LICENSES: return Ui.string(getContext(), R.string.licenses_title);
                case UPDATES: return "Updates";
                case LANGUAGE: return Ui.tr("Idioma", "Language");
                case PLAYER_APPEARANCE: return Ui.tr("Visual do player", "Player appearance");
                default: throw new AssertionError(row);
            }
        }

        private String value(AppSettingsModel.Row row) {
            switch (row) {
                case ACCOUNT:
                    if (AppIdentity.standalone(getContext())) {
                        if (NoiroAccount.ready()) return "Stremio + Noiro cloud";
                        return NoiroAccount.current().signedIn() ? "Stremio · free" : "Browsing without an account";
                    }
                    return Ui.string(getContext(), NoiroAccount.ready() ? R.string.login_status_connected : R.string.login_status_sign_in);
                case WHERE_TO_WATCH: return onOff(Prefs.whereToWatch());
                case LANGUAGE:
                    String language = Prefs.language();
                    return language.equals("tr") ? "Türkçe" : language.equals("pt") ? "Português"
                            : language.equals("en") ? "English" : language.equals("es") ? "Español"
                            : language.equals("de") ? "Deutsch" : language.equals("ru") ? "Русский"
                            : Ui.tr("Automático", "Automatic");
                case PLAYER_APPEARANCE: return PlayerUiPreferences.noiro(getContext()) ? "Noiro · preview" : "Classic";
                default: return "";
            }
        }

        private String appearanceLabel() {
            switch (Ui.language) {
                case "tr": return "Görünüm";
                case "pt": return "Aparência";
                case "es": return "Apariencia";
                case "de": return "Darstellung";
                case "ru": return "Внешний вид";
                default: return "Appearance";
            }
        }

        private String appSettingsLabel() {
            switch (Ui.language) {
                case "tr": return "Uygulama ayarları";
                case "pt": return "Ajustes do aplicativo";
                case "es": return "Ajustes de la aplicación";
                case "de": return "App-Einstellungen";
                case "ru": return "Настройки приложения";
                default: return "App settings";
            }
        }

        private static String onOff(boolean enabled) {
            return enabled ? Ui.tr("Ligado", "On") : Ui.tr("Desligado", "Off");
        }

        private void adjust(int direction) {
            switch (model.selected()) {
                case LANGUAGE: Prefs.setLanguage(AppSettingsModel.cycle(LANGUAGES, Prefs.language(), direction)); break;
                case WHERE_TO_WATCH: Prefs.setWhereToWatch(!Prefs.whereToWatch()); break;
                case PLAYER_APPEARANCE:
                    boolean noiro = !PlayerUiPreferences.noiro(getContext());
                    PlayerUiPreferences.noiro(getContext(), noiro);
                    PlayerCloudState.enqueuePreference(getContext(), NoiroAccount.current(), "appearance", noiro ? "noiro" : "classic");
                    break;
                default: return;
            }
            invalidate();
            spoken.focusMoved();
        }

        private void activate() {
            if (model.openSelected()) {
                firstRow = 0;
                invalidate();
                spoken.focusMoved();
                return;
            }
            switch (model.selected()) {
                case SERVICES: activity.startActivity(new Intent(activity, ServicesActivity.class)); break;
                case ACCOUNT: activity.startActivity(new Intent(activity, OnboardingActivity.class).putExtra("manage", true)); break;
                case TV_SETTINGS:
                    try { activity.startActivity(new Intent(Settings.ACTION_SETTINGS)); }
                    catch (android.content.ActivityNotFoundException unavailable) {
                        Toast.makeText(activity, "TV settings are unavailable.", Toast.LENGTH_SHORT).show();
                    }
                    break;
                case LICENSES: activity.startActivity(new Intent(activity, LicensesActivity.class)); break;
                case UPDATES: activity.startActivity(new Intent(activity, UpdatesActivity.class)); break;
                default: adjust(1); return;
            }
        }

        boolean onKey(KeyEvent event) {
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down) {
                        model.move(event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
                        ensureFocusVisible();
                        invalidate();
                        spoken.focusMoved();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down) adjust(event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down) {
                        if (event.getRepeatCount() == 0) okDown = true;
                    } else {
                        if (okDown && !event.isCanceled() && spoken.keyClick()) activate();
                        okDown = false;
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (model.page() == AppSettingsModel.Page.MAIN) return false;
                    if (down) {
                        if (event.getRepeatCount() == 0) backDown = true;
                    } else {
                        if (backDown && !event.isCanceled()) {
                            model.back();
                            firstRow = 0;
                            invalidate();
                            spoken.focusMoved();
                        }
                        backDown = false;
                    }
                    return true;
                default: return false;
            }
        }

        private float drawRowBackground(Canvas canvas, int index) {
            float focus = Ui.clamp(pop[index].value);
            float radius = 18 * k;
            if (focus <= 0.01f) {
                glassRow.setColor(0x22242428);
                canvas.drawRoundRect(rowRect, radius, radius, glassRow);
                glassRim.setColor(0x26FFFFFF);
                canvas.drawRoundRect(rowRect, radius, radius, glassRim);
            } else {
                float scale = 1 + 0.02f * focus;
                float cx = rowRect.centerX(), cy = rowRect.centerY();
                tmp.set(cx - rowRect.width() * scale / 2, cy - rowRect.height() * scale / 2,
                        cx + rowRect.width() * scale / 2, cy + rowRect.height() * scale / 2);
                glassRow.setColor(Ui.alpha(0x2EFFFFFF, 1 - focus));
                canvas.drawRoundRect(rowRect, radius, radius, glassRow);
                tmp.offset(0, 10 * k * focus);
                shadow.setColor(Ui.alpha(0xFF000000, 0.4f * focus));
                canvas.drawRoundRect(tmp, radius * scale, radius * scale, shadow);
                tmp.offset(0, -10 * k * focus);
                white.setAlpha(Math.round(255 * focus));
                canvas.drawRoundRect(tmp, radius * scale, radius * scale, white);
                rowRect.set(tmp);
            }
            return focus;
        }

        private static int blend(int from, int to, float fraction) {
            int a = from >>> 24, r = (from >> 16) & 255, g = (from >> 8) & 255, b = from & 255;
            int aa = to >>> 24, rr = (to >> 16) & 255, gg = (to >> 8) & 255, bb = to & 255;
            return (Math.round(a + (aa - a) * fraction) << 24) | (Math.round(r + (rr - r) * fraction) << 16)
                    | (Math.round(g + (gg - g) * fraction) << 8) | Math.round(b + (bb - b) * fraction);
        }

        private static String versionName(Context context) {
            try { return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; }
            catch (PackageManager.NameNotFoundException unavailable) { return ""; }
        }
    }
}
