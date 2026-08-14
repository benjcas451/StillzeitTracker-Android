package org.dwarftsch.stillzeit.wear

import java.io.IOException
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject

/**
 * Die Anfrage kam nicht bis zum Server (DNS, Verbindung, TLS-Handshake).
 * Es wurde garantiert nichts gesendet — der Aufrufer darf gefahrlos auf den
 * Weg über das Telefon ausweichen.
 */
class VerbindungsFehler(meldung: String) : Exception(meldung)

/**
 * Der Server hat geantwortet, aber mit einem Fehler — oder die Antwort war
 * unbrauchbar. Hier darf **nicht** über das Telefon wiederholt werden: die
 * Anfrage könnte bereits ausgeführt worden sein.
 */
class AntwortFehler(meldung: String) : Exception(meldung)

/**
 * Spricht die Stillzeit-REST-API direkt von der Uhr aus — mit derselben
 * Basis-URL und denselben Zugangsdaten wie die Telefon-App.
 *
 * Alle Methoden blockieren und gehören auf einen Hintergrund-Thread.
 */
class DirectApi(private val verbindung: ServerConnection) {

    private val socketFactory by lazy {
        if (verbindung.istMtls) {
            ClientCertificates.socketFactory(
                verbindung.clientCertPem!!,
                verbindung.clientKeyPem!!,
            )
        } else {
            null
        }
    }

    /** Die letzten Einträge, neueste zuerst. */
    fun eintraege(): List<WatchEntry> {
        val antwort = anfrage("GET", url(), null)
        val liste = WatchEntry.listeAusJson(antwort.optJSONArray("entries"))
        return liste.take(MAX_EINTRAEGE)
    }

    fun anlegen(
        seite: String,
        menge: Int?,
        flaschenArt: String?,
        dauerMinuten: Int?,
        zeitstempel: String?,
    ) {
        val koerper = JSONObject().put("seite", seite)
        if (seite == Seite.FLASCHE) {
            koerper.put("menge", menge ?: 0)
            if (flaschenArt != null) koerper.put("flaschen_art", flaschenArt)
        } else if (dauerMinuten != null) {
            koerper.put("dauer_minuten", dauerMinuten)
        }
        if (zeitstempel != null) koerper.put("create_time", zeitstempel)
        anfrage("POST", url(), koerper)
    }

    fun aendern(id: Int, seite: String, wert: Int, flaschenArt: String?) {
        val koerper = if (seite == Seite.FLASCHE) {
            JSONObject()
                .put("menge", wert)
                .put("flaschen_art", flaschenArt ?: FlaschenArt.PRE)
        } else {
            JSONObject().put("dauer_minuten", wert)
        }
        anfrage("PATCH", url("id=$id"), koerper)
    }

    private fun url(query: String? = null): URL {
        val basis = verbindung.baseUrl
        val text = if (query == null) basis else "$basis?$query"
        return runCatching { URL(text) }.getOrElse {
            throw AntwortFehler("Ungültige API-URL: $basis")
        }
    }

    private fun anfrage(methode: String, url: URL, koerper: JSONObject?): JSONObject {
        val connection = runCatching { url.openConnection() as HttpURLConnection }
            .getOrElse { throw VerbindungsFehler("Verbindung nicht möglich: ${it.meldung()}") }

        try {
            if (connection is HttpsURLConnection) {
                // Das Zertifikat wurde beim Import geprüft; scheitert es hier
                // trotzdem, ist der Direktweg nicht nutzbar und der Aufrufer
                // darf auf das Telefon ausweichen.
                val factory = try {
                    socketFactory
                } catch (fehler: ClientCertificateException) {
                    throw VerbindungsFehler(
                        fehler.message ?: "Client-Zertifikat unbrauchbar.",
                    )
                }
                factory?.let { connection.sslSocketFactory = it }
            }
            val ueberschrieben = methodeSetzen(connection, methode)
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            verbindung.apiKey?.let { connection.setRequestProperty("X-API-Key", it) }
            if (ueberschrieben) {
                connection.setRequestProperty("X-HTTP-Method-Override", methode)
            }
            if (koerper != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
            }

            // connect() erledigt DNS, TCP und den TLS-Handshake. Schlägt es
            // fehl, wurde die Anfrage selbst noch nicht abgeschickt.
            try {
                connection.connect()
            } catch (fehler: Exception) {
                throw VerbindungsFehler("Server nicht erreichbar: ${fehler.meldung()}")
            }

            return austauschen(connection, koerper)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Setzt die HTTP-Methode. Falls die Plattform PATCH ablehnt, wird wie in
     * der Telefon-App auf POST + `X-HTTP-Method-Override` ausgewichen.
     * Liefert true, wenn überschrieben wurde.
     */
    private fun methodeSetzen(connection: HttpURLConnection, methode: String): Boolean =
        try {
            connection.requestMethod = methode
            false
        } catch (_: ProtocolException) {
            connection.requestMethod = "POST"
            true
        }

    private fun austauschen(connection: HttpURLConnection, koerper: JSONObject?): JSONObject {
        try {
            if (koerper != null) {
                connection.outputStream.use { strom ->
                    strom.write(koerper.toString().toByteArray(Charsets.UTF_8))
                }
            }
            val status = connection.responseCode
            val erfolgreich = status in 200..299
            val strom = if (erfolgreich) connection.inputStream else connection.errorStream
            val text = strom?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (!erfolgreich) {
                throw AntwortFehler("Fehler $status: ${fehlertext(text)}")
            }
            if (text.isBlank()) return JSONObject()
            return runCatching { JSONObject(text) }.getOrElse {
                throw AntwortFehler("Unerwartete Antwort des Servers.")
            }
        } catch (fehler: IOException) {
            // Ab hier ist die Anfrage bereits unterwegs gewesen — bewusst kein
            // VerbindungsFehler, damit nicht doppelt geschrieben wird.
            throw AntwortFehler("Antwort des Servers unvollständig: ${fehler.meldung()}")
        }
    }

    /** Zieht `{"error": "..."}` heraus bzw. kürzt eine HTML-Fehlerseite. */
    private fun fehlertext(text: String): String {
        val ausJson = runCatching { JSONObject(text).optString("error") }.getOrNull()
        if (!ausJson.isNullOrEmpty()) return ausJson
        val sauber = text.replace(Regex("\\s+"), " ").trim()
        if (sauber.isEmpty()) return "Anfrage fehlgeschlagen"
        return if (sauber.length > 120) "${sauber.take(120)}…" else sauber
    }

    private fun Throwable.meldung(): String =
        message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName

    private companion object {
        const val TIMEOUT_MS = 15_000
        const val MAX_EINTRAEGE = 12
    }
}
