package tv.noiro.launcher;

import org.json.*;
import java.time.*;
import java.util.*;

/** Same Cinemeta roster and immediate-aired-successor rule as website continue-episodes.mjs. */
final class PlayerEpisodes {
    static List<PlayerSessionPolicy.Episode> load(String video) throws Exception {
        String imdb=video.split(":")[0];
        if(!imdb.matches("tt[0-9]{5,12}"))throw new IllegalArgumentException();
        JSONObject response=AccountHttp.call("https://v3-cinemeta.strem.io/meta/series/"+imdb+".json",null,"");
        return fromMetadata(video,response.optJSONObject("meta"));
    }
    static List<PlayerSessionPolicy.Episode> fromMetadata(String video,JSONObject meta) {
        String imdb=video.split(":")[0];
        if(!imdb.matches("tt[0-9]{5,12}"))throw new IllegalArgumentException();
        JSONArray videos=meta==null?null:meta.optJSONArray("videos");
        Map<String,PlayerSessionPolicy.Episode> unique=new HashMap<>();
        for(int i=0;videos!=null&&i<Math.min(2500,videos.length());i++) {
            JSONObject v=videos.optJSONObject(i);if(v==null)continue;
            String id=v.optString("id");if(!UpNextPolicy.valid("series",imdb,id))continue;
            String[] parts=id.split(":");int season=Integer.parseInt(parts[1]),episode=Integer.parseInt(parts[2]);
            // The website excludes specials from ordinary episode succession.
            if(season<1)continue;
            long released=-1;String date=v.optString("released");
            try { released=Instant.parse(date).toEpochMilli(); }
            catch(Exception malformed) { try { released=LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(); } catch(Exception unknown) { } }
            String title=v.optString("title");if(title.isEmpty())title=v.optString("name");
            unique.putIfAbsent(id,new PlayerSessionPolicy.Episode(id,title,season,episode,released));
        }
        List<PlayerSessionPolicy.Episode> out=new ArrayList<>(unique.values());
        out.sort(Comparator.comparingInt((PlayerSessionPolicy.Episode e)->e.season).thenComparingInt(e->e.number));return out;
    }
}
