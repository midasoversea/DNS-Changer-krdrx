package id.krdrx.dnschanger

import android.app.Activity
import android.net.VpnService
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class DnsOption(val name: String, val address: String, val detail: String)
private val options = listOf(
    DnsOption("Cloudflare", "1.1.1.1", "Cepat dan berorientasi privasi"),
    DnsOption("Google", "8.8.8.8", "DNS publik Google"),
    DnsOption("Quad9", "9.9.9.9", "Memblokir domain berbahaya")
)

class MainActivity : ComponentActivity() {
    private var pendingDns by mutableStateOf("1.1.1.1")
    private var connectionState by mutableStateOf(false)
    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) startDns(pendingDns)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var selected by remember { mutableStateOf(options.first()) }
            val isOn = connectionState
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF20C9B0), background = Color(0xFF101820),
                surface = Color(0xFF1B2732), onSurface = Color(0xFFEAF2F5)
            )) {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF101820)) {
                    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text("DNS CHANGER", fontSize = 14.sp, color = Color(0xFF20C9B0))
                        Text("Atur DNS, kendalikan koneksi.", fontSize = 25.sp, color = Color.White)
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2732)), shape = RoundedCornerShape(20.dp)) {
                            Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(if (isOn) "TERHUBUNG" else "TIDAK TERHUBUNG", color = if (isOn) Color(0xFF20C9B0) else Color.LightGray)
                                Spacer(Modifier.height(12.dp))
                                Text(selected.address, fontSize = 30.sp, color = Color.White)
                                Text(selected.name, color = Color.LightGray)
                                Spacer(Modifier.height(18.dp))
                                Button(onClick = {
                                    if (isOn) {
                                        stopDns()
                                    } else {
                                        pendingDns = selected.address
                                        val prepare = VpnService.prepare(this@MainActivity)
                                        if (prepare == null) startDns(selected.address)
                                        else vpnPermission.launch(prepare)
                                    }
                                }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF20C9B0)), modifier = Modifier.fillMaxWidth()) {
                                    Text(if (isOn) "PUTUSKAN" else "SAMBUNGKAN", color = Color(0xFF071512))
                                }
                            }
                        }
                        Text("PILIH PENYEDIA DNS", color = Color(0xFF20C9B0), fontSize = 13.sp)
                        options.forEach { option ->
                            Card(
                                onClick = { if (!isOn) selected = option },
                                colors = CardDefaults.cardColors(containerColor = if (selected == option) Color(0xFF24433F) else Color(0xFF1B2732)),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(option.name, color = Color.White)
                                        Text(option.detail, color = Color.LightGray, fontSize = 12.sp)
                                    }
                                    Text(option.address, color = Color(0xFF20C9B0))
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Text("VPN lokal hanya untuk meneruskan permintaan DNS. Tidak ada akun atau data penjelajahan yang dikumpulkan.", color = Color.Gray, fontSize = 11.sp)
                    }
                }
            }
        }
    }

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
