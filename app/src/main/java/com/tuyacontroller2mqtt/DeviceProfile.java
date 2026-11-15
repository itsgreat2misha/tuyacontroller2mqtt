package com.tuyacontroller2mqtt;
// A device profile describes one supported panel model: how its MCU is
// reached and what hardware it exposes. Everything model-specific that
// used to be literals scattered through ButtonService and MqttPublisher
// (serial device, the "F5-3"/"sznaner" strings in Home Assistant
// discovery, button/relay counts, the MCU frame codes, the /proc node
// paths) lives here instead. Frame building/parsing reads these values
// through FrameCodec, and discovery publication reads the capability
// flags through Discovery, so a model whose codes differ is a profile
// entry, not call-site branches.
//
// Supporting a new panel model means adding a profile here and a row
// in the README's Supported devices table. See CONTRIBUTING.md.
//
// Only the F5-3 profile exists today and it is the only tested one;
// active() therefore returns it unconditionally. When a second profile
// lands, selection can key off Build.MODEL.
public final class DeviceProfile {
    public final String model;
    public final String manufacturer;
    public final String serialPath;
    public final int baud;
    public final int firstButton;
    public final int lastButton;
    public final int relayCount;
    public final boolean nightLightSupported;
    public final boolean motionSupported;
    public final boolean speakerMuteSupported;
    // MCU frame format: fixed start/end bytes and frame length, the
    // function codes at byte 2 (motion, night light), the query code
    // and the query-batch end marker at byte 3, and the relay code
    // base (a relay's code is relayCodeBase + relay number).
    public final byte frameStart;
    public final byte frameEnd;
    public final int frameLength;
    public final int motionOnCode;
    public final int motionOffCode;
    public final int nightLightCode;
    public final int queryCode;
    public final int queryEndMarkerCode;
    public final int relayCodeBase;
    // The /proc node the Speaker Mute switch writes ("on"/"off").
    public final String muteProcPath;

    public DeviceProfile(String model, String manufacturer,
                         String serialPath, int baud,
                         int firstButton, int lastButton, int relayCount,
                         boolean nightLightSupported, boolean motionSupported,
                         boolean speakerMuteSupported,
                         byte frameStart, byte frameEnd, int frameLength,
                         int motionOnCode, int motionOffCode, int nightLightCode,
                         int queryCode, int queryEndMarkerCode, int relayCodeBase,
                         String muteProcPath) {
        this.model = model;
        this.manufacturer = manufacturer;
        this.serialPath = serialPath;
        this.baud = baud;
        this.firstButton = firstButton;
        this.lastButton = lastButton;
        this.relayCount = relayCount;
        this.nightLightSupported = nightLightSupported;
        this.motionSupported = motionSupported;
        this.speakerMuteSupported = speakerMuteSupported;
        this.frameStart = frameStart;
        this.frameEnd = frameEnd;
        this.frameLength = frameLength;
        this.motionOnCode = motionOnCode;
        this.motionOffCode = motionOffCode;
        this.nightLightCode = nightLightCode;
        this.queryCode = queryCode;
        this.queryEndMarkerCode = queryEndMarkerCode;
        this.relayCodeBase = relayCodeBase;
        this.muteProcPath = muteProcPath;
    }

    // F5-3: Allwinner A133, Android 11. Momentary buttons are numbered
    // 4-8 (the frame id IS the button number); relays are 1-3 (a 4th
    // relay channel appears in query batches but is query-only and is
    // not exposed). The MCU speaks 7-byte frames on the serial device.
    // Relays are numbered 1..relayCount. Anything outside that range
    // (on the F5-3, relay 4 appears in query batches only) is not a
    // command target.
    public boolean hasRelay(int relay) {
        return relay >= 1 && relay <= relayCount;
    }

    public static final DeviceProfile F5_3 = new DeviceProfile(
        "F5-3", "sznaner",
        "/dev/ttyAS2", 115200,
        4, 8, 3,
        true, true, true,
        (byte) 0xfc, (byte) 0xfe, 7,
        0x1c, 0x0c, 0x1d,
        0xaa, 0xa4, 0xa0,
        "/proc/vendor/mute_manual");

    public static DeviceProfile active() { return F5_3; }
}
