package com.goa

import android.app.Application
import com.goa.data.api.GogAuthClient
import com.goa.data.api.GogLibraryClient
import com.goa.data.api.SecureSessionStorage
import com.goa.data.db.GoaDatabase
import com.goa.data.repository.AuthRepository
import com.goa.data.repository.GameCatalogRepository
import com.goa.data.storage.AppStorageManager
import java.io.File
import kotlinx.coroutines.launch

class GoaApplication : Application() {

    lateinit var database: GoaDatabase
        private set

    lateinit var sessionStorage: SecureSessionStorage
        private set

    lateinit var authClient: GogAuthClient
        private set

    lateinit var libraryClient: GogLibraryClient
        private set

    lateinit var authRepository: AuthRepository
        private set

    lateinit var catalogRepository: GameCatalogRepository
        private set

    lateinit var storageManager: AppStorageManager
        private set

    lateinit var patchRepository: com.goa.data.repository.GamePatchRepository
        private set

    lateinit var extractionManager: com.goa.domain.usecase.ExtractionManager
        private set

    lateinit var installGameUseCase: com.goa.domain.usecase.InstallGameUseCase
        private set

    lateinit var launchGameUseCase: com.goa.domain.usecase.LaunchGameUseCase
        private set

    lateinit var downloadExtraUseCase: com.goa.domain.usecase.DownloadExtraUseCase
        private set

    lateinit var importLocalGameUseCase: com.goa.domain.usecase.ImportLocalGameUseCase
        private set

    lateinit var folderRepository: com.goa.data.folders.FolderRepository
        private set

    lateinit var licenseManager: com.goa.domain.license.LicenseManager
        private set

    lateinit var billingRepository: com.goa.domain.license.BillingRepository
        private set

    /** The Google account for Drive backup of save states, and the service that copies them (specs/009). */
    lateinit var driveAuth: com.goa.data.drive.DriveAuth
        private set

    lateinit var driveSaveStates: com.goa.data.drive.DriveSaveStates
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        val currentProcessName = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            packageName
        }
        if (currentProcessName.endsWith(":emulator")) {
            return
        }
        com.goa.data.prefs.DisplayAudioPreferences.resetGlobalPresetOnce(this)

        database = GoaDatabase.getInstance(this)
        sessionStorage = SecureSessionStorage(this)
        authClient = GogAuthClient()
        libraryClient = GogLibraryClient()
        authRepository = AuthRepository(authClient, sessionStorage)
        catalogRepository = GameCatalogRepository(libraryClient, authRepository, database.gameDao())
        storageManager = AppStorageManager(this)
        patchRepository = com.goa.data.repository.GamePatchRepository(this)
        extractionManager = com.goa.domain.usecase.ExtractionManager(database.gameDao(), storageManager, patchRepository)
        licenseManager = com.goa.domain.license.LicenseManager(this, database.gameDao())
        billingRepository = com.goa.domain.license.BillingRepository(this, licenseManager)
        driveAuth = com.goa.data.drive.DriveAuth(this)
        driveSaveStates = com.goa.data.drive.DriveBackup.create(
            this, driveAuth, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO),
            isPro = { licenseManager.registered.value },
            installs = { database.gameDao().getActiveInstalls() }
        )
        installGameUseCase = com.goa.domain.usecase.InstallGameUseCase(
            this, database.gameDao(), storageManager, extractionManager, licenseManager::requireSlot,
            localSourceInstaller = com.goa.domain.usecase.LocalSourceInstaller(
                storage = storageManager,
                access = com.goa.domain.usecase.ContentSourceAccess(contentResolver),
                setStatus = { id, status -> database.gameDao().updateStatus(id, status) },
                extract = { id, progress -> extractionManager.extractAndSanitize(id, progress) },
                finalize = { id -> extractionManager.finalizeInstall(id) }
            )
        )
        launchGameUseCase = com.goa.domain.usecase.LaunchGameUseCase(
            this, database.gameDao(), patchRepository, requirePlayable = licenseManager::requirePlayable
        )
        downloadExtraUseCase = com.goa.domain.usecase.DownloadExtraUseCase(
            this,
            database.gameDao(),
            storageManager,
            authRepository,
            libraryClient
        )
        importLocalGameUseCase = com.goa.domain.usecase.ImportLocalGameUseCase(
            this,
            database.gameDao(),
            storageManager,
            extractionManager,
            licenseManager::requireSlot
        )

        folderRepository = com.goa.data.folders.FolderRepository(
            this,
            com.goa.data.folders.FolderStore(
                com.goa.data.folders.PrefsTextStore(getSharedPreferences("goa_folders", MODE_PRIVATE), "folders")
            ),
            database.gameDao(),
            database
        )
        // Folders whose access the system revoked are flagged; the rest are quietly refreshed if they are a day old
        runCatching { folderRepository.validateGrants(); folderRepository.rescanIfStale() }

        // Ensure any existing unnormalized URLs in the database are upgraded immediately
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            // First, on its own: players who installed games before registration existed are registered as thanks
            runCatching { licenseManager.grandfatherExistingInstallsOnce() }
            try {
                // Populate default freeware DOS games if database is fresh
                catalogRepository.seedFreewareGamesIfEmpty()
                val db = database.openHelper.writableDatabase
                db.execSQL("UPDATE games SET boxArtUrl = 'https:' || boxArtUrl WHERE boxArtUrl LIKE '//%'")
                db.execSQL("UPDATE games SET bannerUrl = 'https:' || bannerUrl WHERE bannerUrl LIKE '//%'")
                db.execSQL("UPDATE games SET boxArtUrl = boxArtUrl || '.jpg' WHERE boxArtUrl NOT LIKE '%.jpg' AND boxArtUrl NOT LIKE '%.png' AND boxArtUrl NOT LIKE '%.webp' AND boxArtUrl NOT LIKE '%.jpeg'")
                db.execSQL("UPDATE games SET bannerUrl = bannerUrl || '.jpg' WHERE bannerUrl IS NOT NULL AND bannerUrl NOT LIKE '%.jpg' AND bannerUrl NOT LIKE '%.png' AND bannerUrl NOT LIKE '%.webp' AND bannerUrl NOT LIKE '%.jpeg'")
                db.execSQL("UPDATE games SET installStatus = 'NOT_INSTALLED', localInstallPath = NULL, mainExecutable = NULL, dosboxConfPath = NULL WHERE installStatus = 'READY_TO_PLAY' AND (mainExecutable = 'GAME.EXE' OR mainExecutable IS NULL)")
                // Drop seed titles GOA no longer offers (an existing install is left alone)
                val removedIds = com.goa.data.repository.FreewareCdn.removedGameIds.joinToString()
                db.execSQL("DELETE FROM games WHERE id IN ($removedIds) AND installStatus != 'READY_TO_PLAY'")
                db.execSQL("DELETE FROM installer_chunks WHERE gameId IN ($removedIds) AND gameId NOT IN (SELECT id FROM games)")
                db.execSQL("UPDATE games SET isDosNative = 0 WHERE title LIKE '%Enhanced%' OR title LIKE '%(2013)%' OR title LIKE '%Redux%' OR title LIKE '%RTX%' OR title LIKE '%DOOM + DOOM II%' OR title LIKE '%Shadow Warrior 2%' OR title LIKE '%Monkey Island%Special Edition%' OR title LIKE '%Settlers%4%' OR title LIKE '%Heretic + Hexen%'")
                db.execSQL("UPDATE games SET isDosNative = 1 WHERE title LIKE '%Blake Stone%' OR title LIKE '%System Shock: Classic%' OR title LIKE '%Indiana Jones%' OR title LIKE '%Theme Hospital%' OR title LIKE '%Red Baron 1%' OR title LIKE '%Red Baron Pack%' OR title LIKE '%The Last Express%' OR title LIKE '%War Wind%' OR title LIKE '%Tomb Raider 1%' OR title LIKE '%Bio Menace%' OR title LIKE '%Stargunner%' OR title LIKE '%Wacky Wheels%' OR title LIKE '%Tyrian%' OR title LIKE '%SimCity%2000%' OR title LIKE '%Jill of the Jungle%' OR title LIKE '%Jagged Alliance%' OR title LIKE '%Beneath a Steel Sky%' OR title LIKE '%Flight of the Amazon Queen%' OR title LIKE '%Lure of the Temptress%' OR title LIKE '%Dragonsphere%' OR title LIKE '%Teenagent%'")

                // The seed owns the free catalog's artwork: links that went stale on GOG's image CDN are replaced
                com.goa.data.repository.DefaultCatalogSeed.games.forEach { seed ->
                    db.execSQL("UPDATE games SET boxArtUrl = ?, bannerUrl = ? WHERE id = ?", arrayOf<Any?>(seed.boxArtUrl, seed.bannerUrl, seed.id))
                }
                
                // Remove corrupted 0-byte dummy game directories and auto-patch all installed games
                val gamesDir = File(storageManager.baseDir, "games")
                gamesDir.listFiles()?.forEach { gameDir ->
                    val gameExe = File(gameDir, "GAME.EXE")
                    if (gameExe.exists() && gameExe.length() == 0L) {
                        gameDir.deleteRecursively()
                    }
                }
                // Unpack any APK bundled games (none at the moment)
                val bundledInstaller = com.goa.domain.usecase.InstallBundledGamesUseCase(
                    this@GoaApplication,
                    database.gameDao(),
                    storageManager,
                    extractionManager
                )
                bundledInstaller.installBundledGamesIfNeeded()

                patchRepository.patchAllInstalledGames(gamesDir)
            } catch (e: Exception) {
                // Ignore if tables are not initialized yet
            }
            // Game health: ingest session journals left by the emulator process (including crashed sessions)
            runCatching { com.goa.telemetry.TelemetryIngestion.run(this@GoaApplication) }
        }
        // Restores a GOA Pro purchase after a reinstall or on a new phone
        billingRepository.refreshInBackground()
        com.goa.telemetry.JournalSweepWorker.schedule(this)
        com.goa.telemetry.TelemetryUploader.schedulePeriodic(this)
        com.goa.data.repository.PublishedDefaultsSync.schedule(this)
    }

    companion object {
        lateinit var instance: GoaApplication
            private set
    }
}

