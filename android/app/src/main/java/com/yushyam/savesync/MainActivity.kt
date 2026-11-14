package com.yushyam.savesync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.work.*
import java.util.concurrent.TimeUnit
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yushyam.savesync.data.model.AppSyncProfile
import com.yushyam.savesync.data.model.AppSyncStatus
import com.yushyam.savesync.data.model.CandidateApp
import com.yushyam.savesync.data.model.SnapshotItem
import com.yushyam.savesync.data.model.WebDavConfig
import com.yushyam.savesync.data.shell.PrivilegeMode
import com.yushyam.savesync.data.shell.ShellManager
import com.yushyam.savesync.data.storage.WebDavClient
import com.yushyam.savesync.engine.SyncCoordinator
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var shellManager: ShellManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shellManager = ShellManager(this)

        val shouldAutoSync = intent?.getBooleanExtra("auto_sync", false) ?: false

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF0F172A),
                    onPrimary = Color.White,
                    secondary = Color(0xFF334155),
                    background = Color(0xFFF8FAFC),
                    surface = Color.White,
                    surfaceVariant = Color(0xFFF1F5F9)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFFF8FAFC)
                ) {
                    MainScreen(
                        context = this,
                        shellManager = shellManager,
                        initialAutoSync = shouldAutoSync,
                        onShowToast = { msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    context: Context,
    shellManager: ShellManager,
    initialAutoSync: Boolean = false,
    onShowToast: (String) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var privilegeMode by remember { mutableStateOf(PrivilegeMode.NONE) }
    var privilegeDiagnosis by remember { mutableStateOf("檢測中...") }

    val prefs = remember { context.getSharedPreferences("savesync_prefs", Context.MODE_PRIVATE) }
    val gson = remember { Gson() }

    var webDavConfig by remember {
        mutableStateOf(
            WebDavConfig(
                url = prefs.getString("webdav_url", "http://10.0.2.2:8080/") ?: "http://10.0.2.2:8080/",
                username = prefs.getString("webdav_user", "") ?: "",
                password = prefs.getString("webdav_pass", "") ?: ""
            )
        )
    }

    var maxSnapshots by remember {
        mutableStateOf(prefs.getInt("max_snapshots", 3))
    }

    val appsList = remember { mutableStateListOf<AppSyncProfile>() }
    val appStatusMap = remember { mutableStateMapOf<String, AppSyncStatus>() }
    var isSyncingAll by remember { mutableStateOf(false) }

    var showSettingsDialog by remember { mutableStateOf(false) }
    var showAddAppDialog by remember { mutableStateOf(false) }
    var appToRemove by remember { mutableStateOf<AppSyncProfile?>(null) }
    var appForSnapshots by remember { mutableStateOf<AppSyncProfile?>(null) }
    var snapshotList by remember { mutableStateOf<List<SnapshotItem>>(emptyList()) }
    var installedAppCandidates by remember { mutableStateOf<List<CandidateApp>>(emptyList()) }

    fun saveAppsListToPrefs() {
        val json = gson.toJson(appsList.toList())
        prefs.edit().putString("saved_apps_list", json).apply()
    }

    fun loadAppsListFromPrefs(): List<AppSyncProfile> {
        val json = prefs.getString("saved_apps_list", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<AppSyncProfile>>() {}.type
            val loaded: List<AppSyncProfile> = gson.fromJson(json, type) ?: emptyList()
            loaded.map { profile ->
                val smart = com.yushyam.savesync.data.model.DefaultExcludes.getExcludesFor(profile.packageName)
                val merged = (profile.excludes + smart).distinct()
                if (merged != profile.excludes) profile.copy(excludes = merged) else profile
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun checkStatusForApp(profile: AppSyncProfile) {
        coroutineScope.launch(Dispatchers.IO) {
            val coordinator = SyncCoordinator(context, shellManager, webDavConfig)
            val st = coordinator.checkAppStatus(profile)
            withContext(Dispatchers.Main) {
                appStatusMap[profile.packageName] = st
            }
        }
    }

    fun refreshAllStatuses() {
        appsList.forEach { checkStatusForApp(it) }
    }

    fun refreshPrivilegeAndApps() {
        coroutineScope.launch(Dispatchers.IO) {
            val detected = shellManager.detectPrivilege()
            val diag = shellManager.getPrivilegeDiagnosis()
            withContext(Dispatchers.Main) {
                privilegeMode = detected
                privilegeDiagnosis = diag
            }

            val saved = loadAppsListFromPrefs()
            if (saved.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    appsList.clear()
                    appsList.addAll(saved)
                }
            } else {
                withContext(Dispatchers.Main) {
                    appsList.clear()
                }
            }

            // 啟動後自動檢查雲端存檔狀態
            withContext(Dispatchers.Main) {
                refreshAllStatuses()
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshPrivilegeAndApps()
        if (initialAutoSync) {
            coroutineScope.launch {
                kotlinx.coroutines.delay(1200)
                if (privilegeMode != PrivilegeMode.NONE && appsList.isNotEmpty()) {
                    isSyncingAll = true
                    var successCount = 0
                    withContext(Dispatchers.IO) {
                        val coordinator = SyncCoordinator(context, shellManager, webDavConfig)
                        appsList.forEach { app ->
                            appStatusMap[app.packageName] = AppSyncStatus.SYNCING
                            val (ok, _) = coordinator.autoSyncApp(app, maxSnapshots)
                            appStatusMap[app.packageName] = if (ok) AppSyncStatus.UP_TO_DATE else AppSyncStatus.ERROR
                            if (ok) successCount++
                        }
                    }
                    isSyncingAll = false
                    onShowToast("同步完成：$successCount / ${appsList.size}")
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "SaveSync",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = Color(0xFF0F172A),
                            letterSpacing = (-0.3).sp
                        )
                        Text(
                            "跨裝置存檔同步",
                            fontSize = 11.sp,
                            color = Color(0xFF64748B)
                        )
                    }
                },
                actions = {
                    // 系統提權模式膠囊標籤 (支援點擊查看深度診斷)
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (privilegeMode != PrivilegeMode.NONE) Color(0xFFDCFCE7) else Color(0xFFFEE2E2),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (privilegeMode != PrivilegeMode.NONE) Color(0xFF86EFAC) else Color(0xFFFCA5A5)
                        ),
                        modifier = Modifier
                            .clickable {
                                onShowToast(privilegeDiagnosis)
                                refreshPrivilegeAndApps()
                            }
                            .padding(end = 4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (privilegeMode != PrivilegeMode.NONE) Color(0xFF16A34A) else Color(0xFFDC2626)
                                    )
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            val modeLabel = when (privilegeMode) {
                                PrivilegeMode.ROOT -> "Root (UID 0)"
                                PrivilegeMode.SHIZUKU -> "Shizuku (正常)"
                                PrivilegeMode.NONE -> "未提權"
                            }
                            Text(
                                text = modeLabel,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (privilegeMode != PrivilegeMode.NONE) Color(0xFF15803D) else Color(0xFFB91C1C)
                            )
                        }
                    }

                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "雲端設定",
                            tint = Color(0xFF475569)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    coroutineScope.launch(Dispatchers.IO) {
                        val pm = context.packageManager
                        val list = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
                            .map { appInfo ->
                                val pkg = appInfo.packageName
                                val label = pm.getApplicationLabel(appInfo).toString()
                                val hasLauncher = pm.getLaunchIntentForPackage(pkg) != null
                                val isExt = pkg.startsWith("eu.kanade.tachiyomi.extension") ||
                                        pkg.startsWith("tachiyomi.extension") ||
                                        !hasLauncher
                                CandidateApp(packageName = pkg, displayName = label, isExtension = isExt)
                            }
                            .sortedWith(compareBy({ it.isExtension }, { it.displayName.lowercase() }))
                        withContext(Dispatchers.Main) {
                            installedAppCandidates = list
                            showAddAppDialog = true
                        }
                    }
                },
                containerColor = Color(0xFF0F172A),
                contentColor = Color.White,
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "新增同步應用")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(10.dp))

            // 模式警示卡片 (若未取得權限或 Shizuku 服務未啟動)
            if (privilegeMode == PrivilegeMode.NONE) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFFEF2F2),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFECACA)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.WarningAmber, contentDescription = null, tint = Color(0xFFDC2626), modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("提權狀態異常", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFF991B1B))
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = privilegeDiagnosis,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFB91C1C)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "模擬器請開啟 Root；實體機請開啟 Shizuku 服務後點擊標籤重新整理。",
                            fontSize = 11.sp,
                            color = Color(0xFF7F1D1D),
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            // 全局一鍵雙向同步按鈕
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF0F172A),
                shadowElevation = 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isSyncingAll && privilegeMode != PrivilegeMode.NONE) {
                        coroutineScope.launch {
                            isSyncingAll = true
                            var successCount = 0
                            withContext(Dispatchers.IO) {
                                val coordinator = SyncCoordinator(context, shellManager, webDavConfig)
                                appsList.forEach { app ->
                                    appStatusMap[app.packageName] = AppSyncStatus.SYNCING
                                    val (ok, _) = coordinator.autoSyncApp(app, maxSnapshots)
                                    appStatusMap[app.packageName] = if (ok) AppSyncStatus.UP_TO_DATE else AppSyncStatus.ERROR
                                    if (ok) successCount++
                                }
                            }
                            isSyncingAll = false
                            onShowToast("同步完成：$successCount / ${appsList.size}")
                        }
                    }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (isSyncingAll) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("同步中...", color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    } else {
                        Icon(Icons.Default.Sync, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("全部同步", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 清單標題列
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "同步清單 (${appsList.size})",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF475569)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "· 長按卡片移除",
                        fontSize = 11.sp,
                        color = Color(0xFF94A3B8)
                    )
                }
                Text(
                    "重新整理",
                    fontSize = 12.sp,
                    color = Color(0xFF2563EB),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { refreshAllStatuses() }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 應用列表
            if (appsList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.CloudQueue, contentDescription = null, tint = Color(0xFFCBD5E1), modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("尚未加入應用", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF64748B))
                        Text("點擊右下角「+」加入", fontSize = 12.sp, color = Color(0xFF94A3B8))
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(appsList) { app ->
                        val status = appStatusMap[app.packageName] ?: AppSyncStatus.UNCHECKED
                        AppCardItemClean(
                            app = app,
                            status = status,
                            onSyncClick = {
                                if (privilegeMode == PrivilegeMode.NONE) {
                                    onShowToast("無提權權限\n$privilegeDiagnosis")
                                    return@AppCardItemClean
                                }
                                coroutineScope.launch {
                                    appStatusMap[app.packageName] = AppSyncStatus.SYNCING
                                    val (ok, message) = withContext(Dispatchers.IO) {
                                        val coordinator = SyncCoordinator(context, shellManager, webDavConfig)
                                        coordinator.autoSyncApp(app, maxSnapshots)
                                    }
                                    appStatusMap[app.packageName] = if (ok) AppSyncStatus.UP_TO_DATE else AppSyncStatus.ERROR
                                    onShowToast("${app.displayName}: $message")
                                }
                            },
                            onDeleteClick = {
                                appToRemove = app
                            },
                            onShowSnapshots = {
                                coroutineScope.launch(Dispatchers.IO) {
                                    val coordinator = SyncCoordinator(context, shellManager, webDavConfig)
                                    val snaps = coordinator.listSnapshots(app.packageName)
                                    withContext(Dispatchers.Main) {
                                        snapshotList = snaps
                                        appForSnapshots = app
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // 歷史快照管理對話框
    if (appForSnapshots != null) {
        val targetApp = appForSnapshots!!
        var isBackingUp by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { appForSnapshots = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.History, contentDescription = null, tint = Color(0xFF0F172A))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("本機快照歷史", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        targetApp.displayName,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A)
                    )
                    Text(
                        "同步前自動保留最新 $maxSnapshots 份快照。",
                        fontSize = 11.sp,
                        color = Color(0xFF64748B)
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    // 手動立即備份按鈕
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                isBackingUp = true
                                val ok = withContext(Dispatchers.IO) {
                                    val coordinator = SyncCoordinator(context, shellManager, webDavConfig)
                                    val created = coordinator.createLocalRollbackSnapshot(targetApp, maxSnapshots)
                                    if (created) {
                                        val updated = coordinator.listSnapshots(targetApp.packageName)
                                        withContext(Dispatchers.Main) {
                                            snapshotList = updated
                                        }
                                    }
                                    created
                                }
                                isBackingUp = false
                                onShowToast(if (ok) "已建立快照" else "快照建立失敗")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                        enabled = !isBackingUp
                    ) {
                        if (isBackingUp) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("建立快照中...", fontSize = 12.sp)
                        } else {
                            Icon(Icons.Default.AddCircleOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("建立快照", fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = Color(0xFFE2E8F0))
                    Spacer(modifier = Modifier.height(10.dp))

                    if (snapshotList.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "尚無快照，可點擊上方按鈕建立。",
                                fontSize = 12.sp,
                                color = Color(0xFF64748B),
                                lineHeight = 18.sp
                            )
                        }
                    } else {
                        // 使用自適應高度 heightIn(max = 300.dp)，杜絕 3 個項目產生不必要滾動
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 300.dp)
                        ) {
                            items(snapshotList) { item ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0xFFF8FAFC),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .padding(horizontal = 12.dp, vertical = 8.dp)
                                            .fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                item.formattedDate,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp,
                                                color = Color(0xFF0F172A)
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                item.sizeFormatted,
                                                fontSize = 11.sp,
                                                color = Color(0xFF64748B)
                                            )
                                        }
                                        Button(
                                            onClick = {
                                                coroutineScope.launch {
                                                    val ok = withContext(Dispatchers.IO) {
                                                        val coordinator = SyncCoordinator(context, shellManager, webDavConfig)
                                                        coordinator.restoreSnapshot(targetApp, item)
                                                    }
                                                    appForSnapshots = null
                                                    onShowToast(if (ok) "已還原存檔" else "還原失敗")
                                                }
                                            },
                                            shape = RoundedCornerShape(6.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A)),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier.height(30.dp)
                                        ) {
                                            Text("還原", fontSize = 11.sp, color = Color.White)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { appForSnapshots = null }) {
                    Text("關閉", color = Color(0xFF0F172A))
                }
            }
        )
    }

    // 刪除確認對話框
    if (appToRemove != null) {
        val target = appToRemove!!
        AlertDialog(
            onDismissRequest = { appToRemove = null },
            title = { Text("移除應用", fontWeight = FontWeight.Bold) },
            text = { Text("確定從清單移除「${target.displayName}」？\n（不會刪除本機存檔與雲端備份）", fontSize = 13.sp) },
            confirmButton = {
                TextButton(
                    onClick = {
                        appsList.removeAll { it.packageName == target.packageName }
                        saveAppsListToPrefs()
                        appToRemove = null
                        onShowToast("已移除：${target.displayName}")
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFDC2626))
                ) {
                    Text("移除", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { appToRemove = null }) {
                    Text("取消", color = Color(0xFF64748B))
                }
            }
        )
    }

    // 全新美編大師重構之設定面板
    if (showSettingsDialog) {
        var url by remember { mutableStateOf(webDavConfig.url) }
        var user by remember { mutableStateOf(webDavConfig.username) }
        var pass by remember { mutableStateOf(webDavConfig.password) }
        var showPassword by remember { mutableStateOf(false) }
        var selectedSnapshots by remember { mutableStateOf(maxSnapshots) }
        var autoSyncEnabled by remember { mutableStateOf(prefs.getBoolean("pref_auto_sync_enabled", false)) }
        var isTesting by remember { mutableStateOf(false) }

        Dialog(
            onDismissRequest = { showSettingsDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .heightIn(max = 620.dp),
                shape = RoundedCornerShape(18.dp),
                color = Color.White,
                shadowElevation = 8.dp
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // 頂部標題列 (固定釘選，不隨內容滾動)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Tune, contentDescription = null, tint = Color(0xFF0F172A), modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("設定", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color(0xFF0F172A))
                        }
                        IconButton(onClick = { showSettingsDialog = false }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "關閉", tint = Color(0xFF64748B), modifier = Modifier.size(18.dp))
                        }
                    }

                    HorizontalDivider(color = Color(0xFFE2E8F0))

                    // 可滾動內容區塊
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // 分區一：雲端儲存伺服器
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFF8FAFC),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CloudQueue, contentDescription = null, tint = Color(0xFF0F172A), modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("WebDAV 伺服器", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFF0F172A))
                                }

                                OutlinedTextField(
                                    value = url,
                                    onValueChange = { url = it },
                                    label = { Text("網址 (URL)") },
                                    leadingIcon = { Icon(Icons.Default.Dns, contentDescription = null, tint = Color(0xFF64748B), modifier = Modifier.size(18.dp)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                OutlinedTextField(
                                    value = user,
                                    onValueChange = { user = it },
                                    label = { Text("帳號") },
                                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = Color(0xFF64748B), modifier = Modifier.size(18.dp)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                OutlinedTextField(
                                    value = pass,
                                    onValueChange = { pass = it },
                                    label = { Text("密碼") },
                                    leadingIcon = { Icon(Icons.Default.Key, contentDescription = null, tint = Color(0xFF64748B), modifier = Modifier.size(18.dp)) },
                                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = {
                                        IconButton(onClick = { showPassword = !showPassword }, modifier = Modifier.size(24.dp)) {
                                            Icon(
                                                if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                contentDescription = null,
                                                tint = Color(0xFF64748B),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Button(
                                    onClick = {
                                        coroutineScope.launch {
                                            isTesting = true
                                            val ok = withContext(Dispatchers.IO) {
                                                WebDavClient(WebDavConfig(url, user, pass)).testConnection()
                                            }
                                            isTesting = false
                                            onShowToast(if (ok) "連線成功" else "連線失敗，請檢查網址與帳密")
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(vertical = 8.dp),
                                    enabled = !isTesting
                                ) {
                                    if (isTesting) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("連線中...", fontSize = 12.sp)
                                    } else {
                                        Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("測試連線", fontSize = 12.sp)
                                    }
                                }
                            }
                        }

                        // 分區二：本機歷史快照上限選擇 (精緻晶片切換器)
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFF8FAFC),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.History, contentDescription = null, tint = Color(0xFF0F172A), modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("快照保留數量", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFF0F172A))
                                }
                                Text("覆蓋前保留快照份數：", fontSize = 11.sp, color = Color(0xFF64748B))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf(3 to "3 份", 5 to "5 份", 10 to "10 份").forEach { (count, label) ->
                                        val isSelected = selectedSnapshots == count
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (isSelected) Color(0xFF0F172A) else Color.White,
                                            border = androidx.compose.foundation.BorderStroke(
                                                1.dp,
                                                if (isSelected) Color(0xFF0F172A) else Color(0xFFCBD5E1)
                                            ),
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable { selectedSnapshots = count }
                                        ) {
                                            Box(
                                                modifier = Modifier.padding(vertical = 8.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = label,
                                                    fontSize = 11.sp,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (isSelected) Color.White else Color(0xFF334155)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 分區三：自動化排程與極簡觸發指令
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFF8FAFC),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("背景自動同步", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFF0F172A))
                                        Text("每 6 小時自動同步", fontSize = 11.sp, color = Color(0xFF64748B))
                                    }
                                    Switch(
                                        checked = autoSyncEnabled,
                                        onCheckedChange = { autoSyncEnabled = it }
                                    )
                                }

                                HorizontalDivider(color = Color(0xFFE2E8F0))

                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("廣播觸發指令：", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF475569))
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = Color(0xFF0F172A),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                                .fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                "am broadcast -a sync.all",
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color(0xFF38BDF8)
                                            )
                                            IconButton(
                                                onClick = {
                                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                    clipboard.setPrimaryClip(ClipData.newPlainText("SyncCmd", "am broadcast -a sync.all"))
                                                    onShowToast("已複製指令")
                                                },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.ContentCopy,
                                                    contentDescription = "複製指令",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    HorizontalDivider(color = Color(0xFFE2E8F0))

                    // 底部常駐操作按鈕列 (固定釘選，永不被內容推擠或遮擋)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { showSettingsDialog = false },
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text("取消", color = Color(0xFF64748B), fontSize = 13.sp)
                        }

                        Button(
                            onClick = {
                                maxSnapshots = selectedSnapshots
                                webDavConfig = WebDavConfig(url, user, pass)
                                prefs.edit()
                                    .putString("webdav_url", url)
                                    .putString("webdav_user", user)
                                    .putString("webdav_pass", pass)
                                    .putInt("max_snapshots", selectedSnapshots)
                                    .putBoolean("pref_auto_sync_enabled", autoSyncEnabled)
                                    .apply()

                                if (autoSyncEnabled) {
                                    val workRequest = PeriodicWorkRequestBuilder<com.yushyam.savesync.worker.SyncWorker>(6, TimeUnit.HOURS)
                                        .setConstraints(
                                            Constraints.Builder()
                                                .setRequiresBatteryNotLow(true)
                                                .build()
                                        )
                                        .build()
                                    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                                        "savesync_periodic_sync",
                                        ExistingPeriodicWorkPolicy.UPDATE,
                                        workRequest
                                    )
                                } else {
                                    WorkManager.getInstance(context).cancelUniqueWork("savesync_periodic_sync")
                                }

                                showSettingsDialog = false
                                onShowToast("設定已儲存")
                                refreshAllStatuses()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A)),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text("儲存", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.White)
                        }
                    }
                }
            }
        }
    }

    // 新增應用對話框 (支援即時搜尋與外掛/漫畫套件過濾)
    if (showAddAppDialog) {
        var searchQuery by remember { mutableStateOf("") }
        var hideExtensions by remember { mutableStateOf(true) }

        val filteredCandidates = remember(installedAppCandidates, searchQuery, hideExtensions) {
            installedAppCandidates.filter { app ->
                if (hideExtensions && app.isExtension) {
                    false
                } else {
                    searchQuery.isBlank() ||
                        app.displayName.contains(searchQuery, ignoreCase = true) ||
                        app.packageName.contains(searchQuery, ignoreCase = true)
                }
            }
        }

        val extensionCount = remember(installedAppCandidates) {
            installedAppCandidates.count { it.isExtension }
        }

        AlertDialog(
            onDismissRequest = { showAddAppDialog = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("新增同步應用", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color(0xFF0F172A))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFF1F5F9)
                    ) {
                        Text(
                            text = "${filteredCandidates.size} 個應用",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF475569),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // 即時搜尋框
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("搜尋應用名稱或套件包名...", fontSize = 12.sp, color = Color(0xFF94A3B8)) },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF64748B), modifier = Modifier.size(18.dp))
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = "清除", tint = Color(0xFF64748B), modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF0F172A),
                            unfocusedBorderColor = Color(0xFFCBD5E1)
                        )
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // 外掛過濾開關
                    if (extensionCount > 0) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { hideExtensions = !hideExtensions }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = hideExtensions,
                                onCheckedChange = { hideExtensions = it },
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "自動隱藏漫畫/外掛套件 (已收納 $extensionCount 個)",
                                fontSize = 12.sp,
                                color = Color(0xFF475569)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    HorizontalDivider(color = Color(0xFFE2E8F0))

                    // 候選清單
                    LazyColumn(modifier = Modifier.height(280.dp)) {
                        if (filteredCandidates.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(180.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(32.dp))
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text("無符合條件的應用程式", fontSize = 13.sp, color = Color(0xFF94A3B8))
                                    }
                                }
                            }
                        } else {
                            items(filteredCandidates, key = { it.packageName }) { app ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (appsList.none { it.packageName == app.packageName }) {
                                                val smartExcludes = com.yushyam.savesync.data.model.DefaultExcludes.getExcludesFor(app.packageName)
                                                val newProfile = AppSyncProfile(app.packageName, app.packageName, app.displayName, excludes = smartExcludes)
                                                appsList.add(newProfile)
                                                saveAppsListToPrefs()
                                                onShowToast("已加入: ${app.displayName}")
                                                checkStatusForApp(newProfile)
                                            }
                                            showAddAppDialog = false
                                        }
                                        .padding(vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(app.displayName, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Color(0xFF0F172A))
                                            if (app.isExtension) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = Color(0xFFF1F5F9)
                                                ) {
                                                    Text(
                                                        text = "外掛",
                                                        fontSize = 9.sp,
                                                        color = Color(0xFF64748B),
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Text(app.packageName, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF64748B))
                                    }
                                }
                                HorizontalDivider(color = Color(0xFFF1F5F9))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAddAppDialog = false }) {
                    Text("關閉", color = Color(0xFF0F172A))
                }
            }
        )
    }
}

// 極簡乾淨卡片元件：長按卡片直接觸發移除，底部保留純粹的「歷史版本」與「同步」按鈕
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppCardItemClean(
    app: AppSyncProfile,
    status: AppSyncStatus,
    onSyncClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onShowSnapshots: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0)),
        shadowElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(
                onClick = {},
                onLongClick = onDeleteClick
            )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // 第一列：應用名稱 (左側伸展) + 狀態徽章 (固定釘選在最右側，徹底杜絕位移)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = app.displayName,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color(0xFF0F172A),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(10.dp))

                // 狀態徽章固定釘在右側
                StatusPillBadge(status)
            }

            Spacer(modifier = Modifier.height(2.dp))

            // 第二列：套件識別碼 (微小等寬字型)
            Text(
                text = app.packageName,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF94A3B8),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = Color(0xFFF8FAFC))
            Spacer(modifier = Modifier.height(10.dp))

            // 第三列：操作功能列 (左：歷史快照；右：主要同步按鈕)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 歷史快照入口
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onShowSnapshots() }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Icon(
                        Icons.Default.History,
                        contentDescription = "快照清單",
                        tint = Color(0xFF64748B),
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        "歷史版本",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF475569)
                    )
                }

                // 主要操作：同步按鈕 (深黑底色 + 白色文字與圖示)
                Button(
                    onClick = onSyncClick,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF0F172A),
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp),
                    enabled = status != AppSyncStatus.SYNCING
                ) {
                    if (status == AppSyncStatus.SYNCING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("同步中", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    } else {
                        Icon(
                            Icons.Default.CloudUpload,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("同步", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

// 狀態膠囊標籤
@Composable
fun StatusPillBadge(status: AppSyncStatus) {
    val (bg, border, text) = when (status) {
        AppSyncStatus.UP_TO_DATE -> Triple(Color(0xFFDCFCE7), Color(0xFF86EFAC), Color(0xFF15803D))
        AppSyncStatus.NEED_PUSH -> Triple(Color(0xFFFEF3C7), Color(0xFFFCD34D), Color(0xFFB45309))
        AppSyncStatus.NEED_PULL -> Triple(Color(0xFFDBEAFE), Color(0xFF93C5FD), Color(0xFF1D4ED8))
        AppSyncStatus.CONFLICT -> Triple(Color(0xFFFEE2E2), Color(0xFFFCA5A5), Color(0xFFB91C1C))
        AppSyncStatus.NO_REMOTE -> Triple(Color(0xFFF1F5F9), Color(0xFFCBD5E1), Color(0xFF475569))
        AppSyncStatus.UNCHECKED -> Triple(Color(0xFFF8FAFC), Color(0xFFE2E8F0), Color(0xFF64748B))
        AppSyncStatus.SYNCING -> Triple(Color(0xFFF3E8FF), Color(0xFFD8B4FE), Color(0xFF7E22CE))
        AppSyncStatus.ERROR -> Triple(Color(0xFFFEE2E2), Color(0xFFFCA5A5), Color(0xFFB91C1C))
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = bg,
        border = androidx.compose.foundation.BorderStroke(1.dp, border)
    ) {
        Text(
            text = status.labelZh,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = text,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
        )
    }
}
