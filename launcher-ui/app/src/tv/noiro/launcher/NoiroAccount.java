package tv.noiro.launcher;

import android.content.Context;
import android.os.SystemClock;
import org.json.JSONObject;

/** UID-scoped encrypted device account. No client-selected Noiro user IDs. */
final class NoiroAccount {
    static final String APP_VERSION = "1.8.40";
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
    static synchronized void init(Context context) {
        if (vault != null) return;
        app = context.getApplicationContext();
        vault = new SessionVault(app);
        state = vault.load();
    }
    static AccountState current() { return state; }
    static synchronized boolean ready() { return state.ready() && leaseOwner.owns(state.authKey, state.uid) && lease.valid(SystemClock.elapsedRealtime()); }
    static synchronized boolean configured() { return state.ready(); }
    private static boolean standalone() { return app != null && AppIdentity.standalone(app); }
    static synchronized String tmdbKey() { return ready() ? state.tmdbKey : ""; }
    static synchronized void remember(AccountState account) throws Exception {
        if (!account.signedIn()) throw new AccountHttp.Failure("authentication_required");
        String oldUid = state.uid;
        vault.save(account); state = account; lease = SupporterLease.empty(); leaseOwner = AccountState.EMPTY;
        deviceAccess = ""; accessOwner = AccountState.EMPTY; accessUntil = 0; Recs.clearRatingsCache();
        if (!oldUid.equals(account.uid)) { WatchProgress.clear(); Tmdb.credentialsChanged(app); }
        Prefs.version++;
    }
    static synchronized void signOut() throws Exception {
        WatchProgress.clear();
        vault.clear(); state = AccountState.EMPTY; lease = SupporterLease.empty(); leaseOwner = AccountState.EMPTY;
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
        if (!granted.valid(SystemClock.elapsedRealtime())) throw new AccountHttp.Failure("supporter_required");
        return granted;
    }
    static synchronized boolean beginAccessRefresh() {
        long elapsed = SystemClock.elapsedRealtime();
        if (checkingAccess || !(standalone() ? state.ready() : state.signedIn()) || elapsed - lastAccessAttempt < 60_000L) return false;
        lastAccessAttempt = elapsed; checkingAccess = true; return true;
    }
    static synchronized void endAccessRefresh() { checkingAccess = false; }
    static void refreshAccess() throws Exception {
        AccountState expected = state;
        if (!expected.signedIn()) throw new AccountHttp.Failure("authentication_required");
        if (standalone() && !expected.ready()) throw new AccountHttp.Failure("supporter_required");
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
        if (refreshing || !state.ready() || (!coldStart && System.currentTimeMillis() - state.syncedAt < 15 * 60_000L)) return false;
        coldStart = false; refreshing = true; return true;
    }
    static synchronized void endRefresh() { refreshing = false; }
}

