package com.misha.f5daemon;
import android.util.Log;
import java.io.*;
import java.net.Socket;
// Minimal MQTT 3.1.1 client, QoS 0, no dependencies.
// Paho was removed: its ResourceBundle log properties are not in a dex,
// so MqttClient construction crashed on Android with MissingResourceException.
// Keepalive is 0 (disabled) so an idle socket is not closed by the broker;
// a failed publish reconnects once and retries.
public class MqttPublisher {
    private static final String TAG = "F5Daemon";
    private Socket socket; private OutputStream out; private InputStream in;
    private String host; private int port; private String user; private String pass;
    private boolean connected = false;
    public synchronized void connect(String host, int port, String user, String pass) throws IOException {
        if (connected && socket != null && socket.isConnected() && !socket.isClosed()) return;
        closeQuietly();
        this.host = host; this.port = port; this.user = user; this.pass = pass;
        socket = new Socket(host, port); socket.setSoTimeout(5000);
        out = socket.getOutputStream(); in = socket.getInputStream();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeString(body, "MQTT"); body.write(4); // protocol level 4
        int flags = 0x02; // clean session
        if (user != null && !user.isEmpty()) flags |= 0x80;
        if (pass != null && !pass.isEmpty()) flags |= 0x40;
        body.write(flags); body.write(0); body.write(0); // keepalive 0
        writeString(body, "f5-panel-daemon");
        if (user != null && !user.isEmpty()) writeString(body, user);
        if (pass != null && !pass.isEmpty()) writeString(body, pass);
        ByteArrayOutputStream pkt = new ByteArrayOutputStream();
        pkt.write(0x10); writeRemainingLength(pkt, body.size()); pkt.write(body.toByteArray());
        out.write(pkt.toByteArray()); out.flush();
        int type = in.read(); int rem = in.read(); int flags2 = in.read(); int rc = in.read();
        if (type != 0x20 || rc != 0) throw new IOException("MQTT CONNACK failed type=" + type + " rc=" + rc);
        connected = true;
        Log.i(TAG, "mqtt connected to " + host + ":" + port);
        publishDiscovery();
    }
    public synchronized boolean connected() { return connected; }
    private void writeString(OutputStream o, String s) throws IOException {
        byte[] b = s.getBytes("UTF-8"); o.write((b.length >> 8) & 0xff); o.write(b.length & 0xff); o.write(b);
    }
    private void writeRemainingLength(OutputStream o, int len) throws IOException {
        do { int d = len % 128; len /= 128; if (len > 0) d |= 0x80; o.write(d); } while (len > 0);
    }
    private synchronized void publishRaw(String topic, String payload, boolean retain) throws IOException {
        try { doPublish(topic, payload, retain); }
        catch (IOException e) {
            connected = false;
            // one reconnect + retry, then give up and let the next frame retry
            connect(host, port, user, pass);
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
        String dev = "\"device\":{\"identifiers\":[\"f5-panel\"],\"name\":\"F5 Entryway Panel\",\"model\":\"F5-3\",\"manufacturer\":\"sznaner\"}";
        for (int i = 1; i <= 5; i++) {
            String cfg = "{\"name\":\"F5 Button " + i + "\",\"unique_id\":\"f5_button_" + i + "\",\"state_topic\":\"f5/button/" + i + "\",\"event_types\":[\"press\"]," + dev + "}";
            doPublish("homeassistant/event/f5_button_" + i + "/config", cfg, true);
        }
        String[][] toggles = {{"l3","6"},{"l2","7"},{"l1","8"}};
        for (String[] t : toggles) {
            String cfg = "{\"name\":\"F5 " + t[0].toUpperCase() + "\",\"unique_id\":\"f5_" + t[0] + "\",\"state_topic\":\"f5/" + t[0] + "\",\"payload_on\":\"ON\",\"payload_off\":\"OFF\"," + dev + "}";
            doPublish("homeassistant/binary_sensor/f5_" + t[0] + "/config", cfg, true);
        }
        String pcfg = "{\"name\":\"F5 Presence\",\"unique_id\":\"f5_presence\",\"state_topic\":\"f5/presence\",\"payload_on\":\"ON\",\"payload_off\":\"OFF\",\"device_class\":\"motion\"," + dev + "}";
        doPublish("homeassistant/binary_sensor/f5_presence/config", pcfg, true);
        Log.i(TAG, "mqtt discovery published");
    }
    public synchronized void buttonPress(int button) { try { publishRaw("f5/button/" + button, "{\"event_type\":\"press\"}", false); } catch (Exception e) { Log.w(TAG, "mqtt press failed: " + e, e); } }
    public synchronized void toggle(String name, boolean on) { try { publishRaw("f5/" + name, on ? "ON" : "OFF", true); } catch (Exception e) { Log.w(TAG, "mqtt toggle failed: " + e, e); } }
    public synchronized void presence(boolean present) { try { publishRaw("f5/presence", present ? "ON" : "OFF", true); } catch (Exception e) { Log.w(TAG, "mqtt presence failed: " + e, e); } }
    private void closeQuietly() { try { if (socket != null) socket.close(); } catch (Exception ignored) {} socket = null; connected = false; }
}
