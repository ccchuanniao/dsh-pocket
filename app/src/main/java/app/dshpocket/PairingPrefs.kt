package app.dshpocket

import android.content.Context
import android.net.Uri

/**
 * Where this device reaches the harness, and whether that address is a local one.
 *
 * The address used to be a compile-time constant, which made the app usable against exactly one
 * deployment. The owner pairs a phone by typing, or by scanning, the address the plugin reports, so
 * the address is now per-installation state.
 *
 * Only the origin is kept: a scheme, a host, and usually a port. Paths, queries, and fragments are
 * dropped because every request the app makes is built from the origin, and keeping the rest would
 * let a stale path from a scanned link leak into later calls.
 *
 * `lanMode` is the owner's statement that this address is a local one, so a bare host typed without
 * a scheme is completed with `http` rather than `https`. Local deployments have no certificate, and
 * assuming `https` there fails in a way whose error text says nothing about the real cause.
 */
internal data class PairingPrefs(val base: String, val lanMode: Boolean, val deviceToken: String) {
    /** The origin every harness URL for this installation is built from. */
    val origin: String get() = "$base/"

    companion object {
        private const val STORE = "dsh-pocket-pairing"
        private const val KEY_BASE = "base"
        private const val KEY_LAN = "lan"
        private const val KEY_DEVICE = "device"

        fun load(context: Context): PairingPrefs? {
            val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            val base = prefs.getString(KEY_BASE, null) ?: return null
            return PairingPrefs(base, prefs.getBoolean(KEY_LAN, false), prefs.getString(KEY_DEVICE, null).orEmpty())
        }

        fun save(context: Context, base: String, lanMode: Boolean, deviceToken: String = "") {
            context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
                .putString(KEY_BASE, base)
                .putBoolean(KEY_LAN, lanMode)
                .putString(KEY_DEVICE, deviceToken)
                .apply()
        }

        /**
         * Turns typed or scanned text into an origin, or null when no origin can be read from it.
         *
         * Accepts a bare host, a host and port, or a full URL, because the owner reads these off a
         * screen and types them by hand. A path that looks like it belongs to the app is kept out of
         * the result on purpose: callers append their own paths.
         */
        fun normalize(input: String, lanMode: Boolean): String? {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return null
            val withScheme = if (trimmed.contains("://")) {
                trimmed
            } else {
                // IPv6 literals are bracketed, so a colon inside brackets is not a port separator.
                (if (lanMode) "http://" else "https://") + trimmed
            }
            val parsed = try {
                Uri.parse(withScheme)
            } catch (error: Exception) {
                return null
            }
            val scheme = parsed.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            // Uri reports the authority only when the string actually had one, which rejects text
            // such as "https://" as well as anything the owner typed without a host.
            val authority = parsed.authority?.trim()?.trimEnd('/') ?: return null
            if (authority.isEmpty()) return null
            return "$scheme://$authority"
        }
    }
}
