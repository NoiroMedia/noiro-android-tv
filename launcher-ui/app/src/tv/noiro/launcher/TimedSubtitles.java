package tv.noiro.launcher;

import java.util.*;
import java.util.regex.*;

/** Bounded plain-text WebVTT/SRT captions, preserving source timings. */
final class TimedSubtitles {
    static final class Cue {
        final long start, end; final String text;
        Cue(long start, long end, String text) { this.start=start; this.end=end; this.text=text; }
    }
    private static final Pattern TIMING = Pattern.compile("^((?:\\d{1,3}:)?\\d{2}:\\d{2}[.,]\\d{3})\\s*-->\\s*((?:\\d{1,3}:)?\\d{2}:\\d{2}[.,]\\d{3}).*$");
    private static long time(String value) {
        String[] fields=value.replace(',', '.').split(":");
        double seconds=0; for(String field:fields) seconds=seconds*60+Double.parseDouble(field);
        return Math.round(seconds*1000);
    }
    static List<Cue> parse(String input) {
        if(input.length()>2*1024*1024) throw new IllegalArgumentException("subtitle_too_large");
        List<Cue> result=new ArrayList<>(); String[] lines=input.replace("\r", "").split("\n");
        for(int i=0;i<lines.length && result.size()<20000;i++) {
            Matcher m=TIMING.matcher(lines[i].trim()); if(!m.matches()) continue;
            long start=time(m.group(1)),end=time(m.group(2)); StringBuilder text=new StringBuilder();
            while(i+1<lines.length && !lines[i+1].trim().isEmpty()) { if(text.length()>0) text.append('\n'); text.append(lines[++i]); }
            String plain=text.toString().replaceAll("<[^>]*>", "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ");
            if(end>start && !plain.isEmpty()) result.add(new Cue(start,end,plain));
        }
        result.sort(Comparator.comparingLong(c->c.start)); return Collections.unmodifiableList(result);
    }
    static List<Cue> prefer(List<Cue> preferred, List<Cue> fallback) {
        if (fallback.isEmpty()) return preferred;
        if (preferred.isEmpty()) return fallback;
        Map<Long, Set<Long>> covered = new HashMap<>();
        for (Cue cue : preferred) covered.computeIfAbsent(cue.start, ignored -> new HashSet<>()).add(cue.end);
        List<Cue> result = new ArrayList<>(preferred);
        for (Cue cue : fallback) {
            Set<Long> endings = covered.get(cue.start);
            if (endings == null || !endings.contains(cue.end)) result.add(cue);
        }
        result.sort(Comparator.comparingLong(cue -> cue.start));
        return result;
    }

    static String at(List<Cue> cues,long position) {
        int lo=0,hi=cues.size()-1,found=-1;
        while(lo<=hi){int mid=(lo+hi)>>>1;if(cues.get(mid).start<=position){found=mid;lo=mid+1;}else hi=mid-1;}
        return found>=0 && position<cues.get(found).end ? cues.get(found).text : "";
    }
}
