package com.tuyacontroller2mqtt;
import java.util.ArrayList;
import java.util.List;
// Pure construction of the Home Assistant discovery messages, with
// no Android dependencies, so the plain-JVM tests can cover it (see
// tests/). MqttPublisher sends what this class builds.
//
// Each message is a {topic, payload} pair; an empty payload retracts
// the entity. Publication order matters on upgrades (a retraction
// must not land after the config it removes), so the order here is
// the order MqttPublisher has always published in. Entity names,
// topics and unique_ids are a compatibility surface (see MqttTopics)
// and must not change. Capability-gated entities (night light,
// motion, Speaker Mute) are published only when the device profile
// supports them.
public final class Discovery {
    private Discovery() {}

    public static List<String[]> discoveryMessages(DeviceProfile profile, boolean[] relayMomentary,
                                                   String slug, String deviceName, String root) {
        List<String[]> msgs = new ArrayList<String[]>();
        // (The pre-1.2.0 builds also retracted the v1/v2 f5_* discovery
        // topics on every connect. That legacy retraction is dropped:
        // a fresh install never had them, and anyone migrating from
        // those builds removes the stale entities once, see README.)
        String dev = MqttTopics.deviceJson(slug, deviceName, profile);
        String avail = MqttTopics.availabilityJson(root);
        for (int i = profile.firstButton; i <= profile.lastButton; i++) {
            String cfg = "{\"name\":\"Button " + i + "\",\"unique_id\":\"" + slug + "_button_" + i + "\",\"state_topic\":\"" + root + "/button/" + i + "\",\"event_types\":[\"press\"]," + avail + dev + "}";
            msgs.add(new String[]{"homeassistant/event/" + slug + "_button_" + i + "/config", cfg});
        }
        // Relays are switches, not binary_sensors (an earlier build
        // exposed binary_sensors; the empty config below removes those
        // so they do not linger). The switch config itself is
        // mode-dependent (see relaySwitchMessage): in momentary mode
        // it is retracted and only the press event entity remains.
        // The mode select is always published so the mode can be
        // flipped from HA.
        for (int i = 1; i <= profile.relayCount; i++) {
            msgs.add(new String[]{"homeassistant/binary_sensor/" + slug + "_relay_" + i + "/config", ""});
            msgs.add(relaySwitchMessage(profile, relayMomentary[i], slug, deviceName, root, i));
            String ecfg = "{\"name\":\"Relay " + i + " press\",\"unique_id\":\"" + slug + "_relay_" + i + "_press\",\"state_topic\":\"" + root + "/relay/" + i + "/event\",\"event_types\":[\"press\"]," + avail + dev + "}";
            msgs.add(new String[]{"homeassistant/event/" + slug + "_relay_" + i + "_press/config", ecfg});
            String mcfg = "{\"name\":\"Relay " + i + " Mode\",\"unique_id\":\"" + slug + "_relay_" + i + "_mode\",\"state_topic\":\"" + root + "/relay/" + i + "/mode\",\"command_topic\":\"" + root + "/relay/" + i + "/mode/set\",\"options\":[\"relay\",\"momentary\"]," + avail + dev + "}";
            msgs.add(new String[]{"homeassistant/select/" + slug + "_relay_" + i + "_mode/config", mcfg});
        }
        // Night light: MCU side LED bar, brightness only, no colour
        // (the vendor API has setSwitch + setBright 0-100, no colour).
        // State is optimistic: the MCU sends no status frame for it.
        if (profile.nightLightSupported) {
            String ncfg = "{\"name\":\"Night Light\",\"unique_id\":\"" + slug + "_night_light\",\"state_topic\":\"" + root + "/nightlight/state\",\"command_topic\":\"" + root + "/nightlight/set\",\"brightness_state_topic\":\"" + root + "/nightlight/brightness\",\"brightness_command_topic\":\"" + root + "/nightlight/brightness/set\",\"brightness_scale\":100,\"payload_on\":\"ON\",\"payload_off\":\"OFF\"," + avail + dev + "}";
            msgs.add(new String[]{"homeassistant/light/" + slug + "_night_light/config", ncfg});
        }
        // Removed entities (Music Mute, Audio Source, Horn): publish
        // empty discovery so HA removes them from upgraded installs.
        msgs.add(new String[]{"homeassistant/switch/" + slug + "_volume_mute/config", ""});
        msgs.add(new String[]{"homeassistant/select/" + slug + "_proc_aux/config", ""});
        msgs.add(new String[]{"homeassistant/switch/" + slug + "_proc_horn/config", ""});
        // Device-level controls. Brightness/volume state is polled
        // by the service, so changes from Settings/Kiosk update HA too.
        String scfg2 = "{\"name\":\"Screen\",\"unique_id\":\"" + slug + "_screen\",\"state_topic\":\"" + root + "/screen/state\",\"command_topic\":\"" + root + "/screen/set\",\"payload_on\":\"ON\",\"payload_off\":\"OFF\",\"state_on\":\"ON\",\"state_off\":\"OFF\"," + avail + dev + "}";
        msgs.add(new String[]{"homeassistant/switch/" + slug + "_screen/config", scfg2});
        String bcfg = "{\"name\":\"Screen Brightness\",\"unique_id\":\"" + slug + "_brightness\",\"state_topic\":\"" + root + "/brightness/state\",\"command_topic\":\"" + root + "/brightness/set\",\"min\":0,\"max\":100,\"step\":1,\"unit_of_measurement\":\"%\"," + avail + dev + "}";
        msgs.add(new String[]{"homeassistant/number/" + slug + "_brightness/config", bcfg});
        String vcfg = "{\"name\":\"Media Volume\",\"unique_id\":\"" + slug + "_volume_music\",\"state_topic\":\"" + root + "/volume/state\",\"command_topic\":\"" + root + "/volume/set\",\"min\":0,\"max\":100,\"step\":1,\"unit_of_measurement\":\"%\"," + avail + dev + "}";
        msgs.add(new String[]{"homeassistant/number/" + slug + "_volume_music/config", vcfg});
        String[][] streams = {{"System Volume", "_volume_system", "system"}, {"Notification Volume", "_volume_notification", "notification"}, {"Alarm Volume", "_volume_alarm", "alarm"}, {"Ring Volume", "_volume_ring", "ring"}, {"Voice Call Volume", "_volume_voice", "voice"}};
        for (String[] st : streams) {
            String cfg = "{\"name\":\"" + st[0] + "\",\"unique_id\":\"" + slug + st[1] + "\",\"state_topic\":\"" + root + "/volume/" + st[2] + "/state\",\"command_topic\":\"" + root + "/volume/" + st[2] + "/set\",\"min\":0,\"max\":100,\"step\":1,\"unit_of_measurement\":\"%\"," + avail + dev + "}";
            msgs.add(new String[]{"homeassistant/number/" + slug + st[1] + "/config", cfg});
        }
        String rcfg = "{\"name\":\"Rotation\",\"unique_id\":\"" + slug + "_rotation\",\"state_topic\":\"" + root + "/rotation/state\",\"command_topic\":\"" + root + "/rotation/set\",\"options\":[\"0\",\"90\",\"180\",\"270\"]," + avail + dev + "}";
        msgs.add(new String[]{"homeassistant/select/" + slug + "_rotation/config", rcfg});
        String rbcfg = "{\"name\":\"Reboot\",\"unique_id\":\"" + slug + "_reboot\",\"command_topic\":\"" + root + "/reboot/press\",\"payload_press\":\"PRESS\"," + avail + dev + "}";
        msgs.add(new String[]{"homeassistant/button/" + slug + "_reboot/config", rbcfg});
        // /proc/vendor audio nodes: world-writable, no root needed.
        // Values are the literal file contents ("on"/"off", "local"/"aux").
        if (profile.speakerMuteSupported) {
            String pmcfg = "{\"name\":\"Speaker Mute\",\"unique_id\":\"" + slug + "_proc_mute\",\"state_topic\":\"" + root + "/proc/mute/state\",\"command_topic\":\"" + root + "/proc/mute/set\",\"payload_on\":\"on\",\"payload_off\":\"off\",\"state_on\":\"on\",\"state_off\":\"off\"," + avail + dev + "}";
            msgs.add(new String[]{"homeassistant/switch/" + slug + "_proc_mute/config", pmcfg});
        }
        if (profile.motionSupported) {
            String mcfg = "{\"name\":\"Motion\",\"unique_id\":\"" + slug + "_motion\",\"state_topic\":\"" + root + "/motion\",\"payload_on\":\"ON\",\"payload_off\":\"OFF\",\"device_class\":\"motion\"," + avail + dev + "}";
            msgs.add(new String[]{"homeassistant/binary_sensor/" + slug + "_motion/config", mcfg});
        }
        String wcfg = "{\"name\":\"Wake Lock\",\"unique_id\":\"" + slug + "_wakelock\",\"state_topic\":\"" + root + "/wakelock/state\",\"command_topic\":\"" + root + "/wakelock/set\",\"payload_on\":\"ON\",\"payload_off\":\"OFF\",\"state_on\":\"ON\",\"state_off\":\"OFF\"," + avail + dev + "}";
        msgs.add(new String[]{"homeassistant/switch/" + slug + "_wakelock/config", wcfg});
        String scfg = "{\"name\":\"Wake Screen with Button\",\"unique_id\":\"" + slug + "_wakescreen\",\"state_topic\":\"" + root + "/wakescreen/state\",\"command_topic\":\"" + root + "/wakescreen/set\",\"payload_on\":\"ON\",\"payload_off\":\"OFF\",\"state_on\":\"ON\",\"state_off\":\"OFF\"," + avail + dev + "}";
        msgs.add(new String[]{"homeassistant/switch/" + slug + "_wakescreen/config", scfg});
        // The old Serial Debug switch was replaced by the global
        // Debug switch -- publish an empty config for the old entity
        // so HA removes it instead of leaving it stale.
        msgs.add(new String[]{"homeassistant/switch/" + slug + "_serial_debug/config", ""});
        String dcfg = "{\"name\":\"Debug\",\"unique_id\":\"" + slug + "_debug\",\"state_topic\":\"" + root + "/debug/state\",\"command_topic\":\"" + root + "/debug/set\",\"payload_on\":\"ON\",\"payload_off\":\"OFF\",\"state_on\":\"ON\",\"state_off\":\"OFF\"," + avail + dev + "}";
        msgs.add(new String[]{"homeassistant/switch/" + slug + "_debug/config", dcfg});
        return msgs;
    }

    // The relay switch discovery for one relay, honouring its mode:
    // momentary mode publishes an empty config so HA removes the
    // switch entity (the press event entity is separate and stays).
    // Called from discoveryMessages and by the service (through
    // MqttPublisher) on a mode change.
    public static String[] relaySwitchMessage(DeviceProfile profile, boolean momentary,
                                              String slug, String deviceName, String root, int i) {
        if (momentary) {
            return new String[]{"homeassistant/switch/" + slug + "_relay_" + i + "/config", ""};
        }
        String dev = MqttTopics.deviceJson(slug, deviceName, profile);
        String avail = MqttTopics.availabilityJson(root);
        String cfg = "{\"name\":\"Relay " + i + "\",\"unique_id\":\"" + slug + "_relay_" + i + "\",\"state_topic\":\"" + root + "/relay/" + i + "\",\"command_topic\":\"" + root + "/relay/" + i + "/set\",\"payload_on\":\"ON\",\"payload_off\":\"OFF\",\"state_on\":\"ON\",\"state_off\":\"OFF\"," + avail + dev + "}";
        return new String[]{"homeassistant/switch/" + slug + "_relay_" + i + "/config", cfg};
    }
}
