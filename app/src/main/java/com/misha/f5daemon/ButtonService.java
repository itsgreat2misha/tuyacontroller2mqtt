package com.misha.f5daemon;
import android.app.*;
import android.content.*;
import android.os.*;
import android.util.Log;
import java.io.*;
import java.util.*;
// F5-3 panel daemon: /dev/ttyAS2 @115200, 7-byte frames fc..fe
// B1-B5 momentary: fc 00 00 00 01 08..04 fe
// B6-B8 toggle:    fc 00 00 a3/a2/a1 <state 01/00> 03/02/01 fe  (B6=L3, B7=L2, B8=L1)
// Presence:        fc 00 1c 00 00 00 fe = present, fc 00 0c 00 00 00 fe = away
// Transport: MQTT discovery (broker configured in MainActivity settings).
// Toggle buttons are reported as binary_sensor state, NOT switch, because
// no outbound MCU command to change their state was found in vendor code.
public class ButtonService extends Service {
    private static final String TAG = "F5Daemon";
    private volatile boolean running = false;
    private PowerManager.WakeLock wakeLock;
    private final MqttPublisher mqtt = new MqttPublisher();
    @Override public IBinder onBind(Intent i) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("f5daemon", "F5 Panel Daemon", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
        Notification n = new Notification.Builder(this, "f5daemon")
            .setContentTitle("F5 panel daemon").setContentText("Listening on ttyAS2")
            .setSmallIcon(android.R.drawable.ic_dialog_info).build();
        startForeground(1, n);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "F5Daemon::serial");
        wakeLock.acquire();
        if (!running) { running = true; new Thread(this::readLoop).start(); }
        // Connect + publish discovery immediately on start, not only on the
        // first button frame, so entities appear in HA right after Save.
        new Thread(() -> {
            try {
                android.content.SharedPreferences p = getSharedPreferences("f5", MODE_PRIVATE);
                String host = p.getString("host", "");
                if (!host.isEmpty()) mqtt.connect(host, Integer.parseInt(p.getString("port", "1883")), p.getString("user", ""), p.getString("pass", ""));
            } catch (Exception e) { Log.w(TAG, "mqtt connect on start failed: " + e, e); }
        }).start();
        return START_STICKY;
    }
    private void readLoop() {
        // Baud is set via stty before opening: the port defaults to 9600 on boot.
        try { Runtime.getRuntime().exec(new String[]{"sh","-c","stty -F /dev/ttyAS2 115200 raw"}).waitFor(); } catch (Exception e) { Log.w(TAG, "stty failed", e); }
        try (FileInputStream in = new FileInputStream("/dev/ttyAS2")) {
            byte[] frame = new byte[7]; int pos = 0;
            while (running) {
                int b = in.read(); if (b < 0) break;
                if (pos == 0 && b != 0xfc) continue; // resync on fc
                frame[pos++] = (byte) b;
                if (pos == 7) { pos = 0; if (frame[6] == (byte) 0xfe) handleFrame(frame); }
            }
        } catch (Exception e) { Log.e(TAG, "serial read failed", e); }
    }
    private void handleFrame(byte[] f) {
        StringBuilder sb = new StringBuilder();
        for (byte b : f) sb.append(String.format("%02x ", b));
        Log.i(TAG, "frame: " + sb.toString().trim());
        try {
            if (!mqtt.connected()) {
                android.content.SharedPreferences p = getSharedPreferences("f5", MODE_PRIVATE);
                String host = p.getString("host", "");
                if (!host.isEmpty()) mqtt.connect(host, Integer.parseInt(p.getString("port", "1883")), p.getString("user", ""), p.getString("pass", ""));
            }
            if (f[2] == 0x1c) mqtt.presence(true);
            else if (f[2] == 0x0c) mqtt.presence(false);
            else if (f[2] == 0x00 && f[3] == 0x00 && f[4] == 0x01) {
                // momentary B1..B5: id byte f[5] is 08..04, button = 9 - id
                mqtt.buttonPress(9 - (f[5] & 0xff));
            } else if (f[2] == 0x00 && (f[3] & 0xf0) == 0xa0) {
                // toggle: f[3]=a3/a2/a1, f[4]=state, f[5]=03/02/01 -> l3/l2/l1
                String name = f[5] == 0x03 ? "l3" : f[5] == 0x02 ? "l2" : "l1";
                mqtt.toggle(name, f[4] == 0x01);
            }
        } catch (Exception e) { Log.w(TAG, "mqtt publish failed", e); }
    }
    @Override public void onDestroy() { running = false; if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); super.onDestroy(); }
}
