package com.misha.f5daemon;
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
// device_name, e.g. "Entryway assistant") and can be edited here before
// the daemon is enabled; it becomes the HA device name in discovery.
// Options are shared with HA: the Wake Lock / Wake Screen switches in HA
// write the same prefs these checkboxes edit, and vice versa.
public class MainActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setScrollbarFadingEnabled(false);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = 32; root.setPadding(pad, pad, pad, pad);
        SharedPreferences p = getSharedPreferences("f5", MODE_PRIVATE);
        EditText name = field(ButtonService.deviceName(this, p), "Device name", InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_NEXT);
        EditText host = field(p.getString("host", ""), "MQTT host or IP", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, EditorInfo.IME_ACTION_NEXT);
        EditText port = field(p.getString("port", "1883"), "MQTT port", InputType.TYPE_CLASS_NUMBER, EditorInfo.IME_ACTION_NEXT);
        EditText user = field(p.getString("user", ""), "MQTT username", InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_NEXT);
        EditText pass = field(PasswordStore.getPassword(p), "MQTT password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, EditorInfo.IME_ACTION_DONE);
        CheckBox show = new CheckBox(this); show.setText("Show password");
        show.setOnCheckedChangeListener((v, checked) -> {
            pass.setInputType(checked ? InputType.TYPE_CLASS_TEXT : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            pass.setTransformationMethod(checked ? null : android.text.method.PasswordTransformationMethod.getInstance());
            pass.setSelection(pass.getText().length());
        });
        CheckBox wakelock = new CheckBox(this); wakelock.setText("Hold wake lock (prevent suspend, keep daemon reading)");
        wakelock.setChecked(ButtonService.wakelockEnabled(p));
        CheckBox wakescreen = new CheckBox(this); wakescreen.setText("Wake screen on button press (not on motion)");
        wakescreen.setChecked(ButtonService.wakescreenEnabled(p));
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
                ed.putBoolean("wakelock_enabled", wakelock.isChecked());
                ed.putBoolean("wakescreen_enabled", wakescreen.isChecked());
                PasswordStore.setPassword(ed, pass.getText().toString());
                ed.apply();
            } catch (Exception e) {
                Toast.makeText(this, "Could not store password securely, not saved: " + e.getMessage(), Toast.LENGTH_LONG).show();
                return;
            }
            startForegroundService(new Intent(this, ButtonService.class));
            Toast.makeText(this, "Saved, daemon starting and publishing to Home Assistant", Toast.LENGTH_SHORT).show();
        });
        TextView note = new TextView(this);
        note.setText("After Save, the daemon connects to MQTT immediately and publishes discovery, so the entities should appear in Home Assistant without pressing a button first. Turning off the wake lock lets the panel suspend, which stops serial reading until it wakes. Wake screen uses su on this rooted panel; without it, it logs a warning and does nothing.");
        root.addView(name); root.addView(host); root.addView(port); root.addView(user); root.addView(pass); root.addView(show);
        root.addView(wakelock); root.addView(wakescreen); root.addView(save); root.addView(note);
        scroll.addView(root); setContentView(scroll);
    }
    private EditText field(String value, String hint, int inputType, int ime) {
        EditText e = new EditText(this);
        e.setText(value); e.setHint(hint); e.setInputType(inputType);
        e.setSingleLine(true); e.setImeOptions(ime);
        return e;
    }
}
