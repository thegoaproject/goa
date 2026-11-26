package com.goa.ui.settings

import com.goa.ui.theme.RetroTypographyScope
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

const val SUPPORT_EMAIL = "support@goodoldandroid.app"

/** The help text from `assets/legal/help.txt`, the same file the website's Support page is built from (see HelpContent). */
@Composable
fun rememberHelpDocument(): HelpDocument {
    val context = LocalContext.current
    return remember {
        val text = runCatching {
            context.assets.open(ThirdPartyNotices.HELP_ASSET).bufferedReader().use { it.readText() }
        }.getOrDefault("SUPPORT\n\nHelp is unavailable. Email $SUPPORT_EMAIL for support.")
        HelpContent.parse(text)
    }
}

/** ☰ → Help & FAQ's body: the intro, then each question. Works offline. */
@Composable
fun HelpDocumentContent(document: HelpDocument, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        document.intro.forEach { HelpBlockView(it, context) }
        document.sections.forEach { section ->
            HorizontalDivider()
            FaqItem(section, context)
        }
    }
}

@Composable
private fun FaqItem(section: HelpSection, context: Context) {
    // Collapsed by default, and forgotten when the dialog closes: nothing is saved between visits.
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = if (expanded) "Collapse" else "Expand") { expanded = !expanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = section.heading,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
        if (expanded) section.blocks.forEach { HelpBlockView(it, context) }
    }
}

@Composable
private fun HelpBlockView(block: HelpBlock, context: Context) {
    when (block) {
        is HelpBlock.Paragraph -> SpansText(block.spans, context)
        is HelpBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            block.items.forEach { item -> SpansText(listOf<HelpSpan>(HelpSpan.Text("• ")) + item, context) }
        }
    }
}

/** Bold names are shown by colour, not weight: the retro face has only one weight. */
@Composable
private fun SpansText(spans: List<HelpSpan>, context: Context) {
    val accent = MaterialTheme.colorScheme.primary
    val annotated = remember(spans, accent) {
        buildAnnotatedString {
            spans.forEach { span ->
                when (span) {
                    is HelpSpan.Text -> append(span.text)
                    is HelpSpan.Bold -> withStyle(SpanStyle(color = accent)) { append(span.text) }
                    is HelpSpan.Link -> {
                        pushStringAnnotation(TAG_URL, span.url)
                        withStyle(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)) { append(span.label) }
                        pop()
                    }
                    is HelpSpan.Email -> {
                        pushStringAnnotation(TAG_EMAIL, span.address)
                        withStyle(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)) { append(span.address) }
                        pop()
                    }
                }
            }
        }
    }
    ClickableText(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
        onClick = { offset -> handleClick(annotated, offset, context) }
    )
}

private const val TAG_URL = "url"
private const val TAG_EMAIL = "email"

private fun handleClick(text: AnnotatedString, offset: Int, context: Context) {
    text.getStringAnnotations(TAG_EMAIL, offset, offset).firstOrNull()?.let { emailSupport(context, it.item); return }
    text.getStringAnnotations(TAG_URL, offset, offset).firstOrNull()?.let { openLink(context, it.item) }
}

private fun openLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app found to open $url", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Opens a new message to support with the phone model filled in (the player can edit or delete it before sending; GOA
 * sends nothing itself). With no email app installed the address is copied instead.
 */
fun emailSupport(context: Context, address: String = SUPPORT_EMAIL) {
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
        putExtra(Intent.EXTRA_EMAIL, arrayOf(address))
        putExtra(Intent.EXTRA_SUBJECT, "GOA support")
        putExtra(Intent.EXTRA_TEXT, "Phone: ${Build.MANUFACTURER} ${Build.MODEL}\nGame: \n\n")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Support email", address))
        Toast.makeText(context, "No email app found. Copied $address", Toast.LENGTH_LONG).show()
    }
}
