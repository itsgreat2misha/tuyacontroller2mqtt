package com.misha.f5daemon;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
// MQTT password storage: AES/GCM with a key in the Android Keystore.
// The key itself is not extractable from the Keystore; only ciphertext
// (IV + encrypted password, base64) is stored in SharedPreferences.
// Migration: a legacy plaintext "pass" entry is encrypted on first read
// and then deleted, so an update does not force re-entry.
// Honest limit: this panel is rooted with unauthenticated adb, so root
// could still use the Keystore key via the system APIs. This stops the
// password sitting in plaintext in an app-private XML file, which is what
// any other app / backup / log reader would see; it is not a defence
// against root on this device.
public class PasswordStore {
    private static final String TAG = "F5Daemon";
    private static final String ALIAS = "F5DaemonMqttPassword";
    // All entry points are synchronized: two threads generating / reading
    // the Keystore key at the same time raced and one failed with
    // UnrecoverableKeyException on first run after update.
    private static synchronized SecretKey getKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
            kg.generateKey();
        }
        return (SecretKey) ks.getKey(ALIAS, null);
    }
    public static synchronized String encrypt(String plain) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, getKey());
        byte[] iv = c.getIV(); byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length); System.arraycopy(ct, 0, out, iv.length, ct.length);
        return Base64.encodeToString(out, Base64.NO_WRAP);
    }
    public static synchronized String decrypt(String encoded) throws Exception {
        byte[] all = Base64.decode(encoded, Base64.DEFAULT);
        byte[] iv = new byte[12]; byte[] ct = new byte[all.length - 12];
        System.arraycopy(all, 0, iv, 0, 12);
        System.arraycopy(all, 12, ct, 0, ct.length);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, getKey(), new GCMParameterSpec(128, iv));
        return new String(c.doFinal(ct), StandardCharsets.UTF_8);
    }
    // Returns the password, migrating a legacy plaintext entry if present.
    public static synchronized String getPassword(SharedPreferences p) {
        String enc = p.getString("pass_enc", null);
        if (enc != null) {
            try { return decrypt(enc); }
            catch (Exception e) { Log.w(TAG, "password decrypt failed, re-enter in settings: " + e); return ""; }
        }
        String legacy = p.getString("pass", null);
        if (legacy == null) return "";
        try {
            p.edit().putString("pass_enc", encrypt(legacy)).remove("pass").apply();
            Log.i(TAG, "migrated plaintext MQTT password to Keystore-encrypted storage");
            return legacy;
        } catch (Exception e) { Log.w(TAG, "password migration failed: " + e); return legacy; }
    }
    public static synchronized void setPassword(SharedPreferences.Editor ed, String plain) throws Exception {
        if (plain == null || plain.isEmpty()) { ed.remove("pass_enc").remove("pass"); return; }
        ed.putString("pass_enc", encrypt(plain)).remove("pass");
    }
}
