package com.misha.f5daemon;
import android.util.Log;
import java.io.*;
import java.net.Socket;
// Minimal MQTT 3.1.1 client, QoS 0, no dependencies.
// Paho was removed: its ResourceBundle log properties are not in a dex,
// so MqttClient construction crashed on Android with MissingResourceException.
// Keepalive is 0 (disabled) so an idle socket is not closed by the broker;
// a failed publish reconnects once and retries.
// Entity numbering (v2, Oct 29 2026): the MCU's own id byte IS the number.
// Relays 1-3 = old L1-L3 (codes 01-03, old buttons 8-6), Buttons 4-8 =
// old momentary buttons 5-1 (codes 04-08, reversed). So a momentary frame's
// id byte and a relay frame's code byte are already the new entity number.
public class MqttPublisher {
    private static final String TAG = "F5Daemon";
    private Socket socket; private OutputStream out; private InputStream in;
    private String host; private int port; private String user; private String pass; private String deviceName;
    private boolean connected = false;
    public synchronized void connect(String host, int port, String user, String pass, String deviceName) throws IOException {
        if (connected && socket != null && socket.isConnected() && !socket.isClosed()) return;
        closeQuietly();
        this.host = host; this.port = port; this.user = user; this.pass = pass; this.deviceName = deviceName;
        socket = new Socket(host, port); socket.setSoTimeout(5000);
        out = socket.getOutputStream(); in = socket.getInputStream();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeString(body, "MQTT"); body.write(4);
        int flags = 0x02;
        if (user != null && !user.isEmpty()) flags |= 0x80;
        if (pass != null && !pass.isEmpty()) flags |= 0x40;
        body.write(flags); body.write(0); body.write(0);
        writeString(body, "f5-panel-daemon");
        if (user != null && !user.isEmpty()) writeString(body, user);
        if (pass != null && !pass.isEmpty()) writeString(body, pass);
        ByteArrayOutputStream pkt = new ByteArrayOutputStream();
        pkt.write(0x10); writeRemainingLength(pkt, body.size()); pkt.write(body.toByteArray());
        out.write(pkt.toByteArray()); out.flush();
        int type = in.read(); int rem = in.read(); int flags2 = in.read(); int rc = in.read();
        if (type != 0x20 || rc != 0) throw new IOException("MQTT CONNACK failed type=" + type + " rc=" + rc);
        socket.setSoTimeout(0);
        connected = true;
        Log.i(TAG, "mqtt connected to " + host + ":" + port);
        publishDiscovery();
    }
    public synchronized void disconnect() { closeQuietly(); }
    public synchronized boolean connected() { return connected; }
    private void writeString(OutputStream o, String s) throws IOException {
        byte[] b = s.getBytes("UTF-8"); o.write((b.length >> 8) & 0xff); o.write(b.length & 0xff); o.write(b);
    }
    private void writeRemainingLength(OutputStream o, int len) throws IOException {
        do { int d = len % 128; len /= 128; if (len > 0) d |= 0x80; o.write(d); } while (len > 0);
    }
    private static String json(String s) { return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\""); }
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
        out.write(pkt.toByteArray()); out.flush();
    }
    public synchronized void publishDiscovery() throws IOException {
        // Remove v1 entities first: empty retained discovery deletes them
        // from HA, otherwise the old button 1-5 / l1-l3 entities linger
        // as unavailable alongside the renumbered ones.
        for (int i = 1; i <= 5; i++) doPublish("homeassistant/event/f5_button_" + i + "/config", "", true);
        for (String l : new String[]{"l1", "l2", "l3"}) {
            doPublish("homeassistant/binary_sensor/f5_" + l + "/config", "", true);
            doPublish("f5/" + l, "", true);
        }
        String dev = "\"device\":{\"identifiers\":[\"f5-panel\"],\"name\":\"" + json(deviceName) + "\",\"model\":\"F5-3\",\"manufacturer\":\"sznaner\"}";
        for (int i = 4; i <= 8; i++) {
            String cfg = "{\"name\":\"F5 Button " + i + "\",\"unique_id\":\"f5_button_" + i + "\",\"state_topic\":\"f5/button/" + i + "\",\"event_types\":[\"press\"]," + dev + "}";
            doPublish("homeassistant/event/f5_button_" + i + "/config", cfg, true);
        }
        for (int i = 1; i <= 3; i++) {
            String cfg = "{\"name\":\"F5 Relay " + i + "\",\"unique_id\":\"f5_relay_" + i + "\",\"state_topic\":\"f5/relay/" + i + "\",\"payload_on\":\"ON\",\"payload_off\":\"OFF\"," + dev + "}";
            doPublish("homeassistant/binary_sensor/f5_relay_" + i + "/config", cfg, true);
        }
        String pcfg = "{\"name\":\"F5 Presence\",\"unique_id\":\"f5_presence\",\"state_topic\":\"f5/presence\",\"payload_on\":\"ON\",\"payload_off\":\"OFF\",\"device_class\":\"motion\"," + dev + "}";
        doPublish("homeassistant/binary_sensor/f5_presence/config", pcfg, true);
        Log.i(TAG, "mqtt discovery published, device=" + deviceName);
    }
    public synchronized void buttonPress(int button) { try { publishRaw("f5/button/" + button, "{\"event_type\":\"press\"}", false); } catch (Exception e) { Log.w(TAG, "mqtt press failed: " + e, e); } }
    public synchronized void relay(int relay, boolean on) { try { publishRaw("f5/relay/" + relay, on ? "ON" : "OFF", true); } catch (Exception e) { Log.w(TAG, "mqtt relay failed: " + e, e); } }
    public synchronized void presence(boolean present) { try { publishRaw("f5/presence", present ? "ON" : "OFF", true); } catch (Exception e) { Log.w(TAG, "mqtt presence failed: " + e, e); } }
    private void closeQuietly() { try { if (socket != null) socket.close(); } catch (Exception ignored) {} socket = null; connected = false; }
}
