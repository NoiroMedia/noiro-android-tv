package tv.noiro.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;
import java.io.IOException;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;

/** One encrypted, non-backed-up device snapshot; the encryption key stays in Android Keystore. */
final class SessionVault {
    private static final String ALIAS = "NoiroTVLauncherAccountV1";
    private final SharedPreferences prefs;
    SessionVault(Context context) { prefs = context.getSharedPreferences("noiro_account_encrypted", Context.MODE_PRIVATE); }
    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
    AccountState load() {
        String value = prefs.getString("cipher", ""), iv = prefs.getString("iv", "");
        if (value.isEmpty() || iv.isEmpty()) return AccountState.EMPTY;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            JSONObject data = new JSONObject(new String(cipher.doFinal(Base64.decode(value, Base64.NO_WRAP)), StandardCharsets.UTF_8));
            return new AccountState(data.optString("authKey"), data.optString("uid"), data.optString("label"), data.optString("tmdbKey"), data.optLong("syncedAt"));
        } catch (Exception invalidOrInvalidated) { return AccountState.EMPTY; }
    }
    void save(AccountState state) throws Exception {
        JSONObject data = new JSONObject().put("authKey", state.authKey).put("uid", state.uid)
                .put("label", state.label).put("tmdbKey", state.tmdbKey).put("syncedAt", state.syncedAt);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(data.toString().getBytes(StandardCharsets.UTF_8));
        if (!prefs.edit().putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .putString("cipher", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) throw new IOException("account_storage_failed");
    }
    void clear() throws IOException {
        if (!prefs.edit().clear().commit()) throw new IOException("account_storage_failed");
    }
}
