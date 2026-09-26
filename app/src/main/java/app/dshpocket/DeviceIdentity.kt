package app.dshpocket

import java.security.SecureRandom

/**
 * The credential pair that identifies this installation to the harness gate.
 *
 * The owner asked for the device itself to be the credential rather than a password typed on each
 * network. A phone's serial number cannot serve: since Android 10 an ordinary app reads "unknown"
 * from `Build.getSerial()`. A random pair generated here and registered on the server once is the
 * workable equivalent, and it is per installation, so a lost device can be revoked on its own.
 *
 * The pair is generated once and never regenerated on its own. A rejected pair means the server
 * side was changed, and showing the same pair again is what lets it be registered again; minting a
 * new one would silently invalidate whatever the owner had already registered.
 */
internal data class DeviceIdentity(val userName: String, val token: String) {
    companion object {
        private const val RANDOM_BYTES = 32
        private const val NAME_BYTES = 4

        /** A fresh pair, never shown before. */
        fun create(): DeviceIdentity {
            val random = SecureRandom()
            val nameBytes = ByteArray(NAME_BYTES).also(random::nextBytes)
            val tokenBytes = ByteArray(RANDOM_BYTES).also(random::nextBytes)
            return DeviceIdentity(
                userName = "dsh-" + nameBytes.toHex(),
                token = tokenBytes.toHex(),
            )
        }

        private fun ByteArray.toHex(): String =
            joinToString("") { "%02x".format(it) }
    }
}
