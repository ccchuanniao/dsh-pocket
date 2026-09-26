package app.dshpocket

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * 收到推送就把任务结果放进通知栏。
 *
 * 存在的理由很直接：手机连 harness 主要是为了"任务跑完了告诉我一声"。盯着屏幕等一个可能跑
 * 十分钟的回合是不现实的，而 Android 在应用退到后台后会掐掉网页里那条实时连接 ——
 * 只有推送能把消息送进来。
 *
 * 这个服务只在"构建时注入了 Firebase 配置"的包里会被系统调用；没有配置的包不会注册到
 * Firebase，也就永远收不到消息，这段代码是死代码。
 */
class PushService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: message.data["title"] ?: getString(R.string.app_name)
        val body = message.notification?.body ?: message.data["body"] ?: return
        show(title, body)
    }

    /**
     * 令牌会在重装、恢复备份、或长期不用之后被 Firebase 换掉。
     *
     * 换掉而不上报，那台设备就再也收不到推送了 —— 而且是静默的：服务端还在往一个已经失效的
     * 令牌发消息，手机什么也不显示。所以这里也要上报一次。
     *
     * 这里不能依赖 Activity：服务可能在应用完全没界面的时候被拉起。令牌先落到本地，
     * 下次界面启动时会带着它去注册。
     */
    override fun onNewToken(token: String) {
        PushRegistration.stash(this, token)
    }

    private fun show(title: String, body: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notify_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }

        // 点通知回到 App，而不是新开一个任务栈 —— 应用是 singleTask，这里只是一次 resume。
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            // 任务标题常常比一行长，展开能看全。
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()

        // 用会话名当 id 的一部分不现实（服务端不一定给），用当前时间：同一回合的重复推送会被
        // 后来的覆盖，而不是堆一屏。
        manager.notify((System.currentTimeMillis() / 1000).toInt(), notification)
    }

    companion object {
        const val CHANNEL_ID = "dsh-tasks"
    }
}
