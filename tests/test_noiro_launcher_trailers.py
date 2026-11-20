"""Exercise native trailer selection and failure paths without a television or network."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class TrailerTests(unittest.TestCase):
    def test_native_selection_identity_and_fallback(self):
        java = Path(os.environ['JAVA_HOME']) / 'bin'
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            sources = {
                'org/json/JSONObject.java': r'''package org.json;
import java.util.*;
public class JSONObject {
 final Map<String,Object> m=new HashMap<>();
 public JSONObject(){} public JSONObject(String value){throw new UnsupportedOperationException("network disabled in fixture");}
 public JSONObject put(String k,Object v){m.put(k,v);return this;}
 public boolean has(String k){return m.containsKey(k);}
 public String optString(String k){return m.get(k) instanceof String?(String)m.get(k):"";}
 public int optInt(String k){return (int)optLong(k);}
 public long optLong(String k){return m.get(k) instanceof Number?((Number)m.get(k)).longValue():0;}
 public JSONObject optJSONObject(String k){return m.get(k) instanceof JSONObject?(JSONObject)m.get(k):null;}
 public JSONObject getJSONObject(String k){JSONObject v=optJSONObject(k);if(v==null)throw new IllegalArgumentException();return v;}
 public JSONArray optJSONArray(String k){return m.get(k) instanceof JSONArray?(JSONArray)m.get(k):null;}
 public JSONArray getJSONArray(String k){JSONArray v=optJSONArray(k);if(v==null)throw new IllegalArgumentException();return v;}
}''',
                'org/json/JSONArray.java': r'''package org.json;
import java.util.*;
public class JSONArray {
 final List<Object> a=new ArrayList<>();
 public JSONArray put(Object v){a.add(v);return this;} public int length(){return a.size();}
 public JSONObject optJSONObject(int i){return i>=0&&i<a.size()&&a.get(i) instanceof JSONObject?(JSONObject)a.get(i):null;}
 public JSONObject getJSONObject(int i){JSONObject v=optJSONObject(i);if(v==null)throw new IllegalArgumentException();return v;}
}''',
                'tv/noiro/launcher/TrailerHarness.java': r'''package tv.noiro.launcher;
import org.json.*;
public class TrailerHarness {
 static final String VIDEO="https://r1.googlevideo.com/videoplayback?fixture=video",AUDIO="https://r1.googlevideo.com/videoplayback?fixture=audio";
 static void check(boolean ok){if(!ok)throw new AssertionError();}
 static JSONObject format(String url,String mime,int height,int itag)throws Exception{return new JSONObject().put("url",url).put("mimeType",mime).put("height",height).put("itag",itag);}
 static JSONObject stream()throws Exception{return new JSONObject().put("formats",new JSONArray().put(format(VIDEO,"video/mp4",360,18)));}
 static JSONObject response(String id,JSONObject data)throws Exception{return new JSONObject().put("videoDetails",new JSONObject().put("videoId",id)).put("playabilityStatus",new JSONObject().put("status","OK")).put("streamingData",data);}
 public static void main(String[] args)throws Exception{
  for(String bad:new String[]{"http://r.googlevideo.com/a","https://googlevideo.com.evil.test/a","https://notgooglevideo.com/a","https://user@r.googlevideo.com/a","https://r.googlevideo.com:8080/a","https://r.googlevideo.com/a#fragment"})check(!TrailerResolver.mediaUrl(bad,"googlevideo.com"));
  check(TrailerResolver.mediaUrl(VIDEO,"googlevideo.com"));
  check(TrailerResolver.select(stream(),"ua").audio.isEmpty());
  JSONObject data=stream().put("adaptiveFormats",new JSONArray().put(format(VIDEO,"video/mp4; codecs=avc1",1080,137)).put(format(AUDIO,"audio/mp4; codecs=mp4a",0,140)));
  TrailerResolver.Result pair=TrailerResolver.select(data,"per-client-UA");check(pair.audio.equals(AUDIO)&&pair.userAgent.equals("per-client-UA"));
  data.put("adaptiveFormats",new JSONArray().put(format(VIDEO,"video/mp4; codecs=avc1",1080,137)));check(TrailerResolver.select(data,"ua").audio.isEmpty());
  JSONObject cipher=format(VIDEO,"video/mp4",720,22).put("signatureCipher","requires decipher");check(TrailerResolver.select(new JSONObject().put("formats",new JSONArray().put(cipher)),"ua")==null);
  data.put("formats",new JSONArray());check(TrailerResolver.select(data,"ua")==null);
  JSONObject imdb=new JSONObject().put("data",new JSONObject().put("title",new JSONObject().put("primaryVideos",new JSONObject().put("edges",new JSONArray().put(new JSONObject().put("node",new JSONObject().put("name",new JSONObject().put("value","Official trailer")).put("playbackURLs",new JSONArray().put(new JSONObject().put("url","https://video.media-imdb.com/trailer.mp4").put("mimeType","video/mp4").put("videoDefinition","DEF_1080p")))))))));
  check(TrailerResolver.imdb(imdb).video.equals("https://video.media-imdb.com/trailer.mp4"));
  int[] calls={0};
  TrailerResolver.resolve("abcdefghijk","tt1234567",(url,body,ua)->{calls[0]++;check(url.equals("https://api.graphql.imdb.com/"));check(body.getJSONObject("variables").optString("id").equals("tt1234567"));return imdb;},result->true);
  check(calls[0]==1);calls[0]=0;
  TrailerResolver.Result resolved=TrailerResolver.resolve("abcdefghijk","",(url,body,ua)->{calls[0]++;check(url.startsWith("https://www.youtube.com/"));if(calls[0]==1)return response("wrong-video",stream());return response("abcdefghijk",stream());},result->true);
  check(calls[0]==2&&resolved.userAgent.contains("android.youtube"));
  calls[0]=0;
  TrailerResolver.resolve("abcdefghijk","",(url,body,ua)->{calls[0]++;return response("abcdefghijk",stream());},result->calls[0]>1);
  check(calls[0]==2);
  calls[0]=0;try{TrailerResolver.resolve("bad","",(url,body,ua)->{calls[0]++;return new JSONObject();},result->true);throw new AssertionError();}catch(java.io.IOException expected){}check(calls[0]==0);
  try{TrailerResolver.resolve("abcdefghijk","",(url,body,ua)->{throw new java.io.IOException("provider secret");},result->true);throw new AssertionError();}catch(java.io.IOException expected){check(expected.getMessage().equals("Trailer unavailable"));}
  System.out.println("PASS: native muxed/adaptive selection, complete A/V pairs, host validation, client UA and safe failures");
 }
}'''
            }
            paths = []
            for name, text in sources.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(text)
                paths.append(str(path))
            paths.append(str(ROOT / 'launcher-ui/app/src/tv/noiro/launcher/TrailerResolver.java'))
            subprocess.run([str(java / 'javac'), '-d', tmp, *paths], check=True)
            subprocess.run([str(java / 'java'), '-cp', tmp, 'tv.noiro.launcher.TrailerHarness'], check=True)
