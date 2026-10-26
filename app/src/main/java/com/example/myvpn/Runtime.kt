package com.example.myvpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** سجل الأحداث الظاهر في صفحة السجل. */
object AppLog {
    val lines = mutableStateListOf<String>()
    private val main = Handler(Looper.getMainLooper())

    fun add(msg: String) {
        val line = "${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}  $msg"
        main.post {
            lines.add(line)
            if (lines.size > 500) lines.removeAt(0)
        }
    }

    fun clear() { main.post { lines.clear() } }
}

/** يدير نفق SSH خارج الواجهة حتى يبقى يعمل عند إغلاق الشاشة. */
object SshManager {
    private val tunnel = SshSocksTunnel()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watcher: Job? = null

    var status by mutableStateOf(VpnStatus.DISCONNECTED)
    var error by mutableStateOf<String?>(null)

    fun connect(ctx: Context, server: Server, socksPort: Int, keepAlive: Int) {
        val app = ctx.applicationContext
        error = null
        status = VpnStatus.CONNECTING
        AppLog.add("بدء الاتصال: ${server.name}")
        scope.launch {
            try {
                tunnel.start(server, socksPort, keepAlive)
                status = VpnStatus.CONNECTED
                AppLog.add("تم الاتصال ✔")
                ContextCompat.startForegroundService(
                    app, Intent(app, TunnelService::class.java).putExtra("name", server.name)
                )
                watcher?.cancel()
                watcher = scope.launch {
                    while (isActive) {
                        delay(3000)
                        if (!tunnel.isRunning) {
                            AppLog.add("انقطع اتصال SSH")
                            tunnel.stop()
                            status = VpnStatus.DISCONNECTED
                            error = "انقطع الاتصال"
                            app.stopService(Intent(app, TunnelService::class.java))
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                tunnel.stop()
                status = VpnStatus.DISCONNECTED
                error = "فشل الاتصال: ${e.message}"
                AppLog.add("فشل: ${e.message}")
            }
        }
    }

    fun disconnect(ctx: Context) {
        val app = ctx.applicationContext
        watcher?.cancel()
        scope.launch {
            tunnel.stop()
            status = VpnStatus.DISCONNECTED
            AppLog.add("تم قطع الاتصال")
        }
        app.stopService(Intent(app, TunnelService::class.java))
    }
}

/** خدمة أمامية تعرض إشعار "متصل" وتبقي التطبيق حياً. */
class TunnelService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            SshManager.disconnect(this)
            stopSelf()
            return START_NOT_STICKY
        }
        val name = intent?.getStringExtra("name") ?: "SSH"

        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "حالة الاتصال", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TunnelService::class.java).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Redo VPN")
            .setContentText("متصل — $name")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(open)
            .addAction(0, "قطع الاتصال", stop)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
        return START_NOT_STICKY
    }

    companion object { const val CHANNEL = "tunnel" }
}
