package ph.bulig.data.presentation

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The third cross-language contract, alongside the HMAC vector and the priority
 * parity check.
 *
 * The phone scores a report offline using its bundled catalog; the server
 * rescores it on arrival using the seeded one. If a base severity differs by so
 * much as a point, a resident is shown one priority and an operator sees
 * another — and nobody would notice until someone compared two screens during a
 * flood.
 *
 * So this test does not hand-copy the expected values: it reads the PHP seeder
 * and compares. Editing either file alone fails here.
 */
class EmergencyTypeCatalogTest {

    private fun seederFile(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "backend/database/seeders/EmergencyTypeSeeder.php")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        fail("could not locate EmergencyTypeSeeder.php from ${File(".").absolutePath}")
    }

    /** Matches `['MEDICAL', 'Medical', 'Emerhensya Medikal', 'medical_services', 35, true],`. */
    private val row = Regex(
        """\[\s*'([A-Z_]+)',\s*'([^']*)',\s*'([^']*)',\s*'([^']*)',\s*(\d+),\s*(true|false)\s*\]"""
    )

    private data class SeededType(
        val code: String,
        val labelEn: String,
        val labelWar: String,
        val icon: String,
        val baseSeverity: Int,
        val isLifeThreatening: Boolean,
    )

    private fun seeded(): List<SeededType> =
        row.findAll(seederFile().readText()).map { m ->
            val (code, en, war, icon, severity, life) = m.destructured
            SeededType(code, en, war, icon, severity.toInt(), life == "true")
        }.toList()

    @Test
    fun `the seeder is parseable at all`() {
        // Guards the regex itself: a silently-zero match would make every other
        // assertion in this class vacuously pass.
        assertTrue(seeded().size >= 10, "parsed ${seeded().size} rows from the seeder")
    }

    @Test
    fun `the phone offers exactly the types the server knows`() {
        assertEquals(
            seeded().map { it.code },
            EmergencyTypeCatalog.all.map { it.code },
            "catalog and seeder disagree on which types exist, or on their order",
        )
    }

    /**
     * The field that actually changes a score. A drifting label is cosmetic; a
     * drifting severity means the phone and the server rank the same emergency
     * differently.
     */
    @Test
    fun `every base severity matches the server`() {
        seeded().forEach { expected ->
            val actual = EmergencyTypeCatalog.byCode(expected.code)
                ?: fail("catalog is missing ${expected.code}")

            assertEquals(
                expected.baseSeverity, actual.baseSeverity,
                "${expected.code} scores differently on phone and server",
            )
        }
    }

    /** Life-threatening adds a flat 25 in both engines. Drift here is a 25-point error. */
    @Test
    fun `every life-threatening flag matches the server`() {
        seeded().forEach { expected ->
            val actual = EmergencyTypeCatalog.byCode(expected.code)!!

            assertEquals(
                expected.isLifeThreatening, actual.isLifeThreatening,
                "${expected.code} is life-threatening on one side only",
            )
        }
    }

    @Test
    fun `the bilingual labels match the server`() {
        seeded().forEach { expected ->
            val actual = EmergencyTypeCatalog.byCode(expected.code)!!

            assertEquals(expected.labelEn, actual.labelEn, "English label for ${expected.code}")
            assertEquals(expected.labelWar, actual.labelWar, "Waray label for ${expected.code}")
            assertEquals(expected.icon, actual.icon, "icon for ${expected.code}")
        }
    }

    @Test
    fun `no code appears twice`() {
        val codes = EmergencyTypeCatalog.all.map { it.code }
        assertEquals(codes.size, codes.toSet().size, "duplicate code in the catalog")
    }

    /**
     * OTHER must exist and must be last. A resident facing something the list
     * does not name still has to be able to file — an unlisted emergency that
     * cannot be reported is the worst failure this screen can have.
     */
    @Test
    fun `there is always an escape hatch and it sorts last`() {
        assertEquals("OTHER", EmergencyTypeCatalog.all.last().code)
    }

    @Test
    fun `every type carries a waray label for the resident-facing tiles`() {
        EmergencyTypeCatalog.all.forEach {
            assertTrue(!it.labelWar.isNullOrBlank(), "${it.code} has no Waray label")
        }
    }
}
