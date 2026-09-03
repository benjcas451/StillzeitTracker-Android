package org.dwarftsch.stillzeit.wear

import android.util.Base64
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.dwarftsch.stillzeit.Entry
import org.dwarftsch.stillzeit.FlaschenArt
import org.dwarftsch.stillzeit.Seite
import org.dwarftsch.stillzeit.data.AppSettings
import org.dwarftsch.stillzeit.data.CertSource
import org.dwarftsch.stillzeit.data.DataSourceMode
import org.dwarftsch.stillzeit.data.EntryService
import org.dwarftsch.stillzeit.data.createConfiguredEntryService
import org.dwarftsch.stillzeit.parseIsoZeit
import org.json.JSONArray
import org.json.JSONObject
import java.time.format.DateTimeFormatter

/**
 * Nimmt RPC-Anfragen der Wear-OS-App entgegen (`MessageClient.sendRequest`)
 * und führt sie direkt gegen die konfigurierte Datenquelle aus. Protokoll und
 * Pfad sind identisch zur bisherigen Flutter-App, die bereits veröffentlichte
 * Uhr-App funktioniert also unverändert weiter.
 *
 * Protokoll (JSON, UTF-8):
 *   Anfrage: {"action": "...", "arguments": { ... }}
 *   Antwort: {"ok": true, "data": { ... }} bzw. {"ok": false, "error": "..."}
 */
class WearRequestService : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onRequest(nodeId: String, path: String, data: ByteArray): Task<ByteArray>? {
        if (path != REQUEST_PATH) return null

        val antwort = TaskCompletionSource<ByteArray>()
        val anfrage = runCatching { JSONObject(String(data, Charsets.UTF_8)) }.getOrNull()
        val action = anfrage?.optString("action").orEmpty()
        if (action.isEmpty()) {
            antwort.setResult(fehler("Ungültige Anfrage der Uhr."))
            return antwort.task
        }
        val argumente = anfrage?.optJSONObject("arguments") ?: JSONObject()

        scope.launch {
            val ergebnis = runCatching { fuehreAus(action, argumente) }
            antwort.setResult(
                ergebnis.fold(
                    onSuccess = ::erfolg,
                    onFailure = { fehler(it.message ?: "Unbekannter Fehler.") },
                ),
            )
        }
        return antwort.task
    }

    private suspend fun fuehreAus(action: String, argumente: JSONObject): JSONObject {
        val settings = AppSettings(this)
        val certSource = CertSource(this, settings)

        return when (action) {
            "getConnection" -> verbindung(settings, certSource)

            "getDashboard" -> mitService(settings, certSource) { service -> dashboard(service, settings) }

            "createEntry" -> mitService(settings, certSource) { service ->
                val seite = seiteAus(argumente)
                service.createEntry(
                    seite = seite,
                    menge = argumente.intOderNull("menge"),
                    flaschenArt = FlaschenArt.fromApi(argumente.stringOderNull("flaschen_art")),
                    dauerMinuten = argumente.intOderNull("dauer_minuten"),
                    createTime = argumente.stringOderNull("create_time")
                        ?.let { runCatching { parseIsoZeit(it) }.getOrNull() },
                )
                WatchChangeBus.melden()
                dashboard(service, settings)
            }

            "updateEntry" -> mitService(settings, certSource) { service ->
                val id = argumente.getLong("id")
                val seite = seiteAus(argumente)
                when {
                    seite.isFlasche -> {
                        val flaschenArt = FlaschenArt.fromApi(argumente.stringOderNull("flaschen_art"))
                            ?: throw IllegalArgumentException("Flaschenart fehlt.")
                        service.updateFlasche(id, argumente.getInt("menge"), flaschenArt)
                    }
                    // Brei/Wasser: Menge ohne Flaschen-Art (Server lehnt sie ab).
                    seite.hatMenge -> service.updateMenge(id, argumente.getInt("menge"))
                    else -> service.updateDauer(id, argumente.getInt("dauer_minuten"))
                }
                WatchChangeBus.melden()
                dashboard(service, settings)
            }

            else -> throw IllegalArgumentException("Unbekannte Watch-Anfrage: $action")
        }
    }

    private suspend fun <T> mitService(
        settings: AppSettings,
        certSource: CertSource,
        aktion: suspend (EntryService) -> T,
    ): T {
        val service = createConfiguredEntryService(this, settings, certSource)
        return try {
            aktion(service)
        } finally {
            service.dispose()
        }
    }

    /**
     * Überträgt die auf dem Telefon eingerichtete Server-Verbindung an die
     * Uhr, damit diese anschließend direkt mit dem Server sprechen kann. Bei
     * der lokalen SQLite-Quelle gibt es nichts zu übernehmen — die Uhr bleibt
     * dann beim Weg über das Telefon.
     */
    private suspend fun verbindung(settings: AppSettings, certSource: CertSource): JSONObject {
        val daten = when (settings.mode) {
            DataSourceMode.DEMO -> JSONObject().put("mode", "demo")

            DataSourceMode.API_KEY -> {
                val baseUrl = settings.apiKeyBaseUrl
                pruefeBaseUrl(baseUrl)
                JSONObject()
                    .put("mode", "apiKey")
                    .put("base_url", baseUrl)
                    .put("api_key", settings.apiKey)
            }

            DataSourceMode.API -> {
                val baseUrl = settings.apiBaseUrl
                pruefeBaseUrl(baseUrl)
                // Wirft eine CertException mit sprechender Meldung, wenn die
                // Dateien fehlen oder der Ordner nicht (mehr) freigegeben ist.
                val (cert, key) = certSource.readCredentials()
                JSONObject()
                    .put("mode", "api")
                    .put("base_url", baseUrl)
                    .put("client_cert", Base64.encodeToString(cert, Base64.NO_WRAP))
                    .put("client_key", Base64.encodeToString(key, Base64.NO_WRAP))
                    // Optionaler Zusatz-Key: fehlt er, bleibt das Feld leer und
                    // die Uhr spricht wie bisher rein per Zertifikat.
                    .put("api_key", settings.mtlsApiKey)
            }
        }
        // Das lokale Opt-in wandert mit: im Direktmodus fragt die Uhr das
        // Telefon nicht mehr, und die Server-Option allein darf die beiden
        // Buttons nicht mehr unterdrücken.
        return daten.put("brei_wasser_aktiv", settings.breiWasserAktiviert)
    }

    private fun pruefeBaseUrl(baseUrl: String) {
        if (baseUrl.isEmpty()) {
            throw IllegalStateException("Auf dem Telefon ist keine API-URL hinterlegt.")
        }
    }

    /**
     * Die Uhr sendet nur Werte ihrer eigenen Buttons – ein unbekannter Wert
     * ist ein echter Fehler und wird gemeldet statt still als „Links“ gedeutet.
     */
    private fun seiteAus(argumente: JSONObject): Seite {
        val roh = argumente.optString("seite", "")
        return Seite.fromApi(roh)
            ?: throw IllegalArgumentException("Unbekannte Eintragsart: $roh")
    }

    private suspend fun dashboard(service: EntryService, settings: AppSettings): JSONObject {
        // Neueste zuerst: die Uhr zeigt den ersten Eintrag als „letzten“ an,
        // die REST-API garantiert aber keine Reihenfolge.
        val eintraege = service.getEntries().sortedByDescending { it.createTime }.take(12)
        return JSONObject()
            .put("entries", JSONArray().apply { eintraege.forEach { put(alsJson(it)) } })
            // Wie auf dem Telefon: allein das lokale Opt-in entscheidet. Der
            // frühere zusätzliche `?action=heute`-Roundtrip entfällt damit –
            // er kostete pro Uhr-Aktion eine Anfrage und konnte die Buttons
            // trotz aktivem Schalter unterdrücken.
            .put("brei_wasser_aktiv", settings.breiWasserAktiviert)
    }

    private fun alsJson(entry: Entry): JSONObject = JSONObject().apply {
        put("id", entry.id)
        // java.time auf der Uhr erwartet eine explizite Zeitzone; UTC mit "Z"
        // wie bisher (Gegenstück: WatchEntry im :wear-Modul).
        put("create_time", DateTimeFormatter.ISO_INSTANT.format(entry.createTime))
        put("seite", entry.seite.apiValue)
        entry.menge?.let { put("menge", it) }
        entry.flaschenArt?.let { put("flaschen_art", it.apiValue) }
        entry.dauerMinuten?.let { put("dauer_minuten", it) }
        entry.anzeigeEinheit?.let { put("einheit", it) }
    }

    private fun erfolg(daten: JSONObject): ByteArray =
        JSONObject()
            .put("ok", true)
            .put("data", daten)
            .toString()
            .toByteArray(Charsets.UTF_8)

    private fun fehler(meldung: String): ByteArray =
        JSONObject()
            .put("ok", false)
            .put("error", meldung)
            .toString()
            .toByteArray(Charsets.UTF_8)

    private fun JSONObject.intOderNull(key: String): Int? =
        if (isNull(key)) null else optInt(key)

    private fun JSONObject.stringOderNull(key: String): String? =
        if (isNull(key)) null else optString(key)

    private companion object {
        /** Muss zu `PhoneConnection.REQUEST_PATH` im :wear-Modul passen. */
        const val REQUEST_PATH = "/stillzeit/request"
    }
}
