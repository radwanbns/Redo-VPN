package com.example.myvpn

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Proxy
import java.net.URLDecoder

/** V2Ray/Xray core bridge. The GitHub workflow builds libv2ray.aar from AndroidLibV2rayLite. */
object V2rayManager {
    var status by mutableStateOf(VpnStatus.DISCONNECTED)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var controller: Any? = null

    fun isSupportedLink(link: String): Boolean = try {
        val s = link.trim()
        s.startsWith("vless://", true) || s.startsWith("vmess://", true) || s.startsWith("trojan://", true)
    } catch (_: Exception) { false }

    @Synchronized fun connect(context: Context, rawLink: String) {
        if (status == VpnStatus.CONNECTING || status == VpnStatus.CONNECTED) disconnect()
        error = null
        status = VpnStatus.CONNECTING
        AppLog.add("V2Ray: جارٍ إعداد الاتصال")
        try {
            val config = configFromLink(rawLink.trim())
            val libClass = Class.forName("libv2ray.Libv2ray")
            val init = libClass.methods.firstOrNull { it.name.equals("initCoreEnv", true) && it.parameterTypes.size == 2 }
            init?.invoke(null, context.filesDir.absolutePath, "")
            val callbackClass = Class.forName("libv2ray.CoreCallbackHandler")
            val callback = Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass)) { _, method, args ->
                when (method.name.lowercase()) {
                    "startup" -> { AppLog.add("V2Ray: بدأ المحرك"); 0 }
                    "shutdown" -> { AppLog.add("V2Ray: توقف المحرك"); 0 }
                    "onemitstatus" -> {
                        val msg = args?.getOrNull(1)?.toString().orEmpty()
                        if (msg.contains("error", true) || msg.contains("fail", true)) error = msg
                        0
                    }
                    else -> 0
                }
            }
            val factory = libClass.methods.firstOrNull { it.name.equals("newCoreController", true) && it.parameterTypes.size == 1 }
                ?: kotlin.error("واجهة محرك V2Ray غير متوافقة")
            val core = factory.invoke(null, callback) ?: kotlin.error("تعذر إنشاء محرك V2Ray")
            val start = core.javaClass.methods.firstOrNull { it.name.equals("startLoop", true) && it.parameterTypes.size == 1 }
                ?: kotlin.error("دالة تشغيل V2Ray غير موجودة")
            start.invoke(core, config)
            controller = core
            status = VpnStatus.CONNECTED
            AppLog.add("V2Ray: تم تشغيل النفق ✔ SOCKS5 على 127.0.0.1:10808")
        } catch (e: Exception) {
            controller = null
            status = VpnStatus.DISCONNECTED
            val cause = e.cause ?: e
            error = "فشل تشغيل V2Ray: ${cause.message ?: cause.javaClass.simpleName}. تأكد من بناء libv2ray.aar"
            AppLog.add(error!!)
        }
    }

    @Synchronized fun disconnect() {
        try {
            controller?.let { c ->
                c.javaClass.methods.firstOrNull { it.name.equals("stopLoop", true) && it.parameterTypes.isEmpty() }?.invoke(c)
            }
        } catch (e: Exception) {
            AppLog.add("V2Ray: ${e.cause?.message ?: e.message}")
        } finally {
            controller = null
            status = VpnStatus.DISCONNECTED
            AppLog.add("V2Ray: تم قطع الاتصال")
        }
    }

    private fun configFromLink(link: String): String {
        val outbound = when {
            link.startsWith("vmess://", true) -> vmessOutbound(link)
            link.startsWith("vless://", true) -> vlessOutbound(link)
            link.startsWith("trojan://", true) -> trojanOutbound(link)
            else -> throw IllegalArgumentException("نوع الرابط غير مدعوم")
        }
        val inbounds = JSONArray().put(JSONObject()
            .put("tag", "socks-in")
            .put("listen", "127.0.0.1")
            .put("port", 10808)
            .put("protocol", "socks")
            .put("settings", JSONObject().put("auth", "noauth").put("udp", true)))
        val outbounds = JSONArray().put(outbound).put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", inbounds)
            .put("outbounds", outbounds)
            .put("routing", JSONObject().put("domainStrategy", "AsIs").put("rules", JSONArray()
                .put(JSONObject().put("type", "field").put("inboundTag", JSONArray().put("socks-in")).put("outboundTag", "proxy"))))
            .toString()
    }

    private fun vmessOutbound(link: String): JSONObject {
        val encoded = link.substringAfter("vmess://").substringBefore('#')
        val decoded = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
        val o = JSONObject(decoded)
        val host = o.optString("add")
        val port = o.optInt("port", 443)
        val id = o.optString("id")
        require(host.isNotBlank() && id.isNotBlank()) { "بيانات VMess ناقصة" }
        val user = JSONObject().put("id", id).put("alterId", o.optInt("aid", 0)).put("security", o.optString("scy", "auto"))
        val outbound = JSONObject().put("tag", "proxy").put("protocol", "vmess")
            .put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject().put("address", host).put("port", port).put("users", JSONArray().put(user)))))
        val q = JSONObject()
        val net = o.optString("net", "tcp")
        val tls = o.optString("tls", "")
        val sni = o.optString("sni", o.optString("host", host))
        outbound.put("streamSettings", streamSettings(net, tls, sni, o.optString("path", "/"), o.optString("host", ""), "", "", "", ""))
        return outbound
    }

    private fun vlessOutbound(link: String): JSONObject {
        val uri = Uri.parse(link)
        val host = uri.host ?: throw IllegalArgumentException("عنوان VLESS ناقص")
        val id = uri.userInfo?.substringBefore(':') ?: throw IllegalArgumentException("UUID ديال VLESS ناقص")
        val p = query(uri)
        val user = JSONObject().put("id", id).put("encryption", p["encryption"] ?: "none")
        p["flow"]?.takeIf { it.isNotBlank() }?.let { user.put("flow", it) }
        val outbound = JSONObject().put("tag", "proxy").put("protocol", "vless")
            .put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject().put("address", host).put("port", uri.port.takeIf { it > 0 } ?: 443).put("users", JSONArray().put(user)))))
        outbound.put("streamSettings", streamSettings(p["type"] ?: "tcp", p["security"] ?: "", p["sni"] ?: host, p["path"] ?: "/", p["host"] ?: "", p["serviceName"] ?: "", p["mode"] ?: "", p["pbk"] ?: "", p["sid"] ?: "", p["fp"] ?: ""))
        return outbound
    }

    private fun trojanOutbound(link: String): JSONObject {
        val uri = Uri.parse(link)
        val host = uri.host ?: throw IllegalArgumentException("عنوان Trojan ناقص")
        val password = uri.userInfo?.let { decode(it) } ?: throw IllegalArgumentException("كلمة مرور Trojan ناقصة")
        val p = query(uri)
        val outbound = JSONObject().put("tag", "proxy").put("protocol", "trojan")
            .put("settings", JSONObject().put("servers", JSONArray().put(JSONObject().put("address", host).put("port", uri.port.takeIf { it > 0 } ?: 443).put("password", password))))
        outbound.put("streamSettings", streamSettings(p["type"] ?: "tcp", p["security"] ?: "tls", p["sni"] ?: host, p["path"] ?: "/", p["host"] ?: "", p["serviceName"] ?: "", p["mode"] ?: "", p["pbk"] ?: "", p["sid"] ?: "", p["fp"] ?: ""))
        return outbound
    }

    private fun streamSettings(network: String, security: String, sni: String, path: String, hostHeader: String,
                               serviceName: String, mode: String, publicKey: String, shortId: String, fingerprint: String = ""): JSONObject {
        val net = network.lowercase().let { if (it == "h2") "http" else it }
        val out = JSONObject().put("network", net)
        if (security.equals("tls", true)) {
            out.put("security", "tls").put("tlsSettings", JSONObject().put("serverName", sni)
                .put("allowInsecure", false).put("fingerprint", fingerprint.ifBlank { "chrome" }))
        } else if (security.equals("reality", true)) {
            val reality = JSONObject().put("serverName", sni).put("fingerprint", fingerprint.ifBlank { "chrome" })
            if (publicKey.isNotBlank()) reality.put("publicKey", publicKey)
            if (shortId.isNotBlank()) reality.put("shortId", shortId)
            out.put("security", "reality").put("realitySettings", reality)
        } else out.put("security", "none")
        when (net) {
            "ws" -> {
                val headers = JSONObject()
                if (hostHeader.isNotBlank()) headers.put("Host", hostHeader)
                out.put("wsSettings", JSONObject().put("path", path).put("headers", headers))
            }
            "grpc" -> out.put("grpcSettings", JSONObject().put("serviceName", serviceName.ifBlank { path.trim('/') }).put("multiMode", mode.equals("multi", true)))
            "http" -> out.put("httpSettings", JSONObject().put("path", path).put("host", if (hostHeader.isBlank()) JSONArray() else JSONArray().put(hostHeader)))
        }
        return out
    }

    private fun query(uri: Uri): Map<String, String> {
        val result = mutableMapOf<String, String>()
        uri.encodedQuery?.split('&')?.forEach { pair ->
            val k = pair.substringBefore('=')
            val v = pair.substringAfter('=', "")
            result[decode(k)] = decode(v)
        }
        return result
    }

    private fun decode(s: String): String = try { URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }
}
