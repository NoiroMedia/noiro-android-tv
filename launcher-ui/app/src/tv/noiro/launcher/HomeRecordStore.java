package tv.noiro.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;

/** Commit recovery data synchronously before issuing any system command. */
final class HomeRecordStore implements HomeSwitch.Store {
    private final SharedPreferences prefs;
    HomeRecordStore(Context context) { prefs = context.getSharedPreferences("home_recovery", Context.MODE_PRIVATE); }
    @Override public HomeSwitch.Record read() {
        String home = prefs.getString("original", "");
        return home.isEmpty() ? null : new HomeSwitch.Record(home, prefs.getString("disabled", ""));
    }
    @Override public void write(HomeSwitch.Record record) throws IOException {
        if (!prefs.edit().putString("original", record.original).putString("disabled", record.disabled).commit())
            throw new IOException("Cannot save Home recovery information. No changes will be attempted.");
    }
    @Override public void clear() throws IOException {
        if (!prefs.edit().clear().commit()) throw new IOException("Home restored, but recovery information could not be cleared.");
    }
}
