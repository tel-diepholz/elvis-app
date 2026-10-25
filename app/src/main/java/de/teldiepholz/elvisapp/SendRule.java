package de.teldiepholz.elvisapp;

/**
 * Wann die App eine Position meldet – dieselbe Regel wie shouldSendPosition in ELVIS (packages/shared/src/applink.ts):
 * die erste sofort, danach bei 10 m Bewegung, spätestens aber alle 10 Minuten.
 */
final class SendRule {
    static final long INTERVAL_MS = 10 * 60_000L;
    static final double DISTANCE_M = 10;

    private SendRule() {}

    /** @param lastSentAt 0 = noch nichts gesendet */
    static boolean shouldSend(double lastLat, double lastLon, long lastSentAt, double lat, double lon, long now) {
        if (lastSentAt == 0) return true;
        return now - lastSentAt >= INTERVAL_MS || distanceM(lastLat, lastLon, lat, lon) >= DISTANCE_M;
    }

    /** Abstand in Metern (Haversine) */
    static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        double rad = Math.PI / 180;
        double dLat = (lat2 - lat1) * rad;
        double dLon = (lon2 - lon1) * rad;
        double h = Math.pow(Math.sin(dLat / 2), 2) + Math.cos(lat1 * rad) * Math.cos(lat2 * rad) * Math.pow(Math.sin(dLon / 2), 2);
        return 2 * 6_371_000 * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    /** App-ID zum Vorlesen in zwei Vierergruppen: K7M2QX9P → K7M2-QX9P */
    static String formatDeviceId(String id) {
        return id != null && id.length() == 8 ? id.substring(0, 4) + "-" + id.substring(4) : id;
    }

    /** Code zum Vorlesen in zwei Dreiergruppen: 123456 → 123 456 */
    static String formatCode(String code) {
        return code != null && code.length() == 6 ? code.substring(0, 3) + " " + code.substring(3) : code;
    }
}
