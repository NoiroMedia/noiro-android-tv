package tv.noiro.launcher;

import java.net.URI;

/** Pure trust and timeout rules shared by onboarding and regression tests. */
final class LoginPolicy {
    static final long MAX_POLL_MS = 10 * 60_000L;
    static boolean expired(long elapsed) { return elapsed >= MAX_POLL_MS; }
    static boolean validUid(String uid) { return uid != null && !uid.isEmpty() && uid.length() <= 128 && !uid.matches("(?s).*\\s.*"); }
    static boolean trustedLink(String value) {
        try {
            URI uri = new URI(value);
            return value.length() <= 2048 && "https".equals(uri.getScheme()) && "link.stremio.com".equalsIgnoreCase(uri.getHost())
                    && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443);
        } catch (Exception invalid) { return false; }
    }
}
