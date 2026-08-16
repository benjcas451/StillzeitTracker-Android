package org.dwarftsch.stillzeit

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.RiceBowl
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.ui.graphics.vector.ImageVector
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Art des Eintrags. [apiValue] ist exakt der String, den die API erwartet.
 * Die Farbzuordnung (Minze/Flieder/Grau/Honig) liegt im Theme (ui/Theme.kt).
 */
enum class Seite(val apiValue: String) {
    LINKS("Links"),
    RECHTS("Rechts"),
    BEIDSEITIG("Beidseitig"),
    FLASCHE("Flasche"),
    BREI("Brei"),
    WASSER("Wasser");

    /** Nur für Pre/Mutter-Belange (FlaschenArt) – nicht für die Mengenlogik. */
    val isFlasche: Boolean get() = this == FLASCHE

    /** Einträge mit Pflicht-Menge (Flasche in ml, Brei in g, Wasser in ml). */
    val hatMenge: Boolean get() = this == FLASCHE || this == BREI || this == WASSER

    /** Still-Einträge, bei denen `dauer_minuten` erlaubt ist. */
    val hatDauer: Boolean get() = !hatMenge

    /** Nur anbieten, wenn die Server-Option `brei_wasser_aktiv` an ist. */
    val istBreiWasser: Boolean get() = this == BREI || this == WASSER

    /** Anzeigeeinheit der Menge; Fallback, wenn die API kein `einheit` liefert. */
    val mengenEinheit: String?
        get() = when (this) {
            FLASCHE, WASSER -> "ml"
            BREI -> "g"
            else -> null
        }

    val icon: ImageVector
        get() = when (this) {
            LINKS -> Icons.Filled.ChevronLeft
            RECHTS -> Icons.Filled.ChevronRight
            BEIDSEITIG -> Icons.Filled.SwapHoriz
            FLASCHE -> Icons.Filled.LocalDrink
            BREI -> Icons.Filled.RiceBowl
            WASSER -> Icons.Filled.WaterDrop
        }

    companion object {
        /**
         * Unbekannte Werte liefern null – Aufrufer blenden solche Einträge aus
         * (Listen) bzw. melden einen Fehler (Watch-Anfragen), statt sie wie
         * früher stillschweigend als „Links“ auszugeben.
         */
        fun fromApi(value: String?): Seite? =
            entries.firstOrNull { it.apiValue == value }
    }
}

/** Inhalt eines Flaschen-Eintrags. */
enum class FlaschenArt(val apiValue: String) {
    PRE("Pre"),
    MUTTER("Mutter");

    companion object {
        fun fromApi(value: String?): FlaschenArt? =
            entries.firstOrNull { it.apiValue == value }
    }
}

/** Ein einzelner Stillzeit-/Flaschen-/Brei-/Wasser-Eintrag. */
data class Entry(
    val id: Long,
    val createTime: Instant,
    val seite: Seite,
    /** Bei Einträgen mit [Seite.hatMenge] gesetzt, sonst null. */
    val menge: Int? = null,
    /** Inhalt der Flasche (Pre oder Mutter). Bei älteren Einträgen null. */
    val flaschenArt: FlaschenArt? = null,
    /** Nur bei Still-Einträgen gesetzt (Dauer in Minuten), sonst null. */
    val dauerMinuten: Int? = null,
    /** Einheit der Menge („ml“/„g“) – vom Server geliefert, lokal abgeleitet. */
    val einheit: String? = null,
) {
    /** Einheit für die Anzeige: API-Wert, sonst aus der Seite abgeleitet. */
    val anzeigeEinheit: String? get() = einheit ?: seite.mengenEinheit
}

/** Tagesstatistik (`GET /api/?action=heute`). */
data class TodayStats(
    val gesamt: Int,
    val links: Int,
    val rechts: Int,
    val beidseitig: Int,
    val flasche: Int,
    val totalMl: Int,
    val totalMinuten: Int,
    /** Anzahl Brei-Einträge heute (0, solange die Option aus ist). */
    val brei: Int = 0,
    /** Anzahl Wasser-Einträge heute. */
    val wasser: Int = 0,
    /** Brei-Gesamtmenge heute in Gramm. */
    val totalGBrei: Int = 0,
    /** Wasser-Gesamtmenge heute in Millilitern (getrennt von [totalMl]). */
    val totalMlWasser: Int = 0,
    /** Server-Option „Brei & Wasser“ dieser Familie. */
    val breiWasserAktiv: Boolean = false,
)

/**
 * Liest einen ISO-8601-Zeitstempel tolerant: mit Offset (`+02:00`), mit `Z`
 * oder – wie ihn ältere lokale Einträge theoretisch enthalten könnten – ganz
 * ohne Zeitzone (wird dann als lokale Zeit interpretiert, wie in Dart).
 */
fun parseIsoZeit(text: String): Instant {
    runCatching { return Instant.parse(text) }
    runCatching { return OffsetDateTime.parse(text).toInstant() }
    return LocalDateTime.parse(text).atZone(ZoneId.systemDefault()).toInstant()
}
