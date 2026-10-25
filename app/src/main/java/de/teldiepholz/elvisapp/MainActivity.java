package de.teldiepholz.elvisapp;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ein Bildschirm: ungekoppelt App-ID und Code zum Eintragen in ELVIS, gekoppelt die Einheit und der Stand der
 * Ortung mit Start/Stopp. Solange die App offen ist und nicht ortet, fragt sie alle 10 s beim Relay nach, ob sie
 * gekoppelt wurde, und startet dann die Ortung von selbst (außer der Nutzer hat sie gestoppt).
 */
public class MainActivity extends Activity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final long POLL_MS = 10_000;
    private static final int REQUEST_PERMISSIONS = 1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = this::poll;
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private View unpaired;
    private View paired;
    private TextView deviceId;
    private TextView code;
    private TextView label;
    private TextView trackingState;
    private TextView lastSent;
    private TextView message;
    private Button toggle;
    private boolean resumed;
    private boolean registering;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        unpaired = findViewById(R.id.unpaired);
        paired = findViewById(R.id.paired);
        deviceId = findViewById(R.id.device_id);
        code = findViewById(R.id.code);
        label = findViewById(R.id.label);
        trackingState = findViewById(R.id.tracking_state);
        lastSent = findViewById(R.id.last_sent);
        message = findViewById(R.id.message);
        toggle = findViewById(R.id.toggle);
        toggle.setOnClickListener(v -> {
            if (Prefs.tracking(this)) {
                TrackingService.stop(this, true);
            } else {
                Prefs.setStoppedByUser(this, false);
                startTracking();
            }
        });
        findViewById(R.id.relay).setOnClickListener(v -> askRelay());
        findViewById(R.id.reset).setOnClickListener(v -> confirmReset());
        findViewById(R.id.battery).setOnClickListener(v -> openBatterySettings());
        findViewById(R.id.privacy).setOnClickListener(v -> openPrivacy());
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        Prefs.prefs(this).registerOnSharedPreferenceChangeListener(this);
        refresh();
        if (Prefs.relay(this) == null) askRelay();
        else poll();
    }

    @Override
    protected void onPause() {
        resumed = false;
        handler.removeCallbacks(poll);
        Prefs.prefs(this).unregisterOnSharedPreferenceChangeListener(this);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        net.shutdown();
        super.onDestroy();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        refresh();
    }

    /** Anmelden, falls nötig; sonst Zustand abfragen. Während der Ortung fragt der Dienst selbst nach. */
    private void poll() {
        handler.removeCallbacks(poll);
        if (!resumed) return;
        String base = Prefs.relay(this);
        if (base == null) return;
        if (Prefs.token(this) == null) {
            register(base);
        } else if (!Prefs.tracking(this)) {
            String token = Prefs.token(this);
            net.execute(() -> {
                try {
                    Relay.Status s = new Relay(base, token).status();
                    runOnUiThread(() -> onStatus(s));
                } catch (Relay.RelayException e) {
                    runOnUiThread(() -> {
                        if (e.status == 401) Prefs.clearRegistration(this);
                        else Prefs.setError(this, e.getMessage());
                    });
                } catch (IOException e) {
                    runOnUiThread(() -> Prefs.setError(this, getString(R.string.error_relay)));
                }
            });
        }
        handler.postDelayed(poll, POLL_MS);
    }

    private void register(String base) {
        if (registering) return;
        registering = true;
        net.execute(() -> {
            try {
                Relay.Registration r = new Relay(base, null).register();
                runOnUiThread(() -> Prefs.setRegistration(this, r.deviceId, r.token, r.code));
            } catch (IOException e) {
                runOnUiThread(() -> Prefs.setError(this, getString(R.string.error_register)));
            } finally {
                runOnUiThread(() -> registering = false);
            }
        });
    }

    private void onStatus(Relay.Status s) {
        Prefs.setStatus(this, s);
        if (s.paired && !Prefs.tracking(this) && !Prefs.stoppedByUser(this)) startTracking();
    }

    private void refresh() {
        boolean isPaired = Prefs.paired(this);
        boolean tracking = Prefs.tracking(this);
        String id = Prefs.deviceId(this);
        unpaired.setVisibility(isPaired ? View.GONE : View.VISIBLE);
        paired.setVisibility(isPaired ? View.VISIBLE : View.GONE);
        deviceId.setText(id == null ? "…" : SendRule.formatDeviceId(id));
        String c = Prefs.code(this);
        code.setText(c == null ? "…" : SendRule.formatCode(c));
        String l = Prefs.label(this);
        label.setText(l.isEmpty() ? getString(R.string.paired_unknown) : l);
        trackingState.setText(tracking ? R.string.tracking_on : Prefs.stoppedByUser(this) ? R.string.tracking_stopped : R.string.tracking_off);
        long sent = Prefs.lastSent(this);
        lastSent.setText(sent == 0 ? getString(R.string.last_sent_never) : getString(R.string.last_sent, DateFormat.getTimeFormat(this).format(new Date(sent))));
        toggle.setText(tracking ? R.string.action_stop : R.string.action_start);

        String error = Prefs.error(this);
        String hint = !locationEnabled() ? getString(R.string.hint_location_off) : error;
        message.setText(hint);
        message.setVisibility(hint == null ? View.GONE : View.VISIBLE);
    }

    private boolean locationEnabled() {
        LocationManager lm = getSystemService(LocationManager.class);
        return lm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P || lm.isLocationEnabled();
    }

    // --- Ortung starten: erst Berechtigungen ---

    private void startTracking() {
        List<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (missing.isEmpty()) TrackingService.start(this);
        else requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != REQUEST_PERMISSIONS) return;
        boolean location = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (location) {
            TrackingService.start(this);
        } else {
            // Nicht ständig erneut fragen: erst wieder auf „Ortung starten“
            Prefs.setStoppedByUser(this, true);
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show();
        }
    }

    // --- Einstellungen ---

    private void askRelay() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(true);
        input.setHint(BuildConfig.RELAY_URL);
        String current = Prefs.relay(this);
        if (current != null) input.setText(current);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.relay_title)
                .setMessage(R.string.relay_hint)
                .setView(input)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String url = Prefs.normalize(input.getText().toString());
            if (url == null) {
                input.setError(getString(R.string.relay_invalid));
                return;
            }
            if (!url.equals(current)) {
                if (Prefs.tracking(this)) TrackingService.stop(this, false);
                Prefs.setRelay(this, url);
            }
            dialog.dismiss();
            poll();
        }));
        dialog.show();
    }

    /** Neue App-ID: die alte wird beim Relay gelöscht, eine bestehende Kopplung endet */
    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.reset_title)
                .setMessage(R.string.reset_message)
                .setPositiveButton(R.string.reset_confirm, (d, w) -> {
                    if (Prefs.tracking(this)) TrackingService.stop(this, false);
                    String base = Prefs.relay(this);
                    String token = Prefs.token(this);
                    net.execute(() -> {
                        try {
                            if (base != null && token != null) new Relay(base, token).delete();
                        } catch (IOException ignored) {
                            // Nicht erreichbar: das Relay löscht unbenutzte App-IDs nach 90 Tagen selbst
                        }
                        runOnUiThread(() -> {
                            Prefs.clearRegistration(this);
                            poll();
                        });
                    });
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void openBatterySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.battery_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    private void openPrivacy() {
        String base = Prefs.relay(this);
        if (base == null) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(base + "/datenschutz")));
        } catch (ActivityNotFoundException ignored) {
            // Kein Browser installiert
        }
    }
}
