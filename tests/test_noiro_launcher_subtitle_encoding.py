"""Real subtitle byte decoding, including Balkan legacy encodings and damaged UTF-8."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class SubtitleEncodingTests(unittest.TestCase):
    def test_unicode_legacy_bom_gzip_entities_and_limits(self):
        java = Path(os.environ['JAVA_HOME']) / 'bin'
        src = ROOT / 'launcher-ui/app/src/tv/noiro/launcher'
        with tempfile.TemporaryDirectory() as tmp:
            harness = Path(tmp) / 'EncodingHarness.java'
            harness.write_text(r'''package tv.noiro.launcher;
import java.nio.charset.*;
import java.io.*;
import java.util.zip.*;
public class EncodingHarness {
 static void eq(String a,String b) { if(!a.equals(b))throw new AssertionError(a+" != "+b); }
 public static void main(String[] args)throws Exception {
  String accents="ČĆŽŠĐ čćžšđ";
  String srt="1\n00:00:01,000 --> 00:00:03,500\n"+accents+"\n";
  eq(SubtitleText.decode(srt.getBytes("UTF-8"),"text/vtt; charset=utf-8","hr"),srt);
  eq(SubtitleText.decode(srt.getBytes("windows-1250"),"application/octet-stream","hrv"),srt);
  eq(SubtitleText.decode(srt.getBytes("ISO-8859-2"),"text/plain; charset=iso-8859-2","hr"),srt);
  eq(SubtitleText.decode(srt.getBytes("UTF-16"),"","hr"),srt);
  eq(SubtitleText.decode(("\uFEFF"+srt).getBytes("UTF-8"),"","hr"),srt);
  String mixed="日本語 · Русский · العربية · Äpfel";
  eq(SubtitleText.decode(mixed.getBytes("UTF-8"),"","en"),mixed);
  eq(SubtitleText.decode("Привет".getBytes("windows-1251"),"","rus"),"Привет");
  eq(SubtitleText.decode("c\u030C".getBytes("UTF-8"),"","hr"),"č");
  String damaged=new String("ćšžđ".getBytes("UTF-8"),"windows-1252");
  eq(SubtitleText.decode(damaged.getBytes("UTF-8"),"","hr"),"ćšžđ");
  eq(SubtitleText.decode("Ä\u008D".getBytes("UTF-8"),"","hr"),"č");
  eq(SubtitleText.entities("&ccaron;&cacute;&zcaron;&scaron;&dstrok; &#269;&#x107;"),"čćžšđ čć");
  ByteArrayOutputStream out=new ByteArrayOutputStream();
  try(GZIPOutputStream zip=new GZIPOutputStream(out)){zip.write(srt.getBytes("UTF-8"));}
  String decoded=SubtitleText.decode(out.toByteArray(),"","hr");eq(decoded,srt);
  eq(TimedSubtitles.at(TimedSubtitles.parse(decoded),2000),accents);
  boolean bounded=false;try{SubtitleText.decode(new byte[2*1024*1024+1],"","hr");}catch(IllegalArgumentException expected){bounded=true;}
  if(!bounded)throw new AssertionError("Unbounded input");
 }
}''')
            subprocess.run([str(java/'javac'), '-encoding', 'UTF-8', '-d', tmp,
                            str(src/'SubtitleSelection.java'), str(src/'SubtitleText.java'),
                            str(src/'TimedSubtitles.java'), str(harness)], check=True)
            subprocess.run([str(java/'java'), '-cp', tmp, 'tv.noiro.launcher.EncodingHarness'], check=True)
