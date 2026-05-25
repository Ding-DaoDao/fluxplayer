package dev.anilbeesetti.nextplayer.core.data.openlist

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * OpenList 本地服务器的前台 Service。
 *
 * 通过前台通知保活，防止 Android 系统在后台杀死 OpenList 进程。
 * 启动/停止时弹出 Toast 提示。
 */
class OpenListService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "openlist_service"
        private const val ACTION_START = "dev.anilbeesetti.nextplayer.action.OPENLIST_START"
        private const val ACTION_STOP = "dev.anilbeesetti.nextplayer.action.OPENLIST_STOP"

        fun start(context: Context) {
            val intent = Intent(context, OpenListService::class.java).apply { action = ACTION_START }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Toast.makeText(context, "OpenList 正在启动…", Toast.LENGTH_SHORT).show()
        }

        fun stop(context: Context) {
            val intent = Intent(context, OpenListService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
            Toast.makeText(context, "OpenList 已停止", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        // START_STICKY 重建时 intent 可能为 null — 此时也应恢复前台通知
        if (action == null || action == ACTION_START) {
            startForeground(NOTIFICATION_ID, buildNotification("OpenList 正在运行"))
            if (action == ACTION_START) {
                val manager = OpenListManagerProvider.get()
                if (manager != null) {
                    manager.start()
                    // 获取端口和IP，更新通知内容
                    val port = getServerPort(manager)
                    val lanIp = getLanIp() ?: "127.0.0.1"
                    val notification = buildNotification(
                        "运行于 http://$lanIp:$port  |  http://127.0.0.1:$port"
                    )
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(NOTIFICATION_ID, notification)
                    Toast.makeText(this, "OpenList 已启动", Toast.LENGTH_SHORT).show()
                }
            }
        } else if (action == ACTION_STOP) {
            val manager = OpenListManagerProvider.get()
            if (manager != null) {
                manager.stop()
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_STICKY
    }

    /** 从 OpenListManager 的 state 中读取端口号，默认 5244 */
    private fun getServerPort(manager: OpenListManager): Int {
        val s = manager.state.value
        return if (s is OpenListServerState.Running) s.port else 5244
    }

    /** 获取设备局域网 IPv4 地址（非回环、非虚拟接口） */
    private fun getLanIp(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        // 过滤常见的虚拟网络接口（VPN、容器等）
                        if (host.startsWith("192.168.") || host.startsWith("10.") ||
                            host.startsWith("172.") || host.startsWith("100.")
                        ) return host
                    }
                }
            }
        } catch (_: Exception) {
            // 忽略权限不足等异常
        }
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OpenList 服务",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "OpenList 本地文件服务器"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): android.app.Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager?.getLaunchIntentForPackage(packageName!!),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenList")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
