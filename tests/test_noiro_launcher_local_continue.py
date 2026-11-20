"""Exercise actual local history, episode projection and feed workers without accounts or network."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "launcher-ui/app/src/tv/noiro/launcher"

FIXTURES = {
    "org/json/JSONObject.java": r'''package org.json;
import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
/** Detached in-process serialization double; contains no projection or sync policy. */
public class JSONObject {
 static final Map<String,Map<String,Object>> snapshots=new ConcurrentHashMap<>();static final AtomicLong serial=new AtomicLong();
 final Map<String,Object> values=new LinkedHashMap<>();
 public JSONObject(){}public JSONObject(String saved){if(!saved.equals("{}")){Map<String,Object> data=snapshots.get(saved);if(data==null)throw new IllegalArgumentException(saved);values.putAll(copy(data));}}
 private JSONObject(JSONObject saved){values.putAll(copy(saved.values));}
 static Object detached(Object value){return value instanceof JSONObject?new JSONObject((JSONObject)value):value instanceof JSONArray?new JSONArray((JSONArray)value):value;}
 static Map<String,Object> copy(Map<String,Object> data){Map<String,Object> out=new LinkedHashMap<>();for(String key:data.keySet())out.put(key,detached(data.get(key)));return out;}
 public JSONObject put(String key,Object value){values.put(key,value);return this;}public Object remove(String key){return values.remove(key);}public int length(){return values.size();}public Iterator<String> keys(){return values.keySet().iterator();}
 public String optString(String key){return optString(key,"");}public String optString(String key,String fallback){Object value=values.get(key);return value==null?fallback:String.valueOf(value);}
 public String getString(String key){if(!values.containsKey(key))throw new IllegalArgumentException(key);return String.valueOf(values.get(key));}
 public JSONObject optJSONObject(String key){Object value=values.get(key);return value instanceof JSONObject?(JSONObject)value:null;}public JSONObject getJSONObject(String key){JSONObject value=optJSONObject(key);if(value==null)throw new IllegalArgumentException(key);return value;}
 public JSONArray optJSONArray(String key){Object value=values.get(key);return value instanceof JSONArray?(JSONArray)value:null;}
 public long optLong(String key){Object value=values.get(key);return value instanceof Number?((Number)value).longValue():0;}public int optInt(String key){return (int)optLong(key);}public double optDouble(String key,double fallback){Object value=values.get(key);return value instanceof Number?((Number)value).doubleValue():fallback;}public boolean optBoolean(String key){return Boolean.TRUE.equals(values.get(key));}
 public String toString(){String key="snapshot-"+serial.incrementAndGet();snapshots.put(key,copy(values));return key;}
}
''',
    "org/json/JSONArray.java": r'''package org.json;
import java.util.*;
public class JSONArray {
 final List<Object> values=new ArrayList<>();public JSONArray(){}JSONArray(JSONArray saved){for(Object value:saved.values)values.add(JSONObject.detached(value));}
 public JSONArray put(Object value){values.add(value);return this;}public int length(){return values.size();}public String getString(int index){return String.valueOf(values.get(index));}
 public Object opt(int index){return index>=0&&index<values.size()?values.get(index):null;}public JSONObject optJSONObject(int index){Object value=opt(index);return value instanceof JSONObject?(JSONObject)value:null;}public JSONObject getJSONObject(int index){JSONObject value=optJSONObject(index);if(value==null)throw new IllegalArgumentException();return value;}
}
''',
    "android/content/SharedPreferences.java": r'''package android.content;
import java.util.*;
public interface SharedPreferences {
 String getString(String key,String fallback);Set<String> getStringSet(String key,Set<String> fallback);Editor edit();
 interface Editor {Editor putString(String key,String value);Editor putStringSet(String key,Set<String> values);Editor clear();boolean commit();void apply();}
}
''',
    "android/content/Context.java": r'''package android.content;
import java.util.*;import java.util.concurrent.*;
public class Context {
 public static final int MODE_PRIVATE=0;public String packageName="tv.noiro.androidtv";
 final Map<String,Map<String,Object>> stores=new ConcurrentHashMap<>();public Context getApplicationContext(){return this;}
 public SharedPreferences getSharedPreferences(String name,int mode){Map<String,Object> values=stores.computeIfAbsent(name,n->new ConcurrentHashMap<>());return new SharedPreferences(){
  public String getString(String key,String fallback){Object value=values.get(key);return value instanceof String?(String)value:fallback;}
  @SuppressWarnings("unchecked") public Set<String> getStringSet(String key,Set<String> fallback){Object value=values.get(key);return value instanceof Set?new HashSet<>((Set<String>)value):fallback;}
  public Editor edit(){return new Editor(){final Map<String,Object> edits=new HashMap<>();boolean clear;
   public Editor putString(String key,String value){edits.put(key,value);return this;}public Editor putStringSet(String key,Set<String> value){edits.put(key,new HashSet<>(value));return this;}
   public Editor clear(){clear=true;return this;}public boolean commit(){if(clear)values.clear();values.putAll(edits);return true;}public void apply(){commit();}
  };}
 };}
}
''',
    "android/os/Looper.java": r'''package android.os;public class Looper {public static Looper getMainLooper(){return new Looper();}}''',
    "android/os/Handler.java": r'''package android.os;
import java.util.concurrent.*;import java.util.function.*;
public class Handler {
 static final BlockingQueue<Runnable> tasks=new LinkedBlockingQueue<>();public Handler(Looper ignored){}
 public boolean post(Runnable task){return tasks.offer(task);}public boolean postDelayed(Runnable task,long millis){return true;}public void removeCallbacks(Runnable task){tasks.remove(task);}
 public static void until(BooleanSupplier condition)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(!condition.getAsBoolean()){if(System.nanoTime()>until)throw new AssertionError("async operation timed out");Runnable task=tasks.poll(50,TimeUnit.MILLISECONDS);if(task!=null)task.run();}}
}
''',
    "tv/noiro/launcher/LocalDependencies.java": r'''package tv.noiro.launcher;
import android.content.*;import org.json.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
class AppIdentity {static boolean standalone(Context context){return context.packageName.equals("tv.noiro.androidtv");}}
class NoiroAccount {
 static volatile AccountState owner=new AccountState("session-A","uid-A","Viewer","",0);static volatile boolean cloud;static final AtomicInteger reads=new AtomicInteger(),writes=new AtomicInteger();
 static AccountState current(){return owner;}static boolean ready(){return cloud;}
 static JSONObject progress(JSONObject body,AccountState expected){if(!cloud)throw new AssertionError("Free app contacted Noiro cloud");
  if(body==null){reads.incrementAndGet();return new JSONObject().put("items",new JSONArray()).put("sources",new JSONArray());}
  writes.incrementAndGet();JSONArray accepted=new JSONArray(),changes=body.optJSONArray("changes");for(int i=0;i<changes.length();i++)accepted.put(changes.optJSONObject(i).optString("eventId"));
  return new JSONObject().put("accepted",accepted);
 }
}
class FreeCatalog {
 static final Map<String,JSONObject> metadata=new ConcurrentHashMap<>();static final AtomicInteger requests=new AtomicInteger();static volatile CountDownLatch gate;
 static JSONObject metadata(String type,String imdb)throws Exception {requests.incrementAndGet();CountDownLatch latch=gate;if(latch!=null&&!latch.await(5,TimeUnit.SECONDS))throw new Exception("held metadata");JSONObject value=metadata.get(imdb);if(value==null)throw new Exception("No metadata");return new JSONObject(value.toString());}
}
class AccountHttp {static JSONObject call(String url,JSONObject body,String token){throw new AssertionError("Unexpected network "+url);}}
class Tmdb {static boolean isSeen(Context context,String kind,int id){return false;}static JSONObject get(String path){throw new AssertionError("Free app used TMDB");}}
class Recs {static final class Pick {int id,ratingCount;double rating;String kind,title,meta,poster="",backdrop="",imdb="",logo="";UpNext.Item continuing;}}
''',
}

HARNESS = r'''package tv.noiro.launcher;
import android.content.*;import android.os.Handler;import org.json.*;import java.util.*;import java.util.concurrent.*;
public class LocalContinueHarness {
 static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
 static JSONObject event(String type,String video,long position,long duration,boolean complete,long at){return new JSONObject().put("eventAt",at).put("completed",complete)
  .put("item",new JSONObject().put("_id",video.split(":")[0]).put("type",type).put("name","Title "+video.split(":")[0])
   .put("state",new JSONObject().put("video_id",video).put("timeOffset",position).put("duration",duration)));}
 static JSONArray saved(JSONArray events){return events;}
 static JSONObject episode(String id,String date){return new JSONObject().put("id",id).put("released",date).put("title",id);}
 static JSONObject metadata(String id,JSONObject...episodes){JSONArray videos=new JSONArray();for(JSONObject episode:episodes)videos.put(episode);return new JSONObject().put("id",id).put("poster","https://images.invalid/"+id+".jpg").put("background","https://images.invalid/"+id+"-wide.jpg").put("videos",videos);}
 static void project()throws Exception {
  String id="tt1000001";long now=java.time.Instant.parse("2026-11-20T00:00:00Z").toEpochMilli();
  JSONObject meta=metadata(id,episode(id+":2:2","2026-11-21"),episode(id+":2:1","2026-11-19"),episode(id+":1:9","2026-11-18"),episode(id+":0:1","2020-01-01"));
  List<PlayerSessionPolicy.Episode> roster=PlayerEpisodes.fromMetadata(id,meta);
  check(roster.size()==3&&roster.get(0).id.equals(id+":1:9"),"actual Cinemeta parser sorts and excludes specials");
  Map<String,List<PlayerSessionPolicy.Episode>> rosters=new HashMap<>();rosters.put(id,roster);
  JSONObject complete=event("series",id+":1:9",0,100000,true,10),partial=event("movie","tt1000002",40000,100000,false,20),endedFilm=event("movie","tt1000003",95000,100000,true,30);
  JSONArray history=new JSONArray().put(complete).put(partial).put(endedFilm);
  JSONArray rows=LocalContinue.project(history,rosters,now);
  check(rows.length()==2&&rows.optJSONObject(0).optString("id").equals("tt1000002"),"original viewing recency is newest first");
  check(rows.optJSONObject(0).optLong("positionMs")==40000&&!rows.optJSONObject(0).optBoolean("upNext"),"partial film resumes exact saved position");
  check(rows.optJSONObject(1).optString("videoId").equals(id+":2:1")&&rows.optJSONObject(1).optBoolean("upNext"),"finished final episode crosses to aired next season even if ended position reset");
  check(rows.optJSONObject(1).optLong("eventAt")==10,"metadata resolution never promotes viewing timestamp");
  history=new JSONArray().put(event("series",id+":2:1",100000,100000,true,40));
  check(LocalContinue.project(history,rosters,now).length()==0,"immediate future successor stays hidden");
  check(LocalContinue.project(history,rosters,now+2*86400000L).optJSONObject(0).optString("videoId").equals(id+":2:2"),"same saved anchor becomes eligible when next episode airs");
  rosters.put(id,PlayerEpisodes.fromMetadata(id,metadata(id,episode(id+":1:9","2026-11-18"),episode(id+":2:1",""),episode(id+":2:2","2026-11-19"))));
  check(LocalContinue.project(new JSONArray().put(complete),rosters,now).length()==0,"unknown immediate release never jumps ahead to another episode");
  check(LocalContinue.project(new JSONArray().put(event("series",id+":1:9",40000,100000,false,50)),rosters,now).optJSONObject(0).optString("videoId").equals(id+":1:9"),"an active rewatch remains current even with successors");
  check(LocalContinue.completedSeries(new JSONArray().put(complete)).equals(Collections.singletonList(id)),"completed anchors retained for later metadata refresh");
  System.out.println("PASS: resume, completed films, cross-season successor, future and missing releases, stable recency and rewatch");
 }
 static void durable()throws Exception {
  Context context=new Context();AccountState owner=NoiroAccount.owner;
  check(WatchProgress.record(context,owner,"movie","tt1000010","Film",40000,100000,false),"free local checkpoint persisted");
  WatchProgress.flush(owner);check(NoiroAccount.reads.get()==0&&NoiroAccount.writes.get()==0,"free mode has no cloud reads or outbox retries");
  check(WatchProgress.local(owner).length()==1,"free history survives explicit flush");
  NoiroAccount.cloud=true;
  check(WatchProgress.record(context,owner,"series","tt1000011:1:1","Show",100000,100000,true),"supporter local checkpoint persisted before optional upload");
  WatchProgress.flush(owner);
  check(NoiroAccount.writes.get()>0&&WatchProgress.local(owner).length()==2,"cloud ACK removes outbox but preserves durable local history");
  NoiroAccount.cloud=false;WatchProgress.clear();
  NoiroAccount.owner=new AccountState("other","uid-B","Other","",0);AccountState other=NoiroAccount.owner;
  check(WatchProgress.local(other).length()==0&&WatchProgress.local(owner).length()==0,"another UID cannot read previous local history");
  check(!WatchProgress.record(context,owner,"movie","tt1000012","Stale",1,100,false),"stale owner cannot write another account");
  NoiroAccount.owner=new AccountState("rotated-session","uid-A","Viewer","",0);AccountState rotated=NoiroAccount.owner;
  check(WatchProgress.local(rotated).length()==2,"same UID retains local history across credential rotation");
  for(java.lang.reflect.Field field:WatchProgress.class.getDeclaredFields())if(field.getName().equals("store")||field.getName().equals("history")){field.setAccessible(true);field.set(null,null);}
  WatchProgress.init(context);check(WatchProgress.local(rotated).length()==2,"history reloads from durable preferences after memory state resets");
  NoiroAccount.owner=new AccountState("bounded","uid-C","Many","",0);AccountState many=NoiroAccount.owner;
  for(int i=0;i<505;i++)check(WatchProgress.record(context,many,"movie",String.format(Locale.US,"tt%07d",2000000+i),"Film",1,100,false),"bounded write");
  check(WatchProgress.local(many).length()==500,"per-UID history is bounded");
  NoiroAccount.owner=AccountState.EMPTY;check(WatchProgress.local(many).length()==0,"guest cannot see signed-in progress");
  System.out.println("PASS: durable history, separate ACK outbox, no free cloud traffic, UID isolation, rotation and 500-title bound");
 }
 static void feed()throws Exception {
  Context context=new Context();AccountState owner=NoiroAccount.owner;String series="tt1000020",film="tt1000021";
  check(WatchProgress.record(context,owner,"series",series+":1:1","Show",100000,100000,true),"completed series saved");
  check(WatchProgress.record(context,owner,"movie",film,"Film",30000,100000,false),"partial film saved");
  FreeCatalog.metadata.put(series,metadata(series,episode(series+":1:1","2020-01-01"),episode(series+":1:2","2020-01-02")));
  FreeCatalog.metadata.put(film,metadata(film));FreeCatalog.gate=new CountDownLatch(1);
  UpNext feed=new UpNext(context,()->{});
  try {
   feed.resume();Handler.until(()->feed.loaded);
   check(feed.items().size()==1&&feed.items().get(0).continuing.id.equals(film),"resume feed renders before slow series metadata");
   check(NoiroAccount.reads.get()==0&&NoiroAccount.writes.get()==0,"real free feed never contacts Noiro cloud");
   FreeCatalog.gate.countDown();Handler.until(()->feed.items().size()==2);
   Recs.Pick next=null;for(Recs.Pick pick:feed.items())if(pick.continuing.id.equals(series))next=pick;
   check(next!=null&&next.continuing.videoId.equals(series+":1:2")&&next.continuing.next,"real feed resolves the aired next episode");
   Recs.Pick movie=null;for(Recs.Pick pick:feed.items())if(pick.continuing.id.equals(film))movie=pick;
   final Recs.Pick finalMovie=movie;feed.enrich(finalMovie);Handler.until(()->!finalMovie.poster.isEmpty());
   check(finalMovie.poster.startsWith("https://images.invalid/"),"free artwork comes directly from Cinemeta");
   check(UpNext.setHidden(context,owner,next.continuing,0,true),"local dismissal recorded");
   check(feed.items().size()==1,"existing hidden filtering remains active");
   UpNext.setHidden(context,owner,next.continuing,0,false);
   check(WatchProgress.record(context,owner,"series",series+":1:2","Show",20000,100000,false),"newer episode activity recorded");
   feed.pause();feed.resume();Handler.until(()->!feed.items().isEmpty()&&feed.items().get(0).continuing.videoId.equals(series+":1:2")&&!feed.items().get(0).continuing.next);
   check(feed.items().get(0).continuing.positionMs==20000,"newest real watching moves first with exact resume");
   NoiroAccount.owner=new AccountState("other","other-uid","Other","",0);
   check(feed.items().isEmpty(),"old feed is inaccessible after account switching");
   feed.pause();feed.resume();Handler.until(()->feed.loaded);
   check(feed.items().isEmpty(),"new account does not inherit local items or artwork");
   check(NoiroAccount.reads.get()==0&&NoiroAccount.writes.get()==0,"all free refreshes remain local");
  } finally {feed.destroy();FreeCatalog.gate.countDown();}
  System.out.println("PASS: actual feed workers, immediate resume, asynchronous next episode/artwork, dismissal and account switching");
 }
 public static void main(String[] args)throws Exception {if(args[0].equals("project"))project();else if(args[0].equals("durable"))durable();else feed();System.exit(0);}
}
'''


class LocalContinueTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        directory = Path(cls.temp.name)
        generated = []
        for name, content in {**FIXTURES, "LocalContinueHarness.java": HARNESS}.items():
            path = directory / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
            generated.append(path)
        cls.java = Path(os.environ["JAVA_HOME"]) / "bin"
        files = ["WatchProgress.java", "UpNext.java", "LocalContinue.java", "PlayerEpisodes.java",
                 "PlayerSessionPolicy.java", "UpNextPolicy.java", "UpNextLocalPolicy.java", "AccountState.java"]
        subprocess.run([str(cls.java / "javac"), "-d", str(directory),
                        *map(str, [SOURCE / name for name in files] + generated)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def run_case(self, case):
        subprocess.run([str(self.java / "java"), "-cp", self.temp.name,
                        "tv.noiro.launcher.LocalContinueHarness", case], check=True, timeout=25)

    def test_exact_resume_and_aired_successors(self):
        self.run_case("project")

    def test_durable_uid_history_survives_cloud_acknowledgment(self):
        self.run_case("durable")

    def test_real_free_feed_and_account_switch(self):
        self.run_case("feed")

    def test_real_free_metadata_boundary_preserves_aired_episode_rules(self):
        fixtures = dict(FIXTURES)
        # Only replace the transport-facing catalog double; all metadata sanitization,
        # episode parsing and progress projection below run production implementations.
        deps = fixtures["tv/noiro/launcher/LocalDependencies.java"]
        start, end = deps.index("class FreeCatalog {"), deps.index("class AccountHttp {")
        fixtures["tv/noiro/launcher/LocalDependencies.java"] = deps[:start] + deps[end:]
        fixtures["tv/noiro/launcher/StudioHome.java"] = r'''package tv.noiro.launcher;
import org.json.*;import java.util.*;
class StudioHome {
 static class Row {String catalogId;List<Card> cards=new ArrayList<>();Row(String name){}}
 static class Card {final String id,type;final JSONObject metadata;Card(String id,String name,String type,String image,String background,String logo,String detail,String summary,boolean landscape,boolean portrait,JSONObject entry,JSONObject metadata){this.id=id;this.type=type;this.metadata=metadata;}String key(){return type+":"+id;}}
}
'''
        fixtures["tv/noiro/launcher/Ui.java"] = 'package tv.noiro.launcher;class Ui {static String tr(String a,String b){return b;}}'
        fixtures["CinemetaBoundaryHarness.java"] = r'''package tv.noiro.launcher;
import org.json.*;import java.util.*;import java.lang.reflect.*;
public class CinemetaBoundaryHarness {
 static final String id="tt1000001";
 static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
 static JSONObject episode(String video,String field,String released){return new JSONObject().put("id",video).put(field,released).put("name","Episode "+video);}
 static JSONObject metadata(JSONObject...episodes)throws Exception {
  JSONArray videos=new JSONArray();for(JSONObject episode:episodes)videos.put(episode);
  JSONObject provider=new JSONObject().put("meta",new JSONObject().put("id",id).put("type","series").put("name","Series")
   .put("poster","https://images.invalid/poster.jpg").put("videos",videos));
  // Seed the provider response cache to prevent any actual HTTP call. The public
  // metadata method still performs its own production identity/field sanitization.
  Field cache=FreeCatalog.class.getDeclaredField("CACHE");cache.setAccessible(true);
  Class<?> cached=Class.forName("tv.noiro.launcher.FreeCatalog$Cached");Constructor<?> constructor=cached.getDeclaredConstructor(JSONObject.class,long.class);constructor.setAccessible(true);
  @SuppressWarnings("unchecked") Map<String,Object> values=(Map<String,Object>)cache.get(null);
  values.put(FreeCatalog.API+"/meta/series/"+id+".json",constructor.newInstance(provider,System.currentTimeMillis()+60000));
  return FreeCatalog.metadata("series",id);
 }
 static JSONArray project(JSONObject meta,String video,long now){JSONObject event=new JSONObject().put("eventAt",123).put("completed",true)
  .put("item",new JSONObject().put("_id",id).put("type","series").put("name","Series")
   .put("state",new JSONObject().put("video_id",video).put("timeOffset",100).put("duration",100)));
  return LocalContinue.project(new JSONArray().put(event),Collections.singletonMap(id,PlayerEpisodes.fromMetadata(id,meta)),now);
 }
 public static void main(String[] args)throws Exception {
  long now=java.time.Instant.parse("2026-11-20T00:00:00Z").toEpochMilli();
  JSONObject safe=metadata(episode(id+":2:2","released","2026-11-21"),episode(id+":1:9","released","2026-11-18"),episode(id+":2:1","firstAired","2026-11-19T00:00:00.000Z"),episode("tt9999999:1:1","released","2020-01-01"));
  List<PlayerSessionPolicy.Episode> roster=PlayerEpisodes.fromMetadata(id,safe);
  check(roster.size()==3&&roster.get(1).title.equals("Episode "+id+":2:1"),"sanitization retains matching roster, date fallback and human title");
  JSONArray rows=project(safe,id+":1:9",now);check(rows.length()==1&&rows.optJSONObject(0).optString("videoId").equals(id+":2:1"),"actual metadata boundary retains cross-season aired successor");
  check(project(safe,id+":2:1",now).length()==0,"actual metadata boundary excludes future episode");
  check(project(safe,id+":2:1",now+86400000L).length()==1,"cached future release becomes eligible with current time");
  JSONObject unknown=metadata(episode(id+":1:9","released","2026-11-18"),episode(id+":2:1","released",""),episode(id+":2:2","released","2026-11-19"));
  check(project(unknown,id+":1:9",now).length()==0,"missing immediate date cannot skip forward after sanitization");
  System.out.println("PASS: actual FreeCatalog.metadata -> PlayerEpisodes.fromMetadata -> LocalContinue episode boundary");
 }
}
'''
        with tempfile.TemporaryDirectory() as tmp:
            generated = []
            for name, content in fixtures.items():
                path = Path(tmp) / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content)
                generated.append(path)
            names = ["WatchProgress.java", "UpNext.java", "LocalContinue.java", "PlayerEpisodes.java",
                     "PlayerSessionPolicy.java", "UpNextPolicy.java", "UpNextLocalPolicy.java", "AccountState.java", "FreeCatalog.java"]
            subprocess.run([str(self.java / "javac"), "-d", tmp,
                            *map(str, [SOURCE / name for name in names] + generated)], check=True)
            subprocess.run([str(self.java / "java"), "-cp", tmp,
                            "tv.noiro.launcher.CinemetaBoundaryHarness"], check=True, timeout=15)
