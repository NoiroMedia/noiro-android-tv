package tv.noiro.launcher;

/** Credential formatting shared by setup and both TMDB clients. Never logs a credential. */
final class TmdbCredential {
    private TmdbCredential() {}

    static String normalize(String value) {
        String key = value == null ? "" : value.trim();
        if (key.regionMatches(true, 0, "Bearer ", 0, 7)) key = key.substring(7).trim();
        return key;
    }

    static boolean validInput(String value) {
        String key = normalize(value);
        return !key.isEmpty() && key.length() <= 8192 && !key.matches("(?s).*\\s.*");
    }

    static boolean bearer(String key) { return key.startsWith("eyJ"); }
}
