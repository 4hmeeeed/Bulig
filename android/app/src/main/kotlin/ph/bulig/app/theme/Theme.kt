package ph.bulig.app.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Bulig's Material 3 theme.
 *
 * Two deliberate departures from Material defaults:
 *
 * **No dynamic colour.** Material You would recolour the app from the user's
 * wallpaper, which on this product could tint a delivery chip green on one phone
 * and not another. The delivery palette carries meaning, so it is fixed.
 *
 * **No dark theme, for now.** The design bundle specifies one palette, and a
 * dark variant invented here would have to re-derive which greens still mean
 * "confirmed" — a decision that belongs to the designer, not to a guess. The
 * light palette is applied in both cases until that work is done.
 */
private val BuligColorScheme = lightColorScheme(
    primary = BuligColors.BrandStrong,
    onPrimary = BuligColors.InkInverse,
    primaryContainer = BuligColors.BrandSoft,
    onPrimaryContainer = BuligColors.BrandStrong,

    // "Error" in Material terms is Bulig's danger: the emergency button and
    // life-threatening controls, not validation messages.
    error = BuligColors.StateDanger,
    onError = BuligColors.InkInverse,

    background = BuligColors.Canvas,
    onBackground = BuligColors.Ink,
    surface = BuligColors.Surface,
    onSurface = BuligColors.Ink,
    onSurfaceVariant = BuligColors.InkMuted,
    outline = BuligColors.Border,
    outlineVariant = BuligColors.Border,
)

/**
 * Body sizes start at 16sp because the person reading this may be in the dark,
 * in the rain, holding a child. Nothing instructional is smaller.
 */
private val BuligTypography = Typography(
    titleLarge = TextStyle(fontSize = BuligType.ScreenTitle, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = BuligType.SectionHeading, fontWeight = FontWeight.Bold),
    bodyLarge = TextStyle(fontSize = BuligType.Body, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = BuligType.BodySmall, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(
        fontSize = BuligType.Eyebrow,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.1.sp,
    ),
)

@Composable
fun BuligTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = BuligColorScheme,
        typography = BuligTypography,
        content = content,
    )
}
