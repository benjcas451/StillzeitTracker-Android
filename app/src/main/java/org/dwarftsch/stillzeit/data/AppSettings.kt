package org.dwarftsch.stillzeit.data

import android.content.Context
import android.content.SharedPreferences

/** Welche Datenquelle die App verwendet. Namen identisch zur Flutter-App. */
enum class DataSourceMode(val gespeichert: String) {
    /** Die mTLS-Server-API. */
    API("api"),

    /** Server-API mit API-Key (X-API-Key-Header) statt Client-Zertifikat. */
    API_KEY("apiKey"),

    /** Immer die lokale SQLite-Datenbank. */
    DEMO("demo");

    companion object {
        fun fromGespeichert(value: String?): DataSourceMode =
            entries.firstOrNull { it.gespeichert == value } ?: DEMO
    }
}

/**
 * Lädt und speichert App-Einstellungen (SharedPreferences).
 *
 * Beim ersten Start nach dem Umstieg von der Flutter-App werden die dort
 * hinterlegten Werte übernommen: Flutters shared_preferences-Plugin speichert
 * unter `FlutterSharedPreferences` mit dem Präfix `flutter.`. Die Schlüssel-
 * namen selbst sind identisch geblieben.
 */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("stillzeit_settings", Context.MODE_PRIVATE)

    init {
        migriereVonFlutter(context.applicationContext)
    }

    var mode: DataSourceMode
        get() = DataSourceMode.fromGespeichert(prefs.getString(KEY_MODE, null))
        set(value) = prefs.edit().putString(KEY_MODE, value.gespeichert).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_API_KEY, value.trim()).apply()

    /** Basis-URL der mTLS-API; leer, solange keine hinterlegt ist. */
    var apiBaseUrl: String
        get() = ladeUrl(KEY_API_BASE_URL)
        set(value) = prefs.edit().putString(KEY_API_BASE_URL, value.trim()).apply()

    /** Basis-URL der API-Key-API; leer, solange keine hinterlegt ist. */
    var apiKeyBaseUrl: String
        get() = ladeUrl(KEY_API_KEY_BASE_URL)
        set(value) = prefs.edit().putString(KEY_API_KEY_BASE_URL, value.trim()).apply()

    /** SAF-Ordner-URI der Zertifikate; null, solange keiner gewählt wurde. */
    var certFolderUri: String?
        get() = prefs.getString(KEY_CERT_FOLDER_URI, null)
        set(value) = prefs.edit().putString(KEY_CERT_FOLDER_URI, value).apply()

    /**
     * Lokales Opt-in für Brei & Wasser (Default aus). Erst wenn es der
     * Nutzer hier aktiviert, zeigt die App die beiden Erfassungs-Buttons –
     * in den Server-Modi zusätzlich nur, wenn auch die Server-Option der
     * Familie aktiv ist.
     */
    var breiWasserAktiviert: Boolean
        get() = prefs.getBoolean(KEY_BREI_WASSER_AKTIVIERT, false)
        set(value) = prefs.edit().putBoolean(KEY_BREI_WASSER_AKTIVIERT, value).apply()

    /**
     * Effektive Sichtbarkeit der Brei-/Wasser-Buttons: allein das lokale
     * Opt-in entscheidet – in jedem Modus.
     *
     * Bis 2.2.0 war die Sichtbarkeit im Server-Modus zusätzlich an
     * `brei_wasser_aktiv` aus `?action=heute` gekoppelt. Meldet ein Server
     * das Flag nicht (oder nicht positiv), konnte der Schalter die Buttons
     * nie einblenden – er war dort wirkungslos. Der Server-Stand ist deshalb
     * nur noch ein Hinweis, siehe [serverOptionZuletztAktiv].
     */
    fun breiWasserAktivFuerAktuellenZugang(): Boolean = breiWasserAktiviert

    /**
     * Zuletzt vom Server gemeldeter Stand der Option für den aktuellen Zugang
     * (Modus + Basis-URL), rein informativ für den Hinweis in den
     * Einstellungen. Im Demo-Modus gibt es keine Server-Option.
     */
    fun serverOptionZuletztAktiv(): Boolean =
        mode == DataSourceMode.DEMO || prefs.getBoolean(breiWasserCacheKey(), false)

    /** Cache nach erfolgreichem `?action=heute` aktualisieren (nicht im Demo). */
    fun merkeBreiWasserAktiv(aktiv: Boolean) {
        if (mode == DataSourceMode.DEMO) return
        prefs.edit().putBoolean(breiWasserCacheKey(), aktiv).apply()
    }

    private fun breiWasserCacheKey(): String {
        val baseUrl = when (mode) {
            DataSourceMode.API -> apiBaseUrl
            DataSourceMode.API_KEY -> apiKeyBaseUrl
            DataSourceMode.DEMO -> ""
        }
        return "$KEY_BREI_WASSER_AKTIV:${mode.gespeichert}:$baseUrl"
    }

    private fun ladeUrl(key: String): String {
        val url = prefs.getString(key, "").orEmpty().trim()
        if (url.isEmpty()) return ""
        return if (url.endsWith("/")) url else "$url/"
    }

    private fun migriereVonFlutter(context: Context) {
        if (prefs.getBoolean(KEY_MIGRIERT, false)) return

        val flutter = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        for (key in listOf(KEY_MODE, KEY_API_KEY, KEY_API_BASE_URL, KEY_API_KEY_BASE_URL, KEY_CERT_FOLDER_URI)) {
            val wert = flutter.getString("flutter.$key", null)
            if (wert != null && !prefs.contains(key)) {
                editor.putString(key, wert)
            }
        }
        editor.putBoolean(KEY_MIGRIERT, true).apply()
    }

    private companion object {
        const val KEY_MODE = "data_source_mode"
        const val KEY_API_KEY = "api_key"
        const val KEY_API_BASE_URL = "api_base_url"
        const val KEY_API_KEY_BASE_URL = "api_key_base_url"
        const val KEY_CERT_FOLDER_URI = "cert_folder_uri"
        const val KEY_MIGRIERT = "migriert_von_flutter"
        const val KEY_BREI_WASSER_AKTIVIERT = "brei_wasser_aktiviert"
        const val KEY_BREI_WASSER_AKTIV = "brei_wasser_aktiv"
    }
}
