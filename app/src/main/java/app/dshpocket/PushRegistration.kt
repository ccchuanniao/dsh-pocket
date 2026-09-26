package app.dshpocket

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * 把这台设备的推送令牌报给 harness 那边的插件。
 *
 * 服务端要按令牌发消息，不登记就收不到。登记走**闸门**那条路：它本来就已经按设备令牌
 * 认过这台设备了，所以插件知道这个推送令牌属于哪一台，吊销设备时能一起清掉。
 *
 * 令牌可能在任何界面都没起来的时候（onNewToken）产生，所以分两步：先落到本地，
 * 等有界面时再带着它去登记。只存在本地等于永远没登记，那台设备会静默地收不到任何消息。
 */
internal object PushRegistration {
    private const val STORE = "dsh-pocket-push"
    private const val KEY_TOKEN = "token"
    private const val KEY_REGISTERED = "registered"
    private const val TIMEOUT_MS = 15_000

    /** 记下令牌，标记为尚未登记。 */
    fun stash(context: Context, token: String) {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
            .putString(KEY_TOKEN, token)
            .putBoolean(KEY_REGISTERED, false)
            .apply()
    }

    fun pending(context: Context): String? {
        val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_REGISTERED, false)) return null
        return prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotEmpty() }
    }

    /**
     * 上报一次。成功与否都不抛给调用方：推送登记失败不该影响配对或加载控制台 ——
     * 下次启动还会再试一次。
     *
     * @returns 成功时为 true；false 表示下次还要重试。
     */
    fun register(base: String, token: String, deviceToken: String): Boolean {
        val body = JSONObject().put("token", token).toString().toByteArray(Charsets.UTF_8)
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("$base/dsh-pocket-pair/push").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setFixedLengthStreamingMode(body.size)
                // 必须**显式**带上设备令牌。设备令牌本来只存在 WebView 的 CookieManager 里，
                // 而这里是应用自己的 HTTP 栈 —— 两者不共享 cookie，所以裸请求会被闸门 401，
                // 而且失败是静默的（只是没有推送令牌，界面上看不出原因）。
                if (deviceToken.isNotEmpty())
                    setRequestProperty("X-Dsh-Device-Token", deviceToken)
            }
            connection.outputStream.use { it.write(body) }
            connection.responseCode in 200..299
        }
        catch (error: Exception) {
            false
        }
        finally {
            connection?.disconnect()
        }
    }

    fun markRegistered(context: Context) {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_REGISTERED, true)
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
