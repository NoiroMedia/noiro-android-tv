"""Drive the real settings model used by the TV view, without connecting to a device."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class AppSettingsTests(unittest.TestCase):
    def test_app_rows_navigation_and_value_cycles(self):
        java = Path(os.environ["JAVA_HOME"]) / "bin"
        source = ROOT / "launcher-ui/app/src/tv/noiro/launcher/AppSettingsModel.java"
        with tempfile.TemporaryDirectory() as temporary:
            harness = Path(temporary) / "AppSettingsHarness.java"
            harness.write_text(r'''package tv.noiro.launcher;
import java.util.*;
public class AppSettingsHarness {
 static void check(boolean value) { if (!value) throw new AssertionError(); }
 public static void main(String[] args) {
  AppSettingsModel model = new AppSettingsModel();
  List<AppSettingsModel.Row> main = Arrays.asList(AppSettingsModel.Row.APPEARANCE,
   AppSettingsModel.Row.SERVICES, AppSettingsModel.Row.ACCOUNT, AppSettingsModel.Row.WHERE_TO_WATCH,
   AppSettingsModel.Row.TV_SETTINGS, AppSettingsModel.Row.LICENSES, AppSettingsModel.Row.UPDATES);
  check(model.rows().equals(main));
  check(!model.back());
  model.move(-1); check(model.focus() == 0);
  Set<AppSettingsModel.Row> reached = new HashSet<>();
  for (int i = 0; i < 20; i++) { reached.add(model.selected()); model.move(1); }
  check(reached.equals(new HashSet<>(main)) && model.selected() == AppSettingsModel.Row.UPDATES);
  check(!model.openSelected());
  for (int i = 0; i < 20; i++) model.move(-1);
  check(model.selected() == AppSettingsModel.Row.APPEARANCE && model.openSelected());
  check(model.page() == AppSettingsModel.Page.APPEARANCE);
  check(model.rows().equals(Arrays.asList(AppSettingsModel.Row.LANGUAGE,
   AppSettingsModel.Row.PLAYER_APPEARANCE)));
  model.focus(99); check(model.selected() == AppSettingsModel.Row.PLAYER_APPEARANCE);
  model.focus(-99); check(model.selected() == AppSettingsModel.Row.LANGUAGE);
  check(model.back() && model.selected() == AppSettingsModel.Row.APPEARANCE && !model.back());
  for (AppSettingsModel.Row row : main) {
   model.focus(main.indexOf(row));
   if (row == AppSettingsModel.Row.APPEARANCE) { check(model.openSelected()); check(model.back()); }
   else check(!model.openSelected());
  }
  String[] languages = {"auto", "tr", "pt", "en", "es", "de", "ru"};
  String value = languages[0]; Set<String> visited = new HashSet<>();
  for (int i = 0; i < languages.length; i++) { visited.add(value); value=AppSettingsModel.cycle(languages,value,1); }
  check(value.equals("auto") && visited.equals(new HashSet<>(Arrays.asList(languages))));
  check(AppSettingsModel.cycle(languages,"auto",-1).equals("ru"));
  check(AppSettingsModel.cycle(languages,"invalid",1).equals("auto"));
  try { model.rows().clear(); throw new AssertionError(); } catch (UnsupportedOperationException expected) {}
  System.out.println("PASS: app-only rows, bounded remote focus, appearance/back routing and saved language cycles");
 }
}''')
            subprocess.run([str(java / "javac"), "-d", temporary, str(source), str(harness)], check=True)
            subprocess.run([str(java / "java"), "-cp", temporary, "tv.noiro.launcher.AppSettingsHarness"], check=True)
