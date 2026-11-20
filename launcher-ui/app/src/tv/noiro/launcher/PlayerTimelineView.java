package tv.noiro.launcher;

import android.content.Context;
import android.graphics.*;
import android.util.AttributeSet;
import androidx.media3.ui.DefaultTimeBar;
import java.util.*;

/** Media3 seeking plus actual chapter/segment overlays and optional decorative progress styles. */
public final class PlayerTimelineView extends DefaultTimeBar {
    static final String[] STYLES={"Classic","Gradient Sweep","Breathing Glow","Wave","Heartbeat","Ripple","Beads","Equalizer","Minimal","Neon Comet","Liquid","Comet","Runner","Spectrum"};
    private final Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<PlayerSessionPolicy.Chapter> chapters=Collections.emptyList();
    private List<PlayerSessionPolicy.Segment> segments=Collections.emptyList();
    private PlayerSessionPolicy.Segment editing;
    private long duration,position;
    private int style;
    private boolean playing;
    public PlayerTimelineView(Context c,AttributeSet attrs){super(c,attrs);}
    @Override public void setDuration(long value){duration=value;super.setDuration(value);}
    @Override public void setPosition(long value){position=value;super.setPosition(value);}
    void decorate(List<PlayerSessionPolicy.Chapter> chapters,List<PlayerSessionPolicy.Segment> segments,PlayerSessionPolicy.Segment editing,int style,boolean playing){
        this.chapters=chapters;this.segments=segments;this.editing=editing;this.style=style>=0&&style<STYLES.length?style:0;this.playing=playing;invalidate();
    }
    @Override public void onDraw(Canvas canvas){
        super.onDraw(canvas);if(duration<=0)return;
        float dp=getResources().getDisplayMetrics().density,left=getPaddingLeft()+8*dp,right=getWidth()-getPaddingRight()-8*dp,y=getHeight()/2f;
        float width=Math.max(0,right-left),head=left+width*Math.max(0,Math.min(position,duration))/(float)duration;
        ink.setShader(null);ink.setStyle(Paint.Style.FILL);ink.setStrokeWidth(2*dp);
        float phase=(android.os.SystemClock.elapsedRealtime()%2000)/2000f;
        if(style>0){
            ink.setColor(0xFF8490FF);
            if(style==1||style==9||style==11)ink.setShader(new LinearGradient(left,y,Math.max(left+1,head),y,0xFF665BFF,0xFF70F1FF,Shader.TileMode.CLAMP));
            if(style==2)ink.setAlpha(130+(int)(100*Math.abs(Math.sin(phase*Math.PI))));
            if(style==8)canvas.drawRect(left,y-dp,head,y+dp,ink);
            else if(style==3||style==4||style==10){
                Path path=new Path();path.moveTo(left,y);
                for(float x=left;x<=head;x+=2*dp){float wave=(float)Math.sin((x-left)/(style==4?11:24)/dp+phase*6.28)*3*dp;if(style==4&&Math.abs(wave)<2*dp)wave=0;path.lineTo(x,y+wave);}
                ink.setStyle(Paint.Style.STROKE);canvas.drawPath(path,ink);ink.setStyle(Paint.Style.FILL);
            }else if(style==6||style==7||style==13){
                for(float x=left;x<head;x+=8*dp){if(style==13)ink.setColor(Color.HSVToColor(new float[]{360*(x-left)/Math.max(1,width),.65f,1}));
                    float h=style==7?(2+4*(float)Math.abs(Math.sin((x-left)/dp+phase*6.28)))*dp:2*dp;
                    if(style==6)canvas.drawCircle(x,y,2*dp,ink);else canvas.drawRect(x,y-h,Math.min(x+5*dp,head),y+h,ink);}
            }else canvas.drawRoundRect(left,y-2*dp,head,y+2*dp,2*dp,2*dp,ink);
            if(style==5){ink.setStyle(Paint.Style.STROKE);canvas.drawCircle(head,y,(3+phase*6)*dp,ink);ink.setStyle(Paint.Style.FILL);}
            if(style==9||style==11||style==12)canvas.drawCircle(head,y,(style==12?4:3)*dp,ink);
            ink.setShader(null);ink.setAlpha(255);
        }
        for(PlayerSessionPolicy.Segment s:segments){ink.setColor(color(s.kind));canvas.drawRect(at(s.start,left,width),y-3*dp,at(s.end,left,width),y+3*dp,ink);}
        ink.setColor(Color.WHITE);ink.setStrokeWidth(dp);
        for(PlayerSessionPolicy.Chapter c:chapters)if(c.start>1000&&c.start<duration-5000){float x=at(c.start,left,width);canvas.drawLine(x,y-5*dp,x,y+5*dp,ink);}
        if(editing!=null){ink.setColor(0xFFFFEF87);ink.setStrokeWidth(2*dp);for(long boundary:new long[]{editing.start,editing.end}){float x=at(boundary,left,width);canvas.drawLine(x,y-10*dp,x,y+10*dp,ink);}}
        if(style>1&&playing&&isShown())postInvalidateDelayed(80);
    }
    private float at(long time,float left,float width){return left+width*Math.max(0,Math.min(time,duration))/(float)duration;}
    private int color(String kind){return kind.equals("intro")?0xCC00D5EF:kind.equals("recap")?0xCCE8D44A:kind.equals("credits")?0xCCBA77FF:0xCCFF9F43;}
}
