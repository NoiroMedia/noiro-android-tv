"""Exercise real feed loading and row reconciliation without a TV or account."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
PACKAGE = ROOT / 'launcher-ui/app/src/tv/noiro/launcher'


class ContinueFeedTests(unittest.TestCase):
    def run_harness(self, sources, fixtures, main):
        java = Path(os.environ['JAVA_HOME']) / 'bin'
        with tempfile.TemporaryDirectory() as directory:
            temp = Path(directory)
            paths = []
            for name, content in fixtures.items():
                path = temp / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content)
                paths.append(str(path))
            subprocess.run([str(java/'javac'), '-d', directory,
                            *[str(PACKAGE/name) for name in sources], *paths], check=True)
            subprocess.run([str(java/'java'), '-cp', directory, main], check=True, timeout=15)

    def test_recent_activity_moves_to_front_and_catalogs_keep_stable_order(self):
        self.run_harness(['HomeRowOrder.java'], {'OrderHarness.java': r'''
package tv.noiro.launcher;
import java.util.*;
public class OrderHarness {
 static final class Card { final String id, label; Card(String id,String label){this.id=id;this.label=label;} }
 static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
 static String ids(List<Card> cards){StringBuilder result=new StringBuilder();for(Card c:cards)result.append(c.id);return result.toString();}
 public static void main(String[] args){
  Card a=new Card("a","old a"),b=new Card("b","old b");
  List<Card> previous=Collections.unmodifiableList(Arrays.asList(a,b));
  Card recentB=new Card("b","new episode"),newA=new Card("a","updated a"),newC=new Card("c","new title");
  List<Card> fresh=Collections.unmodifiableList(Arrays.asList(recentB,newA,newC));
  List<Card> continuing=HomeRowOrder.reconcile("continue",previous,fresh,c->c.id);
  check(ids(continuing).equals("bac"),"latest watched title must move to the front");
  check(continuing.get(0)==recentB,"a new episode must replace its old title card");
  check(ids(HomeRowOrder.reconcile("plex:discover",previous,fresh,c->c.id)).equals("abc"),"Discover should retain its stable order");
  check(ids(HomeRowOrder.reconcile("continue",previous,Arrays.asList(newC,recentB),c->c.id)).equals("cb"),"completed/removed titles must disappear");
  check(ids(HomeRowOrder.reconcile("recommendations",previous,Arrays.asList(newC,recentB),c->c.id)).equals("bc"),"stable catalogs drop removed titles and append new ones");
  check(ids(HomeRowOrder.reconcile("continue",previous,Arrays.asList(recentB,recentB,newC),c->c.id)).equals("bc"),"duplicate identities must not create duplicate cards");
  check(ids(previous).equals("ab")&&ids(fresh).equals("bac"),"reconciliation must not mutate either input");
  check(HomeRowOrder.reconcile("continue",previous,Collections.<Card>emptyList(),c->c.id).isEmpty(),"an empty fresh feed must remove old entries");
 }
}
'''}, 'tv.noiro.launcher.OrderHarness')

    def test_real_feed_retains_late_next_episodes_and_refreshes_their_pointers(self):
        fixtures = {
            'android/content/SharedPreferences.java': r'''
package android.content;
import java.util.*;
public class SharedPreferences {
 private final Map<String,Set<String>> data=new HashMap<>();
 public Set<String> getStringSet(String key,Set<String> fallback){return data.getOrDefault(key,fallback);}
 public Editor edit(){return new Editor();}
 public final class Editor {
  public Editor putStringSet(String key,Set<String> values){data.put(key,new HashSet<>(values));return this;}
  public boolean commit(){return true;} public void apply(){}
 }
}
''',
            'android/content/Context.java': r'''
package android.content;
public class Context {
 public static final int MODE_PRIVATE=0;
 private final SharedPreferences preferences=new SharedPreferences();
 public Context getApplicationContext(){return this;}
 public SharedPreferences getSharedPreferences(String name,int mode){return preferences;}
}
''',
            'android/os/Looper.java': r'''
package android.os;
public class Looper { public static Looper getMainLooper(){return new Looper();} }
''',
            'android/os/Handler.java': r'''
package android.os;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
public class Handler {
 private static final BlockingQueue<Runnable> queue=new LinkedBlockingQueue<>();
 public Handler(Looper ignored){}
 public boolean post(Runnable task){return queue.offer(task);}
 public boolean postDelayed(Runnable task,long delay){return true;}
 public void removeCallbacks(Runnable task){queue.remove(task);}
 public static void until(BooleanSupplier ready)throws Exception {
  long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
  while(!ready.getAsBoolean()){
   if(System.nanoTime()>deadline)throw new AssertionError("feed refresh timed out");
   Runnable task=queue.poll(100,TimeUnit.MILLISECONDS);if(task!=null)task.run();
  }
 }
}
''',
            'org/json/JSONObject.java': r'''
package org.json;
import java.util.*;
public class JSONObject {
 private final Map<String,Object> values=new HashMap<>();
 public JSONObject put(String key,Object value){values.put(key,value);return this;}
 public String optString(String key){return optString(key,"");}
 public String optString(String key,String fallback){Object v=values.get(key);return v instanceof String?(String)v:fallback;}
 public int optInt(String key){return (int)optLong(key);}
 public long optLong(String key){Object v=values.get(key);return v instanceof Number?((Number)v).longValue():0;}
 public double optDouble(String key,double fallback){Object v=values.get(key);return v instanceof Number?((Number)v).doubleValue():fallback;}
 public boolean optBoolean(String key){return Boolean.TRUE.equals(values.get(key));}
 public JSONArray optJSONArray(String key){Object v=values.get(key);return v instanceof JSONArray?(JSONArray)v:null;}
}
''',
            'org/json/JSONArray.java': r'''
package org.json;
import java.util.*;
public class JSONArray {
 private final List<Object> values=new ArrayList<>();
 public JSONArray put(Object value){values.add(value);return this;}
 public int length(){return values.size();}
 public JSONObject optJSONObject(int index){Object value=values.get(index);return value instanceof JSONObject?(JSONObject)value:null;}
 public JSONObject getJSONObject(int index){return (JSONObject)values.get(index);}
}
''',
            'tv/noiro/launcher/FeedDependencies.java': r'''
package tv.noiro.launcher;
import android.content.Context;
import org.json.*;
final class NoiroAccount {
 static final AccountState owner=new AccountState("fixture","test-owner","Test","metadata",1);
 static volatile JSONObject response;
 static AccountState current(){return owner;}
 static boolean ready(){return true;}
 static JSONObject progress(JSONObject change,AccountState expected){return response;}
}
final class WatchProgress {
 static boolean failFlush;
 static void init(Context ignored){}
 static void flush(AccountState ignored)throws Exception {if(failFlush)throw new Exception("pending upload unavailable");}
 static JSONArray local(AccountState ignored){throw new AssertionError("cloud feed used local history");}
}
final class AppIdentity {static boolean standalone(Context ignored){return false;}}
final class FreeCatalog {static JSONObject metadata(String type,String id){throw new AssertionError("cloud feed used free metadata");}}
final class PlayerSessionPolicy {static final class Episode {}}
final class PlayerEpisodes {static java.util.List<PlayerSessionPolicy.Episode> fromMetadata(String id,JSONObject data){throw new AssertionError("cloud feed used local episodes");}}
final class LocalContinue {
 static JSONArray project(JSONArray saved,java.util.Map<String,java.util.List<PlayerSessionPolicy.Episode>> rosters,long now){throw new AssertionError("cloud feed used local projection");}
 static java.util.List<String> completedSeries(JSONArray saved){throw new AssertionError("cloud feed used local projection");}
 static java.util.Set<String> mediaKeys(JSONArray saved){throw new AssertionError("cloud feed used local projection");}
}
final class Tmdb {
 static boolean isSeen(Context context,String kind,int id){return false;}
 static JSONObject get(String path){return new JSONObject();}
}
final class Recs {
 static final class Pick {
  int id,ratingCount;double rating;String kind,title,meta,poster,backdrop,imdb,logo;UpNext.Item continuing;
 }
}
''',
            'tv/noiro/launcher/FeedHarness.java': r'''
package tv.noiro.launcher;
import android.content.Context;
import android.os.Handler;
import org.json.*;
import java.util.*;
public class FeedHarness {
 static void check(boolean pass,String why){if(!pass)throw new AssertionError(why);}
 static String id(int index){return String.format(Locale.US,"tt%07d",1000000+index);}
 static JSONObject response(int count,boolean newestFirst,int episode){
  JSONArray rows=new JSONArray();
  for(int n=0;n<count;n++){
   int index=newestFirst?count-n-1:n;
   rows.put(new JSONObject().put("type","series").put("id",id(index))
    .put("videoId",id(index)+":2:"+episode).put("name","Series "+index)
    .put("upNext",true).put("progress",0).put("positionMs",0).put("durationMs",0));
  }
  return new JSONObject().put("items",rows).put("sources",new JSONArray());
 }
 public static void main(String[] args)throws Exception {
  NoiroAccount.response=response(143,false,3);
  UpNext feed=new UpNext(new Context(),()->{});
  try{
   feed.resume();Handler.until(()->feed.loaded);
   List<Recs.Pick> initial=feed.items();
   check(initial.size()==143,"the old 24-card limit must not hide eligible episodes");
   check(initial.get(142).continuing.videoId.equals(id(142)+":2:3"),"an episode beyond card 24 must remain playable");
   check(initial.get(142).continuing.next,"late entries must retain their Up Next action");
   NoiroAccount.response=response(143,true,4);feed.pause();feed.resume();
   Handler.until(()->!feed.items().isEmpty()&&feed.items().get(0).continuing.videoId.equals(id(142)+":2:4"));
   List<Recs.Pick> refreshed=feed.items();
   check(refreshed.size()==143&&refreshed.get(142).continuing.id.equals(id(0)),"fresh server recency order must be preserved");
   check(refreshed.get(0).continuing.action().equals("Play S02 E04"),"finished episodes must expose the updated next pointer");
   NoiroAccount.response=response(510,false,5);feed.pause();feed.resume();
   Handler.until(()->!feed.items().isEmpty()&&feed.items().get(0).continuing.videoId.equals(id(0)+":2:5"));
   check(feed.items().size()==500,"the expanded feed must retain its defensive 500-item bound");
   WatchProgress.failFlush=true;
   NoiroAccount.response=response(2,false,6);feed.pause();feed.resume();
   Handler.until(()->!feed.items().isEmpty()&&feed.items().get(0).continuing.videoId.equals(id(0)+":2:6"));
   check(feed.items().size()==2,"an unavailable queued upload must not block reading fresh progress");
  } finally {feed.destroy();}
 }
}
''',
        }
        self.run_harness(['UpNext.java', 'UpNextPolicy.java', 'UpNextLocalPolicy.java', 'AccountState.java'],
                         fixtures, 'tv.noiro.launcher.FeedHarness')
