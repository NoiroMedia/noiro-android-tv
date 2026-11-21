package tv.noiro.launcher;

/** A trailer click belongs to its page/login, independently from background catalog refresh generations. */
final class TrailerPlaybackPolicy {
    static final class Ticket {
        private final Object page;
        private final AccountState owner;
        private Ticket(Object page, AccountState owner) { this.page = page; this.owner = owner; }
    }
    private Ticket pending;
    synchronized Ticket begin(Object page, AccountState owner) {
        pending = page != null && owner.signedIn() ? new Ticket(page, owner) : null;
        return pending;
    }
    synchronized boolean current(Ticket ticket, Object page, AccountState owner, boolean foreground) {
        return ticket != null && pending == ticket && foreground && ticket.page == page
                && ticket.owner.owns(owner.authKey, owner.uid);
    }
    synchronized boolean pending(Ticket ticket) { return ticket != null && pending == ticket; }
    synchronized boolean finish(Ticket ticket) {
        if (!pending(ticket)) return false;
        pending = null;
        return true;
    }
    synchronized void cancel() { pending = null; }

    static String failureMessage(String code) {
        if ("supporter_required".equals(code) || "account_unavailable".equals(code))
            return "Check your Noiro cloud access before playing this Plex trailer.";
        if ("authentication_required".equals(code) || "identity_mismatch".equals(code)
                || "account_link_required".equals(code))
            return "Check your linked Stremio and Plex accounts in Noiro cloud, then try again.";
        if ("rate_limited".equals(code)) return "Too many trailer requests. Wait a moment and try again.";
        if ("trailer_unavailable".equals(code) || "server_update_required".equals(code))
            return "Plex did not provide a playable trailer for this item. Try another trailer.";
        return "Couldn't load this Plex trailer. Try again in a moment.";
    }
}
