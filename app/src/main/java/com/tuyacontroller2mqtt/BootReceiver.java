package com.tuyacontroller2mqtt;
import android.content.*;
import android.util.Log;
public class BootReceiver extends BroadcastReceiver {
    // Retained for ROMs where BOOT_COMPLETED delivery works. On the
    // F5-3's vendor ROM, ActivityManager skips delivery to this
    // receiver entirely (even for a custom explicit broadcast), so on
    // that panel autostart must come from something else starting the
    // service directly, e.g.
    // am start-foreground-service -n com.tuyacontroller2mqtt/.ButtonService
    // Do not "fix" this by more BOOT_COMPLETED tweaks on that panel.
    @Override public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        Log.i("TuyaController", "BootReceiver fired, action=" + action);
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
