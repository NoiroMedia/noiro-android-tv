package tv.noiro.launcher;

import android.content.Context;
import android.os.SystemClock;
import org.json.JSONObject;

/** UID-scoped encrypted device account. No client-selected Noiro user IDs. */
final class NoiroAccount {
    static final String APP_VERSION = "1.8.41";
    private static SupporterLease lease = SupporterLease.empty();
    private static AccountState leaseOwner = AccountState.EMPTY;
    private static long lastAccessAttempt;
    private static boolean checkingAccess;
    private static String deviceAccess = "";
    private static AccountState accessOwner = AccountState.EMPTY;
    private static long accessUntil;
    private static volatile AccountState state = AccountState.EMPTY;
    private static SessionVault vault;
    private static Context app;
    private static boolean refreshing, coldStart = true;
    private static SupporterDiscovery discovery = new SupporterDiscovery();
    private static SupporterCheck supporterCheck;
    /** Opaque account-generation ticket: a queued worker must never adopt a later login. */
    static final class SupporterCheck {
        private final AccountState owner;
        private final SupporterDiscovery generation;
        private final boolean force;
        private SupporterCheck(AccountState owner, SupporterDiscovery generation, boolean force) {
            this.owner = owner; this.generation = generation; this.force = force;
        }
    }
    static synchronized void init(Context context) {
        if (vault != null) return;
        app = context.getApplicationContext();
        vault = new SessionVault(app);
        state = vault.load();
    }
    static AccountState current() { return state; }
    static synchronized boolean ready() {
        return (standalone() ? state.signedIn() : state.ready())
                && leaseOwner.owns(state.authKey, state.uid) && lease.valid(SystemClock.elapsedRealtime());
    }
    static synchronized boolean configured() {
        return standalone() ? state.signedIn() && leaseOwner.owns(state.authKey, state.uid) : state.ready();
    }
    static synchronized SupporterDiscovery.Status supporterStatus() {
        return discovery.status(state.signedIn(), ready());
    }
    private static boolean standalone() { return app != null && AppIdentity.standalone(app); }
    static synchronized String tmdbKey() { return ready() ? state.tmdbKey : ""; }
    static synchronized void remember(AccountState account) throws Exception {
        if (!account.signedIn()) throw new AccountHttp.Failure("authentication_required");
        String oldUid = state.uid;
        vault.save(account); state = account; lease = SupporterLease.empty(); leaseOwner = AccountState.EMPTY;
        discovery = new SupporterDiscovery(); supporterCheck = null;
        deviceAccess = ""; accessOwner = AccountState.EMPTY; accessUntil = 0; Recs.clearRatingsCache();
        if (!oldUid.equals(account.uid)) { WatchProgress.clear(); Tmdb.credentialsChanged(app); }
        Prefs.version++;
    }
    static synchronized void signOut() throws Exception {
        WatchProgress.clear();
        vault.clear(); state = AccountState.EMPTY; lease = SupporterLease.empty(); leaseOwner = AccountState.EMPTY;
        discovery = new SupporterDiscovery(); supporterCheck = null;
        deviceAccess = ""; accessOwner = AccountState.EMPTY; accessUntil = 0; Recs.clearRatingsCache();
        Tmdb.credentialsChanged(app); Prefs.version++;
    }
    private static synchronized void loseCloud(AccountState expected, boolean loseSession) throws Exception {
        if (!state.owns(expected.authKey, expected.uid)) return;
        // A Noiro service response cannot invalidate the independently approved Stremio session.
        loseSession = loseSession && !standalone();
        AccountState next = loseSession ? AccountState.EMPTY : new AccountState(expected.authKey, expected.uid, expected.label, "", 0);
        // Revoke memory access even if encrypted storage is temporarily unwritable.
        state = next; lease = SupporterLease.empty(); leaseOwner = AccountState.EMPTY;
        deviceAccess = ""; accessOwner = AccountState.EMPTY; accessUntil = 0; Recs.clearRatingsCache();
        Tmdb.credentialsChanged(app); Prefs.version++;
        if (loseSession) { WatchProgress.clear(); vault.clear(); } else vault.save(next);
    }
    private static boolean revokesAccess(String code) {
        return code.equals("supporter_required") || code.equals("account_unavailable")
                || code.equals("account_link_required") || code.equals("identity_mismatch")
                || code.equals("tmdb_not_connected") || code.equals("tmdb_reconnect_required")
                || code.equals("server_update_required");
    }
    static void sync() throws Exception {
        AccountState expected = state;
        if (!expected.signedIn()) throw new AccountHttp.Failure("authentication_required");
        if (standalone()) {
            SupporterCheck check = beginSupporterCheck(true);
            if (check != null) checkSupporter(check);
            return;
        }
        JSONObject session;
        try {
            session = AccountHttp.call("https://noiro.tv/api/stremio-for-kodi/v1/session",
                    new JSONObject().put("authKey", expected.authKey).put("app", "noirotv-launcher").put("appVersion", APP_VERSION), "");
        } catch (AccountHttp.Failure failure) {
            if (failure.code.equals("authentication_required")) loseCloud(expected, true);
            else if (revokesAccess(failure.code)) loseCloud(expected, false);
            throw failure;
        }
        String access = session.optString("accessToken");
        if (access.isEmpty()) throw new AccountHttp.Failure("service_unavailable");
        final JSONObject response;
        final SupporterLease granted;
        final String key;
        long requestStarted = SystemClock.elapsedRealtime();
        try {
            response = AccountHttp.call("https://noiro.tv/api/stremio-for-kodi/v1/launcher-tmdb", null, access);
            if (!expected.uid.equals(response.optString("stremioUid"))) throw new AccountHttp.Failure("identity_mismatch");
            granted = parseLease(response, requestStarted);
            JSONObject tmdb = response.optJSONObject("tmdb");
            if (tmdb == null || !tmdb.optBoolean("configured")) throw new AccountHttp.Failure("tmdb_not_connected");
            key = TmdbCredential.normalize(tmdb.optString("apiKey"));
            if (!TmdbCredential.validInput(key)) throw new AccountHttp.Failure("tmdb_reconnect_required");
        } catch (AccountHttp.Failure failure) {
            if (failure.code.equals("authentication_required")) loseCloud(expected, true);
            else if (revokesAccess(failure.code)) loseCloud(expected, false);
            throw failure;
        }
        cacheDeviceAccess(expected, session);
        synchronized (NoiroAccount.class) {
            if (!state.owns(expected.authKey, expected.uid)) return; // logout/account-switch while the request was running
            String oldKey = Prefs.tmdbKey();
            AccountState connected = new AccountState(expected.authKey, expected.uid, expected.label, key, System.currentTimeMillis());
            vault.save(connected); state = connected; lease = granted; leaseOwner = connected;
            lastAccessAttempt = SystemClock.elapsedRealtime();
            Recs.clearRatingsCache();
            if (!oldKey.equals(key)) Tmdb.credentialsChanged(app);
            Prefs.version++;
        }
    }
    private static SupporterLease parseLease(JSONObject response, long started) throws AccountHttp.Failure {
        JSONObject access = response.optJSONObject("access");
        if (access == null) throw new AccountHttp.Failure("server_update_required");
        Object active = access.opt("active"), duration = access.opt("expiresIn");
        if (!(active instanceof Boolean) || !(duration instanceof Number)) throw new AccountHttp.Failure("server_update_required");
        long seconds = ((Number) duration).longValue();
        if (((Number) duration).doubleValue() != seconds) throw new AccountHttp.Failure("server_update_required");
        SupporterLease granted = SupporterLease.grant((Boolean) active, seconds, started);
        if (!granted.valid(SystemClock.elapsedRealtime())) {
            if (!standalone() || !((Boolean) active)) throw new AccountHttp.Failure("supporter_required");
            if (seconds <= 0 || seconds > SupporterLease.MAX_SECONDS || started < 0
                    || started > Long.MAX_VALUE - seconds * 1000)
                throw new AccountHttp.Failure("server_update_required");
            throw new AccountHttp.Failure("service_unavailable"); // A slow reply says nothing about membership.
        }
        return granted;
    }
    static synchronized boolean beginAccessRefresh() {
        long elapsed = SystemClock.elapsedRealtime();
        if (standalone() || checkingAccess || !state.signedIn() || elapsed - lastAccessAttempt < 60_000L) return false;
        lastAccessAttempt = elapsed; checkingAccess = true; return true;
    }
    static synchronized void endAccessRefresh() { checkingAccess = false; }
    static void refreshAccess() throws Exception {
        AccountState expected = state;
        if (!expected.signedIn()) throw new AccountHttp.Failure("authentication_required");
        if (standalone()) {
            SupporterCheck check = beginSupporterCheck(true);
            if (check != null) checkSupporter(check);
            return;
        }
        try {
            String token = accessToken(expected);
            long started = SystemClock.elapsedRealtime();
            JSONObject response = AccountHttp.call("https://noiro.tv/api/stremio-for-kodi/v1/launcher-access", null, token);
            if (!expected.uid.equals(response.optString("stremioUid"))) throw new AccountHttp.Failure("identity_mismatch");
            SupporterLease granted = parseLease(response, started);
            synchronized (NoiroAccount.class) {
                if (state.owns(expected.authKey, expected.uid)) { lease = granted; leaseOwner = expected; }
            }
        } catch (AccountHttp.Failure failure) {
            if (failure.code.equals("authentication_required")) loseCloud(expected, true);
            else if (revokesAccess(failure.code)) loseCloud(expected, false);
            throw failure;
        }
    }
    /** Shared automatic/manual entry point. A forced check bypasses retry delay, never an in-flight request. */
    static synchronized SupporterCheck beginSupporterCheck(boolean force) {
        if (!standalone() || !state.signedIn() || supporterCheck != null
                || !discovery.begin(SystemClock.elapsedRealtime(), force)) return null;
        supporterCheck = new SupporterCheck(state, discovery, force);
        Prefs.version++;
        return supporterCheck;
    }
    private static synchronized boolean current(SupporterCheck check) {
        return check != null && check == supporterCheck && check.generation == discovery
                && state.owns(check.owner.authKey, check.owner.uid);
    }
    static void checkSupporter(SupporterCheck check) throws Exception {
        if (!current(check)) return;
        try {
            String token = accessToken(check.owner);
            if (!current(check)) return;
            long started = SystemClock.elapsedRealtime();
            JSONObject response = AccountHttp.call("https://noiro.tv/api/stremio-for-kodi/v1/launcher-access", null, token, 8000);
            if (!current(check)) return;
            if (!check.owner.uid.equals(response.optString("stremioUid"))) throw new AccountHttp.Failure("identity_mismatch");
            SupporterLease granted = parseLease(response, started);
            boolean metadata;
            synchronized (NoiroAccount.class) {
                if (!current(check)) return;
                lease = granted; leaseOwner = state;
                discovery.success(SystemClock.elapsedRealtime(), granted.deadline);
                metadata = discovery.metadataDue(SystemClock.elapsedRealtime(), check.force);
                Recs.clearRatingsCache(); Prefs.version++;
            }
            if (metadata) refreshOptionalTmdb(check, token);
        } catch (Exception failure) {
            supporterFailure(check, failure);
            throw failure;
        } finally {
            synchronized (NoiroAccount.class) {
                if (current(check)) {
                    discovery.finish(); supporterCheck = null; Prefs.version++;
                }
            }
        }
    }
    private static void refreshOptionalTmdb(SupporterCheck check, String token) throws Exception {
        if (!current(check)) return;
        try {
            JSONObject response = AccountHttp.call("https://noiro.tv/api/stremio-for-kodi/v1/launcher-tmdb", null, token, 8000);
            if (!current(check)) return;
            if (!check.owner.uid.equals(response.optString("stremioUid"))) throw new AccountHttp.Failure("identity_mismatch");
            JSONObject tmdb = response.optJSONObject("tmdb");
            if (tmdb == null) return; // Optional service failure cannot take away verified membership.
            String key = tmdb.optBoolean("configured") ? TmdbCredential.normalize(tmdb.optString("apiKey")) : "";
            if (!key.isEmpty() && !TmdbCredential.validInput(key)) key = "";
            synchronized (NoiroAccount.class) {
                if (!current(check)) return;
                String oldKey = state.tmdbKey;
                AccountState updated = new AccountState(state.authKey, state.uid, state.label, key, System.currentTimeMillis());
                vault.save(updated); state = updated;
                if (!oldKey.equals(key)) Tmdb.credentialsChanged(app);
                Prefs.version++;
            }
        } catch (AccountHttp.Failure failure) {
            if (membershipDenial(failure.code) || failure.code.equals("identity_mismatch")) throw failure;
            // Missing/invalid TMDB and transient metadata outages leave the membership lease untouched.
        } catch (Exception metadataUnavailable) {
            // An optional credential/storage failure does not prevent already verified paid features.
        }
    }
    private static boolean membershipDenial(String code) {
        return code.equals("supporter_required") || code.equals("account_link_required")
                || code.equals("account_unavailable") || code.equals("authentication_required");
    }
    private static SupporterDiscovery.Status failureStatus(String code) {
        return code.equals("supporter_required") ? SupporterDiscovery.Status.FREE
                : code.equals("account_link_required") ? SupporterDiscovery.Status.LINK_REQUIRED
                : SupporterDiscovery.Status.UNAVAILABLE;
    }
    private static void supporterFailure(SupporterCheck check, Exception failure) throws Exception {
        synchronized (NoiroAccount.class) {
            if (!current(check)) return;
            String code = failure instanceof AccountHttp.Failure ? ((AccountHttp.Failure) failure).code : "service_unavailable";
            try {
                if (membershipDenial(code) || code.equals("identity_mismatch") || code.equals("server_update_required"))
                    loseCloud(check.owner, false);
            } finally {
                discovery.failure(failureStatus(code), SystemClock.elapsedRealtime()); Prefs.version++;
            }
        }
    }
    private static String accessToken(AccountState expected) throws Exception {
        synchronized (NoiroAccount.class) {
            if (accessOwner.owns(expected.authKey, expected.uid) && System.currentTimeMillis() < accessUntil) return deviceAccess;
        }
        JSONObject session = AccountHttp.call("https://noiro.tv/api/stremio-for-kodi/v1/session",
                new JSONObject().put("authKey", expected.authKey).put("app", "noirotv-launcher").put("appVersion", APP_VERSION), "");
        cacheDeviceAccess(expected, session);
        String token = session.optString("accessToken");
        if (token.isEmpty()) throw new AccountHttp.Failure("service_unavailable");
        return token;
    }
    private static synchronized void requireCloud(AccountState expected) throws AccountHttp.Failure {
        if (!state.owns(expected.authKey, expected.uid)) throw new AccountHttp.Failure("identity_mismatch");
        if (!ready()) throw new AccountHttp.Failure("supporter_required");
    }
    private static void cloudFailure(AccountState expected, AccountHttp.Failure failure) throws Exception {
        if (standalone()) {
            synchronized (NoiroAccount.class) {
                if (!state.owns(expected.authKey, expected.uid)) return;
                if (membershipDenial(failure.code) || failure.code.equals("identity_mismatch") || failure.code.equals("server_update_required")) {
                    try { loseCloud(expected, false); }
                    finally {
                        discovery = new SupporterDiscovery(); supporterCheck = null;
                        discovery.failure(failureStatus(failure.code), SystemClock.elapsedRealtime()); Prefs.version++;
                    }
                }
            }
            return;
        }
        if (failure.code.equals("authentication_required")) loseCloud(expected, true);
        else if (revokesAccess(failure.code)) loseCloud(expected, false);
    }
    private static String featureToken(AccountState expected) throws Exception {
        requireCloud(expected);
        final String token;
        try {
            token = accessToken(expected);
        } catch (AccountHttp.Failure failure) {
            cloudFailure(expected, failure);
            throw failure;
        }
        requireCloud(expected);
        return token;
    }
    private static JSONObject cloudCall(String url, JSONObject body, AccountState expected, int timeout) throws Exception {
        String token = featureToken(expected);
        final JSONObject response;
        try { response = AccountHttp.call(url, body, token, timeout); }
        catch (AccountHttp.Failure failure) { cloudFailure(expected, failure); throw failure; }
        // Natural expiry rejects this result without turning a temporary renewal outage into a permanent denial.
        requireCloud(expected);
        return response;
    }
    private static synchronized void cacheDeviceAccess(AccountState expected, JSONObject session) {
        if (!state.owns(expected.authKey, expected.uid)) return;
        deviceAccess=session.optString("accessToken"); accessOwner=expected;
        accessUntil=Math.min(System.currentTimeMillis()+10*60_000L,session.optLong("expiresAt",0)*1000L-30_000L);
    }
    static String updateToken() throws Exception { return updateToken(state); }
    static String updateToken(AccountState expected) throws Exception {
        if (!state.owns(expected.authKey, expected.uid)) throw new AccountHttp.Failure("authentication_required");
        return featureToken(expected);
    }
    static JSONObject progress(JSONObject changes, AccountState expected) throws Exception {
        JSONObject result = cloudCall("https://noiro.tv/api/stremio-for-kodi/v1/launcher-progress", changes, expected, 30000);
        if (!state.owns(expected.authKey, expected.uid) || !expected.uid.equals(result.optString("stremioUid")))
            throw new AccountHttp.Failure("identity_mismatch");
        return result;
    }
    static JSONObject subtitleRequest(String url, JSONObject payload, AccountState expected) throws Exception {
        return cloudCall(url, payload, expected, 25000);
    }
    static JSONObject playerState(String query,JSONObject changes,AccountState expected) throws Exception {
        JSONObject result=cloudCall("https://noiro.tv/api/stremio-for-kodi/v1/player-state"+query,changes,expected,8000);
        if(!state.owns(expected.authKey,expected.uid)||!expected.uid.equals(result.optString("stremioUid")))throw new AccountHttp.Failure("identity_mismatch");
        return result;
    }
    static JSONObject ratings(String kind, int id) throws Exception {
        AccountState expected=state;
        if(!expected.signedIn() || !(kind.equals("movie")||kind.equals("tv")) || id<=0)throw new AccountHttp.Failure("service_unavailable");
        JSONObject data=cloudCall("https://noiro.tv/api/stremio-for-kodi/v1/launcher-ratings?type="+kind+"&id="+id,null,expected,8000);
        if(!state.owns(expected.authKey,expected.uid)||!expected.uid.equals(data.optString("stremioUid")))throw new AccountHttp.Failure("identity_mismatch");
        return data;
    }
    static synchronized boolean beginRefresh() {
        if (standalone() || refreshing || !state.ready() || (!coldStart && System.currentTimeMillis() - state.syncedAt < 15 * 60_000L)) return false;
        coldStart = false; refreshing = true; return true;
    }
    static synchronized void endRefresh() { refreshing = false; }
}

