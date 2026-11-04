package com.tuyacontroller2mqtt;
import android.app.Activity;
import android.content.*;
import android.os.Bundle;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
// Settings UI. Wrapped in a ScrollView so the small panel screen + keyboard
// cannot hide the Save button. All fields are single-line; Enter moves to
// the next field (Done on the last), it never inserts a newline.
// Password is masked, with a Show password checkbox, and stored encrypted
// via the Android Keystore (see PasswordStore), never as plaintext.
// Device name defaults to the Android device name (Settings.Global
// device_name) and can be edited here before the daemon is enabled;
// it becomes the HA device name in discovery.
// Options are shared with HA: the Wake Lock / Wake Screen switches in HA
// write the same prefs these checkboxes edit, and vice versa.
public class MainActivity extends Activity {
    private Button grantWriteButton;
    // The grant button mirrors the live Write Settings state: enabled
    // with the grant prompt while the grant is missing, disabled and
    // labelled granted once it is in place (by the system settings
    // screen or by the su auto-grant). Refreshed on resume and when an
    // auto-grant attempt lands, so it never shows a stale state.
    private void refreshGrantButton() {
        if (grantWriteButton == null) return;
        boolean granted = WriteSettingsGrant.isGranted(this);
        grantWriteButton.setText(AccessLogic.grantButtonLabel(granted));
        boolean clickable = AccessLogic.grantButtonClickable(granted);
        grantWriteButton.setEnabled(clickable);
        grantWriteButton.setClickable(clickable);
    }
    private void attemptAutoGrant() {
        WriteSettingsGrant.attemptAutoGrantAsync(this, () -> runOnUiThread(this::refreshGrantButton));
    }
    @Override protected void onResume() {
        super.onResume();
        refreshGrantButton();
        attemptAutoGrant();
    }
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setScrollbarFadingEnabled(false);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = 32; root.setPadding(pad, pad, pad, pad);
        SharedPreferences p = getSharedPreferences(Constants.PREFS, MODE_PRIVATE);
        EditText name = field(ButtonService.deviceName(this, p), "Device name", InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_NEXT);
        EditText host = field(p.getString("host", ""), "MQTT host or IP", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, EditorInfo.IME_ACTION_NEXT);
        boolean tlsSaved = p.getBoolean("tls_enabled", false);
        EditText port = field(p.getString("port", tlsSaved ? "8883" : "1883"), "MQTT port", InputType.TYPE_CLASS_NUMBER, EditorInfo.IME_ACTION_NEXT);
        EditText user = field(p.getString("user", ""), "MQTT username", InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_NEXT);
        EditText pass = field(PasswordStore.getPassword(p), "MQTT password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, EditorInfo.IME_ACTION_DONE);
        CheckBox show = new CheckBox(this); show.setText("Show password");
        show.setOnCheckedChangeListener((v, checked) -> {
            pass.setInputType(checked ? InputType.TYPE_CLASS_TEXT : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            pass.setTransformationMethod(checked ? null : android.text.method.PasswordTransformationMethod.getInstance());
            pass.setSelection(pass.getText().length());
        });
        CheckBox tls = new CheckBox(this); tls.setText("Use TLS (MQTT over TLS, port 8883; broker certificate is verified)");
        tls.setChecked(tlsSaved);
        tls.setOnCheckedChangeListener((v, checked) -> {
            // Convenience only: when the port field still holds the
            // other mode's well-known default, swap it for this mode's.
            String cur = port.getText().toString().trim();
            if (checked && "1883".equals(cur)) port.setText("8883");
            if (!checked && "8883".equals(cur)) port.setText("1883");
        });
        CheckBox wakelock = new CheckBox(this); wakelock.setText("Hold wake lock (prevent suspend, keep daemon reading)");
        wakelock.setChecked(ButtonService.wakelockEnabled(p));
        CheckBox wakescreen = new CheckBox(this); wakescreen.setText("Wake screen on button press (not on motion)");
        wakescreen.setChecked(ButtonService.wakescreenEnabled(p));
        CheckBox debugBox = new CheckBox(this); debugBox.setText("Debug: rotating log files (serial traces, metrics, events) — safe to leave on, capped at 30 MB total");
        debugBox.setChecked(ButtonService.debugEnabled(p));
        Button save = new Button(this); save.setText("Save and start daemon");
        save.setOnClickListener(v -> {
            int portNum;
            try { portNum = Integer.parseInt(port.getText().toString().trim()); if (portNum < 1 || portNum > 65535) throw new NumberFormatException(); }
            catch (NumberFormatException e) { Toast.makeText(this, "MQTT port must be a number 1-65535", Toast.LENGTH_SHORT).show(); return; }
            try {
                SharedPreferences.Editor ed = p.edit();
                ed.putString("device_name", name.getText().toString().trim());
                ed.putString("host", host.getText().toString().trim());
                ed.putString("port", String.valueOf(portNum));
                ed.putString("user", user.getText().toString().trim());
                ed.putBoolean("tls_enabled", tls.isChecked());
                ed.putBoolean("wakelock_enabled", wakelock.isChecked());
                ed.putBoolean("wakescreen_enabled", wakescreen.isChecked());
                ed.putBoolean("debug_enabled", debugBox.isChecked());
                PasswordStore.setPassword(ed, pass.getText().toString());
                ed.apply();
            } catch (Exception e) {
                Toast.makeText(this, "Could not store password securely, not saved: " + e.getMessage(), Toast.LENGTH_LONG).show();
                return;
            }
            startForegroundService(new Intent(this, ButtonService.class));
            Toast.makeText(this, "Saved, daemon starting and publishing to Home Assistant", Toast.LENGTH_SHORT).show();
        });
        grantWriteButton = new Button(this);
        grantWriteButton.setOnClickListener(v -> {
            if (WriteSettingsGrant.isGranted(this)) { refreshGrantButton(); return; }
            Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS);
            i.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(i);
        });
        refreshGrantButton();
        TextView note = new TextView(this);
        note.setText("Save starts the daemon and publishes the device to Home Assistant. Without TLS, MQTT is plaintext — use it only on a trusted network.");
        root.addView(name); root.addView(host); root.addView(port); root.addView(user); root.addView(pass); root.addView(show);
        root.addView(tls); root.addView(wakelock); root.addView(wakescreen); root.addView(debugBox); root.addView(save); root.addView(grantWriteButton); root.addView(note);
        scroll.addView(root); setContentView(scroll);
        // On a rooted panel, try to apply the Write Settings grant
        // via su right away; the button flips to its granted state if
        // it lands. OnResume repeats the attempt when the screen
        // becomes visible again.
        attemptAutoGrant();
    }
    private EditText field(String value, String hint, int inputType, int ime) {
        EditText e = new EditText(this);
        e.setText(value); e.setHint(hint); e.setInputType(inputType);
        e.setSingleLine(true); e.setImeOptions(ime);
        return e;
    }
}
