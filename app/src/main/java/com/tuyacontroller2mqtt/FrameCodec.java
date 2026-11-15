package com.tuyacontroller2mqtt;
// Pure MCU frame building and parsing, driven by the DeviceProfile's
// frame constants. No Android dependencies, so the plain-JVM tests
// can cover it (see tests/). The Android glue (serial I/O, state
// tracking, publishing) lives in ButtonService.
//
// Frame layout (see PROTOCOL.md for the full table): a fixed-length
// frame bracketed by the profile's start/end bytes. Byte 2 carries
// the motion and night-light function codes; byte 3 carries the
// relay/query codes; byte 4 is a state or value byte; byte 5 is an
// id (button number, relay number).
public final class FrameCodec {
    private FrameCodec() {}

    // ---- inbound frame classification ----

    public static boolean isMotionOn(DeviceProfile p, byte[] f) { return f[2] == (byte) p.motionOnCode; }

    public static boolean isMotionOff(DeviceProfile p, byte[] f) { return f[2] == (byte) p.motionOffCode; }

    public static boolean isButtonPress(byte[] f) { return f[2] == 0x00 && f[3] == 0x00 && f[4] == 0x01; }

    // The query frame must be checked BEFORE the relay branch (the
    // query code's high nibble matches the relay code base, so it
    // would otherwise read as a relay frame).
    public static boolean isQuery(DeviceProfile p, byte[] f) { return f[2] == 0x00 && f[3] == (byte) p.queryCode; }

    public static boolean isRelayFrame(DeviceProfile p, byte[] f) { return f[2] == 0x00 && (f[3] & 0xf0) == p.relayCodeBase; }

    // The query-batch end marker: on the F5-3 the terminating frame's
    // byte 3 is the marker code itself (relay 4's code) and its byte
    // 5 is 0, NOT the relay number.
    public static boolean isQueryEndMarker(DeviceProfile p, byte[] f) { return (f[3] & 0xff) == p.queryEndMarkerCode; }

    public static int relayNumber(byte[] f) { return f[5] & 0xff; }

    public static boolean relayOn(byte[] f) { return f[4] == 0x01; }

    // Human-readable label for the serial diagnostics log.
    public static String describe(DeviceProfile p, byte[] f) {
        if (isMotionOn(p, f)) return "motion ON";
        if (isMotionOff(p, f)) return "motion OFF";
        if (isButtonPress(f)) return "button press id=" + (f[5] & 0xff);
        if (isQuery(p, f)) return "query";
        if (isRelayFrame(p, f)) return "relay " + (f[5] & 0xff) + " " + (f[4] == 0x01 ? "ON" : "OFF") + " (f3=" + String.format("%02x", f[3]) + ")";
        return "unknown f2=" + String.format("%02x", f[2]) + " f3=" + String.format("%02x", f[3]);
    }

    // ---- outbound frames ----

    public static byte[] queryFrame(DeviceProfile p) {
        return new byte[]{p.frameStart, 0x00, 0x00, (byte) p.queryCode, (byte) 0xff, 0x00, p.frameEnd};
    }

    // Real switch frame for the exposed relays: last byte = relay id.
    // A relay outside the profile's range keeps the test frame (last
    // byte 0): the vendor test-app frames with a 00 last byte only
    // change the MCU's reported state, not the relay or its LED.
    public static byte[] relayFrame(DeviceProfile p, int relay, boolean on) {
        byte last = p.hasRelay(relay) ? (byte) relay : (byte) 0x00;
        return new byte[]{p.frameStart, 0x00, 0x00, (byte) (p.relayCodeBase + relay), (byte) (on ? 0x01 : 0x00), last, p.frameEnd};
    }

    public static byte[] nightLightFrame(DeviceProfile p, boolean on) {
        return new byte[]{p.frameStart, 0x00, (byte) p.nightLightCode, (byte) (on ? 0x01 : 0x00), 0x00, 0x00, p.frameEnd};
    }

    public static byte[] nightLightBrightnessFrame(DeviceProfile p, int brightness) {
        return new byte[]{p.frameStart, 0x00, (byte) p.nightLightCode, 0x02, (byte) brightness, 0x00, p.frameEnd};
    }
}
