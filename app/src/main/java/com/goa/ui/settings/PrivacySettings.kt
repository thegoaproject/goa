package com.goa.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.goa.BuildConfig
import com.goa.GoaApplication
import com.goa.telemetry.AnonymousPlayerId
import com.goa.telemetry.ConsentRepository
import com.goa.telemetry.ConsentState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Settings → Privacy: opt-in sharing toggle, what is collected, player id reset and data deletion (FR-009, SC-009). */
@Composable
fun PrivacySettingsCard(modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as? GoaApplication ?: return
    val consent = remember(app) { ConsentRepository(app, app.database.telemetryDao()) }
    val state by consent.state.collectAsState()
    val scope = rememberCoroutineScope()
    var showDetails by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PrivacyTip, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Privacy", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Share anonymous game health data", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Helps fix games that run badly and improve default settings. Off unless you turn it on.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state == ConsentState.GRANTED,
                    onCheckedChange = { on -> scope.launch { consent.set(if (on) ConsentState.GRANTED else ConsentState.DECLINED) } }
                )
            }
            TextButton(onClick = { showDetails = !showDetails }) { com.goa.ui.menu.OneLineLabel(if (showDetails) "Hide details" else "What we collect") }
            if (showDetails) {
                Text(SHARED_DATA_SUMMARY, style = MaterialTheme.typography.bodySmall)
                Text(NEVER_SHARED_SUMMARY, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    AnonymousPlayerId.reset(app)
                    status = "New player ID created. Data shared under the old ID can still be deleted below."
                }) { com.goa.ui.menu.OneLineLabel("Reset player ID") }
                OutlinedButton(onClick = {
                    scope.launch {
                        val ids = listOfNotNull(AnonymousPlayerId.current(app)) + AnonymousPlayerId.previous(app)
                        val ok = ids.isEmpty() || withContext(Dispatchers.IO) { deleteShared(ids) }
                        if (ok) AnonymousPlayerId.clearPrevious(app)
                        status = if (ok) "Your shared data has been deleted." else "Couldn't reach GOA. Try again when online."
                    }
                }) { com.goa.ui.menu.OneLineLabel("Delete my shared data") }
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        }
    }
}

private fun deleteShared(playerIds: List<String>): Boolean = runCatching {
    val base = BuildConfig.TELEMETRY_BASE_URL.trimEnd('/')
    if (base.isEmpty()) return true
    val body = org.json.JSONObject().put("playerIds", org.json.JSONArray(playerIds.take(50))).toString()
    val conn = (URL("$base/v1/players/delete").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        connectTimeout = 15_000
        readTimeout = 20_000
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("X-GOA-Client", BuildConfig.FREEWARE_CDN_CLIENT_KEY)
    }
    conn.outputStream.use { it.write(body.toByteArray()) }
    (conn.responseCode == 204).also { conn.disconnect() }
}.getOrDefault(false)
