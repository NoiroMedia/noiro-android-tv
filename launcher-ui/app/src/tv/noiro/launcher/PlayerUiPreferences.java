package tv.noiro.launcher;

import android.content.Context;
import android.content.SharedPreferences;

/** Device-local player UI preferences. Existing installations retain Classic. */
final class PlayerUiPreferences {
    private static SharedPreferences store(Context c) { return c.getSharedPreferences("settings", Context.MODE_PRIVATE); }
    static boolean noiro(Context c) {
        try { return "noiro".equals(store(c).getString("player_appearance", "classic")); }
        catch (RuntimeException invalid) { return false; }
    }
    static void noiro(Context c, boolean enabled) { store(c).edit().putString("player_appearance", enabled ? "noiro" : "classic").apply(); }
    static float speed(Context c) {
        try { return PlayerChromePolicy.speed(Float.parseFloat(store(c).getString("player_speed", "1"))); }
        catch (RuntimeException invalid) { return 1f; }
    }
    static void speed(Context c, float value) { store(c).edit().putString("player_speed", String.valueOf(PlayerChromePolicy.speed(value))).apply(); }
    static int aspect(Context c) {
        try { return PlayerChromePolicy.aspect(Integer.parseInt(store(c).getString("player_aspect", "0"))); }
        catch (RuntimeException invalid) { return 0; }
    }
    static void aspect(Context c, int value) { store(c).edit().putString("player_aspect", String.valueOf(PlayerChromePolicy.aspect(value))).apply(); }
    static int integer(Context c,String key,int fallback) {
        try{return Integer.parseInt(store(c).getString("player_"+key,String.valueOf(fallback)));}catch(RuntimeException invalid){return fallback;}
    }
    static void setInteger(Context c,String key,int value){store(c).edit().putString("player_"+key,String.valueOf(value)).apply();}
    static boolean enabled(Context c,String key,boolean fallback){return integer(c,key,fallback?1:0)==1;}
    static void setEnabled(Context c,String key,boolean value){setInteger(c,key,value?1:0);}
}
