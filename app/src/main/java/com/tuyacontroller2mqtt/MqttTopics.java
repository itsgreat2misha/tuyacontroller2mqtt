package com.tuyacontroller2mqtt;
// Pure naming/discovery helpers with no Android dependencies, so they
// can be unit-tested on a plain JVM (see tests/).
//
// Naming is universal, not model-specific: displayed entity names have
// no prefix, topics are tuyacontroller/<device-slug>/..., unique_ids
// are <device-slug>_..., and the HA device name is the panel's Android
// device name. These names are a compatibility surface: changing them
// orphans existing Home Assistant entities, so they must not change
// across the package rename or future releases.
public final class MqttTopics {
    private MqttTopics() {}

    public static String slug(String name) {
        if (name == null || name.isEmpty()) return "panel";
        String s = name.toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return s.isEmpty() ? "panel" : s;
    }

    public static String topicRoot(String slug) { return "tuyacontroller/" + slug; }

    public static String clientId(String slug) { return "tuyacontroller-" + slug; }

    public static String escapeJson(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // The "device" block shared by every discovery config. Model and
    // manufacturer come from the device profile, never from literals
    // at the call site.
    public static String deviceJson(String slug, String deviceName, DeviceProfile profile) {
        return "\"device\":{\"identifiers\":[\"" + slug + "\"],\"name\":\"" + escapeJson(deviceName)
            + "\",\"model\":\"" + escapeJson(profile.model)
            + "\",\"manufacturer\":\"" + escapeJson(profile.manufacturer) + "\"}";
    }

    public static String availabilityJson(String root) {
        return "\"availability_topic\":\"" + root + "/availability\",\"payload_available\":\"online\",\"payload_not_available\":\"offline\",";
    }
}
