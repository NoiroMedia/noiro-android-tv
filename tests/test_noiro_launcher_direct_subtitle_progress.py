"""Verify the launcher's progressive subtitle polling with a fixture transport."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class DirectSubtitleProgressTests(unittest.TestCase):
    def test_valid_partial_cues_arrive_before_completed_track(self):
        java = Path(os.environ['JAVA_HOME']) / 'bin'
        src = ROOT / 'launcher-ui/app/src/tv/noiro/launcher'
        with tempfile.TemporaryDirectory() as tmp:
            files = {
                'org/json/JSONObject.java': '''package org.json;
import java.util.HashMap;
public class JSONObject {
    private final HashMap<String, Object> values = new HashMap<>();
    public JSONObject put(String key, Object value) { values.put(key, value); return this; }
    public String getString(String key) { return (String) values.get(key); }
    public String optString(String key) { return (String) values.getOrDefault(key, ""); }
    public JSONArray getJSONArray(String key) { return (JSONArray) values.get(key); }
    public boolean has(String key) { return values.containsKey(key); }
}''',
                'org/json/JSONArray.java': '''package org.json;
public class JSONArray {
    public int length() { return 0; }
    public String getString(int index) { throw new IndexOutOfBoundsException(); }
}''',
                'ProgressFixture.java': r'''package tv.noiro.launcher;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

class AccountState {}
class SubtitleSelection {
    static String normalize(String language) { return language; }
}
class AccountHttp {
    static class Failure extends Exception { Failure(String code) { super(code); } }
}
class NoiroAccount {
    static final String JOB = "a".repeat(64) + "." + "b".repeat(64);
    static int reads;
    static boolean progressive = true;
    static JSONObject subtitleRequest(String url, JSONObject payload, AccountState account) {
        if (payload != null) return new JSONObject().put("job", JOB).put("state", "queued");
        if (!url.endsWith("?job=" + JOB + (progressive ? "&partial=1" : ""))) {
            throw new AssertionError("Incorrect status URL");
        }
        if (progressive && ++reads == 1) return new JSONObject().put("state", "running")
                .put("partialSrt", ProgressFixture.CUE_TWO.replace("first translated", "mistranslated")
                        .replace("00:00:04,000", "00:00:04,005"));
        if (progressive && reads == 2) return new JSONObject().put("state", "running")
                .put("partialSrt", ProgressFixture.CUE_TWO);
        return new JSONObject().put("state", "complete").put("srt", ProgressFixture.TRACK);
    }
}
public class ProgressFixture {
    static final String CUE_ONE = "1\n00:00:01,000 --> 00:00:02,000\nsecond translated\n\n";
    static final String CUE_TWO = "2\n00:00:04,000 --> 00:00:05,000\nfirst translated\n\n";
    static final String TRACK = CUE_ONE + CUE_TWO;

    private static void require(boolean condition) { if (!condition) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        var account = new AccountState();
        var progress = new ArrayList<String>();
        var snapshots = new ArrayList<List<TimedSubtitles.Cue>>();
        var finalCues = DirectSubtitles.translate(account, "tt1234567", TRACK, "en", "hr",
                progress::add, cues -> {
                    require(NoiroAccount.reads == 2);
                    snapshots.add(cues);
                });
        require(snapshots.size() == 1 && snapshots.get(0).size() == 1);
        require(TimedSubtitles.at(snapshots.get(0), 4500).equals("first translated"));
        require(progress.contains("Noiro AI · 1/2"));
        require(finalCues.size() == 2);
        NoiroAccount.progressive = false;
        require(DirectSubtitles.translate(account, "tt1234567", TRACK, "en", "hr", progress::add).size() == 2);
        require(!DirectSubtitles.matchesSourceTimings(snapshots.get(0), finalCues));
        require(!DirectSubtitles.matchesSourceTimings(finalCues, List.of(finalCues.get(0), finalCues.get(0))));
    }
}''',
            }
            paths = []
            for name, source in files.items():
                path = Path(tmp) / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(source, encoding='utf-8')
                paths.append(str(path))
            subprocess.run([str(java / 'javac'), '-encoding', 'UTF-8', '-d', tmp, *paths,
                            str(src / 'DirectSubtitles.java'), str(src / 'TimedSubtitles.java')], check=True)
            subprocess.run([str(java / 'java'), '-cp', tmp, 'tv.noiro.launcher.ProgressFixture'], check=True)
