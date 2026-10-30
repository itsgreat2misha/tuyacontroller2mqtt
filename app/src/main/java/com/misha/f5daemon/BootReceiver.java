package com.misha.f5daemon;
import android.content.*;
import android.util.Log;
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent intent) {
        Log.i("F5Daemon", "BOOT_COMPLETED received, starting ButtonService");
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()))
            ctx.startForegroundService(new Intent(ctx, ButtonService.class));
    }
}
