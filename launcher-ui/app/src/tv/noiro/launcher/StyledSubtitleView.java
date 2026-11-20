package tv.noiro.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;
import android.widget.FrameLayout;
import androidx.media3.common.text.Cue;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.SubtitleView;
import java.util.ArrayList;
import java.util.List;

/** Shared text renderer with adjustable outline/shadow; Media3 retains bitmap cue rendering. */
@UnstableApi
final class StyledSubtitleView extends FrameLayout {
    private final SubtitleView bitmaps;
    private final TextLayer text;
    StyledSubtitleView(Context context) {
        super(context);
        bitmaps = new SubtitleView(context); addView(bitmaps, new LayoutParams(-1, -1));
        text = new TextLayer(context); addView(text, new LayoutParams(-1, -1));
    }
    void setAppearance(SubtitlePreferences prefs, float scale) { text.prefs = prefs; text.scale = scale; text.rebuild(); }
    void setBottomPaddingFraction(float fraction) { text.bottom = fraction; text.invalidate(); bitmaps.setBottomPaddingFraction(fraction); }
    void setCues(List<Cue> cues) {
        List<Cue> images = new ArrayList<>(); StringBuilder lines = new StringBuilder();
        for (Cue cue : cues) {
            if (cue.bitmap != null) images.add(cue);
            else if (cue.text != null && cue.text.length() > 0) { if (lines.length() > 0) lines.append('\n'); lines.append(cue.text); }
        }
        bitmaps.setCues(images);
        String value = lines.toString();
        if (!value.equals(text.value)) { text.value = value; text.rebuild(); }
    }
    private static final class TextLayer extends View {
        final TextPaint ink = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        final Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
        SubtitlePreferences prefs;
        float scale = 1, bottom = 110f / 1080f;
        String value = "";
        StaticLayout layout;
        TextLayer(Context context) { super(context); setLayerType(View.LAYER_TYPE_SOFTWARE, null); }
        void rebuild() {
            if (prefs == null || getWidth() <= 0 || value.isEmpty()) { layout = null; invalidate(); return; }
            ink.setTypeface(prefs.font==1?android.graphics.Typeface.SERIF:Ui.medium); ink.setTextSize(SubtitlePreferences.TEXT_SIZES[prefs.size] * scale * prefs.fineScale);
            ink.setStyle(Paint.Style.FILL); ink.clearShadowLayer(); ink.setColor(SubtitlePreferences.TEXT_COLORS[prefs.color]);
            // Include font padding so uppercase and combining Latin accents are never clipped.
            layout = StaticLayout.Builder.obtain(value, 0, value.length(), ink, Math.max(1, getWidth() - Math.round(32 * scale)))
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(true).build();
            invalidate();
        }
        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { rebuild(); }
        @Override protected void onDraw(Canvas canvas) {
            if (layout == null || prefs == null) return;
            float x = (getWidth() - layout.getWidth()) / 2f;
            float y = Math.max(0, getHeight() * (1 - bottom) - layout.getHeight());
            canvas.save(); canvas.translate(x, y);
            box.setColor(SubtitlePreferences.BACKGROUND_COLORS[prefs.background]);
            if ((box.getColor() >>> 24) != 0) for (int i = 0; i < layout.getLineCount(); i++) {
                canvas.drawRect(layout.getLineLeft(i) - 4 * scale, layout.getLineTop(i),
                        layout.getLineRight(i) + 4 * scale, layout.getLineBottom(i), box);
            }
            boolean outline = prefs.edge == 1 || prefs.edge == 3;
            boolean shadow = prefs.edge == 0 || prefs.edge == 3;
            ink.clearShadowLayer();
            if (shadow) {
                float radius = SubtitlePreferences.SHADOW_RADII[prefs.shadowSize] * scale;
                ink.setShadowLayer(radius, radius / 2, radius / 2, SubtitlePreferences.EDGE_INKS[prefs.shadowColor]);
            }
            if (outline) {
                ink.setStyle(Paint.Style.STROKE); ink.setStrokeJoin(Paint.Join.ROUND);
                ink.setStrokeWidth(SubtitlePreferences.BORDER_WIDTHS[prefs.borderSize] * scale);
                ink.setColor(SubtitlePreferences.EDGE_INKS[prefs.borderColor]); layout.draw(canvas);
                ink.clearShadowLayer();
            }
            ink.setStyle(Paint.Style.FILL); ink.setColor(SubtitlePreferences.TEXT_COLORS[prefs.color]);
            layout.draw(canvas); ink.clearShadowLayer(); canvas.restore();
        }
    }
}
