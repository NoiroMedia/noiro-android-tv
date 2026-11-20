package tv.noiro.launcher;

import java.util.*;

/** Owner binding and three-way conflict rules shared by cloud outbox operations. */
final class PlayerCloudPolicy {
    static boolean sameBinding(String queued,String current){return queued!=null&&!queued.isEmpty()&&queued.equals(current);}
    static boolean equal(Object a,Object b){return a instanceof Number&&b instanceof Number?Double.compare(((Number)a).doubleValue(),((Number)b).doubleValue())==0:Objects.equals(a,b);}
    static Set<String> conflicts(Map<String,Object> base,Map<String,Object> changes,Map<String,Object> remote){
        Set<String> out=new HashSet<>();for(String key:changes.keySet())if(!equal(remote.get(key),changes.get(key))&&(!base.containsKey(key)||!equal(remote.get(key),base.get(key))))out.add(key);return out;
    }
    static List<PlayerSessionPolicy.Segment> merge(List<PlayerSessionPolicy.Segment> base,List<PlayerSessionPolicy.Segment> changes,List<PlayerSessionPolicy.Segment> remote){
        List<PlayerSessionPolicy.Segment> out=new ArrayList<>(remote);
        for(String kind:new String[]{"intro","recap","credits","preview"}){
            Set<String> before=keys(base,kind),local=keys(changes,kind),server=keys(remote,kind);
            if(local.equals(before))continue;
            if(!server.equals(before)&&!server.equals(local))return null;
            out.removeIf(s->s.kind.equals(kind));for(PlayerSessionPolicy.Segment s:changes)if(s.kind.equals(kind))out.add(s);
        }
        if(out.size()>16)return null;out.sort(Comparator.comparingLong(s->s.start));return out;
    }
    /** Playback projects only independent queued kinds; conflicting copies stay in the outbox. */
    static List<PlayerSessionPolicy.Segment> projectSegments(List<PlayerSessionPolicy.Segment> base,List<PlayerSessionPolicy.Segment> changes,List<PlayerSessionPolicy.Segment> remote){
        List<PlayerSessionPolicy.Segment> out=new ArrayList<>(remote);
        for(String kind:new String[]{"intro","recap","credits","preview"}){
            Set<String> before=keys(base,kind),local=keys(changes,kind),server=keys(remote,kind);
            if(local.equals(before)||!server.equals(before)&&!server.equals(local))continue;
            out.removeIf(s->s.kind.equals(kind));for(PlayerSessionPolicy.Segment s:changes)if(s.kind.equals(kind))out.add(s);
        }
        if(out.size()>16)return new ArrayList<>(remote);out.sort(Comparator.comparingLong(s->s.start));return out;
    }
    static boolean acceptsRevision(String cachedBinding,long cachedRevision,String incomingBinding,long incomingRevision){
        return sameBinding(incomingBinding,incomingBinding)&&(!sameBinding(cachedBinding,incomingBinding)||incomingRevision>=cachedRevision);
    }
    static List<PlayerSessionPolicy.Segment> retainBaseForUnchangedKinds(List<PlayerSessionPolicy.Segment> originalBase,List<PlayerSessionPolicy.Segment> previousEdit,List<PlayerSessionPolicy.Segment> newEdit,List<PlayerSessionPolicy.Segment> latestRemote){
        List<PlayerSessionPolicy.Segment> out=new ArrayList<>();for(String kind:new String[]{"intro","recap","credits","preview"}){List<PlayerSessionPolicy.Segment> source=keys(previousEdit,kind).equals(keys(newEdit,kind))?originalBase:latestRemote;for(PlayerSessionPolicy.Segment s:source)if(s.kind.equals(kind))out.add(s);}return out;
    }
    /** Only our deliberately changed kinds can advance an in-flight newer draft's base. */
    static List<PlayerSessionPolicy.Segment> acknowledgedBase(List<PlayerSessionPolicy.Segment> originalBase,List<PlayerSessionPolicy.Segment> originalEdit,List<PlayerSessionPolicy.Segment> latestBase,List<PlayerSessionPolicy.Segment> exactSent){
        List<PlayerSessionPolicy.Segment> out=new ArrayList<>();
        for(String kind:new String[]{"intro","recap","credits","preview"}){
            boolean ours=!keys(originalBase,kind).equals(keys(originalEdit,kind));
            List<PlayerSessionPolicy.Segment> source=ours&&keys(latestBase,kind).equals(keys(originalBase,kind))?exactSent:latestBase;
            for(PlayerSessionPolicy.Segment s:source)if(s.kind.equals(kind))out.add(s);
        }
        return out;
    }
    private static Set<String> keys(List<PlayerSessionPolicy.Segment> values,String kind){Set<String> out=new HashSet<>();for(PlayerSessionPolicy.Segment s:values)if(s.kind.equals(kind))out.add(s.key());return out;}
}
