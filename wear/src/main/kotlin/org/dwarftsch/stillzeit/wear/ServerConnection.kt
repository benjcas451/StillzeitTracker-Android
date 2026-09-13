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
    /**
     * Wird als `X-API-Key` mitgesendet; im mTLS-Modus optional (nur, wenn der
     * Server zusätzlich zum Zertifikat einen Key verlangt).
     */
    val apiKey: String?,
    /** PEM-Bytes des Client-Zertifikats; null im API-Key-Modus. */
    val clientCertPem: ByteArray?,
    /** PEM-Bytes des privaten Schlüssels; null im API-Key-Modus. */
    val clientKeyPem: ByteArray?,
    /**
     * Client-ID des Cloudflare Service Tokens; null ausserhalb des
     * Cloudflare-Modus. Verbindungen, die vor 2.3.0 übernommen wurden, haben
     * das Feld nicht in der Ablage — es wird dann zu null gelesen.
     */
    val cfAccessClientId: String? = null,
    /** Client-Secret des Cloudflare Service Tokens; null ausserhalb des Modus. */
    val cfAccessClientSecret: String? = null,
) {
    val istMtls: Boolean get() = clientCertPem != null && clientKeyPem != null

    /** Läuft die Verbindung über ein Cloudflare Service Token? */
    val istCloudflare: Boolean
        get() = cfAccessClientId != null && cfAccessClientSecret != null

    /** Kurzbeschreibung für die Statusanzeige auf der Uhr. */
    val beschreibung: String get() = when {
        istCloudflare && apiKey != null -> "Direkt · Cloudflare + Key"
        istCloudflare -> "Direkt · Cloudflare"
        istMtls && apiKey != null -> "Direkt · mTLS + Key"
        istMtls -> "Direkt · mTLS"
        else -> "Direkt · API-Key"
    }

    // baseUrl identifiziert die Verbindung ausreichend; die Byte-Arrays aus
    // equals/hashCode herauszuhalten erspart das fehleranfällige Vergleichen
    // von Schlüsselmaterial.
    override fun equals(other: Any?): Boolean =
        other is ServerConnection && other.baseUrl == baseUrl &&
            other.apiKey == apiKey && other.istMtls == istMtls &&
            other.cfAccessClientId == cfAccessClientId &&
            other.cfAccessClientSecret == cfAccessClientSecret

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

                "cloudflare" -> {
                    // Ein halbes Service Token ist so gut wie keines — dann
                    // lieber weiter über das Telefon, statt am Rand
                    // abgewiesen zu werden.
                    val id = daten.optString("cf_access_client_id")
                    val secret = daten.optString("cf_access_client_secret")
                    if (baseUrl.isEmpty() || id.isEmpty() || secret.isEmpty()) return null
                    val zusatzKey = daten.optString("api_key").takeIf { it.isNotEmpty() }
                    ServerConnection(mitSlash(baseUrl), zusatzKey, null, null, id, secret)
                }

                "api" -> {
                    val cert = daten.optString("client_cert").entschluesselt()
                    val key = daten.optString("client_key").entschluesselt()
                    if (baseUrl.isEmpty() || cert == null || key == null) return null
                    // Zusatz-Key optional: ältere Telefon-Versionen senden das
                    // Feld gar nicht, dann bleibt es wie bisher bei reinem mTLS.
                    val zusatzKey = daten.optString("api_key").takeIf { it.isNotEmpty() }
                    ServerConnection(mitSlash(baseUrl), zusatzKey, cert, key)
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
        val cfId = prefs.getString(CF_CLIENT_ID, null)?.takeIf { it.isNotEmpty() }
        val cfSecret = prefs.getString(CF_CLIENT_SECRET, null)?.takeIf { it.isNotEmpty() }
        val hatCfToken = cfId != null && cfSecret != null
        if (apiKey == null && !hatCfToken && (cert == null || key == null)) return null
        return ServerConnection(baseUrl, apiKey, cert, key, cfId, cfSecret)
    }

    fun speichern(verbindung: ServerConnection) {
        prefs.edit()
            .putString(BASE_URL, verbindung.baseUrl)
            .putString(API_KEY, verbindung.apiKey)
            .putString(CERT, verbindung.clientCertPem.alsText())
            .putString(SCHLUESSEL, verbindung.clientKeyPem.alsText())
            .putString(CF_CLIENT_ID, verbindung.cfAccessClientId)
            .putString(CF_CLIENT_SECRET, verbindung.cfAccessClientSecret)
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
        const val CF_CLIENT_ID = "cf_access_client_id"
        const val CF_CLIENT_SECRET = "cf_access_client_secret"
    }
}
