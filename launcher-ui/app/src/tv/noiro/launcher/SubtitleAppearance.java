package tv.noiro.launcher;

import android.text.Layout;
import androidx.media3.common.text.Cue;
import androidx.media3.common.util.UnstableApi;
import java.util.ArrayList;
import java.util.List;

/** One renderer and one style for all text subtitles. Bitmap lettering remains part of its image. */
@UnstableApi
final class SubtitleAppearance {
    static void apply(StyledSubtitleView view, SubtitlePreferences p, float scale) {
        view.setAppearance(p, scale);
        view.setBottomPaddingFraction(p.bottomFraction());
    }
    static List<Cue> cues(List<Cue> input) {
        List<Cue> result = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (Cue cue : input) {
            if (cue.bitmap != null) { result.add(cue); continue; }
            if (cue.text == null || cue.text.length() == 0) continue;
            if (text.length() > 0) text.append('\n');
            text.append(cue.text);
        }
        // Rebuild text cues so authored size, color and line positions cannot override the saved style.
        if (text.length() > 0) result.add(new Cue.Builder().setText(text.toString())
                .setTextAlignment(Layout.Alignment.ALIGN_CENTER).build());
        return result;
    }
}
