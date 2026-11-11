package id.krdrx.dnschanger

import android.net.VpnService
import android.content.Intent
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

class DnsVpnService : VpnService() {
    @Volatile private var running = false
    private var tun: ParcelFileDescriptor? = null
    private var worker: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopVpn()
            ACTION_START -> startVpn(intent.getStringExtra(EXTRA_DNS) ?: "1.1.1.1")
        }
        return START_NOT_STICKY
    }

    private fun startVpn(dns: String) {
        if (running) stopVpn()
        try {
            val server = InetAddress.getByName(dns)
            tun = Builder()
                .setSession("DNS Changer")
                .setMtu(1500)
                .addAddress("10.111.0.1", 32)
                .addDnsServer(server)
                .addRoute(server, 32)
                .establish() ?: return
            running = true
            worker = thread(name = "dns-forwarder") {
                val input = FileInputStream(tun!!.fileDescriptor)
                val output = FileOutputStream(tun!!.fileDescriptor)
                val buffer = ByteArray(32767)
                val socket = DatagramSocket()
                try {
                    if (!protect(socket)) return@thread
                    socket.soTimeout = 5000
                    while (running) {
                        val length = input.read(buffer)
                        if (length < 28) continue
                        val ihl = (buffer[0].toInt() and 0x0f) * 4
                        if (ihl < 20 || (buffer[9].toInt() and 0xff) != 17) continue
                        val total = ((buffer[2].toInt() and 0xff) shl 8) or (buffer[3].toInt() and 0xff)
                        val udp = ihl
                        if (total > length || total < ihl + 8) continue
                        val destPort = ((buffer[udp + 2].toInt() and 0xff) shl 8) or (buffer[udp + 3].toInt() and 0xff)
                        if (destPort != 53) continue
                        val dnsLength = total - ihl - 8
                        if (dnsLength < 12) continue
                        val query = buffer.copyOfRange(udp + 8, udp + 8 + dnsLength)
                        try {
                            val request = DatagramPacket(query, query.size, server, 53)
                            socket.send(request)
                            val answer = ByteArray(4096)
                            val response = DatagramPacket(answer, answer.size)
                            socket.receive(response)
                            val dnsReply = answer.copyOf(response.length)
                            val packet = buildIpv4UdpResponse(buffer, ihl, udp, dnsReply)
                            output.write(packet)
                        } catch (_: Exception) { }
                    }
                } catch (_: Exception) {
                } finally {
                    socket.close()
                    stopVpn()
                }
            }
        } catch (_: Exception) {
            stopVpn()
        }
    }

    private fun buildIpv4UdpResponse(original: ByteArray, ihl: Int, udp: Int, dns: ByteArray): ByteArray {
        val out = ByteArray(ihl + 8 + dns.size)
        original.copyInto(out, 0, 0, ihl)
        for (i in 0..3) {
            out[12 + i] = original[16 + i]
            out[16 + i] = original[12 + i]
        }
        out[2] = ((out.size ushr 8) and 0xff).toByte()
        out[3] = (out.size and 0xff).toByte()
        out[10] = 0; out[11] = 0
        val ipSum = checksum(out, 0, ihl)
        out[10] = (ipSum ushr 8).toByte(); out[11] = ipSum.toByte()
        val sourcePort = original[udp].toInt() and 0xff
        val sourcePort2 = original[udp + 1].toInt() and 0xff
        out[udp] = original[udp + 2]; out[udp + 1] = original[udp + 3]
        out[udp + 2] = sourcePort.toByte(); out[udp + 3] = sourcePort2.toByte()
        val udpLength = 8 + dns.size
        out[udp + 4] = (udpLength ushr 8).toByte(); out[udp + 5] = udpLength.toByte()
        out[udp + 6] = 0; out[udp + 7] = 0
        dns.copyInto(out, udp + 8)
        return out
    }

    private fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        while (i < offset + length) {
            val high = data[i].toInt() and 0xff
            val low = if (i + 1 < offset + length) data[i + 1].toInt() and 0xff else 0
            sum += (high shl 8) or low
            i += 2
        }
        while ((sum ushr 16) != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.inv().toInt() and 0xffff
    }

    private fun stopVpn() {
        running = false
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        try { tun?.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "id.krdrx.dnschanger.START"
        const val ACTION_STOP = "id.krdrx.dnschanger.STOP"
        const val EXTRA_DNS = "dns"
    }
}
