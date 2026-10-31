package com.misha.f5daemon;
import android.util.Log;
import java.io.*;
import java.net.Socket;
// Minimal MQTT 3.1.1 client, QoS 0, no dependencies.
// Paho was removed: its ResourceBundle log properties are not in a dex,
// so MqttClient construction crashed on Android with MissingResourceException.
// Naming is universal, not F5-specific: displayed entity names have no
// prefix, topics are tuyacontroller/<device-slug>/..., unique_ids are
// <device-slug>_..., and the HA device name is the panel's Android name.
// The sensor is Motion, not Presence: see README research note.
// Availability uses a Last Will (offline, retained) plus an online
// retained message on connect, with keepalive 60 and a 30 s PINGREQ from
// the service, so a dead daemon is detected by the broker within ~90 s.
// Option switches (Wake Lock, Wake Screen) are command topics the daemon
// subscribes to; HA toggles update the same prefs the app config edits.
public class MqttPublisher {
    private static final String TAG = "F5Daemon";
    public interface CommandListener { void onCommand(String topic, String payload); }
    private Socket socket; private OutputStream out; private InputStream in;
    private String host; private int port; private String user; private String pass; private String deviceName; private String slug; private String root;
    private boolean connected = false;
    private CommandListener listener;
    private Thread readerThread;
    public void setCommandListener(CommandListener l) { listener = l; }
    public static String slug(String name) {
        if (name == null || name.isEmpty()) return "panel";
        String s = name.toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return s.isEmpty() ? "panel" : s;
    }
    public String topicRoot() { return root; }
    public synchronized void connect(String host, int port, String user, String pass, String deviceName) throws IOException {
        if (connected && socket != null && socket.isConnected() && !socket.isClosed()) return;
        closeQuietly();
        this.host = host; this.port = port; this.user = user; this.pass = pass; this.deviceName = deviceName;
        this.slug = slug(deviceName); this.root = "tuyacontroller/" + slug;
        socket = new Socket(host, port); socket.setSoTimeout(5000);
        out = socket.getOutputStream(); in = socket.getInputStream();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeString(body, "MQTT"); body.write(4);
        int flags = 0x02; // clean session
        if (user != null && !user.isEmpty()) flags |= 0x80;
        if (pass != null && !pass.isEmpty()) flags |= 0x40;
        flags |= 0x04; // will flag, QoS 0, retain set below in will payload handling
        body.write(flags); body.write(0); body.write(60); // keepalive 60 s
        writeString(body, "tuyacontroller-" + slug); // per-device client id: a fixed id would kick a second panel off the broker
        writeString(body, root + "/availability"); writeString(body, "offline");
        if (user != null && !user.isEmpty()) writeString(body, user);
        if (pass != null && !pass.isEmpty()) writeString(body, pass);
        ByteArrayOutputStream pkt = new ByteArrayOutputStream();
        pkt.write(0x10); writeRemainingLength(pkt, body.size()); pkt.write(body.toByteArray());
        // Will retain bit is in the CONNECT flags byte, not the packet header:
        // rebuild flags with retain (0x20) -- flags byte is at body offset 7
        // (2 protocol name len + 4 "MQTT" + 1 level). Set it before sending.
        byte[] bodyBytes = body.toByteArray(); bodyBytes[7] |= 0x20;
        pkt.reset(); pkt.write(0x10); writeRemainingLength(pkt, bodyBytes.length); pkt.write(bodyBytes);
        out.write(pkt.toByteArray()); out.flush();
        int type = in.read(); int rem = in.read(); int flags2 = in.read(); int rc = in.read();
        if (type != 0x20 || rc != 0) throw new IOException("MQTT CONNACK failed type=" + type + " rc=" + rc);
        socket.setSoTimeout(0);
        connected = true;
        Log.i(TAG, "mqtt connected to " + host + ":" + port + " root=" + root);
        startReader(socket);
        // Subscribe to everything under root, and filter in the service:
        // only topics ending in /set or /press are commands. State topics
        // are published by the daemon itself and must be ignored on receipt,
        // otherwise the daemon would act on its own state publishes.
        subscribe(root + "/#");
        publishAvailability("online");
        publishDiscovery();
    }
    public synchronized void disconnect() {
        if (connected) { try { publishAvailability("offline"); } catch (Exception ignored) {} }
        closeQuietly();
    }
    public synchronized boolean connected() { return connected; }
    private void writeString(OutputStream o, String s) throws IOException {
        byte[] b = s.getBytes("UTF-8"); o.write((b.length >> 8) & 0xff); o.write(b.length & 0xff); o.write(b);
    }
    private void writeRemainingLength(OutputStream o, int len) throws IOException {
        do { int d = len % 128; len /= 128; if (len > 0) d |= 0x80; o.write(d); } while (len > 0);
    }
    private int readRemainingLength(InputStream in) throws IOException {
        int mult = 1, len = 0, b;
        do { b = in.read(); if (b < 0) throw new EOFException(); len += (b & 0x7f) * mult; mult *= 128; } while ((b & 0x80) != 0);
        return len;
    }
    private static String json(String s) { return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\""); }
    private synchronized void sendPacket(byte[] pkt) throws IOException { out.write(pkt); out.flush(); }
    public synchronized void ping() throws IOException {
        if (!connected) return;
        sendPacket(new byte[]{(byte) 0xc0, 0x00});
    }
    private void subscribe(String topic) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(0); body.write(1); // packet id
        writeString(body, topic); body.write(0); // QoS 0
        ByteArrayOutputStream pkt = new ByteArrayOutputStream();
        pkt.write(0x82); writeRemainingLength(pkt, body.size()); pkt.write(body.toByteArray());
        sendPacket(pkt.toByteArray());
    }
    private void startReader(final Socket s) {
        readerThread = new Thread(() -> {
            try {
                InputStream rin = s.getInputStream();
                while (socket == s && !s.isClosed()) {
                    int header = rin.read(); if (header < 0) break;
                    int len = readRemainingLength(rin);
                    byte[] data = new byte[len]; int off = 0;
                    while (off < len) { int n = rin.read(data, off, len - off); if (n < 0) throw new EOFException(); off += n; }
                    int type = (header >> 4) & 0x0f;
                    if (type == 3 && listener != null) { // PUBLISH QoS 0
                        int tlen = ((data[0] & 0xff) << 8) | (data[1] & 0xff);
                        String topic = new String(data, 2, tlen, "UTF-8");
                        String payload = new String(data, 2 + tlen, data.length - 2 - tlen, "UTF-8");
                        listener.onCommand(topic, payload);
                    }
                    // PINGRESP (13) and others are ignored
                }
            } catch (Exception e) { Log.w(TAG, "mqtt reader ended: " + e); }
            synchronized (MqttPublisher.this) { if (socket == s) { connected = false; closeQuietly(); } }
        });
        readerThread.setDaemon(true); readerThread.start();
    }
    private synchronized void publishRaw(String topic, String payload, boolean retain) throws IOException {
        try { doPublish(topic, payload, retain); }
        catch (IOException e) {
            connected = false;
            connect(host, port, user, pass, deviceName);
            doPublish(topic, payload, retain);
        }
    }
    private void doPublish(String topic, String payload, boolean retain) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeString(body, topic); body.write(payload.getBytes("UTF-8"));
        ByteArrayOutputStream pkt = new ByteArrayOutputStream();
        pkt.write(0x30 | (retain ? 1 : 0)); writeRemainingLength(pkt, body.size()); pkt.write(body.toByteArray());
        sendPacket(pkt.toByteArray());
    }
    public synchronized void publishAvailability(String v) throws IOException { doPublish(root + "/availability", v, true); }
    public synchronized void publishOptionStates(boolean wakelock, boolean wakescreen, boolean serialDebug) throws IOException {
        doPublish(root + "/wakelock/state", wakelock ? "ON" : "OFF", true);
        doPublish(root + "/wakescreen/state", wakescreen ? "ON" : "OFF", true);
        doPublish(root + "/serial_debug/state", serialDebug ? "ON" : "OFF", true);
    }
    public synchronized void publishDiscovery() throws IOException {
        // Remove old f5-* entities (v1 and v2) so they do not linger.
        for (int i = 1; i <= 8; i++) doPublish("homeassistant/event/f5_button_" + i + "/config", "", true);
        for (int i = 1; i <= 3; i++) {
            doPublish("homeassistant/binary_sensor/f5_relay_" + i + "/config", "", true);
            doPublish("f5/relay/" + i, "", true);
        }
        for (String l : new String[]{"l1", "l2", "l3"}) {
            doPublish("homeassistant/binary_sensor/f5_" + l + "/config", "", true);
            doPublish("f5/" + l, "", true);
        }
        doPublish("homeassistant/binary_sensor/f5_presence/config", "", true);
        doPublish("f5/presence", "", true);
        String dev = "\"device\":{\"identifiers\":[\"" + slug + "\"],\"name\":\"" + json(deviceName) + "\",\"model\":\"F5-3\",\"manufacturer\":\"sznaner\"}";
        String avail = "\"availability_topic\":\"" + root + "/availability\",\"payload_available\":\"online\",\"payload_not_available\":\"offline\",";
        for (int i = 4; i <= 8; i++) {
            String cfg = "{\"name\":\"Button " + i + "\",\"unique_id\":\"" + slug + "_button_" + i + "\",\"state_topic\":\"" + root + "/button/" + i + "\",\"event_types\":[\"press\"]," + avail + dev + "}";
            doPublish("homeassistant/event/" + slug + "_button_" + i + "/config", cfg, true);
        }
        // v5: relays are switches, not binary_sensors (outbound MCU frames
        // found Oct 30, 2026 in McuTestActivity, proven live Oct 30).
        // Remove the old binary_sensor discovery so it does not linger.
        for (int i = 1; i <= 3; i++) {
            doPublish("homeassistant/binary_sensor/" + slug + "_relay_" + i + "/config", "", true);
            String cfg = "{\"name\":\"Relay " + i + "\",\"unique_id\":\"" + slug + "_relay_" + i + "\",\"state_topic\":\"" + root + "/relay/" + i + "\",\"command_topic\":\"" + root + "/relay/" + i + "/set\",\"payload_on\":\"ON\",\"payload_off\":\"OFF\",\"state_on\":\"ON\",\"state_off\":\"OFF\"," + avail + dev + "}";
            doPublish("homeassistant/switch/" + slug + "_relay_" + i + "/config", cfg, true);
            String ecfg = "{\"name\":\"Relay " + i + " press\",\"unique_id\":\"" + slug + "_relay_" + i + "_press\",\"state_topic\":\"" + root + "/relay/" + i + "/event\",\"event_types\":[\"press\"]," + avail + dev + "}";
            doPublish("homeassistant/event/" + slug + "_relay_" + i + "_press/config", ecfg, true);
        }
        // Night light: MCU side LED bar, brightness only, no colour
        // (F5SmallLightUtil: setSwitch + setBright 0-100, no colour API).
        // State is optimistic: the MCU sends no status frame for it.
        String ncfg = "{\"name\":\"Night Light\",\"unique_id\":\"" + slug + "_night_light\",\"state_topic\":\"" + root + "/nightlight/state\",\"command_topic\":\"" + root + "/nightlight/set\",\"brightness_state_topic\":\"" + root + "/nightlight/brightness\",\"brightness_command_topic\":\"" + root + "/nightlight/brightness/set\",\"brightness_scale\":100,\"payload_on\":\"ON\",\"payload_off\":\"OFF\"," + avail + dev + "}";
        doPublish("homeassistant/light/" + slug + "_night_light/config", ncfg, true);
        // v6 removals (user does not use these): Music Mute, Audio
        // Source, Horn -- publish empty discovery so HA removes them.
        doPublish("homeassistant/switch/" + slug + "_volume_mute/config", "", true);
        doPublish("homeassistant/select/" + slug + "_proc_aux/config", "", true);
        doPublish("homeassistant/switch/" + slug + "_proc_horn/config", "", true);
        // Device-level controls (v5). Brightness/volume state is polled
        // by the service, so changes from Settings/Kiosk update HA too.
        String scfg2 = "{\"name\":\"Screen\",\"unique_id\":\"" + slug + "_screen\",\"state_topic\":\"" + root + "/screen/state\",\"command_topic\":\"" + root + "/screen/set\",\"payload_on\":\"ON\",\"payload_off\":\"OFF\",\"state_on\":\"ON\",\"state_off\":\"OFF\"," + avail + dev + "}";
        doPublish("homeassistant/switch/" + slug + "_screen/config", scfg2, true);
        String bcfg = "{\"name\":\"Screen Brightness\",\"unique_id\":\"" + slug + "_brightness\",\"state_topic\":\"" + root + "/brightness/state\",\"command_topic\":\"" + root + "/brightness/set\",\"min\":0,\"max\":100,\"step\":1,\"unit_of_measurement\":\"%\"," + avail + dev + "}";
        doPublish("homeassistant/number/" + slug + "_brightness/config", bcfg, true);
        String vcfg = "{\"name\":\"Media Volume\",\"unique_id\":\"" + slug + "_volume_music\",\"state_topic\":\"" + root + "/volume/state\",\"command_topic\":\"" + root + "/volume/set\",\"min\":0,\"max\":100,\"step\":1,\"unit_of_measurement\":\"%\"," + avail + dev + "}";
        doPublish("homeassistant/number/" + slug + "_volume_music/config", vcfg, true);
        String[][] streams = {{"System Volume", "_volume_system", "system"}, {"Notification Volume", "_volume_notification", "notification"}, {"Alarm Volume", "_volume_alarm", "alarm"}, {"Ring Volume", "_volume_ring", "ring"}, {"Voice Call Volume", "_volume_voice", "voice"}};
        for (String[] st : streams) {
            String cfg = "{\"name\":\"" + st[0] + "\",\"unique_id\":\"" + slug + st[1] + "\",\"state_topic\":\"" + root + "/volume/" + st[2] + "/state\",\"command_topic\":\"" + root + "/volume/" + st[2] + "/set\",\"min\":0,\"max\":100,\"step\":1,\"unit_of_measurement\":\"%\"," + avail + dev + "}";
            doPublish("homeassistant/number/" + slug + st[1] + "/config", cfg, true);
        }
        String rcfg = "{\"name\":\"Rotation\",\"unique_id\":\"" + slug + "_rotation\",\"state_topic\":\"" + root + "/rotation/state\",\"command_topic\":\"" + root + "/rotation/set\",\"options\":[\"0\",\"90\",\"180\",\"270\"]," + avail + dev + "}";
        doPublish("homeassistant/select/" + slug + "_rotation/config", rcfg, true);
        String rbcfg = "{\"name\":\"Reboot\",\"unique_id\":\"" + slug + "_reboot\",\"command_topic\":\"" + root + "/reboot/press\",\"payload_press\":\"PRESS\"," + avail + dev + "}";
        doPublish("homeassistant/button/" + slug + "_reboot/config", rbcfg, true);
        // /proc/vendor audio nodes: world-writable, no root needed.
        // Values are the literal file contents ("on"/"off", "local"/"aux").
        String pmcfg = "{\"name\":\"Speaker Mute\",\"unique_id\":\"" + slug + "_proc_mute\",\"state_topic\":\"" + root + "/proc/mute/state\",\"command_topic\":\"" + root + "/proc/mute/set\",\"payload_on\":\"on\",\"payload_off\":\"off\",\"state_on\":\"on\",\"state_off\":\"off\"," + avail + dev + "}";
        doPublish("homeassistant/switch/" + slug + "_proc_mute/config", pmcfg, true);
        String mcfg = "{\"name\":\"Motion\",\"unique_id\":\"" + slug + "_motion\",\"state_topic\":\"" + root + "/motion\",\"payload_on\":\"ON\",\"payload_off\":\"OFF\",\"device_class\":\"motion\"," + avail + dev + "}";
        doPublish("homeassistant/binary_sensor/" + slug + "_motion/config", mcfg, true);
        String wcfg = "{\"name\":\"Wake Lock\",\"unique_id\":\"" + slug + "_wakelock\",\"state_topic\":\"" + root + "/wakelock/state\",\"command_topic\":\"" + root + "/wakelock/set\",\"payload_on\":\"ON\",\"payload_off\":\"OFF\",\"state_on\":\"ON\",\"state_off\":\"OFF\"," + avail + dev + "}";
        doPublish("homeassistant/switch/" + slug + "_wakelock/config", wcfg, true);
        String scfg = "{\"name\":\"Wake Screen with Button\",\"unique_id\":\"" + slug + "_wakescreen\",\"state_topic\":\"" + root + "/wakescreen/state\",\"command_topic\":\"" + root + "/wakescreen/set\",\"payload_on\":\"ON\",\"payload_off\":\"OFF\",\"state_on\":\"ON\",\"state_off\":\"OFF\"," + avail + dev + "}";
        doPublish("homeassistant/switch/" + slug + "_wakescreen/config", scfg, true);
        String dcfg = "{\"name\":\"Serial Debug\",\"unique_id\":\"" + slug + "_serial_debug\",\"state_topic\":\"" + root + "/serial_debug/state\",\"command_topic\":\"" + root + "/serial_debug/set\",\"payload_on\":\"ON\",\"payload_off\":\"OFF\",\"state_on\":\"ON\",\"state_off\":\"OFF\"," + avail + dev + "}";
        doPublish("homeassistant/switch/" + slug + "_serial_debug/config", dcfg, true);
        Log.i(TAG, "mqtt discovery published, device=" + deviceName);
    }
    public synchronized void buttonPress(int button) { try { publishRaw(root + "/button/" + button, "{\"event_type\":\"press\"}", false); } catch (Exception e) { Log.w(TAG, "mqtt press failed: " + e, e); } }
    public synchronized void relay(int relay, boolean on) { try { publishRaw(root + "/relay/" + relay, on ? "ON" : "OFF", true); } catch (Exception e) { Log.w(TAG, "mqtt relay failed: " + e, e); } }
    public synchronized void relayPress(int relay) { try { publishRaw(root + "/relay/" + relay + "/event", "{\"event_type\":\"press\"}", false); } catch (Exception e) { Log.w(TAG, "mqtt relay press failed: " + e, e); } }
    public synchronized void motion(boolean present) { try { publishRaw(root + "/motion", present ? "ON" : "OFF", true); } catch (Exception e) { Log.w(TAG, "mqtt motion failed: " + e, e); } }
    public synchronized void nightLight(boolean on, int brightness) { try { publishRaw(root + "/nightlight/state", on ? "ON" : "OFF", true); publishRaw(root + "/nightlight/brightness", String.valueOf(brightness), true); } catch (Exception e) { Log.w(TAG, "mqtt nightlight failed: " + e, e); } }
    public synchronized void screen(boolean on) { try { publishRaw(root + "/screen/state", on ? "ON" : "OFF", true); } catch (Exception e) { Log.w(TAG, "mqtt screen failed: " + e, e); } }
    public synchronized void brightness(int pct) { try { publishRaw(root + "/brightness/state", String.valueOf(pct), true); } catch (Exception e) { Log.w(TAG, "mqtt brightness failed: " + e, e); } }
    public synchronized void volume(int pct, boolean muted) { try { publishRaw(root + "/volume/state", String.valueOf(pct), true); } catch (Exception e) { Log.w(TAG, "mqtt volume failed: " + e, e); } }
    public synchronized void volumeStream(String name, int pct) { try { if (pct >= 0) publishRaw(root + "/volume/" + name + "/state", String.valueOf(pct), true); } catch (Exception e) { Log.w(TAG, "mqtt volume stream failed: " + e, e); } }
    public synchronized void rotation(int deg) { try { publishRaw(root + "/rotation/state", String.valueOf(deg), true); } catch (Exception e) { Log.w(TAG, "mqtt rotation failed: " + e, e); } }
    public synchronized void procState(String name, String value) { try { publishRaw(root + "/proc/" + name + "/state", value, true); } catch (Exception e) { Log.w(TAG, "mqtt proc failed: " + e, e); } }
    private void closeQuietly() { try { if (socket != null) socket.close(); } catch (Exception ignored) {} socket = null; connected = false; }
}
