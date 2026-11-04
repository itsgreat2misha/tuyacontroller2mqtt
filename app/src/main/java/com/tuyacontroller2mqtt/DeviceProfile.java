package com.tuyacontroller2mqtt;
// A device profile describes one supported panel model: how its MCU is
// reached and what hardware it exposes. Everything model-specific that
// used to be literals scattered through ButtonService and MqttPublisher
// (serial device, the "F5-3"/"sznaner" strings in Home Assistant
// discovery, button/relay counts) lives here instead.
//
// Supporting a new panel model means adding a profile here (plus any
// new frame codes its MCU uses) and a row in the README's Supported
// devices table. See CONTRIBUTING.md.
//
// Only the F5-3 profile exists today and it is the only tested one;
// active() therefore returns it unconditionally. When a second profile
// lands, selection can key off Build.MODEL.
public final class DeviceProfile {
    public final String id;
    public final String model;
    public final String manufacturer;
    public final String serialPath;
    public final int baud;
    public final int firstButton;
    public final int lastButton;
    public final int relayCount;
    public final boolean nightLightSupported;

    public DeviceProfile(String id, String model, String manufacturer,
                         String serialPath, int baud,
                         int firstButton, int lastButton, int relayCount,
                         boolean nightLightSupported) {
        this.id = id;
        this.model = model;
        this.manufacturer = manufacturer;
        this.serialPath = serialPath;
        this.baud = baud;
        this.firstButton = firstButton;
        this.lastButton = lastButton;
        this.relayCount = relayCount;
        this.nightLightSupported = nightLightSupported;
    }

    // F5-3: Allwinner A133, Android 11. Momentary buttons are numbered
    // 4-8 (the frame id IS the button number); relays are 1-3 (a 4th
    // relay channel appears in query batches but is query-only and is
    // not exposed). The MCU speaks 7-byte frames on the serial device.
    public static final DeviceProfile F5_3 = new DeviceProfile(
        "f5_3", "F5-3", "sznaner",
        "/dev/ttyAS2", 115200,
        4, 8, 3,
        true);

    public static DeviceProfile active() { return F5_3; }
}
