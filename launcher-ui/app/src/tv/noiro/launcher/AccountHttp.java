package tv.noiro.launcher;

import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Bounded HTTPS JSON transport. No redirects or raw error/credential logging. */
final class AccountHttp {
    static final class Failure extends Exception {
        final String code;
        Failure(String code) { super(code); this.code = code; }
    }
    static JSONObject call(String url, JSONObject body, String bearer) throws Failure {
        return call(url, body, bearer, 12000);
    }
    static JSONObject call(String url, JSONObject body, String bearer, int readTimeout) throws Failure {
        HttpURLConnection connection = null;
        try {
            URL target = new URL(url);
            if (!target.getProtocol().equals("https") || target.getUserInfo() != null || target.getPort() != -1)
                throw new Failure("service_unavailable");
            connection = (HttpURLConnection) target.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(8000); connection.setReadTimeout(readTimeout);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "NoiroTV-Launcher/" + NoiroAccount.APP_VERSION);
            if (!bearer.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + bearer);
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setRequestMethod("POST"); connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            JSONObject data;
            try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                if (input == null) throw new Failure("service_unavailable");
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 1024 * 1024) throw new Failure("service_unavailable");
                    output.write(buffer, 0, count);
                }
                data = new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
            }
            if (status < 200 || status >= 300) {
                if (target.getHost().equals("noiro.tv")) {
                    if(target.getPath().endsWith("/player-state")&&status==409)throw new Failure("revision_conflict");
                    if(target.getPath().endsWith("/player-state")&&data.optString("error").equals("account_binding_changed"))throw new Failure("account_binding_changed");
                    String error = data.optString("error");
                    if (target.getPath().endsWith("/launcher-subtitles")) {
                        if (java.util.Arrays.asList("subtitle_job_not_found", "subtitle_source_not_found",
                                "invalid_source_subtitle", "subtitle_source_unavailable", "subtitle_source_too_large",
                                "umbrel_queue_full", "umbrel_translation_failed", "translation_unavailable").contains(error))
                            throw new Failure(error);
                    }
                    if (status == 404) throw new Failure("server_update_required");
                    if (error.equals("supporter_required") || data.optString("reason").equals("supporter_required")) throw new Failure("supporter_required");
                    if (error.equals("account_link_required") || error.equals("account_unavailable") || error.equals("tmdb_reconnect_required") || error.equals("provider_login_required") || error.equals("provider_rate_limited") || error.equals("provider_unavailable"))
                        throw new Failure(error);
                }
                if (status == 401) throw new Failure("authentication_required");
                if (status == 429) throw new Failure("rate_limited");
                throw new Failure("service_unavailable");
            }
            return data;
        } catch (Failure failure) { throw failure; }
        catch (Exception failure) { throw new Failure("service_unavailable"); }
        finally { if (connection != null) connection.disconnect(); }
    }
}
