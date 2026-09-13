package org.dwarftsch.stillzeit.data

import android.content.Context
import java.io.File
import java.time.Instant
import org.dwarftsch.stillzeit.Entry
import org.dwarftsch.stillzeit.FlaschenArt
import org.dwarftsch.stillzeit.Seite
import org.dwarftsch.stillzeit.TodayStats
import org.dwarftsch.stillzeit.parseIsoZeit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ein Schreibzugriff, der offline erfasst wurde und noch zum Server muss.
 *
 * Änderungen und Löschungen beziehen sich immer auf eine **Server-ID**.
 * Betreffen sie einen Eintrag, der selbst noch in der Warteschlange steht,
 * werden sie direkt in dessen [Anlegen] eingearbeitet bzw. löschen sie ganz —
 * siehe [Warteschlange.aendere] und [Warteschlange.loesche]. Dadurch kann beim
 * Abarbeiten keine noch unbekannte ID auftauchen.
 */
sealed interface Warteaktion {

    /** Ein offline erfasster neuer Eintrag. */
    data class Anlegen(
        /**
         * Negative Kennung, unter der der Eintrag in der Liste auftaucht,
         * solange er nicht hochgeladen ist.
         */
        val lokaleId: Long,
        val seite: Seite,
        val menge: Int? = null,
        val flaschenArt: FlaschenArt? = null,
        val dauerMinuten: Int? = null,
        /**
         * Zeitpunkt der Erfassung, nicht des Hochladens – sonst bekäme der
         * Eintrag beim Nachholen die falsche Uhrzeit.
         */
        val createTime: Instant,
    ) : Warteaktion

    /** Eine offline erfasste Änderung an einem bereits hochgeladenen Eintrag. */
    data class Aendern(
        val id: Long,
        val menge: Int? = null,
        val flaschenArt: FlaschenArt? = null,
        val dauerMinuten: Int? = null,
    ) : Warteaktion

    /** Eine offline erfasste Löschung eines bereits hochgeladenen Eintrags. */
    data class Loeschen(val id: Long) : Warteaktion
}

/** Die geordnete Liste der offenen Schreibzugriffe eines Zugangs. */
class Warteschlange private constructor(
    private val liste: MutableList<Warteaktion>,
    private var naechsteLokaleId: Long,
) {

    constructor() : this(mutableListOf(), -1L)

    val aktionen: List<Warteaktion> get() = liste
    val istLeer: Boolean get() = liste.isEmpty()
    val anzahl: Int get() = liste.size

    /**
     * IDs mit noch nicht übertragenem Stand – die Oberfläche markiert sie.
     * Lokale Kennungen sind negativ.
     */
    val ausstehendeIds: Set<Long>
        get() = liste.mapTo(mutableSetOf()) {
            when (it) {
                is Warteaktion.Anlegen -> it.lokaleId
                is Warteaktion.Aendern -> it.id
                is Warteaktion.Loeschen -> it.id
            }
        }

    // ── Aufnehmen ───────────────────────────────────────────────────────────

    /** Nimmt einen neuen Eintrag auf und liefert dessen lokale Kennung. */
    fun lege(
        seite: Seite,
        menge: Int?,
        flaschenArt: FlaschenArt?,
        dauerMinuten: Int?,
        createTime: Instant,
    ): Long {
        val id = naechsteLokaleId
        naechsteLokaleId -= 1
        liste.add(Warteaktion.Anlegen(id, seite, menge, flaschenArt, dauerMinuten, createTime))
        return id
    }

    /**
     * Nimmt eine Änderung auf. Bei einem Eintrag, der selbst noch wartet, wird
     * dessen [Warteaktion.Anlegen] angepasst statt eine zweite Aktion
     * anzuhängen; bei einem bereits hochgeladenen Eintrag ersetzt die neue
     * Änderung eine ältere für dieselbe ID.
     */
    fun aendere(id: Long, menge: Int?, flaschenArt: FlaschenArt?, dauerMinuten: Int?) {
        val anlegen = indexDesAnlegens(id)
        if (anlegen >= 0) {
            val alt = liste[anlegen] as Warteaktion.Anlegen
            liste[anlegen] = alt.copy(
                menge = menge ?: alt.menge,
                flaschenArt = flaschenArt ?: alt.flaschenArt,
                dauerMinuten = dauerMinuten ?: alt.dauerMinuten,
            )
            return
        }
        val bestehend = liste.indexOfFirst { it is Warteaktion.Aendern && it.id == id }
        if (bestehend >= 0) {
            val alt = liste[bestehend] as Warteaktion.Aendern
            liste[bestehend] = alt.copy(
                menge = menge ?: alt.menge,
                flaschenArt = flaschenArt ?: alt.flaschenArt,
                dauerMinuten = dauerMinuten ?: alt.dauerMinuten,
            )
            return
        }
        liste.add(Warteaktion.Aendern(id, menge, flaschenArt, dauerMinuten))
    }

    /**
     * Nimmt eine Löschung auf. Einen Eintrag, der noch gar nicht beim Server
     * war, wirft sie ersatzlos aus der Warteschlange.
     */
    fun loesche(id: Long) {
        val anlegen = indexDesAnlegens(id)
        if (anlegen >= 0) {
            liste.removeAt(anlegen)
            return
        }
        // Eine wartende Änderung wird durch die Löschung hinfällig.
        liste.removeAll { it is Warteaktion.Aendern && it.id == id }
        liste.add(Warteaktion.Loeschen(id))
    }

    /** Entfernt die erste Aktion – nach erfolgreichem Senden. */
    fun entferneErste() {
        if (liste.isNotEmpty()) liste.removeAt(0)
    }

    private fun indexDesAnlegens(id: Long): Int =
        if (id >= 0) -1 else liste.indexOfFirst { it is Warteaktion.Anlegen && it.lokaleId == id }

    // ── Anwenden ────────────────────────────────────────────────────────────

    /**
     * Legt die offenen Aktionen über eine Liste vom Server (bzw. aus dem
     * Zwischenspeicher), damit die Oberfläche den Stand zeigt, den der Nutzer
     * erwartet: neueste zuerst.
     */
    fun anwenden(eintraege: List<Entry>): List<Entry> {
        val ergebnis = eintraege.toMutableList()
        for (aktion in liste) {
            when (aktion) {
                is Warteaktion.Anlegen -> ergebnis.add(
                    Entry(
                        id = aktion.lokaleId,
                        createTime = aktion.createTime,
                        seite = aktion.seite,
                        menge = aktion.menge,
                        flaschenArt = aktion.flaschenArt,
                        dauerMinuten = aktion.dauerMinuten,
                    ),
                )

                is Warteaktion.Aendern -> {
                    val index = ergebnis.indexOfFirst { it.id == aktion.id }
                    if (index >= 0) {
                        val alt = ergebnis[index]
                        ergebnis[index] = alt.copy(
                            menge = aktion.menge ?: alt.menge,
                            flaschenArt = aktion.flaschenArt ?: alt.flaschenArt,
                            dauerMinuten = aktion.dauerMinuten ?: alt.dauerMinuten,
                        )
                    }
                }

                is Warteaktion.Loeschen -> ergebnis.removeAll { it.id == aktion.id }
            }
        }
        return ergebnis.sortedByDescending { it.createTime }
    }

    /**
     * Rechnet die offenen Aktionen in die Tagesstatistik ein, damit Kacheln
     * und Liste nicht auseinanderlaufen. Nur Einträge von heute zählen.
     */
    fun anwenden(stats: TodayStats, heute: java.time.LocalDate = java.time.LocalDate.now()): TodayStats {
        var werte = stats
        val zone = java.time.ZoneId.systemDefault()
        for (aktion in liste) {
            if (aktion !is Warteaktion.Anlegen) continue
            if (aktion.createTime.atZone(zone).toLocalDate() != heute) continue
            val menge = aktion.menge ?: 0
            werte = when (aktion.seite) {
                Seite.LINKS -> werte.copy(links = werte.links + 1)
                Seite.RECHTS -> werte.copy(rechts = werte.rechts + 1)
                Seite.BEIDSEITIG -> werte.copy(beidseitig = werte.beidseitig + 1)
                Seite.FLASCHE -> werte.copy(
                    flasche = werte.flasche + 1,
                    totalMl = werte.totalMl + menge,
                )
                Seite.BREI -> werte.copy(
                    brei = werte.brei + 1,
                    totalGBrei = werte.totalGBrei + menge,
                )
                Seite.WASSER -> werte.copy(
                    wasser = werte.wasser + 1,
                    totalMlWasser = werte.totalMlWasser + menge,
                )
            }
            // „gesamt“ zählt nur Milchmahlzeiten – wie auf dem Server.
            if (!aktion.seite.istBreiWasser) werte = werte.copy(gesamt = werte.gesamt + 1)
            if (aktion.seite.hatDauer) {
                werte = werte.copy(totalMinuten = werte.totalMinuten + (aktion.dauerMinuten ?: 0))
            }
        }
        return werte
    }

    // ── JSON ────────────────────────────────────────────────────────────────

    fun alsJson(): JSONObject {
        val aktionenJson = JSONArray()
        for (aktion in liste) {
            val o = JSONObject()
            when (aktion) {
                is Warteaktion.Anlegen -> {
                    o.put("art", "anlegen")
                    o.put("lokale_id", aktion.lokaleId)
                    o.put("seite", aktion.seite.apiValue)
                    aktion.menge?.let { o.put("menge", it) }
                    aktion.flaschenArt?.let { o.put("flaschen_art", it.apiValue) }
                    aktion.dauerMinuten?.let { o.put("dauer_minuten", it) }
                    o.put("create_time", aktion.createTime.toString())
                }

                is Warteaktion.Aendern -> {
                    o.put("art", "aendern")
                    o.put("id", aktion.id)
                    aktion.menge?.let { o.put("menge", it) }
                    aktion.flaschenArt?.let { o.put("flaschen_art", it.apiValue) }
                    aktion.dauerMinuten?.let { o.put("dauer_minuten", it) }
                }

                is Warteaktion.Loeschen -> {
                    o.put("art", "loeschen")
                    o.put("id", aktion.id)
                }
            }
            aktionenJson.put(o)
        }
        return JSONObject()
            .put("naechste_lokale_id", naechsteLokaleId)
            .put("aktionen", aktionenJson)
    }

    companion object {
        fun ausJson(json: JSONObject): Warteschlange {
            val liste = mutableListOf<Warteaktion>()
            val aktionen = json.optJSONArray("aktionen") ?: JSONArray()
            for (i in 0 until aktionen.length()) {
                val o = aktionen.optJSONObject(i) ?: continue
                val aktion = when (o.optString("art")) {
                    "anlegen" -> {
                        val seite = Seite.fromApi(o.optString("seite"))
                        if (seite == null) {
                            null
                        } else {
                            Warteaktion.Anlegen(
                                lokaleId = o.optLong("lokale_id"),
                                seite = seite,
                                menge = o.intOderNull("menge"),
                                flaschenArt = FlaschenArt.fromApi(o.stringOderNull("flaschen_art")),
                                dauerMinuten = o.intOderNull("dauer_minuten"),
                                createTime = parseIsoZeit(o.optString("create_time")),
                            )
                        }
                    }

                    "aendern" -> Warteaktion.Aendern(
                        id = o.optLong("id"),
                        menge = o.intOderNull("menge"),
                        flaschenArt = FlaschenArt.fromApi(o.stringOderNull("flaschen_art")),
                        dauerMinuten = o.intOderNull("dauer_minuten"),
                    )

                    "loeschen" -> Warteaktion.Loeschen(o.optLong("id"))
                    else -> null
                }
                if (aktion != null) liste.add(aktion)
            }
            val naechste = if (json.has("naechste_lokale_id")) {
                json.optLong("naechste_lokale_id")
            } else {
                -1L
            }
            return Warteschlange(liste, naechste)
        }
    }
}

/**
 * Legt Warteschlange und Lesestand je Zugang im app-privaten Verzeichnis ab.
 *
 * Der Schlüssel ist Modus plus Basis-URL: Wer zwischen zwei Servern wechselt,
 * bekommt nicht die Einträge des anderen zu sehen und lädt auch keine
 * Warteschlange dorthin hoch, wo sie nicht hingehört.
 *
 * Einträge und Statistik liegen in getrennten Dateien, weil die Oberfläche
 * beide nebenläufig lädt — in einer gemeinsamen Datei überschriebe die eine
 * Antwort die andere.
 */
class OfflineSpeicher(context: Context, zugang: String) {

    private val schluessel = zugang.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
    private val ordner = File(context.applicationContext.filesDir, "offline").apply { mkdirs() }

    private val warteschlangeDatei get() = File(ordner, "warteschlange_$schluessel.json")
    private val eintraegeDatei get() = File(ordner, "eintraege_$schluessel.json")
    private val statsDatei get() = File(ordner, "stats_$schluessel.json")

    fun ladeWarteschlange(): Warteschlange =
        lies(warteschlangeDatei)?.let { Warteschlange.ausJson(it) } ?: Warteschlange()

    fun speichere(warteschlange: Warteschlange) {
        schreibe(warteschlangeDatei, warteschlange.alsJson())
    }

    fun ladeEintraege(): List<Entry>? {
        val json = lies(eintraegeDatei) ?: return null
        val liste = json.optJSONArray("eintraege") ?: return null
        return (0 until liste.length()).mapNotNull { eintragAusJson(liste.optJSONObject(it)) }
    }

    fun speichereEintraege(eintraege: List<Entry>) {
        val liste = JSONArray()
        for (e in eintraege) {
            liste.put(
                JSONObject()
                    .put("id", e.id)
                    .put("create_time", e.createTime.toString())
                    .put("seite", e.seite.apiValue)
                    .apply {
                        e.menge?.let { put("menge", it) }
                        e.flaschenArt?.let { put("flaschen_art", it.apiValue) }
                        e.dauerMinuten?.let { put("dauer_minuten", it) }
                        e.einheit?.let { put("einheit", it) }
                    },
            )
        }
        schreibe(eintraegeDatei, JSONObject().put("eintraege", liste))
    }

    fun ladeStats(): TodayStats? {
        val json = lies(statsDatei) ?: return null
        return TodayStats(
            gesamt = json.optInt("gesamt"),
            links = json.optInt("links"),
            rechts = json.optInt("rechts"),
            beidseitig = json.optInt("beidseitig"),
            flasche = json.optInt("flasche"),
            totalMl = json.optInt("total_ml"),
            totalMinuten = json.optInt("total_minuten"),
            brei = json.optInt("brei"),
            wasser = json.optInt("wasser"),
            totalGBrei = json.optInt("total_g_brei"),
            totalMlWasser = json.optInt("total_ml_wasser"),
            breiWasserAktiv = json.optBoolean("brei_wasser_aktiv"),
        )
    }

    fun speichereStats(stats: TodayStats) {
        schreibe(
            statsDatei,
            JSONObject()
                .put("gesamt", stats.gesamt)
                .put("links", stats.links)
                .put("rechts", stats.rechts)
                .put("beidseitig", stats.beidseitig)
                .put("flasche", stats.flasche)
                .put("total_ml", stats.totalMl)
                .put("total_minuten", stats.totalMinuten)
                .put("brei", stats.brei)
                .put("wasser", stats.wasser)
                .put("total_g_brei", stats.totalGBrei)
                .put("total_ml_wasser", stats.totalMlWasser)
                .put("brei_wasser_aktiv", stats.breiWasserAktiv),
        )
    }

    private fun eintragAusJson(json: JSONObject?): Entry? {
        if (json == null) return null
        val seite = Seite.fromApi(json.optString("seite")) ?: return null
        return Entry(
            id = json.optLong("id"),
            createTime = parseIsoZeit(json.optString("create_time")),
            seite = seite,
            menge = json.intOderNull("menge"),
            flaschenArt = FlaschenArt.fromApi(json.stringOderNull("flaschen_art")),
            dauerMinuten = json.intOderNull("dauer_minuten"),
            einheit = json.stringOderNull("einheit"),
        )
    }

    private fun lies(datei: File): JSONObject? =
        runCatching { JSONObject(datei.readText()) }.getOrNull()

    /**
     * Erst in eine Nebendatei, dann umbenennen: ein Absturz mitten im
     * Schreiben hinterlässt sonst eine halbe Datei, und die Warteschlange
     * wäre verloren.
     */
    private fun schreibe(datei: File, json: JSONObject) {
        runCatching {
            val temp = File(datei.parentFile, "${datei.name}.tmp")
            temp.writeText(json.toString())
            if (!temp.renameTo(datei)) {
                datei.writeText(json.toString())
                temp.delete()
            }
        }
    }
}

private fun JSONObject.intOderNull(key: String): Int? = if (isNull(key)) null else optInt(key)

private fun JSONObject.stringOderNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
