package org.dwarftsch.stillzeit.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.dwarftsch.stillzeit.Entry
import org.dwarftsch.stillzeit.FlaschenArt
import org.dwarftsch.stillzeit.Seite
import org.dwarftsch.stillzeit.TodayStats
import org.dwarftsch.stillzeit.parseIsoZeit
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Eine Roh-Zeile der Tabelle `entries` (für Backup-Export/-Restore). */
data class EntryRow(
    val id: Long,
    val createTime: String,
    val seite: String,
    val menge: Int?,
    val flaschenArt: String?,
    val dauerMinuten: Int?,
)

/**
 * Lokaler Modus: speichert Einträge in derselben SQLite-Datenbank, die schon
 * die Flutter-App (sqflite) verwendet hat – gleicher Dateiname, gleiches
 * Schema, gleiche Version. Bestehende Daten werden dadurch beim Umstieg auf
 * die native App nahtlos übernommen.
 */
class DemoService(context: Context) : EntryService {

    private val helper = Helper(context.applicationContext)

    private class Helper(context: Context) :
        SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE entries(
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  create_time TEXT NOT NULL,
                  seite TEXT NOT NULL,
                  menge INTEGER,
                  flaschen_art TEXT,
                  dauer_minuten INTEGER
                )
                """.trimIndent(),
            )
        }

        // Identisch zur sqflite-Migration der Flutter-App, damit auch ältere
        // Datenbankstände (Version 1/2) korrekt angehoben werden.
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE entries ADD COLUMN dauer_minuten INTEGER")
            }
            if (oldVersion < 3) {
                db.execSQL("ALTER TABLE entries ADD COLUMN flaschen_art TEXT")
            }
        }
    }

    override suspend fun getEntries(): List<Entry> = withContext(Dispatchers.IO) {
        helper.readableDatabase.query(
            "entries", null,
            "create_time >= ?", arrayOf(zuDb(tagesbeginn(tageZurueck = 1))),
            null, null, "create_time DESC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        Entry(
                            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                            createTime = parseIsoZeit(cursor.getString(cursor.getColumnIndexOrThrow("create_time"))),
                            seite = Seite.fromApi(cursor.getString(cursor.getColumnIndexOrThrow("seite"))),
                            menge = cursor.intOderNull("menge"),
                            flaschenArt = FlaschenArt.fromApi(cursor.stringOderNull("flaschen_art")),
                            dauerMinuten = cursor.intOderNull("dauer_minuten"),
                        ),
                    )
                }
            }
        }
    }

    override suspend fun getToday(): TodayStats = withContext(Dispatchers.IO) {
        helper.readableDatabase.query(
            "entries", arrayOf("seite", "menge", "dauer_minuten"),
            "create_time >= ?", arrayOf(zuDb(tagesbeginn())),
            null, null, null,
        ).use { cursor ->
            var links = 0
            var rechts = 0
            var beidseitig = 0
            var flasche = 0
            var totalMl = 0
            var totalMinuten = 0
            while (cursor.moveToNext()) {
                when (Seite.fromApi(cursor.getString(0))) {
                    Seite.LINKS -> { links++; totalMinuten += cursor.intOderNull("dauer_minuten") ?: 0 }
                    Seite.RECHTS -> { rechts++; totalMinuten += cursor.intOderNull("dauer_minuten") ?: 0 }
                    Seite.BEIDSEITIG -> { beidseitig++; totalMinuten += cursor.intOderNull("dauer_minuten") ?: 0 }
                    Seite.FLASCHE -> { flasche++; totalMl += cursor.intOderNull("menge") ?: 0 }
                }
            }
            TodayStats(
                gesamt = links + rechts + beidseitig + flasche,
                links = links,
                rechts = rechts,
                beidseitig = beidseitig,
                flasche = flasche,
                totalMl = totalMl,
                totalMinuten = totalMinuten,
            )
        }
    }

    override suspend fun createEntry(
        seite: Seite,
        menge: Int?,
        flaschenArt: FlaschenArt?,
        dauerMinuten: Int?,
        createTime: Instant?,
    ): Entry = withContext(Dispatchers.IO) {
        val zeit = createTime ?: Instant.now()
        val werte = ContentValues().apply {
            put("create_time", zuDb(zeit))
            put("seite", seite.apiValue)
            if (seite.isFlasche) {
                put("menge", menge ?: 0)
                if (flaschenArt != null) put("flaschen_art", flaschenArt.apiValue) else putNull("flaschen_art")
                putNull("dauer_minuten")
            } else {
                putNull("menge")
                putNull("flaschen_art")
                if (dauerMinuten != null) put("dauer_minuten", dauerMinuten) else putNull("dauer_minuten")
            }
        }
        val id = helper.writableDatabase.insertOrThrow("entries", null, werte)
        Entry(
            id = id,
            createTime = zeit,
            seite = seite,
            menge = if (seite.isFlasche) (menge ?: 0) else null,
            flaschenArt = if (seite.isFlasche) flaschenArt else null,
            dauerMinuten = if (seite.isFlasche) null else dauerMinuten,
        )
    }

    override suspend fun updateFlasche(id: Long, menge: Int, flaschenArt: FlaschenArt) =
        withContext(Dispatchers.IO) {
            val werte = ContentValues().apply {
                put("menge", menge)
                put("flaschen_art", flaschenArt.apiValue)
            }
            helper.writableDatabase.update("entries", werte, "id = ?", arrayOf(id.toString()))
            Unit
        }

    override suspend fun updateDauer(id: Long, dauerMinuten: Int) = withContext(Dispatchers.IO) {
        val werte = ContentValues().apply { put("dauer_minuten", dauerMinuten) }
        helper.writableDatabase.update("entries", werte, "id = ?", arrayOf(id.toString()))
        Unit
    }

    override suspend fun deleteEntry(id: Long) = withContext(Dispatchers.IO) {
        helper.writableDatabase.delete("entries", "id = ?", arrayOf(id.toString()))
        Unit
    }

    override fun dispose() {
        // Der SQLiteOpenHelper cached die Verbindung prozessweit; bewusst
        // offen lassen (UI, Backup und Watch-Service teilen sich die DB).
    }

    /** Alle Roh-Zeilen der lokalen Tabelle (für den Backup-Export). */
    suspend fun exportRows(): List<EntryRow> = withContext(Dispatchers.IO) {
        helper.readableDatabase.query("entries", null, null, null, null, null, "id").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        EntryRow(
                            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                            createTime = cursor.getString(cursor.getColumnIndexOrThrow("create_time")),
                            seite = cursor.getString(cursor.getColumnIndexOrThrow("seite")),
                            menge = cursor.intOderNull("menge"),
                            flaschenArt = cursor.stringOderNull("flaschen_art"),
                            dauerMinuten = cursor.intOderNull("dauer_minuten"),
                        ),
                    )
                }
            }
        }
    }

    /**
     * Ersetzt den gesamten lokalen Bestand durch [rows] (Backup-Restore).
     * Löschen und Einfügen laufen in einer Transaktion, damit bei einem Fehler
     * der bisherige Stand erhalten bleibt.
     */
    suspend fun replaceAll(rows: List<EntryRow>) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete("entries", null, null)
            for (row in rows) {
                val werte = ContentValues().apply {
                    put("id", row.id)
                    put("create_time", row.createTime)
                    put("seite", row.seite)
                    if (row.menge != null) put("menge", row.menge) else putNull("menge")
                    if (row.flaschenArt != null) put("flaschen_art", row.flaschenArt) else putNull("flaschen_art")
                    if (row.dauerMinuten != null) put("dauer_minuten", row.dauerMinuten) else putNull("dauer_minuten")
                }
                db.insertOrThrow("entries", null, werte)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private companion object {
        /** Muss zum sqflite-Bestand der Flutter-App passen. */
        const val DB_NAME = "stillzeit_demo.db"
        const val DB_VERSION = 3

        /**
         * Zeitpunkte werden – wie von der Flutter-App – als ISO 8601 in UTC
         * inklusive Millisekunden gespeichert, damit die lexikalische
         * Sortierung der Strings der zeitlichen entspricht.
         */
        val DB_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        fun zuDb(zeit: Instant): String = DB_FORMAT.format(zeit)

        /** Heutiger Tagesbeginn (lokale Zeit), optional um Tage zurückversetzt. */
        fun tagesbeginn(tageZurueck: Long = 0): Instant =
            LocalDate.now().minusDays(tageZurueck).atStartOfDay(ZoneId.systemDefault()).toInstant()
    }
}

private fun android.database.Cursor.intOderNull(spalte: String): Int? {
    val index = getColumnIndexOrThrow(spalte)
    return if (isNull(index)) null else getInt(index)
}

private fun android.database.Cursor.stringOderNull(spalte: String): String? {
    val index = getColumnIndexOrThrow(spalte)
    return if (isNull(index)) null else getString(index)
}
