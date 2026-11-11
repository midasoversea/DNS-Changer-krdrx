package id.krdrx.dnschanger

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

internal object DnsTransport {
    fun query(address: String, mode: String, host: String, query: ByteArray): ByteArray {
        val ip = InetAddress.getByName(address)
        return when (mode) {
            "DoT" -> tls(ip, host, query)
            "DoH" -> https(ip, host, query)
            else -> udp(ip, query)
        }
    }

    private fun udp(ip: InetAddress, query: ByteArray): ByteArray {
        val s = DatagramSocket()
        try {
            s.soTimeout = 5000
            s.send(DatagramPacket(query, query.size, ip, 53))
            val data = ByteArray(4096)
            val packet = DatagramPacket(data, data.size)
            s.receive(packet)
            return data.copyOf(packet.length)
        } finally { s.close() }
    }

    private fun open(ip: InetAddress, host: String, port: Int): SSLSocket {
        require(host.isNotBlank()) { "Nama host DNS kosong" }
        val s = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket() as SSLSocket
        s.connect(InetSocketAddress(ip, port), 5000)
        s.soTimeout = 5000
        val p: SSLParameters = s.sslParameters
        p.serverNames = listOf(SNIHostName(host))
        p.endpointIdentificationAlgorithm = "HTTPS"
        s.sslParameters = p
        s.startHandshake()
        return s
    }

    private fun tls(ip: InetAddress, host: String, query: ByteArray): ByteArray =
        open(ip, host, 853).use { s ->
            val o = s.outputStream
            o.write(query.size ushr 8); o.write(query.size and 255); o.write(query); o.flush()
            val input = s.inputStream
            val hi = input.read(); val lo = input.read()
            if (hi < 0 || lo < 0) error("Respons DoT terputus")
            readFully(input, (hi shl 8) or lo)
        }

    private fun https(ip: InetAddress, host: String, query: ByteArray): ByteArray =
        open(ip, host, 443).use { s ->
            val o = s.outputStream
            val h = "POST /dns-query HTTP/1.1\r\nHost: $host\r\nContent-Type: application/dns-message\r\nAccept: application/dns-message\r\nContent-Length: ${query.size}\r\nConnection: close\r\n\r\n"
            o.write(h.toByteArray(Charsets.US_ASCII)); o.write(query); o.flush()
            val input = s.inputStream
            val b = ByteArrayOutputStream(); var state = 0
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
}
