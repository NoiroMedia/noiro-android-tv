"""Physical image coverage and bounded decode memory on low-memory and 4K TVs."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
ROOT = Path(__file__).resolve().parents[1]
class ArtworkTests(unittest.TestCase):
    def test_render_size_and_pixel_budgets(self):
        java = Path(os.environ['JAVA_HOME']) / 'bin'
        with tempfile.TemporaryDirectory() as tmp:
            harness = Path(tmp) / 'ArtworkHarness.java'
            harness.write_text('''package tv.noiro.launcher;
public class ArtworkHarness {
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) {
        String base="https://image.tmdb.org/t/p/";
        check(ArtworkPolicy.url(base+"w780/poster.jpg",470).equals(base+"w500/poster.jpg"));
        check(ArtworkPolicy.url(base+"original/hero.jpg",3840).equals(base+"original/hero.jpg"));
        check(ArtworkPolicy.url(base+"original/hero.jpg",1200).equals(base+"w1280/hero.jpg"));
        check(ArtworkPolicy.url(base+"w300/blur.jpg",3840).equals(base+"w300/blur.jpg"));
        int[] landscape=ArtworkPolicy.requestDimensions(378,213,true);
        check(landscape[0]==1280 && landscape[1]==721);
        int[] landscape4k=ArtworkPolicy.requestDimensions(756,426,true);
        check(landscape4k[0]==1512 && landscape4k[1]==852);
        int[] large=ArtworkPolicy.requestDimensions(1600,900,true);
        check(large[0]==1920 && large[1]==1080);
        int[] logo=ArtworkPolicy.requestDimensions(213,213,true);
        check(logo[0]==234 && logo[1]==234);
        check(ArtworkPolicy.url(base+"w300/cover.jpg",1280,true).equals(base+"original/cover.jpg"));
        check(ArtworkPolicy.url(base+"w780/cover.jpg",1512,true).equals(base+"original/cover.jpg"));
        check(ArtworkPolicy.url(base+"w300/blur.jpg",3840,false).equals(base+"w300/blur.jpg"));
        int[] four=ArtworkPolicy.dimensions(8192,4608,3840,2160,512L*1024*1024);
        check(four[0]==3840 && four[1]==2160);
        int[] low=ArtworkPolicy.dimensions(8192,4608,3840,2160,64L*1024*1024);
        check((long)low[0]*low[1]<=2L*1024*1024);
        int[] poster=ArtworkPolicy.dimensions(2000,3000,500,750,128L*1024*1024);
        check(poster[0]==500 && poster[1]==750);
        int[] tiny=ArtworkPolicy.dimensions(200,300,500,750,128L*1024*1024);
        check(tiny[0]==200 && tiny[1]==300);
        System.out.println("PASS: native 4K, exact poster sizes, no upscaling, low-memory limits");
    }
}''')
            subprocess.run([str(java/'javac'),'-d',tmp,str(ROOT/'launcher-ui/app/src/tv/noiro/launcher/ArtworkPolicy.java'),str(harness)],check=True)
            subprocess.run([str(java/'java'),'-cp',tmp,'tv.noiro.launcher.ArtworkHarness'],check=True)
