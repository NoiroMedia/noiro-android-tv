package tv.noiro.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.util.*;

/** Durable private markers. No source URLs, headers or credentials are stored. */
final class PlayerSegmentStore {
    static String key(AccountState owner,String video) {
        try {
            byte[] bytes=java.security.MessageDigest.getInstance("SHA-256").digest((owner.uid+"\n"+video).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder key=new StringBuilder();for(byte b:bytes)key.append(String.format(Locale.US,"%02x",b&255));return key.toString();
        }catch(Exception impossible){throw new IllegalStateException();}
    }
    static List<PlayerSessionPolicy.Segment> load(Context c,AccountState owner,String video,long duration) {
        List<PlayerSessionPolicy.Segment> out=new ArrayList<>();
        try {
            JSONObject record=new JSONObject(c.getSharedPreferences("player-segments",0).getString(key(owner,video),"{}"));
            if(duration<=0||Math.abs(record.optLong("durationMs")-duration)>2000)return out;
            JSONArray values=record.optJSONArray("segments");
            for(int i=0;values!=null&&i<Math.min(16,values.length());i++){JSONObject v=values.getJSONObject(i);out.add(new PlayerSessionPolicy.Segment(v.getString("kind"),v.getLong("startMs"),v.getLong("endMs")));}
        }catch(Exception invalid){ }
        return out;
    }
    static boolean save(Context c,AccountState owner,String video,long duration,List<PlayerSessionPolicy.Segment> segments) {
        if(!NoiroAccount.current().owns(owner.authKey,owner.uid))return false;
        if(duration<=0||segments.size()>16)return false;
        try {
            JSONArray values=new JSONArray();for(PlayerSessionPolicy.Segment s:segments)values.put(new JSONObject().put("kind",s.kind).put("startMs",s.start).put("endMs",s.end));
            return c.getSharedPreferences("player-segments",0).edit().putString(key(owner,video),new JSONObject().put("durationMs",duration).put("segments",values).toString()).commit();
        }catch(Exception unavailable){return false;}
    }
    static List<PlayerSessionPolicy.Segment> publicSegments(String video) throws Exception {
        String[] parts=video.split(":");if(!parts[0].matches("tt[0-9]{5,12}"))return Collections.emptyList();
        String query="?imdb_id="+parts[0]+(parts.length==3?"&season="+Integer.parseInt(parts[1])+"&episode="+Integer.parseInt(parts[2]):"&is_movie=true");
        // Public IntroDB reads only. The retired Noiro edge/community routes are never called.
        JSONObject response=AccountHttp.call("https://api.introdb.app/segments"+query,null,"",4000);
        List<PlayerSessionPolicy.Segment> out=new ArrayList<>();
        for(String key:new String[]{"intro","recap","outro","post_credits"}) {
            JSONObject v=response.optJSONObject(key);if(v==null)continue;
            long start=v.optLong("start_ms",-1),end=v.optLong("end_ms",-1);
            if(start>=0&&end>start)out.add(new PlayerSessionPolicy.Segment(key.equals("outro")?"credits":key.equals("post_credits")?"preview":key,start,end));
        }
        return out;
    }
}
