package tv.noiro.launcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Language priorities are independent of stream URLs, subtitle labels and player track indexes. */
final class SubtitleSelection {
    static final String[] CODES = {"ar","bs","bg","zh","hr","cs","da","nl","en","fa","fi","fr","de","el","he","hi","hu","id","it","ja","ko","mk","ms","no","pl","pt","ro","ru","sr","sk","sl","es","sv","ta","te","th","tr","uk","vi"};
    static final String[] NAMES = {"Arabic","Bosnian","Bulgarian","Chinese","Croatian","Czech","Danish","Dutch","English","Farsi","Finnish","French","German","Greek","Hebrew","Hindi","Hungarian","Indonesian","Italian","Japanese","Korean","Macedonian","Malay","Norwegian","Polish","Portuguese","Romanian","Russian","Serbian","Slovak","Slovenian","Spanish","Swedish","Tamil","Telugu","Thai","Turkish","Ukrainian","Vietnamese"};

    static String normalize(String value) {
        String code = value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('_', '-').split("-", 2)[0];
        switch (code) {
            case "fre": code = "fr"; break;
            case "ger": code = "de"; break;
            case "dut": code = "nl"; break;
            case "cze": code = "cs"; break;
            case "rum": code = "ro"; break;
            case "chi": code = "zh"; break;
            case "per": code = "fa"; break;
            case "may": code = "ms"; break;
            case "scc": code = "sr"; break;
            case "scr": code = "hr"; break;
            case "nob": case "nno": code = "no"; break;
            case "iw": code = "he"; break;
        }
        for (String supported : CODES) {
            if (supported.equals(code) || new Locale(supported).getISO3Language().equals(code)) return supported;
        }
        return "";
    }
    static List<String> languages(String value) {
        List<String> result = new ArrayList<>();
        for (String part : value.split(",")) {
            String code = normalize(part);
            if (!code.isEmpty() && !result.contains(code)) result.add(code);
            if (result.size() == 5) break;
        }
        return result;
    }
    static String name(String code) {
        for (int i = 0; i < CODES.length; i++) if (CODES[i].equals(code)) return NAMES[i];
        return code;
    }
    static int rank(List<String> preferred, String code) {
        int at = preferred.indexOf(normalize(code));
        return at < 0 ? Integer.MAX_VALUE : at;
    }
    static int addon(List<String> preferred, List<String> languages, List<Boolean> noiro,
                     List<Boolean> ai, boolean allowGemini) {
        int best = -1, bestRank = Integer.MAX_VALUE, bestType = Integer.MAX_VALUE;
        for (int i = 0; i < languages.size(); i++) {
            if (ai.get(i) && !allowGemini) continue;
            int rank = rank(preferred, languages.get(i));
            int type = ai.get(i) ? 2 : noiro.get(i) ? 0 : 1;
            if (rank < bestRank || (rank != Integer.MAX_VALUE && rank == bestRank && type < bestType)) {
                best = i; bestRank = rank; bestType = type;
            }
        }
        return best;
    }
}
