"""Exercise public catalog/meta mapping and bounded redirects without accounts, network or a TV."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class FreeCatalogTests(unittest.TestCase):
    def test_catalog_metadata_and_official_redirects(self):
        java_home = os.environ.get('JAVA_HOME')
        javac = str(Path(java_home) / 'bin/javac') if java_home else shutil.which('javac')
        java = str(Path(java_home) / 'bin/java') if java_home else shutil.which('java')
        self.assertTrue(javac and java, 'A JDK is required')
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            sources = {
                'org/json/JSONObject.java': r'''package org.json;
import java.util.*;
public class JSONObject {
 final Map<String,Object> values=new HashMap<>();
 public JSONObject(){} public JSONObject(String value){throw new UnsupportedOperationException("network disabled");}
 public JSONObject put(String key,Object value){values.put(key,value);return this;}
 public String optString(String key){Object value=values.get(key);return value==null?"":String.valueOf(value);}
 public int optInt(String key){Object value=values.get(key);return value instanceof Number?((Number)value).intValue():0;}
 public JSONArray optJSONArray(String key){Object value=values.get(key);return value instanceof JSONArray?(JSONArray)value:null;}
 public JSONObject optJSONObject(String key){Object value=values.get(key);return value instanceof JSONObject?(JSONObject)value:null;}
 public String toString(){StringJoiner out=new StringJoiner(",","{","}");for(Map.Entry<String,Object> item:values.entrySet())out.add(quote(item.getKey())+":"+encode(item.getValue()));return out.toString();}
 static String quote(String value){return "\""+value.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n")+"\"";}
 static String encode(Object value){return value instanceof String?quote((String)value):String.valueOf(value);}
}''',
                'org/json/JSONArray.java': r'''package org.json;
import java.util.*;
public class JSONArray {
 static final Map<String,List<Object>> stored=new HashMap<>();
 final List<Object> values=new ArrayList<>();
 public JSONArray(){} public JSONArray(String value){if(value.equals("[]"))return;List<Object> saved=stored.get(value);if(saved==null)throw new IllegalArgumentException("Corrupt fixture");values.addAll(saved);}
 public JSONArray put(Object value){values.add(value);return this;}
 public Object opt(int index){return index>=0&&index<values.size()?values.get(index):null;}
 public int length(){return values.size();}
 public JSONObject optJSONObject(int index){Object value=opt(index);return value instanceof JSONObject?(JSONObject)value:null;}
 public String toString(){StringJoiner out=new StringJoiner(",","[","]");for(Object value:values)out.add(JSONObject.encode(value));String value=out.toString();stored.put(value,new ArrayList<>(values));return value;}
}''',
                'android/content/SharedPreferences.java': r'''package android.content;
import java.util.*;
public final class SharedPreferences {
 final Map<String,String> values=new HashMap<>();
 public String getString(String key,String fallback){return values.getOrDefault(key,fallback);}
 public Editor edit(){return new Editor();}
 public final class Editor {final Map<String,String> pending=new HashMap<>();public Editor putString(String key,String value){pending.put(key,value);return this;}public void apply(){values.putAll(pending);}}
}''',
                'android/content/Context.java': r'''package android.content;
import java.util.*;
public final class Context {
 public static final int MODE_PRIVATE=0;
 final Map<String,SharedPreferences> stores=new HashMap<>();
 public SharedPreferences getSharedPreferences(String name,int mode){return stores.computeIfAbsent(name,key->new SharedPreferences());}
}''',
                'tv/noiro/launcher/NoiroAccount.java': r'''package tv.noiro.launcher;
final class NoiroAccount {static AccountState state=AccountState.EMPTY;static AccountState current(){return state;}}''',
                'tv/noiro/launcher/StudioHome.java': r'''package tv.noiro.launcher;
import java.util.*;import org.json.*;
final class StudioHome {
 static final class Row {final String name;String catalogId="";final List<Card> cards=new ArrayList<>();Row(String name){this.name=name;}}
 static final class Card {
  final String id,name,type,image,background,logo,summary;final JSONObject metadata;
  Card(String id,String name,String type,String image,String background,String logo,String detail,String summary,
       boolean landscape,boolean portrait,JSONObject entry,JSONObject metadata){
   this.id=id;this.name=name;this.type=type;this.image=image;this.background=background;this.logo=logo;this.summary=summary;this.metadata=metadata;
  }
  String key(){return type+":"+id;}
  boolean title(){return type.equals("movie")||type.equals("series");}
 }
}''',
                'tv/noiro/launcher/Ui.java': 'package tv.noiro.launcher; final class Ui {static String tr(String pt,String en){return en;}}',
                'tv/noiro/launcher/FreeCatalogHarness.java': r'''package tv.noiro.launcher;
import java.util.*;import org.json.*;
public class FreeCatalogHarness {
 static void check(boolean value){if(!value)throw new AssertionError();}
 static JSONObject movie(){return new JSONObject().put("id","tt1160419").put("imdb_id","tt1160419").put("type","movie")
  .put("name","Dune").put("releaseInfo","2021").put("description","Paul Atreides arrives on Arrakis.")
  .put("poster","https://images.metahub.space/poster/small/tt1160419/img")
  .put("background","https://images.metahub.space/background/medium/tt1160419/img")
  .put("genres",new JSONArray().put("Action").put("Adventure")).put("imdbRating","8.0");}
 static void blocked(String url)throws Exception {
  int[] calls={0};try{FreeCatalog.request(url,target->{calls[0]++;return new FreeCatalog.Response(200,"",new JSONObject());});throw new AssertionError(url);}
  catch(java.io.IOException expected){check(calls[0]==0);}
 }
 public static void main(String[] args)throws Exception {
  // These are the fields returned by the live Cinemeta catalog and meta endpoints.
  JSONObject data=new JSONObject().put("metas",new JSONArray().put(movie()).put(movie())
    .put(new JSONObject().put("id","tt0944947").put("name","A series").put("type","series"))
    .put(new JSONObject().put("id","tmdb:438631").put("name","Wrong identity").put("type","movie"))
    .put(new JSONObject().put("id","tt1234567").put("name","Unsupported").put("type","channel")));
  List<StudioHome.Card> cards=FreeCatalog.parseCatalog(data,"movie");check(cards.size()==1);
  StudioHome.Card card=cards.get(0);check(card.id.equals("tt1160419")&&card.name.equals("Dune")&&card.type.equals("movie"));
  check(card.image.startsWith("https://")&&card.metadata.optString("releaseInfo").equals("2021"));
  check(FreeCatalog.parseCatalog(new JSONObject(),"movie").isEmpty());
  check(FreeCatalog.card(null,"movie")==null);check(FreeCatalog.card(movie(),"series")==null);
  check(FreeCatalog.card(movie().put("name",""),"movie")==null);
  JSONObject meta=FreeCatalog.parseMetadata(new JSONObject().put("meta",movie()),"movie","tt1160419");
  check(meta.optString("imdbId").equals("tt1160419")&&meta.optString("description").contains("Arrakis"));
  try{FreeCatalog.parseMetadata(new JSONObject().put("meta",movie()),"movie","tt9999999");throw new AssertionError();}catch(java.io.IOException expected){}
  JSONObject series=new JSONObject().put("id","tt0944947").put("name","Game of Thrones").put("type","series")
   .put("videos",new JSONArray()
    .put(new JSONObject().put("id","tt0944947:1:1").put("name","Winter Is Coming").put("season",99).put("episode",99)
      .put("firstAired","2011-04-17T00:00:00.000Z").put("thumbnail","https://episodes.metahub.space/tt0944947/1/1/w780.jpg"))
    .put(new JSONObject().put("id","tt0944947:1:2").put("released","2011-04-24T00:00:00.000Z"))
    .put(new JSONObject().put("id","tt9999999:1:1").put("name","Wrong show")));
  JSONObject safe=FreeCatalog.parseMetadata(new JSONObject().put("meta",series),"series","tt0944947");
  JSONArray videos=safe.optJSONArray("videos");check(videos.length()==2);
  JSONObject first=videos.optJSONObject(0);check(first.optInt("season")==1&&first.optInt("episode")==1&&first.optInt("number")==1);
  check(first.optString("released").equals("2011-04-17T00:00:00.000Z"));check(first.optString("name").equals("Winter Is Coming"));
  check(videos.optJSONObject(1).optString("released").equals("2011-04-24T00:00:00.000Z"));
  for(String bad:new String[]{"http://images.test/a","https://user@images.test/a","https://images.test:8080/a","https://images.test/a#secret","file:///tmp/a"})check(FreeCatalog.image(bad).isEmpty());
  check(FreeCatalog.card(movie().put("poster","http://unsafe.test/a"),"movie").image.isEmpty());
  JSONArray many=new JSONArray();for(int i=0;i<150;i++)many.put(movie().put("id","tt"+(1000000+i)));
  check(FreeCatalog.parseCatalog(new JSONObject().put("metas",many),"movie").size()==100);
  String start="https://v3-cinemeta.strem.io/catalog/movie/top/skip=0.json";
  JSONObject expected=new JSONObject().put("metas",new JSONArray().put(movie()));int[] calls={0};
  JSONObject result=FreeCatalog.request(start,target->{calls[0]++;if(calls[0]==1)return new FreeCatalog.Response(307,"https://cinemeta-catalogs.strem.io/top/catalog/movie/top/skip=0.json",null);check(target.startsWith("https://cinemeta-catalogs.strem.io/"));return new FreeCatalog.Response(200,"",expected);});
  check(result==expected&&calls[0]==2);
  for(String bad:new String[]{"http://v3-cinemeta.strem.io/a","https://evil.test/a","https://v3-cinemeta.strem.io.evil.test/a","https://u@v3-cinemeta.strem.io/a","https://v3-cinemeta.strem.io:443/a","https://v3-cinemeta.strem.io/a#x"})blocked(bad);
  calls[0]=0;try{FreeCatalog.request(start,target->{calls[0]++;return new FreeCatalog.Response(307,"https://evil.test/a",null);});throw new AssertionError();}catch(java.io.IOException rejected){check(calls[0]==1);}
  calls[0]=0;try{FreeCatalog.request(start,target->{calls[0]++;return new FreeCatalog.Response(307,"/again",null);});throw new AssertionError();}catch(java.io.IOException bounded){check(calls[0]==3);}
  try{FreeCatalog.request(start,target->new FreeCatalog.Response(403,"",null));throw new AssertionError();}catch(java.io.IOException unavailable){}
  check(FreeCatalog.type("tv").equals("series")&&FreeCatalog.type("channel").isEmpty());
  android.content.Context context=new android.content.Context();
  JSONObject untrusted=movie().put("authKey","private-credential").put("url","https://playback.test/private-source")
    .put("headers",new JSONObject().put("Authorization","secret-bearer"))
    .put("videos",new JSONArray().put(new JSONObject().put("id","tt1160419:1:1")));
  StudioHome.Card bookmark=FreeCatalog.card(untrusted,"movie");
  check(FreeWatchlist.cards(context).isEmpty());FreeWatchlist.toggle(context,bookmark);
  check(FreeWatchlist.contains(context,bookmark)&&FreeWatchlist.cards(context).size()==1);
  String persisted=context.getSharedPreferences("local-watchlist",0).getString("guest","");
  check(persisted.contains("Dune")&&persisted.contains("https://images.metahub.space/"));
  for(String secret:new String[]{"private-credential","private-source","Authorization","secret-bearer","authKey","videos","headers"})check(!persisted.contains(secret));
  check(FreeWatchlist.cards(context).get(0).metadata.optString("releaseInfo").equals("2021"));
  NoiroAccount.state=new AccountState("auth-a","uid-a","A","",0);check(FreeWatchlist.cards(context).isEmpty());
  FreeWatchlist.toggle(context,bookmark);check(FreeWatchlist.contains(context,bookmark));
  NoiroAccount.state=new AccountState("rotated-auth-a","uid-a","A","",0);check(FreeWatchlist.contains(context,bookmark));
  NoiroAccount.state=new AccountState("auth-b","uid-b","B","",0);check(FreeWatchlist.cards(context).isEmpty());
  StudioHome.Card show=FreeCatalog.card(series,"series");FreeWatchlist.toggle(context,show);
  check(FreeWatchlist.contains(context,show)&&!FreeWatchlist.contains(context,bookmark));
  NoiroAccount.state=AccountState.EMPTY;check(FreeWatchlist.contains(context,bookmark)&&!FreeWatchlist.contains(context,show));
  FreeWatchlist.toggle(context,bookmark);check(FreeWatchlist.cards(context).isEmpty());
  NoiroAccount.state=new AccountState("third-auth-a","uid-a","A","",0);check(FreeWatchlist.contains(context,bookmark));
  FreeWatchlist.toggle(context,bookmark);check(FreeWatchlist.cards(context).isEmpty());
  context.getSharedPreferences("local-watchlist",0).edit().putString("user:uid-a","broken fixture").apply();
  check(FreeWatchlist.cards(context).isEmpty());
  FreeWatchlist.toggle(context,bookmark);check(FreeWatchlist.contains(context,bookmark));
 }
}''',
            }
            for name, content in sources.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content)
            source = ROOT / 'launcher-ui/app/src/tv/noiro/launcher/FreeCatalog.java'
            extra = [source.with_name('FreeWatchlist.java'), source.with_name('AccountState.java')]
            subprocess.run([javac, '-encoding', 'UTF-8', '-d', tmp, str(source)] + [str(path) for path in extra]
                           + [str(root / name) for name in sources], check=True)
            subprocess.run([java, '-cp', tmp, 'tv.noiro.launcher.FreeCatalogHarness'], check=True)
