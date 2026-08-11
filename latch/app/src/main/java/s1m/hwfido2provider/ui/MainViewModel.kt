package s1m.hwfido2provider.ui

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModel
import s1m.hwfido2provider.Store

class MainViewModel(state: MainState, val context: () -> Context?) : ViewModel() {
    var state by mutableStateOf(state)

    fun openSettings() {
        runCatching {
            context()?.let {
                CredentialManager.create(it)
                    .createSettingsPendingIntent()
                    .send()
            }
        }.getOrNull() ?: run {
            Log.d(TAG, "Impossible to open settings")
        }
    }

    fun refresh() {
        context()?.let {
            state = MainState.from(it)
        }
    }

    fun toggleNfcEntry() {
        val newState = !state.entryNfc
        state = state.copy(entryNfc = newState)
        context()?.let {
            Store(it).entryNfc = newState
        }
    }

    fun toggleUsbEntry() {
        val newState = !state.entryUsb
        state = state.copy(entryUsb = newState)
        context()?.let {
            Store(it).entryUsb = newState
        }
    }

    fun toggleHybridEntry() {
        val newState = !state.entryHybrid
        state = state.copy(entryHybrid = newState)
        context()?.let {
            Store(it).entryHybrid = newState
        }
    }

    companion object {
        private val TAG = MainViewModel::class.java.simpleName
        fun preview(): MainViewModel = MainViewModel(MainState.preview(), { null })
    }
}
