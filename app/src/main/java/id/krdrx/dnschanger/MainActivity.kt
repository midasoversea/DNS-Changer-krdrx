package id.krdrx.dnschanger

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
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

private data class DnsOption(val name: String, val address: String, val detail: String, val custom: Boolean = false)

private val builtInOptions = listOf(
    DnsOption("Cloudflare", "1.1.1.1", "DNS publik, fokus privasi"),
    DnsOption("Google", "8.8.8.8", "DNS publik Google"),
    DnsOption("Quad9", "9.9.9.9", "Perlindungan domain berbahaya"),
    DnsOption("Cloudflare Secondary", "1.0.0.1", "Alamat alternatif Cloudflare"),
    DnsOption("Google Secondary", "8.8.4.4", "Alamat alternatif Google"),
    DnsOption("OpenDNS", "208.67.222.222", "DNS publik Cisco"),
    DnsOption("AdGuard DNS", "94.140.14.14", "Pemblokiran iklan dan pelacak"),
    DnsOption("CleanBrowsing Family", "185.228.168.168", "Filter konten keluarga")
)

private const val PREFS = "dns_profiles"
private const val CUSTOM_DNS = "custom_dns"

class MainActivity : ComponentActivity() {
    private var pendingDns by mutableStateOf("1.1.1.1")
    private var connectionState by mutableStateOf(false)
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) startDns(pendingDns)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val initialCustom = prefs.getStringSet(CUSTOM_DNS, emptySet()).orEmpty()
            .mapNotNull { entry ->
                val parts = entry.split("|", limit = 2)
                if (parts.size == 2 && isValidIpv4(parts[1])) DnsOption(parts[0], parts[1], "DNS tersimpan", true) else null
            }.sortedBy { it.name.lowercase() }

        setContent {
            var customOptions by remember { mutableStateOf(initialCustom) }
            var selected by remember { mutableStateOf(builtInOptions.first()) }
            var dnsName by remember { mutableStateOf("") }
            var dnsAddress by remember { mutableStateOf("") }
            var message by remember { mutableStateOf<String?>(null) }
            val isOn = connectionState
            val allOptions = builtInOptions + customOptions

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
                                            pendingDns = selected.address
                                            val prepare = VpnService.prepare(this@MainActivity)
                                            if (prepare == null) startDns(selected.address) else vpnPermission.launch(prepare)
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF20C9B0)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(if (isOn) "PUTUSKAN" else "SAMBUNGKAN", color = Color(0xFF071512))
                                }
                            }
                        }

                        Text("PILIH DNS", color = Color(0xFF20C9B0), fontSize = 13.sp)
                        allOptions.forEach { option ->
                            Card(
                                onClick = { if (!isOn) { selected = option; message = null } },
                                colors = CardDefaults.cardColors(containerColor = if (selected.address == option.address) Color(0xFF24433F) else Color(0xFF1B2732)),
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
                                            customOptions = customOptions.filterNot { it.address == option.address }
                                            prefs.edit().putStringSet(CUSTOM_DNS, customOptions.map { "${it.name}|${it.address}" }.toSet()).apply()
                                            if (selected.address == option.address) selected = builtInOptions.first()
                                            message = "DNS kustom dihapus."
                                        }) { Text("HAPUS", color = Color(0xFFFF8A80), fontSize = 10.sp) }
                                    }
                                }
                            }
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
                                    allOptions.any { it.address == address } -> message = "Alamat DNS tersebut sudah tersedia."
                                    else -> {
                                        val added = DnsOption(name, address, "DNS kustom tersimpan", true)
                                        customOptions = customOptions + added
                                        prefs.edit().putStringSet(CUSTOM_DNS, customOptions.map { "${it.name}|${it.address}" }.toSet()).apply()
                                        selected = added
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
                        Text(
                            "DNS kustom disimpan di perangkat dan tetap tersedia setelah aplikasi ditutup. Untuk mengganti DNS saat tersambung, putuskan koneksi terlebih dahulu.",
                            color = Color.Gray, fontSize = 11.sp
                        )
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

    private fun startDns(address: String) {
        val intent = Intent(this, DnsVpnService::class.java).apply {
            action = DnsVpnService.ACTION_START
            putExtra(DnsVpnService.EXTRA_DNS, address)
        }
        startService(intent)
        connectionState = true
    }

    private fun stopDns() {
        startService(Intent(this, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_STOP))
        connectionState = false
    }
}

private fun isValidIpv4(value: String): Boolean {
    val parts = value.split(".")
    if (parts.size != 4) return false
    return parts.all { part ->
        part.isNotEmpty() && part.all { it.isDigit() } &&
            (part.length == 1 || !part.startsWith("0")) &&
            part.toIntOrNull()?.let { it in 0..255 } == true
    }
}
