package com.goa.ui.savestate

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.goa.data.drive.DriveAuth
import kotlinx.coroutines.launch

/**
 * Makes sure there is a Google account and Drive access before [ensure]'s action runs: the system account chooser the
 * first time (or on [changeAccount]), then Google's consent screen once. Later runs need neither.
 */
class DriveAccess internal constructor(
    private val auth: DriveAuth,
    private val chooseAccount: () -> Unit,
    private val authorizeThen: (() -> Unit) -> Unit,
    private val setPending: ((() -> Unit)?) -> Unit
) {
    fun ensure(action: () -> Unit) {
        setPending(action)
        if (auth.account.value == null) chooseAccount() else authorizeThen(action)
    }

    /** Picks another Google account on the phone; [then] runs once it has access. */
    fun changeAccount(then: (() -> Unit)? = null) {
        setPending(then)
        chooseAccount()
    }
}

@Composable
fun rememberDriveAccess(auth: DriveAuth, onMessage: (String) -> Unit): DriveAccess {
    val scope = rememberCoroutineScope()
    val holder = remember { arrayOfNulls<() -> Unit>(1) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val action = holder[0]
        holder[0] = null
        if (auth.onConsentResult(result.data) != null) action?.invoke()
        else onMessage("GOA needs your permission to keep save states in your Google Drive.")
    }
    val authorizeThen: (() -> Unit) -> Unit = { action ->
        scope.launch {
            when (val access = auth.authorize()) {
                is DriveAuth.Access.Granted -> {
                    holder[0] = null
                    action()
                }
                is DriveAuth.Access.NeedsConsent -> consent.launch(IntentSenderRequest.Builder(access.intent).build())
                is DriveAuth.Access.Failed -> {
                    holder[0] = null
                    onMessage(access.error.message)
                }
            }
        }
    }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val action = holder[0]
        if (auth.onAccountChosen(result.data)) authorizeThen(action ?: {}) else holder[0] = null
    }
    return remember(auth) {
        DriveAccess(auth, { chooser.launch(auth.chooseAccountIntent()) }, authorizeThen) { holder[0] = it }
    }
}
