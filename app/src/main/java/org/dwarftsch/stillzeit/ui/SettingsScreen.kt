package org.dwarftsch.stillzeit.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dwarftsch.stillzeit.data.AppSettings
import org.dwarftsch.stillzeit.data.CertSource
import org.dwarftsch.stillzeit.data.DataSourceMode
import org.dwarftsch.stillzeit.data.DemoService
import org.dwarftsch.stillzeit.data.LocalBackupService

/** Beschreibung der REST-API (für den Dialog "Aufbau API"). */
private const val API_INFO_TEXT = """
Die App spricht eine REST-API unter der eingestellten Basis-URL an. Alle Antworten sind JSON.

Endpunkte:

• GET <Basis-URL>
  Alle Einträge: {"entries": [...]}

• GET <Basis-URL>?action=heute
  Tagesstatistik: gesamt, links, rechts, beidseitig, flasche, total_ml, total_minuten

• GET <Basis-URL>?action=last
  Letzter Eintrag (oder leeres Objekt)

• POST <Basis-URL>
  Neuen Eintrag anlegen, Body z. B.:
  {"seite": "Links", "dauer_minuten": 12}
  {"seite": "Links"}
  {"seite": "Flasche", "menge": 120, "flaschen_art": "Pre"}
  {"seite": "Brei", "menge": 90} bzw. {"seite": "Wasser", "menge": 30}
  Optional "create_time" als ISO 8601 mit Zeitzonen-Offset, z. B. 2026-06-10T14:30:00+02:00

• PATCH <Basis-URL>?id=42
  Flasche ändern: {"menge": 150, "flaschen_art": "Mutter"}
  Brei/Wasser ändern: {"menge": 120}
  Stilldauer ändern: {"dauer_minuten": 15}

• DELETE <Basis-URL>?id=42
  Eintrag löschen

Gültige Werte für "seite": Links, Rechts, Beidseitig, Flasche sowie – wenn die Server-Option "Brei & Wasser" der Familie aktiv ist (?action=heute liefert "brei_wasser_aktiv") – Brei und Wasser.

Authentifizierung je nach Datenquelle:
• Server (mTLS-API): Client-Zertifikat (client.crt + client.key)
• Server (API-Key): HTTP-Header "X-API-Key: <Key>"

Ein Eintrag hat die Felder id, create_time, seite, menge (bei Flasche/Wasser in ml, bei Brei in g), einheit ("ml", "g" oder null), flaschen_art (Pre oder Mutter, nur bei Flasche) und dauer_minuten (optionale Stilldauer in Minuten, nur bei Links/Rechts/Beidseitig). Fehler kommen als {"error": "..."} mit passendem HTTP-Statuscode.
"""

/** Beschreibung der lokalen SQLite-Datenbank (für den Dialog "Aufbau Datenbank"). */
private const val DB_INFO_TEXT = """
Im Modus "Lokal (SQLite)" speichert die App alle Einträge in der Datenbank stillzeit_demo.db im app-privaten Speicher. Andere Apps haben keinen Zugriff, es findet keine Synchronisation statt.

Tabelle "entries":

• id
  INTEGER, Primärschlüssel (Auto-Increment)

• create_time
  TEXT, Zeitpunkt als ISO 8601 in UTC gespeichert (dadurch chronologisch sortierbar), Anzeige in lokaler Zeit

• seite
  TEXT: Links, Rechts, Beidseitig, Flasche, Brei oder Wasser

• menge
  INTEGER, Menge (Flasche/Wasser in ml, Brei in g) – sonst NULL

• flaschen_art
  TEXT, Pre oder Mutter – nur bei Flasche gesetzt, bei älteren Einträgen ggf. NULL

• dauer_minuten
  INTEGER, optionale Stilldauer in Minuten – nur bei Links/Rechts/Beidseitig gesetzt, sonst NULL

Die Server-API verwendet dasselbe Datenmodell, Einträge sind also identisch aufgebaut (id, create_time, seite, menge, flaschen_art, dauer_minuten).
"""

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    certSource: CertSource,
    onZurueck: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var mode by remember { mutableStateOf(settings.mode) }
    var apiUrl by remember { mutableStateOf(settings.apiBaseUrl) }
    var apiKeyUrl by remember { mutableStateOf(settings.apiKeyBaseUrl) }
    var apiKey by remember { mutableStateOf(settings.apiKey) }
    var apiKeySichtbar by remember { mutableStateOf(false) }
    var certsOk by remember { mutableStateOf(false) }
    var beschaeftigt by remember { mutableStateOf(false) }
    var infoDialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    var restoreBestaetigen by remember { mutableStateOf(false) }

    fun zeige(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    suspend fun pruefeZertifikate(): Boolean =
        runCatching { certSource.readCredentials() }.isSuccess

    LaunchedEffect(Unit) { certsOk = pruefeZertifikate() }

    // Ordner-Auswahl für die mTLS-Zertifikate (Storage Access Framework).
    val ordnerWahl = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            runCatching { certSource.uebernehmeOrdner(uri) }
                .onFailure { zeige("Fehler bei der Ordnerauswahl: ${it.meldung()}") }
            scope.launch {
                certsOk = pruefeZertifikate()
                zeige(if (certsOk) "Zertifikate gefunden." else "Keine Zertifikate gefunden.")
            }
        }
    }

    // Backup-Export: Ziel-Datei über den System-Speichern-Dialog wählen.
    val backupSpeichern = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            beschaeftigt = true
            scope.launch {
                runCatching {
                    val json = LocalBackupService.exportJson(DemoService(context).exportRows())
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use {
                            it.write(json.toByteArray(Charsets.UTF_8))
                        } ?: error("Datei ließ sich nicht schreiben.")
                    }
                }.fold(
                    onSuccess = { zeige("Backup gespeichert.") },
                    onFailure = { zeige("Backup fehlgeschlagen: ${it.meldung()}") },
                )
                beschaeftigt = false
            }
        }
    }

    // Backup-Restore: Quelldatei wählen, validieren, Bestand ersetzen.
    val backupOeffnen = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            beschaeftigt = true
            scope.launch {
                runCatching {
                    val text = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use {
                            it.readBytes().toString(Charsets.UTF_8)
                        } ?: error("Datei ließ sich nicht lesen.")
                    }
                    val rows = LocalBackupService.parseUndValidiere(text)
                    DemoService(context).replaceAll(rows)
                    rows.size
                }.fold(
                    onSuccess = { zeige("Wiederherstellung erfolgreich: $it Einträge.") },
                    onFailure = { zeige("Wiederherstellung fehlgeschlagen: ${it.meldung()}") },
                )
                beschaeftigt = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                navigationIcon = {
                    IconButton(onClick = onZurueck) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innenAbstand ->
        Column(
            modifier = Modifier
                .padding(innenAbstand)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Abschnitt("Datenquelle")

            ModusZeile(
                gewaehlt = mode == DataSourceMode.API,
                titel = "Server (mTLS-API)",
                untertitel = "Synchronisation per Client-Zertifikat",
            ) { mode = DataSourceMode.API; settings.mode = mode }
            ModusZeile(
                gewaehlt = mode == DataSourceMode.API_KEY,
                titel = "Server (API-Key)",
                untertitel = "API-Key empfohlen (statt Zertifikat)",
            ) { mode = DataSourceMode.API_KEY; settings.mode = mode }
            ModusZeile(
                gewaehlt = mode == DataSourceMode.DEMO,
                titel = "Lokal (SQLite)",
                untertitel = "Einträge bleiben nur auf diesem Gerät",
            ) { mode = DataSourceMode.DEMO; settings.mode = mode }

            if (mode == DataSourceMode.API_KEY) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Abschnitt("Server (API-Key)")
                UrlFeld(wert = apiKeyUrl, onAenderung = {
                    apiKeyUrl = it
                    settings.apiKeyBaseUrl = it
                })
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = {
                        apiKey = it
                        settings.apiKey = it
                    },
                    shape = MaterialTheme.shapes.medium,
                    colors = mhEingabefeldFarben(),
                    label = { Text("API-Key (optional)") },
                    supportingText = { Text("Empfohlen. Ohne API-Key nur für interne Testzwecke.") },
                    singleLine = true,
                    visualTransformation = if (apiKeySichtbar) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    trailingIcon = {
                        IconButton(onClick = { apiKeySichtbar = !apiKeySichtbar }) {
                            Icon(
                                if (apiKeySichtbar) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (apiKeySichtbar) "Verbergen" else "Anzeigen",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }

            if (mode == DataSourceMode.API) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Abschnitt("Server (mTLS-API)")
                UrlFeld(wert = apiUrl, onAenderung = {
                    apiUrl = it
                    settings.apiBaseUrl = it
                })
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (certsOk) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
                        contentDescription = null,
                        tint = if (certsOk) MinzeHonig.farben.erfolg else MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(if (certsOk) "Zertifikate gefunden" else "Keine Zertifikate gefunden")
                        Text(
                            certSource.locationLabel ?: "Kein Ordner ausgewählt",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                FlowRow(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = { ordnerWahl.launch(null) }, shape = MaterialTheme.shapes.medium) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Zertifikats-Ordner wählen")
                    }
                    OutlinedButton(shape = MaterialTheme.shapes.medium, colors = ButtonDefaults.outlinedButtonColors(contentColor = MinzeHonig.farben.gruenText), onClick = {
                        scope.launch {
                            certsOk = pruefeZertifikate()
                            zeige(if (certsOk) "Zertifikate gefunden." else "Keine Zertifikate gefunden.")
                        }
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Erneut prüfen")
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Abschnitt("Brei & Wasser")
            // Lokales Opt-in (Default aus): allein dieser Schalter blendet die
            // beiden Erfassungs-Buttons ein – in jedem Modus. Der Stand der
            // Server-Option ist nur noch ein Hinweis (siehe unten).
            var breiWasserAktiviert by remember { mutableStateOf(settings.breiWasserAktiviert) }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Brei & Wasser erfassen")
                    Text(
                        "Zusätzliche Buttons für Brei (g) und Wasser (ml).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = breiWasserAktiviert,
                    onCheckedChange = {
                        breiWasserAktiviert = it
                        settings.breiWasserAktiviert = it
                    },
                )
            }
            // Der Server kann die Eintragsarten pro Familie abschalten und
            // lehnt sie dann mit 400 ab. Das ist ein Hinweis, kein Riegel:
            // meldet ein Server das Flag gar nicht erst, bliebe der Schalter
            // sonst wirkungslos (Fehlerbild bis 2.2.0).
            if (breiWasserAktiviert && !settings.serverOptionZuletztAktiv()) {
                Text(
                    "Dieser Server hat die Option für deine Familie zuletzt nicht " +
                        "gemeldet. Legst du trotzdem Brei oder Wasser an, kann er " +
                        "den Eintrag ablehnen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                )
            }

            if (mode == DataSourceMode.DEMO) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Abschnitt("Backup")
                Button(
                    onClick = { backupSpeichern.launch(LocalBackupService.dateiname()) },
                    enabled = !beschaeftigt,
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    Icon(Icons.Filled.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (beschaeftigt) "Bitte warten …" else "Zu Google Drive speichern")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { restoreBestaetigen = true },
                    enabled = !beschaeftigt,
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MinzeHonig.farben.gruenText),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    Icon(Icons.Filled.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Backup wiederherstellen")
                }
                Text(
                    "Im Dateidialog Google Drive als Ziel wählen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Abschnitt("Erklärung")
            FlowRow(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(shape = MaterialTheme.shapes.medium, colors = ButtonDefaults.outlinedButtonColors(contentColor = MinzeHonig.farben.gruenText), onClick = { infoDialog = "Aufbau API" to API_INFO_TEXT.trim() }) {
                    Icon(Icons.Outlined.Cloud, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Aufbau API")
                }
                OutlinedButton(shape = MaterialTheme.shapes.medium, colors = ButtonDefaults.outlinedButtonColors(contentColor = MinzeHonig.farben.gruenText), onClick = { infoDialog = "Aufbau Datenbank" to DB_INFO_TEXT.trim() }) {
                    Icon(Icons.Filled.Storage, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Aufbau Datenbank")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    infoDialog?.let { (titel, text) ->
        InfoDialog(titel = titel, text = text, onSchliessen = { infoDialog = null })
    }

    if (restoreBestaetigen) {
        AlertDialog(
            shape = MaterialTheme.shapes.extraLarge,
            onDismissRequest = { restoreBestaetigen = false },
            title = { Text("Backup wiederherstellen?") },
            text = {
                Text(
                    "Alle aktuell lokal gespeicherten Einträge werden durch den Inhalt " +
                        "des Backups ersetzt. Dieser Vorgang kann nicht rückgängig gemacht werden.",
                )
            },
            confirmButton = {
                Button(
                    shape = MaterialTheme.shapes.medium,
                    onClick = {
                        restoreBestaetigen = false
                        backupOeffnen.launch(arrayOf("application/json"))
                    },
                ) {
                    Text("Backup auswählen")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { restoreBestaetigen = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = MinzeHonig.farben.gruenText),
                ) { Text("Abbrechen") }
            },
        )
    }
}

@Composable
private fun Abschnitt(titel: String) {
    Text(
        titel,
        style = MaterialTheme.typography.titleSmall,
        color = MinzeHonig.farben.sektionsTitel,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun ModusZeile(
    gewaehlt: Boolean,
    titel: String,
    untertitel: String,
    onWahl: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onWahl)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = gewaehlt,
            onClick = onWahl,
            colors = RadioButtonDefaults.colors(selectedColor = MinzeHonig.farben.gruenText),
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(titel)
            Text(
                untertitel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Eingabefeld für eine API-Basis-URL. */
@Composable
private fun UrlFeld(wert: String, onAenderung: (String) -> Unit) {
    OutlinedTextField(
        value = wert,
        onValueChange = onAenderung,
        shape = MaterialTheme.shapes.medium,
        colors = mhEingabefeldFarben(),
        label = { Text("API-URL") },
        supportingText = { Text("Basis-URL der API inkl. abschließendem /") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
}
