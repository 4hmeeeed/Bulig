package ph.bulig.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ph.bulig.app.components.PrimaryButton
import ph.bulig.app.components.SecondaryButton
import ph.bulig.app.components.StepHeader
import ph.bulig.app.theme.BuligColors
import ph.bulig.app.theme.BuligDimens
import ph.bulig.app.theme.BuligType
import ph.bulig.data.auth.LoginFailure

/**
 * Responder sign-in.
 *
 * Reached only from a deliberate tap on Home — never shown on launch, never in
 * anybody's way. **A resident never signs in**, because requiring an account to
 * report an emergency would put a network call in front of the one action that
 * must work with no network at all. The "Back" button is always available for
 * exactly that reason.
 *
 * @see docs/02-roles-permissions.md
 */
@Composable
fun LoginScreen(
    onSignIn: (String, String) -> Unit,
    onBack: () -> Unit,
    isWorking: Boolean,
    failure: LoginFailure?,
    modifier: Modifier = Modifier,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BuligColors.Canvas),
    ) {
        StepHeader(title = "Responder sign-in", stepLabel = "", onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(BuligDimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(BuligDimens.Gap),
        ) {
            Icon(
                imageVector = Icons.Filled.Badge,
                contentDescription = null,
                tint = BuligColors.BrandStrong,
                modifier = Modifier
                    .size(44.dp)
                    .align(Alignment.CenterHorizontally),
            )

            Text(
                text = "For barangay responders",
                color = BuligColors.Ink,
                fontSize = BuligType.SectionHeading,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            // Says plainly that nobody else needs this screen, so a resident who
            // wandered in knows to go back rather than hunting for an account.
            Text(
                text = "Residents do not need an account. Reporting an emergency " +
                    "never requires signing in.",
                color = BuligColors.InkMuted,
                fontSize = BuligType.BodySmall,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(6.dp))

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                singleLine = true,
                enabled = !isWorking,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                ),
                shape = RoundedCornerShape(BuligDimens.InnerRadius),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                enabled = !isWorking,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                shape = RoundedCornerShape(BuligDimens.InnerRadius),
                modifier = Modifier.fillMaxWidth(),
            )

            failure?.let { FailureNote(it) }

            Spacer(modifier = Modifier.weight(1f))

            PrimaryButton(
                text = if (isWorking) "Signing in…" else "Sign in",
                onClick = { onSignIn(email, password) },
                enabled = !isWorking && email.isNotBlank() && password.isNotBlank(),
            )

            SecondaryButton(text = "Back", onClick = onBack)
        }
    }
}

/**
 * Why it failed, in terms that say what to do next.
 *
 * The three cases lead to three different actions — retype, retry, or phone the
 * barangay — so collapsing them into "Sign-in failed" would leave a responder
 * guessing during an emergency.
 */
@Composable
private fun FailureNote(failure: LoginFailure) {
    val message = when (failure) {
        LoginFailure.WrongCredentials ->
            "That email and password do not match. Check them and try again."

        LoginFailure.AccountDisabled ->
            "This account has been disabled. Contact the barangay office — " +
                "retrying will not help."

        is LoginFailure.Unreachable ->
            "Cannot reach the barangay server. You can try again when you have signal."

        is LoginFailure.ServerError ->
            "The barangay server is having trouble (${failure.status}). Try again shortly."
    }

    val accent = if (failure.isTransient) BuligColors.StateOffline else BuligColors.StateDanger

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.10f), RoundedCornerShape(BuligDimens.InnerRadius))
            .padding(BuligDimens.CardPadding),
        horizontalArrangement = Arrangement.spacedBy(BuligDimens.GapSmall),
    ) {
        Icon(
            imageVector = Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = message,
            color = BuligColors.Ink,
            fontSize = BuligType.BodySmall,
            lineHeight = 20.sp,
        )
    }
}
