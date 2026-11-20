package tv.noiro.launcher;

import org.json.JSONObject;
import java.net.URLEncoder;

/** Official link flow: approve code, exchange one-time token, then verify the account session. */
final class StremioLogin {
    static final String LINK = "https://link.stremio.com/api/v2/";
    static final String API = "https://api.strem.io/api/";
    static final class Code {
        final String value, url;
        Code(String value, String url) { this.value = value; this.url = url; }
    }
    static Code create() throws Exception {
        JSONObject result = AccountHttp.call(LINK + "create?type=Create", null, "").optJSONObject("result");
        if (result == null) throw new AccountHttp.Failure("service_unavailable");
        String code = result.optString("code"), url = result.optString("link");
        if (code.isEmpty() || code.length() > 128 || !LoginPolicy.trustedLink(url)) throw new AccountHttp.Failure("service_unavailable");
        return new Code(code, url);
    }
    /** null is the pending sentinel; every other provider error stops the loop. */
    static String poll(Code code) throws Exception {
        JSONObject data = AccountHttp.call(LINK + "read?type=Read&code=" + URLEncoder.encode(code.value, "UTF-8"), null, "");
        JSONObject error = data.optJSONObject("error");
        if (error != null) {
            if (error.optInt("code", -1) == 101) return null;
            throw new AccountHttp.Failure("code_rejected");
        }
        if (data.has("error") && !data.isNull("error")) throw new AccountHttp.Failure("code_rejected");
        JSONObject result = data.optJSONObject("result");
        if (result == null) return null;
        String key = result.optString("authKey");
        if (key.isEmpty()) key = result.optString("auth_key");
        return key.isEmpty() ? null : key;
    }
    static AccountState exchange(String token) throws Exception {
        JSONObject data = AccountHttp.call(API + "loginWithToken", new JSONObject().put("type", "LoginWithToken").put("token", token), "");
        JSONObject result = data.optJSONObject("result");
        if ((data.has("error") && !data.isNull("error")) || result == null) throw new AccountHttp.Failure("code_rejected");
        String authKey = result.optString("authKey");
        if (authKey.isEmpty() || authKey.length() > 1024) throw new AccountHttp.Failure("code_rejected");
        JSONObject userData = AccountHttp.call(API + "getUser", new JSONObject().put("type", "GetUser").put("authKey", authKey), "");
        JSONObject user = userData.optJSONObject("result");
        if ((userData.has("error") && !userData.isNull("error")) || user == null || !LoginPolicy.validUid(user.optString("_id")))
            throw new AccountHttp.Failure("authentication_required");
        return new AccountState(authKey, user.getString("_id"), user.optString("email"), "", 0);
    }
}
