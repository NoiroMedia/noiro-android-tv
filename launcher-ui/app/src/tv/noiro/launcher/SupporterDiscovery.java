package tv.noiro.launcher;

/** In-memory discovery state and retry timing; this class never grants an entitlement. */
final class SupporterDiscovery {
    enum Status { SIGNED_OUT, UNCHECKED, CHECKING, SUPPORTER, FREE, LINK_REQUIRED, UNAVAILABLE }
    private Status outcome = Status.UNCHECKED;
    private boolean checking;
    private int failures;
    private long retryAt, metadataAt;

    boolean begin(long elapsed, boolean force) {
        if (checking || elapsed < 0 || (!force && elapsed < retryAt)) return false;
        checking = true;
        return true;
    }
    Status status(boolean signedIn, boolean leased) {
        if (!signedIn) return Status.SIGNED_OUT;
        if (leased) return Status.SUPPORTER;
        if (checking) return Status.CHECKING;
        return outcome == Status.SUPPORTER ? Status.UNCHECKED : outcome;
    }
    void success(long elapsed, long leaseDeadline) {
        outcome = Status.SUPPORTER;
        failures = 0;
        // Renew early, including unusually short server-issued grants, without changing their deadline.
        retryAt = Math.max(elapsed, Math.min(after(elapsed, 60_000), leaseDeadline - 15_000));
    }
    void failure(Status result, long elapsed) {
        outcome = result;
        if (result == Status.FREE || result == Status.LINK_REQUIRED) {
            failures = 0;
            retryAt = after(elapsed, 15 * 60_000);
        } else {
            failures = Math.min(5, failures + 1);
            retryAt = after(elapsed, Math.min(5 * 60_000, 30_000L << (failures - 1)));
        }
    }
    boolean metadataDue(long elapsed, boolean force) {
        if (!force && elapsed < metadataAt) return false;
        metadataAt = after(elapsed, 15 * 60_000);
        return true;
    }
    void finish() { checking = false; }
    private static long after(long elapsed, long delay) {
        return elapsed > Long.MAX_VALUE - delay ? Long.MAX_VALUE : elapsed + delay;
    }
}
