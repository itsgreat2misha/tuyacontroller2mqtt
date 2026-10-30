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
// Password is masked, with a Show password checkbox.
public class MainActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = 32; root.setPadding(pad, pad, pad, pad);
        SharedPreferences p = getSharedPreferences("f5", MODE_PRIVATE);
        EditText host = field(p.getString("host", ""), "MQTT host or IP", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, EditorInfo.IME_ACTION_NEXT);
        EditText port = field(p.getString("port", "1883"), "MQTT port", InputType.TYPE_CLASS_NUMBER, EditorInfo.IME_ACTION_NEXT);
        EditText user = field(p.getString("user", ""), "MQTT username", InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_NEXT);
        EditText pass = field(p.getString("pass", ""), "MQTT password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, EditorInfo.IME_ACTION_DONE);
        CheckBox show = new CheckBox(this); show.setText("Show password");
        show.setOnCheckedChangeListener((v, checked) -> {
            pass.setInputType(checked ? InputType.TYPE_CLASS_TEXT : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            pass.setSelection(pass.getText().length());
        });
        Button save = new Button(this); save.setText("Save and start daemon");
        save.setOnClickListener(v -> {
            p.edit().putString("host", host.getText().toString().trim()).putString("port", port.getText().toString().trim())
                .putString("user", user.getText().toString().trim()).putString("pass", pass.getText().toString()).apply();
            startForegroundService(new Intent(this, ButtonService.class));
            Toast.makeText(this, "Saved, daemon starting and publishing to Home Assistant", Toast.LENGTH_SHORT).show();
        });
        TextView note = new TextView(this);
        note.setText("After Save, the daemon connects to MQTT immediately and publishes discovery, so the entities should appear in Home Assistant without pressing a button first.");
        root.addView(host); root.addView(port); root.addView(user); root.addView(pass); root.addView(show); root.addView(save); root.addView(note);
        scroll.addView(root); setContentView(scroll);
    }
    private EditText field(String value, String hint, int inputType, int ime) {
        EditText e = new EditText(this);
        e.setText(value); e.setHint(hint); e.setInputType(inputType);
        e.setSingleLine(true); e.setImeOptions(ime);
        return e;
    }
}
