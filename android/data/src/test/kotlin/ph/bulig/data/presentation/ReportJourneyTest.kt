package ph.bulig.data.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ph.bulig.data.repository.IdGenerator
import ph.bulig.data.repository.ReportRepository
import ph.bulig.data.repository.SaveResult
import ph.bulig.data.store.InMemoryReportStore
import ph.bulig.mesh.Clock
import ph.bulig.mesh.crypto.PacketSigner
import ph.bulig.mesh.delivery.DeliveryState
import ph.bulig.mesh.delivery.DeliveryTone
import ph.bulig.mesh.model.DeviceId

/**
 * The resident's whole journey: open the app, file a report, see it listed.
 *
 * Every step here is one the `BuligViewModel` performs by delegation. That class
 * lives in `:app` and cannot be tested in this environment, so this test covers
 * the sequence it is responsible for getting right — the ordering in
 * particular, since "save, then advance" and "advance, then save" look identical
 * until the save fails.
 */
class ReportJourneyTest {

    private val now = 1_787_802_731_000L
    private var minted = 0

    private val store = InMemoryReportStore()

    private val repository = ReportRepository(
        deviceId = DeviceId("this-phone"),
        store = store,
        signer = PacketSigner(deviceKey = null),
        clock = Clock { now },
        ids = IdGenerator { "00000000-0000-4000-8000-%012d".format(++minted) },
    )

    private fun startFlow() = ReportFlowState(
        types = EmergencyTypeCatalog.all,
        isOnline = false,
    )

    /** Walks the flow exactly as the four Continue taps would. */
    private fun walkToReview(typeCode: String, lifeThreatening: Boolean = false): ReportFlowState {
        var state = ReportFlowReducer.selectType(startFlow(), typeCode)
        state = ReportFlowReducer.next(state, now)
        state = ReportFlowReducer.setLifeThreatening(state, lifeThreatening)
        state = ReportFlowReducer.next(state, now)
        state = ReportFlowReducer.next(state, now)
        return state
    }

    @Test
    fun `a resident can file a report end to end`() {
        val review = walkToReview("FLOOD")
        assertEquals(ReportStep.REVIEW, review.step)

        val result = repository.save(review.draft)
        assertTrue(result is SaveResult.Saved, "the flow produced a draft the repository refused")

        val listed = MyReportsStateFactory.build(
            reports = repository.myReports(),
            isOnline = false,
            isSyncing = false,
            typeLabels = EmergencyTypeCatalog.all.associate {
                it.code to TypeLabel(it.code, it.labelEn, it.labelWar)
            },
        )

        assertEquals(1, listed.totalCount)
        assertEquals("Flood", listed.rows.single().typeLabelEn)
    }

    /**
     * The honesty rule, at the exact moment it is most tempting to break.
     *
     * A resident has just tapped "Send report". Nothing has been sent — the
     * report is on their phone. If this row ever renders CONFIRMED, the app is
     * lying at the one moment somebody is deciding whether to go looking for
     * help themselves.
     */
    @Test
    fun `a just-filed report is never presented as delivered`() {
        repository.save(walkToReview("MEDICAL").draft)

        val row = MyReportsStateFactory.build(
            reports = repository.myReports(), isOnline = false, isSyncing = false,
        ).rows.single()

        assertEquals(DeliveryState.SAVED_LOCAL, row.presentation.state)
        assertEquals(DeliveryTone.NEUTRAL, row.presentation.tone)
        assertTrue(row.presentation.sentence.isNotBlank())
    }

    /** The server assigns the code on first sync; offline there is nothing to show. */
    @Test
    fun `a report filed offline carries no emergency code yet`() {
        val saved = repository.save(walkToReview("FIRE").draft) as SaveResult.Saved

        assertNull(saved.report.emergencyCode)
    }

    /**
     * Ordering, stated as a test because it is invisible in the code.
     *
     * The confirmation screen must not appear before the write. Here the draft
     * is invalid, the save is refused, and the flow must therefore stay on
     * REVIEW — a SUBMITTED state after a failed save would tell a resident their
     * report is safe when nothing was stored.
     */
    @Test
    fun `a refused save leaves the flow on the review step`() {
        val review = walkToReview("FLOOD").let {
            // An empty type code is the one thing the draft rejects.
            it.copy(draft = it.draft.copy(typeCode = ""))
        }

        val result = repository.save(review.draft)
        assertTrue(result is SaveResult.Invalid)

        val advanced = if (result is SaveResult.Saved) {
            ReportFlowReducer.submitted(review, null)
        } else {
            review
        }

        assertEquals(ReportStep.REVIEW, advanced.step)
        assertEquals(0, store.count(), "nothing may be stored when the save was refused")
    }

    @Test
    fun `the life-threatening toggle reaches the priority shown on review`() {
        val calm = walkToReview("FLOOD", lifeThreatening = false)
        val urgent = walkToReview("FLOOD", lifeThreatening = true)

        assertNotNull(calm.priority)
        assertNotNull(urgent.priority)
        assertTrue(
            urgent.priority!!.score > calm.priority!!.score,
            "the toggle a resident is warned about did not change the score",
        )
    }

    /** Priority is computed on the device, offline, before anything is stored. */
    @Test
    fun `the review step explains itself without any network`() {
        val review = walkToReview("TRAPPED", lifeThreatening = true)

        val reasons = review.priority!!.reasons()
        assertTrue(reasons.isNotEmpty(), "a score with no explanation is not defensible")
        reasons.forEach {
            assertTrue(it.length > 5, "reason too terse to mean anything: '$it'")
        }
    }

    @Test
    fun `two reports filed in a row are both kept`() {
        repository.save(walkToReview("FLOOD").draft)
        repository.save(walkToReview("MEDICAL").draft)

        assertEquals(2, repository.myReports().size)
        assertEquals(
            2, repository.myReports().map { it.packetId }.toSet().size,
            "the second report overwrote the first",
        )
    }

    /** Home and My reports must not disagree about how much is outstanding. */
    @Test
    fun `home and my reports agree on the pending count`() {
        repository.save(walkToReview("FLOOD").draft)
        repository.save(walkToReview("FIRE").draft)

        val home = HomeStateFactory.build(
            myReports = repository.myReports(),
            carriedForOthers = repository.carriedForOthers(),
            isOnline = false, isSyncing = false, nearbyPeerCount = 0,
        )
        val mine = MyReportsStateFactory.build(
            reports = repository.myReports(), isOnline = false, isSyncing = false,
        )

        assertEquals(home.banner.sentence, mine.banner.sentence)
    }
}
