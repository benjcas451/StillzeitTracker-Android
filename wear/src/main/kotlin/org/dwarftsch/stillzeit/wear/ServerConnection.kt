package org.dwarftsch.stillzeit.wear

import android.content.Context
import android.util.Base64
import org.json.JSONObject

/**
 * Die vom Telefon übernommene Server-Verbindung. Solange keine hinterlegt ist,
 * läuft alles über das Telefon (Relay).
 */
data class ServerConnection(
    /** Basis-URL inklusive abschließendem Slash. */
    val baseUrl: String,
    /** Wird als `X-API-Key` mitgesendet; null im mTLS-Modus. */
    val apiKey: String?,
    /** PEM-Bytes des Client-Zertifikats; null im API-Key-Modus. */
    val clientCertPem: ByteArray?,
    /** PEM-Bytes des privaten Schlüssels; null im API-Key-Modus. */
    val clientKeyPem: ByteArray?,
) {
    val istMtls: Boolean get() = clientCertPem != null && clientKeyPem != null

    /** Kurzbeschreibung für die Statusanzeige auf der Uhr. */
    val beschreibung: String get() = if (istMtls) "Direkt · mTLS" else "Direkt · API-Key"

    // baseUrl identifiziert die Verbindung ausreichend; die Byte-Arrays aus
    // equals/hashCode herauszuhalten erspart das fehleranfällige Vergleichen
    // von Schlüsselmaterial.
    override fun equals(other: Any?): Boolean =
        other is ServerConnection && other.baseUrl == baseUrl &&
            other.apiKey == apiKey && other.istMtls == istMtls

    override fun hashCode(): Int = baseUrl.hashCode()

    companion object {
        /**
         * Baut die Verbindung aus der Antwort auf `getConnection`.
         * Liefert null, wenn das Telefon auf der lokalen SQLite-Quelle steht —
         * dann gibt es nichts zu übernehmen.
         */
        fun ausAntwort(daten: JSONObject): ServerConnection? {
            val baseUrl = daten.optString("base_url").trim()
            return when (daten.optString("mode")) {
                "apiKey" -> {
                    val key = daten.optString("api_key")
                    if (baseUrl.isEmpty() || key.isEmpty()) return null
                    ServerConnection(mitSlash(baseUrl), key, null, null)
                }

                "api" -> {
                    val cert = daten.optString("client_cert").entschluesselt()
                    val key = daten.optString("client_key").entschluesselt()
                    if (baseUrl.isEmpty() || cert == null || key == null) return null
                    ServerConnection(mitSlash(baseUrl), null, cert, key)
                }

                else -> null
            }
        }

        private fun mitSlash(url: String): String =
            if (url.endsWith("/")) url else "$url/"

        private fun String.entschluesselt(): ByteArray? {
            if (isEmpty()) return null
            return runCatching { Base64.decode(this, Base64.DEFAULT) }.getOrNull()
        }
    }
}

/**
 * Legt die übernommene Verbindung app-privat ab.
 *
 * Hinweis: Wie auf dem Telefon (SharedPreferences) liegen API-Key bzw.
 * Schlüsselmaterial unverschlüsselt im App-Verzeichnis. Sie sind damit vor
 * anderen Apps geschützt, nicht aber vor einem entsperrten, gerooteten Gerät.
 */
class ServerConnectionStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(DATEI, Context.MODE_PRIVATE)

    fun laden(): ServerConnection? {
        val baseUrl = prefs.getString(BASE_URL, null)?.takeIf { it.isNotEmpty() } ?: return null
        val apiKey = prefs.getString(API_KEY, null)?.takeIf { it.isNotEmpty() }
        val cert = prefs.getString(CERT, null).alsBytes()
        val key = prefs.getString(SCHLUESSEL, null).alsBytes()
        if (apiKey == null && (cert == null || key == null)) return null
        return ServerConnection(baseUrl, apiKey, cert, key)
    }

    fun speichern(verbindung: ServerConnection) {
        prefs.edit()
            .putString(BASE_URL, verbindung.baseUrl)
            .putString(API_KEY, verbindung.apiKey)
            .putString(CERT, verbindung.clientCertPem.alsText())
            .putString(SCHLUESSEL, verbindung.clientKeyPem.alsText())
            .apply()
    }

    fun loeschen() {
        prefs.edit().clear().apply()
    }

    private fun String?.alsBytes(): ByteArray? {
        if (this.isNullOrEmpty()) return null
        return runCatching { Base64.decode(this, Base64.DEFAULT) }.getOrNull()
    }

    private fun ByteArray?.alsText(): String? =
        this?.let { Base64.encodeToString(it, Base64.NO_WRAP) }

    private companion object {
        const val DATEI = "stillzeit_wear_verbindung"
        const val BASE_URL = "base_url"
        const val API_KEY = "api_key"
        const val CERT = "client_cert"
        const val SCHLUESSEL = "client_key"
    }
}
