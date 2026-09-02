package ph.bulig.data.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PermissionStateTest {

    private val all = setOf(
        MeshPermission.FIND_NEARBY_PHONES,
        MeshPermission.BE_FOUND_BY_OTHERS,
        MeshPermission.EXCHANGE_REPORTS,
        MeshPermission.SHOW_RELAY_NOTICE,
    )

    private fun state(vararg granted: MeshPermission, denied: Set<MeshPermission> = emptySet()) =
        PermissionStateFactory.build(granted.toSet(), denied)

    // --- what the phone can actually do ------------------------------------

    @Test
    fun `all four permissions gives a fully participating phone`() {
        assertEquals(MeshCapability.FULL, PermissionStateFactory.build(all).capability)
    }

    /**
     * The distinction no other app tells a resident: a phone that cannot
     * advertise can hand its own reports on, but can never be found, so it will
     * never carry anybody else's.
     */
    @Test
    fun `without advertise permission the phone can send but never receive`() {
        val partial = state(
            MeshPermission.FIND_NEARBY_PHONES,
            MeshPermission.EXCHANGE_REPORTS,
        )

        assertEquals(MeshCapability.OUTGOING_ONLY, partial.capability)
        assertTrue(partial.consequence!!.contains("cannot find it"))
    }

    @Test
    fun `without connect permission nothing can move at all`() {
        assertEquals(
            MeshCapability.NONE,
            state(MeshPermission.FIND_NEARBY_PHONES, MeshPermission.BE_FOUND_BY_OTHERS).capability,
        )
    }

    @Test
    fun `without scan permission nothing can move at all`() {
        assertEquals(
            MeshCapability.NONE,
            state(MeshPermission.BE_FOUND_BY_OTHERS, MeshPermission.EXCHANGE_REPORTS).capability,
        )
    }

    @Test
    fun `granting nothing is the none capability`() {
        assertEquals(MeshCapability.NONE, state().capability)
    }

    // --- what the resident is told -----------------------------------------

    /**
     * Android's own dialog says "Allow Bulig to find nearby devices?". This is
     * the sentence that has to make somebody willing to say yes to it.
     */
    @Test
    fun `the rationale explains why an emergency app wants the radio`() {
        val explanation = state().explanation

        assertTrue(explanation.contains("no signal"))
        assertTrue(explanation.contains("only looks for other phones running Bulig"))
        assertTrue(explanation.contains("never uses your location for anything else"))
    }

    /**
     * A resident who declines must be told what their phone can no longer do —
     * not shown a generic warning and left to guess.
     */
    @Test
    fun `declining is answered with the actual consequence`() {
        val none = state()

        assertTrue(none.consequence!!.contains("stay on this phone"))
        assertTrue(
            none.consequence.contains("You can still write them now"),
            "a resident denied permission must still be told they can file",
        )
    }

    /** No consequence to state means none is shown. Reassurance nobody needed is noise. */
    @Test
    fun `a fully granted phone is told nothing it does not need`() {
        assertNull(PermissionStateFactory.build(all).consequence)
    }

    /**
     * Missing notification permission costs no capability, but it changes what
     * the resident sees — so it is stated rather than quietly ignored.
     */
    @Test
    fun `missing notification permission is disclosed without being called a failure`() {
        val noNotice = state(
            MeshPermission.FIND_NEARBY_PHONES,
            MeshPermission.BE_FOUND_BY_OTHERS,
            MeshPermission.EXCHANGE_REPORTS,
        )

        assertEquals(MeshCapability.FULL, noNotice.capability)
        assertTrue(noNotice.consequence!!.contains("still will"))
    }

    // --- never a gate ------------------------------------------------------

    /**
     * A phone with no permissions at all can still save a report locally.
     * Refusing to let a resident file one would be a worse failure than
     * relaying nothing.
     */
    @Test
    fun `a resident can always continue without granting anything`() {
        listOf(state(), state(MeshPermission.EXCHANGE_REPORTS), PermissionStateFactory.build(all))
            .forEach {
                assertTrue(
                    it.canContinueWithoutGranting,
                    "the permission screen became a hard gate at ${it.capability}",
                )
            }
    }

    @Test
    fun `a permanently denied permission sends the resident to settings`() {
        val denied = state(denied = setOf(MeshPermission.EXCHANGE_REPORTS))

        assertTrue(denied.needsSettings)
        assertEquals("Open settings", denied.primaryLabel)
    }

    @Test
    fun `a granted phone offers to continue rather than to ask again`() {
        assertEquals("Continue", PermissionStateFactory.build(all).primaryLabel)
        assertTrue(PermissionStateFactory.build(all).isFullyGranted)
    }

    @Test
    fun `an ungranted phone offers to ask`() {
        assertEquals("Allow Bluetooth", state().primaryLabel)
    }

    // --- platform ----------------------------------------------------------

    /** API 33 added a runtime permission for the notification the service must show. */
    @Test
    fun `notification permission is only requested where it exists`() {
        assertTrue(PermissionStateFactory.required(33).contains(MeshPermission.SHOW_RELAY_NOTICE))
        assertTrue(!PermissionStateFactory.required(31).contains(MeshPermission.SHOW_RELAY_NOTICE))
    }

    @Test
    fun `the three radio permissions are always required`() {
        listOf(26, 31, 33, 35).forEach { sdk ->
            val required = PermissionStateFactory.required(sdk)

            assertTrue(required.contains(MeshPermission.FIND_NEARBY_PHONES), "sdk $sdk")
            assertTrue(required.contains(MeshPermission.BE_FOUND_BY_OTHERS), "sdk $sdk")
            assertTrue(required.contains(MeshPermission.EXCHANGE_REPORTS), "sdk $sdk")
        }
    }
}
