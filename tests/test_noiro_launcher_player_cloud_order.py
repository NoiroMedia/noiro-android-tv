"""Run the actual cloud outbox with controlled network and delayed main-thread delivery.

The tiny Android/JSON doubles model preference serialization, defensive copies and task queues;
they do not implement sync decisions. Production PlayerCloudState/Policy run unchanged.
"""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "launcher-ui/app/src/tv/noiro/launcher"


class PlayerCloudOrderTests(unittest.TestCase):
    def test_delayed_get_after_ack_marker_rebase_conflicts_and_offline_deletes(self):
        files = {
            "android/content/SharedPreferences.java": r'''package android.content;
public interface SharedPreferences {String getString(String key,String fallback);Editor edit();interface Editor {Editor putString(String key,String value);Editor remove(String key);boolean commit();void apply();}}
''',
            "android/content/Context.java": r'''package android.content;
import java.util.*;
public class Context {
 final Map<String,Map<String,String>> stores=new HashMap<>();public Context getApplicationContext(){return this;}
 public SharedPreferences getSharedPreferences(String name,int mode){Map<String,String> values=stores.computeIfAbsent(name,n->new HashMap<>());return new SharedPreferences(){
  public String getString(String key,String fallback){return values.getOrDefault(key,fallback);}
  public Editor edit(){return new Editor(){final Map<String,String> changed=new HashMap<>();final Set<String> removed=new HashSet<>();public Editor putString(String key,String value){changed.put(key,value);return this;}public Editor remove(String key){removed.add(key);return this;}public boolean commit(){for(String key:removed)values.remove(key);values.putAll(changed);return true;}public void apply(){commit();}};}
 };}
}
''',
            "android/os/Handler.java": r'''package android.os;
import java.util.*;
public class Handler {public final List<Runnable> tasks=new ArrayList<>();public boolean post(Runnable task){tasks.add(task);return true;}public void drain(){while(!tasks.isEmpty())tasks.remove(0).run();}}
''',
            "org/json/JSONObject.java": r'''package org.json;
import java.util.*;
/** In-process serialization double: snapshots are detached, order and numeric values retained. */
public class JSONObject {
 static final Map<String,Map<String,Object>> snapshots=new HashMap<>();final Map<String,Object> values=new LinkedHashMap<>();
 public JSONObject(){}public JSONObject(String serialized){if(!serialized.equals("{}")){Map<String,Object> saved=snapshots.get(serialized);if(saved==null)throw new IllegalArgumentException("not a test snapshot");values.putAll(detach(saved));}}
 static Object detached(Object v){if(v instanceof JSONObject)return new JSONObject(v.toString());if(v instanceof JSONArray)return new JSONArray((JSONArray)v);return v;}
 static Map<String,Object> detach(Map<String,Object> source){Map<String,Object> out=new LinkedHashMap<>();for(String key:source.keySet())out.put(key,detached(source.get(key)));return out;}
 public JSONObject put(String key,Object value){values.put(key,value);return this;}public Object get(String key){if(!values.containsKey(key))throw new IllegalArgumentException(key);return values.get(key);}public Object opt(String key){return values.get(key);}public Object remove(String key){return values.remove(key);}public boolean has(String key){return values.containsKey(key);}public int length(){return values.size();}public Iterator<String> keys(){return values.keySet().iterator();}
 public String optString(String key){return optString(key,"");}public String optString(String key,String fallback){Object v=values.get(key);return v==null?fallback:String.valueOf(v);}public long optLong(String key){Object v=values.get(key);return v instanceof Number?((Number)v).longValue():0;}public JSONObject optJSONObject(String key){Object v=values.get(key);return v instanceof JSONObject?(JSONObject)v:null;}public JSONArray optJSONArray(String key){Object v=values.get(key);return v instanceof JSONArray?(JSONArray)v:null;}
 public String toString(){String out=values.toString();snapshots.put(out,detach(values));return out;}
}
''',
            "org/json/JSONArray.java": r'''package org.json;
import java.util.*;
public class JSONArray {final List<Object> values=new ArrayList<>();public JSONArray(){}JSONArray(JSONArray source){for(Object value:source.values)values.add(JSONObject.detached(value));}public JSONArray put(Object value){values.add(value);return this;}public int length(){return values.size();}public JSONObject optJSONObject(int index){return index>=0&&index<values.size()&&values.get(index) instanceof JSONObject?(JSONObject)values.get(index):null;}public String toString(){return values.toString();}}
''',
            "CloudOrderHarness.java": r'''package tv.noiro.launcher;
import android.content.*;import android.os.Handler;import org.json.*;import java.util.*;import java.lang.reflect.*;import java.io.IOException;
class AccountState {String uid="owner",authKey="private-session";boolean signedIn(){return true;}boolean owns(String key,String uid){return authKey.equals(key)&&this.uid.equals(uid);}}
interface NetworkStep {JSONObject run(JSONObject body)throws Exception;}
class NoiroAccount {static final AccountState owner=new AccountState();static final ArrayDeque<Object> responses=new ArrayDeque<>();static int writes;static boolean ready(){return true;}static AccountState current(){return owner;}static JSONObject playerState(String query,JSONObject body,AccountState owner)throws Exception{if(body!=null)writes++;Object response=responses.remove();if(response instanceof Exception)throw (Exception)response;if(response instanceof NetworkStep)response=((NetworkStep)response).run(body);return new JSONObject(response.toString());}}
class AccountHttp {static class Failure extends Exception {String code;Failure(String code){this.code=code;}}}
class PlayerSegmentStore {static String key(AccountState owner,String value){return owner.uid+"-"+Integer.toHexString(value.hashCode());}}
class SubtitlePreferences {int font;float fineScale=1;static SubtitlePreferences load(Context context){return new SubtitlePreferences();}}
class PlayerUiPreferences {static int integer(Context c,String name,int fallback){return fallback;}static boolean noiro(Context c){return false;}static float speed(Context c){return 1;}static int aspect(Context c){return 0;}static boolean enabled(Context c,String name,boolean fallback){return fallback;}}
public class CloudOrderHarness {
 static final String video="tt1234567:1:1",binding="opaque-A";static final AccountState owner=NoiroAccount.owner;
 static void check(boolean okay,String reason){if(!okay)throw new AssertionError(reason);}
 static JSONObject prefs(long revision,double speed){return new JSONObject().put("accountBinding",binding).put("prefsRevision",revision).put("preferences",new JSONObject().put("speed",speed).put("aspect","fit"));}
 static JSONObject segment(String kind,long start,long end){return new JSONObject().put("kind",kind).put("startMs",start).put("endMs",end);}
 static JSONObject media(long revision,JSONArray segments){return prefs(6,1.25).put("videoId",video).put("durationMs",100000).put("segmentsRevision",revision).put("segments",segments);}
 static String scope(){return PlayerSegmentStore.key(owner,owner.authKey);}static String queue(String prefix,String account){return prefix+scope()+"_"+PlayerSegmentStore.key(owner,account)+(prefix.equals("m_")?"_"+PlayerSegmentStore.key(owner,video):"");}
 static String mediaKey(){return "state_"+scope()+"_"+PlayerSegmentStore.key(owner,video);}
 static SharedPreferences store(Context context){return context.getSharedPreferences("player-cloud-outbox",0);}
 static void seed(Context context,JSONObject latest){store(context).edit().putString("binding_"+scope(),binding).putString("prefs_"+scope(),latest.toString()).commit();}
 static void sync(PlayerCloudState cloud)throws Exception{Method method=PlayerCloudState.class.getDeclaredMethod("sync");method.setAccessible(true);try{method.invoke(cloud);}catch(InvocationTargetException e){if(!(e.getCause() instanceof IOException))throw e;}}
 static double speed(JSONObject state){return ((Number)state.optJSONObject("preferences").get("speed")).doubleValue();}
 static void script(Object...responses){NoiroAccount.responses.clear();Collections.addAll(NoiroAccount.responses,responses);}
 static JSONObject edit(JSONArray before,JSONArray after){return new JSONObject().put("accountBinding",binding).put("videoId",video).put("durationMs",100000).put("baseRevision",1).put("before",before).put("segments",after);}
 static void holdWorker(PlayerCloudState cloud)throws Exception{Field field=PlayerCloudState.class.getDeclaredField("busy");field.setAccessible(true);field.setBoolean(cloud,true);}
 static void fastPreferences(boolean peer)throws Exception{
  Context context=new Context();Handler main=new Handler();seed(context,prefs(5,1));
  PlayerCloudState cloud=new PlayerCloudState(context,owner,"series",video,main,doc->{});
  check(PlayerCloudState.enqueuePreference(context,owner,"speed",1.25),"first real preference edit queued");
  script(prefs(5,1),(NetworkStep)body->{
   check(((Number)body.optJSONObject("preferences").get("speed")).doubleValue()==1.25,"first exact speed was sent");
   check(PlayerCloudState.enqueuePreference(context,owner,"speed",1.5),"newer speed queues before first ACK");
   return prefs(peer?7:6,peer?2:1.25);
  },new IOException("delay video GET"));sync(cloud);main.drain();
  JSONObject pending=new JSONObject(store(context).getString(queue("p_",binding),"{}"));
  check(((Number)pending.optJSONObject("preferences").get("speed")).doubleValue()==1.5,"ACK retains newer local value");
  check(((Number)pending.optJSONObject("basePreferences").get("speed")).doubleValue()==1.25,"newer draft baseline advances only to exact own sent speed, never ACK canonical peer2");
  int writes=NoiroAccount.writes;
  if(peer){script(prefs(7,2),media(1,new JSONArray()).put("prefsRevision",7).put("preferences",new JSONObject().put("speed",2)));sync(cloud);check(NoiroAccount.writes==writes&&cloud.status().contains("conflict"),"peer speed2 before ACK must stay a real conflict");}
  else{script(prefs(6,1.25),prefs(7,1.5),media(1,new JSONArray()).put("prefsRevision",7).put("preferences",new JSONObject().put("speed",1.5)));sync(cloud);check(NoiroAccount.writes==writes+1&&!store(context).getString(queue("p_",binding),"{}").contains("speed"),"own fast second edit syncs without false peer conflict");}
  cloud.close();
 }
 static void fastMarkers(boolean peer)throws Exception{
  Context context=new Context();Handler main=new Handler();seed(context,prefs(6,1.25));
  JSONArray original=new JSONArray().put(segment("intro",1000,20000)),first=new JSONArray().put(segment("intro",2000,21000)),newer=new JSONArray().put(segment("intro",3000,22000)),theirs=new JSONArray().put(segment("intro",4000,23000));
  store(context).edit().putString(mediaKey(),media(1,original).toString()).commit();
  PlayerCloudState cloud=new PlayerCloudState(context,owner,"series",video,main,doc->{});holdWorker(cloud);
  cloud.segments(100000,Arrays.asList(new PlayerSessionPolicy.Segment("intro",2000,21000)));
  script(prefs(6,1.25),media(1,original),(NetworkStep)body->{
   check(body.optJSONArray("segments").optJSONObject(0).optLong("startMs")==2000,"first exact marker was sent");
   cloud.segments(100000,Arrays.asList(new PlayerSessionPolicy.Segment("intro",3000,22000)));
   return media(peer?3:2,peer?theirs:first);
  });sync(cloud);main.drain();
  JSONObject pending=new JSONObject(store(context).getString(queue("m_",binding),"{}"));
  check(pending.optJSONArray("segments").optJSONObject(0).optLong("startMs")==3000,"marker ACK retains newer local kind");
  check(pending.optJSONArray("before").optJSONObject(0).optLong("startMs")==2000,"same-kind draft baseline advances only to exact own sent marker, never peer's returned marker");
  check(pending.optLong("baseRevision")==1,"partial baseline advance must not advance global revision and hide other-kind conflicts");
  int writes=NoiroAccount.writes;
  if(peer){script(prefs(6,1.25),media(3,theirs));sync(cloud);check(NoiroAccount.writes==writes&&cloud.status().contains("conflict"),"new peer marker before ACK remains a real same-kind conflict");}
  else{script(prefs(6,1.25),media(2,first),media(3,newer));sync(cloud);check(NoiroAccount.writes==writes+1&&store(context).getString(queue("m_",binding),"{}").equals("{}"),"own fast second marker edit syncs without false peer conflict");}
  cloud.close();
 }
 static void acknowledgedIntroMustNotRebaseNewCredits()throws Exception{
  Context context=new Context();Handler main=new Handler();seed(context,prefs(6,1.25));
  JSONArray original=new JSONArray().put(segment("intro",1000,20000)),first=new JSONArray().put(segment("intro",2000,21000)),peer=new JSONArray().put(segment("intro",2000,21000)).put(segment("credits",85000,100000));
  store(context).edit().putString(mediaKey(),media(1,original).toString()).commit();
  PlayerCloudState cloud=new PlayerCloudState(context,owner,"series",video,main,doc->{});holdWorker(cloud);cloud.segments(100000,Arrays.asList(new PlayerSessionPolicy.Segment("intro",2000,21000)));
  script(prefs(6,1.25),media(1,original),(NetworkStep)body->{cloud.segments(100000,Arrays.asList(new PlayerSessionPolicy.Segment("intro",3000,22000),new PlayerSessionPolicy.Segment("credits",80000,100000)));return media(3,peer);});sync(cloud);
  JSONObject pending=new JSONObject(store(context).getString(queue("m_",binding),"{}"));
  check(pending.optJSONArray("before").length()==1&&pending.optJSONArray("before").optJSONObject(0).optLong("startMs")==2000,"own intro ACK must not adopt peer credits into newly queued credits base");
  int writes=NoiroAccount.writes;script(prefs(6,1.25),media(3,peer));sync(cloud);check(NoiroAccount.writes==writes&&cloud.status().contains("conflict"),"new credits conflict cannot disappear after unrelated own intro ACK");cloud.close();
 }
 public static void main(String[] args)throws Exception{
  Context context=new Context();Handler main=new Handler();List<JSONObject> seen=new ArrayList<>();seed(context,prefs(5,1));
  check(PlayerCloudState.enqueuePreference(context,owner,"speed",1.25),"durable speed queued");
  PlayerCloudState cloud=new PlayerCloudState(context,owner,"series",video,main,seen::add);
  script(prefs(5,1),prefs(6,1.25),new IOException("media GET unavailable"));sync(cloud);
  check(main.tasks.size()==2,"both early GET and ACK callbacks held until POST finished");
  check(!store(context).getString(queue("p_",binding),"{}").contains("speed"),"ACK really removed the overlay before callback delivery");
  main.drain();check(seen.size()==2,"callbacks delivered");for(JSONObject doc:seen)check(speed(doc)==1.25&&doc.optLong("prefsRevision")==6,"late early GET cannot undo acknowledged speed when media GET fails");
  // Even a lagging media response cannot lower the independent preference revision.
  script(prefs(5,1),media(1,new JSONArray()).put("prefsRevision",5).put("preferences",new JSONObject().put("speed",1)));sync(cloud);main.drain();check(speed(cloud.cached())==1.25,"stale server document cannot regress cached prefs");
  // Local draft projection is detached from canonical cache, so CAS bases remain server values.
  check(PlayerCloudState.enqueuePreference(context,owner,"speed",1.5),"new local preference queued");check(speed(cloud.cached())==1.5,"draft appears in projection");check(speed(new JSONObject(store(context).getString("prefs_"+scope(),"{}")))==1.25,"projection never pollutes canonical server preference object");
  store(context).edit().remove(queue("p_",binding)).commit();
  JSONArray original=new JSONArray().put(segment("intro",1000,20000)),mine=new JSONArray().put(segment("intro",2000,21000)),website=new JSONArray().put(segment("intro",3000,22000));
  store(context).edit().putString(mediaKey(),media(1,original).toString()).putString(queue("m_",binding),edit(original,mine).toString()).commit();
  script(prefs(6,1.25),media(1,original),media(2,mine));sync(cloud);main.drain();check(store(context).getString(queue("m_",binding),"{}").equals("{}"),"accepted marker removed from outbox");
  script(prefs(6,1.25),media(3,website));sync(cloud);main.drain();JSONObject current=cloud.cached();check(current.optString("privateMarkersProjected").equals("true")&&current.optJSONArray("segments").optJSONObject(0).optLong("startMs")==3000,"fresh website marker replaces ACKed old local shadow");
  // A same-kind conflict is saved for review, but does not mask the current website marker.
  JSONArray independent=new JSONArray().put(segment("intro",2000,21000)).put(segment("credits",80000,100000));
  store(context).edit().putString(queue("m_",binding),edit(original,independent).toString()).commit();
  current=cloud.cached();check(current.optJSONArray("segments").length()==2&&current.optJSONArray("segments").optJSONObject(0).optLong("startMs")==3000,"only independent pending credits project; conflicting intro stays remote");check(!store(context).getString(queue("m_",binding),"{}").equals("{}"),"conflict retained durably");
  // An empty changed kind is a real pending deletion, not absence of an overlay.
  store(context).edit().putString(mediaKey(),media(1,original).toString()).putString(queue("m_",binding),edit(original,new JSONArray()).toString()).commit();check(cloud.cached().optJSONArray("segments").length()==0,"offline pending delete must not resurrect canonical intro");
  String unbound=edit(original,new JSONArray()).put("accountBinding","").toString();store(context).edit().remove(queue("m_",binding)).putString(queue("m_",""),unbound).commit();
  check(cloud.cached().optJSONArray("segments").length()==1,"unbound edits cannot shadow bound website markers");check(store(context).getString(queue("m_",""),"{}").equals(unbound),"unbound deletion preserved for explicit owner review");
  cloud.close();fastPreferences(false);fastPreferences(true);fastMarkers(false);fastMarkers(true);acknowledgedIntroMustNotRebaseNewCredits();System.out.println("PASS: delayed callbacks, monotonic revisions, marker authority/deletes, own fast preference/marker ACK rebasing and intervening peer conflicts");
 }
}
''',
        }
        java = Path(os.environ["JAVA_HOME"]) / "bin"
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            generated = []
            for name, content in files.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content)
                generated.append(path)
            sources = [SOURCE / name for name in ["PlayerCloudState.java", "PlayerCloudPolicy.java", "PlayerSessionPolicy.java"]]
            subprocess.run([str(java / "javac"), "-d", temp, *map(str, sources + generated)], check=True)
            subprocess.run([str(java / "java"), "-cp", temp, "tv.noiro.launcher.CloudOrderHarness"], check=True)
