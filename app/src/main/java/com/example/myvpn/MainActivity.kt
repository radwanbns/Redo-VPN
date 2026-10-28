package com.example.myvpn

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wireguard.android.backend.GoBackend
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

const val WS_TEMPLATE =
    "GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf]Connection: Upgrade[crlf][crlf]"

/*
 * نصوص نافذة Telegram مشفرة بـ AES-GCM.
 * ملاحظة: المفتاح داخل APK، لذلك هذا إخفاء للنصوص وليس حماية سرية كاملة.
 */
private object DialogSecrets {
    private const val KEY_B64 = "t7XnPsX4ZuJZSH0gI6XjiA=="

    private const val TITLE = "Lt5g+OOs2I9ktX0p3n6L0ZOHoTRd7Jah5D/d0eQ5emDPFZuQeeOdQZhFMuEBWDUWdHu71CU9oKE="
    private const val DESCRIPTION = "ZkKlSpylE3QEPj+Hv39+LmcuOABtouzW85qkPUHLilcQUg7ofQkmqxuIbTs8z2WHE0WIaOLrIkaD8Dj/X68oYRhHDZ8sIIBirR6asvWZTiuCP3Qh2gfKdpiGMzFRljmnLKpfu03r1Drr1E/RA2F6B5GS0rd6wWT7kc1q5w=="
    private const val TELEGRAM_URL = "nMk4S/dRD8Hnj/3ovWI4N+LNbPXhqaegynyg8KX5HE8PssEUa8PAKK8TBeyMtQRleifEVtaY"
    private const val YOUTUBE_URL = "zplhs0vmPQRb6A10N1HEi0ivxO0Ofso7vvWS+SjNiFe3YYJSyjlouMtIWNKkZWMo18lXnHY+BNDoUve3x9Y="
    private const val JOIN_LABEL = "fsbNsMSPjic6WVOZ+YnTr5HdoKzBqcfC7hx5xbU/uBnBXtmHYCW3X75aYy7OSEJFt6ryj48lfMmhV/Fe"
    private const val YOUTUBE_LABEL = "pgLK9/xlpKdZow8T7ctJ1mnnGJxKU8oyr1mLIhmKwP2j2vc="
    private const val SKIP_LABEL = "dqp0JbLz01OENs348MH8UaK9Y8jMkq1S5otnNFCNz7howscg"

    private fun decrypt(value: String): String {
        val data = Base64.decode(value, Base64.DEFAULT)
        val iv = data.copyOfRange(0, 12)
        val encrypted = data.copyOfRange(12, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(Base64.decode(KEY_B64, Base64.DEFAULT), "AES"),
            GCMParameterSpec(128, iv)
        )
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    val title: String get() = decrypt(TITLE)
    val description: String get() = decrypt(DESCRIPTION)
    val telegramUrl: String get() = decrypt(TELEGRAM_URL)
    val youtubeUrl: String get() = decrypt(YOUTUBE_URL)
    val joinLabel: String get() = decrypt(JOIN_LABEL)
    val youtubeLabel: String get() = decrypt(YOUTUBE_LABEL)
    val skipLabel: String get() = decrypt(SKIP_LABEL)
}

private object DialogDisplayState {
    var countedThisSession: Boolean = false
}

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
        } else if (vm.servers.getOrNull(vm.selected)?.type != "SSH") {
            val intent = android.net.VpnService.prepare(this)
            if (intent != null) permissionLauncher.launch(intent) else vm.connect()
        } else {
            vm.connect()
        }
    }
}

@Composable
fun VpnScreen(vm: VpnViewModel, onToggle: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    val navy = Color(0xFF06101F)
    val cyan = Color(0xFF00B7FF)
    val pale = Color(0xFFD9E8FF)
    Column(Modifier.fillMaxSize().background(navy).statusBarsPadding()) {
        // Header is above the tabs so the app name and settings sit higher.
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Redo ", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            Text("VPN", color = cyan, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showSettings = true }) {
                Icon(Icons.Default.Settings, "الإعدادات", tint = pale, modifier = Modifier.size(27.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        TabRow(
            selectedTabIndex = tab,
            containerColor = Color(0xFF080D18),
            contentColor = Color(0xFFB9D9FF),
        ) {
            Tab(tab == 0, { tab = 0 }, text = { Text("الرئيسية", fontWeight = FontWeight.SemiBold) })
            Tab(tab == 1, { tab = 1 }, text = { Text("السجل", fontWeight = FontWeight.SemiBold) })
        }
        if (tab == 0) HomeTab(vm, onToggle) else LogScreen()
    }
    if (showSettings) SettingsDialog(vm, onDismiss = { showSettings = false })
}

@Composable
fun LogScreen() {
    val clipboard = LocalClipboardManager.current
    val state = rememberLazyListState()
    LaunchedEffect(AppLog.lines.size) {
        if (AppLog.lines.isNotEmpty()) state.scrollToItem(AppLog.lines.size - 1)
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF06101F)).padding(16.dp)) {
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
    var showTelegramDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val navy = Color(0xFF06101F)
    val panel = Color(0xFF102442)
    val cyan = Color(0xFF00B7FF)
    val pale = Color(0xFFD9E8FF)

    LaunchedEffect(Unit) {
        if (!DialogDisplayState.countedThisSession) {
            DialogDisplayState.countedThisSession = true
            val prefs = context.getSharedPreferences("promo_dialog_prefs", 0)
            val shownCount = prefs.getInt("shown_count", 0)
            if (shownCount < 10) {
                showTelegramDialog = true
                prefs.edit().putInt("shown_count", shownCount + 1).apply()
            }
        }
    }

    fun openLink(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    val statusColor = when (vm.status) {
        VpnStatus.CONNECTED -> Color(0xFF26D98A)
        VpnStatus.CONNECTING -> Color(0xFFFFC857)
        VpnStatus.DISCONNECTED -> Color(0xFF9BAEC5)
    }
    val label = when (vm.status) {
        VpnStatus.CONNECTED -> "متصل"
        VpnStatus.CONNECTING -> "جارٍ الاتصال..."
        VpnStatus.DISCONNECTED -> "غير متصل"
    }

    Column(
        Modifier.fillMaxSize().background(navy).padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(12.dp))

        Box(
            Modifier.size(174.dp)
                .background(Brush.radialGradient(listOf(Color(0xFF1D5CA8), Color(0xFF0C2346), navy)), CircleShape)
                .border(2.dp, cyan, CircleShape)
                .clickable(enabled = vm.status != VpnStatus.CONNECTING) { onToggle() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.PowerSettingsNew, null, Modifier.size(78.dp), tint = Color.White)
        }
        Spacer(Modifier.height(12.dp))
        Text(label, fontSize = 22.sp, color = statusColor, fontWeight = FontWeight.SemiBold)

        Spacer(Modifier.height(14.dp))
        Button(
            onClick = { openLink(DialogSecrets.telegramUrl) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFBFA4FF), contentColor = Color(0xFF25134D))
        ) { Text("الانضمام إلى قناة Telegram", fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { openLink(DialogSecrets.youtubeUrl) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF0000), contentColor = Color.White)
        ) { Text("الاشتراك في قناة YouTube", fontWeight = FontWeight.SemiBold) }

        if (vm.activeSsh) {
            Spacer(Modifier.height(6.dp))
            Text("SOCKS5 على 127.0.0.1:${vm.socksPort}\nاضبط هذا البروكسي في التطبيق الذي تريد تمريره عبر SSH", color = pale, fontSize = 12.sp)
        }
        if (vm.activeV2ray) {
            Spacer(Modifier.height(6.dp))
            Text("V2Ray يعمل كـ VPN كامل: كل حركة الهاتف تمر عبر السيرفر.", color = pale, fontSize = 12.sp)
        }
        vm.error?.let { Spacer(Modifier.height(6.dp)); Text(it, color = MaterialTheme.colorScheme.error) }

        Spacer(Modifier.height(18.dp))
        Card(
            modifier = Modifier.fillMaxWidth().border(1.dp, Color(0xFF25466F), MaterialTheme.shapes.large),
            colors = CardDefaults.cardColors(containerColor = panel),
            shape = MaterialTheme.shapes.large
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("🌐", fontSize = 24.sp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("اختيار السيرفر", color = pale, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(vm.servers.getOrNull(vm.selected)?.name ?: "اختر سيرفراً من القائمة بالأسفل", color = Color(0xFF9DB6D8), fontSize = 12.sp)
                }
                IconButton(onClick = { vm.updateSubscription() }, enabled = !vm.subBusy) {
                    Icon(Icons.Default.Refresh, "تحديث السيرفرات", tint = cyan)
                }
                IconButton(onClick = { showAdd = true }) {
                    Icon(Icons.Default.Add, "إضافة سيرفر", tint = pale)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("السيرفرات", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
            Text("${vm.servers.size} متاح", color = Color(0xFF8CA8CC), fontSize = 12.sp)
        }
        vm.subMessage?.let { Text(it, fontSize = 12.sp, color = Color(0xFFFFC857), modifier = Modifier.padding(vertical = 4.dp)) }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 8.dp)) {
            itemsIndexed(vm.servers) { i, server ->
                val selected = vm.selected == i
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { if (vm.status == VpnStatus.DISCONNECTED) vm.selected = i },
                    colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFF173A63) else panel),
                    shape = MaterialTheme.shapes.medium,
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) cyan else Color(0xFF203B5E))
                ) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected, onClick = { if (vm.status == VpnStatus.DISCONNECTED) vm.selected = i }, colors = RadioButtonDefaults.colors(selectedColor = cyan, unselectedColor = Color(0xFF8CA8CC)))
                        Column(Modifier.weight(1f)) {
                            Text(server.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                            val sub = when (server.type) {
                                "WG" -> "WireGuard"
                                "V2RAY" -> "V2Ray • ${server.config.substringBefore("://").uppercase()}"
                                else -> "SSH • ${server.host}" + (if (server.payload.isNotBlank()) " • Payload" else "") + (if (server.tls) " • TLS" else "")
                            }
                            Text(sub, fontSize = 12.sp, color = Color(0xFF9DB6D8))
                        }
                        IconButton(onClick = { vm.removeServer(i) }) { Icon(Icons.Default.Delete, "حذف", tint = Color(0xFFFF7C86)) }
                    }
                }
            }
        }
    }

    if (showAdd) AddServerDialog(onDismiss = { showAdd = false }, onSave = { vm.addServer(it); showAdd = false })

    if (showTelegramDialog) {
        AlertDialog(
            onDismissRequest = { },
            containerColor = Color(0xFF102442),
            titleContentColor = Color.White,
            textContentColor = Color(0xFFD9E8FF),
            title = { Text(DialogSecrets.title, fontWeight = FontWeight.Bold) },
            text = { Text(DialogSecrets.description) },
            confirmButton = {
                TextButton(
                    onClick = { showTelegramDialog = false; openLink(DialogSecrets.telegramUrl) },
                    modifier = Modifier.padding(end = 8.dp)
                ) { Text(DialogSecrets.joinLabel, color = Color(0xFF4AB8FF), fontSize = 13.sp) }
            },
            dismissButton = { TextButton(onClick = { showTelegramDialog = false }) { Text(DialogSecrets.skipLabel, color = pale) } }
        )
    }
}

@Composable
fun SettingsDialog(vm: VpnViewModel, onDismiss: () -> Unit) {
    var port by remember { mutableStateOf(vm.socksPort.toString()) }
    var ka by remember { mutableStateOf(vm.keepAlive.toString()) }
    var subUrl by remember { mutableStateOf(vm.subUrl) }
    var subAuto by remember { mutableStateOf(vm.subAuto) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("الإعدادات") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    port, { port = it.filter(Char::isDigit) },
                    label = { Text("منفذ SOCKS5 المحلي (1024-65535)") }, singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    ka, { ka = it.filter(Char::isDigit) },
                    label = { Text("فاصل Keep-Alive بالثواني (5-120)") }, singleLine = true
                )
                Spacer(Modifier.height(16.dp))
                Text("اشتراك السيرفرات (رابط GitHub)", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                if (AppConfig.LOCK_SUB && AppConfig.SUB_URL.isNotBlank()) {
                    Text("رابط الاشتراك مدمج في التطبيق.", fontSize = 12.sp)
                } else {
                    OutlinedTextField(
                        subUrl, { subUrl = it.trim() },
                        label = { Text("رابط الملف (raw) الذي يبدأ بـ https://") }, singleLine = true
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(subAuto, { subAuto = it })
                    Spacer(Modifier.width(8.dp))
                    Text("تحديث تلقائي عند فتح التطبيق", fontSize = 13.sp)
                }
                TextButton(
                    onClick = { vm.saveSubscription(subUrl, subAuto); vm.updateSubscription() },
                    enabled = !vm.subBusy
                ) { Text("حفظ وتحديث الآن") }
                vm.subMessage?.let { Text(it, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.saveSettings(port.toIntOrNull() ?: 1080, ka.toIntOrNull() ?: 15)
                vm.saveSubscription(subUrl, subAuto)
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
                    Text("يدعم روابط السيرفرات الفردية، ويعمل كـ VPN كامل لكل الهاتف.", fontSize = 12.sp)
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