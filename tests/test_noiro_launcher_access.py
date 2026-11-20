"""Execute real account revocation paths using bounded local Android/HTTP doubles."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class AccountAccessTests(unittest.TestCase):
    def test_revocation_validation_account_switch_and_outage(self):
        java = shutil.which('java')
        if not java:
            self.skipTest('JDK 17 is required')
        source = ROOT / 'launcher-ui/app/src/tv/noiro/launcher'
        doubles = {
            'android/content/Context.java': '''package android.content;
public class Context { public Context getApplicationContext(){return this;} }
''',
            'android/os/SystemClock.java': '''package android.os;
public class SystemClock { public static long now=1000; public static long elapsedRealtime(){return now;} }
''',
            'org/json/JSONObject.java': '''package org.json;
import java.util.*;
public class JSONObject {
  private final Map<String,Object> values=new HashMap<>();
  public JSONObject put(String key,Object value){values.put(key,value);return this;}
  public Object opt(String key){return values.get(key);}
  public String optString(String key){Object v=opt(key);return v==null?"":v.toString();}
  public long optLong(String key,long fallback){Object v=opt(key);return v instanceof Number?((Number)v).longValue():fallback;}
  public JSONObject optJSONObject(String key){Object v=opt(key);return v instanceof JSONObject?(JSONObject)v:null;}
  public boolean optBoolean(String key){return Boolean.TRUE.equals(opt(key));}
}
''',
            'tv/noiro/launcher/AccountHttp.java': '''package tv.noiro.launcher;
import org.json.JSONObject;
import java.util.*;
final class AccountHttp {
  static JSONObject call(String url,JSONObject body,String bearer,int timeout) throws Failure {return call(url,body,bearer);}
  static final class Failure extends Exception {final String code;Failure(String code){super(code);this.code=code;}}
  static final Queue<Object> replies=new ArrayDeque<>();
  static int calls;
  static Runnable beforeReply;
  static JSONObject call(String url,JSONObject body,String bearer) throws Failure {
    calls++;
    if(!url.startsWith("https://noiro.tv/api/"))throw new AssertionError("Unexpected account origin");
    if(beforeReply!=null){Runnable hook=beforeReply;beforeReply=null;hook.run();}
    Object reply=replies.remove();if(reply instanceof Failure)throw (Failure)reply;return (JSONObject)reply;
  }
}
''',
            'tv/noiro/launcher/AppIdentity.java': '''package tv.noiro.launcher;
final class AppIdentity {static boolean free;static boolean standalone(android.content.Context context){return free;}}
''',
            'tv/noiro/launcher/WatchProgress.java': '''package tv.noiro.launcher;
final class WatchProgress {static void clear(){}}
''',
            'tv/noiro/launcher/SessionVault.java': '''package tv.noiro.launcher;
import android.content.Context;
final class SessionVault {
  static AccountState saved=AccountState.EMPTY;static boolean failSave;
  SessionVault(Context context){}
  AccountState load(){return saved;}
  void save(AccountState value) throws Exception {if(failSave)throw new Exception("fixture storage unavailable");saved=value;}
  void clear(){saved=AccountState.EMPTY;}
}
''',
            'tv/noiro/launcher/Tmdb.java': '''package tv.noiro.launcher;
final class Tmdb {static void credentialsChanged(android.content.Context context){}}
''',
            'tv/noiro/launcher/Prefs.java': '''package tv.noiro.launcher;
final class Prefs {static int version;static String tmdbKey(){return NoiroAccount.current().tmdbKey;}}
''',
            'tv/noiro/launcher/Recs.java': '''package tv.noiro.launcher;
final class Recs {static int clears;static void clearRatingsCache(){clears++;}}
''',
            'tv/noiro/launcher/AccessHarness.java': '''package tv.noiro.launcher;
import org.json.JSONObject;
import android.os.SystemClock;
public class AccessHarness {
  interface Attempt {void run()throws Exception;}
  static void check(boolean condition){if(!condition)throw new AssertionError();}
  static JSONObject session(){return new JSONObject().put("accessToken","device-access").put("expiresAt",System.currentTimeMillis()/1000+3600);}
  static JSONObject response(Object active,Object seconds){return new JSONObject().put("stremioUid","uid").put("access",new JSONObject().put("active",active).put("expiresIn",seconds)).put("tmdb",new JSONObject().put("configured",true).put("apiKey","aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));}
  static Object field(String name) throws Exception {java.lang.reflect.Field f=NoiroAccount.class.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
  static void prepare() throws Exception {
    SessionVault.failSave=false;SystemClock.now=1000;AccountHttp.replies.clear();AccountHttp.beforeReply=null;
    NoiroAccount.remember(new AccountState("auth","uid","Saved account","",0));
    AccountHttp.replies.add(session());AccountHttp.replies.add(response(true,300));NoiroAccount.sync();check(NoiroAccount.ready());
  }
  static void expectFailure(String code,boolean sync) throws Exception {
    try {if(sync)NoiroAccount.sync();else NoiroAccount.refreshAccess();throw new AssertionError("Access was granted");}
    catch(AccountHttp.Failure failure){check(failure.code.equals(code));}
  }
  static void revoked() throws Exception {
    check(!NoiroAccount.ready());check(NoiroAccount.current().signedIn());check(NoiroAccount.current().tmdbKey.isEmpty());
    check(field("deviceAccess").equals(""));check((Long)field("accessUntil")==0);
  }
  public static void main(String[] args) throws Exception {
    NoiroAccount.init(new android.content.Context());
    prepare();AccountHttp.replies.add(new AccountHttp.Failure("supporter_required"));expectFailure("supporter_required",false);revoked();
    prepare();AccountHttp.replies.add(response(false,300));expectFailure("supporter_required",false);revoked();
    for(JSONObject malformed:new JSONObject[]{new JSONObject().put("stremioUid","uid"),response("true",300),response(true,"300"),response(true,1.5)}) {
      prepare();AccountHttp.replies.add(malformed);expectFailure("server_update_required",false);revoked();
    }
    prepare();AccountHttp.replies.add(session());AccountHttp.replies.add(new JSONObject().put("stremioUid","uid"));expectFailure("server_update_required",true);revoked();
    prepare();AccountHttp.replies.add(new AccountHttp.Failure("supporter_required"));expectFailure("supporter_required",true);revoked();
    prepare();AccountHttp.replies.add(new AccountHttp.Failure("service_unavailable"));expectFailure("service_unavailable",false);
    check(NoiroAccount.ready());SystemClock.now=301000;check(!NoiroAccount.ready());
    prepare();AccountHttp.replies.add(new AccountHttp.Failure("supporter_required"));SessionVault.failSave=true;
    try{NoiroAccount.refreshAccess();throw new AssertionError();}catch(Exception expected){}revoked();SessionVault.failSave=false;
    prepare();AccountHttp.replies.add(new AccountHttp.Failure("supporter_required"));
    AccountHttp.beforeReply=()->{try{NoiroAccount.remember(new AccountState("new-auth","new-uid","New account","",0));}catch(Exception e){throw new AssertionError(e);}};
    expectFailure("supporter_required",false);check(NoiroAccount.current().uid.equals("new-uid"));check(NoiroAccount.current().authKey.equals("new-auth"));check(!NoiroAccount.ready());
    check(field("deviceAccess").equals(""));
    AppIdentity.free=true;
    NoiroAccount.signOut();
    check(AppAccess.canBrowse(new android.content.Context()));
    check(!AppAccess.ready(new android.content.Context())&&!NoiroAccount.ready());
    AccountState local=new AccountState("free-auth","free-uid","Free Stremio","",0);
    NoiroAccount.remember(local);
    check(AppAccess.ready(new android.content.Context())&&!NoiroAccount.ready()&&!NoiroAccount.configured());
    check(!NoiroAccount.beginRefresh()&&!NoiroAccount.beginAccessRefresh());
    int requests=AccountHttp.calls;
    for(Attempt feature:new Attempt[]{()->NoiroAccount.updateToken(local),()->NoiroAccount.progress(null,local),
        ()->NoiroAccount.subtitleRequest("https://noiro.tv/api/subtitles",null,local),
        ()->NoiroAccount.playerState("",null,local),()->NoiroAccount.ratings("movie",1)}){
      try{feature.run();throw new AssertionError("Free session acquired hosted access");}
      catch(AccountHttp.Failure expected){check(expected.code.equals("supporter_required"));}
    }
    check(AccountHttp.calls==requests);
    for(String denial:new String[]{"supporter_required","account_link_required","authentication_required"}){
      AccountHttp.replies.add(new AccountHttp.Failure(denial));expectFailure(denial,true);
      check(NoiroAccount.current().owns(local.authKey,local.uid));
      check(AppAccess.ready(new android.content.Context())&&!NoiroAccount.ready()&&!NoiroAccount.beginAccessRefresh());
    }
    prepare();
    AccountState paid=NoiroAccount.current();
    AccountHttp.beforeReply=()->SystemClock.now=301000;
    AccountHttp.replies.add(new JSONObject().put("stremioUid",paid.uid));
    try{NoiroAccount.progress(null,paid);throw new AssertionError("Expired response granted cloud access");}
    catch(AccountHttp.Failure expected){check(expected.code.equals("supporter_required"));}
    check(!NoiroAccount.ready()&&NoiroAccount.configured()&&AppAccess.ready(new android.content.Context()));
    check(NoiroAccount.beginAccessRefresh());NoiroAccount.endAccessRefresh();
    prepare();
    AccountHttp.replies.add(new AccountHttp.Failure("supporter_required"));
    try{NoiroAccount.progress(null,NoiroAccount.current());throw new AssertionError();}
    catch(AccountHttp.Failure expected){check(expected.code.equals("supporter_required"));}
    check(AppAccess.ready(new android.content.Context())&&!NoiroAccount.ready()&&!NoiroAccount.configured());
    NoiroAccount.signOut();
    check(AppAccess.canBrowse(new android.content.Context())&&!AppAccess.ready(new android.content.Context()));
  }
}
''',
        }
        with tempfile.TemporaryDirectory() as temporary:
            tmp = Path(temporary)
            paths = []
            for relative, content in doubles.items():
                path = tmp / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content)
                paths.append(str(path))
            paths += [str(source / name) for name in ('NoiroAccount.java', 'AccountState.java', 'SupporterLease.java', 'TmdbCredential.java', 'AppAccess.java')]
            # The compiler module also works in managed environments that omit
            # the javac convenience executable while retaining the full JDK.
            subprocess.run([java, 'com.sun.tools.javac.Main', '-d', temporary, *paths], check=True)
            subprocess.run([java, '-cp', temporary, 'tv.noiro.launcher.AccessHarness'], check=True)
