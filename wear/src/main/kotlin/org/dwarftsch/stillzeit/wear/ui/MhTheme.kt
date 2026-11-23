package org.dwarftsch.stillzeit.wear.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.Typography
import org.dwarftsch.stillzeit.wear.R

// ============================================================================
// Minze & Honig 2.0 – Theme-Schicht der Uhr
// Quelle: DesignGuide/tokens/design-tokens.json (semantic.dark). Die Uhr ist
// immer dunkel; der schwarze Fenstergrund bleibt (Wear-Konvention). Aufbau
// der Oberfläche unverändert, umgestellt sind nur Farben und Schrift.
// Farbe ist Signal, keine Fläche: Kacheln und Karten neutral, die Art eines
// Eintrags zeigt das farbige Symbol (data1/2/3).
// ============================================================================

/** Rollen-Tokens (Auszug, den die Uhr braucht). Namen wie in design-tokens.json. */
@Immutable
data class MhFarben(
    val bg: Color,
    val surface: Color,
    val border: Color,
    val text: Color,
    val textStrong: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val link: Color,
    val primary: Color,
    val onPrimary: Color,
    val danger: Color,
    val onDanger: Color,
    val selectedBg: Color,
    val onSelected: Color,
    val textGreen: Color,
    val data1: Color,
    val data2: Color,
    val data3: Color,
)

/** Dunkel-Rollen aus dem Guide. */
val MhDunkel = MhFarben(
    bg = Color(0xFF1F2221),
    surface = Color(0xFF262A28),
    border = Color(0xFF343A36),
    text = Color(0xFFE6EAE7),
    textStrong = Color(0xFFFFFFFF),
    textSecondary = Color(0xFFA9B0AB),
    textTertiary = Color(0xFF8A938D),
    link = Color(0xFFA8D5BA),
    primary = Color(0xFF82BD9B),
    onPrimary = Color(0xFF1F2221),
    danger = Color(0xFFF0B6B1),
    onDanger = Color(0xFF1F2221),
    selectedBg = Color(0xFF2C4F3B),
    onSelected = Color(0xFFC8E8D4),
    textGreen = Color(0xFFA8D5BA),
    data1 = Color(0xFFA8D5BA),
    data2 = Color(0xFFF7E8A4),
    data3 = Color(0xFFCDB4DB),
)

/** Wear-MaterialTheme-Farben aus den Dunkel-Rollen. */
val MhWearFarben = Colors(
    primary = MhDunkel.primary,
    primaryVariant = MhDunkel.primary,
    secondary = MhDunkel.link,
    secondaryVariant = MhDunkel.selectedBg,
    background = MhDunkel.bg,
    surface = MhDunkel.surface,
    error = MhDunkel.danger,
    onPrimary = MhDunkel.onPrimary,
    onSecondary = MhDunkel.bg,
    onBackground = MhDunkel.text,
    onSurface = MhDunkel.text,
    onSurfaceVariant = MhDunkel.textSecondary,
    onError = MhDunkel.onDanger,
)

/** Figtree, nur die Schnitte 400/500/600 (keine Kursive). */
val Figtree = FontFamily(
    Font(R.font.figtree_regular, FontWeight.Normal),
    Font(R.font.figtree_medium, FontWeight.Medium),
    Font(R.font.figtree_semibold, FontWeight.SemiBold),
)

/**
 * Wear-Typografie in Figtree. Abweichend vom Wear-Standard: Buttons 600
 * statt 700, große Zahlen (display, z. B. in den Auswahlrädern) dünn in 400.
 */
val MhWearTypo: Typography = Typography(defaultFontFamily = Figtree).let { t ->
    t.copy(
        display1 = t.display1.copy(fontWeight = FontWeight.Normal),
        display2 = t.display2.copy(fontWeight = FontWeight.Normal),
        display3 = t.display3.copy(fontWeight = FontWeight.Normal),
        button = t.button.copy(fontWeight = FontWeight.SemiBold),
    )
}
