package de.teldiepholz.elvisapp;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

/**
 * Gespeicherter Zustand: Relay-Adresse, Anmeldung beim Relay (App-ID, Schlüssel, Code), Kopplung und Ortung.
 * Dienst und Oberfläche tauschen sich hierüber aus; die Oberfläche hört auf Änderungen.
 */
final class Prefs {
    static final String FILE = "elvis";
    static final String KEY_RELAY = "relay";
    static final String KEY_DEVICE_ID = "deviceId";
    static final String KEY_TOKEN = "token";
    static final String KEY_CODE = "code";
    static final String KEY_PAIRED = "paired";
    static final String KEY_LABEL = "label";
    static final String KEY_TRACKING = "tracking";
    static final String KEY_STOPPED_BY_USER = "stoppedByUser";
    static final String KEY_LAST_LAT = "lastLat";
    static final String KEY_LAST_LON = "lastLon";
    static final String KEY_LAST_SENT = "lastSent";
    static final String KEY_ERROR = "error";

    private Prefs() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Basisadresse des Relays ohne abschließenden Schrägstrich; null, solange keine eingetragen ist */
    static String relay(Context c) {
        String s = prefs(c).getString(KEY_RELAY, null);
        if (s == null) s = normalize(BuildConfig.RELAY_URL);
        return s;
    }

    static void setRelay(Context c, String url) {
        // Neues Relay: Anmeldung und Kopplung gelten dort nicht
        prefs(c).edit().putString(KEY_RELAY, url).remove(KEY_DEVICE_ID).remove(KEY_TOKEN).remove(KEY_CODE)
                .putBoolean(KEY_PAIRED, false).remove(KEY_LABEL).remove(KEY_ERROR).apply();
    }

    static String deviceId(Context c) {
        return prefs(c).getString(KEY_DEVICE_ID, null);
    }

    static String token(Context c) {
        return prefs(c).getString(KEY_TOKEN, null);
    }

    static String code(Context c) {
        return prefs(c).getString(KEY_CODE, null);
    }

    static void setRegistration(Context c, String deviceId, String token, String code) {
        prefs(c).edit().putString(KEY_DEVICE_ID, deviceId).putString(KEY_TOKEN, token).putString(KEY_CODE, code)
                .putBoolean(KEY_PAIRED, false).remove(KEY_LABEL).apply();
    }

    static void clearRegistration(Context c) {
        prefs(c).edit().remove(KEY_DEVICE_ID).remove(KEY_TOKEN).remove(KEY_CODE).putBoolean(KEY_PAIRED, false)
                .remove(KEY_LABEL).putBoolean(KEY_STOPPED_BY_USER, false).apply();
    }

    static boolean paired(Context c) {
        return prefs(c).getBoolean(KEY_PAIRED, false);
    }

    static String label(Context c) {
        return prefs(c).getString(KEY_LABEL, "");
    }

    /** Antwort des Relays übernehmen; endet die Kopplung, darf die nächste wieder von selbst starten */
    static void setStatus(Context c, Relay.Status s) {
        SharedPreferences.Editor e = prefs(c).edit().putBoolean(KEY_PAIRED, s.paired).putString(KEY_LABEL, s.label).remove(KEY_ERROR);
        if (s.code != null) e.putString(KEY_CODE, s.code);
        if (!s.paired) e.putBoolean(KEY_STOPPED_BY_USER, false).remove(KEY_LAST_SENT);
        e.apply();
    }

    static boolean tracking(Context c) {
        return prefs(c).getBoolean(KEY_TRACKING, false);
    }

    static void setTracking(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_TRACKING, on).apply();
    }

    static boolean stoppedByUser(Context c) {
        return prefs(c).getBoolean(KEY_STOPPED_BY_USER, false);
    }

    static void setStoppedByUser(Context c, boolean stopped) {
        prefs(c).edit().putBoolean(KEY_STOPPED_BY_USER, stopped).apply();
    }

    static long lastSent(Context c) {
        return prefs(c).getLong(KEY_LAST_SENT, 0);
    }

    static double lastLat(Context c) {
        return Double.longBitsToDouble(prefs(c).getLong(KEY_LAST_LAT, 0));
    }

    static double lastLon(Context c) {
        return Double.longBitsToDouble(prefs(c).getLong(KEY_LAST_LON, 0));
    }

    /** Beim Start der Ortung: die erste Position geht sofort raus, unabhängig von früheren Meldungen */
    static void clearSent(Context c) {
        prefs(c).edit().remove(KEY_LAST_SENT).apply();
    }

    static void setSent(Context c, double lat, double lon, long at) {
        prefs(c).edit().putLong(KEY_LAST_LAT, Double.doubleToRawLongBits(lat)).putLong(KEY_LAST_LON, Double.doubleToRawLongBits(lon))
                .putLong(KEY_LAST_SENT, at).remove(KEY_ERROR).apply();
    }

    static String error(Context c) {
        return prefs(c).getString(KEY_ERROR, null);
    }

    static void setError(Context c, String message) {
        prefs(c).edit().putString(KEY_ERROR, message).apply();
    }

    /** Eingabe in eine Basisadresse wandeln: ohne Schema wird https:// ergänzt, abschließende Schrägstriche fallen weg. */
    static String normalize(String input) {
        String s = input == null ? "" : input.trim();
        if (s.isEmpty()) return null;
        if (!s.contains("://")) s = "https://" + s;
        Uri u = Uri.parse(s);
        String scheme = u.getScheme();
        if (u.getHost() == null || u.getHost().isEmpty() || !("http".equals(scheme) || "https".equals(scheme))) return null;
        String path = u.getPath() == null ? "" : u.getPath().replaceAll("/+$", "");
        return scheme + "://" + u.getEncodedAuthority() + path;
    }
}
