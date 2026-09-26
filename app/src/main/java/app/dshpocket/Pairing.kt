package app.dshpocket

import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pairing a device with a harness that runs the dsh-pocket-pair plugin.
 *
 * The owner mints a single-use code on the harness side and the phone spends it here. What comes
 * back is not a credential the phone invented: it is the harness's own admission URL, carrying the
 * token the running harness process minted at start-up. Opening that URL once makes the harness set
 * its session cookie, and every later request rides on the cookie.
 *
 * The code is single use on the server, so a retry after a failed exchange needs a fresh code. That
 * is deliberate: it keeps a code that was typed into the wrong device from staying usable.
 */
internal object Pairing {
    private const val PATH = "/dsh-pocket-pair/redeem"
    private const val TIMEOUT_MS = 20_000

    /**
     * The result of spending a code.
     *
     * `token` is the device credential for the plugin's gate, and it is the one worth keeping: the
     * gate holds the device table, so a device can be revoked on its own. The phone rebuilds the
     * gate URL from the very address it paired against rather than from anything the server sends,
     * because only the phone knows whether it arrived over the public entry or over the LAN.
     *
     * `url` is the harness's own admission URL, which bypasses the gate. It is kept as a fallback
     * for a harness that runs the plugin without the gate.
     */
    sealed interface Result {
        data class Admitted(val url: String, val token: String?) : Result

        data class Refused(val message: String) : Result
    }

    /** A host, a code, and whether the host is a local address, as read from a scanned link. */
    data class Link(val base: String, val code: String, val lanMode: Boolean)

    fun redeem(base: String, code: String, device: String): Result {
        val body = JSONObject()
            .put("code", code)
            .put("device", device)
            .toString()
            .toByteArray(Charsets.UTF_8)

        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(base + PATH).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setFixedLengthStreamingMode(body.size)
            }
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                return Result.Refused(errorOf(text, status))
            }
            val payload = JSONObject(text)
            val url = payload.optString("url")
            val token = payload.optString("token").takeIf { it.isNotEmpty() }
            if (url.isEmpty() && token == null) {
                Result.Refused("the harness accepted the code but returned no address")
            } else {
                Result.Admitted(url, token)
            }
        } catch (error: Exception) {
            Result.Refused(error.message ?: "could not reach $base")
        } finally {
            connection?.disconnect()
        }
    }

    private fun errorOf(text: String, status: Int): String = try {
        JSONObject(text).optString("error").ifEmpty { "pairing failed ($status)" }
    } catch (error: Exception) {
        "pairing failed ($status)"
    }

    /**
     * Reads a pairing link, or returns null when the intent carries something else.
     *
     * The plugin puts the address and the code in the URL fragment rather than the query, because a
     * fragment is never sent to the server. That keeps a pairing code out of the download host's
     * access log, and it is also why the code only reaches us here, after the link is opened.
     */
    fun linkFrom(uri: Uri?): Link? {
        if (uri == null) return null
        val fragment = uri.fragment ?: return null
        val parameters = try {
            fragment.split('&').mapNotNull { pair ->
                val at = pair.indexOf('=')
                if (at <= 0) null else Uri.decode(pair.substring(0, at)) to Uri.decode(pair.substring(at + 1))
            }.toMap()
        } catch (error: Exception) {
            return null
        }
        val rawBase = parameters["b"] ?: return null
        val code = parameters["c"] ?: return null
        if (code.isEmpty()) return null
        val scheme = uri.scheme?.lowercase()
        val lanMode = scheme == "http" || rawBase.startsWith("http://")
        val base = PairingPrefs.normalize(rawBase, lanMode) ?: return null
        return Link(base, code, lanMode)
    }
}
