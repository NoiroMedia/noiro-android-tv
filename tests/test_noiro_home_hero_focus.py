"""Execute the real Home row/hero methods with deterministic Android focus-event stand-ins."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


def source_method(source, signature):
    start = source.index(signature)
    opening = source.index('{', start)
    depth = 1
    for end in range(opening + 1, len(source)):
        if source[end] == '{':
            depth += 1
        elif source[end] == '}':
            depth -= 1
            if depth == 0:
                return source[start:end + 1]
    raise AssertionError('Unclosed Java method: ' + signature)


class HomeHeroFocusTests(unittest.TestCase):
    def test_first_restored_card_and_refresh_keep_hero_current(self):
        source = (ROOT / 'launcher-ui/app/src/tv/noiro/launcher/KodiHomeView.java').read_text()
        methods = '\n'.join(source_method(source, signature) for signature in [
            'private void selectRow(CatalogRow row, boolean updateHero)',
            'private void preview(CatalogRow row, StudioHome.Card card)',
            'private int beginCatalogRefresh()',
        ])
        java_home = os.environ.get('JAVA_HOME')
        javac = str(Path(java_home) / 'bin/javac') if java_home else shutil.which('javac')
        java = str(Path(java_home) / 'bin/java') if java_home else shutil.which('java')
        self.assertTrue(javac and java, 'A JDK is required')
        with tempfile.TemporaryDirectory() as tmp:
            harness = Path(tmp) / 'HomeFocusHarness.java'
            harness.write_text(r'''package tv.noiro.launcher;
import java.util.*;
public class HomeFocusHarness {
 static final int VISIBLE=0,GONE=8;
 static final class StudioHome {static final class Card {
  final String id;Card(String id){this.id=id;}String key(){return id;}
 }}
 static final class Recs {static final class Pick {Object continuing;}}
 static final class UpNext {int enrichments;void enrich(Recs.Pick pick){enrichments++;}}
 final class CatalogRow {
  final String name;final List<StudioHome.Card> titles=new ArrayList<>();
  final Map<String,Recs.Pick> picks=new HashMap<>();
  int selectedIndex,focusEvents;boolean focused,implicitFocus,focusSucceeds=true;
  CatalogRow(String name,String... ids){this.name=name;for(String id:ids)titles.add(new StudioHome.Card(id));}
  void setVisibility(int visibility){if(visibility==VISIBLE&&implicitFocus&&!focused&&!titles.isEmpty())gainFocus();if(visibility==GONE)focused=false;}
  void loadVisibleCards(){}
  boolean focusCard(){if(!focusSucceeds||titles.isEmpty())return false;if(!focused)gainFocus();return true;}
  void gainFocus(){focused=true;focusEvents++;if(!keepHero)preview(this,titles.get(selectedIndex));}
  StudioHome.Card firstCard(){return titles.isEmpty()?null:titles.get(0);}
  int focusedIndex(){return selectedIndex;}
 }
 CatalogRow activeRow;boolean keepHero;int generation,heroUpdates;
 StudioHome.Card heroCard,pinnedHero;Recs.Pick heroPick;String heroCatalog="";
 final Map<String,String> catalogArtwork=new HashMap<>();final UpNext upNext=new UpNext();
 final List<String> requestedMetadata=new ArrayList<>();
 void parkArtwork(CatalogRow old,CatalogRow next){}void showNextCatalog(){}void queueDiscoverLookup(){}
 void showHero(StudioHome.Card card,String image){heroCard=card;heroUpdates++;}
 void loadHero(StudioHome.Card card){requestedMetadata.add(card.id+"@"+generation);}
 static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
 void resetHero(){heroCard=new StudioHome.Card("old");heroUpdates=0;requestedMetadata.clear();pinnedHero=null;keepHero=false;}
''' + methods + r'''
 public static void main(String[] args){
  HomeFocusHarness home=new HomeFocusHarness();
  home.resetHero();CatalogRow restored=home.new CatalogRow("Restored","first","second");
  restored.focused=true;home.selectRow(restored,true);
  check(restored.focusEvents==0,"already-focused request emits no event");
  check(home.heroCard.id.equals("first")&&home.heroUpdates==1,"first card previews without a focus event");
  check(home.requestedMetadata.size()==1,"first card metadata requested exactly once");

  home.resetHero();CatalogRow fresh=home.new CatalogRow("Fresh","first","second");
  home.selectRow(fresh,true);
  check(fresh.focusEvents==1,"normal request emits focus event");
  check(home.heroUpdates==1&&home.requestedMetadata.size()==1,"focus event plus activation do not duplicate preview");

  home.resetHero();CatalogRow implicit=home.new CatalogRow("Implicit","first");implicit.implicitFocus=true;
  home.selectRow(implicit,false);
  check(implicit.focusEvents==1,"visibility restoration really focused first card");
  check(home.heroCard.id.equals("old")&&home.heroUpdates==0,"keep-hero path suppresses implicit visibility focus");
  check(home.requestedMetadata.isEmpty(),"keep-hero path makes no title metadata request");

  home.resetHero();CatalogRow visible=home.new CatalogRow("Visible","first");visible.implicitFocus=true;
  home.selectRow(visible,true);
  check(home.heroUpdates==1&&home.heroCard.id.equals("first"),"implicit first-card focus still updates once");

  home.resetHero();CatalogRow continued=home.new CatalogRow("Continue Watching","first","second","third");
  continued.selectedIndex=2;continued.focused=true;
  Recs.Pick progress=new Recs.Pick();progress.continuing=new Object();continued.picks.put("third",progress);
  home.selectRow(continued,true);
  check(home.heroCard.id.equals("third")&&home.heroPick==progress,"restored card keeps its Continue Watching identity");
  check(home.upNext.enrichments==1,"Continue Watching enrichment preserved");

  home.resetHero();StudioHome.Card pinned=new StudioHome.Card("pinned");home.heroCard=home.pinnedHero=pinned;
  home.selectRow(home.new CatalogRow("Episodes","episode"),true);
  check(home.heroCard==pinned&&home.heroUpdates==0,"fixed detail/player hero stays pinned");

  home.resetHero();home.keepHero=true;home.selectRow(home.new CatalogRow("Empty"),true);
  check(home.keepHero&&home.heroUpdates==0,"empty row is safe and outer suppression survives");

  home.resetHero();CatalogRow failed=home.new CatalogRow("Deferred layout","first");failed.focusSucceeds=false;
  home.selectRow(failed,true);
  check(home.heroCard.id.equals("first"),"a temporarily unfocusable first card still has hero information");

  home.resetHero();home.generation=7;home.heroCard=new StudioHome.Card("first");
  home.loadHero(home.heroCard);int current=home.beginCatalogRefresh();
  check(current==8&&home.generation==8,"catalog work receives its new generation");
  check(home.requestedMetadata.equals(Arrays.asList("first@7","first@8")),"stationary first-card metadata is retried after old generation invalidation");
  home.heroCard=null;home.requestedMetadata.clear();home.beginCatalogRefresh();
  check(home.requestedMetadata.isEmpty(),"initial blank Home does not request a nonexistent hero");
 }
}
''')
            subprocess.run([javac, '-d', tmp, str(harness)], check=True)
            subprocess.run([java, '-cp', tmp, 'tv.noiro.launcher.HomeFocusHarness'], check=True)
