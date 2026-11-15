package id.krdrx.dnschanger

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.delay
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class DnsOption(val name: String, val address: String, val detail: String, val custom: Boolean = false, val transport: String = "UDP", val host: String = "") { val key: String get() = listOf(name, address, transport, host).joinToString("|") }

private val builtInOptions = listOf(
    DnsOption("Cloudflare", "1.1.1.1", "DNS publik, fokus privasi"),
    DnsOption("Google", "8.8.8.8", "DNS publik Google"),
    DnsOption("Quad9", "9.9.9.9", "Perlindungan domain berbahaya"),
    DnsOption("Cloudflare Secondary", "1.0.0.1", "Alamat alternatif Cloudflare"),
    DnsOption("Google Secondary", "8.8.4.4", "Alamat alternatif Google"),
    DnsOption("OpenDNS", "208.67.222.222", "DNS publik Cisco"),
    DnsOption("AdGuard DNS", "94.140.14.14", "Pemblokiran iklan dan pelacak"),
    DnsOption("CleanBrowsing Family", "185.228.168.168", "Filter konten keluarga"),
    DnsOption("Cloudflare DoT", "1.1.1.1", "DNS terenkripsi TLS", false, "DoT", "one.one.one.one"),
    DnsOption("Google DoT", "8.8.8.8", "DNS terenkripsi TLS", false, "DoT", "dns.google"),
    DnsOption("Quad9 DoT", "9.9.9.9", "DNS terenkripsi TLS", false, "DoT", "dns.quad9.net"),
    DnsOption("Cloudflare DoH", "1.1.1.1", "DNS terenkripsi HTTPS", false, "DoH", "cloudflare-dns.com"),
    DnsOption("Google DoH", "8.8.8.8", "DNS terenkripsi HTTPS", false, "DoH", "dns.google")
)

private const val PREFS = "dns_profiles"
private const val CUSTOM_DNS = "custom_dns"

private fun encodeDnsOption(option: DnsOption): String = listOf(option.name, option.address, option.transport, option.host)
    .joinToString("|") { java.net.URLEncoder.encode(it, "UTF-8") }

private fun decodeDnsOption(entry: String): DnsOption? {
    val p = entry.split("|")
    if (p.size !in 2..4) return null
    return try {
        val fields = p.map { java.net.URLDecoder.decode(it, "UTF-8") }
        if (!isValidIpv4(fields[1])) return null
        DnsOption(fields[0], fields[1], "DNS tersimpan", true, fields.getOrElse(2) { "UDP" }, fields.getOrElse(3) { "" })
    } catch (_: Exception) { null }
}

class MainActivity : ComponentActivity() {
    private var pendingDns by mutableStateOf("1.1.1.1")
    private var pendingOption: DnsOption? = null
    private var connectionState by mutableStateOf(false)
    private var activeProfileKey by mutableStateOf<String?>(null)
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) pendingOption?.let { startDns(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        connectionState = prefs.getBoolean(DnsVpnService.KEY_RUNNING, false)
        activeProfileKey = prefs.getString(DnsVpnService.KEY_ACTIVE_PROFILE, null)
        val activeKey = activeProfileKey
        val initialCustom = prefs.getStringSet(CUSTOM_DNS, emptySet()).orEmpty()
            .mapNotNull(::decodeDnsOption).sortedBy { it.name.lowercase() }

        setContent {
            var customOptions by remember { mutableStateOf(initialCustom) }
            var selected by remember { mutableStateOf((builtInOptions + initialCustom).firstOrNull { it.key == activeKey } ?: builtInOptions.first()) }
            var transport by remember { mutableStateOf(selected.transport) }
            var dnsHost by remember { mutableStateOf(selected.host) }
            var testStatus by remember { mutableStateOf<String?>(null) }
            var testing by remember { mutableStateOf(false) }
            var testRequestId by remember { mutableStateOf(0L) }
            var dnsName by remember { mutableStateOf("") }
            var dnsAddress by remember { mutableStateOf("") }
            var message by remember { mutableStateOf<String?>(null) }
            val isOn = connectionState
            val allOptions = builtInOptions + customOptions

            LaunchedEffect(Unit) {
                while (true) {
                    val state = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    connectionState = state.getBoolean(DnsVpnService.KEY_RUNNING, false)
                    activeProfileKey = state.getString(DnsVpnService.KEY_ACTIVE_PROFILE, null)
                    if (testRequestId != 0L && state.getLong(DnsVpnService.KEY_TEST_ID, 0L) == testRequestId) {
                        testStatus = state.getString(DnsVpnService.KEY_TEST_RESULT, null)
                        testing = false
                        testRequestId = 0L
                    }
                    delay(500)
                }
            }

            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF20C9B0), background = Color(0xFF101820),
                surface = Color(0xFF1B2732), onSurface = Color(0xFFEAF2F5)
            )) {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF101820)) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text("DNS CHANGER", fontSize = 14.sp, color = Color(0xFF20C9B0))
                        Text("Atur DNS, kendalikan koneksi.", fontSize = 25.sp, color = Color.White)

                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2732)), shape = RoundedCornerShape(20.dp)) {
                            Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(if (isOn) "TERHUBUNG" else "TIDAK TERHUBUNG", color = if (isOn) Color(0xFF20C9B0) else Color.LightGray)
                                Spacer(Modifier.height(10.dp))
                                Text(selected.address, fontSize = 30.sp, color = Color.White)
                                Text(selected.name, color = Color.LightGray)
                                Spacer(Modifier.height(16.dp))
                                Button(
                                    onClick = {
                                        if (isOn) stopDns() else {
                                            val effective = selected.copy(transport = transport, host = if (transport == "UDP") "" else dnsHost.trim())
                                            pendingDns = effective.address
                                            pendingOption = effective
                                            val prepare = VpnService.prepare(this@MainActivity)
                                            if (prepare == null) startDns(effective) else vpnPermission.launch(prepare)
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF20C9B0)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(if (isOn) "PUTUSKAN" else "SAMBUNGKAN", color = Color(0xFF071512))
                                }
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = {
                                        if (!testing) {
                                            testing = true
                                            testStatus = null
                                            val option = selected.copy(transport = transport, host = if (transport == "UDP") "" else dnsHost.trim())
                                            if (isOn) {
                                                testRequestId = System.currentTimeMillis()
                                                getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                                                    .putLong(DnsVpnService.KEY_TEST_ID, 0L).putString(DnsVpnService.KEY_TEST_RESULT, "").apply()
                                                startService(Intent(this@MainActivity, DnsVpnService::class.java).apply {
                                                    action = DnsVpnService.ACTION_TEST
                                                    putExtra(DnsVpnService.EXTRA_DNS, option.address)
                                                    putExtra(DnsVpnService.EXTRA_MODE, option.transport)
                                                    putExtra(DnsVpnService.EXTRA_HOST, option.host)
                                                    putExtra(DnsVpnService.EXTRA_TEST_ID, testRequestId)
                                                })
                                            } else {
                                                testDns(option) { result -> testStatus = result; testing = false }
                                            }
                                        }
                                    },
                                    enabled = !testing,
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text(if (testing) "MENGUJI DNS..." else "UJI KONEKSI DNS") }
                                testStatus?.let { Text(it, color = if (it.startsWith("BERHASIL")) Color(0xFF20C9B0) else Color(0xFFFFB4AB), fontSize = 12.sp) }
                            }
                        }

                        Text("PILIH DNS", color = Color(0xFF20C9B0), fontSize = 13.sp)
                        allOptions.forEach { option ->
                            Card(
                                onClick = { if (!isOn) { selected = option; transport = option.transport; dnsHost = option.host; message = null } },
                                colors = CardDefaults.cardColors(containerColor = if (selected.key == option.key) Color(0xFF24433F) else Color(0xFF1B2732)),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(option.name, color = Color.White)
                                        Text(option.detail, color = Color.LightGray, fontSize = 12.sp)
                                    }
                                    Text(option.address, color = Color(0xFF20C9B0), fontSize = 13.sp)
                                    if (option.custom && !isOn) {
                                        Spacer(Modifier.width(8.dp))
                                        TextButton(onClick = {
                                            customOptions = customOptions.filterNot { it.key == option.key }
                                            prefs.edit().putStringSet(CUSTOM_DNS, customOptions.map(::encodeDnsOption).toSet()).apply()
                                            if (selected.key == option.key) { selected = builtInOptions.first(); transport = selected.transport; dnsHost = selected.host }
                                            message = "DNS kustom dihapus."
                                        }) { Text("HAPUS", color = Color(0xFFFF8A80), fontSize = 10.sp) }
                                    }
                                }
                            }
                        }

                        Text("PROTOKOL DNS", color = Color(0xFF20C9B0), fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("UDP", "DoT", "DoH").forEach { mode ->
                                FilterChip(selected = transport == mode, onClick = { if (!isOn) transport = mode }, label = { Text(mode) }, enabled = !isOn)
                            }
                        }
                        if (transport != "UDP") {
                            OutlinedTextField(
                                value = dnsHost, onValueChange = { dnsHost = it.trim() },
                                label = { Text(if (transport == "DoT") "Host TLS, contoh: one.one.one.one" else "Host HTTPS, contoh: cloudflare-dns.com") },
                                singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !isOn,
                                colors = dnsFieldColors()
                            )
                            Text("Alamat IPv4 di bawah menjadi alamat bootstrap untuk host terenkripsi.", color = Color.Gray, fontSize = 11.sp)
                        }
                        Text("TAMBAH DNS SENDIRI", color = Color(0xFF20C9B0), fontSize = 13.sp)
                        OutlinedTextField(
                            value = dnsName,
                            onValueChange = { dnsName = it },
                            label = { Text("Nama DNS, contoh: DNS Kantor") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isOn,
                            colors = dnsFieldColors()
                        )
                        OutlinedTextField(
                            value = dnsAddress,
                            onValueChange = { dnsAddress = it.trim() },
                            label = { Text("Alamat IPv4, contoh: 1.1.1.1") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isOn,
                            colors = dnsFieldColors()
                        )
                        Button(
                            onClick = {
                                val name = dnsName.trim().ifBlank { "DNS Kustom" }
                                val address = dnsAddress.trim()
                                when {
                                    !isValidIpv4(address) -> message = "Alamat tidak valid. Masukkan IPv4, misalnya 1.1.1.1."
                                    transport != "UDP" && !isValidHost(dnsHost.trim()) -> message = "Nama host DNS tidak valid."
                                    allOptions.any { it.address == address && it.transport == transport && it.host == if (transport == "UDP") "" else dnsHost.trim() } -> message = "Profil dengan alamat dan protokol tersebut sudah tersedia."
                                    else -> {
                                        val added = DnsOption(name, address, "DNS kustom tersimpan", true, transport, if (transport == "UDP") "" else dnsHost.trim())
                                        customOptions = customOptions + added
                                        prefs.edit().putStringSet(CUSTOM_DNS, customOptions.map(::encodeDnsOption).toSet()).apply()
                                        selected = added
                                        transport = added.transport
                                        dnsHost = added.host
                                        dnsName = ""
                                        dnsAddress = ""
                                        message = "DNS berhasil disimpan dan dipilih. Tekan SAMBUNGKAN untuk menggunakannya."
                                    }
                                }
                            },
                            enabled = !isOn,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF20C9B0))
                        ) { Text("SIMPAN DNS", color = Color(0xFF071512)) }

                        message?.let { Text(it, color = if (it.startsWith("DNS berhasil")) Color(0xFF20C9B0) else Color(0xFFFFB4AB), fontSize = 12.sp) }
                    }
                }
            }
        }
    }

    @Composable
    private fun dnsFieldColors() = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
        focusedBorderColor = Color(0xFF20C9B0), unfocusedBorderColor = Color.Gray,
        focusedLabelColor = Color(0xFF20C9B0), unfocusedLabelColor = Color.LightGray,
        cursorColor = Color(0xFF20C9B0)
    )

    private fun testDns(option: DnsOption, onResult: (String) -> Unit) {
        thread(name = "dns-connectivity-test") {
            var result = "GAGAL: DNS tidak merespons."
            try {
                val query = buildDnsQuery()
                val elapsed = measureTimeMillis {
                    val answer = DnsTransport.query(option.address, option.transport, option.host, query)
                    if (answer.size < 12 || answer[0] != query[0] || answer[1] != query[1] ||
                        (answer[2].toInt() and 0x80) == 0 || (answer[3].toInt() and 0x0f) != 0) {
                        throw IllegalStateException("Respons DNS tidak valid atau mengandung error.")
                    }
                }
                result = "BERHASIL: ${option.name} (${option.transport}) merespons dalam ${elapsed} ms."
            } catch (e: Exception) {
                result = "GAGAL: ${e.message ?: "Tidak ada respons DNS"}"
            }
            runOnUiThread { onResult(result) }
        }
    }

    private fun buildDnsQuery(): ByteArray {
        val name = "example.com"
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x4b, 0x52, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        name.split(".").forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        out.write(byteArrayOf(0x00, 0x01, 0x00, 0x01))
        return out.toByteArray()
    }

    private fun startDns(option: DnsOption) {
        val intent = Intent(this, DnsVpnService::class.java).apply {
            action = DnsVpnService.ACTION_START
            putExtra(DnsVpnService.EXTRA_DNS, option.address)
            putExtra(DnsVpnService.EXTRA_MODE, option.transport)
            putExtra(DnsVpnService.EXTRA_HOST, option.host)
            putExtra(DnsVpnService.EXTRA_PROFILE, option.key)
        }
        startService(intent)
    }

    private fun getSavedCustomOptions(): List<DnsOption> = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getStringSet(CUSTOM_DNS, emptySet()).orEmpty().mapNotNull(::decodeDnsOption)

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        connectionState = prefs.getBoolean(DnsVpnService.KEY_RUNNING, false)
        activeProfileKey = prefs.getString(DnsVpnService.KEY_ACTIVE_PROFILE, null)
    }

    private fun stopDns() {
        startService(Intent(this, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_STOP))
    }
}

private fun isValidHost(value: String): Boolean = value.length in 1..253 && value.split(".").all { it.isNotEmpty() && it.length <= 63 && it.first() != '-' && it.last() != '-' && it.all { ch -> ch.isLetterOrDigit() || ch == '-' } }

private fun isValidIpv4(value: String): Boolean {
    val parts = value.split(".")
    if (parts.size != 4) return false
    return parts.all { part ->
        part.isNotEmpty() && part.all { it.isDigit() } &&
            (part.length == 1 || !part.startsWith("0")) &&
            part.toIntOrNull()?.let { it in 0..255 } == true
    }
}
