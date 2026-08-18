package org.dwarftsch.stillzeit.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.dwarftsch.stillzeit.Entry
import org.dwarftsch.stillzeit.FlaschenArt
import org.dwarftsch.stillzeit.Seite
import org.dwarftsch.stillzeit.TodayStats
import org.dwarftsch.stillzeit.parseIsoZeit
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Spricht die Stillzeit-Tracker-REST-API an. Authentifizierung wahlweise per
 * mTLS-Client-Zertifikat ([certSource]) oder API-Key ([apiKey], gesendet als
 * `X-API-Key`-Header). Endpunkte und JSON-Felder identisch zur Flutter-App.
 */
class ApiService(
    /** Quelle für client.crt/client.key; null bei API-Key-Authentifizierung. */
    private val certSource: CertSource? = null,
    /** Basis-URL inkl. abschließendem Slash, z. B. `https://host/stillzeit-tracker/api/`. */
    private val baseUrl: String,
    /** Wird als `X-API-Key`-Header mitgesendet, falls gesetzt. */
    private val apiKey: String? = null,
) : EntryService {

    private var client: OkHttpClient? = null

    private suspend fun httpClient(): OkHttpClient {
        client?.let { return it }
        val builder = OkHttpClient.Builder()
        val source = certSource
        if (source != null) {
            val (cert, key) = source.readCredentials()
            val (factory, trust) = ClientCertificates.socketFactoryMitTrust(cert, key)
            builder.sslSocketFactory(factory, trust)
        }
        return builder.build().also { client = it }
    }

    override fun dispose() {
        // Abbau auf einen Hintergrund-Thread schieben: bei einer TLS-Verbindung
        // schreibt Conscrypt beim close() noch das close_notify ins Socket
        // (ConscryptEngineSocket.drainOutgoingQueue). Das ist echter
        // Netzwerkzugriff — auf dem Main-Thread wirft StrictMode dafür eine
        // NetworkOnMainThreadException und die App stürzt ab. Getroffen hat es
        // jeden Rückweg aus den Einstellungen, weil
        // HomeViewModel.datenquelleNeuAufbauen() direkt aus dem Back-Handler
        // läuft und die Verbindung im Server-Modus noch im Pool liegt.
        val alterClient = client ?: return
        client = null
        Thread({
            alterClient.dispatcher.executorService.shutdown()
            alterClient.connectionPool.evictAll()
        }, "stillzeit-api-dispose").start()
    }

    private fun root(): HttpUrl {
        if (baseUrl.isBlank()) {
            throw ApiException(
                "Keine API-URL konfiguriert. Bitte in den Einstellungen die " +
                    "Basis-URL des Servers hinterlegen.",
            )
        }
        return baseUrl.toHttpUrlOrNull()
            ?: throw ApiException("Ungültige API-URL: $baseUrl")
    }

    // Query-Form `/api/?id=43` statt Pfad-Form `/api/entries/43`. Die Pfad-Form
    // wird inzwischen serverseitig geroutet; die Query-Form funktioniert aber
    // auf allen Hosts inklusive der Legacy-Route ohne `/api/` – dabei bleiben.
    private fun entryUrl(id: Long): HttpUrl =
        root().newBuilder().addQueryParameter("id", id.toString()).build()

    private fun actionUrl(action: String): HttpUrl =
        root().newBuilder().addQueryParameter("action", action).build()

    private suspend fun send(method: String, url: HttpUrl, body: JSONObject? = null): Any? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .apply {
                    if (!apiKey.isNullOrEmpty()) header("X-API-Key", apiKey)
                }
                .method(method, body?.let { anfrageKoerper(it) })
                .build()

            httpClient().newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val ok = response.code in 200..299

                val decoded: Any? = if (text.isEmpty()) {
                    null
                } else {
                    runCatching { JSONObject(text) as Any }
                        .recoverCatching { JSONArray(text) as Any }
                        .getOrElse {
                            // Antwort ist kein JSON (z. B. HTML-Fehlerseite).
                            if (ok) return@use null
                            throw ApiException(
                                "Unerwartete Antwort (kein JSON): ${snippet(text)}",
                                statusCode = response.code,
                            )
                        }
                }

                if (ok) return@use decoded

                val meldung = (decoded as? JSONObject)?.optString("error")?.takeIf { it.isNotEmpty() }
                    ?: text.ifEmpty { "Anfrage fehlgeschlagen" }.let(::snippet)
                throw ApiException(meldung, statusCode = response.code)
            }
        }

    private fun anfrageKoerper(json: JSONObject): RequestBody =
        json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

    override suspend fun getEntries(): List<Entry> {
        val data = send("GET", root()) as? JSONObject
            ?: throw ApiException("Unerwartete Antwort der API.")
        val liste = data.getJSONArray("entries")
        // Einträge unbekannter Art (künftige Servererweiterungen) ausblenden.
        return (0 until liste.length()).mapNotNull { eintragAusJson(liste.getJSONObject(it)) }
    }

    override suspend fun getToday(): TodayStats {
        val data = send("GET", actionUrl("heute")) as? JSONObject
            ?: throw ApiException("Unerwartete Antwort der API.")
        fun v(key: String) = data.optInt(key, 0)
        return TodayStats(
            gesamt = v("gesamt"),
            links = v("links"),
            rechts = v("rechts"),
            beidseitig = v("beidseitig"),
            flasche = v("flasche"),
            totalMl = v("total_ml"),
            totalMinuten = v("total_minuten"),
            brei = v("brei"),
            wasser = v("wasser"),
            totalGBrei = v("total_g_brei"),
            totalMlWasser = v("total_ml_wasser"),
            // Tolerant lesen: je nach PHP-Serialisierung true/false oder 0/1.
            breiWasserAktiv = data.optBoolean("brei_wasser_aktiv", false) ||
                data.optInt("brei_wasser_aktiv", 0) != 0,
        )
    }

    override suspend fun createEntry(
        seite: Seite,
        menge: Int?,
        flaschenArt: FlaschenArt?,
        dauerMinuten: Int?,
        createTime: Instant?,
    ): Entry {
        val body = JSONObject().put("seite", seite.apiValue)
        if (seite.hatMenge) {
            body.put("menge", menge ?: 0)
            // flaschen_art akzeptiert der Server nur bei der Flasche (sonst 400).
            if (seite.isFlasche && flaschenArt != null) {
                body.put("flaschen_art", flaschenArt.apiValue)
            }
        }
        if (seite.hatDauer && dauerMinuten != null) {
            body.put("dauer_minuten", dauerMinuten)
        }
        if (createTime != null) {
            body.put("create_time", isoMitOffset(createTime))
        }
        val data = send("POST", root(), body) as? JSONObject
            ?: throw ApiException("Unerwartete Antwort der API.")
        return eintragAusJson(data)
            ?: throw ApiException("Unerwartete Antwort der API (unbekannte Seite).")
    }

    override suspend fun updateFlasche(id: Long, menge: Int, flaschenArt: FlaschenArt) {
        send(
            "PATCH",
            entryUrl(id),
            JSONObject().put("menge", menge).put("flaschen_art", flaschenArt.apiValue),
        )
    }

    override suspend fun updateMenge(id: Long, menge: Int) {
        send("PATCH", entryUrl(id), JSONObject().put("menge", menge))
    }

    override suspend fun updateDauer(id: Long, dauerMinuten: Int) {
        send("PATCH", entryUrl(id), JSONObject().put("dauer_minuten", dauerMinuten))
    }

    override suspend fun deleteEntry(id: Long) {
        send("DELETE", entryUrl(id))
    }

    private companion object {
        fun eintragAusJson(json: JSONObject): Entry? {
            val seite = Seite.fromApi(json.getString("seite")) ?: return null
            return Entry(
                id = json.getLong("id"),
                createTime = parseIsoZeit(json.getString("create_time")),
                seite = seite,
                menge = json.intOderNull("menge"),
                flaschenArt = FlaschenArt.fromApi(json.stringOderNull("flaschen_art")),
                dauerMinuten = json.intOderNull("dauer_minuten"),
                einheit = json.stringOderNull("einheit"),
            )
        }

        /**
         * Formatiert einen Zeitpunkt als ISO 8601 mit Zeitzonen-Offset,
         * z. B. `2026-06-09T14:30:00+02:00` (so erwartet es die API).
         */
        fun isoMitOffset(zeit: Instant): String =
            OffsetDateTime.ofInstant(zeit, ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx"))

        /** Kürzt eine (Fehler-)Antwort für die Anzeige. */
        fun snippet(s: String): String {
            val clean = s.replace(Regex("\\s+"), " ").trim()
            return if (clean.length > 200) clean.take(200) + "…" else clean
        }

        fun JSONObject.intOderNull(key: String): Int? =
            if (isNull(key)) null else optInt(key)

        fun JSONObject.stringOderNull(key: String): String? =
            if (isNull(key)) null else optString(key)
    }
}
