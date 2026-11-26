package com.goa.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import com.goa.ui.auth.AuthWelcomeScreen
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldDestinationItem
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.content.Intent
import androidx.core.content.FileProvider
import com.goa.GoaApplication
import com.goa.domain.usecase.ExtraContent
import com.goa.ui.catalog.LibraryCatalogPane
import com.goa.ui.catalog.LibraryViewModel
import com.goa.ui.detail.GameDetailPane
import com.goa.ui.extras.ImageViewerScreen
import com.goa.ui.extras.PdfManualViewerScreen
import com.goa.ui.extras.SoundtrackPlayerScreen
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import com.goa.ui.menu.AccountPrivacyPage
import com.goa.ui.menu.AudioPage
import com.goa.ui.menu.HelpPage
import com.goa.ui.menu.LibraryMenuPanel
import com.goa.ui.menu.LicensesPage
import com.goa.ui.menu.MenuPage
import com.goa.ui.menu.StoragePage
import com.goa.ui.menu.DriveBackupPage
import com.goa.ui.menu.DriveBackupViewModel
import com.goa.ui.menu.StorageViewModel
import com.goa.ui.menu.VideoPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import kotlinx.coroutines.withContext

import com.goa.ui.navigation.LauncherGamepadAction
import com.goa.ui.navigation.LauncherGamepadController
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun AdaptiveAppRoot(
    libraryViewModel: LibraryViewModel,
    app: GoaApplication,
    gamepadController: LauncherGamepadController? = null,
    isDarkTheme: Boolean = false,
    onToggleTheme: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // A phone on its side is wide enough for two panes by width alone, but far too short for them to be useful: it gets
    // one pane (the library grid, then a game full screen), like a phone held upright
    val phoneLandscape = com.goa.ui.catalog.isPhoneLandscape()
    val scaffoldDirective = calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth(
        currentWindowAdaptiveInfo()
    ).let { if (phoneLandscape) it.copy(maxHorizontalPartitions = 1) else it }
    val navigator = rememberListDetailPaneScaffoldNavigator<Long>(
        scaffoldDirective = scaffoldDirective,
        initialDestinationHistory = listOf(ThreePaneScaffoldDestinationItem(ListDetailPaneScaffoldRole.List))
    )
    // Back from a game always lands on the library. material3-adaptive 1.0.0's navigateBack() clears the whole history
    // when it finds no earlier destination with a different layout, and an empty history shows the empty detail pane
    // ("Select an MS-DOS title...") on a phone, with the next Back leaving the app.
    // Only a single-pane layout (phone) hides the library behind a game; with two panes both are on screen and Back
    // keeps its normal meaning (leave GOA)
    val detailCoversLibrary = scaffoldDirective.maxHorizontalPartitions == 1 &&
        navigator.currentDestination?.pane == ListDetailPaneScaffoldRole.Detail
    val backToLibrary: () -> Unit = {
        if (navigator.canNavigateBack()) navigator.navigateBack()
        val current = navigator.currentDestination
        if (current == null || (current.pane == ListDetailPaneScaffoldRole.Detail && current.content == null)) {
            navigator.navigateTo(ListDetailPaneScaffoldRole.List)
        }
    }
    val catalogListState = rememberLazyListState()
    val catalogGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val uiState by libraryViewModel.uiState.collectAsState()
    var viewingPdf by remember { mutableStateOf<Pair<String, String>?>(null) }
    var activeSoundtrack by remember { mutableStateOf<Pair<String, List<File>>?>(null) }
    var viewingImages by remember { mutableStateOf<Pair<String, List<File>>?>(null) }
    // The ☰ menu, and the page it opened (null: the library). There is no Settings screen; the menu holds it all.
    var isMenuOpen by remember { mutableStateOf(false) }
    var menuPage by remember { mutableStateOf<MenuPage?>(null) }
    var menuAnchor by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var isConnectingGog by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // Unlock GOA Pro: shown when an install or import needs a free game slot
    var showRegister by remember { mutableStateOf(false) }
    val registered by app.licenseManager.registered.collectAsState()
    val occupiedSlots by app.licenseManager.occupiedSlots.collectAsState(initial = emptySet())
    val lockedGames by app.licenseManager.lockedGames.collectAsState(initial = emptySet())
    // Preparing a launch (patching, config) takes seconds before the emulator appears: show it and ignore repeat taps
    var launchingGameId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(launchingGameId) {
        if (launchingGameId != null) {
            kotlinx.coroutines.delay(30_000) // safety net if the game never takes over the screen
            launchingGameId = null
        }
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        // The game covered GOA and the player came back: the launch is over
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START) launchingGameId = null
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val launchTarget: (Long, com.goa.domain.usecase.LaunchTarget) -> Unit = { gameId, target ->
        if (launchingGameId == null) {
            launchingGameId = gameId
            coroutineScope.launch(Dispatchers.IO) {
                app.launchGameUseCase.launchGame(gameId, target).onFailure { error ->
                    launchingGameId = null
                    if (error is com.goa.domain.license.RegistrationRequiredException) {
                        showRegister = true
                    } else {
                        // Without this PLAY just did nothing (an unreadable game folder, a missing file)
                        android.util.Log.e("GOA", "Failed to launch game $gameId", error)
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            val text = when (error) {
                                is java.io.IOException, is SecurityException ->
                                    "Couldn't read this game's files. Uninstall it and install it again."
                                else -> "Couldn't start the game."
                            }
                            android.widget.Toast.makeText(app, text, android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }
    val playGame: (Long) -> Unit = { gameId -> launchTarget(gameId, com.goa.domain.usecase.LaunchTarget.Play) }
    val startInstall: (Long, String) -> Unit = { gameId, title ->
        coroutineScope.launch {
            app.installGameUseCase.startInstall(gameId).onFailure { error ->
                if (error is com.goa.domain.license.RegistrationRequiredException) {
                    showRegister = true
                } else {
                    android.util.Log.e("GOA", "Failed to start install for $title", error)
                    android.widget.Toast.makeText(app, error.message ?: "Failed to start install", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val hiddenHints by libraryViewModel.hiddenHints.collectAsState()

    // Add Folder: the system folder picker. Access is kept (and the scan started) by the view model.
    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? -> if (uri != null) libraryViewModel.addFolder(uri) }

    // One-line news for the player: a folder that was already added, an install that couldn't start or finish
    androidx.compose.runtime.LaunchedEffect(libraryViewModel, app) {
        launch {
            libraryViewModel.events.collect { android.widget.Toast.makeText(app, it, android.widget.Toast.LENGTH_LONG).show() }
        }
        app.installGameUseCase.messages.collect { android.widget.Toast.makeText(app, it, android.widget.Toast.LENGTH_LONG).show() }
    }

    if (showRegister) {
        com.goa.ui.license.RegisterDialog(onDismiss = { showRegister = false })
    }

    if (isConnectingGog) {
        AuthWelcomeScreen(
            onAuthSuccess = {
                isConnectingGog = false
                libraryViewModel.syncLibrary()
            },
            onExchangeCode = { code ->
                app.authRepository.handleAuthCode(code).map { }
            },
            onDismiss = {
                isConnectingGog = false
            }
        )
        return
    }

    if (viewingPdf != null) {
        PdfManualViewerScreen(
            pdfFilePath = viewingPdf!!.first,
            title = viewingPdf!!.second,
            onBack = { viewingPdf = null }
        )
        return
    }

    if (activeSoundtrack != null) {
        SoundtrackPlayerScreen(
            title = activeSoundtrack!!.first,
            tracks = activeSoundtrack!!.second,
            onBack = { activeSoundtrack = null }
        )
        return
    }

    if (viewingImages != null) {
        ImageViewerScreen(
            title = viewingImages!!.first,
            images = viewingImages!!.second,
            onBack = { viewingImages = null }
        )
        return
    }

    // Controller: while the menu or one of its pages shows, the D-pad and A move and press the focused control
    // (MainActivity), and only Back and Menu come through as actions, handled here.
    LaunchedEffect(gamepadController, isMenuOpen, menuPage) {
        gamepadController?.uiFocusMode = isMenuOpen || menuPage != null
    }
    // Opening the menu takes focus (and the keyboard) away from the library's search box
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(isMenuOpen) {
        if (isMenuOpen) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }
    androidx.compose.runtime.DisposableEffect(gamepadController) {
        onDispose { gamepadController?.uiFocusMode = false }
    }
    if (gamepadController != null) {
        LaunchedEffect(gamepadController) {
            gamepadController.actions.collect { action ->
                if (!isMenuOpen && menuPage == null) return@collect // the library's own handler has it
                when (action) {
                    LauncherGamepadAction.Back -> if (isMenuOpen) isMenuOpen = false else menuPage = null
                    LauncherGamepadAction.Menu -> if (isMenuOpen) {
                        isMenuOpen = false
                    } else {
                        menuPage = null
                        isMenuOpen = true
                    }
                    else -> {}
                }
            }
        }
    }

    // A menu page covers the library. The library's scroll position (catalogListState), filters (view model) and
    // selected game (navigator) all live above this return, so Back finds the library exactly as it was.
    menuPage?.let { page ->
        val closePage = { menuPage = null }
        when (page) {
            MenuPage.ACCOUNT_PRIVACY -> AccountPrivacyPage(
                userSession = uiState.userSession,
                onBack = closePage,
                onSignIn = {
                    menuPage = null
                    isConnectingGog = true
                },
                onSignOut = { libraryViewModel.logout() }
            )
            MenuPage.VIDEO -> VideoPage(onBack = closePage)
            MenuPage.AUDIO -> AudioPage(onBack = closePage)
            MenuPage.STORAGE -> {
                val storageViewModel = androidx.lifecycle.viewmodel.compose.viewModel {
                    StorageViewModel(
                        repository = com.goa.domain.storage.StorageUsageRepository(app.storageManager, app.filesDir) {
                            app.database.gameDao().getActiveInstalls()
                        },
                        uninstallGame = { id -> app.installGameUseCase.uninstallGame(id) },
                        lockedGames = app.licenseManager.lockedGames,
                        saveStates = app.driveSaveStates
                    )
                }
                StoragePage(viewModel = storageViewModel, onBack = closePage, watchedFolders = {
                    val folders by app.folderRepository.folders.collectAsState()
                    val scanState by app.folderRepository.scanState.collectAsState()
                    com.goa.ui.menu.WatchedFoldersSection(
                        folders = folders,
                        scanState = scanState,
                        onAdd = { folderLauncher.launch(null) },
                        onReconnect = { folderLauncher.launch(Uri.parse(it.treeUri)) },
                        onRemove = { libraryViewModel.removeFolder(it.id) },
                        onRescan = { libraryViewModel.rescanFolders() }
                    )
                }, saveStatesFooter = {
                    val driveAccount by app.driveAuth.account.collectAsState()
                    val access = com.goa.ui.savestate.rememberDriveAccess(app.driveAuth) { text ->
                        android.widget.Toast.makeText(app, text, android.widget.Toast.LENGTH_LONG).show()
                    }
                    when {
                        !com.goa.data.drive.DriveBackup.enabled -> com.goa.ui.menu.SaveStatesFooter.None
                        !registered -> com.goa.ui.menu.SaveStatesFooter.NotPro { showRegister = true }
                        driveAccount == null -> com.goa.ui.menu.SaveStatesFooter.NoAccount {
                            access.changeAccount { storageViewModel.refreshSaveStates() }
                        }
                        else -> com.goa.ui.menu.SaveStatesFooter.Linked(driveAccount!!) { menuPage = MenuPage.DRIVE_BACKUP }
                    }
                })
            }
            MenuPage.DRIVE_BACKUP -> {
                val driveViewModel = androidx.lifecycle.viewmodel.compose.viewModel {
                    DriveBackupViewModel(app.driveAuth, app.driveSaveStates, app.licenseManager.registered)
                }
                DriveBackupPage(viewModel = driveViewModel, onBack = closePage)
            }
            MenuPage.HELP -> HelpPage(onBack = closePage)
            MenuPage.LICENSES -> LicensesPage(onBack = closePage)
        }
        return
    }

    BackHandler(enabled = isMenuOpen) { isMenuOpen = false }

    BackHandler(enabled = navigator.canNavigateBack() || detailCoversLibrary) {
        backToLibrary()
    }

    val selectedGameId = navigator.currentDestination?.content

    // Handle physical controller / gamepad actions in the launcher
    if (gamepadController != null) {
        LaunchedEffect(gamepadController, uiState.sections, selectedGameId) {
            gamepadController.actions.collectLatest { action ->
                if (isMenuOpen || menuPage != null) return@collectLatest // the menu's handler above has it
                // Up/down step through expanded sections only; lookups use every shown game
                val nav = uiState.navigableGames
                val games = uiState.filteredGames
                val currentIndex = nav.indexOfFirst { it.id == selectedGameId }

                when (action) {
                    LauncherGamepadAction.NavigateDown -> {
                        if (nav.isNotEmpty()) {
                            val nextIndex = if (currentIndex < 0) 0 else (currentIndex + 1).coerceAtMost(nav.lastIndex)
                            val targetGame = nav[nextIndex]
                            navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, targetGame.id)
                        }
                    }
                    LauncherGamepadAction.NavigateUp -> {
                        if (nav.isNotEmpty()) {
                            val prevIndex = if (currentIndex < 0) 0 else (currentIndex - 1).coerceAtLeast(0)
                            val targetGame = nav[prevIndex]
                            navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, targetGame.id)
                        }
                    }
                    LauncherGamepadAction.Select -> {
                        // Button A: Select / Open Detail or if in detail pane and installed, launch game
                        if (selectedGameId != null) {
                            val game = games.find { it.id == selectedGameId }
                            if (game?.installStatus == com.goa.data.db.InstallStatus.READY_TO_PLAY) {
                                playGame(game.id)
                            } else if (game?.installStatus == com.goa.data.db.InstallStatus.NOT_INSTALLED) {
                                startInstall(game.id, game.title)
                            } else {
                                navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, selectedGameId)
                            }
                        } else if (nav.isNotEmpty()) {
                            navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, nav[0].id)
                        }
                    }
                    LauncherGamepadAction.Back -> {
                        // Button B: Navigate back or close modals
                        when {
                            isConnectingGog -> isConnectingGog = false
                            viewingPdf != null -> viewingPdf = null
                            activeSoundtrack != null -> activeSoundtrack = null
                            viewingImages != null -> viewingImages = null
                            navigator.canNavigateBack() || detailCoversLibrary -> backToLibrary()
                        }
                    }
                    LauncherGamepadAction.PlayOrAction -> {
                        // Button X: Quick Play / Install current game
                        if (selectedGameId != null) {
                            val game = games.find { it.id == selectedGameId }
                            if (game?.installStatus == com.goa.data.db.InstallStatus.READY_TO_PLAY) {
                                playGame(game.id)
                            } else if (game?.installStatus == com.goa.data.db.InstallStatus.NOT_INSTALLED) {
                                startInstall(game.id, game.title)
                            }
                        }
                    }
                    LauncherGamepadAction.Menu -> {
                        // Button Y / Start: open the ☰ menu
                        isMenuOpen = true
                    }
                    else -> {}
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    // While the menu is open the library behind it is never a focus target (a quick D-pad press would otherwise
    // land on the search box and bring up the keyboard)
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    val libraryFocus = Modifier
        .focusProperties { enter = { if (isMenuOpen) androidx.compose.ui.focus.FocusRequester.Cancel else androidx.compose.ui.focus.FocusRequester.Default } }
        .focusGroup()
    Box(modifier = libraryFocus) {
    SharedTransitionLayout {
        ListDetailPaneScaffold(
            directive = navigator.scaffoldDirective,
            value = navigator.scaffoldValue,
            modifier = modifier.fillMaxSize(),
            listPane = {
                AnimatedPane {
                    LibraryCatalogPane(
                        uiState = uiState,
                        selectedGameId = selectedGameId,
                        isDarkTheme = isDarkTheme,
                        listState = catalogListState,
                        gridState = catalogGridState,
                        onToggleTheme = onToggleTheme,
                        onGameClick = { gameId ->
                            navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, gameId)
                        },
                        onSearchQueryChange = { query ->
                            libraryViewModel.onSearchQueryChange(query)
                        },
                        onFilterChange = { filter ->
                            libraryViewModel.onFilterChange(filter)
                        },
                        onSortChange = { sort ->
                            libraryViewModel.onSortChange(sort)
                        },
                        onOpenMenu = { isMenuOpen = true },
                        onMenuIconPlaced = { menuAnchor = it },
                        onConnectGog = {
                            isConnectingGog = true
                        },
                        onAddFolder = { initial -> folderLauncher.launch(initial?.let { Uri.parse(it) }) },
                        onRescanFolders = { libraryViewModel.rescanFolders() },
                        onRemoveFolder = { id -> libraryViewModel.removeFolder(id) },
                        hiddenHints = hiddenHints,
                        onHideHint = libraryViewModel::hideHint,
                        onToggleSource = { source -> libraryViewModel.toggleSource(source) },
                        onToggleSection = { id -> libraryViewModel.toggleSection(id) },
                        registered = registered,
                        ownInstalls = occupiedSlots.size,
                        onUpgrade = { showRegister = true },
                        lockedGameIds = lockedGames,
                        onSyncClick = {
                            if (uiState.userSession == null) {
                                isConnectingGog = true
                            } else {
                                libraryViewModel.syncLibrary(force = true)
                            }
                        }
                    )
                }
            },
            detailPane = {
                AnimatedPane {
                    if (selectedGameId != null) {
                        val liveGame by app.database.gameDao().getGameById(selectedGameId).collectAsState(initial = null)
                        val game = liveGame ?: uiState.filteredGames.find { it.id == selectedGameId }
                        val extras by app.database.gameDao().getExtrasForGame(selectedGameId).collectAsState(initial = emptyList())
                        val downloadProgress by app.installGameUseCase.observeDownloadProgress(selectedGameId).collectAsState(initial = 0)
                        val downloadingExtraIds by app.downloadExtraUseCase.downloadingExtraIds.collectAsState()

                        if (game != null) {
                            // Re-match when AI fixes change, so the auto-tune card shows what is actually in effect
                            val fixesVersion by remember(game.id) { app.database.telemetryDao().fixesFlow(game.id) }
                                .collectAsState(initial = emptyList())
                            val profilesVersion by com.goa.data.repository.GamePatchRepository.profilesVersion.collectAsState()
                            val matchedPatches = remember(game.id, game.slug, game.localInstallPath, fixesVersion.map { it.id to it.status }, profilesVersion) {
                                val dir = game.localInstallPath?.let { File(it) } ?: File("")
                                app.patchRepository.findMatchingProfiles(game.id, game.slug, dir)
                            }
                            // Setup and other programs the game ships, read from its conf (GOG's menu) or its folder
                            val launchOptions by androidx.compose.runtime.produceState(
                                emptyList<com.goa.domain.usecase.LaunchOption>(), game.id, game.localInstallPath, game.installStatus, game.mainExecutable
                            ) {
                                value = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val dir = File(game.localInstallPath ?: return@runCatching emptyList())
                                        if (game.installStatus != com.goa.data.db.InstallStatus.READY_TO_PLAY ||
                                            com.goa.domain.scummvm.ScummVmGameDetector.detect(dir) != null
                                        ) return@runCatching emptyList()
                                        val conf = File(dir, "dosbox.conf").takeIf { it.isFile }?.readText()
                                        com.goa.domain.usecase.LauncherMenu.options(conf, dir, game.mainExecutable)
                                    }.getOrDefault(emptyList())
                                }
                            }
                            GameDetailPane(
                                game = game,
                                extras = extras,
                                launchOptions = launchOptions,
                                onLaunchOption = { target -> launchTarget(game.id, target) },
                                matchedPatches = matchedPatches,
                                downloadingExtraIds = downloadingExtraIds,
                                downloadProgressPercent = downloadProgress,
                                onBack = {
                                    backToLibrary()
                                },
                                installLabel = com.goa.ui.license.installLabel(
                                    com.goa.domain.license.GameSlots.decide(game.id, occupiedSlots, registered)
                                ),
                                onInstallClick = { startInstall(game.id, game.title) },
                                onCancelInstallClick = {
                                    coroutineScope.launch(Dispatchers.IO) {
                                        app.installGameUseCase.cancelInstall(game.id)
                                    }
                                },
                                playLocked = game.id in lockedGames,
                                launching = launchingGameId == game.id,
                                onPlayClick = { playGame(game.id) },
                                onUninstallClick = {
                                    coroutineScope.launch {
                                        if (!app.installGameUseCase.uninstallGame(game.id)) {
                                            android.widget.Toast.makeText(
                                                app, "Couldn't remove every file of ${game.title}. Try again.", android.widget.Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                },
                                onOpenExtra = { extra ->
                                    val content = app.downloadExtraUseCase.resolveExtraContent(extra)
                                    when (content) {
                                        is ExtraContent.Pdf -> {
                                            viewingPdf = Pair(content.file.absolutePath, content.title)
                                        }
                                        is ExtraContent.Audio -> {
                                            activeSoundtrack = Pair(content.title, content.tracks)
                                        }
                                        is ExtraContent.Images -> {
                                            viewingImages = Pair(content.title, content.images)
                                        }
                                        is ExtraContent.Generic -> {
                                            try {
                                                val uri = FileProvider.getUriForFile(
                                                    app,
                                                    "${app.packageName}.fileprovider",
                                                    content.file
                                                )
                                                val mime = app.contentResolver.getType(uri) ?: "*/*"
                                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                                    setDataAndType(uri, mime)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                                app.startActivity(intent)
                                            } catch (e: Exception) {
                                                android.widget.Toast.makeText(app, "No app found to open ${content.file.name}", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                        null -> {
                                            android.widget.Toast.makeText(app, "File not found. Please tap Download again.", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                onDownloadExtra = { extra ->
                                    coroutineScope.launch {
                                        val result = app.downloadExtraUseCase.downloadExtra(extra)
                                        result.onSuccess {
                                            android.widget.Toast.makeText(
                                                app,
                                                "Downloaded ${extra.name}",
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }.onFailure { error ->
                                            android.util.Log.e("GOA", "Failed to download extra ${extra.name}", error)
                                            android.widget.Toast.makeText(
                                                app,
                                                "Download failed: ${error.message}",
                                                android.widget.Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    }
                                }
                            )
                        } else {
                            EmptyDetailPlaceholder()
                        }
                    } else {
                        EmptyDetailPlaceholder()
                    }
                }
            }
        )
    }

    }

    // ☰ menu, over the library on every screen width
    LibraryMenuPanel(
        visible = isMenuOpen,
        anchor = menuAnchor,
        onSelect = { page ->
            isMenuOpen = false
            menuPage = page
        },
        onDismiss = { isMenuOpen = false }
    )
    }
}

@Composable
private fun EmptyDetailPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Select an MS-DOS title from the catalog to view details and launch.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

