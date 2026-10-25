# ELVIS Ortung (Android)

Kleine Android-App, mit der Einsatzkräfte ihre Position an den Einsatzleitwagen (ELW) mit
[ELVIS](https://github.com/tel-diepholz/elvis) senden. Die Einsatzleitung koppelt die App in der Kräfteübersicht mit
einer Einheit, danach erscheint die Einheit auf der Lagekarte.

Die Positionen laufen über das Cloud-Relay von ELVIS (`apps/relay`, Cloudflare Worker). Der ELW holt sie dort ab und
braucht deshalb keine öffentliche Adresse. Einrichtung des Relays: ELVIS-README, Abschnitt „App-Anbindung“.

## Ablauf

1. App starten. Sie meldet sich beim Relay an und zeigt eine **App-ID** (z. B. `K7M2-QX9P`) und einen **Code**.
2. Die Einsatzleitung trägt beides in ELVIS ein: *Kräfte → Smartphone-Symbol bei der Einheit*.
3. Die App erkennt die Kopplung innerhalb von 10 Sekunden. Sie fragt nach der Standortberechtigung (und ab Android 13
   nach Benachrichtigungen) und startet die Ortung.
4. Solange die Ortung läuft, steht eine Benachrichtigung „ELVIS-Ortung aktiv“ in der Leiste, mit Knopf „Ortung
   stoppen“. Die App meldet die erste Position sofort, danach ab 10 m Bewegung und spätestens alle 10 Minuten,
   auch bei ausgeschaltetem Bildschirm.
5. Die Ortung endet von selbst, wenn ELVIS die Kopplung beendet: beim Entkoppeln, beim Entlassen der Einheit oder
   mit dem Einsatzende. Die App zeigt danach wieder ID und einen neuen Code.

Stoppt man die Ortung selbst, startet sie erst wieder, wenn man in der App „Ortung starten“ tippt oder die App neu
gekoppelt wird.

## Technik

- Reines Java ohne Fremdbibliotheken (wie [ELVIS Bildschirm](https://github.com/tel-diepholz/elvis-tv)), ab Android 8.
- Die Ortung läuft als **Vordergrunddienst** vom Typ `location` mit dem eingebauten `LocationManager` (GPS,
  ersatzweise Netzwerk). Weil der Dienst mit sichtbarer Benachrichtigung läuft, braucht die App **keine
  Berechtigung für Standort im Hintergrund**. Sie fragt nur „bei Nutzung der App“.
- Fragt alle 30 Sekunden eine Ortung an. Ob sie meldet, entscheidet `SendRule`, nach derselben Regel wie
  `shouldSendPosition` in ELVIS. Ohne fällige Meldung fragt sie alle 2 Minuten beim Relay nach, ob die Kopplung
  noch besteht.
- Ist das Relay nicht erreichbar, versucht sie es mit der nächsten Ortung erneut. Es zählt nur die neueste Position,
  ältere werden nicht nachgeschickt.
- **Gespeichert** wird nur in der App: App-ID, Zugangsschlüssel, Code, Kopplungsstatus und die letzte gesendete
  Position. „Neue App-ID“ löscht die Anmeldung beim Relay.
- Manche Hersteller beenden Hintergrunddienste trotz Benachrichtigung. Dann unter **Akku-Optimierung** die App
  von der Optimierung ausnehmen.
- Nach einem Neustart des Telefons läuft die Ortung nicht von selbst weiter. Android erlaubt Standortdienste ohne
  Hintergrundberechtigung nur aus der geöffneten App. Einmal die App öffnen, dann startet sie wieder.

## Relay-Adresse

Die Vorgabe wird beim Bauen eingesetzt, aus der Repository-Variable `ELVIS_APP_RELAY_URL`
(*Settings → Secrets and variables → Actions → Variables*), z. B. `https://elvis-relay.<konto>.workers.dev`.
In der App lässt sie sich unter „Relay-Adresse“ ändern. Ohne Vorgabe fragt die App beim ersten Start danach.
HTTP ist nur für `10.0.2.2` und `localhost` erlaubt, zum Testen mit `wrangler dev` im Emulator.

## Bauen

GitHub Actions baut bei jedem Push die APK und das App Bundle (Artefakt `elvis-app`) und führt die Unit-Tests aus.
Ein Tag `v*`, z. B. `v1.0.0`, legt zusätzlich ein Release mit APK und `.aab` an. Die neueste APK liegt dann unter
<https://github.com/tel-diepholz/elvis-app/releases/latest/download/elvis-app.apk>.

Lokal geht es mit JDK 17, Android SDK (`ANDROID_HOME`) und Gradle 8.10:

```sh
gradle testReleaseUnitTest assembleRelease bundleRelease
# → app/build/outputs/apk/release/elvis-app.apk, app/build/outputs/bundle/release/app-release.aab
```

### Signierschlüssel

Android installiert ein Update nur, wenn es mit demselben Schlüssel signiert ist wie die installierte Version. Der
Play Store verlangt außerdem einen Upload-Schlüssel. Deshalb einmalig einen Schlüssel erzeugen, sicher aufbewahren
und als Repository-Secrets hinterlegen:

```sh
keytool -genkeypair -v -keystore elvis-app.jks -alias elvis-app -keyalg RSA -keysize 4096 -validity 36500
base64 -w0 elvis-app.jks   # → Secret ELVIS_APP_KEYSTORE_BASE64
```

| Secret | Inhalt |
| --- | --- |
| `ELVIS_APP_KEYSTORE_BASE64` | Keystore, base64-kodiert |
| `ELVIS_APP_KEYSTORE_PASSWORD` | Passwort des Keystores |
| `ELVIS_APP_KEY_ALIAS` | Alias, z. B. `elvis-app` |
| `ELVIS_APP_KEY_PASSWORD` | Passwort des Schlüssels |

Ohne Secrets signiert der Build mit dem Debug-Schlüssel und warnt.

### Play Store

- Das App Bundle (`.aab`) aus dem Release hochladen. Mit „Play App Signing“ wird der Schlüssel oben zum
  Upload-Schlüssel.
- **Datenschutzerklärung:** `<relay>/datenschutz`. Vorher in `apps/relay/src/privacy.ts` den Betreiber ergänzen.
- **Vordergrunddienst „location“:** In der Play Console unter *App-Inhalte → Berechtigungen für Dienste im
  Vordergrund* begründen, etwa „Übermittelt während eines Feuerwehreinsatzes den Standort der Einsatzkraft an die
  Einsatzleitung, solange die Kopplung besteht; sichtbar durch eine dauerhafte Benachrichtigung und jederzeit
  stoppbar.“ Verlangt wird ein kurzes Video vom Ablauf.
- **Datensicherheit:** Erfasst wird der genaue Standort (App-Funktionalität), weitergegeben an die gekoppelte
  Einsatzleitung, verschlüsselt übertragen und löschbar („Neue App-ID“).

## Lizenz

MIT, siehe [LICENSE](LICENSE).
