package com.misha.f5daemon;
import android.app.*;
import android.content.*;
import android.os.*;
import android.provider.Settings;
import android.util.Log;
import java.io.*;
// F5-3 panel daemon: /dev/ttyAS2 @115200, 7-byte frames fc..fe
// Presence: fc 00 1c 00 00 00 fe = present, fc 00 0c 00 00 00 fe = away
// Momentary: fc 00 00 00 01 <id 04..08> fe -> Buttons 4..8 (id IS the number;
//   old top-to-bottom buttons 5..1, reversed)
// Toggle: fc 00 00 a3/a2/a1 <state 01/00> <code 03/02/01> fe -> Relays 3/2/1
//   (code IS the relay number; old L3/L2/L1, old buttons 6/7/8)
// Transport: MQTT discovery (broker configured in MainActivity settings).
// Relays are reported as binary_sensor state, NOT switch, because no
// outbound MCU command to change their state was found in vendor code.
public class ButtonService extends Service {
    private static final String TAG = "F5Daemon";
    private volatile boolean running = false;
    private volatile boolean reconnectRunning = false;
    private PowerManager.WakeLock wakeLock;
    private FileInputStream serialIn;
    private final MqttPublisher mqtt = new MqttPublisher();
    @Override public IBinder onBind(Intent i) { return null; }
    public static String deviceName(Context ctx, SharedPreferences p) {
        String saved = p.getString("device_name", null);
        if (saved != null && !saved.isEmpty()) return saved;
        try {
            String dn = Settings.Global.getString(ctx.getContentResolver(), Settings.Global.DEVICE_NAME);
            if (dn != null && !dn.isEmpty()) return dn;
        } catch (Exception ignored) {}
        if (Build.MODEL != null && !Build.MODEL.isEmpty()) return Build.MODEL;
        return "F5 Entryway Panel";
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("f5daemon", "F5 Panel Daemon", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
        Intent ui = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, ui, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, "f5daemon")
            .setContentTitle("F5 panel daemon").setContentText("Listening on ttyAS2")
            .setContentIntent(pi)
            .setSmallIcon(android.R.drawable.ic_dialog_info).build();
        startForeground(1, n);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (wakeLock == null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "F5Daemon::serial");
            wakeLock.setReferenceCounted(false);
        }
        if (!wakeLock.isHeld()) wakeLock.acquire();
        if (!running) { running = true; new Thread(this::readLoop).start(); }
        if (!reconnectRunning) { reconnectRunning = true; new Thread(this::reconnectLoop).start(); }
        // Settings may have changed on Save: drop the old broker connection
        // so the next connect picks up the new host/credentials/device name.
        // (Previously a connected client kept the old broker forever.)
        mqtt.disconnect();
        new Thread(() -> {
            try {
                SharedPreferences p = getSharedPreferences("f5", MODE_PRIVATE);
                String host = p.getString("host", "");
                if (!host.isEmpty()) {
                    int port = 1883;
                    try { port = Integer.parseInt(p.getString("port", "1883")); } catch (Exception ignored) {}
                    mqtt.connect(host, port, p.getString("user", ""), PasswordStore.getPassword(p), deviceName(this, p));
                }
            } catch (Exception e) { Log.w(TAG, "mqtt connect on start failed, reconnect loop will retry: " + e); }
        }).start();
        return START_STICKY;
    }
    private void reconnectLoop() {
        while (reconnectRunning) {
            try {
                if (!mqtt.connected()) {
                    SharedPreferences p = getSharedPreferences("f5", MODE_PRIVATE);
                    String host = p.getString("host", "");
                    if (!host.isEmpty()) {
                        int port = 1883;
                        try { port = Integer.parseInt(p.getString("port", "1883")); } catch (Exception ignored) {}
                        mqtt.connect(host, port, p.getString("user", ""), PasswordStore.getPassword(p), deviceName(this, p));
                    }
                }
            } catch (Exception e) { Log.w(TAG, "mqtt connect failed, will retry: " + e); }
            try { Thread.sleep(30000); } catch (InterruptedException ignored) { break; }
        }
    }
    private void readLoop() {
        try { Runtime.getRuntime().exec(new String[]{"sh","-c","stty -F /dev/ttyAS2 115200 raw"}).waitFor(); } catch (Exception e) { Log.w(TAG, "stty failed", e); }
        try {
            serialIn = new FileInputStream("/dev/ttyAS2");
            byte[] frame = new byte[7]; int pos = 0;
            while (running) {
                int b = serialIn.read(); if (b < 0) break;
                if (pos == 0 && b != 0xfc) continue;
                frame[pos++] = (byte) b;
                if (pos == 7) { pos = 0; if (frame[6] == (byte) 0xfe) handleFrame(frame); }
            }
        } catch (Exception e) { if (running) Log.e(TAG, "serial read failed", e); }
    }
    private void handleFrame(byte[] f) {
        StringBuilder sb = new StringBuilder();
        for (byte b : f) sb.append(String.format("%02x ", b));
        Log.i(TAG, "frame: " + sb.toString().trim());
        try {
            if (f[2] == 0x1c) mqtt.presence(true);
            else if (f[2] == 0x0c) mqtt.presence(false);
            else if (f[2] == 0x00 && f[3] == 0x00 && f[4] == 0x01) {
                mqtt.buttonPress(f[5] & 0xff); // id 04..08 = Button 4..8
            } else if (f[2] == 0x00 && (f[3] & 0xf0) == 0xa0) {
                mqtt.relay(f[5] & 0xff, f[4] == 0x01); // code 01..03 = Relay 1..3
            }
        } catch (Exception e) { Log.w(TAG, "mqtt publish failed", e); }
    }
    @Override public void onDestroy() {
        running = false; reconnectRunning = false;
        try { if (serialIn != null) serialIn.close(); } catch (Exception ignored) {}
        mqtt.disconnect();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }
}
