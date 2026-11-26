package com.goa.ui.menu

/**
 * The ☰ menu's entries, in the order the menu shows them. Everything the old Settings screen held lives on one of
 * these pages; there is no Settings screen any more.
 */
enum class MenuPage(val label: String, val title: String) {
    ACCOUNT_PRIVACY("Account & Privacy", "Account & Privacy"),
    VIDEO("Video", "Video Scaling & Shaders"),
    AUDIO("Audio", "Audio"),
    STORAGE("Storage", "Storage & Cache"),
    /** GOA Pro: save states in Google Drive (specs/009). Not in GOA OSS; debug builds only until it ships. */
    DRIVE_BACKUP("Drive Backup", "Google Drive Backup"),
    HELP("Help & FAQ", "Help & FAQ"),
    LICENSES("Licenses", "Licenses");

    companion object {
        /** The pages this build shows in the ☰ menu. */
        val shown: List<MenuPage>
            get() = entries.filter { it != DRIVE_BACKUP || com.goa.data.drive.DriveBackup.enabled }
    }
}
