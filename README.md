# Stillzeit (Android + Wear OS)

Native Android-App zum Erfassen von Still- und Flaschenmahlzeiten, mit
eigenständiger Wear-OS-App. Kotlin, Jetpack Compose, AGP 9 mit Built-in
Kotlin. Portiert von einer Flutter-App — Bestandsdaten und -einstellungen
werden beim Update nahtlos übernommen (Details unten).

Schwester-Repo: **StillzeitTracker-XCode** (iOS + watchOS, gleicher
Funktionsumfang, gleiches Design, gleiches Watch-Protokoll).

---

## Module

| Modul | Was | applicationId |
|---|---|---|
| `:app` | Telefon-App (Compose, Material 3) | `org.dwarftsch.stillzeit` |
| `:wear` | Wear-OS-App (Compose for Wear OS) | `org.dwarftsch.stillzeit` (namespace `…stillzeit.wear`) |

Beide Module tragen **dieselbe applicationId** — Voraussetzung dafür, dass
Play die Uhr-App als Wear-Variante derselben App ausliefert und die
Data-Layer-API Telefon und Uhr einander zuordnet. Deshalb niemals beide
Debug-Varianten wahllos installieren: `:wear:installDebug` würde auf einem
Telefon-Emulator die Telefon-App ersetzen. Immer gezielt per
`adb -s <gerät> install` arbeiten.

## Einrichtung auf einem neuen Gerät

1. Repo klonen, in **Android Studio** öffnen — fertig. Es gibt keine
   externen Abhängigkeiten außer Maven-Artefakten.
2. **JDK:** Der Gradle-Daemon provisioniert sich sein JDK (Version 25)
   selbst über `gradle/gradle-daemon-jvm.properties` (Foojay-Resolver).
   Zum *Starten* des Gradle-Launchers genügt irgendein JDK 17–25 —
   Achtung: ein zu neues JDK im PATH (z. B. 26) kann den Launcher brechen;
   dann `JAVA_HOME` z. B. auf das JBR von Android Studio setzen
   (`/Applications/Android Studio.app/Contents/jbr/Contents/Home`).
3. **Signing (nur für Release-Builds nötig):** `key.properties` nach dem
   Muster von `key.properties.example` im Repo-Root anlegen. Die Datei und
   der Keystore (`*.jks`) sind gitignored und dürfen **nie** eingecheckt
   werden. Ohne `key.properties` signieren Release-Builds automatisch mit
   dem Debug-Key (lokal baubar, aber nicht Play-tauglich).
   Wichtig: Telefon- und Uhr-App **müssen** denselben Upload-Key tragen,
   sonst verweigert die Data-Layer-API die Kommunikation.

## Bauen & Testen

```bash
# Debug-APKs
./gradlew :app:assembleDebug :wear:assembleDebug

# Release (signiert, falls key.properties vorhanden)
./gradlew :app:assembleRelease :wear:assembleRelease

# Play-Bundles; buildNumber steuert den versionCode (siehe Versionierung)
./gradlew :app:bundleRelease :wear:bundleRelease -PbuildNumber=123

# Wear-App gezielt auf dem Uhr-Emulator installieren und starten
adb -s <wear-emulator> install -r wear/build/outputs/apk/debug/wear-debug.apk
adb -s <wear-emulator> shell am start \
  -n org.dwarftsch.stillzeit/org.dwarftsch.stillzeit.wear.MainActivity
```

Die Wear-App verlangt keine gekoppelte Uhr zum Starten — ohne erreichbares
Telefon zeigt sie „Handy nicht erreichbar“ und einen Reconnect-Button.

## Versionierung

- `versionName`: manuell in `app/` und `wear/build.gradle.kts`.
  **Konvention:** Major/Minor (1.x.x, x.1.x) sind über alle Plattformen
  (Android **und** iOS) identisch; die Patch-Stelle darf pro Plattform
  divergieren.
- `versionCode`: `-PbuildNumber=<n>` (lokaler Fallback im Buildfile).
  Die CI übergibt `100 + github.run_number`; die Uhr addiert fest `+1000`.
  Play verlangt strikt steigende Codes **pro Formfaktor-Track**.

## CI / Releases (`.github/workflows/build-aab.yml`)

Manuell per *workflow_dispatch*. Ein Lauf:

1. baut signierte **APKs** (Telefon + Wear) und hängt sie an ein
   GitHub-Release (`v<version>-<run_number>`) — direkt installierbar; die
   `<version>` liest der Workflow aus `app/build.gradle.kts`, sie darf also
   nirgends im Workflow doppelt gepflegt werden,
2. baut zusätzlich **App Bundles** und lädt sie in die Play-Tracks —
   nur wenn der Schalter `play_upload` (Default: an) gesetzt ist.

Benötigte **Repository-Secrets** (nur Namen, Werte niemals dokumentieren):
`PLAY_KEYSTORE_BASE64`, `PLAY_KEYSTORE_PASSWORD`, `PLAY_KEY_ALIAS`,
`PLAY_KEY_PASSWORD`, `PLAY_SERVICE_ACCOUNT_JSON`.

**Play-Besonderheiten (hart erarbeitet, nicht ändern ohne Grund):**
- Wear OS braucht seit 2023 einen **eigenen Formfaktor-Track**. Telefon-
  und Wear-Bundle müssen in **getrennten Schritten** (= getrennten
  Play-Edits) hochgeladen werden, sonst scheitert der Commit des Edits
  mit „Internal error encountered“.
- Der Track-Name ist **case-sensitiv**: Telefon → `alpha`,
  Uhr → `wear:Alpha` (der Closed-Testing-Track heißt in der Console „Alpha“).
- Play verteilt aus dem Wear-Track automatisch an gekoppelte Uhren —
  Nutzer installieren nichts separat.

## Herkunft & Datenmigration (Flutter → nativ)

Die App ersetzt eine Flutter-App unter derselben applicationId. Beim
Update bleiben alle Nutzerdaten erhalten:

- **SQLite:** identische Datei `stillzeit_demo.db` (Standard-Datenbankpfad),
  identisches Schema, `user_version 3` inkl. Upgrade-Pfad von v1/v2.
  Zeitstempel als ISO 8601 UTC (lexikalisch sortierbar); der Parser
  toleriert auch die Mikrosekunden-Präzision alter Dart-Einträge.
- **Einstellungen:** einmalige Migration aus `FlutterSharedPreferences`
  (Keys mit Präfix `flutter.`) in die nativen Prefs, siehe `AppSettings`.
- **SAF-Berechtigung** des Zertifikats-Ordners überlebt das Update.

## Architektur (`:app`)

```
Models.kt                    Seite/FlaschenArt/Entry/TodayStats, ISO-Parser
data/EntryService.kt         gemeinsames Interface der Datenquellen
data/DemoService.kt          lokale SQLite (sqflite-kompatibel)
data/ApiService.kt           REST-Client (OkHttp; PATCH + mTLS)
data/ClientCertificates.kt   PEM (crt/key) -> SSLSocketFactory, inkl. PKCS#1->#8
data/CertSource.kt           SAF-Ordner mit client.crt/client.key
data/AppSettings.kt          Prefs + Flutter-Migration
data/LocalBackupService.kt   JSON-Backup (Format kompatibel zu iOS/Flutter)
wear/WearRequestService.kt   Data-Layer-RPC-Endpunkt für die Uhr
ui/…                         Compose-UI (Theme, Home, Settings, Dialoge)
```

**Datenquellen (vom Nutzer wählbar):** Server per mTLS-Client-Zertifikat,
Server per API-Key (`X-API-Key`-Header) oder lokale SQLite ohne Sync.

## Watch-Protokoll (Data-Layer-API)

Die Uhr sendet `MessageClient.sendRequest` an den Pfad
`/stillzeit/request`; `WearRequestService` antwortet. JSON, UTF-8:

```
Anfrage:  {"action": "...", "arguments": { ... }}
Antwort:  {"ok": true, "data": { ... }}  bzw.  {"ok": false, "error": "..."}
```

Aktionen: `getConnection` (überträgt die Server-Konfiguration des Telefons
an die Uhr, bei mTLS inkl. PEM als Base64, dazu `brei_wasser_aktiv` als
Opt-in-Stand des Telefons), `getDashboard` (letzte 12 Einträge, neueste
zuerst, plus `brei_wasser_aktiv`), `createEntry`, `updateEntry`. Das
Telefon meldet die Capability
`stillzeit_phone_app` (res/values/wear.xml). **Dieses Protokoll ist
byte-identisch zur iOS/watchOS-Strecke** — Änderungen immer in beiden
Repos nachziehen.

## REST-API & Datenmodell

Basis-URL konfiguriert der Nutzer in den Einstellungen. Alle Antworten JSON.

| Endpunkt | Zweck |
|---|---|
| `GET <base>` | alle Einträge: `{"entries": [...]}` |
| `GET <base>?action=heute` | Tagesstatistik (gesamt, links, rechts, beidseitig, flasche, total_ml, total_minuten + brei, wasser, total_g_brei, total_ml_wasser, brei_wasser_aktiv) |
| `GET <base>?action=last` | letzter Eintrag |
| `POST <base>` | Eintrag anlegen |
| `PATCH <base>?id=42` | Menge (Flasche/Brei/Wasser; Flaschenart nur Flasche) bzw. Dauer ändern |
| `DELETE <base>?id=42` | Eintrag löschen |

Eintrag: `id`, `create_time` (ISO 8601 mit Zeitzonen-Offset), `seite`
(`Links`/`Rechts`/`Beidseitig`/`Flasche` sowie `Brei`/`Wasser`, wenn die
pro Familie schaltbare Server-Option aktiv ist — `?action=heute` liefert
`brei_wasser_aktiv` und die Zusatzfelder `brei`, `wasser`, `total_g_brei`,
`total_ml_wasser`; `gesamt` zählt weiterhin nur Milchmahlzeiten), `menge`
(Flasche/Wasser in ml, Brei in g), `einheit` (`ml`/`g`/null),
`flaschen_art` (`Pre`/`Mutter`, nur Flasche), `dauer_minuten` (nur
Still-Einträge). Fehler: `{"error": "..."}` mit passendem HTTP-Status.
Die Query-Form (`?id=42`) ist Absicht — sie funktioniert auf allen Hosts
inklusive der Legacy-Route ohne `/api/`. Die lokale Tabelle `entries`
spiegelt exakt dieses Modell.

## Sichtbarkeit von „Brei & Wasser“

Über die beiden Erfassungs-Buttons entscheidet **allein** das lokale Opt-in
unter Einstellungen → „Brei & Wasser“ (Default aus) — in jedem Modus, auf
Telefon und Uhr. Die Uhr bekommt den Stand per `getDashboard` (Relay) bzw.
beim Verbindungsimport (Direktmodus).

`brei_wasser_aktiv` aus `?action=heute` ist seit 2.2.1 nur noch ein
**Hinweis**: Ist es aus, warnen die Einstellungen, dass der Server Einträge
ablehnen könnte — verstecken die Buttons aber nicht mehr. Bis 2.2.0 waren
beide Bedingungen UND-verknüpft; Server, die das Flag gar nicht liefern,
machten den Schalter damit wirkungslos.

## Design-System „Minze & Honig“ (v1.0)

Quelle der Wahrheit im Code: `app/…/ui/Theme.kt` (Telefon) und die
Farbsektion in `wear/…/StillzeitWearApp.kt` (Uhr). Kernregeln:

- **Grundregel:** Weiß dominiert (~80 %), Farbe liegt *auf* dem Grund —
  nie als Seitenhintergrund. Dark: Grund `#1F2221`, Karten `#292D2B`,
  Ränder `#3A403C` — kein reines Schwarz.
- **Skalen 50–900** je Markenfarbe. 300 = Markenton (Flächen/Buttons),
  100 = zarte Hinweisfläche, 600/700 = text-/icontauglich auf Weiß,
  900 = Text auf 300er-Flächen. **Pastell (300) nie als Text auf Weiß.**
- **Markenfarben:** Minze (Primär) `#A8D5BA`/300, Honig (Sekundär)
  `#F7E8A4`/300, Flieder (Akzent, sparsam) `#CDB4DB`/300; Grau leicht
  grünstichig (`#F6F8F6` … `#1F2221`); Rot nur semantisch
  (Fehler/Löschen: Fläche 100 `#FAE3E1`, Text 700 `#96362F`, dark 300 `#F0B6B1`).
- **Eintragsarten (Chip-Muster, plattformübergreifend identisch):**
  Links = Minze, Rechts = Flieder, Flasche = Honig, Beidseitig = Grau,
  Brei = Honig eine Stufe dunkler (400), Wasser = neutrales Grau.
  Kacheln/Buttons: Fläche 300 + Text 900; Listen-Avatare: zarte Fläche
  (100 bzw. Dark-Äquivalent `#263B2F`/`#3B3524`/`#352B3C`) + Icon 700/300.
- **Dark Mode:** Pastellflächen (300) bleiben unverändert mit 900er-Text;
  100er-Flächen werden zu den abgedunkelten Äquivalenten.
- **Typografie:** ausschließlich **Nunito** (eingebettet, OFL — Lizenz in
  `app/src/main/assets/OFL_NUNITO.txt`); Persönlichkeit über das Gewicht
  (400/600/700/800), keine Schriftmischung.
- **Form:** Radius 8 (klein) / 12 (Buttons, Inputs) / 16 (Karten) /
  24 (Dialoge) / Pill (Chips). Buttons min. 44 dp Höhe.
- Kontraste sind WCAG-AA-geprüft; Farbe nie als einziger Informationsträger.

## Sicherheit / was nie ins Repo darf

`key.properties`, `*.jks`, Play-Service-Account-JSON, API-Keys,
Server-URLs von Nutzern. Die `.gitignore` deckt das ab — bei neuen
Secrets zuerst dort eintragen, dann anlegen.
