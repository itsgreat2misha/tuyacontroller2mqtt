package com.misha.f5daemon;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
// Long-term diagnostics logger for the single global Debug mode
// (Nov 1, 2026). Three separate rotating log files in the daemon's
// private files dir (files/diag/), so no new permission is needed and
// nothing is network-exposed:
//   serial.log  - every ttyAS2 frame in/out (hex + decoded meaning),
//                 plus dropped/bad frames and partial frames discarded
//                 on a mid-frame resync.
//   metrics.log - a snapshot every 30 s (piggybacked on the existing
//                 5 s poll loop, every 6th tick, no new thread or
//                 wakelock) plus event-triggered snapshots on motion
//                 frames, service start, MQTT connect/disconnect and
//                 serial reopen, so resolution around an incident is
//                 seconds, not 30 s.
//   events.log  - lifecycle and errors only: service start/stop,
//                 debug on/off, MQTT connect failures, serial read
//                 errors and reopens, rotation, low-space pauses.
// Hard total cap: 30 MB (serial 5 MB, metrics 20 MB, events 5 MB),
// each as current + 4 rotated files, oldest deleted on rotation.
// The cap is enforced in code on every write, so leaving Debug on
// indefinitely can never eat panel storage: old data rolls off.
// A second guard pauses writing if /data free space drops below
// 100 MB (the cap alone already guarantees < 30 MB, this is belt
// and suspenders for a nearly-full disk). Writes are open/write/
// flush/close per entry: rates are low (frames are event-driven,
// metrics are 30 s), and this way a crash loses at most one entry.
// Never logs audio, mic data, or MQTT credentials: callers pass
// only the strings they already log to logcat.
public class DiagLogger {
    private static final String TAG = "F5Daemon";
    private static final int FILES_PER_LOG = 5; // current + 4 rotated
    private static final long SERIAL_TOTAL = 5L * 1024L * 1024L;
    private static final long METRICS_TOTAL = 20L * 1024L * 1024L;
    private static final long EVENTS_TOTAL = 5L * 1024L * 1024L;
    private static final long MIN_FREE_BYTES = 100L * 1024L * 1024L;
    private final File dir;
    private final SimpleDateFormat ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
    private volatile boolean enabled = false;
    private volatile boolean paused = false;
    public DiagLogger(File filesDir) {
        dir = new File(filesDir, "diag");
        try { dir.mkdirs(); } catch (Exception ignored) {}
    }
    public synchronized void setEnabled(boolean on) { enabled = on; }
    public synchronized boolean isEnabled() { return enabled; }
    public synchronized void serial(String line) { write("serial", SERIAL_TOTAL, line); }
    public synchronized void metrics(String line) { write("metrics", METRICS_TOTAL, line); }
    public synchronized void event(String line) { write("events", EVENTS_TOTAL, line); }
    private void write(String kind, long total, String line) {
        if (!enabled) return;
        try {
            long free = dir.getUsableSpace();
            if (free >= 0 && free < MIN_FREE_BYTES) {
                if (!paused) { paused = true; Log.w(TAG, "diag logging paused: free space " + free + " below 100 MB"); }
                return;
            }
            if (paused) { paused = false; Log.i(TAG, "diag logging resumed: free space recovered"); }
            File cur = new File(dir, kind + ".log");
            String entry = ts.format(new Date()) + " " + line + "\n";
            byte[] bytes = entry.getBytes("UTF-8");
            long fileMax = total / FILES_PER_LOG;
            if (cur.length() + bytes.length > fileMax) rotate(kind);
            FileOutputStream out = new FileOutputStream(cur, true);
            try { out.write(bytes); out.flush(); } finally { out.close(); }
        } catch (Exception e) { Log.w(TAG, "diag write failed (" + kind + "): " + e); }
    }
    private void rotate(String kind) {
        try {
            File oldest = new File(dir, kind + "." + (FILES_PER_LOG - 1) + ".log");
            if (oldest.exists()) oldest.delete();
            for (int i = FILES_PER_LOG - 2; i >= 1; i--) {
                File src = new File(dir, kind + "." + i + ".log");
                if (!src.exists()) continue;
                File dst = new File(dir, kind + "." + (i + 1) + ".log");
                if (!src.renameTo(dst)) { dst.delete(); src.renameTo(dst); }
            }
            File cur = new File(dir, kind + ".log");
            if (cur.exists()) {
                File dst = new File(dir, kind + ".1.log");
                if (!cur.renameTo(dst)) { dst.delete(); cur.renameTo(dst); }
            }
        } catch (Exception e) { Log.w(TAG, "diag rotate failed (" + kind + "): " + e); }
    }
}
