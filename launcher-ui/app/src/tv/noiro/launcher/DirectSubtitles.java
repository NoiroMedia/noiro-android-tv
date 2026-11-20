package tv.noiro.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class DirectSubtitles {
    private static final String URL = "https://noiro.tv/api/stremio-for-kodi/v1/launcher-subtitles";
    static List<String> targets(AccountState account) throws Exception {
        JSONArray response = NoiroAccount.subtitleRequest(URL, null, account).getJSONArray("targets");
        List<String> options = new ArrayList<>();
        for (int i = 0; i < response.length(); i++) {
            String target = SubtitleSelection.normalize(response.getString(i));
            if (!target.isEmpty() && !options.contains(target)) options.add(target);
        }
        return options;
    }

    static String sourceLanguage(String language) {
        String known = SubtitleSelection.normalize(language);
        if (!known.isEmpty()) return known;
        if (language == null) return "und";
        String code = java.util.Locale.forLanguageTag(language.replace('_', '-')).getLanguage();
        return code.matches("[a-z]{2,3}") ? code : "und";
    }

    static String srt(List<TimedSubtitles.Cue> cues) throws AccountHttp.Failure {
        if (cues.isEmpty()) throw new AccountHttp.Failure("invalid_source_subtitle");
        List<TimedSubtitles.Cue> ordered = new ArrayList<>(cues);
        ordered.sort(java.util.Comparator.comparingLong(cue -> cue.start));
        StringBuilder text = new StringBuilder();
        int index = 0;
        for (TimedSubtitles.Cue cue : ordered) {
            String line = cue.text.replace("\r", "").replaceAll("\n\\s*\n", "\n").trim();
            if (line.isEmpty()) continue;
            text.append(++index).append('\n').append(stamp(cue.start)).append(" --> ").append(stamp(cue.end))
                    .append('\n').append(line).append("\n\n");
        }
        String source = text.toString();
        if (source.isEmpty()) throw new AccountHttp.Failure("invalid_source_subtitle");
        if (source.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 750_000)
            throw new AccountHttp.Failure("subtitle_source_too_large");
        return source;
    }

    private static String stamp(long time) {
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d,%03d",
                time / 3_600_000, time / 60_000 % 60, time / 1000 % 60, time % 1000);
    }

    static List<TimedSubtitles.Cue> cached(AccountState account, String video, String target, String timing,
                                          int cueCount, String kind) throws Exception {
        JSONObject response = NoiroAccount.subtitleRequest(URL, new JSONObject().put("mode", "cache")
                .put("video", video).put("target", target).put("timing", timing)
                .put("cueCount", cueCount).put("timingKind", kind), account);
        String job = response.optString("job");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!Thread.currentThread().isInterrupted()) {
            String state = response.optString("state");
            if (state.equals("complete")) {
                List<TimedSubtitles.Cue> cues = TimedSubtitles.parse(response.getString("srt"));
                return cues.size() == cueCount ? cues : null;
            }
            if (!state.equals("queued") && !state.equals("running")) return null;
            if (!job.matches("[a-f0-9]{64}\\.[a-f0-9]{64}") || System.nanoTime() >= deadline) return null;
            Thread.sleep(1_100);
            response = NoiroAccount.subtitleRequest(URL + "?job=" + job, null, account);
        }
        return null;
    }

    static List<TimedSubtitles.Cue> translate(AccountState account, String video, String sourceSrt, String sourceLanguage, String target,
                                               Consumer<String> progress) throws Exception {
        return translate(account, video, sourceSrt, sourceLanguage, target, progress, null);
    }

    static List<TimedSubtitles.Cue> translate(AccountState account, String video, String sourceSrt, String sourceLanguage, String target,
                                               Consumer<String> progress, Consumer<List<TimedSubtitles.Cue>> partial) throws Exception {
        checkInterrupted();
        List<TimedSubtitles.Cue> source = partial == null ? null : TimedSubtitles.parse(sourceSrt);
        JSONObject start = NoiroAccount.subtitleRequest(URL, new JSONObject().put("video", video)
                .put("srt", sourceSrt).put("sourceLanguage", sourceLanguage).put("target", target), account);
        String job = start.getString("job");
        if (!job.matches("[a-f0-9]{64}\\.[a-f0-9]{64}")) throw new AccountHttp.Failure("invalid_subtitle_job");
        String statusUrl = URL + "?job=" + job + (partial == null ? "" : "&partial=1");
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(12);
        long pollDelayMs = 1_500;
        checkInterrupted();
        JSONObject status = start.has("srt") && "complete".equals(start.optString("state"))
                ? start : NoiroAccount.subtitleRequest(statusUrl, null, account);
        String lastPartial = "";
        List<TimedSubtitles.Cue> displayed = Collections.emptyList();
        for (;;) {
            checkInterrupted();
            String state = status.getString("state");
            if (state.equals("complete")) {
                List<TimedSubtitles.Cue> cues = TimedSubtitles.parse(status.getString("srt"));
                if (cues.isEmpty()) throw new AccountHttp.Failure("invalid_source_subtitle");
                return cues;
            }
            if (!state.equals("queued") && !state.equals("running"))
                throw new AccountHttp.Failure("umbrel_translation_failed");
            if (partial != null && state.equals("running")) {
                String raw = status.optString("partialSrt");
                if (!raw.isEmpty() && !raw.equals(lastPartial)) {
                    lastPartial = raw;
                    List<TimedSubtitles.Cue> cues = parsePartial(raw);
                    if (!cues.isEmpty() && matchesSourceTimings(source, cues) && matchesSourceTimings(cues, displayed)) {
                        checkInterrupted();
                        partial.accept(cues);
                        displayed = cues;
                    }
                }
            }
            if (!displayed.isEmpty())
                progress.accept("Noiro AI · " + displayed.size() + "/" + source.size());
            else
                progress.accept(state.equals("queued") ? "Noiro AI · queued" : "Noiro AI · translating");
            if (System.nanoTime() >= deadline) throw new AccountHttp.Failure("translation_pending");
            Thread.sleep(pollDelayMs);
            pollDelayMs = partial == null ? 4_000 : 3_500;
            status = NoiroAccount.subtitleRequest(statusUrl, null, account);
        }
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
    }

    private static List<TimedSubtitles.Cue> parsePartial(String srt) {
        try { return TimedSubtitles.parse(srt); }
        catch (IllegalArgumentException malformed) { return Collections.emptyList(); }
    }

    /** Match an ordered subset, preserving repeated source timings. */
    static boolean matchesSourceTimings(List<TimedSubtitles.Cue> source, List<TimedSubtitles.Cue> subset) {
        int index = 0;
        for (TimedSubtitles.Cue cue : subset) {
            boolean found = false;
            while (index < source.size() && source.get(index).start <= cue.start) {
                TimedSubtitles.Cue original = source.get(index++);
                if (original.start == cue.start && original.end == cue.end) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }
}
