package de.teldiepholz.elvisapp;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ortung als Vordergrunddienst (Typ „location“, mit Benachrichtigung): läuft weiter, wenn die App im Hintergrund
 * oder der Bildschirm aus ist, und braucht deshalb keine Berechtigung für Standort im Hintergrund. Meldet nach
 * {@link SendRule} an das Relay und beendet sich selbst, sobald ELVIS die Kopplung beendet.
 */
public class TrackingService extends Service implements LocationListener {
    static final String ACTION_STOP = "de.teldiepholz.elvisapp.STOP";
    private static final String CHANNEL = "ortung";
    private static final int NOTIFICATION_ID = 1;
    /** So oft liefert das System eine Ortung (bei Stillstand sonst keine) und prüft die Senderegel */
    private static final long FIX_MS = 30_000;
    private static final long TICK_MS = 60_000;
    /** Ohne fällige Meldung so oft beim Relay nachfragen, ob die Kopplung noch besteht */
    private static final long STATUS_MS = 2 * 60_000;
    /** Netzwerkortung nur nehmen, wenn das GPS so lange nichts geliefert hat */
    private static final long GPS_PREFERRED_MS = 90_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::onTick;
    private ExecutorService net;
    private LocationManager locations;
    private Location latest;
    private long lastGpsElapsed;
    private long lastContact;
    private boolean busy;
    private boolean started;

    static void start(Context c) {
        c.startForegroundService(new Intent(c, TrackingService.class));
    }

    /** @param byUser vom Nutzer gestoppt: bis zur nächsten Kopplung nicht von selbst wieder starten */
    static void stop(Context c, boolean byUser) {
        Prefs.setStoppedByUser(c, byUser);
        c.stopService(new Intent(c, TrackingService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        net = Executors.newSingleThreadExecutor();
        locations = (LocationManager) getSystemService(LOCATION_SERVICE);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Prefs.setStoppedByUser(this, true);
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIFICATION_ID, notification());
            }
        } catch (RuntimeException e) {
            // Ohne Standortberechtigung oder aus dem Hintergrund (Neustart durch das System) verweigert Android den Dienst
            Prefs.setTracking(this, false);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!hasLocationPermission() || Prefs.token(this) == null || Prefs.relay(this) == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!started) {
            started = true;
            Prefs.setTracking(this, true);
            requestUpdates();
            handler.postDelayed(tick, TICK_MS);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        if (locations != null) locations.removeUpdates(this);
        net.shutdown();
        Prefs.setTracking(this, false);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressWarnings("MissingPermission")
    private void requestUpdates() {
        for (String provider : new String[] {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            if (!locations.getAllProviders().contains(provider)) continue;
            try {
                locations.requestLocationUpdates(provider, FIX_MS, 0, this, Looper.getMainLooper());
                Location known = locations.getLastKnownLocation(provider);
                if (known != null && isBetter(known)) latest = known;
            } catch (SecurityException | IllegalArgumentException ignored) {
                // Anbieter nicht verfügbar oder Berechtigung nur ungefähr – der andere reicht
            }
        }
    }

    private boolean isBetter(Location l) {
        if (latest == null) return true;
        if (l.getTime() <= latest.getTime()) return false;
        boolean gps = LocationManager.GPS_PROVIDER.equals(l.getProvider());
        return gps || SystemClock.elapsedRealtime() - lastGpsElapsed > GPS_PREFERRED_MS;
    }

    @Override
    public void onLocationChanged(Location l) {
        if (LocationManager.GPS_PROVIDER.equals(l.getProvider())) lastGpsElapsed = SystemClock.elapsedRealtime();
        if (!isBetter(l)) return;
        latest = l;
        maybeSend();
    }

    // Ältere Android-Versionen rufen diese Methoden ohne Standardimplementierung auf
    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {}

    @Override
    public void onProviderEnabled(String provider) {}

    @Override
    public void onProviderDisabled(String provider) {}

    private void onTick() {
        maybeSend();
        if (!busy && SystemClock.elapsedRealtime() - lastContact >= STATUS_MS) checkStatus();
        handler.postDelayed(tick, TICK_MS);
    }

    private void maybeSend() {
        Location l = latest;
        if (l == null || busy) return;
        long now = System.currentTimeMillis();
        if (!SendRule.shouldSend(Prefs.lastLat(this), Prefs.lastLon(this), Prefs.lastSent(this), l.getLatitude(), l.getLongitude(), now)) return;
        Float accuracy = l.hasAccuracy() ? l.getAccuracy() : null;
        Float battery = battery();
        run(relay -> relay.sendPosition(l.getLatitude(), l.getLongitude(), accuracy, l.getTime(), battery), () -> Prefs.setSent(this, l.getLatitude(), l.getLongitude(), now));
    }

    private void checkStatus() {
        run(Relay::status, null);
    }

    private interface Call {
        Relay.Status call(Relay relay) throws IOException;
    }

    /** Im Hintergrund ans Relay; die Antwort aktualisiert Kopplung und Benachrichtigung oder beendet die Ortung */
    private void run(Call call, Runnable onSuccess) {
        String base = Prefs.relay(this);
        String token = Prefs.token(this);
        if (base == null || token == null) {
            stopSelf();
            return;
        }
        busy = true;
        net.execute(() -> {
            Relay.Status status = null;
            IOException error = null;
            try {
                status = call.call(new Relay(base, token));
            } catch (IOException e) {
                error = e;
            }
            Relay.Status s = status;
            IOException err = error;
            handler.post(() -> {
                busy = false;
                if (s != null) {
                    lastContact = SystemClock.elapsedRealtime();
                    if (onSuccess != null) onSuccess.run();
                    Prefs.setStatus(this, s);
                    if (!s.paired) {
                        stopSelf();
                        return;
                    }
                    getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification());
                } else if (err instanceof Relay.RelayException && ((Relay.RelayException) err).status == 401) {
                    // Relay kennt die App nicht mehr (z. B. lange nicht genutzt): neu anmelden über die Oberfläche
                    Prefs.clearRegistration(this);
                    stopSelf();
                } else {
                    Prefs.setError(this, getString(R.string.error_relay));
                }
            });
        });
    }

    private Float battery() {
        BatteryManager bm = getSystemService(BatteryManager.class);
        int pct = bm == null ? Integer.MIN_VALUE : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        return pct >= 0 && pct <= 100 ? pct / 100f : null;
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, TrackingService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        String label = Prefs.label(this);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(label.isEmpty() ? getString(R.string.notification_text) : getString(R.string.notification_text_label, label))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, getString(R.string.action_stop), stop).build())
                .build();
    }
}
