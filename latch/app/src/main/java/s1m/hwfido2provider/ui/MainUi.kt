package s1m.hwfido2provider.ui

import android.content.ComponentName
import android.content.Context
import android.credentials.CredentialManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import org.microg.gms.fido.core.R as microgR
import s1m.hwfido2provider.ProviderService
import s1m.hwfido2provider.R
import s1m.hwfido2provider.Store

data class MainState(
    val isProviderEnabled: Boolean,
    val entryNfc: Boolean,
    val entryUsb: Boolean,
    val entryHybrid: Boolean
) {
    companion object {
        fun preview() = MainState(
            isProviderEnabled = true,
            entryNfc = true,
            entryUsb = true,
            entryHybrid = true
        )

        fun from(context: Context): MainState {
            val componentName = ComponentName(
                context.applicationContext,
                ProviderService::class.java
            )
            val isProviderEnabled = runCatching {
                context.getSystemService(CredentialManager::class.java)
                    .isEnabledCredentialProviderService(componentName)
            }.getOrDefault(false)
            val store = Store(context)
            return MainState(
                isProviderEnabled,
                store.entryNfc,
                store.entryUsb,
                store.entryHybrid
            )
        }
    }
}

@Composable
fun MainUi(vm: MainViewModel) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val lifecycleOwner = LocalLifecycleOwner.current
            LaunchedEffect(Unit) {
                lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    vm.refresh()
                }
            }

            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier)
            if (!vm.state.isProviderEnabled) {
                Text(
                    stringResource(R.string.mainui_description_service_disabled)
                        .format(stringResource(R.string.app_name))
                )
                Button(
                    modifier = Modifier
                        .fillMaxWidth(),
                    onClick = { vm.openSettings() },
                    content = {
                        Text(stringResource(R.string.mainui_button_open_settings))
                    }
                )
            } else {
                Text(
                    stringResource(R.string.mainui_description_service_enabled)
                        .format(stringResource(R.string.app_name))
                )

                Spacer(Modifier)

                SectionHeading(
                    stringResource(R.string.heading_cross_device_login)
                )
                Text(
                    stringResource(R.string.description_cross_device_login)
                )

                Spacer(Modifier)

                SectionHeading(
                    "Entries"
                )
                Text("Show the following entries when available")

                Preference(
                    stringResource(microgR.string.fido_transport_selection_nfc),
                    stringResource(R.string.clicklabel_toggle_nfc),
                    vm.state.entryNfc
                ) { vm.toggleNfcEntry() }

                Preference(
                    stringResource(microgR.string.fido_transport_selection_usb),
                    stringResource(R.string.clicklabel_toggle_usb),
                    vm.state.entryUsb
                ) { vm.toggleUsbEntry() }

                Preference(
                    stringResource(microgR.string.fido_transport_selection_hybrid),
                    stringResource(R.string.clicklabel_toggle_hybrid),
                    vm.state.entryHybrid
                ) { vm.toggleHybridEntry() }
            }
        }
    }
}

@Composable
fun SectionHeading(text: String) = Text(
    text,
    style = MaterialTheme.typography.titleMedium.copy(MaterialTheme.colorScheme.secondary)
)

@Composable
fun Preference(
    label: String,
    onclickLabel: String,
    switched: Boolean? = null,
    onSelect: () -> Unit
) {
    Row(
        Modifier.clickable(
            true,
            onclickLabel,
            onClick = { onSelect() }
        )
    ) {
        // We don't set the padding to the Row above,
        // to get a clickable over from start to end
        val modifier = switched?.let {
            Modifier.padding(start = 24.dp, top = 8.dp, bottom = 8.dp)
        } ?: Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
        Column(
            modifier
                .weight(1f)
                .align(Alignment.CenterVertically)
        ) {
            Text(
                style = MaterialTheme.typography.titleMedium,
                text = label
            )
        }
        switched?.let {
            Switch(
                modifier = Modifier
                    .padding(end = 24.dp)
                    .scale(0.8f)
                    .align(Alignment.CenterVertically),
                checked = switched,
                enabled = true,
                onCheckedChange = { onSelect() }
            )
        }
    }
}

@Preview
@Composable
fun PreviewMainUi() = MainUi(MainViewModel.preview())
