package com.example.myvpn

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wireguard.android.backend.GoBackend

const val WS_TEMPLATE =
    "GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf]Connection: Upgrade[crlf][crlf]"

class MainActivity : ComponentActivity() {

    private val vm: VpnViewModel by viewModels()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) vm.connect() else vm.onPermissionDenied()
        }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
            vm.connect()
        }
    }
}

@Composable
fun VpnScreen(vm: VpnViewModel, onToggle: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("الرئيسية") })
            Tab(tab == 1, { tab = 1 }, text = { Text("السجل") })
        }
        if (tab == 0) HomeTab(vm, onToggle) else LogScreen()
    }
}

@Composable
fun LogScreen() {
    val clipboard = LocalClipboardManager.current
    val state = rememberLazyListState()
    LaunchedEffect(AppLog.lines.size) {
        if (AppLog.lines.isNotEmpty()) state.scrollToItem(AppLog.lines.size - 1)
    }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                clipboard.setText(AnnotatedString(AppLog.lines.joinToString("\n")))
            }) { Text("نسخ") }
            OutlinedButton(onClick = { AppLog.clear() }) { Text("مسح") }
        }
        Spacer(Modifier.height(8.dp))
        if (AppLog.lines.isEmpty()) Text("لا توجد سجلات بعد", fontSize = 13.sp)
        LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
            items(AppLog.lines) {
                Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}

@Composable
fun HomeTab(vm: VpnViewModel, onToggle: () -> Unit) {
    var showAdd by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

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
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Redo VPN", fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "الإعدادات") }
        }
        Spacer(Modifier.height(24.dp))

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
                "SOCKS5 على 127.0.0.1:${vm.socksPort}\nاضبط هذا البروكسي في التطبيق الذي تريد تمريره عبر SSH",
                fontSize = 13.sp
            )
        }
        if (vm.activeV2ray) {
            Spacer(Modifier.height(8.dp))
            Text("V2Ray شغال عبر SOCKS5 على 127.0.0.1:10808. حالياً خاصك تضبط البروكسي في التطبيق اللي بغيتي تستعمل.", fontSize = 13.sp)
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
                            val sub = when (server.type) {
                                "WG" -> "WireGuard"
                                "V2RAY" -> "V2Ray • ${server.config.substringBefore("://").uppercase()}"
                                else -> "SSH • ${server.host}" +
                                    (if (server.payload.isNotBlank()) " • Payload" else "") +
                                    (if (server.tls) " • TLS" else "")
                            }
                            Text(sub, fontSize = 12.sp)
                        }
                        IconButton(onClick = { vm.removeServer(i) }) {
                            Icon(Icons.Default.Delete, "حذف")
                        }
                    }
                }
            }
        }
    }

    if (showAdd) AddServerDialog(onDismiss = { showAdd = false }, onSave = { vm.addServer(it); showAdd = false })
    if (showSettings) SettingsDialog(vm, onDismiss = { showSettings = false })
}

@Composable
fun SettingsDialog(vm: VpnViewModel, onDismiss: () -> Unit) {
    var port by remember { mutableStateOf(vm.socksPort.toString()) }
    var ka by remember { mutableStateOf(vm.keepAlive.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("الإعدادات") },
        text = {
            Column {
                OutlinedTextField(
                    port, { port = it.filter(Char::isDigit) },
                    label = { Text("منفذ SOCKS5 المحلي (1024-65535)") }, singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    ka, { ka = it.filter(Char::isDigit) },
                    label = { Text("فاصل Keep-Alive بالثواني (5-120)") }, singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.saveSettings(port.toIntOrNull() ?: 1080, ka.toIntOrNull() ?: 15)
                onDismiss()
            }) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
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
    var advanced by remember { mutableStateOf(false) }
    var cHost by remember { mutableStateOf("") }
    var cPort by remember { mutableStateOf("") }
    var tls by remember { mutableStateOf(false) }
    var sni by remember { mutableStateOf("") }
    var payload by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إضافة سيرفر") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(type == "WG", { type = "WG" }, label = { Text("WireGuard") })
                    FilterChip(type == "SSH", { type = "SSH" }, label = { Text("SSH") })
                    FilterChip(type == "V2RAY", { type = "V2RAY" }, label = { Text("V2Ray") })
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
                } else if (type == "V2RAY") {
                    OutlinedTextField(
                        cfg, { cfg = it.trim() },
                        label = { Text("رابط VLESS / VMess / Trojan") },
                        placeholder = { Text("vless://… أو vmess://… أو trojan://…") },
                        modifier = Modifier.height(150.dp)
                    )
                    Text("يدعم روابط السيرفرات الفردية. الاتصال كيوفر SOCKS5 محلياً على 127.0.0.1:10808.", fontSize = 12.sp)
                } else {
                    OutlinedTextField(host, { host = it }, label = { Text("عنوان سيرفر SSH") }, singleLine = true)
                    OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("منفذ SSH") }, singleLine = true)
                    OutlinedTextField(user, { user = it }, label = { Text("اسم المستخدم") }, singleLine = true)
                    OutlinedTextField(
                        pass, { pass = it }, label = { Text("كلمة المرور") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(advanced, { advanced = it })
                        Spacer(Modifier.width(8.dp))
                        Text("WebSocket / Payload")
                    }
                    if (advanced) {
                        OutlinedTextField(cHost, { cHost = it }, label = { Text("عنوان الوسيط/CDN (اختياري)") }, singleLine = true)
                        OutlinedTextField(cPort, { cPort = it.filter(Char::isDigit) }, label = { Text("منفذ الوسيط (80 أو 443)") }, singleLine = true)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(tls, { tls = it })
                            Spacer(Modifier.width(8.dp))
                            Text("TLS")
                        }
                        if (tls) OutlinedTextField(sni, { sni = it }, label = { Text("SNI") }, singleLine = true)
                        OutlinedTextField(
                            payload, { payload = it },
                            label = { Text("Payload") },
                            modifier = Modifier.height(130.dp)
                        )
                        TextButton(onClick = { payload = WS_TEMPLATE }) { Text("قالب WebSocket") }
                        Text("المتغيرات: [host] [port] [crlf] [cr] [lf]", fontSize = 11.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(Server(
                    name = name, type = type, config = cfg,
                    host = host.trim(), port = port.toIntOrNull() ?: 22,
                    user = user.trim(), password = pass,
                    connectHost = if (advanced) cHost.trim() else "",
                    connectPort = if (advanced) cPort.toIntOrNull() ?: 0 else 0,
                    tls = advanced && tls,
                    sni = if (advanced) sni.trim() else "",
                    payload = if (advanced) payload else ""
                ))
            }) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}
