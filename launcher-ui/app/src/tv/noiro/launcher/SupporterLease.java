package tv.noiro.launcher;

/** Short in-memory authorization, measured with a monotonic clock, never restored from disk. */
final class SupporterLease {
    static final long MAX_SECONDS = 300;
    final long deadline;
    private SupporterLease(long deadline) { this.deadline = deadline; }
    static SupporterLease empty() { return new SupporterLease(0); }
    static SupporterLease grant(boolean active, long seconds, long requestStarted) {
        if (!active || seconds <= 0 || seconds > MAX_SECONDS || requestStarted < 0
                || requestStarted > Long.MAX_VALUE - seconds * 1000) return empty();
        return new SupporterLease(requestStarted + seconds * 1000);
    }
    boolean valid(long elapsed) { return elapsed >= 0 && elapsed < deadline; }
}
