"""A held remote OK must open a menu without activating its first item."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class MenuPressTests(unittest.TestCase):
    def test_opening_hold_repeats_release_and_accessibility(self):
        java = Path(os.environ['JAVA_HOME']) / 'bin'
        with tempfile.TemporaryDirectory() as tmp:
            harness = Path(tmp) / 'MenuPressHarness.java'
            harness.write_text('''package tv.noiro.launcher;
public class MenuPressHarness {
 static void check(boolean value){if(!value)throw new AssertionError();}
 public static void main(String[] args){
  MenuPressPolicy hold=new MenuPressPolicy(true);
  check(hold.consume(true,1));check(hold.consume(true,15));check(hold.consume(false,0));
  check(!hold.consume(true,0));check(!hold.consume(false,0));
  MenuPressPolicy noRepeatRemote=new MenuPressPolicy(true);
  check(noRepeatRemote.consume(false,0));check(!noRepeatRemote.consume(true,0));
  MenuPressPolicy talkBack=new MenuPressPolicy(true);
  check(!talkBack.consume(true,0));check(!talkBack.consume(false,0));
  MenuPressPolicy normal=new MenuPressPolicy(false);
  check(!normal.consume(true,0));check(!normal.consume(false,0));
  System.out.println("PASS: opening holds cannot select a menu action; repeat-free remotes and accessibility remain usable");
 }
}''')
            source = ROOT / 'launcher-ui/app/src/tv/noiro/launcher/MenuPressPolicy.java'
            subprocess.run([str(java / 'javac'), '-d', tmp, str(source), str(harness)], check=True)
            subprocess.run([str(java / 'java'), '-cp', tmp, 'tv.noiro.launcher.MenuPressHarness'], check=True)
