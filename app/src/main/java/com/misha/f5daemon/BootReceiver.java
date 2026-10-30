package com.misha.f5daemon;
import android.content.*;
import android.util.Log;
public class BootReceiver extends BroadcastReceiver {
    // Retained for ROMs where BOOT_COMPLETED delivery works. On the
    // Tuya F5-3 panel (Oct 30, 2026) ActivityManager skips delivery to
    // this receiver entirely (even for a custom explicit broadcast,
    // while Kiosk's own receiver delivers), so the panel's real
    // autostart is the Kiosk Autostart plugin's direct service start
    // (am start-foreground-service -n com.misha.f5daemon/.ButtonService).
    // Do not "fix" this by more BOOT_COMPLETED tweaks on that panel.
    @Override public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        Log.i("F5Daemon", "BootReceiver fired, action=" + action);
        // LOCKED_BOOT_COMPLETED is logged only: the service reads
        // credential-encrypted SharedPreferences (MQTT host/password),
        // which are unavailable before unlock, and the service itself is
        // deliberately NOT direct-boot aware, so starting it here would
        // run with empty config. The real start is BOOT_COMPLETED
        // (post-unlock). QUICKBOOT_POWERON is the same as BOOT_COMPLETED
        // on devices that use it (Kiosk Satellite listens for it too).
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action))
            ctx.startForegroundService(new Intent(ctx, ButtonService.class));
    }
}
