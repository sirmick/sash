package s1m.hwfido2provider.migration

import android.content.Context
import android.util.Log

class Migrations(val context: Context) {
    private val sharedPreferences = context.getSharedPreferences(PREF_MASTER, Context.MODE_PRIVATE)

    val migrations: List<Migration> = listOf(
        Migration010000
    )

    @Synchronized
    fun run() {
        val currentVersion = sharedPreferences.getInt(PREF_MIGRATIONS_LEVEL, 0)
        val migrations = migrations.sortedBy { it.version }
        val lastVersion = migrations.lastOrNull()?.version ?: return
        if (currentVersion >= migrations.last().version) return
        Log.d(TAG, "Migration from $currentVersion to $lastVersion")
        migrations.forEach {
            if (currentVersion < it.version) {
                it.run(context)
                sharedPreferences.edit().putInt(PREF_MIGRATIONS_LEVEL, it.version).apply()
            }
        }
    }

    interface Migration {
        val version: Int
        fun run(context: Context)
    }

    companion object {
        private const val TAG = "Migrations"
        private const val PREF_MASTER = "migrations"
        private const val PREF_MIGRATIONS_LEVEL = "migrations.level"
    }
}
