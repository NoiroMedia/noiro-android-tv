"""Exercise real account discovery, server leases and stale workers without a device or network."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "launcher-ui/app/src/tv/noiro/launcher"


class SupporterRecognitionTests(unittest.TestCase):
    def test_retry_schedule_does_not_become_an_entitlement(self):
        harness = r'''package tv.noiro.launcher;
public class DiscoveryHarness {
 static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
 public static void main(String[] args){
  SupporterDiscovery d=new SupporterDiscovery();long now=0;
  check(d.status(false,false)==SupporterDiscovery.Status.SIGNED_OUT,"No signed-in account has no membership claim");
  check(d.status(true,false)==SupporterDiscovery.Status.UNCHECKED,"Unchecked does not mean free");
  for(long wait:new long[]{30000,60000,120000,240000,300000,300000}){
   check(d.begin(now,false),"Retry becomes eligible at deadline");
   check(!d.begin(now,true),"Force cannot duplicate an in-flight request");
   d.failure(SupporterDiscovery.Status.UNAVAILABLE,now);d.finish();
   check(d.status(true,false)==SupporterDiscovery.Status.UNAVAILABLE,"Outage does not imply nonmembership");
   check(!d.begin(now+wait-1,false),"Backoff prevents repeated foreground requests");now+=wait;
  }
  for(SupporterDiscovery.Status result:new SupporterDiscovery.Status[]{SupporterDiscovery.Status.FREE,SupporterDiscovery.Status.LINK_REQUIRED}){
   check(d.begin(now,true),"Manual action can retry during backoff");d.failure(result,now);d.finish();
   check(d.status(true,false)==result,"Distinct denial reason is retained");
   check(!d.begin(now+899999,false),"Definitive denial has a longer quiet period");now+=900000;
  }
  check(d.begin(now,false),"Long denial backoff eventually permits discovery after website changes");
  d.success(now,now+300000);d.finish();
  check(d.status(true,false)==SupporterDiscovery.Status.UNCHECKED,"Past success alone is never an active entitlement");
  check(d.status(true,true)==SupporterDiscovery.Status.SUPPORTER,"Only supplied real lease status authorizes paid display");
  check(!d.begin(now+59999,false)&&d.begin(now+60000,false),"Active access renews once per minute");
  d.failure(SupporterDiscovery.Status.UNAVAILABLE,now+60000);d.finish();
  check(d.status(true,true)==SupporterDiscovery.Status.SUPPORTER,"Transient failure does not replace a still-valid server lease");
  check(d.status(true,false)==SupporterDiscovery.Status.UNAVAILABLE,"Expired lease does not inherit offline paid status");
  System.out.println("PASS: bounded retry timing, dedupe, denial distinctions and no cached entitlement");
 }
}'''
        java = Path(os.environ["JAVA_HOME"]) / "bin"
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "DiscoveryHarness.java"
            path.write_text(harness)
            subprocess.run([str(java / "javac"), "-d", tmp, str(SOURCE / "SupporterDiscovery.java"), str(path)], check=True)
            subprocess.run([str(java / "java"), "-cp", tmp, "tv.noiro.launcher.DiscoveryHarness"], check=True)

    def test_paid_discovery_independent_of_metadata_and_bound_to_login(self):
        doubles = {
            "android/content/Context.java": "package android.content; public class Context { public Context getApplicationContext(){return this;} }",
            "android/os/SystemClock.java": "package android.os; public class SystemClock { public static long now=1000; public static long elapsedRealtime(){return now;} }",
            "org/json/JSONObject.java": r'''package org.json;
import java.util.*;
public class JSONObject {
 final Map<String,Object> values=new HashMap<>();
 public JSONObject put(String key,Object value){values.put(key,value);return this;}
 public Object opt(String key){return values.get(key);}
 public String optString(String key){Object value=opt(key);return value==null?"":value.toString();}
 public long optLong(String key,long fallback){Object value=opt(key);return value instanceof Number?((Number)value).longValue():fallback;}
 public JSONObject optJSONObject(String key){Object value=opt(key);return value instanceof JSONObject?(JSONObject)value:null;}
 public boolean optBoolean(String key){return Boolean.TRUE.equals(opt(key));}
}''',
            "tv/noiro/launcher/AccountHttp.java": r'''package tv.noiro.launcher;
import org.json.JSONObject;
import java.util.*;
final class AccountHttp {
 static final class Failure extends Exception {final String code;Failure(String code){super(code);this.code=code;}}
 static final class Reply {final String path;final Object body;final Runnable hook;Reply(String p,Object b,Runnable h){path=p;body=b;hook=h;}}
 static final Queue<Reply> replies=new ArrayDeque<>();static int calls;
 static void add(String path,Object body){add(path,body,null);}
 static void add(String path,Object body,Runnable hook){replies.add(new Reply(path,body,hook));}
 static JSONObject call(String url,JSONObject body,String bearer,int timeout)throws Failure{return call(url,body,bearer);}
 static JSONObject call(String url,JSONObject body,String bearer)throws Failure{
  calls++;Reply reply=replies.poll();
  if(reply==null||!url.equals("https://noiro.tv/api/stremio-for-kodi/v1/"+reply.path))throw new AssertionError("Unexpected account request");
  if(reply.hook!=null)reply.hook.run();
  if(reply.body instanceof Failure)throw (Failure)reply.body;
  return (JSONObject)reply.body;
 }
}''',
            "tv/noiro/launcher/AppIdentity.java": "package tv.noiro.launcher; final class AppIdentity {static boolean free=true;static boolean standalone(android.content.Context c){return free;}}",
            "tv/noiro/launcher/SessionVault.java": r'''package tv.noiro.launcher;
final class SessionVault {
 static AccountState saved=AccountState.EMPTY;static boolean failSave;
 SessionVault(android.content.Context c){}
 AccountState load(){return saved;}
 void save(AccountState value)throws Exception{if(failSave)throw new Exception("storage unavailable");saved=value;}
 void clear(){saved=AccountState.EMPTY;}
}''',
            "tv/noiro/launcher/WatchProgress.java": "package tv.noiro.launcher; final class WatchProgress {static void clear(){}}",
            "tv/noiro/launcher/Tmdb.java": "package tv.noiro.launcher; final class Tmdb {static int changes;static void credentialsChanged(android.content.Context c){changes++;}}",
            "tv/noiro/launcher/Prefs.java": "package tv.noiro.launcher; final class Prefs {static int version;static String tmdbKey(){return NoiroAccount.tmdbKey();}}",
            "tv/noiro/launcher/Recs.java": "package tv.noiro.launcher; final class Recs {static void clearRatingsCache(){}}",
            "tv/noiro/launcher/RecognitionHarness.java": r'''package tv.noiro.launcher;
import org.json.JSONObject;
import android.os.SystemClock;
public class RecognitionHarness {
 interface Attempt {void run()throws Exception;}
 static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
 static AccountState identity(String key,String uid){return new AccountState(key,uid,"Synthetic account","",0);}
 static JSONObject session(){return new JSONObject().put("accessToken","synthetic-device-token").put("expiresAt",System.currentTimeMillis()/1000+3600);}
 static JSONObject grant(String uid,Object active,Object seconds){return new JSONObject().put("stremioUid",uid).put("access",new JSONObject().put("active",active).put("expiresIn",seconds));}
 static JSONObject metadata(String uid,boolean configured,String key){return new JSONObject().put("stremioUid",uid).put("tmdb",new JSONObject().put("configured",configured).put("apiKey",key));}
 static void status(SupporterDiscovery.Status expected){check(NoiroAccount.supporterStatus()==expected,"Expected status "+expected+", got "+NoiroAccount.supporterStatus());}
 static void fail(String code,Attempt action)throws Exception{
  try{action.run();throw new AssertionError("Expected failure "+code);}
  catch(AccountHttp.Failure failure){check(code.equals(failure.code),"Wrong failure");}
 }
 static void remember(String key,String uid)throws Exception{NoiroAccount.remember(identity(key,uid));}
 static NoiroAccount.SupporterCheck start(boolean force){NoiroAccount.SupporterCheck check=NoiroAccount.beginSupporterCheck(force);check(check!=null,"Discovery should start");return check;}
 static void paid(String uid,Object metadata)throws Exception{
  AccountHttp.add("session",session());AccountHttp.add("launcher-access",grant(uid,true,300));AccountHttp.add("launcher-tmdb",metadata);
  NoiroAccount.checkSupporter(start(false));check(AccountHttp.replies.isEmpty(),"Queued replies consumed");
 }
 static Object field(String name)throws Exception{java.lang.reflect.Field field=NoiroAccount.class.getDeclaredField(name);field.setAccessible(true);return field.get(null);}
 public static void main(String[] args)throws Exception{
  // An encrypted signed-in snapshot is not a paid entitlement on a new process.
  SessionVault.saved=identity("cold-auth","cold-uid");NoiroAccount.init(new android.content.Context());
  status(SupporterDiscovery.Status.UNCHECKED);check(!NoiroAccount.ready(),"Cold snapshot cannot authorize cloud");
  int before=AccountHttp.calls;
  fail("supporter_required",()->NoiroAccount.updateToken());check(AccountHttp.calls==before,"Unverified cloud feature never goes to the network");
  paid("cold-uid",metadata("cold-uid",false,""));
  check(NoiroAccount.ready()&&NoiroAccount.current().tmdbKey.isEmpty(),"Paid customer without TMDB is recognized");status(SupporterDiscovery.Status.SUPPORTER);
  check(NoiroAccount.beginSupporterCheck(false)==null,"Success is throttled");
  AccountHttp.add("player-state",new JSONObject().put("stremioUid","cold-uid"));
  NoiroAccount.playerState("",null,NoiroAccount.current());check(NoiroAccount.ready(),"Paid cloud works without metadata credentials");
  AccountHttp.add("launcher-access",grant("cold-uid",true,300));AccountHttp.add("launcher-tmdb",metadata("cold-uid",true,"a".repeat(32)));
  NoiroAccount.checkSupporter(start(true));check(NoiroAccount.tmdbKey().equals("a".repeat(32)),"Manual check immediately imports a newly configured website key despite metadata backoff");

  // A pending check is shared by Home/Settings/Onboarding; even forced requests cannot duplicate it.
  remember("paid-auth","paid-uid");NoiroAccount.SupporterCheck pending=start(false);status(SupporterDiscovery.Status.CHECKING);
  check(NoiroAccount.beginSupporterCheck(false)==null&&NoiroAccount.beginSupporterCheck(true)==null,"In-flight discovery is deduplicated");
  AccountHttp.add("session",session());AccountHttp.add("launcher-access",grant("paid-uid",true,300));
  AccountHttp.add("launcher-tmdb",new AccountHttp.Failure("service_unavailable"));NoiroAccount.checkSupporter(pending);
  check(NoiroAccount.ready(),"Optional metadata outage cannot revoke paid membership");status(SupporterDiscovery.Status.SUPPORTER);

  // Invalid/missing optional credentials and failed credential persistence affect metadata only.
  for(Object optional:new Object[]{metadata("optional-uid",true,"not-a-valid-personal-key"),new AccountHttp.Failure("tmdb_reconnect_required"),new JSONObject().put("stremioUid","optional-uid")}){
   remember("optional-auth","optional-uid");paid("optional-uid",optional);
   check(NoiroAccount.ready(),"Metadata setup cannot determine Premium access");status(SupporterDiscovery.Status.SUPPORTER);
  }
  remember("storage-auth","storage-uid");AccountHttp.add("session",session());AccountHttp.add("launcher-access",grant("storage-uid",true,300));
  AccountHttp.add("launcher-tmdb",metadata("storage-uid",true,"a".repeat(32)),()->SessionVault.failSave=true);
  NoiroAccount.checkSupporter(start(false));SessionVault.failSave=false;check(NoiroAccount.ready(),"Failure to cache a metadata key does not revoke a real in-memory paid lease");
  remember("paid-auth","paid-uid");paid("paid-uid",metadata("paid-uid",false,""));

  // No local workaround may lengthen a short lease or turn an outage into paid access.
  long grantedAt=SystemClock.now;SystemClock.now=grantedAt+60_000;
  AccountHttp.add("launcher-access",new AccountHttp.Failure("service_unavailable"));
  fail("service_unavailable",()->NoiroAccount.checkSupporter(start(false)));status(SupporterDiscovery.Status.SUPPORTER);
  SystemClock.now=grantedAt+300_000;check(!NoiroAccount.ready(),"Original monotonic lease deadline remains exact");status(SupporterDiscovery.Status.UNAVAILABLE);
  before=AccountHttp.calls;fail("supporter_required",()->NoiroAccount.updateToken());check(AccountHttp.calls==before,"Expired feature access is blocked locally");
  AccountHttp.add("launcher-access",grant("paid-uid",true,2));NoiroAccount.checkSupporter(start(false));
  long shortLease=SystemClock.now;check(NoiroAccount.ready(),"Short valid server lease accepted");
  SystemClock.now=shortLease+2000;check(!NoiroAccount.ready(),"Short lease was not replaced by a default duration");

  // Missing, malformed or mismatched authority cannot authorize a customer.
  for(JSONObject malformed:new JSONObject[]{grant("paid-uid","true",300),grant("paid-uid",true,"300"),grant("paid-uid",true,1.5),new JSONObject().put("stremioUid","paid-uid")}){
   AccountHttp.add("launcher-access",malformed);fail("server_update_required",()->NoiroAccount.checkSupporter(start(true)));
   check(!NoiroAccount.ready(),"Malformed grant never unlocks paid features");status(SupporterDiscovery.Status.UNAVAILABLE);
   AccountHttp.add("session",session()); // Revocation discarded the previous device token.
  }
  AccountHttp.replies.clear();

  for(long invalidSeconds:new long[]{0,-1,301,Long.MAX_VALUE}){
   remember("bounds-auth","bounds-uid");AccountHttp.add("session",session());AccountHttp.add("launcher-access",grant("bounds-uid",true,invalidSeconds));
   fail("server_update_required",()->NoiroAccount.checkSupporter(start(false)));status(SupporterDiscovery.Status.UNAVAILABLE);
   check(!NoiroAccount.ready(),"Invalid positive grant cannot be interpreted as either paid or confirmed free");
  }
  remember("slow-auth","slow-uid");AccountHttp.add("session",session());
  AccountHttp.add("launcher-access",grant("slow-uid",true,1),()->SystemClock.now+=1000);
  fail("service_unavailable",()->NoiroAccount.checkSupporter(start(false)));status(SupporterDiscovery.Status.UNAVAILABLE);
  check(!NoiroAccount.ready(),"Grant expired in transit never unlocks access or claims nonmembership");

  // Definitive nonmember/unlinked results retain genuine Stremio access and back off automatic checks.
  for(String denial:new String[]{"supporter_required","account_link_required","authentication_required","account_unavailable"}){
   remember("free-auth","free-uid");AccountHttp.add("session",session());AccountHttp.add("launcher-access",new AccountHttp.Failure(denial));
   fail(denial,()->NoiroAccount.checkSupporter(start(false)));
   status(denial.equals("supporter_required")?SupporterDiscovery.Status.FREE:denial.equals("account_link_required")?SupporterDiscovery.Status.LINK_REQUIRED:SupporterDiscovery.Status.UNAVAILABLE);
   check(NoiroAccount.current().signedIn()&&AppAccess.ready(new android.content.Context())&&!NoiroAccount.ready(),"Cloud denial cannot sign out free Stremio");
   check(NoiroAccount.beginSupporterCheck(false)==null,"Denied account is not checked on every foreground tick");
   NoiroAccount.SupporterCheck manual=start(true); // Explicit retry bypasses backoff.
   AccountHttp.add("session",session());AccountHttp.add("launcher-access",new AccountHttp.Failure(denial));fail(denial,()->NoiroAccount.checkSupporter(manual));
  }

  // First network outage is not evidence of a free membership; retries use bounded backoff.
  remember("offline-auth","offline-uid");AccountHttp.add("session",new AccountHttp.Failure("service_unavailable"));
  fail("service_unavailable",()->NoiroAccount.checkSupporter(start(false)));status(SupporterDiscovery.Status.UNAVAILABLE);
  long failedAt=SystemClock.now;SystemClock.now=failedAt+29_999;check(NoiroAccount.beginSupporterCheck(false)==null,"No retry storm during transient outage");
  SystemClock.now=failedAt+30_000;paid("offline-uid",metadata("offline-uid",true,"a".repeat(32)));
  check(NoiroAccount.ready()&&NoiroAccount.tmdbKey().equals("a".repeat(32)),"Retry discovers paid customer and optional key");

  // Logout/account switches before dispatch and during either request cannot grant another login.
  remember("queued-auth","queued-uid");NoiroAccount.SupporterCheck stale=start(false);
  remember("new-auth","new-uid");before=AccountHttp.calls;NoiroAccount.checkSupporter(stale);check(AccountHttp.calls==before,"Stale queued worker makes no request");status(SupporterDiscovery.Status.UNCHECKED);
  stale=start(false);AccountHttp.add("session",session(),()->{try{remember("next-auth","next-uid");}catch(Exception error){throw new AssertionError(error);}});
  NoiroAccount.checkSupporter(stale);check(!NoiroAccount.ready(),"Old token response cannot verify new account");status(SupporterDiscovery.Status.UNCHECKED);
  NoiroAccount.SupporterCheck switched=start(false);AccountHttp.add("session",session());
  AccountHttp.add("launcher-access",grant("next-uid",true,300),()->{try{NoiroAccount.signOut();remember("next-auth","next-uid");}catch(Exception error){throw new AssertionError(error);}});
  NoiroAccount.checkSupporter(switched);check(!NoiroAccount.ready(),"Even re-login to the same UID/auth invalidates old account-generation ticket");status(SupporterDiscovery.Status.UNCHECKED);
  AccountHttp.add("session",session());AccountHttp.add("launcher-access",grant("wrong-uid",true,300));
  fail("identity_mismatch",()->NoiroAccount.checkSupporter(start(false)));check(!NoiroAccount.ready(),"Wrong UID cannot obtain a paid lease");

  // A hosted-feature denial also invalidates a concurrent old discovery response.
  remember("revoked-auth","revoked-uid");paid("revoked-uid",metadata("revoked-uid",false,""));
  NoiroAccount.SupporterCheck concurrent=start(true);AccountHttp.add("launcher-access",grant("revoked-uid",true,300),()->{
   AccountHttp.add("player-state",new AccountHttp.Failure("supporter_required"));
   try{fail("supporter_required",()->NoiroAccount.playerState("",null,NoiroAccount.current()));}catch(Exception error){throw new AssertionError(error);}
  });NoiroAccount.checkSupporter(concurrent);check(!NoiroAccount.ready(),"Older positive response cannot undo newer feature revocation");status(SupporterDiscovery.Status.FREE);

  // Optional metadata denial is not a member bypass: the server can revoke between endpoints.
  remember("changed-auth","changed-uid");AccountHttp.add("session",session());AccountHttp.add("launcher-access",grant("changed-uid",true,300));
  AccountHttp.add("launcher-tmdb",new AccountHttp.Failure("supporter_required"));
  fail("supporter_required",()->NoiroAccount.checkSupporter(start(false)));check(!NoiroAccount.ready(),"Authoritative revocation during metadata also removes entitlement");status(SupporterDiscovery.Status.FREE);

  // Legacy launcher still requires its own valid metadata key; standalone does not.
  remember("legacy-auth","legacy-uid");AppIdentity.free=false;
  AccountHttp.add("session",session());AccountHttp.add("launcher-tmdb",grant("legacy-uid",true,300).put("tmdb",new JSONObject().put("configured",false)));
  fail("tmdb_not_connected",()->NoiroAccount.sync());check(!NoiroAccount.ready(),"Legacy metadata requirement retained");
  check(NoiroAccount.beginSupporterCheck(true)==null,"Standalone discovery is not used by legacy launcher");
  NoiroAccount.signOut();status(SupporterDiscovery.Status.SIGNED_OUT);check(AccountHttp.replies.isEmpty(),"All expected requests consumed");
  System.out.println("PASS: real membership discovery, optional metadata, lease expiry, retries, dedupe and stale login races");
 }
}''',
        }
        java = Path(os.environ["JAVA_HOME"]) / "bin"
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            sources = []
            for name, text in doubles.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(text)
                sources.append(str(path))
            sources += [str(SOURCE / name) for name in (
                "NoiroAccount.java", "AccountState.java", "SupporterLease.java", "SupporterDiscovery.java", "TmdbCredential.java", "AppAccess.java")]
            subprocess.run([str(java / "javac"), "-d", tmp, *sources], check=True)
            subprocess.run([str(java / "java"), "-cp", tmp, "tv.noiro.launcher.RecognitionHarness"], check=True)
