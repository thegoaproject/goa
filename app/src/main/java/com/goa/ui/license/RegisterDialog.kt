package com.goa.ui.license

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.goa.GoaApplication
import com.goa.domain.license.BillingRepository
import com.goa.domain.license.GameSlots
import com.goa.domain.license.LicenseManager
import com.goa.ui.theme.RetroAmber
import com.goa.ui.theme.RetroGreen
import kotlinx.coroutines.launch

/**
 * "Unlock GOA Pro": the one purchase, in the spirit of the shareware order forms these games shipped with. Shown when a
 * free slot is needed and from Settings.
 */
@Composable
fun RegisterDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as GoaApplication
    val billing = app.billingRepository
    val state by billing.state.collectAsState()
    val message by billing.message.collectAsState()
    val registered by app.licenseManager.registered.collectAsState()
    val scope = rememberCoroutineScope()
    val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    if (registered) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Thanks for going Pro!", fontWeight = FontWeight.Bold) },
            text = { Text("GOA Pro is unlocked. Install as many of your own games as you like.") },
            confirmButton = { TextButton(onClick = onDismiss) { com.goa.ui.menu.OneLineLabel("Back to my games") } }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Unlock GOA Pro", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val locked by app.licenseManager.lockedGames.collectAsState(initial = emptySet())
                if (app.licenseManager.revoked || locked.isNotEmpty()) {
                    Text(
                        (if (app.licenseManager.revoked) "Your GOA Pro purchase was refunded, so GOA is back to the free version. "
                        else "GOA is on the free version. ") +
                            "Your ${GameSlots.FREE_SLOTS} most recently played games still work; the others are waiting, " +
                            "with their saves, until you unlock Pro again or uninstall some.",
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Text(
                    "You're using the free version of GOA. It plays every free game in the catalogue, plus " +
                        "${GameSlots.FREE_SLOTS} of your own games at a time from GOG or your own files."
                )
                Text(
                    "GOA Pro installs as many of your own games as you like. One payment, no subscription, " +
                        "no ads, and the games you have keep working either way."
                )
                Text(
                    text = "ORDER FORM\n1 × GOA Pro\nUnlimited games, forever",
                    fontFamily = FontFamily.Monospace,
                    color = RetroAmber,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black, RoundedCornerShape(6.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                )
                Text(
                    "Or uninstall one of your games to free a slot.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (state is BillingRepository.State.Unavailable) {
                    Text(
                        "Google Play billing isn't available right now. Check you're signed in to the Play Store and online.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (val s = state) {
                    is BillingRepository.State.Ready -> Button(
                        onClick = { context.findActivity()?.let { billing.launchPurchase(it) } },
                        colors = ButtonDefaults.buttonColors(containerColor = RetroGreen, contentColor = Color.Black)
                    ) { com.goa.ui.menu.OneLineLabel("Unlock Pro for ${s.price}") }
                    BillingRepository.State.Loading -> Button(onClick = {}, enabled = false) { com.goa.ui.menu.OneLineLabel("Contacting Google Play…") }
                    BillingRepository.State.Unavailable ->
                        if (debuggable) {
                            // Sideloaded test builds can't reach Play billing; lets the gated paths be exercised
                            OutlinedButton(onClick = { app.licenseManager.setRegistered(true, LicenseManager.SOURCE_DEBUG) }) {
                                com.goa.ui.menu.OneLineLabel("Unlock Pro (debug build)")
                            }
                        } else {
                            Button(onClick = { scope.launch { billing.refresh() } }) { com.goa.ui.menu.OneLineLabel("Try again") }
                        }
                }
                TextButton(onClick = { scope.launch { billing.restore() } }) { com.goa.ui.menu.OneLineLabel("Restore purchase") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { com.goa.ui.menu.OneLineLabel("Not now") } }
    )
}

/** "Install (1 of 3 free)", "Install Game" or "Unlock Pro to Install" for the detail pane's install button. */
fun installLabel(decision: GameSlots.Decision): String = when (decision) {
    GameSlots.Decision.Allowed -> "Install Game"
    is GameSlots.Decision.FreeSlot -> "Install (${decision.slotNumber} of ${GameSlots.FREE_SLOTS} free)"
    GameSlots.Decision.NeedsRegistration -> "Unlock Pro to Install"
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Settings → Registration: status, free slots in use, Register and Restore. */
@Composable
fun RegistrationSettingsCard(modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as GoaApplication
    val registered by app.licenseManager.registered.collectAsState()
    val occupied by app.licenseManager.occupiedSlots.collectAsState(initial = emptySet())
    val scope = rememberCoroutineScope()
    var showRegister by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    androidx.compose.material3.Card(
        modifier = modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("GOA Pro", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (registered) {
                Text("GOA Pro is unlocked. Thank you for supporting it!", color = RetroGreen)
            } else {
                Text(
                    "Free version: ${occupied.size} of ${GameSlots.FREE_SLOTS} of your own games installed. " +
                        "Free catalogue games don't count."
                )
                Button(
                    onClick = { showRegister = true },
                    colors = ButtonDefaults.buttonColors(containerColor = RetroGreen, contentColor = Color.Black)
                ) { com.goa.ui.menu.OneLineLabel("Unlock GOA Pro") }
            }
            TextButton(onClick = { scope.launch { app.billingRepository.restore() } }) { com.goa.ui.menu.OneLineLabel("Restore purchase") }
        }
    }
    if (showRegister) RegisterDialog(onDismiss = { showRegister = false })
}
