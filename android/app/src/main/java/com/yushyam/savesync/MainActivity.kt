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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
import com.yushyam.savesync.data.model.FileDetailItem
import com.yushyam.savesync.data.model.SnapshotItem
import com.yushyam.savesync.data.model.StorageInspection
import com.yushyam.savesync.data.model.SubpathDefinition
import com.yushyam.savesync.data.model.SubpathInspection
import com.yushyam.savesync.data.model.WebDavConfig
import com.yushyam.savesync.data.shell.PrivilegeMode
import com.yushyam.savesync.data.shell.ShellManager
import com.yushyam.savesync.data.storage.WebDavClient
import com.yushyam.savesync.engine.AppUpdater
import com.yushyam.savesync.engine.ReleaseUpdateInfo
import com.yushyam.savesync.engine.SyncCoordinator
import com.yushyam.savesync.engine.network.NetworkPolicyGatekeeper
import com.yushyam.savesync.engine.observer.UidImportanceWatcher
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import rikka.shizuku.Shizuku

fun formatRetentionSummary(maxSnapshots: Int, retentionDays: Int): String {
    return when {
        maxSnapshots > 0 && retentionDays > 0 -> "最多保留最新 $maxSnapshots 份，且超過 $retentionDays 天自動淘汰"
        maxSnapshots > 0 && retentionDays <= 0 -> "最多保留最新 $maxSnapshots 份版本"
        maxSnapshots <= 0 && retentionDays > 0 -> "保留 $retentionDays 天內所有版本"
        else -> "歷史版本全數永久保留"
    }
}

class MainActivity : ComponentActivity() {

    private lateinit var shellManager: ShellManager
    var onPrivilegeGrantedCallback: (() -> Unit)? = null

    private val requestPermissionResultListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            onPrivilegeGrantedCallback?.invoke()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            try {
                Shizuku.requestPermission(1001)
            } catch (e: Exception) {
                // ignore
            }
        } else {
            onPrivilegeGrantedCallback?.invoke()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shellManager = ShellManager(this)

        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addRequestPermissionResultListener(requestPermissionResultListener)
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(1001)
            }
        } catch (e: Exception) {
            // ignore
        }

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
                        onShowToast = { msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() },
                        registerPrivilegeCallback = { cb -> onPrivilegeGrantedCallback = cb }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener)
        } catch (e: Exception) {
            // ignore
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    context: Context,
    shellManager: ShellManager,
    initialAutoSync: Boolean = false,
    onShowToast: (String) -> Unit,
    registerPrivilegeCallback: (((() -> Unit)) -> Unit)? = null
) {
    val coroutineScope = rememberCoroutineScope()
    var privilegeMode by remember { mutableStateOf(PrivilegeMode.NONE) }
    var privilegeDiagnosis by remember { mutableStateOf("檢測中...") }

    val prefs = remember { context.getSharedPreferences("savesync_prefs", Context.MODE_PRIVATE) }
    val gson = remember { Gson() }

    var webDavConfig by remember {
        mutableStateOf(
            WebDavConfig(
                url = prefs.getString("webdav_url", "") ?: "",
                username = prefs.getString("webdav_user", "") ?: "",
                password = prefs.getString("webdav_pass", "") ?: ""
            )
        )
    }

    var minSnapshots by remember {
        mutableStateOf(prefs.getInt("min_snapshots", prefs.getInt("max_snapshots", 3)))
    }
    var retentionDays by remember {
        mutableStateOf(prefs.getInt("retention_days", 7))
    }
    var allowCellularSync by remember {
        mutableStateOf(prefs.getBoolean("allow_cellular_sync", true))
    }
    var smartUidSyncEnabled by remember {
        mutableStateOf(prefs.getBoolean("smart_uid_sync_enabled", true))
    }

    val appsList = remember { mutableStateListOf<AppSyncProfile>() }
    val appStatusMap = remember { mutableStateMapOf<String, AppSyncStatus>() }
    val appInspectionMap = remember { mutableStateMapOf<String, StorageInspection>() }
    val appSyncMessageMap = remember { mutableStateMapOf<String, String>() }
    var isSyncingAll by remember { mutableStateOf(false) }

    var showSettingsDialog by remember { mutableStateOf(false) }
    var showPrivilegeDialog by remember { mutableStateOf(false) }
    var showAddAppDialog by remember { mutableStateOf(false) }
    var appToRemove by remember { mutableStateOf<AppSyncProfile?>(null) }
    var appForSnapshots by remember { mutableStateOf<AppSyncProfile?>(null) }
    var appForConflict by remember { mutableStateOf<AppSyncProfile?>(null) }
    var appForSelectiveSync by remember { mutableStateOf<AppSyncProfile?>(null) }
    var appForForceSync by remember { mutableStateOf<AppSyncProfile?>(null) }
    var appForStatusDetail by remember { mutableStateOf<Pair<AppSyncProfile, AppSyncStatus>?>(null) }
    var fileListDialogData by remember { mutableStateOf<Pair<String, List<FileDetailItem>>?>(null) }
    var folderPickerCallback by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var folderPickerInitialPath by remember { mutableStateOf("/sdcard") }
    var snapshotList by remember { mutableStateOf<List<SnapshotItem>>(emptyList()) }
    var installedAppCandidates by remember { mutableStateOf<List<CandidateApp>>(emptyList()) }

    val currentVersionName = remember { AppUpdater.currentVersionName(context) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<ReleaseUpdateInfo?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var isDownloadingUpdate by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0) }
    var downloadedApkFile by remember { mutableStateOf<File?>(null) }

    fun checkForUpdate(manual: Boolean) {
        if (isCheckingUpdate) return
        isCheckingUpdate = true
        coroutineScope.launch(Dispatchers.IO) {
            val res = AppUpdater.checkLatestRelease(context)
            withContext(Dispatchers.Main) {
                isCheckingUpdate = false
                res.onSuccess { info ->
                    updateInfo = info
                    if (info.isUpdateAvailable) {
                        downloadedApkFile = null
                        downloadProgress = 0
                        showUpdateDialog = true
                    } else if (manual) {
                        onShowToast("目前已是最新版本 (v${info.currentVersion})")
                    }
                }.onFailure { err ->
                    if (manual) {
                        onShowToast("檢查更新失敗：${err.message ?: "網路異常"}")
                    }
                }
            }
        }
    }

    fun saveAppsListToPrefs() {
        val json = gson.toJson(appsList.toList())
        prefs.edit().putString("saved_apps_list", json).apply()
    }

    fun loadAppsListFromPrefs(): List<AppSyncProfile> {
        val json = prefs.getString("saved_apps_list", null) ?: return emptyList()
        return com.yushyam.savesync.data.model.AppSyncProfileCodec.parseList(json)
    }

    fun checkStatusForApp(profile: AppSyncProfile) {
        coroutineScope.launch(Dispatchers.IO) {
            val coordinator = SyncCoordinator(context, shellManager, webDavConfig)
            val st = coordinator.checkAppStatus(profile)
            val insp = coordinator.inspectStorage(profile)
            val err = coordinator.getWebDavError()
            withContext(Dispatchers.Main) {
                appStatusMap[profile.packageName] = st
                appInspectionMap[profile.packageName] = insp
                if (err != null && st == AppSyncStatus.ERROR) {
                    appSyncMessageMap[profile.packageName] = err
                }
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
        withContext(Dispatchers.IO) {
            AppUpdater.cleanCachedApks(context)
        }
        checkForUpdate(manual = false)
        registerPrivilegeCallback?.invoke {
            refreshPrivilegeAndApps()
        }
        refreshPrivilegeAndApps()
        if (initialAutoSync) {
            coroutineScope.launch {
                kotlinx.coroutines.delay(1200)
                if (appsList.isNotEmpty()) {
                    isSyncingAll = true
                    var successCount = 0
                    withContext(Dispatchers.IO) {
                        val coordinator = SyncCoordinator(context, shellManager, webDavConfig)
                        appsList.forEach { app ->
                            appStatusMap[app.packageName] = AppSyncStatus.SYNCING
                            val (ok, message) = coordinator.autoSyncApp(app, minSnapshots, retentionDays)
                            appStatusMap[app.packageName] = if (ok) AppSyncStatus.UP_TO_DATE else AppSyncStatus.ERROR
                            appSyncMessageMap[app.packageName] = message
                            if (ok) successCount++
                        }
                    }
                    isSyncingAll = false
                    onShowToast("同步完成：$successCount / ${appsList.size}")
                }
            }
        }
    }

    // 智慧離開感知監聽器：當監聽到清單內之目標 App 脫離前台，30 秒防抖後發起自動同步
    val uidWatcher = remember {
        UidImportanceWatcher(context, coroutineScope) { targetPkg ->
            val targetApp = appsList.find { it.packageName == targetPkg }
            if (targetApp != null && targetApp.isEnabled) {
                coroutineScope.launch(Dispatchers.IO) {
                    val coordinator = SyncCoordinator(context, shellManager, webDavConfig)
                    withContext(Dispatchers.Main) {
                        appStatusMap[targetApp.packageName] = AppSyncStatus.SYNCING
                    }
                    val (ok, msg) = coordinator.autoSyncApp(targetApp, minSnapshots, retentionDays)
                    withContext(Dispatchers.Main) {
                        appStatusMap[targetApp.packageName] = if (ok) AppSyncStatus.UP_TO_DATE else AppSyncStatus.ERROR
                        appSyncMessageMap[targetApp.packageName] = msg
                        onShowToast("${targetApp.displayName} 自動同步：${if (ok) "成功" else msg}")
                    }
                }
            }
        }
    }

    DisposableEffect(appsList.toList(), privilegeMode, smartUidSyncEnabled) {
        if (smartUidSyncEnabled && (privilegeMode == PrivilegeMode.SHIZUKU || privilegeMode == PrivilegeMode.ROOT)) {
            uidWatcher.updateTargetApps(appsList.toList())
            uidWatcher.startWatching()
        } else {
            uidWatcher.stopWatching()
        }
        onDispose {
            uidWatcher.stopWatching()
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
                    // 系統提權模式膠囊標籤
                    val hasPrivilege = privilegeMode != PrivilegeMode.NONE
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (hasPrivilege) Color(0xFFDCFCE7) else Color(0xFFF1F5F9),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (hasPrivilege) Color(0xFF86EFAC) else Color(0xFFCBD5E1)
                        ),
                        modifier = Modifier
                            .clickable {
                                showPrivilegeDialog = true
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
                                        if (hasPrivilege) Color(0xFF16A34A) else Color(0xFF94A3B8)
                                    )
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            val modeLabel = when (privilegeMode) {
                                PrivilegeMode.ROOT -> "Root"
                                PrivilegeMode.SHIZUKU -> "Shizuku"
                                PrivilegeMode.NONE -> "原生模式"
                            }
                            Text(
                                text = modeLabel,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (hasPrivilege) Color(0xFF15803D) else Color(0xFF475569)
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

            // 全局一鍵雙向同步按鈕
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF0F172A),
                shadowElevation = 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isSyncingAll && appsList.isNotEmpty()) {
                        coroutineScope.launch {
                            isSyncingAll = true
                            var successCount = 0
                            withContext(Dispatchers.IO) {
                                val coordinator = SyncCoordinator(context, shellManager, webDavConfig)
                                appsList.forEach { app ->
                                    appStatusMap[app.packageName] = AppSyncStatus.SYNCING
                                    val (ok, message) = coordinator.autoSyncApp(app, minSnapshots, retentionDays)
                                    appStatusMap[app.packageName] = if (ok) AppSyncStatus.UP_TO_DATE else AppSyncStatus.ERROR
                                    appSyncMessageMap[app.packageName] = message
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
                        val inspection = appInspectionMap[app.packageName]
                        AppCardItemClean(
                            app = app,
                            status = status,
                            inspection = inspection,
                            onSyncClick = {
                                if (status == AppSyncStatus.CONFLICT) {
                                    appForConflict = app
                                    return@AppCardItemClean
                                }
                                coroutineScope.launch {
                                    appStatusMap[app.packageName] = AppSyncStatus.SYNCING
                                    val (ok, message) = withContext(Dispatchers.IO) {
                                        val coordinator = SyncCoordinator(context, shellManager, webDavConfig)
                                        coordinator.autoSyncApp(app, minSnapshots, retentionDays)
                                    }
                                    appStatusMap[app.packageName] = if (ok) AppSyncStatus.UP_TO_DATE else AppSyncStatus.ERROR
                                    appSyncMessageMap[app.packageName] = message
                                    onShowToast("${app.displayName}: $message")
                                    if (ok) checkStatusForApp(app)
                                }
                            },
                            onSyncLongClick = {
                                appForForceSync = app
                            },
                            onDeleteClick = {
                                appToRemove = app
                            },
                            onShowSnapshots = {
                                coroutineScope.launch(Dispatchers.IO) {
                                    val coordinator = SyncCoordinator(context, shellManager, webDavConfig)
                                    val snaps = coordinator.listSnapshots(app.packageName, minSnapshots, retentionDays)
                                    withContext(Dispatchers.Main) {
                                        snapshotList = snaps
                                        appForSnapshots = app
                                    }
                                }
                            },
                            onSelectiveSyncClick = {
                                appForSelectiveSync = app
                            },
                            onStatusClick = {
                                appForStatusDetail = Pair(app, status)
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
                    Text("快照歷史", fontWeight = FontWeight.Bold, fontSize = 16.sp)
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
                        formatRetentionSummary(minSnapshots, retentionDays),
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
                                    val created = coordinator.createLocalRollbackSnapshot(targetApp, minSnapshots, retentionDays)
                                    if (created) {
                                        val updated = coordinator.listSnapshots(targetApp.packageName, minSnapshots, retentionDays)
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
                                "尚無快照",
                                fontSize = 12.sp,
                                color = Color(0xFF64748B)
                            )
                        }
                    } else {
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

                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(
                                                onClick = {
                                                    coroutineScope.launch {
                                                        val ok = withContext(Dispatchers.IO) {
                                                            val coordinator = SyncCoordinator(context, shellManager, webDavConfig)
                                                            coordinator.deleteSnapshot(item)
                                                        }
                                                        if (ok) {
                                                            val updated = withContext(Dispatchers.IO) {
                                                                val coordinator = SyncCoordinator(context, shellManager, webDavConfig)
                                                                coordinator.listSnapshots(targetApp.packageName)
                                                            }
                                                            snapshotList = updated
                                                            onShowToast("已刪除快照")
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.DeleteOutline,
                                                    contentDescription = "刪除快照",
                                                    tint = Color(0xFF94A3B8),
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }

                                            Spacer(modifier = Modifier.width(4.dp))

                                            Button(
                                                onClick = {
                                                    coroutineScope.launch {
                                                        val ok = withContext(Dispatchers.IO) {
                                                            val coordinator = SyncCoordinator(context, shellManager, webDavConfig)
                                                            coordinator.restoreSnapshot(targetApp, item)
                                                        }
                                                        appForSnapshots = null
                                                        onShowToast(if (ok) "已還原存檔" else "還原失敗")
                                                        if (ok) {
                                                            checkStatusForApp(targetApp)
                                                        }
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
                }
            },
            confirmButton = {
                TextButton(onClick = { appForSnapshots = null }) {
                    Text("關閉", color = Color(0xFF0F172A))
                }
            }
        )
    }

    // 存檔衝突決策對話框 (Conflict Resolution Dialog)
    if (appForConflict != null) {
        val targetApp = appForConflict!!
        var isResolving by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { if (!isResolving) appForConflict = null },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFDC2626)) },
            title = { Text("存檔衝突：${targetApp.displayName}", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "偵測到雲端存檔與本地存檔皆有更新（雙邊時間戳記不一致），請選擇解決策略：",
                        fontSize = 13.sp,
                        color = Color(0xFF334155),
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFF8FAFC),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("1. 保留本地 ➜ 強制覆蓋雲端 (Force Push)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                            Text("以此設備存檔為準，上傳覆蓋雲端", fontSize = 11.sp, color = Color(0xFF64748B))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("2. 捨棄本地 ➜ 強制下載雲端 (Force Pull)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF2563EB))
                            Text("以雲端存檔為準，下載覆蓋本地", fontSize = 11.sp, color = Color(0xFF64748B))
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "※ 執行前皆會自動建立本地備份快照",
                        fontSize = 11.sp,
                        color = Color(0xFF16A34A),
                        fontWeight = FontWeight.Medium
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            isResolving = true
                            appStatusMap[targetApp.packageName] = AppSyncStatus.SYNCING
                            val (ok, msg) = withContext(Dispatchers.IO) {
                                val coordinator = SyncCoordinator(context, shellManager, webDavConfig)
                                coordinator.forcePushApp(targetApp, minSnapshots, retentionDays)
                            }
                            isResolving = false
                            appForConflict = null
                            appStatusMap[targetApp.packageName] = if (ok) AppSyncStatus.UP_TO_DATE else AppSyncStatus.ERROR
                            appSyncMessageMap[targetApp.packageName] = msg
                            onShowToast(msg)
                            checkStatusForApp(targetApp)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A)),
                    enabled = !isResolving
                ) {
                    Text("強制上傳 (覆蓋雲端)")
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = { appForConflict = null },
                        enabled = !isResolving
                    ) {
                        Text("取消", color = Color(0xFF64748B))
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                isResolving = true
                                appStatusMap[targetApp.packageName] = AppSyncStatus.SYNCING
                                val (ok, msg) = withContext(Dispatchers.IO) {
                                    val coordinator = SyncCoordinator(context, shellManager, webDavConfig)
                                    coordinator.forcePullApp(targetApp, minSnapshots, retentionDays)
                                }
                                isResolving = false
                                appForConflict = null
                                appStatusMap[targetApp.packageName] = if (ok) AppSyncStatus.UP_TO_DATE else AppSyncStatus.ERROR
                                appSyncMessageMap[targetApp.packageName] = msg
                                onShowToast(msg)
                                checkStatusForApp(targetApp)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                        enabled = !isResolving
                    ) {
                        Text("強制下載 (覆蓋本地)")
                    }
                }
            }
        )
    }

    // 手動強制同步選單對話框 (長按同步按鈕觸發)
    if (appForForceSync != null) {
        val targetApp = appForForceSync!!
        var isOperating by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { if (!isOperating) appForForceSync = null },
            icon = { Icon(Icons.Default.Sync, contentDescription = null, tint = Color(0xFF0F172A)) },
            title = { Text("強制同步：${targetApp.displayName}", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "手動覆蓋雲端或本地存檔（執行前皆會自動建立備份快照）：",
                        fontSize = 13.sp,
                        color = Color(0xFF334155),
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFF8FAFC),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("1. 強制上傳 (Force Push)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                            Text("以此設備存檔為準，上傳覆蓋雲端", fontSize = 11.sp, color = Color(0xFF64748B))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("2. 強制下載 (Force Pull)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF2563EB))
                            Text("以雲端存檔為準，下載覆蓋本地", fontSize = 11.sp, color = Color(0xFF64748B))
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            isOperating = true
                            appStatusMap[targetApp.packageName] = AppSyncStatus.SYNCING
                            val (ok, msg) = withContext(Dispatchers.IO) {
                                val coordinator = SyncCoordinator(context, shellManager, webDavConfig)
                                coordinator.forcePushApp(targetApp, minSnapshots, retentionDays)
                            }
                            isOperating = false
                            appForForceSync = null
                            appStatusMap[targetApp.packageName] = if (ok) AppSyncStatus.UP_TO_DATE else AppSyncStatus.ERROR
                            appSyncMessageMap[targetApp.packageName] = msg
                            onShowToast(msg)
                            checkStatusForApp(targetApp)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A)),
                    enabled = !isOperating
                ) {
                    Text("強制上傳")
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = { appForForceSync = null },
                        enabled = !isOperating
                    ) {
                        Text("取消", color = Color(0xFF64748B))
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                isOperating = true
                                appStatusMap[targetApp.packageName] = AppSyncStatus.SYNCING
                                val (ok, msg) = withContext(Dispatchers.IO) {
                                    val coordinator = SyncCoordinator(context, shellManager, webDavConfig)
                                    coordinator.forcePullApp(targetApp, minSnapshots, retentionDays)
                                }
                                isOperating = false
                                appForForceSync = null
                                appStatusMap[targetApp.packageName] = if (ok) AppSyncStatus.UP_TO_DATE else AppSyncStatus.ERROR
                                appSyncMessageMap[targetApp.packageName] = msg
                                onShowToast(msg)
                                checkStatusForApp(targetApp)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                        enabled = !isOperating
                    ) {
                        Text("強制下載")
                    }
                }
            }
        )
    }

    // 同步狀態與詳細診斷對話框
    if (appForStatusDetail != null) {
        val (targetApp, targetStatus) = appForStatusDetail!!
        val targetInspection = appInspectionMap[targetApp.packageName]
        val privLabel = when (privilegeMode) {
            PrivilegeMode.ROOT -> "Root 提權"
            PrivilegeMode.SHIZUKU -> "Shizuku 提權"
            PrivilegeMode.NONE -> "原生模式"
        }
        val detailMsg = appSyncMessageMap[targetApp.packageName] ?: when (targetStatus) {
            AppSyncStatus.UP_TO_DATE -> "本機存檔與雲端一致，資料已是最新狀態"
            AppSyncStatus.NEED_PUSH -> "本機存檔有更新，尚未上傳至雲端"
            AppSyncStatus.NEED_PULL -> "雲端已有較新的存檔版本，待下載還原至本機"
            AppSyncStatus.CONFLICT -> "本機與雲端版本皆有變動，產生雙向衝突，需手動解決"
            AppSyncStatus.NO_REMOTE -> "雲端尚無備份存檔，點擊「同步」即可建立雲端基線"
            AppSyncStatus.UNCHECKED -> "尚未與雲端進行同步狀態校驗"
            AppSyncStatus.SYNCING -> "資料傳輸封裝進行中..."
            AppSyncStatus.ERROR -> "同步失敗，請檢查下方網路連線或伺服器設定"
        }
        val isError = targetStatus == AppSyncStatus.ERROR

        AlertDialog(
            onDismissRequest = { appForStatusDetail = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isError) Icons.Default.ErrorOutline else Icons.Default.Info,
                        contentDescription = null,
                        tint = if (isError) Color(0xFFDC2626) else Color(0xFF0F172A)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isError) "同步失敗診斷" else "同步狀態診斷",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    // 應用名稱與識別
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                targetApp.displayName,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF0F172A)
                            )
                            Text(
                                if (targetApp.customStoragePath.isNotBlank()) targetApp.customStoragePath else targetApp.packageName,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF64748B),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        StatusPillBadge(targetStatus)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 診斷說明卡片
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isError) Color(0xFFFEF2F2) else Color(0xFFF8FAFC),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isError) Color(0xFFFECACA) else Color(0xFFE2E8F0)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "診斷訊息",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isError) Color(0xFF991B1B) else Color(0xFF475569)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = detailMsg,
                                fontSize = 12.sp,
                                color = if (isError) Color(0xFFB91C1C) else Color(0xFF1E293B),
                                lineHeight = 16.sp
                            )
                            if (isError && webDavConfig.url.contains("10.0.2.2")) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "💡 提示：若使用實體裝置或外部 WebDAV，請至右上角 ⚙️ 設定正確的主機 IP（例如 http://192.168.x.x:8080/ 或 Nextcloud 網址），而非 10.0.2.2 模擬器位址",
                                    fontSize = 11.sp,
                                    color = Color(0xFF92400E)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 環境資訊
                    Text("運行環境與存檔檢測", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                    Spacer(modifier = Modifier.height(6.dp))

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFF8FAFC),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("特權模式", fontSize = 11.sp, color = Color(0xFF64748B))
                                Text(privLabel, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color(0xFF0F172A))
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("本機存檔", fontSize = 11.sp, color = Color(0xFF64748B))
                                Text(
                                    targetInspection?.statusSummary ?: "未檢測",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFF0F172A)
                                )
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("雲端伺服器", fontSize = 11.sp, color = Color(0xFF64748B))
                                Text(
                                    if (webDavConfig.url.isNotBlank()) webDavConfig.url else "尚未設定",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (webDavConfig.url.isNotBlank()) Color(0xFF0F172A) else Color(0xFFDC2626),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val timeStr = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()).format(Date())
                            val clipText = buildString {
                                appendLine("=== SaveSync 狀態診斷報告 ===")
                                appendLine("時間: $timeStr")
                                appendLine("應用名稱: ${targetApp.displayName}")
                                appendLine("套件識別: ${targetApp.packageName}")
                                if (targetApp.customStoragePath.isNotBlank()) {
                                    appendLine("自訂路徑: ${targetApp.customStoragePath}")
                                }
                                appendLine("當前狀態: ${targetStatus.labelZh}")
                                appendLine("診斷訊息: $detailMsg")
                                appendLine("特權模式: $privLabel")
                                appendLine("本機存檔: ${targetInspection?.statusSummary ?: "未檢測"}")
                                appendLine("WebDAV 伺服器: ${if (webDavConfig.url.isNotBlank()) webDavConfig.url else "未設定"}")
                                appendLine("=============================")
                            }
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("SaveSync_Diag", clipText))
                            onShowToast("已複製診斷報告至剪貼簿")
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("複製診斷資訊", fontSize = 12.sp)
                    }

                    Button(
                        onClick = {
                            val app = targetApp
                            appForStatusDetail = null
                            coroutineScope.launch {
                                appStatusMap[app.packageName] = AppSyncStatus.SYNCING
                                val (ok, message) = withContext(Dispatchers.IO) {
                                    val coordinator = SyncCoordinator(context, shellManager, webDavConfig)
                                    coordinator.autoSyncApp(app, minSnapshots, retentionDays)
                                }
                                appStatusMap[app.packageName] = if (ok) AppSyncStatus.UP_TO_DATE else AppSyncStatus.ERROR
                                appSyncMessageMap[app.packageName] = message
                                onShowToast("${app.displayName}: $message")
                                if (ok) checkStatusForApp(app)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("立即重試", fontSize = 12.sp)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { appForStatusDetail = null }) {
                    Text("關閉", color = Color(0xFF64748B), fontSize = 12.sp)
                }
            }
        )
    }

    // 應用同步設定對話框
    if (appForSelectiveSync != null) {
        val targetApp = appForSelectiveSync!!
        var customPathInput by remember(targetApp) { mutableStateOf(targetApp.customStoragePath) }
        var forceStopInput by remember(targetApp) { mutableStateOf(targetApp.forceStopBeforeRestore) }
        var isMirrorSyncInput by remember(targetApp) { mutableStateOf(targetApp.isMirrorSync) }
        var previewInspection by remember { mutableStateOf<StorageInspection?>(null) }

        val coordinator = remember { SyncCoordinator(context, shellManager, webDavConfig) }
        val resolvedDefaultPath = remember(targetApp) { coordinator.resolveAppDirectory(targetApp) }

        val subpathDefinitions = remember {
            listOf(
                SubpathDefinition(
                    key = "databases/",
                    titleZh = "核心資料庫"
                ),
                SubpathDefinition(
                    key = "shared_prefs/",
                    titleZh = "偏好設定檔"
                ),
                SubpathDefinition(
                    key = "files/",
                    titleZh = "應用檔案"
                )
            )
        }
        val availablePaths = remember { subpathDefinitions.map { it.key } }
        val currentSelected = remember(targetApp) {
            mutableStateListOf<String>().apply { addAll(targetApp.relativePaths ?: listOf("databases/", "shared_prefs/", "files/")) }
        }
        val extraPathsList = remember(targetApp) {
            mutableStateListOf<String>().apply { addAll(targetApp.extraStoragePaths ?: emptyList()) }
        }
        var subpathInspections by remember { mutableStateOf<Map<String, SubpathInspection>>(emptyMap()) }
        var extraInspections by remember { mutableStateOf<Map<String, StorageInspection>>(emptyMap()) }

        LaunchedEffect(customPathInput, targetApp, currentSelected.toList(), extraPathsList.toList()) {
            val trimmed = customPathInput.trim()
            val effectivePath = if (trimmed.isNotBlank()) trimmed else resolvedDefaultPath

            // 實地探測各子目錄狀態 (檔案數、體積、權限)
            val subMap = mutableMapOf<String, SubpathInspection>()
            var primaryRaw: StorageInspection? = null

            withContext(Dispatchers.IO) {
                availablePaths.forEach { p ->
                    subMap[p] = coordinator.inspectSubpath(effectivePath, p)
                }
                val anySubExists = subMap.values.any { it.exists }
                if (!anySubExists) {
                    primaryRaw = coordinator.inspectRawPath(effectivePath)
                }
            }
            subpathInspections = subMap

            // 實地探測附加目錄狀態
            val extraMap = mutableMapOf<String, StorageInspection>()
            withContext(Dispatchers.IO) {
                extraPathsList.forEach { ep ->
                    extraMap[ep] = coordinator.inspectRawPath(ep)
                }
            }
            extraInspections = extraMap

            val existingKeys = subMap.filter { it.value.exists }.keys
            // 若目前勾選項皆不存在但有其他存在的子目錄，自動選取存在的子目錄
            val validInSelection = currentSelected.filter { existingKeys.contains(it) }
            if (validInSelection.isEmpty() && existingKeys.isNotEmpty()) {
                currentSelected.clear()
                currentSelected.addAll(existingKeys)
            }

            val selectedExisting = currentSelected.filter { existingKeys.contains(it) }.mapNotNull { subMap[it] }

            val primaryCount = if (existingKeys.isNotEmpty()) {
                selectedExisting.sumOf { it.fileCount }
            } else {
                primaryRaw?.fileCount ?: 0
            }

            val primaryBytes = if (existingKeys.isNotEmpty()) {
                selectedExisting.sumOf { it.totalSizeBytes }
            } else {
                primaryRaw?.totalSizeBytes ?: 0L
            }

            val primaryHasInaccessible = if (existingKeys.isNotEmpty()) {
                selectedExisting.any { !it.isAccessible }
            } else {
                primaryRaw?.isValid == false && (primaryRaw?.statusSummary?.contains("權限") == true || primaryRaw?.statusSummary?.contains("沙盒") == true)
            }

            val extraInspList = extraPathsList.mapNotNull { extraMap[it] }
            val extraCount = extraInspList.sumOf { it.fileCount }
            val extraBytes = extraInspList.sumOf { it.totalSizeBytes }
            val extraHasInaccessible = extraInspList.any { !it.isValid && (it.statusSummary.contains("權限") || it.statusSummary.contains("沙盒")) }

            val totalCount = primaryCount + extraCount
            val totalBytes = primaryBytes + extraBytes
            val hasInaccessible = primaryHasInaccessible || extraHasInaccessible

            val sizeKb = totalBytes / 1024.0
            val sizeStr = if (sizeKb >= 1024) "%.1f MB".format(sizeKb / 1024.0) else "%.1f KB".format(sizeKb)

            val summary = when {
                hasInaccessible -> "⚠️ 包含受沙盒保護目錄，當前權限無法讀取"
                totalCount > 0 -> "已選備份：$totalCount 個檔案 · $sizeStr"
                else -> "未選取任何有效檔案 (0 個檔案)"
            }

            previewInspection = StorageInspection(
                path = effectivePath,
                fileCount = totalCount,
                totalSizeBytes = totalBytes,
                formattedSize = sizeStr,
                lastModifiedMs = System.currentTimeMillis(),
                formattedLastModified = "",
                isValid = totalCount > 0 && !hasInaccessible,
                statusSummary = summary
            )
        }

        AlertDialog(
            onDismissRequest = { appForSelectiveSync = null },
            icon = { Icon(Icons.Default.Tune, contentDescription = null, tint = Color(0xFF0F172A)) },
            title = { Text("${targetApp.displayName} 同步設定", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 自訂存檔路徑
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("程式資料路徑", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                            if (customPathInput.isNotBlank()) {
                                Text(
                                    text = "還原預設",
                                    fontSize = 11.sp,
                                    color = Color(0xFF2563EB),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable { customPathInput = "" }
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }

                        OutlinedTextField(
                            value = customPathInput,
                            onValueChange = { customPathInput = it },
                            placeholder = { Text(resolvedDefaultPath.ifBlank { "系統自動偵測" }, fontSize = 11.sp, color = Color(0xFF94A3B8)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                IconButton(onClick = {
                                    folderPickerInitialPath = customPathInput.ifBlank { resolvedDefaultPath.ifBlank { "/sdcard" } }
                                    folderPickerCallback = { selected -> customPathInput = selected }
                                }) {
                                    Icon(Icons.Default.FolderOpen, contentDescription = "瀏覽目錄", tint = Color(0xFF0F172A))
                                }
                            },
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        )

                        if (customPathInput.isBlank()) {
                            Text(
                                text = "預設路徑：$resolvedDefaultPath",
                                fontSize = 10.sp,
                                color = Color(0xFF64748B),
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (previewInspection != null) {
                            val insp = previewInspection!!
                            val hasFiles = insp.isValid && insp.fileCount > 0
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (hasFiles) Color(0xFFF0FDF4) else Color(0xFFFFFBEB),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (hasFiles) Color(0xFFBBF7D0) else Color(0xFFFDE68A)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 2.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        if (hasFiles) Icons.Default.CheckCircle else Icons.Default.WarningAmber,
                                        contentDescription = null,
                                        tint = if (hasFiles) Color(0xFF16A34A) else Color(0xFFD97706),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = insp.statusSummary,
                                        fontSize = 11.sp,
                                        color = if (hasFiles) Color(0xFF15803D) else Color(0xFFB45309)
                                    )
                                }
                            }
                        }
                    }

                    // 鏡像還原模式
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFF8FAFC),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("鏡像還原", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                                Text(
                                    text = "還原時刪除舊檔，與雲端完全一致",
                                    fontSize = 10.sp,
                                    color = Color(0xFF64748B)
                                )
                            }
                            Switch(
                                checked = isMirrorSyncInput,
                                onCheckedChange = { isMirrorSyncInput = it }
                            )
                        }
                    }

                    // 強制關閉程式
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFF8FAFC),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("強制關閉", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                                Text(
                                    text = if (privilegeMode == PrivilegeMode.NONE) "需 Shizuku 或 Root 權限" else "同步前先關閉程式，避免資料衝突",
                                    fontSize = 10.sp,
                                    color = Color(0xFF64748B)
                                )
                            }
                            Switch(
                                checked = forceStopInput && privilegeMode != PrivilegeMode.NONE,
                                onCheckedChange = { forceStopInput = it },
                                enabled = privilegeMode != PrivilegeMode.NONE
                            )
                        }
                    }

                    // 細粒度子目錄 (僅顯示該路徑下實際存在的子目錄，實事求是)
                    val existingSubDefs = subpathDefinitions.filter { subpathInspections[it.key]?.exists == true }
                    if (subpathInspections.isNotEmpty()) {
                        if (existingSubDefs.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("備份子項目", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                                existingSubDefs.forEach { subDef ->
                                    val path = subDef.key
                                    val isChecked = currentSelected.contains(path)
                                    val insp = subpathInspections[path]

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp, horizontal = 2.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable {
                                                    if (isChecked) {
                                                        if (currentSelected.size > 1) currentSelected.remove(path)
                                                    } else {
                                                        currentSelected.add(path)
                                                    }
                                                }
                                        ) {
                                            Checkbox(
                                                checked = isChecked,
                                                onCheckedChange = { checked ->
                                                    if (checked) currentSelected.add(path)
                                                    else if (currentSelected.size > 1) currentSelected.remove(path)
                                                },
                                                modifier = Modifier.size(24.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text(
                                                    subDef.titleZh,
                                                    fontSize = 11.5.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Color(0xFF0F172A),
                                                    maxLines = 1
                                                )
                                                Spacer(modifier = Modifier.width(3.dp))
                                                Text(
                                                    subDef.key.removeSuffix("/"),
                                                    fontSize = 9.5.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = Color(0xFF64748B),
                                                    maxLines = 1
                                                )
                                            }
                                        }

                                        // 子目錄即時狀態徽章 (點擊可檢視檔案清單)
                                        if (insp != null) {
                                            val badgeBg = when {
                                                !insp.isAccessible -> Color(0xFFFEF3C7)
                                                insp.fileCount > 0 -> Color(0xFFF0FDF4)
                                                else -> Color(0xFFF1F5F9)
                                            }
                                            val badgeColor = when {
                                                !insp.isAccessible -> Color(0xFFB45309)
                                                insp.fileCount > 0 -> Color(0xFF15803D)
                                                else -> Color(0xFF64748B)
                                            }
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = badgeBg,
                                                border = androidx.compose.foundation.BorderStroke(
                                                    1.dp,
                                                    if (insp.fileCount > 0) Color(0xFFBBF7D0) else Color(0xFFE2E8F0)
                                                ),
                                                modifier = Modifier
                                                    .padding(start = 6.dp)
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .clickable {
                                                        if (!insp.isAccessible) {
                                                            onShowToast("此目錄受系統沙盒保護，需切換至 Root 提權模式方能讀取")
                                                        } else if (insp.fileCount == 0) {
                                                            onShowToast("此目錄目前為空或尚未建立任何檔案")
                                                        } else {
                                                            coroutineScope.launch {
                                                                val effectivePath = if (customPathInput.trim().isNotBlank()) customPathInput.trim() else resolvedDefaultPath
                                                                val files = withContext(Dispatchers.IO) {
                                                                    coordinator.listSubpathFiles(effectivePath, path)
                                                                }
                                                                fileListDialogData = Pair("${targetApp.displayName} · ${subDef.titleZh} (${subDef.key})", files)
                                                            }
                                                        }
                                                    }
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                                ) {
                                                    Text(
                                                        text = insp.statusText,
                                                        fontSize = 10.sp,
                                                        fontWeight = if (insp.fileCount > 0) FontWeight.SemiBold else FontWeight.Normal,
                                                        color = badgeColor
                                                    )
                                                    if (insp.fileCount > 0) {
                                                        Spacer(modifier = Modifier.width(2.dp))
                                                        Icon(
                                                            Icons.Default.ChevronRight,
                                                            contentDescription = "查看明細",
                                                            tint = badgeColor,
                                                            modifier = Modifier.size(12.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFFF1F5F9),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            ) {
                                Text(
                                    text = "此路徑無標準子目錄 (databases / shared_prefs / files)，將同步整個目錄",
                                    fontSize = 11.sp,
                                    color = Color(0xFF64748B),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }

                    // 附加目錄 (支援多個外部自訂資料夾)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("附加目錄", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0xFFEFF6FF),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable {
                                        folderPickerInitialPath = "/sdcard"
                                        folderPickerCallback = { selected ->
                                            if (selected.isNotBlank() && !extraPathsList.contains(selected)) {
                                                extraPathsList.add(selected)
                                            }
                                        }
                                    }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, tint = Color(0xFF2563EB), modifier = Modifier.size(13.dp))
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text("新增目錄", fontSize = 11.sp, color = Color(0xFF2563EB), fontWeight = FontWeight.Medium)
                                }
                            }
                        }

                        if (extraPathsList.isEmpty()) {
                            Text("尚未設定附加目錄", fontSize = 10.5.sp, color = Color(0xFF94A3B8))
                        } else {
                            extraPathsList.forEachIndexed { idx, extraPath ->
                                val extraInsp = extraInspections[extraPath]
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0xFFF8FAFC),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = extraPath,
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Medium,
                                                color = Color(0xFF0F172A),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            val statusText = extraInsp?.let {
                                                if (it.isValid && it.fileCount > 0) "${it.fileCount} 個檔案 · ${it.formattedSize}"
                                                else it.statusSummary
                                            } ?: "探測中..."
                                            Text(
                                                text = statusText,
                                                fontSize = 10.sp,
                                                color = if (extraInsp?.isValid == true && extraInsp.fileCount > 0) Color(0xFF15803D) else Color(0xFF64748B)
                                            )
                                        }
                                        IconButton(
                                            onClick = { extraPathsList.removeAt(idx) },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = "移除",
                                                tint = Color(0xFF94A3B8),
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        targetApp.customStoragePath = customPathInput.trim()
                        targetApp.forceStopBeforeRestore = forceStopInput
                        targetApp.isMirrorSync = isMirrorSyncInput
                        val existingKeys = subpathInspections.filter { it.value.exists }.keys
                        targetApp.relativePaths = if (existingKeys.isNotEmpty()) {
                            currentSelected.filter { existingKeys.contains(it) }
                        } else {
                            emptyList()
                        }
                        targetApp.extraStoragePaths = extraPathsList.toList()
                        saveAppsListToPrefs()
                        checkStatusForApp(targetApp)
                        appForSelectiveSync = null
                        onShowToast("已儲存設定")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A))
                ) {
                    Text("儲存")
                }
            },
            dismissButton = {
                TextButton(onClick = { appForSelectiveSync = null }) {
                    Text("取消", color = Color(0xFF64748B))
                }
            }
        )
    }

    // 子目錄檔案清單檢視對話框
    if (fileListDialogData != null) {
        val (dialogTitle, fileList) = fileListDialogData!!
        AlertDialog(
            onDismissRequest = { fileListDialogData = null },
            icon = { Icon(Icons.Default.FolderOpen, contentDescription = null, tint = Color(0xFF0F172A)) },
            title = {
                Text(dialogTitle, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "共檢測到 ${fileList.size} 個檔案：",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF64748B),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    if (fileList.isEmpty()) {
                        Text("此目錄內無檔案", fontSize = 12.sp, color = Color(0xFF94A3B8))
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 280.dp)
                        ) {
                            items(fileList) { f ->
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0xFFF8FAFC),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(
                                                Icons.Default.Description,
                                                contentDescription = null,
                                                tint = Color(0xFF64748B),
                                                modifier = Modifier.size(15.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Column {
                                                Text(
                                                    f.name,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Color(0xFF0F172A),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                if (f.relativePath != f.name) {
                                                    Text(
                                                        f.relativePath,
                                                        fontSize = 9.sp,
                                                        fontFamily = FontFamily.Monospace,
                                                        color = Color(0xFF94A3B8),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            f.sizeFormatted,
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = Color(0xFF64748B)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { fileListDialogData = null },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("關閉", fontSize = 12.sp)
                }
            }
        )
    }

    // 全域單一實例目錄選擇器 (杜絕 AlertDialog 巢狀遮罩衝突)
    if (folderPickerCallback != null) {
        FolderPickerDialog(
            initialPath = folderPickerInitialPath,
            shellManager = shellManager,
            onDismiss = { folderPickerCallback = null },
            onFolderSelected = { selected ->
                val cb = folderPickerCallback
                folderPickerCallback = null
                cb?.invoke(selected)
            }
        )
    }
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
        var selectedMinSnapshots by remember { mutableStateOf(minSnapshots) }
        var selectedRetentionDays by remember { mutableStateOf(retentionDays) }
        var autoSyncEnabled by remember { mutableStateOf(prefs.getBoolean("pref_auto_sync_enabled", false)) }
        var selectedAllowCellular by remember { mutableStateOf(allowCellularSync) }
        var selectedSmartUidSync by remember { mutableStateOf(smartUidSyncEnabled) }
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
                                    placeholder = { Text("https://webdav.example.com/", color = Color(0xFF94A3B8)) },
                                    leadingIcon = { Icon(Icons.Default.Dns, contentDescription = null, tint = Color(0xFF64748B), modifier = Modifier.size(18.dp)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                OutlinedTextField(
                                    value = user,
                                    onValueChange = { user = it },
                                    label = { Text("帳號") },
                                    placeholder = { Text("WebDAV 帳號", color = Color(0xFF94A3B8)) },
                                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = Color(0xFF64748B), modifier = Modifier.size(18.dp)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                OutlinedTextField(
                                    value = pass,
                                    onValueChange = { pass = it },
                                    label = { Text("密碼") },
                                    placeholder = { Text("WebDAV 密碼", color = Color(0xFF94A3B8)) },
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

                        // 分區二：歷史快照保留策略 (雙軌制：保底份數 + 天數保留)
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
                                    Icon(Icons.Default.History, contentDescription = null, tint = Color(0xFF0F172A), modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("快照保留策略", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFF0F172A))
                                }

                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("最多保留份數：", fontSize = 11.sp, color = Color(0xFF64748B))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        listOf(3 to "3 份", 5 to "5 份", 10 to "10 份", 0 to "不限").forEach { (count, label) ->
                                            val isSelected = selectedMinSnapshots == count
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = if (isSelected) Color(0xFF0F172A) else Color.White,
                                                border = androidx.compose.foundation.BorderStroke(
                                                    1.dp,
                                                    if (isSelected) Color(0xFF0F172A) else Color(0xFFCBD5E1)
                                                ),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clickable { selectedMinSnapshots = count }
                                            ) {
                                                Box(
                                                    modifier = Modifier.padding(vertical = 7.dp),
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

                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("最長保留天數：", fontSize = 11.sp, color = Color(0xFF64748B))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        listOf(3 to "3 天", 7 to "7 天", 14 to "14 天", 0 to "不限").forEach { (days, label) ->
                                            val isSelected = selectedRetentionDays == days
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = if (isSelected) Color(0xFF0F172A) else Color.White,
                                                border = androidx.compose.foundation.BorderStroke(
                                                    1.dp,
                                                    if (isSelected) Color(0xFF0F172A) else Color(0xFFCBD5E1)
                                                ),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clickable { selectedRetentionDays = days }
                                            ) {
                                                Box(
                                                    modifier = Modifier.padding(vertical = 7.dp),
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

                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0xFFF1F5F9),
                                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
                                ) {
                                    Text(
                                        text = "💡 說明：" + formatRetentionSummary(selectedMinSnapshots, selectedRetentionDays),
                                        fontSize = 10.5.sp,
                                        color = Color(0xFF475569),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                    )
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
                                        Text("定時自動同步", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFF0F172A))
                                        Text("每 6 小時背景同步一次", fontSize = 11.sp, color = Color(0xFF64748B))
                                    }
                                    Switch(
                                        checked = autoSyncEnabled,
                                        onCheckedChange = { autoSyncEnabled = it }
                                    )
                                }

                                HorizontalDivider(color = Color(0xFFE2E8F0))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("智能背景同步", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFF0F172A))
                                        Text("退出 30 秒後自動上傳（需 Shizuku/Root）", fontSize = 11.sp, color = Color(0xFF64748B))
                                    }
                                    Switch(
                                        checked = selectedSmartUidSync,
                                        onCheckedChange = { selectedSmartUidSync = it }
                                    )
                                }

                                HorizontalDivider(color = Color(0xFFE2E8F0))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("行動網路同步", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFF0F172A))
                                        Text("關閉時僅限 Wi-Fi 同步", fontSize = 11.sp, color = Color(0xFF64748B))
                                    }
                                    Switch(
                                        checked = selectedAllowCellular,
                                        onCheckedChange = { selectedAllowCellular = it }
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

                        // 分區四：版本與線上更新
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFF8FAFC),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("版本與更新", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFF0F172A))
                                    Text("目前版本：v$currentVersionName", fontSize = 11.sp, color = Color(0xFF64748B))
                                }
                                OutlinedButton(
                                    onClick = { checkForUpdate(manual = true) },
                                    enabled = !isCheckingUpdate,
                                    shape = RoundedCornerShape(8.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFCBD5E1))
                                ) {
                                    Text(
                                        if (isCheckingUpdate) "檢查中..." else "檢查更新",
                                        fontSize = 12.sp,
                                        color = Color(0xFF0F172A)
                                    )
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
                                minSnapshots = selectedMinSnapshots
                                retentionDays = selectedRetentionDays
                                allowCellularSync = selectedAllowCellular
                                smartUidSyncEnabled = selectedSmartUidSync
                                webDavConfig = WebDavConfig(url, user, pass)
                                prefs.edit()
                                    .putString("webdav_url", url)
                                    .putString("webdav_user", user)
                                    .putString("webdav_pass", pass)
                                    .putInt("min_snapshots", selectedMinSnapshots)
                                    .putInt("max_snapshots", selectedMinSnapshots)
                                    .putInt("retention_days", selectedRetentionDays)
                                    .putBoolean("pref_auto_sync_enabled", autoSyncEnabled)
                                    .putBoolean("allow_cellular_sync", selectedAllowCellular)
                                    .putBoolean("smart_uid_sync_enabled", selectedSmartUidSync)
                                    .apply()

                                coroutineScope.launch(Dispatchers.IO) {
                                    val coordinator = SyncCoordinator(context, shellManager, webDavConfig)
                                    appsList.forEach { app ->
                                        coordinator.pruneSnapshots(app.packageName, selectedMinSnapshots, selectedRetentionDays)
                                    }
                                }

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

    if (showUpdateDialog && updateInfo != null) {
        val info = updateInfo!!
        AlertDialog(
            onDismissRequest = {
                if (!isDownloadingUpdate) showUpdateDialog = false
            },
            title = {
                Text("發現新版本 ${info.tagName}", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color(0xFF0F172A))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "目前版本：v${info.currentVersion} ➔ 最新版本：v${info.latestVersion}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF15803D)
                    )
                    Surface(
                        color = Color(0xFFF8FAFC),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            info.releaseNotes,
                            fontSize = 12.sp,
                            color = Color(0xFF334155),
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                    if (isDownloadingUpdate) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            LinearProgressIndicator(
                                progress = (downloadProgress / 100f).coerceIn(0f, 1f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(999.dp)),
                                color = Color(0xFF0F172A)
                            )
                            Text("下載中... $downloadProgress%", fontSize = 12.sp, color = Color(0xFF64748B))
                        }
                    } else if (downloadedApkFile != null) {
                        Text(
                            "下載完成！點擊「安裝更新」即可覆蓋升級（保留所有設定）。",
                            fontSize = 12.sp,
                            color = Color(0xFF15803D),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !isDownloadingUpdate,
                    onClick = {
                        val readyFile = downloadedApkFile
                        if (readyFile != null && readyFile.exists()) {
                            if (!AppUpdater.canInstallPackages(context)) {
                                onShowToast("請先開啟「允許安裝未知應用程式」，返回後點擊「安裝更新」")
                            }
                            AppUpdater.installApk(context, readyFile)
                        } else {
                            isDownloadingUpdate = true
                            downloadProgress = 0
                            coroutineScope.launch(Dispatchers.IO) {
                                val dl = AppUpdater.downloadApk(
                                    context = context,
                                    downloadUrl = info.downloadUrl,
                                    assetName = info.assetName,
                                    onProgress = { pct -> downloadProgress = pct }
                                )
                                withContext(Dispatchers.Main) {
                                    isDownloadingUpdate = false
                                    dl.onSuccess { file ->
                                        downloadedApkFile = file
                                        if (!AppUpdater.canInstallPackages(context)) {
                                            onShowToast("請先開啟「允許安裝未知應用程式」，返回後點擊「安裝更新」")
                                        }
                                        AppUpdater.installApk(context, file)
                                    }.onFailure { err ->
                                        onShowToast("下載失敗：${err.message ?: "網路錯誤"}")
                                    }
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        when {
                            isDownloadingUpdate -> "下載中..."
                            downloadedApkFile != null -> "安裝更新"
                            else -> "立即下載更新"
                        },
                        color = Color.White
                    )
                }
            },
            dismissButton = {
                if (!isDownloadingUpdate) {
                    TextButton(onClick = { showUpdateDialog = false }) {
                        Text("稍後", color = Color(0xFF64748B))
                    }
                }
            }
        )
    }

    // 權限與運作模式切換對話框
    if (showPrivilegeDialog) {
        var selectedPref by remember { mutableStateOf(shellManager.getPreferredMode()) }
        var currentDiagnosis by remember { mutableStateOf(shellManager.getPrivilegeDiagnosis()) }

        AlertDialog(
            onDismissRequest = { showPrivilegeDialog = false },
            icon = { Icon(Icons.Default.Security, contentDescription = null, tint = Color(0xFF0F172A)) },
            title = { Text("運作模式與權限", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("切換存取手機資料的權限等級：", fontSize = 11.sp, color = Color(0xFF64748B))

                    val options = listOf(
                        Triple("AUTO", "自動偵測 (建議)", "依序偵測 Root ➔ Shizuku ➔ 一般原生模式"),
                        Triple("SHIZUKU", "Shizuku 模式", "透過 Shizuku ADB 存取受保護資料"),
                        Triple("ROOT", "Root 模式", "使用 Superuser 存取完整私有目錄"),
                        Triple("NONE", "一般模式 (原生)", "純 Android 沙盒，僅同步公開與標準目錄")
                    )

                    options.forEach { (modeKey, title, desc) ->
                        val isSelected = selectedPref == modeKey
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) Color(0xFFF8FAFC) else Color.White,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isSelected) Color(0xFF0F172A) else Color(0xFFE2E8F0)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedPref = modeKey
                                    shellManager.setPreferredMode(modeKey)
                                    privilegeMode = shellManager.detectPrivilege()
                                    privilegeDiagnosis = shellManager.getPrivilegeDiagnosis()
                                    currentDiagnosis = privilegeDiagnosis
                                    refreshPrivilegeAndApps()
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        selectedPref = modeKey
                                        shellManager.setPreferredMode(modeKey)
                                        privilegeMode = shellManager.detectPrivilege()
                                        privilegeDiagnosis = shellManager.getPrivilegeDiagnosis()
                                        currentDiagnosis = privilegeDiagnosis
                                        refreshPrivilegeAndApps()
                                    },
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                                    Text(desc, fontSize = 10.sp, color = Color(0xFF64748B))
                                }
                            }
                        }
                    }

                    // 診斷狀態提示框
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFFF1F5F9),
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text("當前狀態：", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF475569))
                            Text(currentDiagnosis, fontSize = 11.sp, color = Color(0xFF0F172A))
                        }
                    }

                    // 重新請求 / 喚出 Shizuku 授權按鈕
                    OutlinedButton(
                        onClick = {
                            val msg = shellManager.requestShizukuPermission()
                            onShowToast(msg)
                            privilegeMode = shellManager.detectPrivilege()
                            privilegeDiagnosis = shellManager.getPrivilegeDiagnosis()
                            currentDiagnosis = privilegeDiagnosis
                            refreshPrivilegeAndApps()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("重新檢查 / 喚出 Shizuku 授權", fontSize = 12.sp, color = Color(0xFF0F172A))
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showPrivilegeDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A))
                ) {
                    Text("完成")
                }
            }
        )
    }

    // 新增應用對話框 (支援應用清單與手動目錄 File Sync 雙分頁)
    if (showAddAppDialog) {
        var selectedTab by remember { mutableStateOf(0) }
        var searchQuery by remember { mutableStateOf("") }
        var hideExtensions by remember { mutableStateOf(true) }

        var manualName by remember { mutableStateOf("") }
        var manualPath by remember { mutableStateOf("") }
        var manualInspection by remember { mutableStateOf<StorageInspection?>(null) }

        LaunchedEffect(manualPath) {
            val trimmed = manualPath.trim()
            if (trimmed.isNotBlank()) {
                val insp = withContext(Dispatchers.IO) {
                    val coordinator = SyncCoordinator(context, shellManager, webDavConfig)
                    coordinator.inspectStorage(trimmed)
                }
                manualInspection = insp
            } else {
                manualInspection = null
            }
        }

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
                Text("新增同步項目", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color(0xFF0F172A))
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // 分頁切換
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = Color(0xFFF1F5F9),
                        contentColor = Color(0xFF0F172A),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("應用程式", fontSize = 12.sp, fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal) }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("自訂目錄", fontSize = 12.sp, fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal) }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (selectedTab == 0) {
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
                    } else {
                        // 自訂目錄 (檔案同步)
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            OutlinedTextField(
                                value = manualName,
                                onValueChange = { manualName = it },
                                label = { Text("項目名稱") },
                                placeholder = { Text("例如：模擬器存檔 / 工作文件") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = manualPath,
                                onValueChange = { manualPath = it },
                                label = { Text("本機目錄絕對路徑") },
                                placeholder = { Text("/sdcard/Documents/Saves") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                trailingIcon = {
                                    IconButton(onClick = {
                                        folderPickerInitialPath = manualPath.ifBlank { "/sdcard" }
                                        folderPickerCallback = { selected ->
                                            manualPath = selected
                                            if (manualName.isBlank()) {
                                                val fName = File(selected).name
                                                manualName = if (fName.isNotBlank()) fName else "自訂目錄"
                                            }
                                        }
                                    }) {
                                        Icon(Icons.Default.FolderOpen, contentDescription = "瀏覽目錄", tint = Color(0xFF0F172A))
                                    }
                                },
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            )

                            // 常用路徑推薦快捷標籤
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("快速填入常用路徑：", fontSize = 11.sp, color = Color(0xFF64748B))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    listOf(
                                        "內部儲存" to "/sdcard",
                                        "文件" to "/sdcard/Documents",
                                        "下載" to "/sdcard/Download"
                                    ).forEach { (label, path) ->
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = Color(0xFFF1F5F9),
                                            modifier = Modifier
                                                .clickable {
                                                    manualPath = path
                                                    if (manualName.isBlank()) manualName = label
                                                }
                                                .padding(vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = label,
                                                fontSize = 10.sp,
                                                color = Color(0xFF334155),
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            // 即時事前檢驗提示框
                            if (manualInspection != null) {
                                val insp = manualInspection!!
                                val hasFiles = insp.isValid && insp.fileCount > 0
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (hasFiles) Color(0xFFF0FDF4) else Color(0xFFFFFBEB),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (hasFiles) Color(0xFFBBF7D0) else Color(0xFFFDE68A)
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            if (hasFiles) Icons.Default.CheckCircle else Icons.Default.WarningAmber,
                                            contentDescription = null,
                                            tint = if (hasFiles) Color(0xFF16A34A) else Color(0xFFD97706),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                text = if (hasFiles) "路徑檢查通過" else "目錄為空或不存在",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp,
                                                color = if (hasFiles) Color(0xFF15803D) else Color(0xFFB45309)
                                            )
                                            Text(
                                                text = insp.statusSummary,
                                                fontSize = 11.sp,
                                                color = if (hasFiles) Color(0xFF166534) else Color(0xFF92400E)
                                            )
                                        }
                                    }
                                }
                            }

                            Button(
                                onClick = {
                                    val cleanName = manualName.trim()
                                    val cleanPath = manualPath.trim()
                                    val pkgKey = "manual.${System.currentTimeMillis()}"
                                    val profile = AppSyncProfile(
                                        id = pkgKey,
                                        packageName = pkgKey,
                                        displayName = cleanName,
                                        customStoragePath = cleanPath,
                                        profileType = "MANUAL",
                                        relativePaths = emptyList()
                                    )
                                    appsList.add(profile)
                                    saveAppsListToPrefs()
                                    checkStatusForApp(profile)
                                    showAddAppDialog = false
                                    onShowToast("已新增：$cleanName")
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A)),
                                shape = RoundedCornerShape(8.dp),
                                enabled = manualName.isNotBlank() && manualPath.isNotBlank()
                            ) {
                                Text("加入同步清單", fontSize = 12.sp, color = Color.White)
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

// 視覺化目錄選擇器對話框
@Composable
fun FolderPickerDialog(
    initialPath: String = "/sdcard",
    shellManager: ShellManager,
    onDismiss: () -> Unit,
    onFolderSelected: (String) -> Unit
) {
    var currentPath by remember {
        val start = if (initialPath.isNotBlank() && initialPath.startsWith("/")) initialPath else "/sdcard"
        val f = File(start)
        mutableStateOf(if (f.exists() && f.isFile) (f.parent ?: "/sdcard") else start)
    }
    var subFolders by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    fun loadFolders(path: String) {
        coroutineScope.launch(Dispatchers.IO) {
            isLoading = true
            val folders = mutableListOf<String>()
            try {
                val dir = File(path)
                if (dir.exists() && dir.canRead()) {
                    val list = dir.listFiles()
                    if (list != null) {
                        list.filter { it.isDirectory && !it.name.startsWith(".") }
                            .map { it.name }
                            .sortedWith(String.CASE_INSENSITIVE_ORDER)
                            .let { folders.addAll(it) }
                    }
                }
            } catch (e: Exception) {
                // ignore
            }

            // 若標準 Java File 無法讀取（如特定保護目錄），且具備特權時嘗試透過 shellManager 讀取
            if (folders.isEmpty()) {
                try {
                    val out = shellManager.exec("ls -1 -p \"$path\" 2>/dev/null")
                    if (out.isNotBlank()) {
                        out.lines()
                            .map { it.trim() }
                            .filter { it.endsWith("/") && it.length > 1 && !it.startsWith(".") }
                            .map { it.removeSuffix("/") }
                            .sortedWith(String.CASE_INSENSITIVE_ORDER)
                            .let { folders.addAll(it) }
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }

            withContext(Dispatchers.Main) {
                subFolders = folders
                isLoading = false
            }
        }
    }

    LaunchedEffect(currentPath) {
        loadFolders(currentPath)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, tint = Color(0xFF0F172A))
                Spacer(modifier = Modifier.width(8.dp))
                Text("選擇目錄", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
            ) {
                // 當前路徑與上一層按鈕
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFF1F5F9),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                val parent = File(currentPath).parent
                                if (!parent.isNullOrBlank()) {
                                    currentPath = parent
                                }
                            },
                            enabled = currentPath != "/" && currentPath.length > 1,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = "上一層", modifier = Modifier.size(16.dp))
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = currentPath,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF0F172A),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // 常用捷徑標籤
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(
                        "內部儲存" to "/sdcard",
                        "文件" to "/sdcard/Documents",
                        "下載" to "/sdcard/Download",
                        "Android/data" to "/sdcard/Android/data"
                    ).forEach { (label, p) ->
                        val isSelected = currentPath == p
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (isSelected) Color(0xFF0F172A) else Color(0xFFE2E8F0),
                            modifier = Modifier.clickable { currentPath = p }
                        ) {
                            Text(
                                text = label,
                                fontSize = 10.sp,
                                color = if (isSelected) Color.White else Color(0xFF334155),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                HorizontalDivider(color = Color(0xFFE2E8F0))

                // 子目錄列表
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                } else if (subFolders.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("此目錄下無可見子資料夾", fontSize = 12.sp, color = Color(0xFF94A3B8))
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                    ) {
                        items(subFolders) { folder ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val next = if (currentPath == "/") "/$folder" else "$currentPath/$folder"
                                        currentPath = next
                                    }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = Color(0xFF3B82F6),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(folder, fontSize = 12.sp, color = Color(0xFF1E293B), modifier = Modifier.weight(1f))
                            }
                            HorizontalDivider(color = Color(0xFFF1F5F9))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onFolderSelected(currentPath)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F172A))
            ) {
                Text("選擇此目錄")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = Color(0xFF64748B))
            }
        }
    )
}

// 極簡乾淨卡片元件：長按卡片直接觸發移除，底部保留純粹的「歷史版本」與「同步」按鈕
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppCardItemClean(
    app: AppSyncProfile,
    status: AppSyncStatus,
    inspection: StorageInspection?,
    onSyncClick: () -> Unit,
    onSyncLongClick: () -> Unit = {},
    onDeleteClick: () -> Unit,
    onShowSnapshots: () -> Unit,
    onSelectiveSyncClick: () -> Unit,
    onStatusClick: () -> Unit
) {
    val isConflict = status == AppSyncStatus.CONFLICT
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isConflict) Color(0xFFFCA5A5) else Color(0xFFE2E8F0)
        ),
        shadowElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(
                onClick = onSelectiveSyncClick,
                onLongClick = onDeleteClick
            )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // 第一列：應用名稱 (左側伸展) + 狀態徽章 (固定釘選在最右側，點擊查看診斷)
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

                // 狀態徽章固定釘在右側 (點擊查看詳細診斷)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { onStatusClick() }
                ) {
                    StatusPillBadge(status)
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            // 第二列：套件識別碼或手動路徑
            Text(
                text = if (app.customStoragePath.isNotBlank()) app.customStoragePath else app.packageName,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF94A3B8),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // 第三列：本機存檔預檢狀態 (即時顯示檔案數與體積)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp)
            ) {
                val hasFiles = inspection?.isValid == true && inspection.fileCount > 0
                Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    tint = if (hasFiles) Color(0xFF64748B) else Color(0xFFD97706),
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = inspection?.statusSummary ?: "掃描存檔中...",
                    fontSize = 11.sp,
                    color = if (hasFiles) Color(0xFF64748B) else Color(0xFFD97706),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = Color(0xFFF8FAFC))
            Spacer(modifier = Modifier.height(10.dp))

            // 第四列：操作功能列 (左：歷史版本；右：主要同步/解決衝突按鈕)
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

                // 主要操作：同步按鈕 (短按觸發智慧同步；長按彈出強制同步選單；衝突時紅底顯示「解決衝突」)
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isConflict) Color(0xFFDC2626) else Color(0xFF0F172A),
                    contentColor = Color.White,
                    modifier = Modifier
                        .height(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .combinedClickable(
                            enabled = status != AppSyncStatus.SYNCING,
                            onClick = onSyncClick,
                            onLongClick = onSyncLongClick
                        )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (status == AppSyncStatus.SYNCING) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("同步中", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        } else if (isConflict) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("解決衝突", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        } else if (status == AppSyncStatus.NEED_PULL) {
                            Icon(
                                Icons.Default.CloudDownload,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("下載", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        } else if (status == AppSyncStatus.NEED_PUSH) {
                            Icon(
                                Icons.Default.CloudUpload,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("上傳", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        } else {
                            Icon(
                                Icons.Default.Sync,
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
