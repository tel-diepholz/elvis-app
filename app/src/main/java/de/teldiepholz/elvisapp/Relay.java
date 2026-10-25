package de.teldiepholz.elvisapp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Zugriff auf das Cloud-Relay (tel-diepholz/elvis-relay). Blockiert – nur aus einem Hintergrund-Thread aufrufen.
 * Protokoll: src/protocol.ts im Relay bzw. packages/shared/src/applink.ts in ELVIS.
 */
final class Relay {
    private static final int TIMEOUT_MS = 15_000;

    /** Zustand der App beim Relay */
    static final class Status {
        final boolean paired;
        final String label;
        /** Code zum Koppeln, nur solange ungekoppelt */
        final String code;

        Status(boolean paired, String label, String code) {
            this.paired = paired;
            this.label = label;
            this.code = code;
        }

        static Status from(JSONObject o) {
            return new Status(o.optBoolean("paired"), o.optString("label", ""), o.isNull("code") ? null : o.optString("code", null));
        }
    }

    static final class Registration {
        final String deviceId;
        final String token;
        final String code;

        Registration(String deviceId, String token, String code) {
            this.deviceId = deviceId;
            this.token = token;
            this.code = code;
        }
    }

    /** Fehler mit HTTP-Status; 401 heißt, das Relay kennt die App nicht (mehr) */
    static final class RelayException extends IOException {
        final int status;

        RelayException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private final String base;
    private final String token;

    Relay(String base, String token) {
        this.base = base;
        this.token = token;
    }

    Registration register() throws IOException {
        JSONObject o = call("POST", "/v1/devices", new JSONObject());
        return new Registration(o.optString("deviceId"), o.optString("token"), o.optString("code"));
    }

    Status status() throws IOException {
        return Status.from(call("GET", "/v1/devices/me", null));
    }

    /** App beim Relay löschen (neue App-ID) */
    void delete() throws IOException {
        call("DELETE", "/v1/devices/me", null);
    }

    /** Position melden; die Antwort sagt, ob die App noch gekoppelt ist */
    Status sendPosition(double lat, double lon, Float accuracyM, long time, Float battery) throws IOException {
        try {
            JSONObject body = new JSONObject()
                    .put("lat", lat)
                    .put("lon", lon)
                    .put("accuracyM", accuracyM == null ? JSONObject.NULL : (double) accuracyM)
                    .put("ts", iso(time))
                    .put("battery", battery == null ? JSONObject.NULL : (double) battery);
            return Status.from(call("POST", "/v1/devices/me/positions", body));
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    static String iso(long time) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(time));
    }

    private JSONObject call(String method, String path, JSONObject body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("Accept", "application/json");
            if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(bytes);
                }
            }
            int status = c.getResponseCode();
            String text = read(status >= 400 ? c.getErrorStream() : c.getInputStream());
            JSONObject json = text.isEmpty() ? new JSONObject() : parse(text);
            if (status >= 400) throw new RelayException(status, json.optString("error", "HTTP " + status));
            return json;
        } finally {
            c.disconnect();
        }
    }

    private static JSONObject parse(String text) throws IOException {
        try {
            return new JSONObject(text);
        } catch (JSONException e) {
            throw new IOException("Ungültige Antwort vom Relay", e);
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = s.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }
}
