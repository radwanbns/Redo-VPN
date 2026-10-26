package com.example.myvpn

import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** يفتح اتصال SSH ويشغّل خادم SOCKS5 محلي يمرر كل الاتصالات عبره (مثل ssh -D). */
class SshSocksTunnel {
    private var session: Session? = null
    private var server: ServerSocket? = null
    @Volatile private var running = false

    val isRunning get() = running && session?.isConnected == true

    fun start(host: String, port: Int, user: String, password: String, localPort: Int = 1080) {
        stop()
        val s = JSch().getSession(user, host, port)
        s.setPassword(password)
        s.setConfig("StrictHostKeyChecking", "no")
        s.setServerAliveInterval(15000)
        s.connect(15000)
        session = s

        val ss = ServerSocket(localPort, 50, InetAddress.getByName("127.0.0.1"))
        server = ss
        running = true
        thread(isDaemon = true) {
            while (running) {
                val client = try { ss.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) { handle(client) }
            }
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        try { session?.disconnect() } catch (_: Exception) {}
        server = null
        session = null
    }

    private fun handle(client: Socket) {
        try {
            val input = DataInputStream(client.getInputStream())
            val output = client.getOutputStream()

            if (input.read() != 5) return
            val methods = input.read()
            input.skipBytes(methods)
            output.write(byteArrayOf(5, 0)); output.flush()

            input.read() // version
            val cmd = input.read()
            input.read() // reserved
            val atyp = input.read()
            val host = when (atyp) {
                1 -> InetAddress.getByAddress(ByteArray(4).also { input.readFully(it) }).hostAddress
                3 -> String(ByteArray(input.read()).also { input.readFully(it) })
                4 -> InetAddress.getByAddress(ByteArray(16).also { input.readFully(it) }).hostAddress
                else -> null
            }
            val port = (input.read() shl 8) or input.read()
            if (cmd != 1 || host == null) {
                output.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0)); return
            }

            val ch = session!!.openChannel("direct-tcpip") as ChannelDirectTCPIP
            ch.setHost(host)
            ch.setPort(port)
            ch.setInputStream(input)
            ch.setOutputStream(output)
            try {
                ch.connect(10000)
            } catch (e: Exception) {
                output.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0)); return
            }
            output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); output.flush()

            while (!ch.isClosed && !client.isClosed && running) Thread.sleep(200)
            ch.disconnect()
        } catch (_: Exception) {
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }
}
