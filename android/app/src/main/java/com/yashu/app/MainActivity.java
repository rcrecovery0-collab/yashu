package com.yashu.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {

    private SharedPreferences prefs;
    private EditText url, pw;
    private CheckBox mute;
    private TextView status;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("yashu", MODE_PRIVATE);
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (24 * d);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF1A0F20);
        root.setPadding(pad, pad * 2, pad, pad);

        TextView title = text("यशु", 46, 0xFFFFD9E2);
        root.addView(title);
        root.addView(text("तुझी Yashu, अॅप बंद असतानाही ऐकते.", 16, 0xFFB9A0C4));

        url = field("Render link (https://yashu.onrender.com)", prefs.getString("url", ""), false);
        pw = field("पासवर्ड", prefs.getString("pw", ""), true);
        root.addView(url, lp(d, 24));
        root.addView(pw, lp(d, 12));

        mute = new CheckBox(this);
        mute.setText("बीप आवाज बंद कर (नोटिफिकेशन/रिंगटोनही शांत होऊ शकतात)");
        mute.setTextColor(0xFFB9A0C4);
        mute.setChecked(prefs.getBoolean("mute", false));
        root.addView(mute, lp(d, 12));

        Button start = button("यशुला सुरू कर", 0xFFF0507F, 0xFF2A0B17);
        start.setOnClickListener(v -> startYashu());
        root.addView(start, lp(d, 20));

        Button stop = button("थांबव", 0xFF2B1631, 0xFFFFD9E2);
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, ListenService.class));
            status.setText("थांबवलं.");
        });
        root.addView(stop, lp(d, 10));

        Button battery = button("बॅटरी ऑप्टिमायझेशन बंद कर (महत्त्वाचं)", 0xFF2B1631, 0xFFFFD9E2);
        battery.setOnClickListener(v -> askBattery());
        root.addView(battery, lp(d, 10));

        status = text("", 16, 0xFFFFB347);
        root.addView(status, lp(d, 20));

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    private void startYashu() {
        String u = url.getText().toString().trim();
        if (u.isEmpty()) {
            status.setText("आधी Render link टाक.");
            return;
        }
        prefs.edit()
                .putString("url", u)
                .putString("pw", pw.getText().toString())
                .putBoolean("mute", mute.isChecked())
                .apply();

        boolean needMic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED;
        boolean needNotif = Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED;
        if (needMic || needNotif) {
            String[] perms = Build.VERSION.SDK_INT >= 33
                    ? new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}
                    : new String[]{Manifest.permission.RECORD_AUDIO};
            requestPermissions(perms, 1);
            return;
        }
        startForegroundService(new Intent(this, ListenService.class));
        status.setText("यशु ऐकतेय. स्क्रीन बंद केली तरी चालेल. “यशु ऐक ना” म्हण.");
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startYashu();
        } else {
            status.setText("मायक्रोफोनची परवानगी दे, नाहीतर मी ऐकू शकत नाही.");
        }
    }

    private void askBattery() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
            status.setText("बॅटरी ऑप्टिमायझेशन आधीच बंद आहे. छान!");
        } else {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private EditText field(String hint, String value, boolean password) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setTextColor(0xFFFFD9E2);
        e.setHintTextColor(0xFF8D7498);
        e.setSingleLine(true);
        e.setInputType(password
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        return e;
    }

    private Button button(String label, int bg, int fg) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(fg);
        b.setBackgroundColor(bg);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout.LayoutParams lp(float d, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = (int) (topDp * d);
        return p;
    }
}
