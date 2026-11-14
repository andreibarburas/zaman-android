package com.brbrs.zaman.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brbrs.zaman.data.prefs.LocalModePreferences
import com.brbrs.zaman.data.repo.ExportImportRepository
import com.brbrs.zaman.data.repo.ImportResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface DataOpState {
    object Idle : DataOpState
    object InProgress : DataOpState
    data class ExportReady(val json: String) : DataOpState
    data class ImportDone(val result: ImportResult) : DataOpState
    data class Failed(val message: String) : DataOpState
}

@HiltViewModel
class DataViewModel @Inject constructor(
    private val localMode: LocalModePreferences,
    private val exportImport: ExportImportRepository,
) : ViewModel() {

    val localOnlyEnabled: StateFlow<Boolean> =
        localMode.localOnlyEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _opState = MutableStateFlow<DataOpState>(DataOpState.Idle)
    val opState: StateFlow<DataOpState> = _opState.asStateFlow()

    fun setLocalOnlyEnabled(enabled: Boolean) {
        viewModelScope.launch { localMode.setLocalOnlyEnabled(enabled) }
    }

    /** Builds the export JSON; the caller writes it to the Uri it got back from CreateDocument. */
    fun prepareExport() {
        viewModelScope.launch {
            _opState.value = DataOpState.InProgress
            _opState.value = runCatching { exportImport.exportToJson() }
                .fold(
                    onSuccess = { DataOpState.ExportReady(it) },
                    onFailure = { DataOpState.Failed(it.message ?: "Export failed") },
                )
        }
    }

    /** Called with the raw text read from the Uri the user picked via OpenDocument. */
    fun importJson(json: String) {
        viewModelScope.launch {
            _opState.value = DataOpState.InProgress
            _opState.value = runCatching { exportImport.importFromJson(json) }
                .fold(
                    onSuccess = { DataOpState.ImportDone(it) },
                    onFailure = { DataOpState.Failed(it.message ?: "Import failed — is this a Zaman backup file?") },
                )
        }
    }

    fun clearOpState() {
        _opState.value = DataOpState.Idle
    }
}
