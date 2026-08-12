package s1m.hwfido2provider.vault

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Configures Syncthing so the user never has to.
 *
 * Every choice here is one the user would otherwise be asked to make, and the
 * answers are the same for everyone running this: one folder, the vault; one
 * peer, your own box; no discovery servers, no relays.
 */
object SyncSetup {
    private const val TAG = "latch"

    /** One folder, always this id, so both ends agree without negotiating. */
    const val FOLDER_ID = "latch-vault"

    /**
     * Points a device at its peer and shares the vault with it.
     *
     * [address] is optional: with it, Syncthing connects directly; without it,
     * the peer has to be found, and we have discovery turned off. For a box at
     * a known address on your own network, giving the address is both faster
     * and one less thing that can be watched.
     */
    fun pair(context: Context, peerId: String, address: String?) {
        val device = JSONObject()
            .put("deviceID", peerId.trim())
            .put("name", "home")
            .put("addresses", JSONArray().put(address?.takeIf { it.isNotBlank() } ?: "dynamic"))
        SyncApi.post(context, "/rest/config/devices", device.toString())
        Log.i(TAG, "sync: paired with ${peerId.take(7)}")

        val folder = JSONObject()
            .put("id", FOLDER_ID)
            .put("label", "Vault")
            .put("path", VaultManager.dir(context).absolutePath)
            .put("type", "sendreceive")
            .put(
                "devices",
                JSONArray().put(JSONObject().put("deviceID", peerId.trim()))
            )
            // The inotify watcher is what dies under seccomp on x86_64 Android;
            // periodic rescans are slower to notice a change, not broken.
            .put("fsWatcherEnabled", false)
            .put("rescanIntervalS", 30)
        SyncApi.post(context, "/rest/config/folders", folder.toString())
        Log.i(TAG, "sync: sharing ${VaultManager.dir(context)}")

        quieten(context)
    }

    /**
     * Turns off everything that would announce this device to the internet.
     *
     * The point of the design is a box you own and a phone you own. Global
     * discovery publishes the device id to a public server, and relays route
     * traffic through strangers when a direct connection fails. Neither is
     * needed for two machines on one network, and both are the sort of default
     * that is fine for a file sync tool and wrong for a password vault.
     */
    fun quieten(context: Context) {
        val options = JSONObject()
            .put("globalAnnounceEnabled", false)
            .put("relaysEnabled", false)
            .put("natEnabled", false)
            .put("urAccepted", -1)
            .put("crashReportingEnabled", false)
        SyncApi.request(context, "PATCH", "/rest/config/options", options.toString())
        Log.i(TAG, "sync: discovery, relays and reporting disabled")
    }

    /** True once the vault folder exists in the running config. */
    fun isPaired(context: Context): Boolean = runCatching {
        SyncApi.config(context).getJSONArray("folders").let { folders ->
            (0 until folders.length()).any { folders.getJSONObject(it).getString("id") == FOLDER_ID }
        }
    }.getOrDefault(false)

    /**
     * The devices we are paired with, and whether they are connected right now.
     *
     * Ourselves excluded: "this device" is already on screen above the list, and
     * a device that appears to be paired with itself reads as a bug.
     */
    fun peers(context: Context): List<Peer> = runCatching {
        val self = SyncApi.status(context).getString("myID")
        val connections = JSONObject(SyncApi.get(context, "/rest/system/connections"))
            .getJSONObject("connections")
        val devices = SyncApi.config(context).getJSONArray("devices")

        (0 until devices.length()).mapNotNull { i ->
            val device = devices.getJSONObject(i)
            val id = device.getString("deviceID")
            if (id == self) return@mapNotNull null
            val connection = connections.optJSONObject(id)
            Peer(
                id = id,
                name = device.optString("name").ifBlank { id.take(7) },
                connected = connection?.optBoolean("connected") == true,
                address = connection?.optString("address").orEmpty()
            )
        }
    }.getOrDefault(emptyList())

    /** Short human status for the folder, e.g. "up to date". */
    fun folderState(context: Context): String? = runCatching {
        JSONObject(SyncApi.get(context, "/rest/db/status?folder=$FOLDER_ID")).getString("state")
    }.getOrNull()
}
