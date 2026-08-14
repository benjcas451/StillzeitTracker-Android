package org.dwarftsch.stillzeit.wear

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/** Eintragstypen — die Werte sind exakt die `apiValue` der Flutter-App. */
object Seite {
    const val LINKS = "Links"
    const val RECHTS = "Rechts"
    const val BEIDSEITIG = "Beidseitig"
    const val FLASCHE = "Flasche"
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
) {
    val istFlasche: Boolean get() = seite == Seite.FLASCHE

    /** "Flasche · Pre" bzw. schlicht der Eintragstyp. */
    val titel: String
        get() = if (istFlasche && flaschenArt != null) "$seite · $flaschenArt" else seite

    /** Der bearbeitbare Wert als Text: Menge bei Flaschen, sonst die Dauer. */
    val wertText: String
        get() = if (istFlasche) "${menge ?: 0} ml" else "${dauerMinuten ?: 0} Min."

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
