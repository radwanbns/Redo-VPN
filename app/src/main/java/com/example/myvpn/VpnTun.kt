package com.example.myvpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import hev.htproxy.TProxyService
import java.io.File
import kotlin.concurrent.thread

/**
 * يفتح واجهة VPN حقيقية (مفتاح VPN) ويمرر كل حركة الهاتف عبر SOCKS5 المحلي
 * الخاص بمحرك V2Ray باستخدام hev-socks5-tunnel (tun2socks).
 */
class RedoVpnService : VpnService() {

    private var pfd: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            teardown()
            stopSelf()
            return START_NOT_STICKY
        }
        if (pfd == null) establishTunnel()
        return START_NOT_STICKY
    }

    private fun establishTunnel() {
        try {
            val builder = Builder()
                .setSession("Redo VPN")
                .setMtu(MTU)
                .addAddress("198.18.0.2", 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
            try {
                builder.addAddress("fc00::2", 126)
                builder.addRoute("::", 0)
            } catch (_: Exception) {
            }
            // استبعاد التطبيق نفسه حتى لا تدخل اتصالات المحرك في حلقة
            builder.addDisallowedApplication(packageName)

            val fd = builder.establish()
                ?: throw IllegalStateException("رفض النظام إنشاء الواجهة (تحقق من إذن VPN)")
            pfd = fd

            val cfg = File(filesDir, "hev-socks5-tunnel.yml")
            cfg.writeText(
                """
                tunnel:
                  mtu: $MTU
                  ipv4: 198.18.0.1
                  ipv6: 'fc00::1'
                socks5:
                  port: 10808
                  address: 127.0.0.1
                  udp: 'udp'
                misc:
                  log-level: warn
                """.trimIndent()
            )
            val rawFd = fd.fd
            thread(isDaemon = true) {
                try {
                    TProxyService.TProxyStartService(cfg.absolutePath, rawFd)
                } catch (t: Throwable) {
                    V2rayManager.onVpnFailed("فشل تشغيل نفق VPN: ${t.message}")
                }
            }
            AppLog.add("VPN: النفق يعمل ✔ (كل حركة الهاتف تمر عبر V2Ray)")
        } catch (t: Throwable) {
            teardown()
            V2rayManager.onVpnFailed("فشل تشغيل نفق VPN: ${t.message}")
            stopSelf()
        }
    }

    private fun teardown() {
        try { TProxyService.TProxyStopService() } catch (_: Throwable) {}
        try { pfd?.close() } catch (_: Exception) {}
        pfd = null
    }

    override fun onRevoke() {
        teardown()
        thread(isDaemon = true) { V2rayManager.disconnect() }
        super.onRevoke()
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "STOP"
        const val MTU = 1500

        fun start(ctx: Context) {
            ctx.startService(Intent(ctx, RedoVpnService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, RedoVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}
