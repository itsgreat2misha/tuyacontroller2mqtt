package com.tuyacontroller2mqtt;
// Pure validation for MQTT command payloads, kept Android-free so
// the plain-JVM tests can cover it (see tests/). The command
// handlers in ButtonService apply these before acting on a payload.
public final class InputValidation {
    private InputValidation() {}

    // Percent payloads (volume, brightness) are clamped into
    // 0..100 instead of being scaled unclamped or rejected.
    public static int clampPercent(int pct) {
        if (pct < 0) return 0;
        if (pct > 100) return 100;
        return pct;
    }

    // The Speaker Mute /proc node takes exactly "on" or "off" (the
    // payload_on/payload_off of its discovery config); anything else
    // is ignored rather than written to the node verbatim.
    public static boolean isValidMutePayload(String payload) {
        return "on".equals(payload) || "off".equals(payload);
    }
}
