"""Run the real public/cloud Home coordinator through account and asynchronous result sequences."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class SupporterHomeTests(unittest.TestCase):
    def test_paid_keyless_fallback_and_custom_profile_isolation(self):
        java_home = os.environ.get('JAVA_HOME')
        javac = str(Path(java_home) / 'bin/javac') if java_home else shutil.which('javac')
        java = str(Path(java_home) / 'bin/java') if java_home else shutil.which('java')
        self.assertTrue(javac and java, 'A JDK is required')
        source = ROOT / 'launcher-ui/app/src/tv/noiro/launcher/HomeCatalogFallback.java'
        with tempfile.TemporaryDirectory() as tmp:
            harness = Path(tmp) / 'HomeFallbackHarness.java'
            harness.write_text(r'''package tv.noiro.launcher;
public class HomeFallbackHarness {
 static void check(boolean result,String reason){if(!result)throw new AssertionError(reason);}
 public static void main(String[] args){
  HomeCatalogFallback home=new HomeCatalogFallback();
  // A free or anonymous Main profile does not depend on an unavailable cloud account.
  home.begin(1,"main",true,false,false);
  check(home.request(1,"main"),"free public Main starts immediately");
  check(home.accepts(1,"main"),"public results can fill free Main");
  check(!home.request(1,"main"),"one request per Home refresh");

  // Membership may be valid while TMDB is absent. Do not mix default rows into
  // an explicit custom Home while its independent Studio request is still running.
  home.begin(2,"main",true,true,false);
  check(!home.request(2,"main"),"paid keyless waits for custom Home decision");
  home.studioResolved(2,"main",false);
  check(home.request(2,"main"),"paid keyless without Studio still gets public rows");
  check(home.accepts(2,"main"),"Plex and public requests share the same generation");
  check(!home.request(2,"main"),"repeated callbacks do not duplicate public work");

  // A refresh invalidates a delayed public reply and delayed Studio decisions.
  home.begin(3,"main",true,true,false);
  check(!home.accepts(2,"main"),"old public results rejected");
  home.studioResolved(2,"main",false);
  check(!home.request(3,"main"),"old Studio response cannot unlock new refresh");
  home.studioResolved(3,"main",true);
  check(!home.request(3,"main"),"published Home suppresses public defaults");
  check(!home.accepts(3,"main"),"late public result cannot enter custom Home");

  // A known custom Home stays authoritative through a transient Studio outage;
  // a later successful empty/unpublished configuration may deliberately restore defaults.
  home.begin(4,"main",true,true,true);
  home.studioResolved(4,"main",true);
  check(!home.request(4,"main"),"outage preserves known custom Home");
  home.begin(5,"main",true,true,true);
  home.studioResolved(5,"main",false);
  check(home.request(5,"main"),"explicitly unpublished Main restores defaults");
  home.studioResolved(5,"main",true);
  check(!home.accepts(5,"main"),"custom Home winning a race discards pending defaults");

  // Neither a paid empty child profile nor a public request from a previous Main
  // may fill another profile, even when its Studio service fails.
  home.begin(6,"child",true,true,false);
  home.studioResolved(6,"main",false);
  check(!home.request(6,"child"),"wrong-profile response ignored");
  home.studioResolved(6,"child",false);
  check(!home.request(6,"child"),"no Main fallback for secondary profiles");
  check(!home.accepts(5,"main"),"prior Main data cannot cross profile boundary");
  home.begin(7,"child",true,false,false);
  check(!home.request(7,"child"),"free/expired child also cannot inherit Main");

  // Configured TMDB and legacy-launcher catalog policies remain untouched.
  home.begin(8,"main",false,true,false);
  home.studioResolved(8,"main",false);
  check(!home.request(8,"main"),"configured metadata keeps existing recommendations");
  home.begin(9,"main",false,false,false);
  check(!home.request(9,"main"),"legacy paths never gain public fallback");
 }
}''')
            subprocess.run([javac, '-d', tmp, str(source), str(harness)], check=True)
            subprocess.run([java, '-cp', tmp, 'tv.noiro.launcher.HomeFallbackHarness'], check=True)
