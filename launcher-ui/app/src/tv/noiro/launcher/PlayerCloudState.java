package tv.noiro.launcher;

import android.content.*;
import android.os.Handler;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** Website-owned settings/private markers with a durable, binding-scoped local outbox. */
final class PlayerCloudState {
    static final String[] STYLES={"classic","gradientSweep","breathingGlow","wave","heartbeat","ripple","beads","equalizer","minimal","neonComet","liquid","comet","runner","spectrum"};
    private final Context context;private final AccountState owner;private final String video,type,scope;private final SharedPreferences saved;
    private final Handler main;private final java.util.function.Consumer<JSONObject> callback;private final ExecutorService io=Executors.newSingleThreadExecutor();
    private volatile boolean closed;private boolean busy,again;private volatile String status="Local settings";
    PlayerCloudState(Context c,AccountState owner,String type,String video,Handler main,java.util.function.Consumer<JSONObject> callback){context=c.getApplicationContext();this.owner=owner;this.type=type;this.video=video;this.main=main;this.callback=callback;saved=c.getSharedPreferences("player-cloud-outbox",0);scope=PlayerSegmentStore.key(owner,owner.authKey);}
    String status(){return status;}
    synchronized JSONObject cached(){if(!current())return null;try{return projectLocal(latestDocument(binding()),binding());}catch(Exception invalid){return null;}}
    // Callback execution reads current cache, not a captured pre-POST GET. Preference and media
    // revisions are independent, so a late media response must not roll back acknowledged prefs.
    private synchronized JSONObject latestDocument(String binding)throws Exception{
        JSONObject value=read("prefs_"+scope);if(!PlayerCloudPolicy.sameBinding(value.optString("accountBinding"),binding))return null;
        JSONObject media=read("state_"+scope+"_"+PlayerSegmentStore.key(owner,video));
        if(binding.equals(media.optString("accountBinding"))&&video.equals(media.optString("videoId")))for(String key:new String[]{"videoId","type","durationMs","segments","segmentsRevision","publicSegments","publicAvailable","episodeNavigation"})if(media.has(key))value.put(key,media.get(key));
        return value;
    }
    private synchronized void rememberPreferences(JSONObject state){
        String binding=state.optString("accountBinding");JSONObject previous=read("prefs_"+scope);
        if(PlayerCloudPolicy.acceptsRevision(previous.optString("accountBinding"),previous.optLong("prefsRevision"),binding,state.optLong("prefsRevision")))saved.edit().putString("prefs_"+scope,state.toString()).commit();
    }
    private synchronized void rememberMedia(JSONObject state){
        if(!video.equals(state.optString("videoId")))return;String key="state_"+scope+"_"+PlayerSegmentStore.key(owner,video);JSONObject previous=read(key);
        if(PlayerCloudPolicy.acceptsRevision(previous.optString("accountBinding"),previous.optLong("segmentsRevision"),state.optString("accountBinding"),state.optLong("segmentsRevision")))saved.edit().putString(key,state.toString()).commit();
    }
    private void publish(String binding){main.post(()->{if(current()&&binding.equals(binding())){JSONObject latest=cached();if(latest!=null)callback.accept(latest);}});}
    private boolean current(){return !closed&&NoiroAccount.ready()&&NoiroAccount.current().owns(owner.authKey,owner.uid);}
    private String binding(){return saved.getString("binding_"+scope,"");}
    private String queueKey(String prefix,String binding){return prefix+scope+"_"+PlayerSegmentStore.key(owner,binding)+(prefix.equals("m_")?"_"+PlayerSegmentStore.key(owner,video):"");}
    private JSONObject read(String key){try{return new JSONObject(saved.getString(key,"{}"));}catch(Exception invalid){return new JSONObject();}}
    private static JSONObject copy(JSONObject value)throws Exception{return new JSONObject(value.toString());}
    static boolean enqueuePreference(Context context,AccountState owner,String name,Object value){
        if(!NoiroAccount.ready()||!owner.signedIn()||!NoiroAccount.current().owns(owner.authKey,owner.uid))return false;
        try{SharedPreferences store=context.getSharedPreferences("player-cloud-outbox",0);String scope=PlayerSegmentStore.key(owner,owner.authKey),binding=store.getString("binding_"+scope,""),key="p_"+scope+"_"+PlayerSegmentStore.key(owner,binding);JSONObject pending=new JSONObject(store.getString(key,"{}")),patch=pending.optJSONObject("preferences"),base=pending.optJSONObject("basePreferences");if(patch==null)patch=new JSONObject();if(base==null)base=new JSONObject();JSONObject latest=new JSONObject(store.getString("prefs_"+scope,"{}")),known=latest.optJSONObject("preferences");base.put(name,known!=null&&binding.equals(latest.optString("accountBinding"))&&known.has(name)?known.get(name):snapshot(context).get(name));patch.put(name,value);pending.put("accountBinding",binding).put("preferences",patch).put("basePreferences",base);return store.edit().putString(key,pending.toString()).commit();}catch(Exception unavailable){return false;}
    }
    synchronized void preference(String name,Object value){if(!current())return;if(enqueuePreference(context,owner,name,value)){status=binding().isEmpty()?"Saved locally · account sync unavailable":"Sync pending";refresh();}else status="Settings saved locally; sync storage unavailable";}
    synchronized void segments(long duration,List<PlayerSessionPolicy.Segment> markers){
        if(!current())return;try{String binding=binding(),key=queueKey("m_",binding);JSONObject cached=read("state_"+scope+"_"+PlayerSegmentStore.key(owner,video));JSONObject previous=read(key);JSONArray before=binding.equals(cached.optString("accountBinding"))?cached.optJSONArray("segments"):null;if(before==null)before=new JSONArray();if(previous.has("segments"))before=jsonSegments(PlayerCloudPolicy.retainBaseForUnchangedKinds(model(previous.optJSONArray("before")),model(previous.optJSONArray("segments")),markers,model(before)));JSONArray values=new JSONArray();for(PlayerSessionPolicy.Segment s:markers)values.put(new JSONObject().put("kind",s.kind).put("startMs",s.start).put("endMs",s.end));JSONObject edit=new JSONObject().put("accountBinding",binding).put("baseRevision",previous.has("baseRevision")?previous.optLong("baseRevision"):binding.equals(cached.optString("accountBinding"))?cached.optLong("segmentsRevision"):0).put("videoId",video).put("type",type).put("durationMs",duration).put("segments",values).put("before",before);if(saved.edit().putString(key,edit.toString()).commit()){status=binding.isEmpty()?"Markers saved locally · account sync unavailable":"Marker sync pending";refresh();}}catch(Exception unavailable){status="Markers saved locally · sync pending";}
    }
    synchronized void refresh(){if(!current())return;if(busy){again=true;return;}busy=true;io.execute(()->{try{sync();}catch(Exception unavailable){status="Saved locally · website sync pending";}finally{synchronized(this){busy=false;if(again&&!closed){again=false;refresh();}}}});}
    void close(){closed=true;io.shutdown();}
    private void sync()throws Exception{
        if(!current())return;status="Syncing with Noiro.tv";String query="?type="+type+"&videoId="+java.net.URLEncoder.encode(video,"UTF-8");JSONObject state=NoiroAccount.playerState("",null,owner);if(!current())return;
        String binding=state.optString("accountBinding");if(binding.isEmpty())return;
        saved.edit().putString("binding_"+scope,binding).commit();rememberPreferences(state);publish(binding);
        String prefsKey=queueKey("p_",binding);JSONObject pending=read(prefsKey),patch=pending.optJSONObject("preferences");
        if(patch!=null&&patch.length()>0&&binding.equals(pending.optString("accountBinding"))){
            for(int attempt=0;attempt<2&&current();attempt++){
                if(!binding.equals(state.optString("accountBinding")))break;
                Set<String> conflicts=PlayerCloudPolicy.conflicts(objectMap(pending.optJSONObject("basePreferences")),objectMap(patch),objectMap(state.optJSONObject("preferences")));
                JSONObject safe=copy(patch);for(String key:conflicts)safe.remove(key);if(!conflicts.isEmpty())status="Settings conflict · local edits kept";if(safe.length()==0)break;
                JSONObject send=state.optLong("prefsRevision")==0?snapshot(context):new JSONObject();for(Iterator<String> keys=safe.keys();keys.hasNext();){String key=keys.next();send.put(key,safe.get(key));}
                try{state=NoiroAccount.playerState("",new JSONObject().put("accountBinding",binding).put("baseRevision",state.optLong("prefsRevision")).put("preferences",send),owner);if(!current()||!binding.equals(state.optString("accountBinding")))return;rememberPreferences(state);ackPreferences(prefsKey,safe,pending.optJSONObject("basePreferences"));publish(binding);break;}
                catch(AccountHttp.Failure error){if(!error.code.equals("revision_conflict"))throw error;state=NoiroAccount.playerState(query,null,owner);}
            }
        }
        if(!current()||!binding.equals(state.optString("accountBinding")))return;
        // Preference POST responses have no video record; refresh the requested record before marker CAS.
        state=NoiroAccount.playerState(query,null,owner);if(!current()||!binding.equals(state.optString("accountBinding")))return;
        rememberMedia(state);rememberPreferences(state);publish(binding);
        String markerKey=queueKey("m_",binding);JSONObject edit=read(markerKey);
        if(edit.has("segments")&&binding.equals(edit.optString("accountBinding"))){
            JSONArray proposed=edit.optJSONArray("segments");long revision=state.optLong("segmentsRevision");
            if(edit.optLong("baseRevision")!=revision){proposed=Math.abs(edit.optLong("durationMs")-state.optLong("durationMs"))>2000&&state.optLong("segmentsRevision")>0?null:merge(edit.optJSONArray("before"),proposed,state.optJSONArray("segments"));}
            if(proposed==null){status="Marker conflict · local edits kept";}
            else try{JSONObject body=copy(edit);body.remove("before");body.put("segments",proposed).put("baseRevision",revision);JSONObject accepted=NoiroAccount.playerState("",body,owner);if(current()&&binding.equals(accepted.optString("accountBinding"))){rememberMedia(accepted);rememberPreferences(accepted);ackSegments(markerKey,edit,proposed);state=accepted;}}
                catch(AccountHttp.Failure error){if(!error.code.equals("revision_conflict"))throw error;status="Marker conflict · local edits kept";}
        }
        if(!current()||!binding.equals(state.optString("accountBinding")))return;
        rememberMedia(state);rememberPreferences(state);

        if(!status.contains("conflict"))status=read(prefsKey).has("preferences")||read(markerKey).has("segments")?"Sync pending":"Synced with Noiro.tv";
        if(!status.contains("conflict")&&(read(queueKey("p_","")).has("preferences")||read(queueKey("m_","")).has("segments")))status=read(queueKey("m_","")).has("segments")?"Marker conflict · unbound local edits kept":"Settings conflict · unbound local edits kept";
        publish(binding);
    }
    private JSONObject projectLocal(JSONObject remote,String binding){
        if(remote==null)return null;
        try{
            JSONObject result=copy(remote),overlay=read(queueKey("p_",binding)).optJSONObject("preferences"),prefs=result.optJSONObject("preferences");
            if(overlay!=null&&prefs!=null)for(Iterator<String> keys=overlay.keys();keys.hasNext();){String key=keys.next();prefs.put(key,overlay.get(key));}
            if(video.equals(result.optString("videoId"))){
                JSONObject markers=read(queueKey("m_",binding));
                if(markers.has("segments")&&binding.equals(markers.optString("accountBinding"))){
                    long remoteDuration=result.optLong("durationMs"),localDuration=markers.optLong("durationMs");
                    if(result.optLong("segmentsRevision")==0||Math.abs(localDuration-remoteDuration)<=2000){
                        result.put("segments",jsonSegments(PlayerCloudPolicy.projectSegments(model(markers.optJSONArray("before")),model(markers.optJSONArray("segments")),model(result.optJSONArray("segments"))))).put("durationMs",localDuration);
                    }
                }
                // The active player must replace legacy/ACKed local shadows with this projection.
                // Unbound and same-kind conflicting edits remain durable for explicit review.
                result.put("privateMarkersProjected",true);
            }
            return result;
        }catch(Exception invalid){return remote;}
    }
    private synchronized void ackPreferences(String key,JSONObject sent,JSONObject originalBase)throws Exception{
        JSONObject pending=read(key),latest=pending.optJSONObject("preferences"),base=pending.optJSONObject("basePreferences");if(latest==null)return;
        if(base==null)base=new JSONObject();
        for(Iterator<String> keys=sent.keys();keys.hasNext();){String name=keys.next();if(!latest.has(name))continue;
            if(PlayerCloudPolicy.equal(latest.get(name),sent.get(name)))latest.remove(name);
            else if(originalBase!=null&&originalBase.has(name)&&base.has(name)&&PlayerCloudPolicy.equal(base.get(name),originalBase.get(name)))base.put(name,sent.get(name));
        }
        if(latest.length()==0)saved.edit().remove(key).commit();else saved.edit().putString(key,pending.put("basePreferences",base).toString()).commit();
    }
    private synchronized void ackSegments(String key,JSONObject original,JSONArray exactSent)throws Exception{
        JSONObject latest=read(key);if(!latest.has("segments"))return;
        if(latest.toString().equals(original.toString())){saved.edit().remove(key).commit();return;}
        if(!PlayerCloudPolicy.sameBinding(original.optString("accountBinding"),latest.optString("accountBinding"))||Math.abs(original.optLong("durationMs")-latest.optLong("durationMs"))>2000)return;
        latest.put("before",jsonSegments(PlayerCloudPolicy.acknowledgedBase(model(original.optJSONArray("before")),model(original.optJSONArray("segments")),model(latest.optJSONArray("before")),model(exactSent))));
        // Keep its global baseRevision: other kinds can still have independent old conflicts.
        // Exact sent values are ours; the POST response may already contain a newer peer write.
        saved.edit().putString(key,latest.toString()).commit();
    }
    private static Map<String,Object> objectMap(JSONObject object){Map<String,Object> map=new HashMap<>();if(object!=null)for(Iterator<String> keys=object.keys();keys.hasNext();){String key=keys.next();map.put(key,object.opt(key));}return map;}
    private static List<PlayerSessionPolicy.Segment> model(JSONArray array){List<PlayerSessionPolicy.Segment> out=new ArrayList<>();for(int i=0;array!=null&&i<Math.min(16,array.length());i++){JSONObject v=array.optJSONObject(i);if(v!=null)out.add(new PlayerSessionPolicy.Segment(v.optString("kind"),v.optLong("startMs"),v.optLong("endMs")));}return out;}
    private static JSONArray jsonSegments(List<PlayerSessionPolicy.Segment> values)throws Exception{JSONArray out=new JSONArray();for(PlayerSessionPolicy.Segment s:values)out.put(new JSONObject().put("kind",s.kind).put("startMs",s.start).put("endMs",s.end));return out;}
    private static JSONArray merge(JSONArray before,JSONArray after,JSONArray remote)throws Exception{List<PlayerSessionPolicy.Segment> merged=PlayerCloudPolicy.merge(model(before),model(after),model(remote));if(merged==null)return null;JSONArray out=new JSONArray();for(PlayerSessionPolicy.Segment s:merged)out.put(new JSONObject().put("kind",s.kind).put("startMs",s.start).put("endMs",s.end));return out;}
    synchronized void resolvePreferences(boolean keepLocal){try{String key=queueKey("p_",binding()),unbound=queueKey("p_","");JSONObject pending=read(key);if(!pending.has("preferences"))pending=read(unbound);if(keepLocal){JSONObject latest=read("prefs_"+scope);if(!binding().equals(latest.optString("accountBinding")))return;pending.put("accountBinding",binding()).put("basePreferences",latest.optJSONObject("preferences"));saved.edit().putString(key,pending.toString()).remove(unbound).commit();}else saved.edit().remove(key).remove(unbound).commit();status="Sync pending";refresh();}catch(Exception unavailable){ }}
    synchronized void resolveSegments(boolean keepLocal){try{String key=queueKey("m_",binding()),unbound=queueKey("m_","");JSONObject pending=read(key);if(!pending.has("segments"))pending=read(unbound);JSONObject latest=read("state_"+scope+"_"+PlayerSegmentStore.key(owner,video));if(!binding().equals(latest.optString("accountBinding")))return;if(keepLocal){pending.put("accountBinding",binding()).put("before",latest.optJSONArray("segments")).put("baseRevision",latest.optLong("segmentsRevision"));saved.edit().putString(key,pending.toString()).remove(unbound).commit();}else saved.edit().remove(key).remove(unbound).commit();status="Sync pending";refresh();}catch(Exception unavailable){ }}
    static JSONObject snapshot(Context context)throws Exception{
        SubtitlePreferences subtitles=SubtitlePreferences.load(context);int style=PlayerUiPreferences.integer(context,"timeline_style",0);String[] aspects={"fit","fill","stretch"};
        return new JSONObject().put("appearance",PlayerUiPreferences.noiro(context)?"noiro":"classic").put("speed",PlayerUiPreferences.speed(context)).put("aspect",aspects[PlayerUiPreferences.aspect(context)]).put("seekStep",PlayerSessionPolicy.seekStep(PlayerUiPreferences.integer(context,"seek_step",10))).put("autoplay",PlayerUiPreferences.enabled(context,"autoplay",true)).put("autoSkip",PlayerUiPreferences.enabled(context,"auto_skip",false)).put("timelineStyle",STYLES[Math.max(0,Math.min(style,STYLES.length-1))]).put("subtitleFont",subtitles.font==0?"modern":"classic").put("subtitleScale",Math.round(subtitles.fineScale*10)/10.0);
    }
}
