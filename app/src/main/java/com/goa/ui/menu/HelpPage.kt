package com.goa.ui.menu

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.goa.ui.settings.HelpDocumentContent
import com.goa.ui.settings.emailSupport
import com.goa.ui.settings.rememberHelpDocument
import com.goa.ui.theme.RetroTypographyScope

/** ☰ → Help & FAQ: the website's Support page, offline, as a full page. */
@Composable
fun HelpPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val document = rememberHelpDocument()
    MenuPageScaffold(title = MenuPage.HELP.title, onBack = onBack) {
        // Retro typeface on purpose (owner request), as the help dialog had
        RetroTypographyScope { HelpDocumentContent(document) }
        OutlinedButton(
            onClick = { emailSupport(context) },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) { OneLineLabel("Email support") }
    }
}
