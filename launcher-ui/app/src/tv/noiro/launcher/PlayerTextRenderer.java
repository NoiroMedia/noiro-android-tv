package tv.noiro.launcher;

import androidx.media3.exoplayer.*;
import androidx.media3.exoplayer.source.*;
import androidx.media3.common.*;
import java.util.function.LongSupplier;

/** Shift text presentation only. Audio/video clocks, streams and lifecycle calls pass through. */
final class PlayerTextRenderer implements Renderer {
    private final Renderer delegate;private final LongSupplier delayUs;
    PlayerTextRenderer(Renderer delegate,LongSupplier delayUs){this.delegate=delegate;this.delayUs=delayUs;}
    private long at(long position){return Math.max(0,position-delayUs.getAsLong());}
    public void render(long position,long elapsed)throws ExoPlaybackException{delegate.render(at(position),elapsed);}
    public long getDurationToProgressUs(long position,long elapsed){return delegate.getDurationToProgressUs(at(position),elapsed);}
    public String getName(){return delegate.getName();}
    public int getTrackType(){return delegate.getTrackType();}
    public RendererCapabilities getCapabilities(){return delegate.getCapabilities();}
    public void init(int index,androidx.media3.exoplayer.analytics.PlayerId id,androidx.media3.common.util.Clock clock){delegate.init(index,id,clock);}
    public MediaClock getMediaClock(){return delegate.getMediaClock();}
    public int getState(){return delegate.getState();}
    public void enable(RendererConfiguration config,Format[] formats,SampleStream stream,long position,boolean joining,boolean start,long offset,long startPosition,MediaSource.MediaPeriodId period)throws ExoPlaybackException{delegate.enable(config,formats,stream,position,joining,start,offset,startPosition,period);}
    public void start()throws ExoPlaybackException{delegate.start();}
    public void replaceStream(Format[] formats,SampleStream stream,long start,long offset,MediaSource.MediaPeriodId period)throws ExoPlaybackException{delegate.replaceStream(formats,stream,start,offset,period);}
    public SampleStream getStream(){return delegate.getStream();}
    public boolean hasReadStreamToEnd(){return delegate.hasReadStreamToEnd();}
    public long getReadingPositionUs(){return delegate.getReadingPositionUs();}
    public void setCurrentStreamFinal(){delegate.setCurrentStreamFinal();}
    public boolean isCurrentStreamFinal(){return delegate.isCurrentStreamFinal();}
    public void maybeThrowStreamError()throws java.io.IOException{delegate.maybeThrowStreamError();}
    public void resetPosition(long position,boolean keyFrame)throws ExoPlaybackException{delegate.resetPosition(position,keyFrame);}
    public boolean supportsResetPositionWithoutKeyFrameReset(long position){return delegate.supportsResetPositionWithoutKeyFrameReset(position);}
    public void setPlaybackSpeed(float speed,float effective)throws ExoPlaybackException{delegate.setPlaybackSpeed(speed,effective);}
    public void enableMayRenderStartOfStream(){delegate.enableMayRenderStartOfStream();}
    public void setTimeline(Timeline timeline){delegate.setTimeline(timeline);}
    public boolean isReady(){return delegate.isReady();}
    public boolean isEnded(){return delegate.isEnded();}
    public void stop(){delegate.stop();}
    public void disable(){delegate.disable();}
    public void reset(){delegate.reset();}
    public void release(){delegate.release();}
    public void handleMessage(int type,Object data)throws ExoPlaybackException{delegate.handleMessage(type,data);}
}
