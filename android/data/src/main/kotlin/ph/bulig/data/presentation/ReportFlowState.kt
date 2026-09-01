package ph.bulig.data.presentation

import ph.bulig.data.model.ReportDraft
import ph.bulig.mesh.priority.PriorityEngine
import ph.bulig.mesh.priority.PriorityResult
import ph.bulig.mesh.priority.TypeSeverity

/**
 * The four steps a resident walks through, plus the confirmation.
 *
 * Numbered because the design shows "1 / 4" in each header: a frightened person
 * needs to know how much is left, and an unbounded flow feels endless.
 */
enum class ReportStep(val position: Int, val total: Int) {
    TYPE(1, 4),
    DETAILS(2, 4),
    LOCATION(3, 4),
    REVIEW(4, 4),

    /** Not part of the count — the flow is over by the time this shows. */
    SUBMITTED(0, 4);

    val isCounted: Boolean get() = position > 0
    val label: String get() = if (isCounted) "$position / $total" else ""
}

/** One tile in the type picker. Bilingual, because residents read Waray first. */
data class EmergencyTypeOption(
    val code: String,
    val labelEn: String,
    val labelWar: String?,
    val icon: String,
    val baseSeverity: Int,
    val isLifeThreatening: Boolean,
) {
    fun toSeverity() = TypeSeverity(code, baseSeverity, isLifeThreatening)
}

/**
 * A stepper on the details screen.
 *
 * [canDecrement] exists so the minus button can be *disabled at zero rather
 * than hidden*. Hiding it would reflow the row under a thumb that is already
 * moving, which in a flood is how a resident taps the wrong control.
 */
data class StepperState(
    val labelEn: String,
    val labelWar: String,
    val value: Int,
    val min: Int = 0,
    val max: Int = 999,
) {
    val canDecrement: Boolean get() = value > min
    val canIncrement: Boolean get() = value < max
}

/**
 * State for the whole report flow.
 *
 * Deliberately one immutable object rather than five screens each holding a
 * fragment: a resident can go back and change an answer, and a design where
 * "Change" links jump between steps needs every step reading from one source.
 *
 * @see docs/design/HANDOFF.md — artboards 02 to 06
 */
data class ReportFlowState(
    val step: ReportStep = ReportStep.TYPE,
    val draft: ReportDraft = ReportDraft(typeCode = ""),
    val types: List<EmergencyTypeOption> = emptyList(),
    val isOnline: Boolean = false,
    val locationAccuracyM: Double? = null,
    val purok: String? = null,
    val pinAdjustedByHand: Boolean = false,
    val emergencyCode: String? = null,
    val priority: PriorityResult? = null,
) {
    val selectedType: EmergencyTypeOption?
        get() = types.firstOrNull { it.code == draft.typeCode }

    /** Only the type gates progress. Everything after it is optional. */
    val canContinue: Boolean
        get() = when (step) {
            ReportStep.TYPE -> draft.typeCode.isNotBlank()
            ReportStep.DETAILS, ReportStep.LOCATION, ReportStep.REVIEW -> draft.isValid
            ReportStep.SUBMITTED -> false
        }

    val steppers: List<StepperState>
        get() = listOf(
            StepperState("People affected", "Pira ka tawo", draft.affectedCount, min = 1),
            StepperState("Children", "Kabataan", draft.childrenCount),
            StepperState("Elderly", "Lagas", draft.elderlyCount),
            StepperState(
                "Cannot walk alone", "Diri makalakat nga usa", draft.mobilityLimitedCount,
            ),
        )

    /**
     * Whether GPS produced anything usable.
     *
     * A report with only a purok is still worth filing — better than no report —
     * so this drives an explainer, never a block.
     */
    val hasCoordinates: Boolean
        get() = draft.latitude != null && draft.longitude != null

    /**
     * The reassurance under the footer button on every step before the last.
     *
     * Says "nothing is sent yet" because nothing is: the flow touches no network
     * until the resident confirms, and even then it only writes to disk.
     */
    val footerNote: String
        get() = when (step) {
            ReportStep.TYPE -> "Nothing is sent yet."
            ReportStep.DETAILS -> "Nothing is sent yet. Next: confirm where you are."
            ReportStep.LOCATION -> "Nothing is sent yet. One more step."
            ReportStep.REVIEW ->
                "Saved instantly. Relaying starts on its own — you can close the app."
            ReportStep.SUBMITTED -> ""
        }

    /**
     * What the offline strip says during the flow.
     *
     * Offline is the normal case for this app, so the wording is matter-of-fact
     * rather than an error.
     */
    val connectivityNote: String
        get() = if (isOnline) {
            "You have signal. This report will be sent as soon as you confirm it."
        } else {
            "OFFLINE — this report will be saved on your phone"
        }
}

object ReportFlowReducer {

    private val engine = PriorityEngine()

    fun selectType(state: ReportFlowState, code: String): ReportFlowState =
        state.copy(draft = state.draft.copy(typeCode = code))

    fun setDescription(state: ReportFlowState, text: String?): ReportFlowState =
        state.copy(draft = state.draft.copy(description = text?.takeIf { it.isNotBlank() }))

    /**
     * Adjusts one count.
     *
     * Clamped rather than validated: a stepper that can produce an invalid value
     * and then complain about it is a worse control than one that simply cannot.
     */
    fun adjust(state: ReportFlowState, field: CountField, delta: Int): ReportFlowState {
        val draft = state.draft
        val updated = when (field) {
            CountField.AFFECTED ->
                draft.copy(affectedCount = (draft.affectedCount + delta).coerceIn(1, 999))
            CountField.CHILDREN ->
                draft.copy(childrenCount = (draft.childrenCount + delta).coerceIn(0, 999))
            CountField.ELDERLY ->
                draft.copy(elderlyCount = (draft.elderlyCount + delta).coerceIn(0, 999))
            CountField.MOBILITY_LIMITED ->
                draft.copy(mobilityLimitedCount = (draft.mobilityLimitedCount + delta).coerceIn(0, 999))
        }
        return state.copy(draft = updated)
    }

    fun setLifeThreatening(state: ReportFlowState, value: Boolean): ReportFlowState =
        state.copy(draft = state.draft.copy(isLifeThreatening = value))

    fun setLocation(
        state: ReportFlowState,
        latitude: Double?,
        longitude: Double?,
        accuracyM: Double?,
        provider: String,
        capturedAtMs: Long?,
        byHand: Boolean = false,
    ): ReportFlowState = state.copy(
        draft = state.draft.copy(
            latitude = latitude,
            longitude = longitude,
            accuracyM = accuracyM,
            locationProvider = provider,
            capturedAtMs = capturedAtMs,
        ),
        locationAccuracyM = accuracyM,
        pinAdjustedByHand = byHand,
    )

    fun setPurok(state: ReportFlowState, purok: String?): ReportFlowState =
        state.copy(purok = purok)

    /**
     * Moves forward, computing priority when the review step is reached.
     *
     * Priority is scored here, on the device, offline — never fetched. A spinner
     * on this screen would be a network dependency in the one flow that must not
     * have one.
     */
    fun next(state: ReportFlowState, nowMs: Long): ReportFlowState {
        if (!state.canContinue) return state

        val nextStep = when (state.step) {
            ReportStep.TYPE -> ReportStep.DETAILS
            ReportStep.DETAILS -> ReportStep.LOCATION
            ReportStep.LOCATION -> ReportStep.REVIEW
            ReportStep.REVIEW, ReportStep.SUBMITTED -> return state
        }

        val priority = if (nextStep == ReportStep.REVIEW) {
            state.selectedType?.let { type ->
                engine.score(
                    type = type.toSeverity(),
                    payload = state.draft.toPayload(),
                    reportedAtMs = nowMs,
                    nowMs = nowMs,
                )
            }
        } else {
            state.priority
        }

        return state.copy(step = nextStep, priority = priority)
    }

    fun back(state: ReportFlowState): ReportFlowState {
        val previous = when (state.step) {
            ReportStep.DETAILS -> ReportStep.TYPE
            ReportStep.LOCATION -> ReportStep.DETAILS
            ReportStep.REVIEW -> ReportStep.LOCATION
            // Going back from the confirmation would invite re-filing a report
            // that already exists, and the first step has nothing before it.
            ReportStep.TYPE, ReportStep.SUBMITTED -> null
        }

        return previous?.let { state.copy(step = it) } ?: state
    }

    /** The "Change" links on the review screen jump straight to the owning step. */
    fun jumpTo(state: ReportFlowState, step: ReportStep): ReportFlowState =
        if (state.step == ReportStep.REVIEW && step.isCounted) {
            state.copy(step = step)
        } else {
            state
        }

    /**
     * Records that the report was committed locally.
     *
     * The code comes from the caller because it is minted by the repository, and
     * the state advances to SUBMITTED only after the write has actually
     * happened — never on the tap that requested it.
     */
    fun submitted(state: ReportFlowState, emergencyCode: String?): ReportFlowState =
        state.copy(step = ReportStep.SUBMITTED, emergencyCode = emergencyCode)

    private fun ReportDraft.toPayload() = ph.bulig.mesh.model.EmergencyPayload(
        typeCode = typeCode,
        description = description,
        affectedCount = affectedCount,
        childrenCount = childrenCount,
        elderlyCount = elderlyCount,
        mobilityLimitedCount = mobilityLimitedCount,
        isLifeThreatening = isLifeThreatening,
        vulnerabilityNotes = vulnerabilityNotes,
        latitude = latitude,
        longitude = longitude,
        accuracyM = accuracyM,
        locationProvider = locationProvider,
        capturedAtMs = capturedAtMs,
    )
}

enum class CountField { AFFECTED, CHILDREN, ELDERLY, MOBILITY_LIMITED }
