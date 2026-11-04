package com.tuyacontroller2mqtt;
import android.util.Log;
import java.io.*;
// Runs a command as root via su. Correct syntax on the F5-3 ROM:
// "su 0 <argv...>", no -c flag. Returns the merged stdout/stderr, or
// null when the process could not be started (e.g. no su binary on
// an unrooted panel) — callers treat null as "no privileged channel".
public final class SuExec {
    private SuExec() {}
    public static String run(String... argv) {
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
            Log.i(Constants.TAG, "su " + String.join(" ", argv) + " exit=" + rc + " output=" + out);
            return out.toString();
        } catch (Exception e) { Log.w(Constants.TAG, "su failed: " + e); return null; }
    }
}
