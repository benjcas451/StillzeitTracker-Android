package org.dwarftsch.stillzeit.data

import org.dwarftsch.stillzeit.FlaschenArt
import org.dwarftsch.stillzeit.Seite
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Portables JSON-Backup der lokalen Datenbank. Format identisch zur
 * Flutter-App (format=1, app=stillzeit), damit alte Backups weiterhin
 * wiederhergestellt werden können und umgekehrt.
 */
object LocalBackupService {

    private const val APP = "stillzeit"
    private const val FORMAT = 1

    /** Vorschlags-Dateiname für den Speichern-Dialog. */
    fun dateiname(): String =
        "stillzeit_backup_${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"))}.json"

    /** Serialisiert alle Zeilen als hübsch formatiertes Backup-JSON. */
    fun exportJson(rows: List<EntryRow>): String {
        val eintraege = rows.map { row ->
            // LinkedHashMap statt JSONObject, damit null-Werte als JSON-null
            // erhalten bleiben (JSONObject.put(key, null) entfernt den Key).
            linkedMapOf<String, Any?>(
                "id" to row.id,
                "create_time" to row.createTime,
                "seite" to row.seite,
                "menge" to row.menge,
                "flaschen_art" to row.flaschenArt,
                "dauer_minuten" to row.dauerMinuten,
            )
        }
        val payload = linkedMapOf<String, Any?>(
            "format" to FORMAT,
            "app" to APP,
            "exported_at" to LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            "entries" to eintraege,
        )
        return JSONObject(payload as Map<*, *>).toString(2)
    }

    /**
     * Obergrenze für eine eingelesene Backup-Datei. Ein Backup dieser App
     * bleibt weit darunter. Die Grenze verhindert, dass eine versehentlich
     * gewählte Riesendatei beim Import den Arbeitsspeicher sprengt: Datei →
     * String → JSON-Baum belegt ein Vielfaches der Dateigröße, und das
     * System beendet die App dann ohne Rückfrage.
     */
    const val MAX_BACKUP_BYTES = 16 * 1024 * 1024

    /** Liest den Strom vollständig, bricht aber über [MAX_BACKUP_BYTES] ab. */
    fun leseBegrenzt(strom: java.io.InputStream): String {
        val puffer = java.io.ByteArrayOutputStream()
        val block = ByteArray(64 * 1024)
        while (true) {
            val gelesen = strom.read(block)
            if (gelesen < 0) break
            if (puffer.size() + gelesen > MAX_BACKUP_BYTES) {
                throw IllegalArgumentException(
                    "Die Datei ist größer als ${MAX_BACKUP_BYTES / (1024 * 1024)} MB " +
                        "und damit kein Backup dieser App.",
                )
            }
            puffer.write(block, 0, gelesen)
        }
        return puffer.toString(Charsets.UTF_8.name())
    }

    /**
     * Prüft ein Backup und liefert die Zeilen passend zum Tabellenschema.
     * Wirft [IllegalArgumentException] mit sprechender Meldung bei Problemen.
     */
    fun parseUndValidiere(text: String): List<EntryRow> {
        val decoded = runCatching { JSONObject(text) }.getOrElse {
            throw IllegalArgumentException("Die Datei ist kein gültiges JSON.")
        }
        if (decoded.optInt("format", -1) != FORMAT || decoded.optString("app") != APP) {
            throw IllegalArgumentException(
                "Nicht unterstütztes Backup-Format (falsche App oder Version).",
            )
        }
        val rawEntries = decoded.optJSONArray("entries")
            ?: throw IllegalArgumentException("Eintragsliste fehlt im Backup.")

        val result = mutableListOf<EntryRow>()
        val ids = mutableSetOf<Long>()
        for (index in 0 until rawEntries.length()) {
            val raw = rawEntries.optJSONObject(index)
                ?: throw IllegalArgumentException("Ungültiger Eintrag im Backup.")

            val id = raw.optLong("id", -1)
            if (id <= 0 || !ids.add(id)) {
                throw IllegalArgumentException("Ungültige oder doppelte Eintrags-ID.")
            }
            val createTime = raw.optString("create_time", "")
            if (createTime.isEmpty() ||
                runCatching { org.dwarftsch.stillzeit.parseIsoZeit(createTime) }.isFailure
            ) {
                throw IllegalArgumentException("Eintrag $id hat einen ungültigen Zeitpunkt.")
            }
            val seite = raw.optString("seite", "")
            if (Seite.entries.none { it.apiValue == seite }) {
                throw IllegalArgumentException("Eintrag $id hat eine ungültige Seite.")
            }
            val menge = if (raw.isNull("menge")) null else raw.optInt("menge", -1)
            if (menge != null && menge < 0) {
                throw IllegalArgumentException("Eintrag $id hat eine ungültige Menge.")
            }
            val flaschenArt = if (raw.isNull("flaschen_art")) null else raw.optString("flaschen_art")
            if (flaschenArt != null && FlaschenArt.fromApi(flaschenArt) == null) {
                throw IllegalArgumentException("Eintrag $id hat eine ungültige Flaschenart.")
            }
            val dauer = if (raw.isNull("dauer_minuten")) null else raw.optInt("dauer_minuten", -1)
            if (dauer != null && dauer < 0) {
                throw IllegalArgumentException("Eintrag $id hat eine ungültige Dauer.")
            }
            result.add(EntryRow(id, createTime, seite, menge, flaschenArt, dauer))
        }
        return result
    }
}
