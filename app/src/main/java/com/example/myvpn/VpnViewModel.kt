package com.example.myvpn

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class Server(
    val name: String,
    val type: String,               // "WG" أو "SSH" أو "V2RAY"
    val config: String = "",        // إعداد WireGuard
    val host: String = "",
    val port: Int = 22,
    val user: String = "",
    val password: String = "",
    val connectHost: String = "",   // عنوان الوسيط/CDN (اختياري)
    val connectPort: Int = 0,       // 0 = تلقائي (80 أو 443)
    val tls: Boolean = false,
    val sni: String = "",
    val payload: String = "",
    val sub: String = ""            // رابط الاشتراك الذي جاء منه السيرفر (فارغ = يدوي)
)

enum class VpnStatus { DISCONNECTED, CONNECTING, CONNECTED }

class VpnViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("servers", 0)
    private val backend = GoBackend(app)

    private val wgTunnel = object : Tunnel {
        override fun getName() = "redovpn"
        override fun onStateChange(newState: Tunnel.State) {
            wgStatus = if (newState == Tunnel.State.UP) VpnStatus.CONNECTED else VpnStatus.DISCONNECTED
        }
    }

    val servers = mutableStateListOf<Server>()
    var selected by mutableStateOf(0)
    private var wgStatus by mutableStateOf(VpnStatus.DISCONNECTED)
    private var localError by mutableStateOf<String?>(null)

    val status: VpnStatus
        get() = when {
            V2rayManager.status != VpnStatus.DISCONNECTED -> V2rayManager.status
            SshManager.status != VpnStatus.DISCONNECTED -> SshManager.status
            else -> wgStatus
        }
    val error: String?
        get() = localError ?: SshManager.error ?: V2rayManager.error

    // الإعدادات
    var socksPort by mutableStateOf(prefs.getInt("socksPort", 1080))
    var keepAlive by mutableStateOf(prefs.getInt("keepAlive", 15))

    val selectedIsWireGuard get() = servers.getOrNull(selected)?.type == "WG"
    val activeSsh get() = servers.getOrNull(selected)?.type == "SSH" && status == VpnStatus.CONNECTED
    val activeV2ray get() = servers.getOrNull(selected)?.type == "V2RAY" && status == VpnStatus.CONNECTED

    // الاشتراك
    private fun initialSubUrl(): String {
        val saved = prefs.getString("subUrl", "") ?: ""
        return if (AppConfig.SUB_URL.isNotBlank() && (AppConfig.LOCK_SUB || saved.isBlank())) {
            AppConfig.SUB_URL
        } else saved
    }

    var subUrl by mutableStateOf(initialSubUrl())
    var subAuto by mutableStateOf(prefs.getBoolean("subAuto", true))
    var subBusy by mutableStateOf(false)
    var subMessage by mutableStateOf<String?>(null)

    init {
        loadServers()
        if (subAuto && subUrl.isNotBlank()) updateSubscription()
    }

    fun saveSubscription(url: String, auto: Boolean) {
        val locked = AppConfig.LOCK_SUB && AppConfig.SUB_URL.isNotBlank()
        subUrl = if (locked) AppConfig.SUB_URL else url.trim()
        subAuto = auto
        prefs.edit().putString("subUrl", subUrl).putBoolean("subAuto", subAuto).apply()
    }

    fun updateSubscription() {
        val url = subUrl.trim()
        if (url.isBlank()) { subMessage = "أدخل رابط الاشتراك أولاً"; return }
        if (!url.startsWith("https://", true)) { subMessage = "الرابط يجب أن يبدأ بـ https://"; return }
        if (status != VpnStatus.DISCONNECTED) { subMessage = "اقطع الاتصال قبل تحديث السيرفرات"; return }
        if (subBusy) return
        subBusy = true
        subMessage = "جارٍ التحديث..."
        AppLog.add("الاشتراك: جارٍ التحديث")
        viewModelScope.launch {
            try {
                val links = withContext(Dispatchers.IO) { fetchLinks(url) }
                val prev = servers.getOrNull(selected)
                servers.removeAll { it.sub == url }
                links.forEachIndexed { i, link ->
                    val name = V2rayManager.nameOf(link).ifBlank { "سيرفر ${i + 1}" }
                    servers.add(Server(name = name, type = "V2RAY", config = link, sub = url))
                }
                selected = servers.indexOf(prev).takeIf { it >= 0 } ?: 0
                saveServers()
                subMessage = "تم تحديث ${links.size} سيرفر ✔"
                AppLog.add("الاشتراك: تمت إضافة ${links.size} سيرفر")
            } catch (e: Exception) {
                subMessage = "فشل التحديث: ${e.message}"
                AppLog.add("الاشتراك: فشل — ${e.message}")
            } finally {
                subBusy = false
            }
        }
    }

    private fun fetchLinks(url: String): List<String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 20000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "RedoVPN/1.0")
        }
        try {
            if (conn.responseCode !in 200..299) throw IOException("رد الخادم: ${conn.responseCode}")
            val out = ByteArrayOutputStream()
            conn.inputStream.use { s ->
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > 2_000_000) throw IOException("الملف كبير جداً")
                    out.write(buf, 0, n)
                }
            }
            val links = V2rayManager.parseSubscription(String(out.toByteArray(), Charsets.UTF_8))
            if (links.isEmpty()) throw IOException("لا توجد روابط vless/vmess/trojan في الملف")
            return links
        } finally {
            conn.disconnect()
        }
    }

    fun saveSettings(port: Int, keepAliveSec: Int) {
        socksPort = port.coerceIn(1024, 65535)
        keepAlive = keepAliveSec.coerceIn(5, 120)
        prefs.edit().putInt("socksPort", socksPort).putInt("keepAlive", keepAlive).apply()
    }

    fun connect() {
        val server = servers.getOrNull(selected)
        if (server == null) { localError = "أضف سيرفراً أولاً"; return }
        localError = null
        if (server.type == "SSH") {
            SshManager.connect(getApplication<Application>(), server, socksPort, keepAlive)
            return
        }
        if (server.type == "V2RAY") {
            V2rayManager.connect(getApplication<Application>(), server.config, server.name)
            return
        }
        wgStatus = VpnStatus.CONNECTING
        AppLog.add("WireGuard: جارٍ الاتصال (${server.name})")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cfg = Config.parse(server.config.byteInputStream())
                backend.setState(wgTunnel, Tunnel.State.UP, cfg)
                wgStatus = VpnStatus.CONNECTED
                AppLog.add("WireGuard: تم الاتصال ✔")
            } catch (e: Exception) {
                wgStatus = VpnStatus.DISCONNECTED
                localError = "فشل الاتصال: ${e.message}"
                AppLog.add("WireGuard: فشل — ${e.message}")
            }
        }
    }

    fun disconnect() {
        if (SshManager.status != VpnStatus.DISCONNECTED) SshManager.disconnect(getApplication<Application>())
        if (V2rayManager.status != VpnStatus.DISCONNECTED) {
            viewModelScope.launch(Dispatchers.IO) { V2rayManager.disconnect() }
        }
        if (wgStatus != VpnStatus.DISCONNECTED) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    backend.setState(wgTunnel, Tunnel.State.DOWN, null)
                } catch (e: Exception) {
                    localError = e.message
                }
                wgStatus = VpnStatus.DISCONNECTED
                AppLog.add("WireGuard: تم قطع الاتصال")
            }
        }
    }

    fun onPermissionDenied() {
        wgStatus = VpnStatus.DISCONNECTED
        localError = "لم تتم الموافقة على إذن VPN"
    }

    fun addServer(s: Server) {
        if (s.type == "WG") {
            try {
                Config.parse(s.config.byteInputStream())
            } catch (e: Exception) {
                localError = "إعداد WireGuard غير صالح: ${e.message}"
                return
            }
        } else if (s.type == "SSH" && (s.host.isBlank() || s.user.isBlank())) {
            localError = "أدخل عنوان السيرفر واسم المستخدم"
            return
        } else if (s.type == "V2RAY" && !V2rayManager.isSupportedLink(s.config)) {
            localError = "رابط V2Ray غير صالح. استعمل vless:// أو vmess:// أو trojan://"
            return
        }
        localError = null
        servers.add(s.copy(name = s.name.ifBlank { "سيرفر ${servers.size + 1}" }))
        saveServers()
    }

    fun removeServer(index: Int) {
        if (index !in servers.indices) return
        servers.removeAt(index)
        if (selected >= servers.size) selected = (servers.size - 1).coerceAtLeast(0)
        saveServers()
    }

    private fun saveServers() {
        val arr = JSONArray()
        servers.forEach {
            arr.put(JSONObject()
                .put("name", it.name).put("type", it.type).put("config", it.config)
                .put("host", it.host).put("port", it.port)
                .put("user", it.user).put("password", it.password)
                .put("connectHost", it.connectHost).put("connectPort", it.connectPort)
                .put("tls", it.tls).put("sni", it.sni).put("payload", it.payload)
                .put("sub", it.sub))
        }
        prefs.edit().putString("list", arr.toString()).apply()
    }

    private fun loadServers() {
        val raw = prefs.getString("list", null) ?: return
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            servers.add(Server(
                o.getString("name"), o.getString("type"), o.optString("config"),
                o.optString("host"), o.optInt("port", 22),
                o.optString("user"), o.optString("password"),
                o.optString("connectHost"), o.optInt("connectPort", 0),
                o.optBoolean("tls", false), o.optString("sni"), o.optString("payload"),
                o.optString("sub")
            ))
        }
    }
}
