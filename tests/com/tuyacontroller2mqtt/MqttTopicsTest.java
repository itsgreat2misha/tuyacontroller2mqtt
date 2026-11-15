package com.tuyacontroller2mqtt;
// Plain-JVM unit tests for the pure naming/discovery logic
// (MqttTopics + DeviceProfile). No Android runtime needed: compile the
// two pure source files together with this test and run it, see
// tests/run_tests.sh. Exits non-zero on the first failure.
public class MqttTopicsTest {
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
    public static void main(String[] args) {
        // slug(): the device name -> topic/unique_id slug. These names
        // are a compatibility surface for existing HA installs.
        check("slug basic", MqttTopics.slug("Entryway Assistant"), "entryway_assistant");
        check("slug already slug", MqttTopics.slug("panel_1"), "panel_1");
        check("slug collapses separators", MqttTopics.slug("My  Panel!!"), "my_panel");
        check("slug trims separators", MqttTopics.slug("  Panel  "), "panel");
        check("slug null", MqttTopics.slug(null), "panel");
        check("slug empty", MqttTopics.slug(""), "panel");
        check("slug only symbols", MqttTopics.slug("***"), "panel");

        // Topic root and client id derive from the slug only.
        check("topic root", MqttTopics.topicRoot("entryway_assistant"), "tuyacontroller/entryway_assistant");
        check("client id", MqttTopics.clientId("entryway_assistant"), "tuyacontroller-entryway_assistant");

        // Discovery device block: identifiers are the slug, name is the
        // device name, model/manufacturer come from the profile.
        DeviceProfile f53 = DeviceProfile.F5_3;
        String dev = MqttTopics.deviceJson("entryway_assistant", "Entryway Assistant", f53);
        checkTrue("device identifiers", dev.contains("\"identifiers\":[\"entryway_assistant\"]"));
        checkTrue("device name", dev.contains("\"name\":\"Entryway Assistant\""));
        checkTrue("device model from profile", dev.contains("\"model\":\"F5-3\""));
        checkTrue("device manufacturer from profile", dev.contains("\"manufacturer\":\"sznaner\""));

        // A different profile must flow through unchanged: discovery
        // must not contain F5-3 literals for another model.
        DeviceProfile other = new DeviceProfile("X1", "acme", "/dev/ttyS1", 9600, 1, 4, 2, false, false, false, (byte) 0xfc, (byte) 0xfe, 7, 0x1c, 0x0c, 0x1d, 0xaa, 0xa4, 0xa0, "/proc/vendor/mute_manual");
        String dev2 = MqttTopics.deviceJson("hall", "Hall", other);
        checkTrue("other model", dev2.contains("\"model\":\"X1\""));
        checkTrue("other manufacturer", dev2.contains("\"manufacturer\":\"acme\""));
        checkTrue("no F5 leakage", !dev2.contains("F5-3") && !dev2.contains("sznaner"));

        // JSON escaping of quotes/backslashes in device names.
        check("escape", MqttTopics.escapeJson("a\"b\\c"), "a\\\"b\\\\c");
        checkTrue("escaped name in device json",
            MqttTopics.deviceJson("s", "Pa\"nel", f53).contains("\"name\":\"Pa\\\"nel\""));

        // Availability block references the device's own root.
        checkTrue("availability root",
            MqttTopics.availabilityJson("tuyacontroller/x").contains("\"availability_topic\":\"tuyacontroller/x/availability\""));

        // F5-3 profile values (the tested device).
        check("profile serial", f53.serialPath, "/dev/ttyAS2");
        check("profile baud", f53.baud, 115200);
        check("profile first button", f53.firstButton, 4);
        check("profile last button", f53.lastButton, 8);
        check("profile relays", f53.relayCount, 3);
        checkTrue("profile night light", f53.nightLightSupported);
        checkTrue("profile motion", f53.motionSupported);
        checkTrue("profile speaker mute", f53.speakerMuteSupported);
        check("profile frame start", f53.frameStart & 0xff, 0xfc);
        check("profile frame end", f53.frameEnd & 0xff, 0xfe);
        check("profile frame length", f53.frameLength, 7);
        check("profile motion on code", f53.motionOnCode, 0x1c);
        check("profile motion off code", f53.motionOffCode, 0x0c);
        check("profile night light code", f53.nightLightCode, 0x1d);
        check("profile query code", f53.queryCode, 0xaa);
        check("profile query end marker", f53.queryEndMarkerCode, 0xa4);
        check("profile relay code base", f53.relayCodeBase, 0xa0);
        check("profile mute proc path", f53.muteProcPath, "/proc/vendor/mute_manual");
        checkTrue("active profile is F5-3", DeviceProfile.active() == f53);

        // Relay range is the profile's relayCount: relay 4 exists on
        // the F5-3 only as a query-batch marker and must not be
        // accepted as a command target.
        checkTrue("profile has relay 1", f53.hasRelay(1));
        checkTrue("profile has relay 3", f53.hasRelay(3));
        checkTrue("profile has no relay 4", !f53.hasRelay(4));
        checkTrue("profile has no relay 0", !f53.hasRelay(0));
        checkTrue("other profile has no relay 3", !other.hasRelay(3));

        System.out.println("OK: " + passed + " checks passed");
    }
}
