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
        subscribe(root + "/+/set");
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
    public synchronized void publishOptionStates(boolean wakelock, boolean wakescreen) throws IOException {
        doPublish(root + "/wakelock/state", wakelock ? "ON" : "OFF", true);
        doPublish(root + "/wakescreen/state", wakescreen ? "ON" : "OFF", true);
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
        for (int i = 1; i <= 3; i++) {
            String cfg = "{\"name\":\"Relay " + i + "\",\"unique_id\":\"" + slug + "_relay_" + i + "\",\"state_topic\":\"" + root + "/relay/" + i + "\",\"payload_on\":\"ON\",\"payload_off\":\"OFF\"," + avail + dev + "}";
            doPublish("homeassistant/binary_sensor/" + slug + "_relay_" + i + "/config", cfg, true);
        }
        String mcfg = "{\"name\":\"Motion\",\"unique_id\":\"" + slug + "_motion\",\"state_topic\":\"" + root + "/motion\",\"payload_on\":\"ON\",\"payload_off\":\"OFF\",\"device_class\":\"motion\"," + avail + dev + "}";
        doPublish("homeassistant/binary_sensor/" + slug + "_motion/config", mcfg, true);
        String wcfg = "{\"name\":\"Wake Lock\",\"unique_id\":\"" + slug + "_wakelock\",\"state_topic\":\"" + root + "/wakelock/state\",\"command_topic\":\"" + root + "/wakelock/set\",\"payload_on\":\"ON\",\"payload_off\":\"OFF\",\"state_on\":\"ON\",\"state_off\":\"OFF\"," + avail + dev + "}";
        doPublish("homeassistant/switch/" + slug + "_wakelock/config", wcfg, true);
        String scfg = "{\"name\":\"Wake Screen\",\"unique_id\":\"" + slug + "_wakescreen\",\"state_topic\":\"" + root + "/wakescreen/state\",\"command_topic\":\"" + root + "/wakescreen/set\",\"payload_on\":\"ON\",\"payload_off\":\"OFF\",\"state_on\":\"ON\",\"state_off\":\"OFF\"," + avail + dev + "}";
        doPublish("homeassistant/switch/" + slug + "_wakescreen/config", scfg, true);
        Log.i(TAG, "mqtt discovery published, device=" + deviceName);
    }
    public synchronized void buttonPress(int button) { try { publishRaw(root + "/button/" + button, "{\"event_type\":\"press\"}", false); } catch (Exception e) { Log.w(TAG, "mqtt press failed: " + e, e); } }
    public synchronized void relay(int relay, boolean on) { try { publishRaw(root + "/relay/" + relay, on ? "ON" : "OFF", true); } catch (Exception e) { Log.w(TAG, "mqtt relay failed: " + e, e); } }
    public synchronized void motion(boolean present) { try { publishRaw(root + "/motion", present ? "ON" : "OFF", true); } catch (Exception e) { Log.w(TAG, "mqtt motion failed: " + e, e); } }
    private void closeQuietly() { try { if (socket != null) socket.close(); } catch (Exception ignored) {} socket = null; connected = false; }
}
