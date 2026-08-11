package s1m.hwfido2provider

import android.content.Context

class Store(context: Context) {
    private val prefs = context.getSharedPreferences(PREF_MASTER, Context.MODE_PRIVATE)

    var entryNfc: Boolean
        get() = prefs.getBoolean(PREF_ENTRY_NFC, true)
        set(value) = prefs.edit().putBoolean(PREF_ENTRY_NFC, value).apply()

    var entryUsb: Boolean
        get() = prefs.getBoolean(PREF_ENTRY_USB, true)
        set(value) = prefs.edit().putBoolean(PREF_ENTRY_USB, value).apply()

    var entryHybrid: Boolean
        get() = prefs.getBoolean(PREF_ENTRY_HYBRID, true)
        set(value) = prefs.edit().putBoolean(PREF_ENTRY_HYBRID, value).apply()

    companion object {
        private const val PREF_MASTER = "prefs"
        private const val PREF_ENTRY_NFC = "entry_nfc"
        private const val PREF_ENTRY_USB = "entry_usb"
        private const val PREF_ENTRY_HYBRID = "entry_hybrid"
    }
}
