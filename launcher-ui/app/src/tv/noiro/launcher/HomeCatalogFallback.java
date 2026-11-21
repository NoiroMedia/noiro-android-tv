package tv.noiro.launcher;

/** Coordinates public Main catalogs with an independently loaded, possibly customized cloud Home. */
final class HomeCatalogFallback {
    private int generation;
    private String profile = "";
    private boolean enabled, awaitingStudio, publishedStudio, requested;

    void begin(int run, String selected, boolean publicMetadata, boolean cloud, boolean wasPublished) {
        generation = run;
        profile = selected;
        enabled = publicMetadata;
        awaitingStudio = cloud;
        publishedStudio = wasPublished;
        requested = false;
    }

    void studioResolved(int run, String selected, boolean published) {
        if (!current(run, selected)) return;
        awaitingStudio = false;
        publishedStudio = published;
    }

    boolean request(int run, String selected) {
        if (!accepts(run, selected) || requested) return false;
        requested = true;
        return true;
    }

    boolean accepts(int run, String selected) {
        return current(run, selected) && enabled && "main".equals(profile)
                && !awaitingStudio && !publishedStudio;
    }

    private boolean current(int run, String selected) {
        return run == generation && profile.equals(selected);
    }
}
