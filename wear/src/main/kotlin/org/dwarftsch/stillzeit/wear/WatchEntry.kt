package org.dwarftsch.stillzeit.wear

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/** Eintragstypen — die Werte sind exakt die `apiValue` der Handy-App. */
object Seite {
    const val LINKS = "Links"
    const val RECHTS = "Rechts"
    const val BEIDSEITIG = "Beidseitig"
    const val FLASCHE = "Flasche"
    const val BREI = "Brei"
    const val WASSER = "Wasser"

    /** Einträge mit Pflicht-Menge (Flasche/Wasser in ml, Brei in g). */
    fun hatMenge(seite: String): Boolean =
        seite == FLASCHE || seite == BREI || seite == WASSER

    /** Anzeigeeinheit, falls die Handy-App kein `einheit`-Feld liefert. */
    fun einheit(seite: String): String = if (seite == BREI) "g" else "ml"
}

/** Flascheninhalte — exakt die `apiValue` von `FlaschenArt` in der Flutter-App. */
object FlaschenArt {
    const val PRE = "Pre"
    const val MUTTER = "Mutter"

    val alle = listOf(PRE, MUTTER)
}

/** Ein Eintrag, wie ihn die Handy-App an die Uhr liefert. */
data class WatchEntry(
    val id: Int,
    val zeit: LocalDateTime,
    val seite: String,
    val menge: Int?,
    val flaschenArt: String?,
    val dauerMinuten: Int?,
    /** Einheit der Menge („ml“/„g“), von der Handy-App geliefert. */
    val einheit: String? = null,
) {
    val istFlasche: Boolean get() = seite == Seite.FLASCHE

    val hatMenge: Boolean get() = Seite.hatMenge(seite)

    /** "Flasche · Pre" bzw. schlicht der Eintragstyp. */
    val titel: String
        get() = if (istFlasche && flaschenArt != null) "$seite · $flaschenArt" else seite

    /** Der bearbeitbare Wert als Text: Menge (mit Einheit), sonst die Dauer. */
    val wertText: String
        get() = if (hatMenge) {
            "${menge ?: 0} ${einheit ?: Seite.einheit(seite)}"
        } else {
            "${dauerMinuten ?: 0} Min."
        }

    companion object {
        fun listeAusJson(array: JSONArray?): List<WatchEntry> {
            if (array == null) return emptyList()
            val eintraege = ArrayList<WatchEntry>(array.length())
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                ausJson(json)?.let(eintraege::add)
            }
            return eintraege
        }

        private fun ausJson(json: JSONObject): WatchEntry? {
            val id = if (json.has("id")) json.optInt("id", -1) else -1
            if (id < 0) return null
            val seite = json.optString("seite").takeIf { it.isNotEmpty() } ?: return null
            val zeit = zeitAusText(json.optString("create_time")) ?: return null
            return WatchEntry(
                id = id,
                zeit = zeit,
                seite = seite,
                menge = json.optIntOderNull("menge"),
                flaschenArt = json.optString("flaschen_art").takeIf { it.isNotEmpty() },
                dauerMinuten = json.optIntOderNull("dauer_minuten"),
                einheit = json.optString("einheit").takeIf { it.isNotEmpty() },
            )
        }

        /**
         * Die Handy-App schickt UTC-Zeitstempel mit "Z". Zusätzlich werden
         * Zeitstempel mit explizitem Offset akzeptiert.
         */
        private fun zeitAusText(text: String): LocalDateTime? {
            if (text.isEmpty()) return null
            return runCatching {
                OffsetDateTime.parse(text).atZoneSameInstant(ZoneId.systemDefault())
                    .toLocalDateTime()
            }.recoverCatching {
                Instant.parse(text).atZone(ZoneId.systemDefault()).toLocalDateTime()
            }.getOrNull()
        }

        private fun JSONObject.optIntOderNull(key: String): Int? =
            if (has(key) && !isNull(key)) optInt(key) else null
    }
}
