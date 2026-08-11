package s1m.hwfido2provider.migration

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.util.Base64
import android.util.Log
import androidx.core.database.getStringOrNull
import com.google.android.gms.fido.fido2.api.common.PublicKeyCredentialUserEntity
import org.microg.gms.fido.core.Database
import org.microg.gms.fido.core.protocol.CredentialId
import org.microg.gms.fido.core.transport.Transport
import s1m.hwfido2provider.migration.Migrations.Migration

/**
 * Migration from 0.x.x to 1.0.0
 */
object Migration010000 : Migration {
    private const val TAG = "Migration010000"
    override val version = 1_00_00

    override fun run(context: Context) {
        try {
            val screenlockDbName = "screenlockcredentials.db"
            val oldDb = SQLiteDatabase.openDatabase(
                context.getDatabasePath(screenlockDbName).absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            )
            if (oldDb.version == 2) {
                Log.d(TAG, "Old screenlockcredentials.db version 2 found")
                val db = Database(context)
                db.registrationsWithoutUsers().forEach { reg ->
                    Log.d(TAG, "Found registration without user (${reg.rpId})")
                    val keyId = keyIdFromCredential(reg.credential) ?: return@forEach
                    OldDB(oldDb).getUserInfo(reg.rpId, keyId)?.let { user ->
                        Log.d(TAG, "Inserting user info for the registration")
                        db.insertKnownRegistration(reg.rpId, reg.credential, reg.transport, user.toJson())
                    }
                }
                db.close()
            }
            oldDb.close()
            context.deleteDatabase(screenlockDbName)
        } catch (_: SQLiteException) {
            Log.d(TAG, "Old screenlockcredentials.db doesn't exist, no need to migrate")
        }
    }

    private class OldDB(val db: SQLiteDatabase) {
        fun getUserInfo(rpId: String, keyId: ByteArray): PublicKeyCredentialUserEntity? {
            val keyAlias = getAlias(rpId, keyId)
            val cursor = db.query(
                "DISPLAY_NAMES_TABLE",
                arrayOf("NAME_COLUMN", "DISPLAY_NAME_COLUMN", "HANDLE_COLUMN"),
                "KEY_ALIAS_COLUMN = ?",
                arrayOf(keyAlias),
                null,
                null,
                null,
                null
            )

            return cursor.use { cursor ->
                if (cursor.moveToNext()) {
                    val name = cursor.getString(0)
                    val displayName = cursor.getStringOrNull(1) ?: name
                    val handle = Base64.decode(cursor.getString(2), Base64.DEFAULT)
                    PublicKeyCredentialUserEntity(
                        handle,
                        name,
                        null,
                        displayName
                    )
                } else {
                    null
                }
            }
        }
    }

    private class Credential(
        val rpId: String,
        val credential: String,
        val transport: Transport
    )

    private fun Database.registrationsWithoutUsers(): MutableList<Credential> = readableDatabase.use {
        val cursor = it.query(
            "known_registrations",
            arrayOf("rp_id", "credential_id", "transport"),
            "register_user IS NULL",
            null,
            null,
            null,
            null
        )
        val result = mutableListOf<Credential>()
        cursor.use { c ->
            while (c.moveToNext()) {
                val rpId = c.getString(0)
                val credentialId = c.getString(1)
                val transport = c.getStringOrNull(2) ?: continue
                result.add(Credential(rpId, credentialId, Transport.valueOf(transport)))
            }
        }
        result
    }

    private fun keyIdFromCredential(credential: String): ByteArray? = runCatching {
        val (type, keyId) = CredentialId.decodeTypeAndDataByBase64(credential)
        if (type == 1.toByte()) {
            keyId
        } else {
            null
        }
    }.getOrNull()

    private fun getAlias(rpId: String, keyId: ByteArray): String =
        "1." + String(Base64.encode(keyId, Base64.NO_PADDING + Base64.NO_WRAP)) + "." + rpId
}
