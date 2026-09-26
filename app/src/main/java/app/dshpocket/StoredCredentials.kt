package app.dshpocket

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * The HTTP basic credentials the harness gate asks for.
 *
 * The harness sits behind an nginx gate that admits a short list of source addresses and asks
 * everyone else for basic auth. A phone on mobile data is never on that list, so without a way to
 * answer the challenge the WebView abandons the load and reports `ERR_CONNECTION_CLOSED`.
 *
 * Two properties matter here, and both were missing once:
 *
 * - **The pair never touches plain storage.** An earlier version fell back to ordinary
 *   `SharedPreferences` when the keystore could not be opened. That fallback wrote a password in the
 *   clear, silently, on exactly the devices least able to protect it — and the comment claiming the
 *   pair was "not readable from a device backup" was simply untrue on those devices. There is no
 *   fallback now: when the encrypted store cannot be opened the pair is not persisted at all and the
 *   user is asked again. Losing convenience is the right trade against writing a password in the clear.
 * - **The pair is scoped to the host it belongs to.** Re-pointing the app at a different harness must
 *   not offer the previous server's password to the new one.
 *
 * This store holds only the harness password. The device token lives in the WebView cookie jar, and
 * [PairingPrefs] holds the address and token used for push registration.
 */
internal class StoredCredentials private constructor(
    val host: String,
    val userName: String,
    val password: String,
) {
    companion object {
        private const val TAG = "DshPocket"
        private const val STORE = "dsh-pocket-credentials"

        /** Written by a version that fell back to plain storage. Deleted on use, never read. */
        private const val STORE_PLAIN_LEGACY = "dsh-pocket-credentials-plain"

        private const val KEY_HOST = "host"
        private const val KEY_USER = "user"
        private const val KEY_PASSWORD = "password"

        /** The pair saved for [host], or null when there is none or it belongs to another host. */
        fun load(context: Context, host: String): StoredCredentials? {
            val prefs = open(context) ?: return null
            val storedHost = prefs.getString(KEY_HOST, null) ?: return null
            if (!storedHost.equals(host, ignoreCase = true)) return null
            val user = prefs.getString(KEY_USER, null) ?: return null
            val password = prefs.getString(KEY_PASSWORD, null) ?: return null
            return StoredCredentials(storedHost, user, password)
        }

        /**
         * Whether any pair at all is stored, regardless of host.
         *
         * Used only to decide whether repeated load failures are worth blaming on the credentials. A
         * pair stored for a different host still counts: what matters is that the user signed in once.
         */
        fun exists(context: Context): Boolean = open(context)?.contains(KEY_PASSWORD) == true

        /**
         * Stores the pair for [host].
         *
         * @returns whether it was persisted. A false return is not a failure to sign in — the caller
         *   should carry on with the credentials it already has — but the pair will not survive a
         *   restart, so the user will be asked again.
         */
        fun save(context: Context, host: String, userName: String, password: String): Boolean {
            val prefs = open(context) ?: return false
            return prefs.edit()
                .putString(KEY_HOST, host)
                .putString(KEY_USER, userName)
                .putString(KEY_PASSWORD, password)
                .commit()
        }

        /** Forgets the pair, so the next challenge asks again instead of reusing a rejected one. */
        fun clear(context: Context) {
            open(context)?.edit()?.clear()?.apply()
        }

        /**
         * The encrypted store, or null when it cannot be opened.
         *
         * Null means "do not persist"; it never means "use something weaker instead".
         */
        private fun open(context: Context): SharedPreferences? {
            dropLegacyPlainStore(context)
            return try {
                // NOTICE: MasterKeys is deprecated in favour of MasterKey in the 1.1.0 line, which is
                // still alpha. This project stays on the stable 1.0.0 release; move to MasterKey when
                // security-crypto 1.1.0 ships.
                val keyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
                EncryptedSharedPreferences.create(
                    STORE,
                    keyAlias,
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            } catch (error: Exception) {
                Log.w(TAG, "encrypted credential store unavailable; the pair will not be persisted", error)
                null
            }
        }

        /**
         * Remove the plaintext store left by an earlier version.
         *
         * A password sitting in the clear is worth deleting even though nothing reads it any more —
         * otherwise it would outlive the fix on every device that already had one.
         */
        private fun dropLegacyPlainStore(context: Context) {
            try {
                context.deleteSharedPreferences(STORE_PLAIN_LEGACY)
            } catch (error: Exception) {
                Log.w(TAG, "could not remove the legacy plain credential store", error)
            }
        }
    }
}
