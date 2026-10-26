package com.example.myvpn

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wireguard.android.backend.GoBackend

class MainActivity : ComponentActivity() {

    private val vm: VpnViewModel by viewModels()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) vm.connect() else vm.onPermissionDenied()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { VpnScreen(vm, onToggle = ::toggle) }
            }
        }
    }

    private fun toggle() {
        if (vm.status == VpnStatus.CONNECTED) {
            vm.disconnect()
        } else if (vm.selectedIsWireGuard) {
            val intent = GoBackend.VpnService.prepare(this)
            if (intent != null) permissionLauncher.launch(intent) else vm.connect()
        } else {
            vm.connect() // SSH لا يحتاج إذن VPN
        }
    }
}

@Composable
fun VpnScreen(vm: VpnViewModel, onToggle: () -> Unit) {
    var showAdd by remember { mutableStateOf(false) }

    val color = when (vm.status) {
        VpnStatus.CONNECTED -> Color(0xFF2ECC71)
        VpnStatus.CONNECTING -> Color(0xFFF1C40F)
        VpnStatus.DISCONNECTED -> Color(0xFF7F8C8D)
    }
    val label = when (vm.status) {
        VpnStatus.CONNECTED -> "متصل"
        VpnStatus.CONNECTING -> "جارٍ الاتصال..."
        VpnStatus.DISCONNECTED -> "غير متصل"
    }

    Column(
        Modifier.fillMaxSize().padding(24.dp).statusBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Redo VPN", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(32.dp))

        Button(
            onClick = onToggle,
            enabled = vm.status != VpnStatus.CONNECTING,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = color),
            modifier = Modifier.size(150.dp)
        ) { Icon(Icons.Default.PowerSettingsNew, null, Modifier.size(64.dp), tint = Color.White) }

        Spacer(Modifier.height(12.dp))
        Text(label, fontSize = 20.sp, color = color)

        if (vm.activeSsh) {
            Spacer(Modifier.height(8.dp))
            Text(
                "SOCKS5 على 127.0.0.1:1080\nاضبط هذا البروكسي في التطبيق الذي تريد تمريره عبر SSH",
                fontSize = 13.sp
            )
        }
        vm.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("السيرفرات", fontSize = 18.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, "إضافة") }
        }

        LazyColumn(Modifier.fillMaxWidth()) {
            itemsIndexed(vm.servers) { i, server ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = vm.selected == i,
                            onClick = { if (vm.status == VpnStatus.DISCONNECTED) vm.selected = i }
                        )
                        Column(Modifier.weight(1f)) {
                            Text(server.name)
                            Text(
                                if (server.type == "WG") "WireGuard" else "SSH • ${server.host}",
                                fontSize = 12.sp
                            )
                        }
                        IconButton(onClick = { vm.removeServer(i) }) {
                            Icon(Icons.Default.Delete, "حذف")
                        }
                    }
                }
            }
        }
    }

    if (showAdd) AddServerDialog(
        onDismiss = { showAdd = false },
        onSave = { vm.addServer(it); showAdd = false }
    )
}

@Composable
fun AddServerDialog(onDismiss: () -> Unit, onSave: (Server) -> Unit) {
    var type by remember { mutableStateOf("WG") }
    var name by remember { mutableStateOf("") }
    var cfg by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إضافة سيرفر") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(type == "WG", { type = "WG" }, label = { Text("WireGuard") })
                    FilterChip(type == "SSH", { type = "SSH" }, label = { Text("SSH") })
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(name, { name = it }, label = { Text("الاسم") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                if (type == "WG") {
                    OutlinedTextField(
                        cfg, { cfg = it },
                        label = { Text("إعداد WireGuard (.conf)") },
                        modifier = Modifier.height(200.dp)
                    )
                } else {
                    OutlinedTextField(host, { host = it }, label = { Text("عنوان السيرفر") }, singleLine = true)
                    OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("المنفذ") }, singleLine = true)
                    OutlinedTextField(user, { user = it }, label = { Text("اسم المستخدم") }, singleLine = true)
                    OutlinedTextField(
                        pass, { pass = it }, label = { Text("كلمة المرور") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(Server(name, type, cfg, host.trim(), port.toIntOrNull() ?: 22, user.trim(), pass))
            }) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}
