package com.goa.ui.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.goa.ui.settings.ThirdPartyComponent
import com.goa.ui.settings.ThirdPartyNotices
import com.goa.ui.theme.GOATheme
import com.goa.ui.theme.RetroTypographyScope

private const val TERMS_KEY = "__terms__"
private const val PRIVACY_KEY = "__privacy__"

/** ☰ → Licenses: Terms and Privacy, every open-source component with its licence, and Special Thanks. */
@Composable
fun LicensesPage(onBack: () -> Unit) {
    var showing by rememberSaveable { mutableStateOf<String?>(null) }
    MenuPageScaffold(title = MenuPage.LICENSES.title, onBack = onBack) {
        LicensesCard(onShowLicense = { showing = it })
    }
    showing?.let { LicenseTextDialog(licenseType = it, onDismiss = { showing = null }) }
}

@Composable
private fun LicensesCard(onShowLicense: (String) -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { onShowLicense(TERMS_KEY) }, modifier = Modifier.weight(1f).height(44.dp)) {
                    OneLineLabel("Terms of Use")
                }
                OutlinedButton(onClick = { onShowLicense(PRIVACY_KEY) }, modifier = Modifier.weight(1f).height(44.dp)) {
                    OneLineLabel("Privacy Policy")
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Open Source Licenses & Credits",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Text(
                text = "GOA is built on open-source emulators, archive tools and libraries. Tap a licence to read it in full.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            ThirdPartyNotices.sections.forEach { section ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                section.components.forEach { component ->
                    LicenseRow(component = component, onLicenseClick = { onShowLicense(component.name) })
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Special Thanks",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Text(
                text = ThirdPartyNotices.thanks.joinToString("\n") { "• $it" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = MaterialTheme.typography.bodySmall.lineHeight * 1.3f
            )
        }
    }
}

@Composable
private fun LicenseRow(component: ThirdPartyComponent, onLicenseClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = component.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(
                onClick = onLicenseClick,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.height(28.dp),
                shape = MaterialTheme.shapes.small
            ) {
                Text(text = component.licenseLabel, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
            }
        }
        Text(text = component.authors, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Text(text = component.usedFor, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Full licence (or the Terms of Use / Privacy Policy) read from assets, so the app always shows the complete text. */
@Composable
private fun LicenseTextDialog(licenseType: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val component = ThirdPartyNotices.sections.flatMap { it.components }.firstOrNull { it.name == licenseType }
    val title = when (licenseType) {
        TERMS_KEY -> "Terms of Use"
        PRIVACY_KEY -> "Privacy Policy"
        else -> component?.name ?: licenseType
    }
    val body = remember(licenseType) {
        fun asset(path: String) = runCatching { context.assets.open(path).bufferedReader().use { it.readText() } }
            .getOrDefault("(missing: $path)")
        if (licenseType == TERMS_KEY) {
            asset(ThirdPartyNotices.TERMS_ASSET)
        } else if (licenseType == PRIVACY_KEY) {
            asset(ThirdPartyNotices.PRIVACY_ASSET)
        } else buildString {
            component?.let { c ->
                append(c.authors).append("\n")
                c.source?.let { append("Source: ").append(it).append("\n") }
                append("\n")
                if (c.licenseFiles.isEmpty()) append("See ${c.source ?: "the provider's terms"} for the terms that apply.")
                c.licenseFiles.forEachIndexed { i, file ->
                    if (i > 0) append("\n\n────────────────────\n\n")
                    append(asset("${ThirdPartyNotices.LICENSE_DIR}/$file.txt"))
                }
            }
        }
    }

    // Retro typeface on purpose (owner request): this dialog is meant to be hard to read
    RetroTypographyScope {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(text = body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { com.goa.ui.menu.OneLineLabel("Close") } }
        )
    }
}

@Preview(name = "Phone", widthDp = 411, heightDp = 1400)
@Preview(name = "Tablet", widthDp = 840, heightDp = 1400)
@Composable
private fun LicensesCardPreview() {
    GOATheme { LicensesCard(onShowLicense = {}) }
}
