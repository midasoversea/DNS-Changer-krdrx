package id.krdrx.dnschanger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

class DnsVpnService : VpnService() {
    @Volatile private var running = false
    private var tun: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopVpn(); return START_NOT_STICKY }
            ACTION_START -> startVpn(intent.getStringExtra(EXTRA_DNS) ?: "1.1.1.1", intent.getStringExtra(EXTRA_MODE) ?: "UDP", intent.getStringExtra(EXTRA_HOST) ?: "")
            else -> {
                val p = getSharedPreferences(PREFS, MODE_PRIVATE)
                val saved = p.getString(KEY_ACTIVE_DNS, null)
                if (saved != null && !running) startVpn(saved, p.getString(KEY_ACTIVE_MODE, "UDP") ?: "UDP", p.getString(KEY_ACTIVE_HOST, "") ?: "")
            }
        }
        return START_STICKY
    }

    private fun startVpn(dns: String, mode: String, host: String) {
        if (running) stopVpn()
        try {
            startForeground(NOTIFICATION_ID, buildNotification(dns, mode))
            val bootstrap = InetAddress.getByName(dns)
            tun = Builder().setSession("DNS Changer").setMtu(1500).addAddress("10.111.0.1", 32)
                .addDnsServer(bootstrap).addRoute(bootstrap, 32).establish()
                ?: run { stopForeground(STOP_FOREGROUND_REMOVE); return }
            running = true
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_ACTIVE_DNS, dns)
                .putString(KEY_ACTIVE_MODE, mode).putString(KEY_ACTIVE_HOST, host).putBoolean(KEY_RUNNING, true).apply()
            thread(name = "dns-forwarder") {
                val fd = tun?.fileDescriptor ?: return@thread
                val input = FileInputStream(fd)
                val output = FileOutputStream(fd)
                val buffer = ByteArray(32767)
                val udp = DatagramSocket()
                try {
                    if (!protect(udp)) error("Tidak dapat mengecualikan socket DNS dari VPN")
                    udp.soTimeout = 5000
                    while (running) {
                        val length = input.read(buffer)
                        if (length < 28) continue
                        val ihl = (buffer[0].toInt() and 15) * 4
                        if (ihl < 20 || (buffer[9].toInt() and 255) != 17) continue
                        val total = ((buffer[2].toInt() and 255) shl 8) or (buffer[3].toInt() and 255)
                        val u = ihl
                        if (total > length || total < ihl + 8) continue
                        val port = ((buffer[u + 2].toInt() and 255) shl 8) or (buffer[u + 3].toInt() and 255)
                        if (port != 53) continue
                        val size = total - ihl - 8
                        if (size < 12) continue
                        val query = buffer.copyOfRange(u + 8, u + 8 + size)
                        try {
                            val response = when (mode) {
                                "DoT" -> queryTls(bootstrap, host, query)
                                "DoH" -> queryHttps(bootstrap, host, query)
                                else -> queryUdp(udp, bootstrap, query)
                            }
                            output.write(buildResponse(buffer, ihl, u, response))
                        } catch (_: Exception) { }
                    }
                } catch (_: Exception) {
                } finally { udp.close(); stopVpn() }
            }
        } catch (_: Exception) { stopVpn() }
    }

    private fun queryUdp(socket: DatagramSocket, server: InetAddress, query: ByteArray): ByteArray {
        socket.send(DatagramPacket(query, query.size, server, 53))
        val data = ByteArray(4096)
        val packet = DatagramPacket(data, data.size)
        socket.receive(packet)
        return data.copyOf(packet.length)
    }

    private fun tlsSocket(ip: InetAddress, host: String, port: Int): SSLSocket {
        require(host.isNotBlank()) { "Host DNS terenkripsi kosong" }
        val s = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket() as SSLSocket
        if (!protect(s)) { s.close(); error("Gagal mengecualikan socket TLS dari VPN") }
        s.connect(InetSocketAddress(ip, port), 7000)
        s.soTimeout = 7000
        val p: SSLParameters = s.sslParameters
        p.serverNames = listOf(SNIHostName(host))
        p.endpointIdentificationAlgorithm = "HTTPS"
        s.sslParameters = p
        s.startHandshake()
        return s
    }

    private fun queryTls(ip: InetAddress, host: String, query: ByteArray): ByteArray =
        tlsSocket(ip, host, 853).use { s ->
            val o = s.outputStream
            o.write(query.size ushr 8); o.write(query.size and 255); o.write(query); o.flush()
            val input = s.inputStream
            val hi = input.read(); val lo = input.read()
            if (hi < 0 || lo < 0) error("Respons DoT terputus")
            readFully(input, (hi shl 8) or lo)
        }

    private fun queryHttps(ip: InetAddress, host: String, query: ByteArray): ByteArray =
        tlsSocket(ip, host, 443).use { s ->
            val o = s.outputStream
            val h = "POST /dns-query HTTP/1.1\r\nHost: $host\r\nContent-Type: application/dns-message\r\nAccept: application/dns-message\r\nContent-Length: ${query.size}\r\nConnection: close\r\n\r\n"
            o.write(h.toByteArray(Charsets.US_ASCII)); o.write(query); o.flush()
            val input = s.inputStream
            val b = ByteArrayOutputStream()
            var state = 0
            while (b.size() < 16384 && state < 4) {
                val x = input.read()
                if (x < 0) error("Header DoH terputus")
                b.write(x)
                state = when {
                    state == 0 && x == 13 -> 1
                    state == 1 && x == 10 -> 2
                    state == 2 && x == 13 -> 3
                    state == 3 && x == 10 -> 4
                    x == 13 -> 1
                    else -> 0
                }
            }
            val headers = b.toString(Charsets.US_ASCII.name())
            if (!headers.startsWith("HTTP/1.1 200") && !headers.startsWith("HTTP/1.0 200")) error("Server DoH tidak mengembalikan HTTP 200")
            val n = headers.lineSequence().firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(":")?.trim()?.toIntOrNull()
                ?: error("Content-Length DoH tidak ditemukan")
            readFully(input, n)
        }

    private fun readFully(input: java.io.InputStream, size: Int): ByteArray {
        require(size in 12..65535) { "Ukuran respons DNS tidak valid" }
        val data = ByteArray(size); var offset = 0
        while (offset < size) {
            val n = input.read(data, offset, size - offset)
            if (n < 0) error("Respons DNS tidak lengkap")
            offset += n
        }
        return data
    }

    private fun buildNotification(dns: String, mode: String): Notification {
        val id = "dns_vpn_status"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(id, "DNS VPN", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, DnsVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, id) else Notification.Builder(this)
        return b.setSmallIcon(android.R.drawable.stat_sys_warning).setContentTitle("DNS Changer aktif")
            .setContentText("$mode melalui $dns").setContentIntent(open).setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Putuskan", stop).build()
    }

    private fun buildResponse(original: ByteArray, ihl: Int, u: Int, dns: ByteArray): ByteArray {
        val out = ByteArray(ihl + 8 + dns.size)
        original.copyInto(out, 0, 0, ihl)
        for (i in 0..3) { out[12 + i] = original[16 + i]; out[16 + i] = original[12 + i] }
        out[2] = (out.size ushr 8).toByte(); out[3] = out.size.toByte()
        out[10] = 0; out[11] = 0
        val sum = checksum(out, 0, ihl); out[10] = (sum ushr 8).toByte(); out[11] = sum.toByte()
        out[u] = original[u + 2]; out[u + 1] = original[u + 3]
        out[u + 2] = original[u]; out[u + 3] = original[u + 1]
        val n = 8 + dns.size; out[u + 4] = (n ushr 8).toByte(); out[u + 5] = n.toByte()
        out[u + 6] = 0; out[u + 7] = 0; dns.copyInto(out, u + 8)
        return out
    }

    private fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L; var i = offset
        while (i < offset + length) {
            val hi = data[i].toInt() and 255
            val lo = if (i + 1 < offset + length) data[i + 1].toInt() and 255 else 0
            sum += (hi shl 8) or lo; i += 2
        }
        while ((sum ushr 16) != 0L) sum = (sum and 65535) + (sum ushr 16)
        return sum.inv().toInt() and 65535
    }

    private fun stopVpn() {
        running = false
        try { tun?.close() } catch (_: Exception) { }
        tun = null
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, false)
            .remove(KEY_ACTIVE_DNS).remove(KEY_ACTIVE_MODE).remove(KEY_ACTIVE_HOST).apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() { running = false; try { tun?.close() } catch (_: Exception) { }; super.onDestroy() }

    companion object {
        const val ACTION_START = "id.krdrx.dnschanger.START"
        const val ACTION_STOP = "id.krdrx.dnschanger.STOP"
        const val EXTRA_DNS = "dns"
        const val EXTRA_MODE = "mode"
        const val EXTRA_HOST = "host"
        const val PREFS = "dns_profiles"
        const val KEY_ACTIVE_DNS = "active_dns"
        const val KEY_ACTIVE_MODE = "active_mode"
        const val KEY_ACTIVE_HOST = "active_host"
        const val KEY_RUNNING = "vpn_running"
        private const val NOTIFICATION_ID = 101
    }
}
