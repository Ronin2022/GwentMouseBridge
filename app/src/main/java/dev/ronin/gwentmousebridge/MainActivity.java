package dev.ronin.gwentmousebridge;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.List;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private static final int SHIZUKU_REQUEST_CODE = 41;
    private static final int SHELL_UID = 2000;

    private TextView statusText;
    private TextView sensitivityLabel;
    private Switch bridgeSwitch;
    private Switch cursorSwitch;
    private SeekBar sensitivitySeek;
    private SharedPreferences prefs;

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = this::refreshStatus;
    private final Shizuku.OnBinderDeadListener binderDeadListener = this::refreshStatus;
    private final Shizuku.OnRequestPermissionResultListener permissionListener = (requestCode, grantResult) -> {
        if (requestCode == SHIZUKU_REQUEST_CODE) refreshStatus();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = BridgePrefs.prefs(this);
        statusText = findViewById(R.id.statusText);
        sensitivityLabel = findViewById(R.id.sensitivityLabel);
        bridgeSwitch = findViewById(R.id.bridgeSwitch);
        cursorSwitch = findViewById(R.id.cursorSwitch);
        sensitivitySeek = findViewById(R.id.sensitivitySeek);
        Button shizukuButton = findViewById(R.id.shizukuButton);
        Button accessibilityButton = findViewById(R.id.accessibilityButton);

        bridgeSwitch.setChecked(BridgePrefs.enabled(this));
        cursorSwitch.setChecked(BridgePrefs.showCursor(this));
        int sensitivityPercent = prefs.getInt(BridgePrefs.KEY_SENSITIVITY, 125);
        sensitivitySeek.setProgress(sensitivityPercent);
        updateSensitivityLabel(sensitivityPercent);

        bridgeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(BridgePrefs.KEY_ENABLED, isChecked).apply();
            refreshStatus();
        });
        cursorSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs.edit().putBoolean(BridgePrefs.KEY_SHOW_CURSOR, isChecked).apply());
        sensitivitySeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                prefs.edit().putInt(BridgePrefs.KEY_SENSITIVITY, progress).apply();
                updateSensitivityLabel(progress);
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        shizukuButton.setOnClickListener(v -> requestShizukuPermission());
        accessibilityButton.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        Shizuku.addRequestPermissionResultListener(permissionListener);
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        Shizuku.removeRequestPermissionResultListener(permissionListener);
        super.onDestroy();
    }

    private void requestShizukuPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                statusText.setText("Shizuku is not running or its binder is unavailable.");
                return;
            }
            if (Shizuku.isPreV11()) {
                statusText.setText("This prototype requires Shizuku API v11 or newer.");
                return;
            }
            if (Shizuku.checkSelfPermission() == PERMISSION_GRANTED) {
                refreshStatus();
                return;
            }
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                statusText.setText("Shizuku permission was denied. Allow Gwent Mouse Bridge from the Shizuku app.");
                return;
            }
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE);
        } catch (Throwable t) {
            statusText.setText("Shizuku error: " + t.getMessage());
        }
    }

    private void refreshStatus() {
        if (statusText == null) return;
        runOnUiThread(() -> {
            boolean shizukuReady = false;
            boolean shizukuGranted = false;
            int shizukuUid = -1;
            try {
                shizukuReady = Shizuku.pingBinder();
                shizukuGranted = shizukuReady && Shizuku.checkSelfPermission() == PERMISSION_GRANTED;
                if (shizukuGranted) shizukuUid = Shizuku.getUid();
            } catch (Throwable ignored) {}

            boolean accessibility = isAccessibilityServiceEnabled(this);
            String text = "Shizuku: " + (shizukuReady ? (shizukuGranted ? "ready + granted" : "ready, permission needed") : "not ready")
                    + (shizukuGranted ? " (UID " + shizukuUid + (shizukuUid == SHELL_UID ? ", shell" : ", unsupported") + ")" : "")
                    + "\nAccessibility: " + (accessibility ? "enabled" : "disabled")
                    + "\nBridge switch: " + (BridgePrefs.enabled(this) ? "ON" : "OFF")
                    + "\nTarget: " + BridgePrefs.GWENT_PACKAGE;
            statusText.setText(text);
        });
    }

    private void updateSensitivityLabel(int percent) {
        sensitivityLabel.setText("Pointer sensitivity: " + (percent / 100f) + "x");
    }

    private static boolean isAccessibilityServiceEnabled(Context context) {
        AccessibilityManager manager = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        List<AccessibilityServiceInfo> services = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        ComponentName expected = new ComponentName(context, MouseBridgeAccessibilityService.class);
        for (AccessibilityServiceInfo info : services) {
            ComponentName actual = info.getId() == null ? null : ComponentName.unflattenFromString(info.getId());
            if (expected.equals(actual)) return true;
        }
        return false;
    }
}
