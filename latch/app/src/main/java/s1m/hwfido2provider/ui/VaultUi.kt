package s1m.hwfido2provider.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import s1m.hwfido2provider.R
import s1m.hwfido2provider.vault.Entry
import s1m.hwfido2provider.vault.Peer
import s1m.hwfido2provider.vault.Resolution

/** What the vault screen is showing. */
sealed interface VaultScreen {
    data object Create : VaultScreen
    data object Unlock : VaultScreen
    data class ListEntries(val entries: List<Entry>, val resolutions: List<Resolution>) : VaultScreen
    data class Edit(val entry: Entry?) : VaultScreen

    /** Choosing a credential to hand back to whoever asked for one. */
    data class Pick(val prompt: String, val entries: List<Entry>) : VaultScreen

    /** Devices, and the QR that adds one. */
    data class Sync(
        val deviceId: String,
        val qr: Bitmap?,
        val peers: List<Peer>
    ) : VaultScreen
}

/** Everything the screen can ask the host activity to do. */
interface VaultActions {
    fun create(passphrase: String): Boolean
    fun unlock(passphrase: String): Boolean
    fun unlockWithScreenLock()
    fun lock()
    fun canUseScreenLock(): Boolean
    fun save(entry: Entry?, origin: String, username: String, password: String, notes: String)
    fun delete(entry: Entry)
    fun edit(entry: Entry?)
    fun back()

    /** Only the credential-picking ceremony implements this. */
    fun choose(entry: Entry) = Unit

    fun syncRunning(): Boolean = false
    fun toggleSync() = Unit
    fun syncStatus(): String? = null

    /** Whether the pairing form is worth showing. */
    fun syncNeedsPairing(): Boolean = false
    fun pair(peerId: String, address: String) = Unit
    fun openSync() = Unit
}

@Composable
fun VaultUi(screen: VaultScreen, actions: VaultActions) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when (screen) {
                is VaultScreen.Create -> CreateVault(actions)
                is VaultScreen.Unlock -> UnlockVault(actions)
                is VaultScreen.ListEntries -> EntryList(screen, actions)
                is VaultScreen.Edit -> EditEntry(screen.entry, actions)
                is VaultScreen.Pick -> PickEntry(screen, actions)
                is VaultScreen.Sync -> SyncScreen(screen, actions)
            }
        }
    }
}

@Composable
private fun CreateVault(actions: VaultActions) {
    var passphrase by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val mismatch = stringResource(R.string.latch_mismatch)
    val tooShort = stringResource(R.string.latch_too_short)

    Text(stringResource(R.string.latch_create_heading), style = MaterialTheme.typography.headlineSmall)
    Text(stringResource(R.string.latch_create_body), style = MaterialTheme.typography.bodyMedium)

    // The sync controls belong here too, not only on the list. On a new device
    // the order is necessarily: sync first, then unlock -- there is no vault to
    // unlock until one arrives. Offering only "create" here would invite the
    // user to make a second, empty vault and lose the one they have.
    HorizontalDivider()
    Text(stringResource(R.string.latch_restore), style = MaterialTheme.typography.bodyMedium)
    SyncControls(actions)
    HorizontalDivider()
    Secret(stringResource(R.string.latch_passphrase), passphrase) { passphrase = it; error = null }
    Secret(stringResource(R.string.latch_passphrase_again), again) { again = it; error = null }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            error = when {
                passphrase.length < 8 -> tooShort
                passphrase != again -> mismatch
                else -> null.also { actions.create(passphrase) }
            }
        }
    ) { Text(stringResource(R.string.latch_create_button)) }
}

@Composable
private fun UnlockVault(actions: VaultActions) {
    var passphrase by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val wrong = stringResource(R.string.latch_wrong_passphrase)

    Text(stringResource(R.string.latch_unlock_heading), style = MaterialTheme.typography.headlineSmall)
    Secret(stringResource(R.string.latch_passphrase), passphrase) { passphrase = it; error = null }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(
        modifier = Modifier.fillMaxWidth(),
        onClick = { if (!actions.unlock(passphrase)) error = wrong }
    ) { Text(stringResource(R.string.latch_unlock_button)) }

    // Offered only when the device can actually gate the key behind the user.
    if (actions.canUseScreenLock()) {
        TextButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = { actions.unlockWithScreenLock() }
        ) { Text(stringResource(R.string.latch_unlock_screen_lock)) }
    }
}

@Composable
private fun EntryList(screen: VaultScreen.ListEntries, actions: VaultActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.latch_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f)
        )
        if (actions.syncRunning()) {
            TextButton(onClick = { actions.toggleSync() }) {
                Text(stringResource(R.string.latch_sync_stop))
            }
        }
        TextButton(onClick = { actions.lock() }) { Text(stringResource(R.string.latch_lock)) }
    }

    SyncControls(actions)

    // Conflicts are reported, never silently absorbed: when two devices both
    // changed a password, only one of them is what the site now has.
    screen.resolutions.filter { it.passwordsDiffered }.forEach {
        Card(Modifier.fillMaxWidth()) {
            Text(
                "${it.origin} changed on two devices — both passwords kept.",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }

    Button(
        modifier = Modifier.fillMaxWidth(),
        onClick = { actions.edit(null) }
    ) { Text(stringResource(R.string.latch_add)) }

    if (screen.entries.isEmpty()) {
        Text(stringResource(R.string.latch_empty), style = MaterialTheme.typography.bodyMedium)
        return
    }

    screen.entries.forEach { entry ->
        Column(
            Modifier
                .fillMaxWidth()
                .clickable { actions.edit(entry) }
                .padding(vertical = 10.dp)
        ) {
            Text(entry.origin, style = MaterialTheme.typography.titleMedium)
            Text(entry.username, style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
    }
}

/** Sync state, and a way in to the sync screen. */
@Composable
private fun SyncControls(actions: VaultActions) {
    if (!actions.syncRunning()) {
        TextButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = { actions.toggleSync() }
        ) { Text(stringResource(R.string.latch_sync_start)) }
        return
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            actions.syncStatus().orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = { actions.openSync() }) {
            Text(stringResource(R.string.latch_sync_devices))
        }
    }
}

/**
 * Devices, and the code that adds one.
 *
 * The QR is this device's own identity. The other device scans it **with the
 * system camera app**, which opens the `latch://pair` link — so there is no
 * camera code, no permission and no decoder here.
 */
@Composable
private fun SyncScreen(screen: VaultScreen.Sync, actions: VaultActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.latch_sync_devices),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = { actions.back() }) { Text(stringResource(R.string.latch_done)) }
    }

    screen.qr?.let {
        Text(stringResource(R.string.latch_scan_me), style = MaterialTheme.typography.bodyMedium)
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = stringResource(R.string.latch_scan_me),
            modifier = Modifier.size(220.dp)
        )
    }
    // The id in text as well as in the code: pairing has to work when the other
    // end is a headless box with no camera pointed at anything.
    Text(screen.deviceId, style = MaterialTheme.typography.bodySmall)

    HorizontalDivider()

    if (screen.peers.isEmpty()) {
        Text(stringResource(R.string.latch_no_devices), style = MaterialTheme.typography.bodyMedium)
    } else {
        screen.peers.forEach { peer ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(peer.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    (if (peer.connected) "connected" else "offline") +
                        (peer.address.takeIf { it.isNotBlank() && peer.connected }
                            ?.let { " · $it" } ?: "") +
                        " · ${peer.shortId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (peer.connected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            HorizontalDivider()
        }
    }

    var peerId by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    Text(stringResource(R.string.latch_add_device), style = MaterialTheme.typography.bodyMedium)
    Field(stringResource(R.string.latch_pair_device), peerId, KeyboardType.Text) { peerId = it }
    Field(stringResource(R.string.latch_pair_address), address, KeyboardType.Uri) { address = it }
    Button(
        modifier = Modifier.fillMaxWidth(),
        onClick = { actions.pair(peerId.trim(), address.trim()) }
    ) { Text(stringResource(R.string.latch_pair)) }
}

@Composable
private fun PickEntry(screen: VaultScreen.Pick, actions: VaultActions) {
    Text(screen.prompt, style = MaterialTheme.typography.headlineSmall)

    if (screen.entries.isEmpty()) {
        Text(stringResource(R.string.latch_empty), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { actions.back() }) { Text(stringResource(R.string.latch_cancel)) }
        return
    }

    screen.entries.forEach { entry ->
        Column(
            Modifier
                .fillMaxWidth()
                .clickable { actions.choose(entry) }
                .padding(vertical = 12.dp)
        ) {
            Text(entry.username, style = MaterialTheme.typography.titleMedium)
            Text(entry.origin, style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
    }
}

@Composable
private fun EditEntry(entry: Entry?, actions: VaultActions) {
    var origin by remember { mutableStateOf(entry?.origin ?: "") }
    var username by remember { mutableStateOf(entry?.username ?: "") }
    var password by remember { mutableStateOf(entry?.password ?: "") }
    var notes by remember { mutableStateOf(entry?.notes ?: "") }
    var revealed by remember { mutableStateOf(false) }

    Text(
        entry?.origin ?: stringResource(R.string.latch_add),
        style = MaterialTheme.typography.headlineSmall
    )
    Field(stringResource(R.string.latch_site), origin, KeyboardType.Uri) { origin = it }
    Field(stringResource(R.string.latch_username), username, KeyboardType.Email) { username = it }

    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text(stringResource(R.string.latch_password)) },
        singleLine = true,
        visualTransformation =
            if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth()
    )
    TextButton(onClick = { revealed = !revealed }) {
        Text(stringResource(if (revealed) R.string.latch_hide else R.string.latch_reveal))
    }

    Field(stringResource(R.string.latch_notes), notes, KeyboardType.Text) { notes = it }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            modifier = Modifier.weight(1f),
            onClick = { actions.save(entry, origin.trim(), username.trim(), password, notes) }
        ) { Text(stringResource(R.string.latch_save)) }
        TextButton(modifier = Modifier.weight(1f), onClick = { actions.back() }) {
            Text(stringResource(R.string.latch_cancel))
        }
    }
    entry?.let {
        TextButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = { actions.delete(it) }
        ) { Text(stringResource(R.string.latch_delete), color = MaterialTheme.colorScheme.error) }
    }

    // History is shown rather than hidden: after a conflict it is the record of
    // which password the site might still be expecting.
    if (!entry?.history.isNullOrEmpty()) {
        Spacer(Modifier.height(8.dp))
        SectionHeading(stringResource(R.string.latch_history))
        entry.history.forEach { past ->
            Text(
                if (revealed) past.password else "•".repeat(past.password.length.coerceAtMost(12)),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun Field(label: String, value: String, type: KeyboardType, onChange: (String) -> Unit) =
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth()
    )

@Composable
private fun Secret(label: String, value: String, onChange: (String) -> Unit) =
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done
        ),
        modifier = Modifier.fillMaxWidth()
    )
