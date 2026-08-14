package org.dwarftsch.stillzeit

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.SwapHoriz
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
    FLASCHE("Flasche");

    val isFlasche: Boolean get() = this == FLASCHE

    val icon: ImageVector
        get() = when (this) {
            LINKS -> Icons.Filled.ChevronLeft
            RECHTS -> Icons.Filled.ChevronRight
            BEIDSEITIG -> Icons.Filled.SwapHoriz
            FLASCHE -> Icons.Filled.LocalDrink
        }

    companion object {
        fun fromApi(value: String?): Seite =
            entries.firstOrNull { it.apiValue == value } ?: LINKS
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

/** Ein einzelner Stillzeit-/Flaschen-Eintrag. */
data class Entry(
    val id: Long,
    val createTime: Instant,
    val seite: Seite,
    /** Nur bei [Seite.FLASCHE] gesetzt (Menge in ml), sonst null. */
    val menge: Int? = null,
    /** Inhalt der Flasche (Pre oder Mutter). Bei älteren Einträgen null. */
    val flaschenArt: FlaschenArt? = null,
    /** Nur bei Still-Einträgen gesetzt (Dauer in Minuten), sonst null. */
    val dauerMinuten: Int? = null,
)

/** Tagesstatistik (`GET /api/?action=heute`). */
data class TodayStats(
    val gesamt: Int,
    val links: Int,
    val rechts: Int,
    val beidseitig: Int,
    val flasche: Int,
    val totalMl: Int,
    val totalMinuten: Int,
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
