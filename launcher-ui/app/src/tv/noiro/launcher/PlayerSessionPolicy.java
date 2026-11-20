package tv.noiro.launcher;

import java.util.*;

/** Playback decisions independent of Android, accounts, streams and network transport. */
final class PlayerSessionPolicy {
    /** Explicit choices invalidate the old modal's automatic episode transition before dismissal. */
    static final class Transitions {
        private Object identity;private long generation;private boolean pending;
        void reset(Object next){identity=next;generation++;pending=false;}
        long capture(Object expected){return identity==expected?generation:-1;}
        boolean current(Object expected,long ticket){return identity==expected&&ticket>=0&&ticket==generation;}
        long begin(Object expected,long menuTicket){if(!current(expected,menuTicket))return -1;pending=true;return ++generation;}
        void complete(Object expected,long actionTicket){if(current(expected,actionTicket))pending=false;}
        boolean mayAdvance(Object expected,long modalTicket){return current(expected,modalTicket)&&!pending;}
        boolean pending(){return pending;}
    }
    static final class Episode {
        final String id, title; final int season, number; final long released;
        Episode(String id, String title, int season, int number, long released) {
            this.id=id; this.title=title; this.season=season; this.number=number; this.released=released;
        }
        boolean aired(long now) { return released >= 0 && released <= now; }
        String label() { return String.format(Locale.US,"S%02d E%02d",season,number)+(title.isEmpty()?"":" · "+title); }
    }
    static Episode adjacent(List<Episode> episodes,String current,int direction,long now) {
        for(int i=0;i<episodes.size();i++) if(episodes.get(i).id.equals(current)) {
            int target=i+(direction<0?-1:1);
            if(target<0||target>=episodes.size())return null;
            Episode next=episodes.get(target);return next.aired(now)?next:null;
        }
        return null;
    }
    static final class Chapter {
        final String title; final long start;
        Chapter(String title,long start){this.title=title;this.start=start;}
    }
    static final class Segment {
        final String kind; final long start,end;
        Segment(String kind,long start,long end){this.kind=kind;this.start=start;this.end=end;}
        String key(){return kind+":"+start+":"+end;}
        String label(){return "Skip "+Character.toUpperCase(kind.charAt(0))+kind.substring(1);}
    }
    static boolean valid(Segment s,long duration) {
        if(duration<=0||s.start<0||s.end>duration||s.end-s.start<5000)return false;
        if(s.kind.equals("intro")||s.kind.equals("recap"))return s.end-s.start<=1_200_000&&s.end<=duration*.6;
        return (s.kind.equals("credits")||s.kind.equals("preview"))&&s.start>=duration*.5;
    }
    static List<Segment> chapterSegments(List<Chapter> chapters,long duration) {
        List<Segment> out=new ArrayList<>();
        for(int i=0;i<chapters.size();i++) {
            Chapter c=chapters.get(i);String name=c.title.toLowerCase(Locale.ROOT),kind=null;
            if(name.contains("opening")||name.contains("intro")||word(name,"op"))kind="intro";
            else if(name.contains("recap")||name.contains("previously"))kind="recap";
            else if(name.contains("ending")||name.contains("outro")||name.contains("credits")||name.contains("closing")||word(name,"ed"))kind="credits";
            else if(name.contains("preview")||name.contains("next episode"))kind="preview";
            if(kind!=null){Segment s=new Segment(kind,c.start,i+1<chapters.size()?chapters.get(i+1).start:duration);if(valid(s,duration))out.add(s);}
        }
        return out;
    }
    private static boolean word(String name,String token){return java.util.regex.Pattern.compile("(?:^|[^\\p{L}])"+token+"(?:$|[^\\p{L}])").matcher(name).find();}
    static int resolution(String text) {
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?i)(?<![0-9])(2160|1080|720|576|480|360)p?(?![0-9])").matcher(text==null?"":text);
        if(m.find())return Integer.parseInt(m.group(1));
        return text!=null&&java.util.regex.Pattern.compile("(?i)\\b(?:4k|uhd)\\b").matcher(text).find()?2160:0;
    }
    static long subtitlePosition(long position,long delay){return Math.max(0,position-delay);}
    static long offset(long value){return Math.max(-60_000,Math.min(60_000,value));}
    static float scale(float value){return Float.isFinite(value)?Math.max(.5f,Math.min(2f,value)):1f;}
    static int seekStep(int value){return value==5||value==10||value==15||value==30||value==60?value:10;}
    static boolean canAdvance(boolean live,boolean enabled,boolean dismissed,boolean endSleep,boolean sleepPaused,int panels){return !live&&enabled&&!dismissed&&!endSleep&&!sleepPaused&&panels==0;}
}
