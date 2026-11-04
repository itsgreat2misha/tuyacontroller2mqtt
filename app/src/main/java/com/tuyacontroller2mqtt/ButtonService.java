package com.tuyacontroller2mqtt;
import android.app.*;
import android.content.*;
import android.media.AudioManager;
import android.os.*;
import android.provider.Settings;
import android.util.Log;
import java.io.*;
// Tuya Android controller daemon. The panel MCU talks to the Android
// SoC over an internal serial bus; the active DeviceProfile carries the
// model specifics (serial device, baud, button/relay counts). Frame
// formats below are the F5-3 profile's protocol; see PROTOCOL.md for
// the full table.
//   Motion: fc 00 1c 00 00 00 fe = motion, fc 00 0c 00 00 00 fe = clear
//     (the sensor is exposed as Motion, device_class motion: static
//     presence is not proven, see PROTOCOL.md)
//   Momentary: fc 00 00 00 01 <id 04..08> fe -> Buttons 4..8
//     (the id IS the button number)
//   Toggle: fc 00 00 a3/a2/a1 <state 01/00> <code 03/02/01> fe
//     -> Relays 3/2/1 (the code IS the relay number)
//   Outbound relay set: fc 00 00 a<N> <01 on / 00 off> <N> fe
//     (the last byte is the relay id; the vendor test-app frames with
//     a 00 last byte only change the MCU's reported state, not the
//     relay or its LED). No immediate frame comes back; state is
//     confirmed via query: fc 00 00 aa ff 00 fe -> a1/a2/a3/a4 batch.
//   Night light: fc 00 1d 01/00 on/off, fc 00 1d 02 <brightness> for
//     brightness 0-100; no status frame back (optimistic state).
// Press vs virtual discernment: a physical press is an immediate
// unsolicited single frame; a virtual (HA) set produces no frame, only
// a later query batch. Relay frames are therefore held as candidates
// for 80 ms; a query batch is a single burst terminated by the relay-4
// frame, which discards any candidates it produced, while a frame
// still unclaimed after 80 ms was a real press. HA toggle automations
// must trigger on the press event, not on state, or a lights->relay
// sync would re-trigger the toggle and loop.
// Per-relay mode: each relay is independently in "relay" mode (HA
// switch exposed, HA may set it) or "momentary" mode (HA switch
// retracted, only the press event remains; a press that turns the
// relay on is snapped back off by the daemon). Mode is an HA select
// per relay, persisted as relay_<N>_momentary. Rationale: on panels
// whose relays carry no loads the buttons are purely virtual, and
// every remote relay flip activates the MCU's motion state invisibly
// (backlight on, no motion frame), which makes panel motion sensing
// miss real walk-ins; momentary mode leaves only physical presses.
// A confirmed physical press also publishes motion Detected (a press
// can land before the sensor's own idle->active edge); the panel's
// own clear frame ends the episode.
// Transport: MQTT discovery, topics tuyacontroller/<device-slug>/...
// Privilege: screen on/off and reboot escalate via su (on the F5-3
// ROM the syntax is "su 0 <command...>", not "su -c ..."); brightness
// and rotation need the Write Settings grant; volume needs only
// MODIFY_AUDIO_SETTINGS; the /proc/vendor audio nodes are
// world-writable on the F5-3 ROM.
public class ButtonService extends Service {
    private static final String TAG = Constants.TAG;
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
    private final DeviceProfile profile = DeviceProfile.active();
    // Frame-loss improvements : serial reading and
    // MQTT publishing are decoupled. readLoop only parses frames and
    // updates tracked state; every MQTT publish it would have done is
    // enqueued as a PublishAction and executed by publisherLoop, the
    // single thread that publishes frame-derived messages. A slow
    // broker, a reconnect, or a discovery publish can therefore stall
    // publishing for seconds without ever stalling serial reading —
    // the queue absorbs the stall (capacity 1000, drop oldest with a
    // log line if it ever fills, which should never happen at
    // event-driven rates). Press batching still uses ARRIVAL time
    // (see handleFrameArrival), so a publishing backlog cannot merge
    // or split a query batch. pollLoop, connectNow, and handleCommand
    // still publish directly (they are not on the read thread); their
    // lock contention with publisherLoop only delays publishes, never
    // frame parsing. Queue counters are in metricsSnapshot.
    private static final class PublishAction {
        static final int MOTION = 1, BUTTON = 2, RELAY = 3, RELAY_PRESS = 4;
        final int type; final int a; final boolean b;
        PublishAction(int type, int a, boolean b) { this.type = type; this.a = a; this.b = b; }
    }
    private final java.util.concurrent.ArrayBlockingQueue<PublishAction> publishQueue =
        new java.util.concurrent.ArrayBlockingQueue<PublishAction>(1000);
    private volatile boolean publisherRunning = false;
    private volatile long queueDroppedCount = 0;
    private void enqueuePublish(PublishAction act) {
        if (!publishQueue.offer(act)) {
            // Queue full: drop the oldest frame publish, never the
            // newest — a stale state is healed by the 5 s poll anyway.
            publishQueue.poll();
            queueDroppedCount++;
            Log.w(TAG, "publish queue full, dropped oldest action");
            diag.event("publish queue full, dropped oldest action");
            publishQueue.offer(act);
        }
    }
    private void publisherLoop() {
        while (publisherRunning) {
            try {
                PublishAction act = publishQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (act == null) continue;
                try {
                    if (act.type == PublishAction.MOTION) mqtt.motion(act.b);
                    else if (act.type == PublishAction.BUTTON) mqtt.buttonPress(act.a);
                    else if (act.type == PublishAction.RELAY) mqtt.relay(act.a, act.b);
                    else if (act.type == PublishAction.RELAY_PRESS) mqtt.relayPress(act.a);
                } catch (Exception e) { Log.w(TAG, "queued publish failed: " + e); diag.event("queued publish failed: " + e); }
            } catch (InterruptedException ignored) { break; }
        }
    }
    // Serial health probe : a query every 15 min
    // (pollLoop ticks) proves the MCU -> serial -> daemon path is
    // alive. Any frame arriving within 2 s of the probe counts as a
    // response (it proves the path, whatever its type). On a miss,
    // pollLoop closes serialIn, which makes readLoop throw and reopen
    // with backoff (existing mechanism), and retries the probe once
    // on the next poll tick. probeRequested is set by the OFF-without-ON
    // warning (below) for an immediate, throttled probe.
    private volatile boolean probePending = false;
    private volatile long probeQueryTimeMs = 0;
    private volatile long probeStartFrames = 0;
    private volatile boolean probeRetryPending = false;
    private volatile boolean probeRetryUsed = false;
    private volatile long lastProbeTimeMs = 0;
    private volatile boolean probeRequested = false;
    private volatile boolean ttyScanDone = false;
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
    // Global Debug mode : one toggle, HA switch "Debug" or app checkbox, default
    // OFF, no auto-off. When on, DiagLogger writes rotating files
    // (serial/metrics/events, 30 MB total hard cap, see DiagLogger),
    // so it is safe to leave on indefinitely to catch intermittent
    // faults like the Nov 1 morning motion miss. Migration: a panel
    // whose old serial_debug_enabled pref was on, with no debug_enabled
    // pref yet, starts with Debug on (applied once in onStartCommand).
    public static boolean debugEnabled(SharedPreferences p) {
        if (p.contains("debug_enabled")) return p.getBoolean("debug_enabled", false);
        return p.getBoolean("serial_debug_enabled", false);
    }
    // Per-relay mode : default is
    // relay mode for every relay, so an upgraded daemon behaves
    // exactly as before until a mode is flipped in HA.
    public static boolean relayMomentary(SharedPreferences p, int relay) { return p.getBoolean("relay_" + relay + "_momentary", false); }
    private void syncRelayModesToMqtt(SharedPreferences p) {
        for (int i = 1; i <= profile.relayCount; i++) mqtt.setRelayMomentary(i, relayMomentary(p, i));
    }
    private void setRelayMode(int relay, boolean momentary) {
        SharedPreferences p = getSharedPreferences(Constants.PREFS, MODE_PRIVATE);
        p.edit().putBoolean("relay_" + relay + "_momentary", momentary).apply();
        mqtt.setRelayMomentary(relay, momentary);
        try { mqtt.publishRelaySwitchDiscovery(relay); mqtt.publishRelayMode(relay); } catch (Exception e) { Log.w(TAG, "relay mode publish failed: " + e); }
        Log.i(TAG, "relay " + relay + " mode -> " + (momentary ? "momentary" : "relay"));
        diag.event("relay " + relay + " mode -> " + (momentary ? "momentary" : "relay"));
        if (momentary) {
            // Momentary steady state is OFF: if the relay is on, snap
            // it off now so the button LED does not stay lit.
            if (lastRelayState[relay] == 1) setRelay(relay, false);
        } else {
            // Refresh the restored switch's state right away instead
            // of waiting for the next poll tick.
            sendQuery();
        }
    }
    // Created in onStartCommand with getFilesDir() (not available at
    // construction time); all diag-using threads start after that.
    private DiagLogger diag;
    private volatile boolean debug = false;
    private volatile long frameCount = 0;
    private volatile long droppedFrameCount = 0;
    private volatile long serialErrorCount = 0;
    private volatile long lastFrameTimeMs = 0;
    private volatile long lastMotionFrameTimeMs = 0;
    private volatile String lastFrameHex = "";
    private void setDebug(boolean on) {
        SharedPreferences p = getSharedPreferences(Constants.PREFS, MODE_PRIVATE);
        SharedPreferences.Editor ed = p.edit(); ed.putBoolean("debug_enabled", on); ed.apply();
        debug = on; diag.setEnabled(on);
        Log.i(TAG, "debug " + (on ? "ON (rotating logs, 30 MB cap)" : "OFF"));
        diag.event("debug " + (on ? "ON" : "OFF"));
        try { mqtt.publishOptionStates(wakelockEnabled(p), wakescreenEnabled(p), on); } catch (Exception e) { Log.w(TAG, "option publish failed: " + e); }
    }
    private static String hex(byte[] f, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) { if (i > 0) sb.append(" "); sb.append(String.format("%02x", f[i])); }
        return sb.toString();
    }
    private static String decodeFrame(byte[] f) {
        if (f[2] == 0x1c) return "motion ON";
        if (f[2] == 0x0c) return "motion OFF";
        if (f[2] == 0x00 && f[3] == 0x00 && f[4] == 0x01) return "button press id=" + (f[5] & 0xff);
        // Query frame fc 00 00 aa ff 00 fe must be checked BEFORE the
        // relay branch (aa & f0 == a0 would otherwise label it
        // "relay 0 OFF").
        if (f[2] == 0x00 && f[3] == (byte) 0xaa) return "query";
        if (f[2] == 0x00 && (f[3] & 0xf0) == 0xa0) return "relay " + (f[5] & 0xff) + " " + (f[4] == 0x01 ? "ON" : "OFF") + " (f3=" + String.format("%02x", f[3]) + ")";
        return "unknown f2=" + String.format("%02x", f[2]) + " f3=" + String.format("%02x", f[3]);
    }
    private String readFirstLine(String path) {
        try {
            BufferedReader r = new BufferedReader(new FileReader(path));
            String s = r.readLine(); r.close();
            return s == null ? "" : s.trim();
        } catch (Exception e) { return ""; }
    }
    private String meminfo(String key) {
        try {
            BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"));
            String line; while ((line = r.readLine()) != null) {
                if (line.startsWith(key)) { r.close(); return line.replaceAll("[^0-9]", ""); }
            }
            r.close();
        } catch (Exception ignored) {}
        return "";
    }
    private String metricsSnapshot(String why) {
        long now = System.currentTimeMillis();
        long motionAge = lastMotionFrameTimeMs > 0 ? (now - lastMotionFrameTimeMs) / 1000 : -1;
        long frameAge = lastFrameTimeMs > 0 ? (now - lastFrameTimeMs) / 1000 : -1;
        String load = readFirstLine("/proc/loadavg");
        String uptime = readFirstLine("/proc/uptime");
        String t0 = readFirstLine("/sys/class/thermal/thermal_zone0/temp");
        String t1 = readFirstLine("/sys/class/thermal/thermal_zone1/temp");
        boolean scr = false;
        try { scr = ((PowerManager) getSystemService(POWER_SERVICE)).isInteractive(); } catch (Exception ignored) {}
        return "why=" + why + " load=" + load + " memTotalKB=" + meminfo("MemTotal:") + " memFreeKB=" + meminfo("MemFree:")
            + " memAvailKB=" + meminfo("MemAvailable:") + " uptime_s=" + (uptime.isEmpty() ? "" : uptime.split(" ")[0])
            + " temp0=" + t0 + " temp1=" + t1 + " screen=" + scr + " mqtt=" + mqtt.connected()
            + " motion=" + lastMotionState + " motionAge_s=" + motionAge + " lastFrameAge_s=" + frameAge
            + " frames=" + frameCount + " dropped=" + droppedFrameCount + " serialErrors=" + serialErrorCount
            + " queueDropped=" + queueDroppedCount + " queueSize=" + publishQueue.size()
            + " probePending=" + probePending
            + " lastFrame=" + lastFrameHex;
    }
    private void enforceDesiredRotation() {
        try {
            SharedPreferences p = getSharedPreferences(Constants.PREFS, MODE_PRIVATE);
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
        SharedPreferences p = getSharedPreferences(Constants.PREFS, MODE_PRIVATE);
        if (wakelockEnabled(p)) {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TuyaController::serial");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) wakeLock.acquire();
        } else if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("tuyacontroller2mqtt", "Tuya Controller Daemon", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
        Intent ui = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, ui, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, "tuyacontroller2mqtt")
            .setContentTitle("Tuya controller daemon").setContentText("Listening on " + profile.serialPath)
            .setContentIntent(pi)
            .setSmallIcon(android.R.drawable.ic_dialog_info).build();
        startForeground(1, n);
        applyWakelock();
        if (diag == null) diag = new DiagLogger(getFilesDir());
        // One-time migration from the old Serial Debug pref: if the
        // new debug_enabled pref does not exist yet, persist the old
        // value under the new key so the toggle state survives.
        SharedPreferences prefs = getSharedPreferences(Constants.PREFS, MODE_PRIVATE);
        if (!prefs.contains("debug_enabled") && prefs.contains("serial_debug_enabled")) {
            prefs.edit().putBoolean("debug_enabled", prefs.getBoolean("serial_debug_enabled", false)).apply();
        }
        debug = debugEnabled(prefs); diag.setEnabled(debug);
        diag.event("service started debug=" + debug); diag.metrics(metricsSnapshot("service-start"));
        // Startup rotation check : user_rotation does NOT
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
        if (!publisherRunning) { publisherRunning = true; new Thread(this::publisherLoop).start(); }
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
        SharedPreferences p = getSharedPreferences(Constants.PREFS, MODE_PRIVATE);
        String host = p.getString("host", "");
        if (host.isEmpty()) return;
        boolean tls = p.getBoolean("tls_enabled", false);
        int port = tls ? 8883 : 1883;
        try { port = Integer.parseInt(p.getString("port", String.valueOf(port))); } catch (Exception ignored) {}
        // Relay modes must be on the publisher BEFORE connect():
        // connect publishes discovery, and momentary relays' switch
        // configs depend on them.
        syncRelayModesToMqtt(p);
        mqtt.connect(host, port, p.getString("user", ""), PasswordStore.getPassword(p), deviceName(this, p), tls);
        diag.event("mqtt connected to " + host + ":" + port + (tls ? " (TLS)" : "")); diag.metrics(metricsSnapshot("mqtt-connect"));
        mqtt.publishOptionStates(wakelockEnabled(p), wakescreenEnabled(p), debugEnabled(p));
        mqtt.publishRelayModeStates();
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
            } catch (Exception e) { Log.w(TAG, "mqtt connect failed, will retry: " + e); diag.event("mqtt connect failed, will retry: " + e); }
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
                    screenWakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "TuyaController::screen");
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
                if (serialOut == null) { serialOut = new FileOutputStream(profile.serialPath); try { Runtime.getRuntime().exec(new String[]{"sh", "-c", "stty -F " + profile.serialPath + " " + profile.baud + " raw"}).waitFor(); } catch (Exception ignored) {} }
                serialOut.write(frame); serialOut.flush();
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                String outHex = hex(frame, frame.length);
                Log.i(TAG, "serial write: " + outHex);
                diag.serial("OUT " + outHex + " " + decodeFrame(frame));
            } catch (Exception e) {
                // Drop the broken stream so the next write reopens
                // the serial device instead of failing forever on a dead handle.
                Log.w(TAG, "serial write failed: " + e);
                try { if (serialOut != null) serialOut.close(); } catch (Exception ignored) {}
                serialOut = null;
            }
        }
    }
    private void sendQuery() {
        lastQueryTime = System.currentTimeMillis();
        writeSerial(new byte[]{(byte) 0xfc, 0x00, 0x00, (byte) 0xaa, (byte) 0xff, 0x00, (byte) 0xfe});
    }
    private void setRelay(int relay, boolean on) {
        lastSetTime = System.currentTimeMillis(); lastSetRelay = relay; lastSetState = on;
        byte code = (byte) (0xa0 + relay);
        // Real switch frame for the exposed relays: last byte = relay id.
        // Relay 4 has no physical button and its inbound last byte is 0,
        // so keep the test frame for it (it is not exposed as a switch).
        byte last = (relay >= 1 && relay <= profile.relayCount) ? (byte) relay : (byte) 0x00;
        writeSerial(new byte[]{(byte) 0xfc, 0x00, 0x00, code, (byte) (on ? 0x01 : 0x00), last, (byte) 0xfe});
        // Optimistic state publish; the following query batch will
        // confirm the same state (suppressed as a press, see handleFrameArrival).
        if (relay >= 1 && relay <= 4) lastRelayState[relay] = on ? 1 : 0;
        try { mqtt.relay(relay, on); } catch (Exception e) { Log.w(TAG, "relay publish failed: " + e); }
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        sendQuery();
    }
    private void setNightLight(boolean on, Integer brightness) {
        SharedPreferences p = getSharedPreferences(Constants.PREFS, MODE_PRIVATE);
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
    // Frame-loss audit  -- three conditions found, all
    // fixed here:
    // 1. PERMANENT LOSS (worst): the old readLoop caught a serial
    //    exception / EOF, logged it, and the thread simply ended.
    //    The service stayed alive, MQTT stayed connected, and pollLoop
    //    kept republishing the stale lastMotionState every 5 s, so HA
    //    showed a live but frozen motion entity -- exactly the Nov 1
    //    morning symptom (buttons lit locally, HA motion stuck
    //    cleared, screensaver stuck). Now the loop reopens the serial device
    //    with backoff (1 s doubling to 30 s, reset on a good frame)
    //    and logs every reopen to events.log, so a dead reader is
    //    visible in the diagnostics instead of silent.
    // 2. ONE-FRAME LOSS ON CORRUPTION: the old parser stored an
    //    unexpected 0xfc mid-frame as data, so the corrupted frame
    //    AND the good frame after it were both lost before resync.
    //    Now a 0xfc at pos > 0 discards the partial frame and starts
    //    a new one immediately. No known frame has 0xfc as a data
    //    byte (data is 00/01/a0-a4/aa/1c/0c/1d/brightness), so this
    //    cannot split a valid frame. A byte lost inside a frame
    //    still costs that one frame (unavoidable: its end byte no
    //    longer lines up), but the next frame resyncs, as before.
    // 3. PER-BYTE LOGCAT SPAM: the old Serial Debug logged every
    //    byte to logcat, which is slow enough to contribute to tty
    //    buffer pressure during bursts. Now frames (not bytes) go
    //    to serial.log, and dropped partials/bad frames are logged
    //    there too, with counters in every metrics snapshot, so
    //    the drop rate is visible long-term instead of in a 10-min
    //    logcat window.
    // Frame-loss improvements , superseding the
    // old "handleFrame runs on the read thread" design: handleFrame
    // is now handleFrameArrival, which only updates tracked state
    // and enqueues MQTT publishes (see PublishAction). A slow broker
    // or reconnect therefore stalls publishing, never serial
    // reading. lastMotionState is still set BEFORE the publish is
    // enqueued, so a single failed QoS 0 publish still heals within
    // one 5 s poll tick. Press batching still uses the 350 ms window
    // from first-frame processing, which is arrival time in practice
    // because handleFrameArrival is fast (no network, no metrics
    // snapshot — the 30 s periodic snapshot covers motion age).
    private void readLoop() {
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_MORE_FAVORABLE); } catch (Exception ignored) {}
        long backoffMs = 1000;
        while (running) {
            try { Runtime.getRuntime().exec(new String[]{"sh", "-c", "stty -F " + profile.serialPath + " " + profile.baud + " raw"}).waitFor(); } catch (Exception e) { Log.w(TAG, "stty failed", e); }
            try {
                serialIn = new FileInputStream(profile.serialPath);
                if (!ttyScanDone) {
                    ttyScanDone = true;
                    // Best-effort competing-reader guard: Android
                    // hides other processes' /proc/<pid>/fd from an
                    // app, so this can only confirm the daemon's own
                    // fds — a full check still needs adb /proc/*/fd.
                    int ttyFds = 0;
                    try {
                        java.io.File[] fds = new java.io.File("/proc/self/fd").listFiles();
                        if (fds != null) for (java.io.File fd : fds) {
                            try { if (profile.serialPath.equals(fd.getCanonicalPath())) ttyFds++; } catch (Exception ignored) {}
                        }
                    } catch (Exception ignored) {}
                    Log.i(TAG, "tty reader scan: own serial fds=" + ttyFds + " (other pids hidden from app, check via adb if a miss is suspected)");
                    diag.event("tty reader scan: own serial fds=" + ttyFds + " (other pids hidden from app)");
                }
                byte[] frame = new byte[7]; int pos = 0;
                byte[] buf = new byte[64];
                while (running) {
                    int n = serialIn.read(buf); if (n < 0) throw new java.io.EOFException("serial EOF");
                    for (int i = 0; i < n; i++) {
                        int b = buf[i] & 0xff;
                        if (pos > 0 && b == 0xfc) {
                            droppedFrameCount++;
                            String partial = hex(frame, pos);
                            Log.i(TAG, "serial frame partial discarded on resync: " + partial);
                            diag.serial("dropped partial frame: " + partial + " (resync on 0xfc)");
                            frame[0] = (byte) b; pos = 1; continue;
                        }
                        if (pos == 0 && b != 0xfc) continue;
                        frame[pos++] = (byte) b;
                        if (pos == 7) {
                            pos = 0;
                            if (frame[6] == (byte) 0xfe) {
                                byte[] copy = java.util.Arrays.copyOf(frame, 7);
                                frameCount++; lastFrameTimeMs = System.currentTimeMillis(); lastFrameHex = hex(copy, 7);
                                backoffMs = 1000;
                                handleFrameArrival(copy);
                            } else {
                                droppedFrameCount++;
                                String bad = hex(frame, 7);
                                Log.i(TAG, "serial frame bad end (dropped, no fe): " + bad);
                                diag.serial("dropped bad frame: " + bad);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                if (!running) break;
                serialErrorCount++;
                Log.e(TAG, "serial read failed, reopening in " + backoffMs + " ms", e);
                diag.event("serial read error: " + e + " reopening in " + backoffMs + " ms");
                diag.metrics(metricsSnapshot("serial-reopen"));
                try { if (serialIn != null) serialIn.close(); } catch (Exception ignored) {}
                serialIn = null;
                try { Thread.sleep(backoffMs); } catch (InterruptedException ignored) { break; }
                if (backoffMs < 30000) backoffMs *= 2;
            }
        }
    }
    private void handleFrameArrival(byte[] f) {
        String frameHex = hex(f, 7);
        Log.i(TAG, "frame: " + frameHex);
        diag.serial("IN " + frameHex + " " + decodeFrame(f));
        try {
            if (f[2] == 0x1c) { lastMotionState = 1; lastMotionFrameTimeMs = System.currentTimeMillis(); enqueuePublish(new PublishAction(PublishAction.MOTION, 0, true)); }
            else if (f[2] == 0x0c) {
                // OFF-without-ON warning : an OFF
                // while already clear means either a repeat clear or
                // a missed ON. The first OFF after service start
                // (lastMotionState == -1) is exempt. Log it and ask
                // pollLoop for an immediate, throttled health probe.
                if (lastMotionState == 0) {
                    Log.w(TAG, "motion OFF without preceding ON - possible missed ON");
                    diag.event("motion OFF without preceding ON - possible missed ON");
                    probeRequested = true;
                }
                lastMotionState = 0; lastMotionFrameTimeMs = System.currentTimeMillis(); enqueuePublish(new PublishAction(PublishAction.MOTION, 0, false));
            }
            else if (f[2] == 0x00 && f[3] == 0x00 && f[4] == 0x01) {
                if (wakescreenEnabled(getSharedPreferences(Constants.PREFS, MODE_PRIVATE))) wakeScreen();
                enqueuePublish(new PublishAction(PublishAction.BUTTON, f[5] & 0xff, false));
                // Physical press = presence :
                // publish Detected now; the panel's own clear ends the
                // episode. Covers a press landing before the radar edge.
                lastMotionState = 1;
                enqueuePublish(new PublishAction(PublishAction.MOTION, 0, true));
            } else if (f[2] == 0x00 && (f[3] & 0xf0) == 0xa0) {
                if (wakescreenEnabled(getSharedPreferences(Constants.PREFS, MODE_PRIVATE))) wakeScreen();
                // Relay 4 is a query-only channel: its frame is
                // fc 00 00 a4 00 00 fe, i.e. f[5] is 0, NOT 4 (detect
                // it via f[3]). The a4 frame TERMINATES a query batch:
                // the batch's a1-a3 frames arrived in the same burst
                // just before it and are sitting in the candidate list
                // below -- discard them, they were state, not presses.
                // A press arriving after the a4 is a new candidate in
                // a fresh window and is never poisoned by this batch.
                if ((f[3] & 0xff) == 0xa4) {
                    synchronized (pendingPresses) { pendingPresses.clear(); }
                    return;
                }
                int relay = f[5] & 0xff;
                boolean on = f[4] == 0x01;
                if (relay >= 1 && relay <= 4) lastRelayState[relay] = on ? 1 : 0;
                enqueuePublish(new PublishAction(PublishAction.RELAY, relay, on));
                // Candidate-press window (80 ms): a relay frame is
                // either a physical press or one frame of a query
                // batch. A batch is a single burst (a1-a4 within
                // ~10 ms) terminated by the a4 frame, which discards
                // the candidates (see the a4 branch above). So: hold
                // the frame as a candidate for 80 ms; if no a4 shows
                // up, it was a real press. The window is anchored to
                // the burst itself. (The previous design used a
                // 350 ms window resolved by a thread started at the
                // batch's FIRST frame; its tail extended past the
                // batch's end and the a4 marker poisoned real presses
                // landing in that tail -- with the snap-back's query
                // after every press, alternating presses failed
                // systematically.)
                synchronized (pendingPresses) {
                    pendingPresses.add(new int[]{relay, on ? 1 : 0});
                    if (!pressThreadRunning) {
                        pressThreadRunning = true;
                        new Thread(() -> {
                            try { Thread.sleep(80); } catch (InterruptedException ignored) {}
                            // Snapshot under the lock, act outside it:
                            // the snap-back below sleeps in setRelay,
                            // and the read thread must not wait on that.
                            java.util.ArrayList<int[]> batch;
                            synchronized (pendingPresses) {
                                batch = new java.util.ArrayList<int[]>(pendingPresses);
                                pendingPresses.clear();
                                pressThreadRunning = false;
                            }
                            if (batch.isEmpty()) return;
                            SharedPreferences p = getSharedPreferences(Constants.PREFS, MODE_PRIVATE);
                            int[] lastPressState = new int[]{-1, -1, -1, -1, -1};
                            for (int[] pr : batch) {
                                if (pr[0] < 1 || pr[0] > profile.relayCount) continue;
                                Log.i(TAG, "physical relay press: relay=" + pr[0] + " on=" + (pr[1] == 1));
                                enqueuePublish(new PublishAction(PublishAction.RELAY_PRESS, pr[0], false));
                                lastPressState[pr[0]] = pr[1];
                            }
                            // Physical press = presence : publish Detected now; the
                            // panel's own clear ends the episode.
                            lastMotionState = 1;
                            enqueuePublish(new PublishAction(PublishAction.MOTION, 0, true));
                            // Momentary snap-back: a press that left a
                            // momentary relay ON is undone by the daemon
                            // itself, locally on the serial wire (no
                            // MQTT round trip), so the button LED stays
                            // lit well under a second. A press that
                            // turned it OFF needs nothing.
                            for (int r = 1; r <= profile.relayCount; r++) {
                                if (lastPressState[r] == 1 && relayMomentary(p, r)) {
                                    Log.i(TAG, "momentary snap-back: relay=" + r);
                                    diag.event("momentary snap-back relay " + r);
                                    setRelay(r, false);
                                }
                            }
                        }).start();
                    }
                }
            }
        } catch (Exception e) {
            // Tracked state was already set above, so a single failed
            // enqueue still heals on the next 5 s poll tick (poll
            // republishes lastMotionState / lastRelayState directly).
            Log.w(TAG, "frame handling failed", e);
            diag.event("frame handling failed for frame " + frameHex + ": " + e);
        }
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
            SharedPreferences p = getSharedPreferences(Constants.PREFS, MODE_PRIVATE);
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
                for (int rr = 1; rr <= profile.relayCount; rr++) if (lastRelayState[rr] >= 0) mqtt.relay(rr, lastRelayState[rr] == 1);
                if (lastMotionState >= 0) mqtt.motion(lastMotionState == 1);
                mqtt.publishOptionStates(wakelockEnabled(p), wakescreenEnabled(p), debugEnabled(p));
                mqtt.publishRelayModeStates();
                mqtt.volumeStream("system", volumePctFor(AudioManager.STREAM_SYSTEM));
                mqtt.volumeStream("notification", volumePctFor(AudioManager.STREAM_NOTIFICATION));
                mqtt.volumeStream("alarm", volumePctFor(AudioManager.STREAM_ALARM));
                mqtt.volumeStream("ring", volumePctFor(AudioManager.STREAM_RING));
                mqtt.volumeStream("voice", volumePctFor(AudioManager.STREAM_VOICE_CALL));
            }
        } catch (Exception e) { Log.w(TAG, "device state publish failed: " + e); }
    }
    private void sendProbe(String why) {
        // Throttled by the caller (60 s minimum between probes, except
        // the one retry after a failure). A probe is just a query: the
        // MCU answers with the a1–a4 state batch, which handleFrameArrival
        // suppresses as presses (queryBatch window), so it has no
        // side effects in HA beyond republishing unchanged states.
        probePending = true; probeQueryTimeMs = System.currentTimeMillis();
        probeStartFrames = frameCount; lastProbeTimeMs = probeQueryTimeMs;
        if (!"retry after failure".equals(why)) probeRetryUsed = false;
        Log.i(TAG, "serial health probe (" + why + ")");
        diag.event("serial health probe (" + why + ")");
        sendQuery();
    }
    private void pollLoop() {
        int tick = 0;
        while (pollRunning) {
            try { if (mqtt.connected()) publishDeviceStates(true); } catch (Exception e) { Log.w(TAG, "poll failed: " + e); }
            tick++;
            // Metrics every 30 s: every 6th 5 s tick, no new thread.
            if (tick % 6 == 0) diag.metrics(metricsSnapshot("periodic-30s"));
            // Health probe: every 15 min (180 ticks), an immediate
            // probe requested by an OFF-without-ON warning (throttled
            // to 60 s), or the single retry after a failed probe.
            long now = System.currentTimeMillis();
            if (probePending) {
                if (frameCount > probeStartFrames) {
                    probePending = false; probeRetryPending = false;
                    Log.i(TAG, "serial health probe OK");
                    diag.event("serial health probe OK");
                } else if (now - probeQueryTimeMs > 2000) {
                    probePending = false;
                    Log.w(TAG, "serial health probe FAILED: no frame in 2 s");
                    diag.event("serial health probe FAILED: no frame in 2 s");
                    // Close serialIn from here: readLoop's blocked
                    // read() throws, and its catch reopens with
                    // backoff (existing mechanism). If the probe
                    // failed because the reader was dead, this is
                    // what revives it; if the MCU was silent, the
                    // reopen is harmless.
                    try { if (serialIn != null) serialIn.close(); } catch (Exception ignored) {}
                    if (!probeRetryUsed) { probeRetryPending = true; probeRetryUsed = true; }
                }
            } else if (probeRetryPending) {
                probeRetryPending = false;
                sendProbe("retry after failure");
            } else if (tick % 180 == 0) {
                sendProbe("15-min");
            } else if (probeRequested && now - lastProbeTimeMs > 60000) {
                probeRequested = false;
                sendProbe("OFF-without-ON");
            }
            try { Thread.sleep(5000); } catch (InterruptedException ignored) { break; }
        }
    }
    // ---- MQTT commands ----
    private void handleCommand(String topic, String payload) {
        if (!topic.endsWith("/set") && !topic.endsWith("/press")) return;
        String pl = payload == null ? "" : payload.trim();
        SharedPreferences p = getSharedPreferences(Constants.PREFS, MODE_PRIVATE);
        Log.i(TAG, "mqtt command " + topic + " -> " + pl);
        if (topic.endsWith("/debug/set")) {
            setDebug("ON".equalsIgnoreCase(pl));
        } else if (topic.endsWith("/wakelock/set")) {
            boolean on = "ON".equalsIgnoreCase(pl);
            SharedPreferences.Editor ed = p.edit(); ed.putBoolean("wakelock_enabled", on); ed.apply(); applyWakelock();
            try { mqtt.publishOptionStates(wakelockEnabled(p), wakescreenEnabled(p), debugEnabled(p)); } catch (Exception e) { Log.w(TAG, "option publish failed: " + e); }
        } else if (topic.endsWith("/wakescreen/set")) {
            boolean on = "ON".equalsIgnoreCase(pl);
            SharedPreferences.Editor ed = p.edit(); ed.putBoolean("wakescreen_enabled", on); ed.apply();
            try { mqtt.publishOptionStates(wakelockEnabled(p), wakescreenEnabled(p), debugEnabled(p)); } catch (Exception e) { Log.w(TAG, "option publish failed: " + e); }
        } else if (topic.matches(".*/relay/[0-9]+/mode/set")) {
            String[] parts = topic.split("/");
            int relay = Integer.parseInt(parts[parts.length - 3]);
            if (relay >= 1 && relay <= 3) {
                if ("momentary".equalsIgnoreCase(pl)) setRelayMode(relay, true);
                else if ("relay".equalsIgnoreCase(pl)) setRelayMode(relay, false);
            }
        } else if (topic.matches(".*/relay/[0-9]+/set")) {
            String[] parts = topic.split("/");
            int relay = Integer.parseInt(parts[parts.length - 2]);
            if (relay >= 1 && relay <= 4) {
                // A momentary relay has no HA switch, so a set can only
                // arrive from a stale entity or a manual publish: ignore
                // it -- remote flips are exactly what momentary mode
                // exists to prevent.
                if (relay <= 3 && relayMomentary(p, relay)) { Log.i(TAG, "relay set ignored: relay " + relay + " is in momentary mode"); }
                else setRelay(relay, "ON".equalsIgnoreCase(pl));
            }
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
        if (diag != null) diag.event("service stopped");
        running = false; reconnectRunning = false; pollRunning = false; publisherRunning = false;
        try { if (serialIn != null) serialIn.close(); } catch (Exception ignored) {}
        try { if (serialOut != null) serialOut.close(); } catch (Exception ignored) {}
        mqtt.disconnect();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (screenWakeLock != null && screenWakeLock.isHeld()) screenWakeLock.release();
        super.onDestroy();
    }
}
