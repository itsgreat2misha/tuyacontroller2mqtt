package com.tuyacontroller2mqtt;
import java.util.List;
// Plain-JVM unit tests for the profile-driven behavior: FrameCodec
// (frame bytes and classification come from the DeviceProfile),
// Discovery (the exact discovery message set, including capability
// gating), and InputValidation (volume clamp, mute payload
// whitelist). No Android runtime needed, see tests/run_tests.sh.
// Exits non-zero on the first failure.
//
// The F5-3 frame bytes and discovery payloads below are the
// compatibility surface: they are what the daemon has always sent
// and published, and the refactor behind the profile must not
// change a single byte of them.
public class ProfileBehaviorTest {
    private static int passed = 0;
    private static void check(String name, Object actual, Object expected) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            System.out.println("FAIL " + name + ": expected <" + expected + "> got <" + actual + ">");
            System.exit(1);
        }
        passed++;
    }
    private static void checkTrue(String name, boolean cond) {
        if (!cond) { System.out.println("FAIL " + name); System.exit(1); }
        passed++;
    }
    private static String hex(byte[] f) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < f.length; i++) { if (i > 0) sb.append(" "); sb.append(String.format("%02x", f[i])); }
        return sb.toString();
    }
    private static byte[] frame(int... bytes) {
        byte[] f = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) f[i] = (byte) bytes[i];
        return f;
    }
    private static boolean hasTopic(List<String[]> msgs, String part) {
        for (String[] m : msgs) if (m[0].contains(part)) return true;
        return false;
    }
    private static String payloadFor(List<String[]> msgs, String topic) {
        for (String[] m : msgs) if (m[0].equals(topic)) return m[1];
        return null;
    }

    public static void main(String[] args) {
        DeviceProfile f53 = DeviceProfile.F5_3;

        // ---- FrameCodec: outbound frames are byte-for-byte the
        // frames ButtonService has always written. ----
        check("query frame", hex(FrameCodec.queryFrame(f53)), "fc 00 00 aa ff 00 fe");
        check("relay 1 on frame", hex(FrameCodec.relayFrame(f53, 1, true)), "fc 00 00 a1 01 01 fe");
        check("relay 2 off frame", hex(FrameCodec.relayFrame(f53, 2, false)), "fc 00 00 a2 00 02 fe");
        check("relay 3 on frame", hex(FrameCodec.relayFrame(f53, 3, true)), "fc 00 00 a3 01 03 fe");
        // A relay outside the profile keeps the test frame (last
        // byte 0): it must not become a real switch frame.
        check("relay 4 test frame", hex(FrameCodec.relayFrame(f53, 4, true)), "fc 00 00 a4 01 00 fe");
        check("night light on frame", hex(FrameCodec.nightLightFrame(f53, true)), "fc 00 1d 01 00 00 fe");
        check("night light off frame", hex(FrameCodec.nightLightFrame(f53, false)), "fc 00 1d 00 00 00 fe");
        check("night light brightness frame", hex(FrameCodec.nightLightBrightnessFrame(f53, 50)), "fc 00 1d 02 32 00 fe");

        // ---- FrameCodec: inbound classification and labels. ----
        checkTrue("motion on", FrameCodec.isMotionOn(f53, frame(0xfc, 0x00, 0x1c, 0x00, 0x00, 0x00, 0xfe)));
        checkTrue("motion off", FrameCodec.isMotionOff(f53, frame(0xfc, 0x00, 0x0c, 0x00, 0x00, 0x00, 0xfe)));
        checkTrue("button press", FrameCodec.isButtonPress(frame(0xfc, 0x00, 0x00, 0x00, 0x01, 0x05, 0xfe)));
        checkTrue("query", FrameCodec.isQuery(f53, frame(0xfc, 0x00, 0x00, 0xaa, 0xff, 0x00, 0xfe)));
        checkTrue("query is not relay-shaped check order", FrameCodec.isQuery(f53, frame(0xfc, 0x00, 0x00, 0xaa, 0xff, 0x00, 0xfe))
            && FrameCodec.describe(f53, frame(0xfc, 0x00, 0x00, 0xaa, 0xff, 0x00, 0xfe)).equals("query"));
        checkTrue("relay frame", FrameCodec.isRelayFrame(f53, frame(0xfc, 0x00, 0x00, 0xa3, 0x01, 0x03, 0xfe)));
        checkTrue("query end marker", FrameCodec.isQueryEndMarker(f53, frame(0xfc, 0x00, 0x00, 0xa4, 0x00, 0x00, 0xfe)));
        check("relay number", FrameCodec.relayNumber(frame(0xfc, 0x00, 0x00, 0xa3, 0x01, 0x03, 0xfe)), 3);
        checkTrue("relay on", FrameCodec.relayOn(frame(0xfc, 0x00, 0x00, 0xa3, 0x01, 0x03, 0xfe)));
        check("describe motion on", FrameCodec.describe(f53, frame(0xfc, 0x00, 0x1c, 0x00, 0x00, 0x00, 0xfe)), "motion ON");
        check("describe motion off", FrameCodec.describe(f53, frame(0xfc, 0x00, 0x0c, 0x00, 0x00, 0x00, 0xfe)), "motion OFF");
        check("describe button", FrameCodec.describe(f53, frame(0xfc, 0x00, 0x00, 0x00, 0x01, 0x05, 0xfe)), "button press id=5");
        check("describe relay", FrameCodec.describe(f53, frame(0xfc, 0x00, 0x00, 0xa3, 0x01, 0x03, 0xfe)), "relay 3 ON (f3=a3)");
        check("describe unknown", FrameCodec.describe(f53, frame(0xfc, 0x00, 0x55, 0x00, 0x00, 0x00, 0xfe)), "unknown f2=55 f3=00");

        // ---- FrameCodec: the codes really come from the profile --
        // a profile with different codes classifies and builds by
        // its own values, not by the F5-3 literals. ----
        DeviceProfile coded = new DeviceProfile("Y2", "acme", "/dev/ttyS2", 9600, 1, 2, 1,
            true, true, true, (byte) 0xfd, (byte) 0xff, 7, 0x2c, 0x3c, 0x2d, 0xbb, 0xa5, 0xb0, "/proc/vendor/mute2");
        check("coded query frame", hex(FrameCodec.queryFrame(coded)), "fd 00 00 bb ff 00 ff");
        check("coded relay frame", hex(FrameCodec.relayFrame(coded, 1, true)), "fd 00 00 b1 01 01 ff");
        checkTrue("coded motion on", FrameCodec.isMotionOn(coded, frame(0xfd, 0x00, 0x2c, 0x00, 0x00, 0x00, 0xff)));
        checkTrue("coded motion on is not F5 code", !FrameCodec.isMotionOn(coded, frame(0xfd, 0x00, 0x1c, 0x00, 0x00, 0x00, 0xff)));
        checkTrue("coded query end marker", FrameCodec.isQueryEndMarker(coded, frame(0xfd, 0x00, 0x00, 0xa5, 0x00, 0x00, 0xff)));

        // ---- Discovery: the F5-3 message set is exactly the set
        // MqttPublisher has always published, in the same order. ----
        String slug = "panel", name = "Panel", root = "tuyacontroller/panel";
        String avail = MqttTopics.availabilityJson(root);
        String dev = MqttTopics.deviceJson(slug, name, f53);
        boolean[] noneMomentary = new boolean[f53.relayCount + 1];
        List<String[]> msgs = Discovery.discoveryMessages(f53, noneMomentary, slug, name, root);
        String[] expectedTopics = {
            "homeassistant/event/panel_button_4/config",
            "homeassistant/event/panel_button_5/config",
            "homeassistant/event/panel_button_6/config",
            "homeassistant/event/panel_button_7/config",
            "homeassistant/event/panel_button_8/config",
            "homeassistant/binary_sensor/panel_relay_1/config",
            "homeassistant/switch/panel_relay_1/config",
            "homeassistant/event/panel_relay_1_press/config",
            "homeassistant/select/panel_relay_1_mode/config",
            "homeassistant/binary_sensor/panel_relay_2/config",
            "homeassistant/switch/panel_relay_2/config",
            "homeassistant/event/panel_relay_2_press/config",
            "homeassistant/select/panel_relay_2_mode/config",
            "homeassistant/binary_sensor/panel_relay_3/config",
            "homeassistant/switch/panel_relay_3/config",
            "homeassistant/event/panel_relay_3_press/config",
            "homeassistant/select/panel_relay_3_mode/config",
            "homeassistant/light/panel_night_light/config",
            "homeassistant/switch/panel_volume_mute/config",
            "homeassistant/select/panel_proc_aux/config",
            "homeassistant/switch/panel_proc_horn/config",
            "homeassistant/switch/panel_screen/config",
            "homeassistant/number/panel_brightness/config",
            "homeassistant/number/panel_volume_music/config",
            "homeassistant/number/panel_volume_system/config",
            "homeassistant/number/panel_volume_notification/config",
            "homeassistant/number/panel_volume_alarm/config",
            "homeassistant/number/panel_volume_ring/config",
            "homeassistant/number/panel_volume_voice/config",
            "homeassistant/select/panel_rotation/config",
            "homeassistant/button/panel_reboot/config",
            "homeassistant/switch/panel_proc_mute/config",
            "homeassistant/binary_sensor/panel_motion/config",
            "homeassistant/switch/panel_wakelock/config",
            "homeassistant/switch/panel_wakescreen/config",
            "homeassistant/switch/panel_serial_debug/config",
            "homeassistant/switch/panel_debug/config",
        };
        check("discovery count", msgs.size(), expectedTopics.length);
        for (int i = 0; i < expectedTopics.length; i++) {
            check("discovery topic " + i, msgs.get(i)[0], expectedTopics[i]);
        }
        // Full payload spot-checks across the entity kinds.
        check("button payload", payloadFor(msgs, "homeassistant/event/panel_button_4/config"),
            "{\"name\":\"Button 4\",\"unique_id\":\"panel_button_4\",\"state_topic\":\"tuyacontroller/panel/button/4\",\"event_types\":[\"press\"]," + avail + dev + "}");
        check("relay switch payload", payloadFor(msgs, "homeassistant/switch/panel_relay_1/config"),
            "{\"name\":\"Relay 1\",\"unique_id\":\"panel_relay_1\",\"state_topic\":\"tuyacontroller/panel/relay/1\",\"command_topic\":\"tuyacontroller/panel/relay/1/set\",\"payload_on\":\"ON\",\"payload_off\":\"OFF\",\"state_on\":\"ON\",\"state_off\":\"OFF\"," + avail + dev + "}");
        check("relay press payload", payloadFor(msgs, "homeassistant/event/panel_relay_1_press/config"),
            "{\"name\":\"Relay 1 press\",\"unique_id\":\"panel_relay_1_press\",\"state_topic\":\"tuyacontroller/panel/relay/1/event\",\"event_types\":[\"press\"]," + avail + dev + "}");
        check("relay mode payload", payloadFor(msgs, "homeassistant/select/panel_relay_1_mode/config"),
            "{\"name\":\"Relay 1 Mode\",\"unique_id\":\"panel_relay_1_mode\",\"state_topic\":\"tuyacontroller/panel/relay/1/mode\",\"command_topic\":\"tuyacontroller/panel/relay/1/mode/set\",\"options\":[\"relay\",\"momentary\"]," + avail + dev + "}");
        check("night light payload", payloadFor(msgs, "homeassistant/light/panel_night_light/config"),
            "{\"name\":\"Night Light\",\"unique_id\":\"panel_night_light\",\"state_topic\":\"tuyacontroller/panel/nightlight/state\",\"command_topic\":\"tuyacontroller/panel/nightlight/set\",\"brightness_state_topic\":\"tuyacontroller/panel/nightlight/brightness\",\"brightness_command_topic\":\"tuyacontroller/panel/nightlight/brightness/set\",\"brightness_scale\":100,\"payload_on\":\"ON\",\"payload_off\":\"OFF\"," + avail + dev + "}");
        check("speaker mute payload", payloadFor(msgs, "homeassistant/switch/panel_proc_mute/config"),
            "{\"name\":\"Speaker Mute\",\"unique_id\":\"panel_proc_mute\",\"state_topic\":\"tuyacontroller/panel/proc/mute/state\",\"command_topic\":\"tuyacontroller/panel/proc/mute/set\",\"payload_on\":\"on\",\"payload_off\":\"off\",\"state_on\":\"on\",\"state_off\":\"off\"," + avail + dev + "}");
        check("motion payload", payloadFor(msgs, "homeassistant/binary_sensor/panel_motion/config"),
            "{\"name\":\"Motion\",\"unique_id\":\"panel_motion\",\"state_topic\":\"tuyacontroller/panel/motion\",\"payload_on\":\"ON\",\"payload_off\":\"OFF\",\"device_class\":\"motion\"," + avail + dev + "}");
        // Retractions stay empty payloads.
        check("relay binary_sensor retraction", payloadFor(msgs, "homeassistant/binary_sensor/panel_relay_1/config"), "");
        check("volume_mute retraction", payloadFor(msgs, "homeassistant/switch/panel_volume_mute/config"), "");
        check("serial_debug retraction", payloadFor(msgs, "homeassistant/switch/panel_serial_debug/config"), "");

        // Momentary mode retracts only that relay's switch.
        boolean[] relay2Momentary = new boolean[f53.relayCount + 1];
        relay2Momentary[2] = true;
        List<String[]> msgs2 = Discovery.discoveryMessages(f53, relay2Momentary, slug, name, root);
        check("momentary relay switch retracted", payloadFor(msgs2, "homeassistant/switch/panel_relay_2/config"), "");
        checkTrue("relay mode switch kept", payloadFor(msgs2, "homeassistant/switch/panel_relay_1/config").length() > 0);
        check("relay switch message momentary", Discovery.relaySwitchMessage(f53, true, slug, name, root, 2)[1], "");
        check("relay switch message topic", Discovery.relaySwitchMessage(f53, false, slug, name, root, 2)[0],
            "homeassistant/switch/panel_relay_2/config");

        // ---- Discovery: capability gating -- a profile without
        // motion, night light, or speaker mute publishes none of
        // those entities (and everything else still publishes). ----
        DeviceProfile bare = new DeviceProfile("B1", "acme", "/dev/ttyS1", 9600, 1, 4, 2,
            false, false, false, (byte) 0xfc, (byte) 0xfe, 7, 0x1c, 0x0c, 0x1d, 0xaa, 0xa4, 0xa0, "/proc/vendor/mute_manual");
        List<String[]> bareMsgs = Discovery.discoveryMessages(bare, new boolean[bare.relayCount + 1], slug, name, root);
        checkTrue("no motion entity", !hasTopic(bareMsgs, "_motion"));
        checkTrue("no speaker mute entity", !hasTopic(bareMsgs, "_proc_mute"));
        checkTrue("no night light entity", !hasTopic(bareMsgs, "_night_light"));
        checkTrue("buttons still published", hasTopic(bareMsgs, "_button_1"));
        checkTrue("relay mode still published", hasTopic(bareMsgs, "_relay_2_mode"));
        check("bare discovery count", bareMsgs.size(), 29);

        // ---- InputValidation: volume clamp + mute whitelist. ----
        check("clamp negative", InputValidation.clampPercent(-5), 0);
        check("clamp zero", InputValidation.clampPercent(0), 0);
        check("clamp mid", InputValidation.clampPercent(55), 55);
        check("clamp hundred", InputValidation.clampPercent(100), 100);
        check("clamp over", InputValidation.clampPercent(150), 100);
        checkTrue("mute on ok", InputValidation.isValidMutePayload("on"));
        checkTrue("mute off ok", InputValidation.isValidMutePayload("off"));
        checkTrue("mute ON rejected", !InputValidation.isValidMutePayload("ON"));
        checkTrue("mute 1 rejected", !InputValidation.isValidMutePayload("1"));
        checkTrue("mute empty rejected", !InputValidation.isValidMutePayload(""));
        checkTrue("mute padded rejected", !InputValidation.isValidMutePayload("on "));
        checkTrue("mute null rejected", !InputValidation.isValidMutePayload(null));

        System.out.println("OK: " + passed + " checks passed");
    }
}
