package tv.noiro.launcher;

import android.content.Context;

/** Installed app identity is separate from the shared launcher implementation and cloud protocol. */
final class AppIdentity {
    private AppIdentity() {}

    static boolean standalone(Context context) {
        return "tv.noiro.androidtv".equals(context.getPackageName());
    }

    static String name(Context context) {
        return context.getString(R.string.app_name);
    }
}
