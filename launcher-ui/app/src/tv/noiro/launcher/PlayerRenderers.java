package tv.noiro.launcher;

import android.content.Context;
import android.os.Looper;
import androidx.media3.exoplayer.*;
import androidx.media3.exoplayer.text.TextOutput;
import java.util.ArrayList;
import java.util.function.LongSupplier;

/** Standard Media3 factory with an isolated text-offset wrapper. */
final class PlayerRenderers extends DefaultRenderersFactory {
    private final LongSupplier subtitleDelayUs;
    PlayerRenderers(Context context,LongSupplier delay){super(context);subtitleDelayUs=delay;}
    @Override protected void buildTextRenderers(Context context,TextOutput output,Looper looper,int mode,ArrayList<Renderer> renderers){
        int first=renderers.size();super.buildTextRenderers(context,output,looper,mode,renderers);
        for(int i=first;i<renderers.size();i++)renderers.set(i,new PlayerTextRenderer(renderers.get(i),subtitleDelayUs));
    }
}
