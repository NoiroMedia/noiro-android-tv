package tv.noiro.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;

/** Device preferences shared by embedded and add-on subtitle rendering. */
final class SubtitlePreferences {
    static final String[] SIZES = {"Small", "Medium", "Default", "Large", "Extra large", "Largest"};
    static final float[] TEXT_SIZES = {22, 26, 30, 36, 42, 48};
    static final String[] COLORS = {"White", "Yellow", "Cyan", "Green", "Orange"};
    static final int[] TEXT_COLORS = {0xFFFFFFFF, 0xFFFFFF00, 0xFF00FFFF, 0xFF80FF80, 0xFFFF8000};
    static final String[] BACKGROUNDS = {"None", "Black · 50%", "Black · 75%", "Black"};
    static final int[] BACKGROUND_COLORS = {0x00000000, 0x80000000, 0xBF000000, 0xFF000000};
    static final String[] EDGES = {"Shadow", "Outline", "None", "Outline + shadow"};
    static final String[] BORDER_SIZES = {"Thin", "Small", "Standard", "Thick"};
    static final float[] BORDER_WIDTHS = {1, 2, 4, 6};
    static final String[] EDGE_COLORS = {"Black", "White", "Gray", "Blue", "Orange"};
    static final int[] EDGE_INKS = {0xFF000000, 0xFFFFFFFF, 0xFF404040, 0xFF1976D2, 0xFFFF8000};
    static final String[] SHADOW_SIZES = {"Small", "Medium", "Large"};
    static final float[] SHADOW_RADII = {2, 4, 6};
    static final String[] POSITIONS = {"Bottom", "Standard", "Raised", "High"};
    static final float[] BOTTOM_FRACTIONS = {0.05f, 110f / 1080f, 0.16f, 0.23f};
    int size = 2, color, background, edge, position = 1;
    int borderSize = 2, borderColor, shadowSize = 1, shadowColor;
    int positionMargin = -1;
    List<String> languages = new ArrayList<>();
    boolean autoGemini;
    String translationTarget = "";
    int font;
    float fineScale=1f;

    private static int bounded(int value, int count, int fallback) {
        return value >= 0 && value < count ? value : fallback;
    }
    static SubtitlePreferences load(Context context) {
        SharedPreferences saved = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        SubtitlePreferences p = new SubtitlePreferences();
        p.size = bounded(saved.getInt("subtitle_size", 2), SIZES.length, 2);
        p.color = bounded(saved.getInt("subtitle_color", 0), COLORS.length, 0);
        p.background = bounded(saved.getInt("subtitle_background", 0), BACKGROUNDS.length, 0);
        p.edge = bounded(saved.getInt("subtitle_edge", 0), EDGES.length, 0);
        p.position = bounded(saved.getInt("subtitle_position", 1), POSITIONS.length, 1);
        p.positionMargin = saved.getInt("subtitle_position_margin", -1);
        if (p.positionMargin < -1 || p.positionMargin > 864) p.positionMargin = -1;
        p.borderSize = bounded(saved.getInt("subtitle_border_size", 2), BORDER_SIZES.length, 2);
        p.borderColor = bounded(saved.getInt("subtitle_border_color", 0), EDGE_COLORS.length, 0);
        p.shadowSize = bounded(saved.getInt("subtitle_shadow_size", 1), SHADOW_SIZES.length, 1);
        p.shadowColor = bounded(saved.getInt("subtitle_shadow_color", 0), EDGE_COLORS.length, 0);
        p.languages = SubtitleSelection.languages(saved.getString("subtitle_languages", ""));
        p.autoGemini = saved.getBoolean("subtitle_auto_gemini", false);
        p.translationTarget = SubtitleSelection.normalize(saved.getString("subtitle_translation_target", ""));
        p.font=bounded(saved.getInt("subtitle_font",0),2,0);
        p.fineScale=PlayerSessionPolicy.scale(saved.getFloat("subtitle_fine_scale",1f));
        return p;
    }
    void save(Context context) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                .putInt("subtitle_size", size).putInt("subtitle_color", color)
                .putInt("subtitle_background", background).putInt("subtitle_edge", edge)
                .putInt("subtitle_position", position)
                .putInt("subtitle_position_margin", positionMargin)
                .putInt("subtitle_border_size", borderSize).putInt("subtitle_border_color", borderColor)
                .putInt("subtitle_shadow_size", shadowSize).putInt("subtitle_shadow_color", shadowColor)
                .putString("subtitle_languages", String.join(",", languages))
                .putString("subtitle_translation_target", translationTarget)
                .putBoolean("subtitle_auto_gemini", autoGemini).putInt("subtitle_font",font).putFloat("subtitle_fine_scale",fineScale).apply();
    }
    float bottomFraction() { return positionMargin < 0 ? BOTTOM_FRACTIONS[position] : positionMargin / 1080f; }
    String positionLabel() { return positionMargin < 0 ? POSITIONS[position] : "Custom · " + Math.round(bottomFraction() * 100) + "%"; }
    void move(int pixels) { positionMargin = Math.max(0, Math.min(864, Math.round(bottomFraction() * 1080) + pixels)); }
    void resetAppearance() { size = 2; color = background = edge = borderColor = shadowColor = 0; position = shadowSize = 1; borderSize = 2; positionMargin = -1; }
}
