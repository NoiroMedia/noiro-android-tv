package tv.noiro.launcher;

/** Immutable identity snapshot. Secrets are never included in UI labels or logs. */
final class AccountState {
    static final AccountState EMPTY = new AccountState("", "", "", "", 0);
    final String authKey, uid, label, tmdbKey;
    final long syncedAt;
    AccountState(String authKey, String uid, String label, String tmdbKey, long syncedAt) {
        this.authKey = authKey; this.uid = uid; this.label = label;
        this.tmdbKey = tmdbKey; this.syncedAt = syncedAt;
    }
    boolean signedIn() { return !authKey.isEmpty() && !uid.isEmpty(); }
    boolean ready() { return signedIn() && !tmdbKey.isEmpty(); }
    boolean owns(String key, String id) { return authKey.equals(key) && uid.equals(id) && signedIn(); }
}
