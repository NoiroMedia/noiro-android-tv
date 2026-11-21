"""Real trailer request tickets survive catalog refresh, while canceled/account-switched requests cannot play."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "launcher-ui/app/src/tv/noiro/launcher"


class TrailerRequestTests(unittest.TestCase):
    def test_request_lifetime_is_page_and_account_bound_not_catalog_generation(self):
        harness = r'''package tv.noiro.launcher;
public class TrailerRequestHarness {
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 public static void main(String[] args){
  TrailerPlaybackPolicy requests=new TrailerPlaybackPolicy();
  AccountState owner=new AccountState("synthetic-auth","synthetic-uid","Synthetic account","",0);
  Object page=new Object();int catalogGeneration=1;
  TrailerPlaybackPolicy.Ticket clicked=requests.begin(page,owner);
  check(requests.current(clicked,page,owner,true),"Accepted click belongs to the visible page");
  catalogGeneration++;catalogGeneration++; // Rows and metadata refresh while trailer resolution is in flight.
  check(requests.current(clicked,page,owner,true),"Background catalog generations must not discard a trailer click");
  AccountState metadataRefreshed=new AccountState(owner.authKey,owner.uid,owner.label,"a".repeat(32),123);
  check(requests.current(clicked,page,metadataRefreshed,true),"Same login with refreshed optional metadata remains the same request owner");
  check(!requests.current(clicked,new Object(),owner,true),"An internal page change prevents playback");
  check(!requests.current(clicked,page,owner,false),"A paused view cannot launch a player");
  requests.cancel();
  check(!requests.current(clicked,page,owner,true)&&!requests.finish(clicked),"Back/pause/destroy cancellation also rejects late completion after resume");
  TrailerPlaybackPolicy.Ticket first=requests.begin(page,owner);
  TrailerPlaybackPolicy.Ticket second=requests.begin(page,owner);
  check(!requests.current(first,page,owner,true)&&requests.current(second,page,owner,true),"A newer click replaces the old intent");
  check(!requests.finish(first)&&requests.pending(second),"An old callback cannot clear or complete a newer request");
  check(!requests.current(second,page,new AccountState("changed-auth",owner.uid,owner.label,"",0),true),"A new authentication session cannot play an old request");
  check(!requests.current(second,page,new AccountState(owner.authKey,"changed-uid",owner.label,"",0),true),"A different account UID cannot play an old request");
  check(!requests.current(second,page,AccountState.EMPTY,true),"Sign-out prevents playback");
  requests.cancel(); // Account switch/close invalidates even a subsequent re-login to the same identity.
  check(!requests.current(second,page,owner,true),"Returning to the old identity does not resurrect canceled work");
  TrailerPlaybackPolicy.Ticket finalClick=requests.begin(page,owner);
  check(requests.finish(finalClick)&&!requests.finish(finalClick),"A successful or failed response is consumed exactly once");
  check(requests.begin(page,AccountState.EMPTY)==null,"Unsigned state cannot start a protected trailer request");
  check(TrailerPlaybackPolicy.failureMessage("trailer_unavailable").contains("Plex"),"Provider failure explains that this trailer is unavailable");
  check(TrailerPlaybackPolicy.failureMessage("supporter_required").contains("cloud"),"Access failure points to cloud verification");
  check(TrailerPlaybackPolicy.failureMessage("rate_limited").contains("Wait"),"Rate limit gives a retry instruction");
  String untrusted="https://private.invalid/video?token=synthetic-secret";
  check(!TrailerPlaybackPolicy.failureMessage(untrusted).contains(untrusted),"Raw failures/media links never enter viewer messages");
  System.out.println("PASS: trailer refresh independence, account/page ownership, cancellation, replacement and safe errors");
 }
}'''
        java = Path(os.environ["JAVA_HOME"]) / "bin"
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "TrailerRequestHarness.java"
            path.write_text(harness)
            subprocess.run([str(java / "javac"), "-d", tmp,
                            str(SOURCE / "AccountState.java"), str(SOURCE / "TrailerPlaybackPolicy.java"), str(path)], check=True)
            subprocess.run([str(java / "java"), "-cp", tmp, "tv.noiro.launcher.TrailerRequestHarness"], check=True)
