package ph.bulig.app.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The design tokens, taken verbatim from `docs/design/HANDOFF.md`.
 *
 * Authored in oklch and given here as their sRGB equivalents, because Compose
 * has no oklch colour space. The oklch value is kept in a comment beside each
 * one so a future change can be made against the source of truth rather than
 * against a hex code somebody eyeballed.
 *
 * Nothing outside this file may declare a colour. A screen that reaches for a
 * raw hex has left the design system, and the first sign of that is usually a
 * green that means something it should not.
 */
object BuligColors {

    // --- surfaces ---------------------------------------------------------
    /** oklch(0.98 0.004 250) */
    val Canvas = Color(0xFFF8F9FB)
    /** oklch(1 0 0) */
    val Surface = Color(0xFFFFFFFF)
    /** oklch(0.90 0.008 250) */
    val Border = Color(0xFFDFE2E8)

    // --- text -------------------------------------------------------------
    /** oklch(0.22 0.015 260) */
    val Ink = Color(0xFF25272E)
    /** oklch(0.50 0.014 260) — secondary text, Waray subtitles */
    val InkMuted = Color(0xFF6E717A)
    /** oklch(0.64 0.012 260) — metadata, timestamps, disabled */
    val InkSubtle = Color(0xFF94969E)
    val InkInverse = Color(0xFFFFFFFF)

    // --- brand ------------------------------------------------------------
    /** oklch(0.52 0.16 250) — borders on selected/active, icon accents */
    val Brand = Color(0xFF3B6FD4)
    /** oklch(0.44 0.17 252) — primary fill, active nav, links */
    val BrandStrong = Color(0xFF2A5AC4)
    /** oklch(0.95 0.03 250) — selected tile fill, mesh callout */
    val BrandSoft = Color(0xFFE8EFFD)

    // --- priority ---------------------------------------------------------
    // Never used alone. Every priority indicator pairs colour with a text label
    // and a distinct icon silhouette, so it survives colour blindness and the
    // mono laser printers barangay offices use for duty rosters.
    /** oklch(0.55 0.22 25) */
    val PriorityCritical = Color(0xFFD2352A)
    /** oklch(0.65 0.19 55) */
    val PriorityHigh = Color(0xFFE8761F)
    /** oklch(0.75 0.15 90) */
    val PriorityModerate = Color(0xFFD89B1C)
    /** oklch(0.65 0.10 200) */
    val PriorityLow = Color(0xFF3E9FB4)

    /** Text placed on HIGH and MODERATE fills, which are too light for white. */
    val OnPriorityHigh = Color(0xFF3A2A18)
    val OnPriorityModerate = Color(0xFF3B3118)

    // --- state ------------------------------------------------------------
    /**
     * oklch(0.62 0.16 150).
     *
     * RESERVED for confirmed truth: delivered, resolved, connected. Never for
     * "sent", "queued", or a local success toast. A submit confirmation that
     * renders green while offline is a bug, not a styling choice.
     */
    val StateOnline = Color(0xFF2E9E63)
    /** oklch(0.70 0.17 65) — no signal, pending delivery */
    val StateOffline = Color(0xFFE28A1B)
    /** oklch(0.60 0.15 250) — uploading, relaying, in motion */
    val StateSyncing = Color(0xFF4A7FDD)
    /** oklch(0.55 0.22 25) — life-threatening, destructive */
    val StateDanger = Color(0xFFD2352A)

    /** Grey rule over an 8% tint: the truth for a report held only on this phone. */
    val StateLocal = InkMuted
}

/**
 * Sizing.
 *
 * The minimums are load-bearing, not stylistic. The user is frightened, possibly
 * in rain, with wet hands, one-handed, on a cheap phone — so 16sp is the floor
 * for anything instructional and 48dp the floor for anything tappable.
 */
object BuligDimens {
    val ScreenPadding = 16.dp
    val CardPadding = 14.dp
    val GapSmall = 8.dp
    val Gap = 10.dp
    val GapLarge = 14.dp

    val PanelRadius = 12.dp
    val InnerRadius = 10.dp
    val ChipRadius = 7.dp

    /** Minimum tappable size. Never go below this. */
    val TouchTarget = 48.dp
    val PrimaryButtonHeight = 60.dp
    val SecondaryButtonHeight = 52.dp
    val ResponderActionHeight = 64.dp
    val StepperButton = 52.dp

    /** Artboard 01's emergency button. Deliberately enormous. */
    val EmergencyButtonHeight = 212.dp

    val BannerRule = 4.dp
    val PriorityRule = 5.dp
    val HairlineWidth = 1.dp
    val BorderWidth = 1.5.dp

    val BottomNavHeight = 74.dp
    val BackHeaderHeight = 60.dp
    val MeshStripHeight = 46.dp
}

/** Type scale from the handoff. Inter throughout; mono for machine values. */
object BuligType {
    val EmergencyLabel = 33.sp
    val EmergencyLabelLineHeight = 35.sp

    val ScreenTitle = 18.sp
    val ScreenSubtitle = 13.sp
    val SectionHeading = 17.sp

    /** The floor for instructional text. Nothing that explains anything goes below this. */
    val Body = 16.sp
    val BodySmall = 14.sp
    val ListRowTitle = 16.sp
    val BilingualSubtitle = 12.5.sp
    val Eyebrow = 12.sp
    val BannerLabel = 12.sp
    val BannerSentence = 14.sp
    val ChipLabel = 11.sp
    val MonoMeta = 12.sp
    val EmergencyCode = 30.sp
    val StepperValue = 22.sp
}
