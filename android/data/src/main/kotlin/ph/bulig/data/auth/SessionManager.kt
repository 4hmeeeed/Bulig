package ph.bulig.data.auth

/** What the app should show for the person currently holding the phone. */
sealed interface AppMode {
    /**
     * Nobody is signed in. This is the **default and the common case** — a
     * resident never signs in, because requiring an account to report an
     * emergency would put a network call in front of the one action that must
     * work with no network.
     */
    data object Resident : AppMode

    data class Responder(val session: Session) : AppMode

    /**
     * A signed-in operator or admin. The mobile app has no screens for them, so
     * it says so rather than showing a resident's home as though the sign-in had
     * failed.
     */
    data class CommandCenterOnly(val session: Session) : AppMode
}

sealed interface SignInResult {
    data class Success(val mode: AppMode) : SignInResult
    data class Failed(val failure: LoginFailure) : SignInResult
}

/**
 * Who is using the phone, and what that entitles them to see.
 *
 * The policy is small but easy to get wrong in a way that matters: **failing to
 * sign in must never take a resident's ability to report away.** So every
 * failure path here ends at [AppMode.Resident] with the report flow intact, and
 * a stale token is discarded rather than treated as a reason to block the app.
 *
 * @see docs/02-roles-permissions.md
 */
class SessionManager(
    private val api: AuthApi,
    private val store: SessionStore,
) {

    fun currentMode(): AppMode = modeFor(store.load())

    fun isSignedIn(): Boolean = store.load() != null

    fun signIn(email: String, password: String): SignInResult = try {
        val session = api.login(email, password)
        store.save(session)

        SignInResult.Success(modeFor(session))
    } catch (e: LoginException) {
        SignInResult.Failed(e.failure)
    } catch (e: Exception) {
        SignInResult.Failed(LoginFailure.Unreachable(e.message ?: "sign-in failed"))
    }

    /**
     * Re-checks a stored token against the server, if there is one.
     *
     * The only way to learn that a token was revoked, or that a responder was
     * moved to another team, while the app was closed.
     *
     * **A transient failure keeps the session.** A responder opening the app in
     * a barangay with no signal must still see their assignment queue — the
     * whole system exists because connectivity is unreliable, so treating its
     * absence as a sign-out would be the wrong instinct here of all places. Only
     * an answer from the server that the credentials are bad clears it.
     */
    fun refresh(): AppMode {
        val stored = store.load() ?: return AppMode.Resident

        return try {
            val fresh = api.me(stored.token)
            store.save(fresh)
            modeFor(fresh)
        } catch (e: LoginException) {
            if (e.failure.isTransient) {
                modeFor(stored)
            } else {
                store.clear()
                AppMode.Resident
            }
        } catch (e: Exception) {
            modeFor(stored)
        }
    }

    /**
     * Signs out locally first, then tells the server.
     *
     * In that order deliberately: a responder tapping sign-out on a phone with
     * no signal must still be signed out of the phone in front of them, which is
     * the part they can see and the part that matters if they are handing it to
     * somebody else.
     */
    fun signOut() {
        val token = store.load()?.token
        store.clear()
        token?.let { api.logout(it) }
    }

    private fun modeFor(session: Session?): AppMode = when {
        session == null -> AppMode.Resident
        session.role.isResponder -> AppMode.Responder(session)
        session.role.isCommandCenter -> AppMode.CommandCenterOnly(session)

        // A resident account, or a role this build does not recognise. Either
        // way the resident experience is the safe answer: it is the one that
        // still lets somebody report an emergency.
        else -> AppMode.Resident
    }
}
