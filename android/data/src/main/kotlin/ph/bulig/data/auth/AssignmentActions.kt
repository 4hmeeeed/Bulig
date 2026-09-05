package ph.bulig.data.auth

import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ph.bulig.data.presentation.Assignment
import ph.bulig.data.presentation.ResponderStatus
import ph.bulig.data.sync.HttpSyncApi
import ph.bulig.data.sync.SyncConfig

@Serializable
internal data class DeclineRequestDto(val reason: String)

@Serializable
internal data class StatusRequestDto(val status: String, val notes: String? = null)

/**
 * What became of an attempt to tell the server.
 *
 * The local change has already happened by the time this is consulted — see
 * [AssignmentActions] — so this only decides whether the responder's screen says
 * "uploaded" or "not yet uploaded".
 */
sealed interface PushOutcome {
    data object Uploaded : PushOutcome

    /** No signal, or the server was broken. The change stands locally. */
    data class Deferred(val reason: String) : PushOutcome

    /** The server refused. The change still stands locally and is flagged. */
    data class Refused(val reason: String) : PushOutcome

    val isUploaded: Boolean get() = this is Uploaded
}

/**
 * Pushes a responder's own status changes to the barangay.
 *
 * The endpoints are deliberately not uniform, and this class mirrors that rather
 * than smoothing it over: `accept` and `decline` are their own routes because
 * they are decisions with consequences beyond a status column — declining
 * returns the incident to the operator's queue and frees the responder — while
 * `EN_ROUTE`, `ON_SITE` and `RESOLVED` are progress along a job already taken.
 * The server validates exactly those three and nothing else.
 *
 * **Never called before the local change.** A responder standing in floodwater
 * tapping ON SITE must see it take effect whether or not the barangay server
 * can be reached; this is what runs afterwards to try to tell anybody.
 *
 * @see docs/05-api-contract.md 5.4
 */
class AssignmentActions(
    private val config: SyncConfig,
    private val client: OkHttpClient = HttpSyncApi.defaultClient(),
    private val json: Json = HttpSyncApi.defaultJson,
) {

    /**
     * Reports [target] for an assignment, choosing the right endpoint for it.
     *
     * Returns [PushOutcome.Deferred] rather than throwing for anything the
     * responder cannot fix, because the caller's only reasonable response is to
     * leave the change marked unsynced and carry on.
     */
    fun push(
        token: String,
        assignment: Assignment,
        target: ResponderStatus,
        declineReason: String? = null,
    ): PushOutcome {
        val id = assignment.assignmentId
            ?: return PushOutcome.Refused("this assignment has no server id yet")

        return when (target) {
            ResponderStatus.ACCEPTED -> patch(token, "assignments/$id/accept", null)

            ResponderStatus.DECLINED -> {
                // The server requires a reason, and rightly: an assignment that
                // bounces back to the queue with no explanation tells an
                // operator nothing about whether to send somebody else.
                val reason = declineReason?.takeIf { it.isNotBlank() }
                    ?: return PushOutcome.Refused("a decline needs a reason")

                patch(
                    token, "assignments/$id/decline",
                    json.encodeToString(DeclineRequestDto.serializer(), DeclineRequestDto(reason)),
                )
            }

            ResponderStatus.EN_ROUTE, ResponderStatus.ON_SITE, ResponderStatus.RESOLVED ->
                patch(
                    token, "assignments/$id/status",
                    json.encodeToString(
                        StatusRequestDto.serializer(), StatusRequestDto(target.name),
                    ),
                )

            // ASSIGNED is where the server put it. There is nothing to report.
            ResponderStatus.ASSIGNED -> PushOutcome.Refused("assigned is not a reportable change")
        }
    }

    private fun patch(token: String, path: String, body: String?): PushOutcome {
        val request = Request.Builder()
            .url("${config.baseUrl.trimEnd('/')}/api/v1/$path")
            .patch((body ?: "{}").toRequestBody(JSON))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            return PushOutcome.Deferred(e.message ?: "no connection")
        }

        response.use {
            return when {
                it.isSuccessful -> PushOutcome.Uploaded

                // Worth trying again when there is signal.
                it.code >= 500 || it.code == 429 -> PushOutcome.Deferred("server ${it.code}")

                // 409 means the server has already moved this assignment past
                // the state being reported — usually an operator reassigned it.
                // Retrying cannot help, and the responder needs to look again.
                it.code == 409 -> PushOutcome.Refused("this assignment changed at the command center")

                it.code == 401 || it.code == 403 ->
                    PushOutcome.Refused("your sign-in is no longer valid")

                else -> PushOutcome.Refused("server rejected the change (${it.code})")
            }
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
