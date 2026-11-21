package tv.noiro.launcher;

import android.os.Handler;
import android.os.Looper;

/** Non-blocking membership discovery shared by foreground app screens. */
final class SupporterChecks {
    private SupporterChecks() {}

    static boolean start(boolean force, Runnable finished) {
        NoiroAccount.SupporterCheck check = NoiroAccount.beginSupporterCheck(force);
        if (check == null) return false;
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try { NoiroAccount.checkSupporter(check); }
            catch (Exception unavailable) { /* Account state records the verification result; basic access stays available. */ }
            finally { if (finished != null) main.post(finished); }
        }, "noiro-supporter-check").start();
        return true;
    }
}
