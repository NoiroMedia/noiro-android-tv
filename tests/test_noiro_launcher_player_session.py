"""Real playback policy/container regressions; no account, media URL or device needed."""
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "launcher-ui/app/src/tv/noiro/launcher"


def ebml(tag, data):
    raw_tag = tag.to_bytes((tag.bit_length() + 7) // 8, "big")
    width = next(n for n in range(1, 9) if len(data) < (1 << (7 * n)) - 1)
    return raw_tag + (len(data) | (1 << (7 * width))).to_bytes(width, "big") + data


def atom(start, title):
    return ebml(0xB6, ebml(0x91, (start * 1_000_000).to_bytes(8, "big")) + ebml(0x80, ebml(0x85, title.encode())))


def box(kind, payload):
    return struct.pack(">I4s", len(payload) + 8, kind.encode()) + payload


class PlayerSessionTests(unittest.TestCase):
    def test_explicit_choice_preempts_ended_modal_autoplay_and_stale_identity(self):
        self.run_java("TransitionHarness", r'''package tv.noiro.launcher;
public class TransitionHarness {
 static void check(boolean okay,String why){if(!okay)throw new AssertionError(why);}
 public static void main(String[] args){
  PlayerSessionPolicy.Transitions transitions=new PlayerSessionPolicy.Transitions();
  Object episode1=new Object(),episode2=new Object(),selectedSourceForEpisode1=new Object();
  transitions.reset(episode1);long sourcesMenu=transitions.capture(episode1),episodesMenu=sourcesMenu;
  boolean ended=true,nextSourcesPreloaded=true;
  // Android can detach a dismissed modal synchronously, before its click handler continues.
  long sourceAction=transitions.begin(episode1,sourcesMenu);
  int automaticTransitions=0;if(ended&&nextSourcesPreloaded&&transitions.mayAdvance(episode1,sourcesMenu))automaticTransitions++;
  check(automaticTransitions==0,"claim before dismiss prevents preloaded next from replacing request");
  check(transitions.current(episode1,sourceAction),"selected source still belongs to captured episode1 request");
  transitions.reset(selectedSourceForEpisode1);
  check(!transitions.current(episode1,sourceAction)&&!transitions.mayAdvance(episode1,sourcesMenu),"late old modal cannot change or advance replacement source");
  check(transitions.begin(episode1,episodesMenu)<0,"old Episodes click cannot select previous under a different request");
  transitions.reset(episode1);long previousMenu=transitions.capture(episode1),previousAction=transitions.begin(episode1,previousMenu);
  check(transitions.pending()&&!transitions.mayAdvance(episode1,previousMenu),"explicit previous waits for its own sources and suppresses autoplay");
  long newerMenu=transitions.capture(episode1),newerChoice=transitions.begin(episode1,newerMenu);
  transitions.complete(episode1,previousAction);
  check(transitions.pending(),"old loading failure cannot clear a newer explicit choice");
  transitions.complete(episode1,newerChoice);check(!transitions.pending(),"matching failed choice releases pending state");
  check(!transitions.mayAdvance(episode1,previousMenu),"finishing async choice does not revive old modal's autoplay");
  long ordinaryMenu=transitions.capture(episode1);check(transitions.mayAdvance(episode1,ordinaryMenu),"ordinary close can advance an ended episode");
  transitions.reset(episode2);check(!transitions.mayAdvance(episode1,ordinaryMenu),"late episode1 detach cannot advance episode2");
  System.out.println("PASS: ENDED/preloaded next, synchronous modal detach and explicit source/episode request identity");
 }
}''', ["PlayerSessionPolicy.java"])

    def run_java(self, name, text, sources, args=(), classpath=()):
        java = Path(os.environ["JAVA_HOME"]) / "bin"
        with tempfile.TemporaryDirectory() as tmp:
            harness = Path(tmp) / (name + ".java")
            harness.write_text(text)
            subprocess.run([str(java / "javac"), "-d", tmp, *(["-cp", os.pathsep.join(map(str, classpath))] if classpath else []), *[str(SOURCE / s) for s in sources], str(harness)], check=True)
            subprocess.run([str(java / "java"), "-cp", os.pathsep.join([tmp, *map(str, classpath)]), "tv.noiro.launcher." + name, *map(str, args)], check=True)

    def test_episode_air_dates_and_real_segment_guards(self):
        self.run_java("SessionHarness", r'''package tv.noiro.launcher;
import java.util.*;
public class SessionHarness {
 static void check(boolean okay,String reason){if(!okay)throw new AssertionError(reason);}
 public static void main(String[] args){
  PlayerSessionPolicy.Episode current=new PlayerSessionPolicy.Episode("tt1234567:1:1","Pilot",1,1,10);
  PlayerSessionPolicy.Episode unknown=new PlayerSessionPolicy.Episode("tt1234567:1:2","Unknown",1,2,-1);
  PlayerSessionPolicy.Episode future=new PlayerSessionPolicy.Episode("tt1234567:1:2","Future",1,2,200);
  PlayerSessionPolicy.Episode laterAired=new PlayerSessionPolicy.Episode("tt1234567:1:3","Later",1,3,20);
  check(PlayerSessionPolicy.adjacent(Arrays.asList(current,unknown,laterAired),current.id,1,100)==null,"never skip unknown release to a later aired episode");
  check(PlayerSessionPolicy.adjacent(Arrays.asList(current,future,laterAired),current.id,1,100)==null,"never skip future episode");
  check(PlayerSessionPolicy.adjacent(Arrays.asList(current,future,laterAired),current.id,1,200)==future,"release boundary is inclusive");
  check(PlayerSessionPolicy.adjacent(Arrays.asList(current,unknown,laterAired),"missing",1,999)==null,"do not guess current episode");
  check(PlayerSessionPolicy.adjacent(Arrays.asList(current,laterAired),laterAired.id,-1,100)==current,"previous episode available");
  check(PlayerSessionPolicy.valid(new PlayerSessionPolicy.Segment("intro",5000,60000),100000),"intro boundary valid");
  check(!PlayerSessionPolicy.valid(new PlayerSessionPolicy.Segment("intro",5000,60001),100000),"intro cannot cross60percent");
  check(!PlayerSessionPolicy.valid(new PlayerSessionPolicy.Segment("credits",49999,90000),100000),"credits cannot start in first half");
  check(!PlayerSessionPolicy.valid(new PlayerSessionPolicy.Segment("recap",1000,4999),100000),"tiny segment must not skip");
  check(!PlayerSessionPolicy.valid(new PlayerSessionPolicy.Segment("unrecognized",70000,90000),100000),"unknown segment kinds are rejected");
  List<PlayerSessionPolicy.Chapter> chapters=Arrays.asList(new PlayerSessionPolicy.Chapter("Opening credits",0),new PlayerSessionPolicy.Chapter("The story",10000),new PlayerSessionPolicy.Chapter("Credits",80000));
  List<PlayerSessionPolicy.Segment> spans=PlayerSessionPolicy.chapterSegments(chapters,100000);
  check(spans.size()==2&&spans.get(0).kind.equals("intro")&&spans.get(1).kind.equals("credits"),"named real chapters yield bounded skip spans");
  check(PlayerSessionPolicy.chapterSegments(Arrays.asList(new PlayerSessionPolicy.Chapter("Stop",0),new PlayerSessionPolicy.Chapter("Editor",10000)),100000).isEmpty(),"OP/ED tokens require word boundaries");
  check(PlayerSessionPolicy.subtitlePosition(5000,500)==4500&&PlayerSessionPolicy.subtitlePosition(100,500)==0,"later subtitles use earlier cue position without negative time");
  check(PlayerSessionPolicy.resolution("2160p HDR")==2160&&PlayerSessionPolicy.resolution("4K")==2160&&PlayerSessionPolicy.resolution("release1234567209")==0,"quality uses real tokens, not incidental digits");
  check(!PlayerSessionPolicy.canAdvance(false,true,false,false,true,0),"sleep pause cannot be undone when a dialog closes");
  check(!PlayerSessionPolicy.canAdvance(false,true,false,true,false,0),"end-of-episode sleep suppresses autoplay");
  check(!PlayerSessionPolicy.canAdvance(true,true,false,false,false,0),"live never advances as VOD");
  check(!PlayerSessionPolicy.canAdvance(false,true,false,false,false,1),"open panel delays automatic transition");
  check(PlayerSessionPolicy.canAdvance(false,true,false,false,false,0),"normal episode can advance");
  System.out.println("PASS: immediate aired episodes, segment sanity and subtitle timing");
 }
}''', ["PlayerSessionPolicy.java"])

    def test_cloud_binding_and_independent_cas_merge(self):
        self.run_java("CloudHarness", r'''package tv.noiro.launcher;
import java.util.*;
public class CloudHarness {
 static void check(boolean okay,String reason){if(!okay)throw new AssertionError(reason);}
 static Map<String,Object> map(Object...values){Map<String,Object> out=new HashMap<>();for(int i=0;i<values.length;i+=2)out.put((String)values[i],values[i+1]);return out;}
 public static void main(String[] args){
  check(!PlayerCloudPolicy.sameBinding("","new")&&!PlayerCloudPolicy.sameBinding("accountA","accountB"),"unbound and relinked outbox must not upload");
  check(PlayerCloudPolicy.sameBinding("accountA","accountA"),"original account can retry");
  Map<String,Object> base=map("speed",1,"autoplay",true),edit=map("speed",1.25),remote=map("speed",1.0,"autoplay",false);
  check(PlayerCloudPolicy.conflicts(base,edit,remote).isEmpty(),"independent website autoplay must not block speed edit");
  check(PlayerCloudPolicy.conflicts(base,edit,map("speed",1.5)).contains("speed"),"offline old speed cannot erase newer device speed");
  check(PlayerCloudPolicy.conflicts(base,edit,map("speed",1.25)).isEmpty(),"already applied retry is idempotent");
  check(PlayerCloudPolicy.conflicts(Collections.emptyMap(),edit,map("speed",1.5)).contains("speed"),"unknown base does not authorize overwrite");
  PlayerSessionPolicy.Segment intro=new PlayerSessionPolicy.Segment("intro",1000,20000),mine=new PlayerSessionPolicy.Segment("intro",2000,21000),theirs=new PlayerSessionPolicy.Segment("intro",3000,22000),credits=new PlayerSessionPolicy.Segment("credits",80000,100000);
  List<PlayerSessionPolicy.Segment> merged=PlayerCloudPolicy.merge(Arrays.asList(intro),Arrays.asList(mine),Arrays.asList(intro,credits));
  check(merged!=null&&merged.size()==2&&merged.get(0)==mine&&merged.get(1)==credits,"independent kind remains on server");
  check(PlayerCloudPolicy.merge(Arrays.asList(intro),Arrays.asList(mine),Arrays.asList(theirs))==null,"same-kind concurrent edit stays local");
  check(PlayerCloudPolicy.merge(Collections.emptyList(),Arrays.asList(mine),Arrays.asList(theirs))==null,"simultaneous first intro edits conflict");
  merged=PlayerCloudPolicy.merge(Arrays.asList(intro),Collections.emptyList(),Arrays.asList(intro,credits));
  check(merged!=null&&merged.size()==1&&merged.get(0)==credits,"deleting own intro preserves fresh remote credits");
  Map<String,Object> queuedBase=map("speed",1,"aspect","fit"),queued=map("speed",1.25,"aspect","fill"),fresh=map("speed",1,"aspect","stretch");
  check(PlayerCloudPolicy.conflicts(queuedBase,queued,fresh).equals(Collections.singleton("aspect")),"only independent speed may be posted");
  queued.remove("speed");fresh.put("speed",1.25);
  check(PlayerCloudPolicy.conflicts(queuedBase,queued,fresh).contains("aspect"),"own speed ACK must not rebase old aspect conflict");
  List<PlayerSessionPolicy.Segment> oldBase=Arrays.asList(intro),oldEdit=Arrays.asList(mine),newEdit=Arrays.asList(mine,credits),freshMarkers=Arrays.asList(theirs);
  List<PlayerSessionPolicy.Segment> retained=PlayerCloudPolicy.retainBaseForUnchangedKinds(oldBase,oldEdit,newEdit,freshMarkers);
  check(PlayerCloudPolicy.merge(retained,newEdit,freshMarkers)==null,"new credits edit cannot rebase old intro conflict");
  System.out.println("PASS: relink isolation, prefs conflicts and independent-kind marker CAS");
 }
}''', ["PlayerSessionPolicy.java", "PlayerCloudPolicy.java"])

    def test_embedded_text_offset_keeps_media_clock_and_lifecycle_unchanged(self):
        cache = Path.home() / ".gradle/caches/modules-2/files-2.1/androidx.media3"
        with tempfile.TemporaryDirectory() as tmp:
            classpath = [Path(os.environ["ANDROID_HOME"]) / "platforms/android-36/android.jar"]
            for module in ["media3-exoplayer", "media3-common", "media3-decoder", "media3-container", "media3-datasource", "media3-extractor"]:
                aar = next((cache / module / "1.11.1").rglob("*.aar"))
                target = Path(tmp) / (module + ".jar")
                with zipfile.ZipFile(aar) as archive:
                    target.write_bytes(archive.read("classes.jar"))
                classpath.append(target)
            guava = Path.home() / ".gradle/caches/modules-2/files-2.1/com.google.guava"
            classpath.append(next((guava / "guava/33.3.1-android").rglob("*.jar")))
            classpath.append(next((guava / "failureaccess/1.0.2").rglob("*.jar")))
            self.run_java("TextHarness", r'''package tv.noiro.launcher;
import androidx.media3.exoplayer.*;import java.lang.reflect.*;
public class TextHarness {
 public static void main(String[] args)throws Exception{
  long[] delay={500000},position={-1},elapsed={-1},reset={-1};boolean[] released={false};
  MediaClock clock=(MediaClock)Proxy.newProxyInstance(MediaClock.class.getClassLoader(),new Class[]{MediaClock.class},(object,method,values)->method.getName().equals("getPositionUs")?123456L:null);
  Renderer original=(Renderer)Proxy.newProxyInstance(Renderer.class.getClassLoader(),new Class[]{Renderer.class},(object,method,values)->{
   switch(method.getName()){
    case "render":position[0]=(Long)values[0];elapsed[0]=(Long)values[1];return null;
    case "getDurationToProgressUs":position[0]=(Long)values[0];return 50L;
    case "resetPosition":reset[0]=(Long)values[0];return null;
    case "getMediaClock":return clock;
    case "release":released[0]=true;return null;
    case "getName":return "text-delegate";
    default:return null;
   }
  });
  PlayerTextRenderer shifted=new PlayerTextRenderer(original,()->delay[0]);shifted.render(5000000,7000000);
  if(position[0]!=4500000||elapsed[0]!=7000000)throw new AssertionError("later text shifts only text position");
  delay[0]=-100000;shifted.render(5000000,7000000);if(position[0]!=5100000)throw new AssertionError("earlier embedded text is supported");
  if(shifted.getDurationToProgressUs(5000000,7000000)!=50||position[0]!=5100000)throw new AssertionError("text wakeup uses same shifted clock");
  if(shifted.getMediaClock()!=clock||clock.getPositionUs()!=123456)throw new AssertionError("shared media clock must be untouched");
  shifted.resetPosition(5000000,false);if(reset[0]!=5000000)throw new AssertionError("source reset position remains real media position");
  shifted.release();if(!released[0]||!shifted.getName().equals("text-delegate"))throw new AssertionError("renderer lifecycle delegates");
  System.out.println("PASS: early/late embedded text without changing media clock or reset/lifecycle");
 }
}''', ["PlayerTextRenderer.java"], classpath=classpath)

    def test_range_transport_rejects_redirects_wrong_offsets_and_unbounded_reads(self):
        self.run_java("RangeHarness", r'''package tv.noiro.launcher;
import java.net.*;import java.io.*;import java.util.*;import java.util.concurrent.atomic.AtomicBoolean;
class StremioStreams {static class Source {String url="https://media.invalid/generated";Map<String,String> headers=new HashMap<>();}}
class NoiroAccount {static final String APP_VERSION="preview";}
public class RangeHarness {
 static int mode,bodyReads,requests;static Fake latest;
 static class Fake extends HttpURLConnection {
  Fake(URL url){super(url);latest=this;requests++;}
  public int getResponseCode(){return mode==3?302:mode==0?200:206;}
  public String getHeaderField(String name){if(!name.equals("Content-Range"))return null;return mode==1?"bytes 0-15/1000":"bytes 100-115/1000";}
  public InputStream getInputStream(){return new InputStream(){public int read(){bodyReads++;return 0;}public int read(byte[] bytes,int offset,int length){bodyReads+=length;Arrays.fill(bytes,offset,offset+length,(byte)7);return length;}};}
  public void disconnect(){}public boolean usingProxy(){return false;}public void connect(){}
 }
 static void reject(PlayerChapterReader reader,long offset)throws Exception{try{reader.read(offset,16);throw new AssertionError("must reject response");}catch(IOException expected){}}
 public static void main(String[] args)throws Exception{
  URL.setURLStreamHandlerFactory(protocol->protocol.equals("https")?new URLStreamHandler(){protected URLConnection openConnection(URL url){return new Fake(url);}}:null);
  StremioStreams.Source source=new StremioStreams.Source();source.headers.put("Referer","generated");source.headers.put("Authorization","not-forwarded");AtomicBoolean current=new AtomicBoolean(true);PlayerChapterReader reader=new PlayerChapterReader(source,current::get);
  mode=0;bodyReads=0;byte[] bytes=reader.read(0,16);if(bytes.length!=16||bodyReads!=16)throw new AssertionError("200 prefix must be bounded");
  bodyReads=0;reject(reader,100);if(bodyReads!=0)throw new AssertionError("200 cannot be used for nonzero range");
  mode=1;reject(reader,100);if(bodyReads!=0)throw new AssertionError("wrong Content-Range must never be consumed");
  mode=2;bytes=reader.read(100,16);if(bytes.length!=16||latest.getInstanceFollowRedirects()||!"bytes=100-115".equals(latest.getRequestProperty("Range")))throw new AssertionError("exact range and no redirects");
  if(latest.getRequestProperty("Authorization")!=null||!"generated".equals(latest.getRequestProperty("Referer")))throw new AssertionError("only existing allowlisted source headers");
  mode=3;bodyReads=0;int before=requests;reject(reader,0);if(bodyReads!=0||requests!=before+1)throw new AssertionError("redirect must not forward source context");
  current.set(false);before=requests;try{reader.read(0,16);throw new AssertionError("cancel");}catch(InterruptedException expected){}if(requests!=before)throw new AssertionError("stale account must not start request");
  System.out.println("PASS: range bounds, redirect/header isolation and owner cancellation");
 }
}''', ["PlayerChapters.java", "PlayerChapterReader.java", "StreamPolicy.java", "PlaybackPolicy.java"])

    def test_real_mkv_seekhead_mp4_chpl_and_parser_limits(self):
        chapters = ebml(0x1043A770, ebml(0x45B9, atom(0, "Opening") + atom(1_200_000, "Credits")))
        cluster = ebml(0x1F43B675, bytes(80_000))
        def seek(position):
            return ebml(0x114D9B74, ebml(0x4DBB, ebml(0x53AB, bytes.fromhex("1043a770")) + ebml(0x53AC, position.to_bytes(4, "big"))))
        head = seek(len(seek(0)) + len(cluster))
        mkv = ebml(0x1A45DFA3, b"") + ebml(0x18538067, head + cluster + chapters)
        chpl = b"\x01\0\0\0" + bytes(4) + b"\x02"
        for time, title in [(0, b"Opening"), (1_200_000, b"Credits")]:
            chpl += struct.pack(">Q", time * 10_000) + bytes([len(title)]) + title
        mp4 = box("ftyp", b"isom" + bytes(4)) + box("mdat", bytes(80_000)) + box("moov", box("udta", box("chpl", chpl)))
        with tempfile.TemporaryDirectory() as tmp:
            paths = [Path(tmp) / "generated.mkv", Path(tmp) / "generated.mp4"]
            for path, data in zip(paths, [mkv, mp4]):
                path.write_bytes(data)
            self.run_java("ChaptersHarness", r'''package tv.noiro.launcher;
import java.nio.file.*;import java.util.*;
public class ChaptersHarness {
 public static void main(String[] args)throws Exception{
  for(String path:args){byte[] bytes=Files.readAllBytes(Paths.get(path));int[] reads={0},total={0};List<PlayerChapters.Chapter> chapters=PlayerChapters.read((offset,limit)->{reads[0]++;if(limit>65536||offset<0)throw new AssertionError("read bounds");if(offset>=bytes.length)return new byte[0];byte[] data=Arrays.copyOfRange(bytes,(int)offset,(int)Math.min(bytes.length,offset+limit));total[0]+=data.length;return data;});if(chapters.size()!=2||chapters.get(1).startMs!=1200000||!chapters.get(1).title.equals("Credits"))throw new AssertionError("actual chapter metadata");if(reads[0]>6||total[0]>2*1024*1024)throw new AssertionError("bounded sparse reads");}
  for(byte[] malformed:new byte[][]{new byte[0],new byte[1],new byte[]{0,0,0,1,109,111,111,118},new byte[]{26,69,(byte)223,(byte)163,(byte)128}})if(!PlayerChapters.read((o,n)->o>=malformed.length?new byte[0]:Arrays.copyOfRange(malformed,(int)o,malformed.length)).isEmpty())throw new AssertionError("truncation must fail soft");
  int[] calls={0};PlayerChapters.read((o,n)->{calls[0]++;byte[] endless=new byte[n];for(int i=0;i+8<n;i+=8){endless[i+3]=8;endless[i+4]='f';endless[i+5]='r';endless[i+6]='e';endless[i+7]='e';}return endless;});if(calls[0]>48)throw new AssertionError("request budget");
  System.out.println("PASS: MKV SeekHead, MP4 tail chapters, malformed files and strict metadata budgets");
 }
}''', ["PlayerChapters.java"], paths)
