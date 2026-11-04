package com.tuyacontroller2mqtt;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;
import java.util.concurrent.atomic.AtomicBoolean;
// Write Settings is special app-access: there is no runtime
// permission to request, and the only in-app grant route is the
// system settings screen (ACTION_MANAGE_WRITE_SETTINGS). On a panel
// with su the grant can be applied directly instead, because
// Settings.System.canWrite reads the WRITE_SETTINGS app-op — see
// AccessLogic.autoGrantArgs for the command. Attempts are
// fire-and-forget on a background thread: a missing or broken su
// changes nothing, blocks nothing, and shows nothing; the settings
// screen's button remains as the manual fallback and always shows
// the live grant state.
public final class WriteSettingsGrant {
    private static final AtomicBoolean ATTEMPT_RUNNING = new AtomicBoolean(false);
    private WriteSettingsGrant() {}
    public static boolean isGranted(Context ctx) {
        try { return Settings.System.canWrite(ctx); } catch (Exception e) { return false; }
    }
    // If the grant is missing, try to apply it via su now. onGranted
    // runs on the attempt thread if the grant is in place afterwards
    // (callers touching UI must marshal to the UI thread themselves).
    // Concurrent calls collapse into the one running attempt.
    public static void attemptAutoGrantAsync(Context ctx, Runnable onGranted) {
        if (!AccessLogic.shouldAttemptAutoGrant(isGranted(ctx))) return;
        if (!ATTEMPT_RUNNING.compareAndSet(false, true)) return;
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                SuExec.run(AccessLogic.autoGrantArgs(app.getPackageName()));
                if (isGranted(app)) {
                    Log.i(Constants.TAG, "WRITE_SETTINGS auto-granted via su appops");
                    if (onGranted != null) onGranted.run();
                } else {
                    Log.i(Constants.TAG, "WRITE_SETTINGS auto-grant did not take (no su, or op not applied); manual grant via settings still available");
                }
            } finally { ATTEMPT_RUNNING.set(false); }
        }).start();
    }
}
