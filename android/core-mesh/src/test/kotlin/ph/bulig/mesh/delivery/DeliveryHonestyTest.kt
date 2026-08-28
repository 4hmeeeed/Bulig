package ph.bulig.mesh.delivery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The product's one non-negotiable rule, enforced by the build.
 *
 *   > The app must never lie about delivery.
 *
 * A design document can state that rule; only a test can keep it true after
 * someone adds a screen at 2am the week before a defense. These assertions are
 * deliberately about the RULE rather than about pixels — they will survive any
 * amount of visual redesign.
 *
 * @see docs/design/README.md
 */
class DeliveryHonestyTest {

    /**
     * THE test. Green means the command center actually has it — nothing else.
     *
     * Not "sent", not "queued", not "saved successfully", not "relayed to three
     * phones". A submit confirmation that renders green while offline is a bug.
     */
    @Test
    fun `no undelivered state is ever presented as confirmed`() {
        DeliveryState.entries.forEach { state ->
            val presentation = DeliveryFormatter.present(state, hopCount = 3)

            if (state.isUndelivered) {
                assertFalse(
                    presentation.tone == DeliveryTone.CONFIRMED,
                    "$state is not confirmed by the command center, so it must never " +
                        "render in the confirmed tone. Green is reserved.",
                )
            }
        }
    }

    @Test
    fun `every confirmed state has actually reached the command center`() {
        DeliveryState.entries
            .filter { DeliveryFormatter.present(it).tone == DeliveryTone.CONFIRMED }
            .forEach {
                assertTrue(
                    it.isConfirmedByCommandCenter,
                    "$it renders as confirmed but has not been acknowledged",
                )
            }
    }

    /**
     * Relaying is the state most likely to be misread as success, so the word
     * "relayed" is never allowed to stand alone.
     */
    @Test
    fun `relayed always says it is not delivered`() {
        val chip = DeliveryFormatter.present(DeliveryState.RELAYED, hopCount = 3)
        assertTrue(
            chip.sentence.contains("Not yet delivered", ignoreCase = true),
            "the relayed sentence must contain the negation; was '${chip.sentence}'",
        )

        val banner = BannerFormatter.present(ConnectivityState.RELAYED, count = 3)
        assertTrue(
            banner.eyebrow.contains("NOT DELIVERED"),
            "'RELAYED' alone reads as success; was '${banner.eyebrow}'",
        )
    }

    /** A locally held report must not imply anyone else knows about it. */
    @Test
    fun `saved local never claims anyone has seen it`() {
        val chip = DeliveryFormatter.present(DeliveryState.SAVED_LOCAL)

        assertEquals(DeliveryTone.NEUTRAL, chip.tone)
        listOf("sent", "delivered", "received").forEach { forbidden ->
            assertFalse(
                chip.label.contains(forbidden, ignoreCase = true) ||
                    chip.sentence.contains(forbidden, ignoreCase = true),
                "a locally-held report must not use the word '$forbidden'",
            )
        }
    }

    /**
     * The chip is never the only explanation — a colour and an uppercase label
     * are not something a frightened person should have to decode.
     */
    @Test
    fun `every state carries a plain language sentence`() {
        DeliveryState.entries.forEach { state ->
            val p = DeliveryFormatter.present(state)

            assertTrue(p.sentence.length > 20, "$state has no real sentence: '${p.sentence}'")
            assertTrue(p.sentence.first().isUpperCase(), "$state sentence is not a sentence")
            assertTrue(p.icon.isNotBlank(), "$state has no icon")
            assertTrue(p.label.isNotBlank(), "$state has no label")
        }
    }

    /** State moves forward on evidence, and only forward. */
    @Test
    fun `delivery state cannot move backwards`() {
        assertTrue(DeliveryState.SAVED_LOCAL.canAdvanceTo(DeliveryState.RELAYED))
        assertTrue(DeliveryState.RELAYED.canAdvanceTo(DeliveryState.DELIVERED))
        assertTrue(DeliveryState.DELIVERED.canAdvanceTo(DeliveryState.RESOLVED))

        // A late mesh event must never drag a resolved report back into motion.
        assertFalse(DeliveryState.RESOLVED.canAdvanceTo(DeliveryState.RELAYED))
        assertFalse(DeliveryState.DELIVERED.canAdvanceTo(DeliveryState.SAVED_LOCAL))
        assertFalse(DeliveryState.RELAYED.canAdvanceTo(DeliveryState.RELAYED))
    }

    /** Relaying is not delivery, however many phones took a copy. */
    @Test
    fun `no number of hops turns relayed into delivered`() {
        listOf(1, 3, 12, 99).forEach { hops ->
            val p = DeliveryFormatter.present(DeliveryState.RELAYED, hopCount = hops)

            assertEquals(
                DeliveryTone.IN_MOTION, p.tone,
                "$hops hops is still not delivery",
            )
            assertTrue(DeliveryState.RELAYED.isUndelivered)
        }
    }

    @Test
    fun `hop and report counts are pluralised properly`() {
        assertTrue(DeliveryFormatter.present(DeliveryState.RELAYED, 1).label.contains("1 PHONE"))
        assertTrue(DeliveryFormatter.present(DeliveryState.RELAYED, 3).label.contains("3 PHONES"))

        assertEquals(
            "1 report waiting to be delivered",
            BannerFormatter.present(ConnectivityState.PENDING, 1).sentence,
        )
        assertEquals(
            "4 reports waiting to be delivered",
            BannerFormatter.present(ConnectivityState.PENDING, 4).sentence,
        )
        assertEquals(
            "Passed to 1 nearby phone",
            BannerFormatter.present(ConnectivityState.RELAYED, 1).sentence,
        )
    }

    /**
     * Two pairs of banner states share a hue by design. They must remain
     * distinguishable by icon and wording alone, so the set still works in
     * greyscale and for a colour-blind user.
     */
    @Test
    fun `banner states sharing a tone are separated by icon and wording`() {
        ConnectivityState.entries
            .groupBy { it.tone }
            .filterValues { it.size > 1 }
            .forEach { (tone, states) ->
                val presentations = states.map { BannerFormatter.present(it, count = 2) }

                assertEquals(
                    states.size, presentations.map { it.icon }.distinct().size,
                    "states sharing the $tone tone reuse an icon: " +
                        presentations.joinToString { "${it.state}=${it.icon}" },
                )
                assertEquals(
                    states.size, presentations.map { it.eyebrow }.distinct().size,
                    "states sharing the $tone tone reuse an eyebrow label",
                )
            }
    }

    @Test
    fun `every banner state is fully specified`() {
        ConnectivityState.entries.forEach { state ->
            val b = BannerFormatter.present(state, count = 3)

            assertTrue(b.icon.isNotBlank(), "$state has no icon")
            assertTrue(b.eyebrow.isNotBlank(), "$state has no eyebrow")
            assertTrue(b.sentence.length > 10, "$state has no usable sentence")
            assertEquals(state.eyebrowIsUppercase(), true, "$state eyebrow must be uppercase")
        }
    }

    private fun ConnectivityState.eyebrowIsUppercase(): Boolean {
        val eyebrow = BannerFormatter.present(this).eyebrow
        return eyebrow == eyebrow.uppercase()
    }

    /** Named responders personalise the sentence without changing its truth. */
    @Test
    fun `responder name appears when known and is omitted gracefully when not`() {
        val named = DeliveryFormatter.present(
            DeliveryState.ASSIGNED, responderName = "Tanod R. Cinco",
        )
        val anonymous = DeliveryFormatter.present(DeliveryState.ASSIGNED)

        assertTrue(named.sentence.contains("Tanod R. Cinco"))
        assertFalse(anonymous.sentence.contains("null"))
        assertTrue(anonymous.sentence.contains("A responder"))
    }
}
