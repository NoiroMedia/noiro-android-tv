package tv.noiro.launcher;

import android.content.Context;

/** Basic app entry is independent from optional, verified Noiro cloud access. */
final class AppAccess {
    private AppAccess() {}

    static boolean ready(Context context) {
        return allows(AppIdentity.standalone(context), NoiroAccount.current().signedIn(), NoiroAccount.ready());
    }

    static boolean canBrowse(Context context) {
        return AppIdentity.standalone(context) || NoiroAccount.ready();
    }

    static boolean allows(boolean standalone, boolean signedIn, boolean cloudReady) {
        return standalone ? signedIn : cloudReady;
    }
}
