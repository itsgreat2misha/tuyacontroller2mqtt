package com.tuyacontroller2mqtt;
import android.util.Log;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
// Minimal MQTT 3.1.1 client, QoS 0, no dependencies.
// Paho was removed: its ResourceBundle log properties are not in a dex,
// so MqttClient construction crashed on Android with MissingResourceException.
// Naming comes from MqttTopics; model/manufacturer in discovery come
// from DeviceProfile.
// Transport is plaintext by default (trusted LAN) or TLS with the
// system certificate store and hostname verification when enabled in
// settings. Without TLS the broker password and all commands travel in
// cleartext and anyone able to publish to the broker can operate the
// panel: see the Security section of the README.
// The sensor is Motion, not Presence: see PROTOCOL.md.
// Availability uses a Last Will (offline, retained) plus an online
// retained message on connect, with keepalive 60 and a 30 s PINGREQ from
// the service, so a dead daemon is detected by the broker within ~90 s.
// Option switches (Wake Lock, Wake Screen) are command topics the daemon
// subscribes to; HA toggles update the same prefs the app config edits.
public class MqttPublisher {
    private static final String TAG = Constants.TAG;
    public interface CommandListener { void onCommand(String topic, String payload); }
    private Socket socket; private OutputStream out; private InputStream in;
    private String host; private int port; private String user; private String pass; private String deviceName; private String slug; private String root;
    private boolean useTls = false;
    private final DeviceProfile profile = DeviceProfile.active();
    private boolean connected = false;
    private CommandListener listener;
    private Thread readerThread;
    // Per-relay mode: false = relay mode (HA switch
    // exposed), true = momentary mode (switch discovery retracted,
    // only the press event remains). Set by the service from prefs
    // before connect and on every mode change; publishDiscovery and
    // publishRelaySwitchDiscovery read it. Sized from the profile
    // (index 0 unused).
    private final boolean[] relayMomentary = new boolean[profile.relayCount + 1];
    public void setRelayMomentary(int relay, boolean momentary) { if (profile.hasRelay(relay)) relayMomentary[relay] = momentary; }
    public boolean relayMomentary(int relay) { return profile.hasRelay(relay) && relayMomentary[relay]; }
    public void setCommandListener(CommandListener l) { listener = l; }
    public static String slug(String name) { return MqttTopics.slug(name); }
    public String topicRoot() { return root; }
    public synchronized void connect(String host, int port, String user, String pass, String deviceName, boolean useTls) throws IOException {
        if (connected && socket != null && socket.isConnected() && !socket.isClosed()) return;
        closeQuietly();
        this.host = host; this.port = port; this.user = user; this.pass = pass; this.deviceName = deviceName; this.useTls = useTls;
        this.slug = slug(deviceName); this.root = MqttTopics.topicRoot(slug);
        // Bounded connect: a no-timeout Socket constructor could block
        // for the OS TCP timeout (~120 s) on an unreachable broker,
        // stalling whichever thread was publishing.
        Socket s;
        if (useTls) {
            // TLS with the platform default trust store (system CAs)
            // and HTTPS-style hostname verification against the broker
            // host name. Brokers with self-signed certificates are not
            // supported; use a publicly trusted or locally installed CA
            // certificate, or plaintext on a trusted LAN.
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket tls = (SSLSocket) factory.createSocket();
            SSLParameters params = tls.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            tls.setSSLParameters(params);
            tls.connect(new InetSocketAddress(host, port), 3000);
            tls.startHandshake();
            s = tls;
        } else {
            s = new Socket(); s.connect(new InetSocketAddress(host, port), 3000);
        }
        socket = s; socket.setSoTimeout(5000);
        out = socket.getOutputStream(); in = socket.getInputStream();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeString(body, "MQTT"); body.write(4);
        int flags = 0x02; // clean session
        if (user != null && !user.isEmpty()) flags |= 0x80;
        if (pass != null && !pass.isEmpty()) flags |= 0x40;
        flags |= 0x04; // will flag, QoS 0, retain set below in will payload handling
        body.write(flags); body.write(0); body.write(60); // keepalive 60 s
        writeString(body, MqttTopics.clientId(slug)); // per-device client id: a fixed id would kick a second panel off the broker
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
        Log.i(TAG, "mqtt connected to " + host + ":" + port + (useTls ? " (TLS)" : " (plaintext)") + " root=" + root);
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
    private static String json(String s) { return MqttTopics.escapeJson(s); }
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
            connect(host, port, user, pass, deviceName, useTls);
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
    public synchronized void publishOptionStates(boolean wakelock, boolean wakescreen, boolean debug) throws IOException {
        doPublish(root + "/wakelock/state", wakelock ? "ON" : "OFF", true);
        doPublish(root + "/wakescreen/state", wakescreen ? "ON" : "OFF", true);
        doPublish(root + "/debug/state", debug ? "ON" : "OFF", true);
    }
    public synchronized void publishDiscovery() throws IOException {
        // The messages themselves are built by Discovery (pure,
        // unit-tested); this method only sends them, in order.
        for (String[] msg : Discovery.discoveryMessages(profile, relayMomentary, slug, deviceName, root)) {
            doPublish(msg[0], msg[1], true);
        }
        Log.i(TAG, "mqtt discovery published, device=" + deviceName);
    }
    // The relay switch discovery for one relay, honouring its mode:
    // momentary mode publishes an empty config so HA removes the
    // switch entity (the press event entity is separate and stays).
    // Called from publishDiscovery (via Discovery) and by the service
    // on a mode change.
    public synchronized void publishRelaySwitchDiscovery(int i) throws IOException {
        String[] msg = Discovery.relaySwitchMessage(profile, relayMomentary(i), slug, deviceName, root, i);
        doPublish(msg[0], msg[1], true);
    }
    public synchronized void publishRelayMode(int i) { try { publishRaw(root + "/relay/" + i + "/mode", relayMomentary[i] ? "momentary" : "relay", true); } catch (Exception e) { Log.w(TAG, "mqtt relay mode failed: " + e, e); } }
    public synchronized void publishRelayModeStates() { for (int i = 1; i <= profile.relayCount; i++) publishRelayMode(i); }
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
