package tv.noiro.launcher;

/** Membership wording never turns an unchecked or unavailable result into a free plan. */
final class SupporterPresentation {
    private SupporterPresentation() {}

    static String label(SupporterDiscovery.Status status) {
        switch (status) {
            case SIGNED_OUT: return "Browsing without an account";
            case CHECKING: return "Stremio · checking membership…";
            case SUPPORTER: return "Stremio + Noiro Supporter";
            case FREE: return "Stremio · free";
            case LINK_REQUIRED: return "Stremio · link Noiro account";
            case UNAVAILABLE: return "Stremio · membership unavailable";
            default: return "Stremio · membership not checked";
        }
    }

    static String detail(SupporterDiscovery.Status status) {
        switch (status) {
            case SIGNED_OUT: return "Connect Stremio to check an existing Noiro Supporter membership.";
            case CHECKING: return "Checking your Noiro Supporter membership… Basic browsing and playback remain available.";
            case SUPPORTER: return "Your Noiro Supporter membership is active. TMDB metadata setup is optional and does not determine your membership.";
            case FREE: return "No active Supporter membership was confirmed for this linked Stremio account. Already paid? Sign in to your original Noiro account and check its Stremio connection before buying again.";
            case LINK_REQUIRED: return "Link the Stremio account used on this TV to your paid Noiro account in Noiro.tv → Account → Connections, then check again.";
            case UNAVAILABLE: return "Couldn't verify your membership. Check your connection and try again; this does not mean your membership is Free.";
            default: return "Your Noiro membership has not been checked yet. Select Check cloud connection to verify it.";
        }
    }
}
