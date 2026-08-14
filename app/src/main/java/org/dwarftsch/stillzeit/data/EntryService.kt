package org.dwarftsch.stillzeit.data

import org.dwarftsch.stillzeit.Entry
import org.dwarftsch.stillzeit.FlaschenArt
import org.dwarftsch.stillzeit.Seite
import org.dwarftsch.stillzeit.TodayStats
import java.time.Instant

/** Fehler einer API-Anfrage (Statuscode + Meldung). */
class ApiException(message: String, val statusCode: Int? = null) : Exception(message) {
    override fun toString(): String =
        if (statusCode != null) "Fehler $statusCode: $message" else message.orEmpty()
}

/**
 * Gemeinsame Schnittstelle für Eintrags-Quellen: die REST-API ([ApiService])
 * oder die lokale SQLite-Datenbank ([DemoService]).
 */
interface EntryService {
    /** Einträge von heute & gestern (neueste zuerst). */
    suspend fun getEntries(): List<Entry>

    /** Tagesstatistik für heute. */
    suspend fun getToday(): TodayStats

    /** Neuen Eintrag anlegen. */
    suspend fun createEntry(
        seite: Seite,
        menge: Int? = null,
        flaschenArt: FlaschenArt? = null,
        dauerMinuten: Int? = null,
        createTime: Instant? = null,
    ): Entry

    /** Menge und Inhalt eines Flaschen-Eintrags ändern. */
    suspend fun updateFlasche(id: Long, menge: Int, flaschenArt: FlaschenArt)

    /** Dauer eines Still-Eintrags ändern. */
    suspend fun updateDauer(id: Long, dauerMinuten: Int)

    /** Eintrag löschen. */
    suspend fun deleteEntry(id: Long)

    /** Gibt Ressourcen frei (HTTP-Client bzw. Datenbank-Handle). */
    fun dispose()
}
