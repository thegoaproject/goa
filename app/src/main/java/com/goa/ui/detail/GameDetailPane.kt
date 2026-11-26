package com.goa.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.goa.data.api.toSecureGogImageUrl
import com.goa.data.db.GameEntity
import com.goa.data.db.GameExtraEntity
import com.goa.data.db.InstallStatus
import com.goa.ui.theme.RetroAmber
import com.goa.ui.theme.RetroGreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameDetailPane(
    game: GameEntity,
    extras: List<GameExtraEntity>,
    matchedPatches: List<com.goa.data.repository.GamePatchProfile> = emptyList(),
    downloadProgressPercent: Int,
    downloadingExtraIds: Set<String> = emptySet(),
    onBack: () -> Unit,
    onInstallClick: () -> Unit,
    /** "Install (1 of 2 free)" etc.; see com.goa.ui.license.installLabel */
    installLabel: String = "Install Game",
    onCancelInstallClick: () -> Unit,
    onPlayClick: () -> Unit,
    /** The game is being prepared and started: PLAY shows progress and ignores taps. */
    launching: Boolean = false,
    /** Own game locked after a refunded registration: the button offers registration instead of playing. */
    playLocked: Boolean = false,
    /** Besides the game: its setup program and extras (GOG's launcher menu, or SETUP.EXE beside the game). */
    launchOptions: List<com.goa.domain.usecase.LaunchOption> = emptyList(),
    onLaunchOption: (com.goa.domain.usecase.LaunchTarget) -> Unit = {},
    onUninstallClick: () -> Unit,
    onOpenExtra: (GameExtraEntity) -> Unit,
    onDownloadExtra: (GameExtraEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf("Overview", "Video", "Controls", "Extras (${extras.size})")

    // First game launch: ask once about opt-in sharing (FR-008); any answer, or dismissing, continues to the game
    val consentApp = LocalContext.current.applicationContext as? com.goa.GoaApplication
    val consentRepository = remember(consentApp) { consentApp?.let { com.goa.telemetry.ConsentRepository(it, it.database.telemetryDao()) } }
    val consentScope = androidx.compose.runtime.rememberCoroutineScope()
    var showConsent by remember { androidx.compose.runtime.mutableStateOf(false) }
    var showPatchDetails by remember(game.id) { androidx.compose.runtime.mutableStateOf(false) }
    if (showPatchDetails) PatchDetailsSheet(profiles = matchedPatches, onDismiss = { showPatchDetails = false })

    val playWithConsent: () -> Unit = {
        if (consentRepository?.current() == com.goa.telemetry.ConsentState.UNASKED) showConsent = true else onPlayClick()
    }
    if (showConsent) {
        com.goa.ui.settings.ConsentSheet { decision ->
            showConsent = false
            consentScope.launch {
                consentRepository?.set(decision ?: com.goa.telemetry.ConsentState.DECLINED)
                onPlayClick()
            }
        }
    }
    val customControls = com.goa.engine.input.GamepadPreferences.hasUserCustomizedControls(LocalContext.current, game.id)
    val autoTuneSummary = remember(matchedPatches, customControls) { buildAutoTuneSummary(matchedPatches, customControls) }

    val phoneLandscape = com.goa.ui.catalog.isPhoneLandscape()

    // Hero banner & metadata header
    val heroHeader: @Composable (Modifier) -> Unit = { heroModifier ->
            Box(modifier = heroModifier.background(Color.Black)) {
                AsyncImage(
                    model = (game.bannerUrl ?: game.boxArtUrl).toSecureGogImageUrl(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color(0xEE100D09)),
                                startY = 100f
                            )
                        )
                )

                // Header Info overlay
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(16.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    // Box art thumb
                    Surface(
                        modifier = Modifier
                            .width(80.dp)
                            .aspectRatio(0.75f)
                            .clip(MaterialTheme.shapes.medium),
                        shadowElevation = 6.dp
                    ) {
                        com.goa.ui.catalog.GameArt(url = game.boxArtUrl, title = game.title, modifier = Modifier.fillMaxSize())
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(bottom = 4.dp)
                        ) {
                            Surface(
                                shape = MaterialTheme.shapes.extraSmall,
                                color = RetroAmber
                            ) {
                                Text(
                                    text = "MS-DOS NATIVE",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            if (autoTuneSummary != null) {
                                AutoTunedBadge(confident = autoTuneSummary.isConfident)
                            }
                        }

                        Text(
                            text = game.title,
                            // The one place outside the text dialogs that uses the retro face (see ui/theme/Type.kt)
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontFamily = com.goa.ui.theme.GoaFontFamily,
                                fontSynthesis = androidx.compose.ui.text.font.FontSynthesis.None
                            ),
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )

                        Text(
                            text = if (game.downloadSize > 0L) "${formatSize(game.downloadSize)} • Zero Config" else "Bundle / Parent Entry",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFCCCCCC)
                        )
                    }
                }
            }
    }

    // Install / Play and the game's other programs
    val actionBar: @Composable () -> Unit = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (game.installStatus) {
                        InstallStatus.NOT_INSTALLED -> {
                            Button(
                                onClick = onInstallClick,
                                enabled = game.downloadSize > 0L,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                shape = MaterialTheme.shapes.medium
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                com.goa.ui.menu.OneLineLabel(if (game.downloadSize > 0L) installLabel else "Bundle / Sub-Edition Only")
                            }
                        }
                        InstallStatus.DOWNLOADING -> {
                            Button(
                                onClick = { },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                shape = MaterialTheme.shapes.medium
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                com.goa.ui.menu.OneLineLabel("Downloading ($downloadProgressPercent%)")
                            }

                            OutlinedButton(
                                onClick = onCancelInstallClick,
                                shape = MaterialTheme.shapes.medium
                            ) {
                                com.goa.ui.menu.OneLineLabel("Cancel")
                            }
                        }
                        InstallStatus.EXTRACTING -> {
                            Button(
                                onClick = onInstallClick,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                shape = MaterialTheme.shapes.medium
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                com.goa.ui.menu.OneLineLabel("Extracting DOS Assets (Retry)")
                            }

                            OutlinedButton(
                                onClick = onCancelInstallClick,
                                shape = MaterialTheme.shapes.medium
                            ) {
                                com.goa.ui.menu.OneLineLabel("Cancel")
                            }
                        }
                        InstallStatus.READY_TO_PLAY -> {
                            Button(
                                onClick = { if (!launching) playWithConsent() },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp),
                                shape = MaterialTheme.shapes.medium,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.tertiary,
                                    contentColor = MaterialTheme.colorScheme.onTertiary
                                )
                            ) {
                                if (launching) {
                                    androidx.compose.material3.CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 3.dp,
                                        color = MaterialTheme.colorScheme.onTertiary
                                    )
                                } else {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(28.dp))
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                com.goa.ui.menu.OneLineLabel(
                                    text = if (launching) "LAUNCHING…" else if (playLocked) "UNLOCK PRO TO PLAY" else "PLAY",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Black
                                )
                            }
                            if (!playLocked) LaunchOptionButtons(launchOptions, enabled = !launching, onLaunch = onLaunchOption)
                        }
                        InstallStatus.ERROR, InstallStatus.DOWNLOAD_PAUSED -> {
                            Button(
                                onClick = onInstallClick,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                shape = MaterialTheme.shapes.medium
                            ) {
                                com.goa.ui.menu.OneLineLabel("Resume / Retry Install")
                            }
                        }
                    }
                }
            }
    }

    // Overview, Video, Controls, Extras
    val tabSection: @Composable () -> Unit = {
            // Scrollable so the labels never wrap ("Overvie / w") on a narrow detail pane or with a large system font
            val tabItems: @Composable () -> Unit = {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = { Text(title, maxLines = 1, softWrap = false) }
                    )
                }
            }
            // Phone landscape: the tab panel is wide enough for fixed tabs that fill it
            if (phoneLandscape) {
                androidx.compose.material3.PrimaryTabRow(selectedTabIndex = selectedTabIndex) { tabItems() }
            } else {
                androidx.compose.material3.PrimaryScrollableTabRow(selectedTabIndex = selectedTabIndex, edgePadding = 0.dp) { tabItems() }
            }

            // Tab Content
            when (selectedTabIndex) {
                0 -> {
                    // Overview Tab
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        if (autoTuneSummary != null) {
                            // Keyed so switching games in two-pane mode starts a fresh card instead of
                            // animating the previous game's rows out
                            key(game.id) {
                                AutoTuneShowcaseCard(
                                    summary = autoTuneSummary,
                                    gameId = game.id,
                                    onViewControls = { selectedTabIndex = 2 },
                                    onRowClick = { showPatchDetails = true }
                                )
                            }
                            Spacer(modifier = Modifier.height(24.dp))
                        }

                        Text(
                            text = "About",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = formatCleanDescription(game.description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Other versions of a game found in a scanned folder (revisions, other publishers): pick which one to install
                        if (game.installStatus == com.goa.data.db.InstallStatus.NOT_INSTALLED && game.variantCount > 1) {
                            Spacer(modifier = Modifier.height(24.dp))
                            OtherVersions(gameId = game.id)
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        Text(
                            text = "Emulation Configuration",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "• Core: DOSBox Native Engine (48 kHz Pure Audio)\n• Sound: Auto-detected (Sound Blaster 16 / OPL3)\n• Scaling: Snapdragon GSR / Pixel-Perfect 4:3 OLED\n• Input: Xbox / Gamepad & Touch HUD",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (matchedPatches.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(24.dp))
                            Text(
                                text = "Active Game Patches (${matchedPatches.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            matchedPatches.forEach { patch ->
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            text = patch.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = RetroGreen
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = patch.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                1 -> {
                    // Video Settings Tab
                    VideoSettingsTab(gameId = game.id)
                }
                2 -> {
                    // Controls Tab
                    ControlsTab(
                        isGamepadConnected = rememberGamepadConnected(),
                        gamepadName = rememberGamepadName(),
                        gameId = game.id,
                        gameTitle = game.title,
                        gameSlug = game.slug,
                        installPath = game.localInstallPath
                    )
                }
                3 -> {
                    // Extras Tab
                    ExtrasTab(
                        extras = extras,
                        downloadingExtraIds = downloadingExtraIds,
                        onOpenExtra = onOpenExtra,
                        onDownloadExtra = onDownloadExtra
                    )
                }
            }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(game.title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (game.installStatus == InstallStatus.READY_TO_PLAY) {
                        IconButton(onClick = onUninstallClick) {
                            Icon(Icons.Default.Delete, contentDescription = "Uninstall")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        if (phoneLandscape) {
            // Phone on its side: the game's art and Install / Play on the left, its tabs on the right
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                Column(
                    modifier = Modifier
                        .weight(0.42f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                ) {
                    heroHeader(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                    actionBar()
                }
                androidx.compose.material3.VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Column(
                    modifier = Modifier
                        .weight(0.58f)
                        .fillMaxHeight()
                ) { tabSection() }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                heroHeader(Modifier.fillMaxWidth().height(200.dp))
                actionBar()
                tabSection()
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val mb = bytes.toDouble() / (1024 * 1024)
    return if (mb >= 1024) {
        String.format("%.1f GB", mb / 1024)
    } else {
        String.format("%.0f MB", mb)
    }
}

fun formatCleanDescription(raw: String): String {
    if (raw.isBlank()) return "Classic retro MS-DOS game from GOG library."

    var text = raw
        .replace("\\n", "\n")
        .replace("\\r", "")
        .replace("\\t", " ")

    // Strip cross-promotional store purchase announcements prepended by GOG
    text = text.replace(Regex("""(?i)<b>\s*When you buy this game.*?</b>\s*(<br\s*/?>|\n)*"""), "")
    text = text.replace(Regex("""(?i)When you buy this game,.*?(<br\s*/?>|\n)*"""), "")

    // Convert HTML structure to plain-text formatting
    text = text.replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
    text = text.replace(Regex("""</p>""", RegexOption.IGNORE_CASE), "\n\n")
    text = text.replace(Regex("""<li[^>]*>""", RegexOption.IGNORE_CASE), "• ")
    text = text.replace(Regex("""<[^>]+>"""), "")

    // Decode HTML entities
    text = android.text.Html.fromHtml(text, android.text.Html.FROM_HTML_MODE_LEGACY).toString()

    // Normalize multiple consecutive line breaks
    text = text.replace(Regex("""\n{3,}"""), "\n\n").trim()

    return text.ifBlank { "Classic retro MS-DOS game from GOG library." }
}

/** The versions of a folder game that is not installed yet, with a button to install a different one than the best-ranked. */
@Composable
private fun OtherVersions(gameId: Long) {
    val app = LocalContext.current.applicationContext as? com.goa.GoaApplication ?: return
    val variants by remember(gameId) { app.database.gameDao().variantsFor(gameId) }.collectAsState(initial = emptyList())
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Text(
        text = "Other versions (${variants.size})",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = "Several files for this game were found. Installing uses the selected one.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(8.dp))
    variants.forEach { v ->
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
                Text(v.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                Text(
                    "${v.sizeBytes / (1024 * 1024)} MB" + if (v.isRev) " · revised" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (v.isPrimary) {
                Text("Selected", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            } else {
                androidx.compose.material3.TextButton(onClick = {
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) { app.folderRepository.useVersion(gameId, v.id) }
                }) { com.goa.ui.menu.OneLineLabel("Use this version") }
            }
        }
    }
}

/**
 * Setup beside Play, and a ⋮ menu for other programs the game ships (SimCity 2000's Urban Renewal Kit, Red Baron's
 * Mission Builder). Each starts that program directly: no DOS menu, no keyboard.
 */
@Composable
private fun androidx.compose.foundation.layout.RowScope.LaunchOptionButtons(
    options: List<com.goa.domain.usecase.LaunchOption>,
    enabled: Boolean,
    onLaunch: (com.goa.domain.usecase.LaunchTarget) -> Unit
) {
    val setup = options.firstOrNull { it.kind == com.goa.domain.usecase.LaunchOption.Kind.SETUP }
    val more = options.filter { it != setup }
    if (setup != null) {
        var confirmSetup by remember { androidx.compose.runtime.mutableStateOf(false) }
        if (confirmSetup) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { confirmSetup = false },
                icon = { Icon(Icons.Default.Tune, contentDescription = null) },
                title = { Text("Enter setup?") },
                text = {
                    Text("This game should be preconfigured with optimal settings. Only enter setup if something is not working.")
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { confirmSetup = false; onLaunch(setup.target) }) {
                        com.goa.ui.menu.OneLineLabel("Enter setup")
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { confirmSetup = false }) { com.goa.ui.menu.OneLineLabel("Cancel") }
                }
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        OutlinedButton(
            onClick = { confirmSetup = true },
            enabled = enabled,
            modifier = Modifier.height(52.dp),
            shape = MaterialTheme.shapes.medium,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp)
        ) {
            Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(6.dp))
            com.goa.ui.menu.OneLineLabel("SETUP", fontWeight = FontWeight.Bold)
        }
    }
    if (more.isNotEmpty()) {
        var open by remember { androidx.compose.runtime.mutableStateOf(false) }
        Box {
            IconButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.height(52.dp)) {
                Icon(Icons.Default.MoreVert, contentDescription = "More programs")
            }
            androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                more.forEach { option ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { com.goa.ui.menu.OneLineLabel(option.name) },
                        onClick = { open = false; onLaunch(option.target) }
                    )
                }
            }
        }
    }
}
