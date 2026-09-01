package ph.bulig.app.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Water
import androidx.compose.material.icons.filled.WavingHand
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Maps a catalog icon name to a drawable.
 *
 * The names come from the backend seeder, which uses Material Symbols
 * identifiers. Compose's `material-icons-extended` does not carry every Symbol —
 * there is no `Flood`, `Landslide`, or `Earthquake` in it — so several of these
 * are the closest available stand-in rather than the designed glyph.
 *
 * Centralised in one function on purpose: this is the file most likely to fail
 * to resolve on first build, and when it does, every fix is here rather than
 * scattered across screens. The `else` branch means an unmapped name renders a
 * neutral icon instead of crashing — a resident must still be able to file a
 * report of a type whose glyph we got wrong.
 *
 * TO BE REPLACED: ship the real Material Symbols as vector drawables before the
 * pilot. A landslide rendered as a mountain is legible; it is not the design.
 */
fun emergencyIcon(name: String): ImageVector = when (name) {
    "medical_services" -> Icons.Filled.MedicalServices
    "local_fire_department" -> Icons.Filled.LocalFireDepartment
    "flood" -> Icons.Filled.Water              // stand-in
    "landslide" -> Icons.Filled.Terrain        // stand-in
    "earthquake" -> Icons.Filled.Vibration     // stand-in
    "hail" -> Icons.Filled.WavingHand          // stand-in: "rescue needed"
    "person_search" -> Icons.Filled.PersonSearch
    "emergency_home" -> Icons.Filled.Home      // stand-in
    "construction" -> Icons.Filled.Construction
    "more_horiz" -> Icons.Filled.MoreHoriz
    else -> Icons.Filled.MoreHoriz
}
