package com.misha.f5daemon;
import android.app.*;
import android.content.*;
import android.os.*;
import android.provider.Settings;
import android.util.Log;
import java.io.*;
// Tuya Android controller daemon (starting with the F5-3 panel):
// /dev/ttyAS2 @115200, 7-byte frames fc..fe
// Motion/approach: fc 00 1c 00 00 00 fe = motion, fc 00 0c 00 00 00 fe = clear
//   (called "radar PIR" in the F5 manual; see README -- motion, not proven
//   static presence, so device_class is motion, not occupancy)
// Momentary: fc 00 00 00 01 <id 04..08> fe -> Buttons 4..8 (id IS the number)
// Toggle: fc 00 00 a3/a2/a1 <state 01/00> <code 03/02/01> fe -> Relays 3/2/1
//   (code IS the relay number)
// Transport: MQTT discovery, topics tuyacontroller/<device-slug>/...
// Relays are binary_sensor state, NOT switch, because no outbound MCU
// command to change their state was found in vendor code. The two HA
// switches are different: they control daemon options (Wake Lock,
// Wake Screen), not the panel's relays.
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
        return "Tuya Controller";
    }
    public static boolean wakelockEnabled(SharedPreferences p) { return p.getBoolean("wakelock_enabled", true); }
    public static boolean wakescreenEnabled(SharedPreferences p) { return p.getBoolean("wakescreen_enabled", true); }
    private void applyWakelock() {
        SharedPreferences p = getSharedPreferences("f5", MODE_PRIVATE);
        if (wakelockEnabled(p)) {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "F5Daemon::serial");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) wakeLock.acquire();
        } else if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("f5daemon", "Tuya Controller Daemon", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
        Intent ui = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, ui, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, "f5daemon")
            .setContentTitle("Tuya controller daemon").setContentText("Listening on ttyAS2")
            .setContentIntent(pi)
            .setSmallIcon(android.R.drawable.ic_dialog_info).build();
        startForeground(1, n);
        applyWakelock();
        mqtt.setCommandListener((topic, payload) -> {
            try {
                SharedPreferences p = getSharedPreferences("f5", MODE_PRIVATE);
                SharedPreferences.Editor ed = p.edit();
                boolean on = "ON".equalsIgnoreCase(payload.trim());
                if (topic.endsWith("/wakelock/set")) { ed.putBoolean("wakelock_enabled", on); ed.apply(); applyWakelock(); }
                else if (topic.endsWith("/wakescreen/set")) { ed.putBoolean("wakescreen_enabled", on); ed.apply(); }
                mqtt.publishOptionStates(wakelockEnabled(p), wakescreenEnabled(p));
                Log.i(TAG, "mqtt command " + topic + " -> " + payload);
            } catch (Exception e) { Log.w(TAG, "mqtt command failed: " + e, e); }
        });
        if (!running) { running = true; new Thread(this::readLoop).start(); }
        if (!reconnectRunning) { reconnectRunning = true; new Thread(this::reconnectLoop).start(); }
        mqtt.disconnect();
        new Thread(() -> { try { connectNow(); } catch (Exception e) { Log.w(TAG, "mqtt connect on start failed, reconnect loop will retry: " + e); } }).start();
        return START_STICKY;
    }
    private void connectNow() throws Exception {
        SharedPreferences p = getSharedPreferences("f5", MODE_PRIVATE);
        String host = p.getString("host", "");
        if (host.isEmpty()) return;
        int port = 1883;
        try { port = Integer.parseInt(p.getString("port", "1883")); } catch (Exception ignored) {}
        mqtt.connect(host, port, p.getString("user", ""), PasswordStore.getPassword(p), deviceName(this, p));
        mqtt.publishOptionStates(wakelockEnabled(p), wakescreenEnabled(p));
    }
    private void reconnectLoop() {
        while (reconnectRunning) {
            try {
                if (mqtt.connected()) mqtt.ping();
                else connectNow();
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
    private void wakeScreen() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm.isInteractive()) return;
            // Best effort on this rooted panel: su + input keyevent 224
            // (KEYCODE_WAKEUP). A normal app cannot hold a screen wake lock
            // (DEVICE_POWER is signature-only). Captures su/input stderr
            // too: exit=1 alone (seen Oct 29) does not say whether su
            // denied the app uid or input itself failed.
            ProcessBuilder pb = new ProcessBuilder("su", "-c", "input keyevent 224");
            pb.redirectErrorStream(true);
            java.lang.Process proc = pb.start();
            StringBuilder out = new StringBuilder();
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                String line; while ((line = r.readLine()) != null) { if (out.length() > 0) out.append(" | "); out.append(line); }
            } catch (Exception ignored) {}
            int rc = proc.waitFor();
            Log.i(TAG, "wake screen via su, exit=" + rc + " output=" + out);
        } catch (Exception e) { Log.w(TAG, "wake screen failed (su unavailable?): " + e); }
    }
    private void handleFrame(byte[] f) {
        StringBuilder sb = new StringBuilder();
        for (byte b : f) sb.append(String.format("%02x ", b));
        Log.i(TAG, "frame: " + sb.toString().trim());
        try {
            if (f[2] == 0x1c) mqtt.motion(true);
            else if (f[2] == 0x0c) mqtt.motion(false);
            else if (f[2] == 0x00 && f[3] == 0x00 && f[4] == 0x01) {
                if (wakescreenEnabled(getSharedPreferences("f5", MODE_PRIVATE))) wakeScreen();
                mqtt.buttonPress(f[5] & 0xff);
            } else if (f[2] == 0x00 && (f[3] & 0xf0) == 0xa0) {
                if (wakescreenEnabled(getSharedPreferences("f5", MODE_PRIVATE))) wakeScreen();
                mqtt.relay(f[5] & 0xff, f[4] == 0x01);
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
