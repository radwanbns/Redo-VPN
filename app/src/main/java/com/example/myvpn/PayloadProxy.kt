package com.example.myvpn

import com.jcraft.jsch.Proxy
import com.jcraft.jsch.SocketFactory
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * يتصل بسيرفر الوسيط (WebSocket/HTTP) ويرسل الـ Payload ثم يمرر SSH داخل نفس الاتصال.
 * المتغيرات المدعومة في الـ Payload: [host] [port] [crlf] [cr] [lf]
 */
class PayloadProxy(
    private val connectHost: String,
    private val connectPort: Int,
    private val useTls: Boolean,
    private val sni: String,
    private val payload: String
) : Proxy {

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override fun connect(factory: SocketFactory?, host: String, port: Int, timeout: Int) {
        val raw = Socket()
        raw.connect(InetSocketAddress(connectHost, connectPort), if (timeout > 0) timeout else 15000)
        raw.tcpNoDelay = true
        AppLog.add("تم الاتصال بالوسيط $connectHost:$connectPort")

        val s: Socket = if (useTls) {
            val name = sni.ifBlank { connectHost }
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(raw, name, connectPort, true) as SSLSocket
            val params = ssl.sslParameters
            params.serverNames = listOf(SNIHostName(name))
            ssl.sslParameters = params
            ssl.startHandshake()
            AppLog.add("TLS ناجح (SNI: $name)")
            ssl
        } else raw

        socket = s
        input = s.getInputStream()
        output = s.getOutputStream()

        if (payload.isNotBlank()) {
            val text = payload
                .replace("[host]", host)
                .replace("[port]", port.toString())
                .replace("[crlf]", "\r\n")
                .replace("[cr]", "\r")
                .replace("[lf]", "\n")
            AppLog.add("إرسال Payload (${text.length} حرف)")
            output!!.write(text.toByteArray(Charsets.ISO_8859_1))
            output!!.flush()

            val header = readHeader(input!!)
            val status = header.lineSequence().first()
            AppLog.add("رد الوسيط: $status")
            if (!status.contains(" 101") && !status.contains(" 200")) {
                close()
                throw IOException("رد الوسيط: $status")
            }
        }
    }

    private fun readHeader(i: InputStream): String {
        val sb = StringBuilder()
        while (!sb.endsWith("\r\n\r\n")) {
            val b = i.read()
            if (b < 0) throw IOException("أُغلق الاتصال قبل اكتمال الرد")
            sb.append(b.toChar())
            if (sb.length > 8192) throw IOException("رد الوسيط كبير جداً")
        }
        return sb.toString()
    }

    override fun getInputStream(): InputStream = input!!
    override fun getOutputStream(): OutputStream = output!!
    override fun getSocket(): Socket? = socket
    override fun close() {
        try { input?.close() } catch (_: Exception) {}
        try { output?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
    }
}
