package com.goa.ui.menu

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.goa.data.drive.DriveAuth
import com.goa.data.drive.DriveSaveStates
import com.goa.data.drive.TransferRun
import com.goa.domain.savestate.ConflictChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** ☰ → Google Drive Backup: the account, the last runs, and export / import with their questions. */
class DriveBackupViewModel(
    val auth: DriveAuth,
    private val service: DriveSaveStates,
    val registered: StateFlow<Boolean>
) : ViewModel() {
    val account: StateFlow<String?> = auth.account
    val run: StateFlow<TransferRun?> = service.run

    private val _lastExport = MutableStateFlow(auth.lastExport())
    val lastExport: StateFlow<DriveAuth.LastRun?> = _lastExport.asStateFlow()
    private val _lastImport = MutableStateFlow(auth.lastImport())
    val lastImport: StateFlow<DriveAuth.LastRun?> = _lastImport.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        // The last-run lines follow each finished run
        viewModelScope.launch {
            service.run.collect { r ->
                if (r != null && !r.running) {
                    _lastExport.value = auth.lastExport()
                    _lastImport.value = auth.lastImport()
                }
            }
        }
    }

    fun export() {
        _message.value = null
        if (!service.export()) _message.value = "Another copy is still running."
    }

    fun import() {
        _message.value = null
        if (!service.import()) _message.value = "Another copy is still running."
    }

    fun cancel() = service.cancel()
    fun retry() = service.retryFailed()
    fun confirmImport(go: Boolean) = service.confirmImport(go)
    fun chooseInstall(game: com.goa.data.db.GameEntity?) = service.chooseInstall(game)
    fun answer(choice: ConflictChoice, applyToRemaining: Boolean) = service.answer(choice, applyToRemaining)
    fun keepNewerForAll() = service.keepNewerForAll()
    fun showMessage(text: String) { _message.value = text }

    fun unlink() {
        viewModelScope.launch {
            auth.unlink()
            service.dismiss()
            _lastExport.value = null
            _lastImport.value = null
        }
    }
}
