package com.misha.f5daemon;
import android.app.*;
import android.content.*;
import android.media.AudioManager;
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
// Outbound relay set -- v7 correction (Oct 30, 2026):
//   McuTestActivity frames fc 00 00 a<N> <01/00> 00 fe are TEST frames:
//   they change the MCU's reported/query state, but NOT the button LED
//   or the actual relay. The real set frame is the one vendor
//   A485McuUtil.send() sends for an unbound local switch, and it is
//   identical to an inbound physical-press frame:
//   fc 00 00 a<N> <01 on / 00 off> <N> fe  (last byte = relay id, not 00).
//   e.g. Relay 3 ON = fc 00 00 a3 01 03 fe, OFF = fc 00 00 a3 00 03 fe.
//   No immediate frame is expected back; state is confirmed via query.
//   Query: fc 00 00 aa ff 00 fe -> batch a1/a2/a3/a4 state frames.
//   Night light: fc 00 1d 01 00 00 fe on, fc 00 1d 00 00 00 fe off,
//   fc 00 1d 02 <brightness 00-64 hex> 00 fe -- brightness only, no colour,
//   no status frame back (optimistic state in prefs).
// Press vs virtual discernment (Oct 30, 2026 test): a physical press is
// an immediate unsolicited single frame; a virtual set produces no frame,
// only a later query batch. So: frames arriving shortly after a query
// (or matching a just-sent set) are state only; all other relay frames
// are physical presses and publish BOTH state and a press event. HA
// toggle automations must trigger on the press event, not on state,
// otherwise a lights->relay sync (external light change -> relay switch)
// would re-trigger the toggle and loop.
// Transport: MQTT discovery, topics tuyacontroller/<device-slug>/...
// su on this ROM: syntax is "su 0 <command...>", NOT "su -c ...".
// The old "su -c input keyevent 224" failed with exit=1 for that reason
// alone. App UID 10096 CAN escalate silently via "su 0 ..." (proven
// Oct 30, 2026: su 10096 su 0 id -> uid=0, screen off/on via keyevents).
// /proc/vendor audio nodes are world-writable, no su needed.
// Brightness/rotation need Settings WRITE_SETTINGS (special grant, once,
// via the app UI or appops); without it, commands log and HA reverts to
// the polled actual value. Volume needs MODIFY_AUDIO_SETTINGS only.
public class ButtonService extends Service {
    private static final String TAG = "F5Daemon";
    private volatile boolean running = false;
    private volatile boolean reconnectRunning = false;
    private volatile boolean pollRunning = false;
    private PowerManager.WakeLock wakeLock;
    private PowerManager.WakeLock screenWakeLock;
    private FileInputStream serialIn;
    private FileOutputStream serialOut;
    private final Object serialWriteLock = new Object();
    private volatile long lastQueryTime = 0;
    private volatile long lastSetTime = 0;
    private volatile int lastSetRelay = -1;
    private volatile boolean lastSetState = false;
    private volatile int lastBrightness = -1;
    private volatile int lastVolume = -1;
    private volatile int lastRotation = -1;
    private volatile boolean lastScreen = true;
    private volatile String lastProcMute = "";
    private volatile int[] lastRelayState = new int[]{-1, -1, -1, -1, -1};
    private volatile int lastMotionState = -1;
    private final java.util.ArrayList<int[]> pendingPresses = new java.util.ArrayList<int[]>();
    private volatile boolean pressThreadRunning = false;
    private volatile boolean connecting = false;
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
    private void enforceDesiredRotation() {
        try {
            SharedPreferences p = getSharedPreferences("f5", MODE_PRIVATE);
            int desiredDeg = p.getInt("rotation_desired_deg", 90);
            if (desiredDeg != 0 && desiredDeg != 90 && desiredDeg != 180 && desiredDeg != 270) desiredDeg = 90;
            if (!Settings.System.canWrite(this)) { Log.w(TAG, "startup rotation check skipped: WRITE_SETTINGS not granted"); return; }
            int cur = Settings.System.getInt(getContentResolver(), Settings.System.USER_ROTATION, -1);
            int accel = Settings.System.getInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, -1);
            if (cur == desiredDeg / 90 && accel == 0) { Log.i(TAG, "startup rotation already correct: " + desiredDeg); lastRotation = desiredDeg; return; }
            Settings.System.putInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0);
            Settings.System.putInt(getContentResolver(), Settings.System.USER_ROTATION, desiredDeg / 90);
            lastRotation = desiredDeg;
            Log.i(TAG, "startup rotation enforced: was user_rotation=" + cur + " accel=" + accel + " -> " + desiredDeg);
        } catch (Exception e) { Log.w(TAG, "startup rotation check failed: " + e); }
    }
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
        // Startup rotation check (Oct 30, 2026): user_rotation does NOT
        // survive a reboot on this panel, and the Kiosk Autostart plugin
        // rotation commands are manual config that was once lost. The
        // daemon itself enforces the desired rotation on every service
        // start instead, reusing the same WRITE_SETTINGS path as the
        // MQTT /rotation/set command. Runs once per start only — never
        // in pollLoop — so a deliberate rotation change is not fought.
        // If WRITE_SETTINGS is not granted, this logs and does nothing.
        enforceDesiredRotation();
        mqtt.setCommandListener((topic, payload) -> {
            try { handleCommand(topic, payload); }
            catch (Exception e) { Log.w(TAG, "mqtt command failed: " + e, e); }
        });
        if (!running) { running = true; new Thread(this::readLoop).start(); }
        if (!reconnectRunning) { reconnectRunning = true; new Thread(this::reconnectLoop).start(); }
        if (!pollRunning) { pollRunning = true; new Thread(this::pollLoop).start(); }
        mqtt.disconnect();
        new Thread(() -> { try { connectNow(); } catch (Exception e) { Log.w(TAG, "mqtt connect on start failed, reconnect loop will retry: " + e); } }).start();
        return START_STICKY;
    }
    private void connectNow() throws Exception {
        if (connecting) return;
        connecting = true;
        try { connectNowInner(); } finally { connecting = false; }
    }
    private void connectNowInner() throws Exception {
        SharedPreferences p = getSharedPreferences("f5", MODE_PRIVATE);
        String host = p.getString("host", "");
        if (host.isEmpty()) return;
        int port = 1883;
        try { port = Integer.parseInt(p.getString("port", "1883")); } catch (Exception ignored) {}
        mqtt.connect(host, port, p.getString("user", ""), PasswordStore.getPassword(p), deviceName(this, p));
        mqtt.publishOptionStates(wakelockEnabled(p), wakescreenEnabled(p));
        // Sync relay states immediately, and publish current device states
        // (brightness/volume/screen/rotation/proc/nightlight) so HA does
        // not wait for a physical event or a poll tick.
        sendQuery();
        publishDeviceStates(true);
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
    // ---- su / privileged helpers ----
    // Correct syntax on this ROM: su 0 <argv...>, no -c flag.
    private String runSu(String... argv) {
        try {
            String[] cmd = new String[argv.length + 2];
            cmd[0] = "su"; cmd[1] = "0";
            System.arraycopy(argv, 0, cmd, 2, argv.length);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            java.lang.Process proc = pb.start();
            StringBuilder out = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            String line; while ((line = r.readLine()) != null) { if (out.length() > 0) out.append(" | "); out.append(line); }
            int rc = proc.waitFor();
            Log.i(TAG, "su " + String.join(" ", argv) + " exit=" + rc + " output=" + out);
            return out.toString();
        } catch (Exception e) { Log.w(TAG, "su failed: " + e); return null; }
    }
    private void wakeScreen() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm.isInteractive()) return;
            // Try a screen wake lock first (no root), then su keyevent.
            try {
                if (screenWakeLock == null) {
                    screenWakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "F5Daemon::screen");
                    screenWakeLock.setReferenceCounted(false);
                }
                if (!screenWakeLock.isHeld()) screenWakeLock.acquire(10000);
            } catch (Exception e) { Log.w(TAG, "screen wake lock failed: " + e); }
            if (!pm.isInteractive()) runSu("input", "keyevent", "224");
            Log.i(TAG, "wake screen, interactive=" + pm.isInteractive());
        } catch (Exception e) { Log.w(TAG, "wake screen failed: " + e); }
    }
    private void setScreen(boolean on) {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (on) { wakeScreen(); }
        else {
            if (screenWakeLock != null && screenWakeLock.isHeld()) screenWakeLock.release();
            runSu("input", "keyevent", "26");
        }
        try { Thread.sleep(600); } catch (InterruptedException ignored) {}
        try { boolean scr = pm.isInteractive(); lastScreen = scr; mqtt.screen(scr); } catch (Exception e) { Log.w(TAG, "screen publish failed: " + e); }
    }
    // ---- serial ----
    private void writeSerial(byte[] frame) {
        synchronized (serialWriteLock) {
            try {
                if (serialOut == null) { serialOut = new FileOutputStream("/dev/ttyAS2"); try { Runtime.getRuntime().exec(new String[]{"sh", "-c", "stty -F /dev/ttyAS2 115200 raw"}).waitFor(); } catch (Exception ignored) {} }
                serialOut.write(frame); serialOut.flush();
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                StringBuilder sb = new StringBuilder();
                for (byte b : frame) sb.append(String.format("%02x ", b));
                Log.i(TAG, "serial write: " + sb.toString().trim());
            } catch (Exception e) { Log.w(TAG, "serial write failed: " + e); }
        }
    }
    private void sendQuery() {
        lastQueryTime = System.currentTimeMillis();
        writeSerial(new byte[]{(byte) 0xfc, 0x00, 0x00, (byte) 0xaa, (byte) 0xff, 0x00, (byte) 0xfe});
    }
    private void setRelay(int relay, boolean on) {
        lastSetTime = System.currentTimeMillis(); lastSetRelay = relay; lastSetState = on;
        byte code = (byte) (0xa0 + relay);
        // Real switch frame for relays 1-3: last byte = relay id.
        // Relay 4 has no physical button and its inbound last byte is 0,
        // so keep the test frame for it (it is not exposed as a switch).
        byte last = (relay >= 1 && relay <= 3) ? (byte) relay : (byte) 0x00;
        writeSerial(new byte[]{(byte) 0xfc, 0x00, 0x00, code, (byte) (on ? 0x01 : 0x00), last, (byte) 0xfe});
        // Optimistic state publish; the following query batch will
        // confirm the same state (suppressed as a press, see handleFrame).
        if (relay >= 1 && relay <= 4) lastRelayState[relay] = on ? 1 : 0;
        try { mqtt.relay(relay, on); } catch (Exception e) { Log.w(TAG, "relay publish failed: " + e); }
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        sendQuery();
    }
    private void setNightLight(boolean on, Integer brightness) {
        SharedPreferences p = getSharedPreferences("f5", MODE_PRIVATE);
        int bright = brightness != null ? brightness : p.getInt("nightlight_brightness", 50);
        if (brightness != null) { SharedPreferences.Editor ed = p.edit(); ed.putInt("nightlight_brightness", bright); ed.apply(); }
        if (on) {
            writeSerial(new byte[]{(byte) 0xfc, 0x00, 0x1d, 0x01, 0x00, 0x00, (byte) 0xfe});
            writeSerial(new byte[]{(byte) 0xfc, 0x00, 0x1d, 0x02, (byte) bright, 0x00, (byte) 0xfe});
        } else {
            writeSerial(new byte[]{(byte) 0xfc, 0x00, 0x1d, 0x00, 0x00, 0x00, (byte) 0xfe});
        }
        SharedPreferences.Editor ed = p.edit(); ed.putBoolean("nightlight_on", on); ed.putInt("nightlight_brightness", bright); ed.apply();
        try { mqtt.nightLight(on, bright); } catch (Exception e) { Log.w(TAG, "nightlight publish failed: " + e); }
    }
    private void readLoop() {
        try { Runtime.getRuntime().exec(new String[]{"sh", "-c", "stty -F /dev/ttyAS2 115200 raw"}).waitFor(); } catch (Exception e) { Log.w(TAG, "stty failed", e); }
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
            if (f[2] == 0x1c) { lastMotionState = 1; mqtt.motion(true); }
            else if (f[2] == 0x0c) { lastMotionState = 0; mqtt.motion(false); }
            else if (f[2] == 0x00 && f[3] == 0x00 && f[4] == 0x01) {
                if (wakescreenEnabled(getSharedPreferences("f5", MODE_PRIVATE))) wakeScreen();
                mqtt.buttonPress(f[5] & 0xff);
            } else if (f[2] == 0x00 && (f[3] & 0xf0) == 0xa0) {
                if (wakescreenEnabled(getSharedPreferences("f5", MODE_PRIVATE))) wakeScreen();
                // Relay 4 is a query-only channel: its frame is
                // fc 00 00 a4 00 00 fe, i.e. f[5] is 0, NOT 4. The old
                // code read relay = f[5], so it never saw relay 4 and
                // the query-batch marker below never fired -- a manual
                // query (adb cat) then published false press events.
                // Detect a4 via f[3] and add a marker entry (relay=4).
                if ((f[3] & 0xff) == 0xa4) {
                    synchronized (pendingPresses) {
                        pendingPresses.add(new int[]{4, 0});
                        if (!pressThreadRunning) {
                            pressThreadRunning = true;
                            new Thread(() -> {
                                try { Thread.sleep(350); } catch (InterruptedException ignored) {}
                                synchronized (pendingPresses) { pendingPresses.clear(); pressThreadRunning = false; }
                            }).start();
                        }
                    }
                    return;
                }
                int relay = f[5] & 0xff;
                boolean on = f[4] == 0x01;
                if (relay >= 1 && relay <= 4) lastRelayState[relay] = on ? 1 : 0;
                mqtt.relay(relay, on);
                boolean queryBatch = System.currentTimeMillis() - lastQueryTime < 5000;
                boolean justSet = System.currentTimeMillis() - lastSetTime < 5000 && relay == lastSetRelay && on == lastSetState;
                if (queryBatch || justSet) {
                    // Daemon-initiated query/set: state only, never a press.
                    return;
                }
                // Relay 4 is a query-only channel (no physical button);
                // it is added to the pending batch below only as a marker
                // that the batch was a query response, never published
                // as a press itself (see publish filter).
                // Otherwise, possibly a physical press -- but a query batch
                // sent by another writer (adb, etc.) looks identical, so
                // delay the press event 350 ms and batch it: if a relay-4
                // frame arrives in that window, the whole batch was a query
                // response and no press is published; otherwise every frame
                // in the window was a real press and is published in order.
                synchronized (pendingPresses) {
                    pendingPresses.add(new int[]{relay, on ? 1 : 0});
                    if (!pressThreadRunning) {
                        pressThreadRunning = true;
                        new Thread(() -> {
                            try { Thread.sleep(350); } catch (InterruptedException ignored) {}
                            synchronized (pendingPresses) {
                                boolean hasRelay4 = false;
                                for (int[] pr : pendingPresses) if (pr[0] == 4) hasRelay4 = true;
                                if (!hasRelay4) {
                                    for (int[] pr : pendingPresses) {
                                        if (pr[0] < 1 || pr[0] > 3) continue;
                                        try { mqtt.relayPress(pr[0]); Log.i(TAG, "physical relay press: relay=" + pr[0] + " on=" + (pr[1] == 1)); } catch (Exception e) { Log.w(TAG, "relay press publish failed: " + e); }
                                    }
                                }
                                pendingPresses.clear();
                                pressThreadRunning = false;
                            }
                        }).start();
                    }
                }
            }
        } catch (Exception e) { Log.w(TAG, "mqtt publish failed", e); }
    }
    // ---- device state: get / set / poll ----
    private int brightnessPct() {
        try {
            int v = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, -1);
            if (v < 0) return -1;
            return (int) Math.round(v * 100.0 / 255.0);
        } catch (Exception e) { return -1; }
    }
    private int volumePct() { return volumePctFor(AudioManager.STREAM_MUSIC); }
    private int volumePctFor(int stream) {
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(stream);
            if (max <= 0) return -1;
            return (int) Math.round(am.getStreamVolume(stream) * 100.0 / max);
        } catch (Exception e) { return -1; }
    }
    private void setVolumePct(int stream, int pct) {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        int max = am.getStreamMaxVolume(stream);
        am.setStreamVolume(stream, pct * max / 100, 0);
    }
    private int rotationDeg() {
        try {
            int v = Settings.System.getInt(getContentResolver(), Settings.System.USER_ROTATION, -1);
            if (v < 0) return -1;
            return v * 90;
        } catch (Exception e) { return -1; }
    }
    private String readProc(String path) {
        try {
            BufferedReader r = new BufferedReader(new FileReader(path));
            String s = r.readLine(); r.close();
            return s == null ? "" : s.trim();
        } catch (Exception e) { return ""; }
    }
    private boolean writeProc(String path, String value) {
        try {
            FileOutputStream o = new FileOutputStream(path);
            o.write(value.getBytes("UTF-8")); o.flush(); o.close();
            return value.equals(readProc(path));
        } catch (Exception e) { Log.w(TAG, "proc write failed " + path + ": " + e); return false; }
    }
    private void publishDeviceStates(boolean force) {
        try {
            int b = brightnessPct();
            if (b >= 0 && (force || b != lastBrightness)) { lastBrightness = b; mqtt.brightness(b); }
            int v = volumePct();
            SharedPreferences p = getSharedPreferences("f5", MODE_PRIVATE);
            if (v >= 0 && (force || v != lastVolume)) { lastVolume = v; mqtt.volume(v, false); }
            // Stream volumes are published on force (above) and via their own command handlers; poll change-checks for them are covered by the force re-publish in pollLoop.
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            boolean scr = pm.isInteractive();
            if (force || scr != lastScreen) { lastScreen = scr; mqtt.screen(scr); }
            int r = rotationDeg();
            if (r >= 0 && (force || r != lastRotation)) { lastRotation = r; mqtt.rotation(r); }
            String pmv = readProc("/proc/vendor/mute_manual");
            if (force || !pmv.equals(lastProcMute)) { lastProcMute = pmv; mqtt.procState("mute", pmv); }
            if (force) {
                boolean nlOn = p.getBoolean("nightlight_on", false);
                int nlB = p.getInt("nightlight_brightness", 50);
                mqtt.nightLight(nlOn, nlB);
                // QoS 0 state messages can be lost; re-publishing the
                // tracked states every poll heals a stale HA entity
                // within one poll tick instead of waiting for a change.
                for (int rr = 1; rr <= 3; rr++) if (lastRelayState[rr] >= 0) mqtt.relay(rr, lastRelayState[rr] == 1);
                if (lastMotionState >= 0) mqtt.motion(lastMotionState == 1);
                mqtt.publishOptionStates(wakelockEnabled(p), wakescreenEnabled(p));
                mqtt.volumeStream("system", volumePctFor(AudioManager.STREAM_SYSTEM));
                mqtt.volumeStream("notification", volumePctFor(AudioManager.STREAM_NOTIFICATION));
                mqtt.volumeStream("alarm", volumePctFor(AudioManager.STREAM_ALARM));
                mqtt.volumeStream("ring", volumePctFor(AudioManager.STREAM_RING));
                mqtt.volumeStream("voice", volumePctFor(AudioManager.STREAM_VOICE_CALL));
            }
        } catch (Exception e) { Log.w(TAG, "device state publish failed: " + e); }
    }
    private void pollLoop() {
        while (pollRunning) {
            try { if (mqtt.connected()) publishDeviceStates(true); } catch (Exception e) { Log.w(TAG, "poll failed: " + e); }
            try { Thread.sleep(5000); } catch (InterruptedException ignored) { break; }
        }
    }
    // ---- MQTT commands ----
    private void handleCommand(String topic, String payload) {
        if (!topic.endsWith("/set") && !topic.endsWith("/press")) return;
        String pl = payload == null ? "" : payload.trim();
        SharedPreferences p = getSharedPreferences("f5", MODE_PRIVATE);
        Log.i(TAG, "mqtt command " + topic + " -> " + pl);
        if (topic.endsWith("/wakelock/set")) {
            boolean on = "ON".equalsIgnoreCase(pl);
            SharedPreferences.Editor ed = p.edit(); ed.putBoolean("wakelock_enabled", on); ed.apply(); applyWakelock();
            try { mqtt.publishOptionStates(wakelockEnabled(p), wakescreenEnabled(p)); } catch (Exception e) { Log.w(TAG, "option publish failed: " + e); }
        } else if (topic.endsWith("/wakescreen/set")) {
            boolean on = "ON".equalsIgnoreCase(pl);
            SharedPreferences.Editor ed = p.edit(); ed.putBoolean("wakescreen_enabled", on); ed.apply();
            try { mqtt.publishOptionStates(wakelockEnabled(p), wakescreenEnabled(p)); } catch (Exception e) { Log.w(TAG, "option publish failed: " + e); }
        } else if (topic.matches(".*/relay/[0-9]+/set")) {
            String[] parts = topic.split("/");
            int relay = Integer.parseInt(parts[parts.length - 2]);
            if (relay >= 1 && relay <= 4) setRelay(relay, "ON".equalsIgnoreCase(pl));
        } else if (topic.endsWith("/nightlight/set")) {
            setNightLight("ON".equalsIgnoreCase(pl), null);
        } else if (topic.endsWith("/nightlight/brightness/set")) {
            try { int b = Integer.parseInt(pl); if (b < 0) b = 0; if (b > 100) b = 100; setNightLight(true, b); } catch (Exception ignored) {}
        } else if (topic.endsWith("/screen/set")) {
            setScreen("ON".equalsIgnoreCase(pl));
        } else if (topic.endsWith("/brightness/set")) {
            try {
                int pct = Integer.parseInt(pl); if (pct < 0) pct = 0; if (pct > 100) pct = 100;
                if (!Settings.System.canWrite(this)) { Log.w(TAG, "brightness set refused: WRITE_SETTINGS not granted"); publishDeviceStates(true); return; }
                Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, 0);
                Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, pct * 255 / 100);
                lastBrightness = pct; mqtt.brightness(pct);
            } catch (Exception e) { Log.w(TAG, "brightness set failed: " + e); }
        } else if (topic.endsWith("/volume/set")) {
            try {
                int pct = Integer.parseInt(pl); if (pct < 0) pct = 0; if (pct > 100) pct = 100;
                AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                am.setStreamVolume(AudioManager.STREAM_MUSIC, pct * max / 100, 0);
                lastVolume = pct; mqtt.volume(pct, false);
            } catch (Exception e) { Log.w(TAG, "volume set failed: " + e); }
        } else if (topic.endsWith("/volume/system/set")) {
            try { int pct = Integer.parseInt(pl); setVolumePct(AudioManager.STREAM_SYSTEM, pct); publishDeviceStates(true); } catch (Exception e) { Log.w(TAG, "system volume set failed: " + e); }
        } else if (topic.endsWith("/volume/notification/set")) {
            try { int pct = Integer.parseInt(pl); setVolumePct(AudioManager.STREAM_NOTIFICATION, pct); publishDeviceStates(true); } catch (Exception e) { Log.w(TAG, "notification volume set failed: " + e); }
        } else if (topic.endsWith("/volume/alarm/set")) {
            try { int pct = Integer.parseInt(pl); setVolumePct(AudioManager.STREAM_ALARM, pct); publishDeviceStates(true); } catch (Exception e) { Log.w(TAG, "alarm volume set failed: " + e); }
        } else if (topic.endsWith("/volume/ring/set")) {
            try { int pct = Integer.parseInt(pl); setVolumePct(AudioManager.STREAM_RING, pct); publishDeviceStates(true); } catch (Exception e) { Log.w(TAG, "ring volume set failed: " + e); }
        } else if (topic.endsWith("/volume/voice/set")) {
            try { int pct = Integer.parseInt(pl); setVolumePct(AudioManager.STREAM_VOICE_CALL, pct); publishDeviceStates(true); } catch (Exception e) { Log.w(TAG, "voice volume set failed: " + e); }
        } else if (topic.endsWith("/rotation/set")) {
            try {
                int deg = Integer.parseInt(pl);
                if (deg != 0 && deg != 90 && deg != 180 && deg != 270) return;
                if (!Settings.System.canWrite(this)) { Log.w(TAG, "rotation set refused: WRITE_SETTINGS not granted"); publishDeviceStates(true); return; }
                Settings.System.putInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0);
                Settings.System.putInt(getContentResolver(), Settings.System.USER_ROTATION, deg / 90);
                // Persist the desired rotation so the startup check
                // enforces THIS value after a reboot, not a hardcoded 90.
                p.edit().putInt("rotation_desired_deg", deg).apply();
                lastRotation = deg; mqtt.rotation(deg);
            } catch (Exception e) { Log.w(TAG, "rotation set failed: " + e); }
        } else if (topic.endsWith("/reboot/press")) {
            Log.i(TAG, "reboot button pressed via HA, running su reboot");
            runSu("reboot");
        } else if (topic.endsWith("/proc/mute/set")) {
            if (writeProc("/proc/vendor/mute_manual", pl)) mqtt.procState("mute", pl); else publishDeviceStates(true);
        }
    }
    @Override public void onDestroy() {
        running = false; reconnectRunning = false; pollRunning = false;
        try { if (serialIn != null) serialIn.close(); } catch (Exception ignored) {}
        try { if (serialOut != null) serialOut.close(); } catch (Exception ignored) {}
        mqtt.disconnect();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (screenWakeLock != null && screenWakeLock.isHeld()) screenWakeLock.release();
        super.onDestroy();
    }
}
