# SaveSync

> **Android 輕量級跨裝置存檔與資料雙向自動同調系統（Steam Cloud for Android）。**
>
> 專為多裝置玩家、模擬器愛好者與重度用戶打造。支援 Shizuku（免 Root）與 Superuser Root 雙軌提權，透過標準 WebDAV 協定實現無感同步。

<p align="center">
  <a href="https://github.com/YuShyam/SaveSync/releases/latest">
    <img src="https://img.shields.io/github/v/release/YuShyam/SaveSync?label=Latest%20Release&color=blue" alt="Latest Release">
  </a>
  <a href="https://github.com/YuShyam/SaveSync/releases/latest">
    <img src="https://img.shields.io/badge/Download-SaveSync--v1.0.0.apk-brightgreen.svg" alt="Download APK">
  </a>
  <img src="https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg" alt="Platform">
  <img src="https://img.shields.io/badge/Auth-Basic%20%2B%20Digest-orange.svg" alt="Authentication">
  <img src="https://img.shields.io/badge/License-MIT-lightgrey.svg" alt="License">
</p>

---

## 📥 下載與安裝 (Download)

最新正式發布版本可至 GitHub Releases 下載：

👉 **[前往下載最新版 APK (SaveSync-v1.0.0.apk)](https://github.com/YuShyam/SaveSync/releases/latest)**

- **支援環境**：Android 8.0 (API 26) 以上。
- **特權模式**：
  - **實體手機（免 Root）**：透過 [Shizuku](https://shizuku.rikka.app/) 無線偵錯即可免解鎖存取應用私有沙盒。
  - **模擬器 / 已 Root 裝置**：支援雷電、MuMu 等模擬器與 Magisk / KernelSU，自動切換 Superuser Root (UID 0)。

---

## 📖 核心痛點與產品定位

在多裝置環境（實體手機、平板、電腦 Android 模擬器）之間切換使用時，資料同步長期面臨以下痛點：

1. **Mihon 漫畫閱讀進度**：換裝置閱讀必須「手動匯入還原」，自架伺服器又無法兼顧離線閱讀優勢。
2. **模擬器與單機遊戲存檔（NS 模擬器、離線手遊）**：電腦模擬器玩了進度，出門想用手機接續遊玩時，存檔往往鎖在系統內部 `/data/data/` 沙盒中，無法直接傳輸。
3. **輸入法與日常工具詞庫（如 Rime / Trime）**：跨裝置個人化設定與詞庫缺乏即時雙向同調。
4. **現成工具局限**：Tasker 需單機付費且多開配置繁瑣；DataBackup（笨蛋備份）偏向災難備份，缺乏時間戳記智慧雙向比對。

`SaveSync` 定位為一個**通用型、全自動、以時間戳記比對為核心**的跨裝置同步系統：

```mermaid
flowchart LR
    subgraph DeviceA [裝置 A (實體手機 / Shizuku)]
        AppA[目標應用沙盒] -->|凍結 + 去噪打包| SyncA[SaveSync 引擎]
    end

    subgraph Cloud [標準 WebDAV 雲端空間]
        Vault[雲端存檔庫 (CloudDrive / NAS)]
        Manifest[收據比對 (manifest.json)]
    end

    subgraph DeviceB [裝置 B (電腦模擬器 / Root)]
        SyncB[SaveSync 引擎] -->|自動還原 + chown 修復| AppB[接續遊戲進度]
    end

    SyncA <-->|PUT / GET / PROPFIND| Cloud
    Cloud <-->|PUT / GET / PROPFIND| SyncB
```

---

## ✨ 核心特色與防衛性設計

- **⚖️ 智慧雙向比對 (Manifest SHA256 Diff)**：
  自動計算本機檔案 SHA256 與遠端 Manifest 中繼收據，精準判定 `最新`、`待上傳`、`可下載`、`衝突` 與 `無備份`。
- **🛡️ 防衛性存檔保障 (Zero Save Loss Guarantee)**：
  - 覆蓋或還原資料前，**強制打包本機 3 版滾動歷史快照**，支援一鍵即時回滾。
  - 備份或還原寫入前，自動執行 `am force-stop` 凍結目標程式，杜絕 SQLite 資料庫鎖死與存檔損毀。
- **🚀 Shizuku + Root 雙軌提權**：
  未解鎖手機走 Shizuku Binder 遠端提權；模擬器與 Root 手機自動無感切換 `su`。
- **🌐 WebDAV 雙模身分驗證 (Basic + Digest)**：
  原生支援 RFC 2617 / RFC 7616 Digest 挑戰與標準 Basic 認證，全面相容 **CloudMe**、**Nextcloud**、**Synology DSM**、**QNAP** 與本機自建 NAS。
- **📦 智慧去噪過濾**：
  自動剔除 `cache/`、`code_cache/`、`app_webview/`、`no_backup/` 等無效暫存，將幾百 MB 的快取精簡至僅幾十 KB 至數 MB 的純存檔，節省 95% 頻寬。
- **🔧 跨裝置 Linux 權限自動修復**：
  還原時自動重新指派 Linux UID / GID（`chown`）與 SELinux 安全標籤，杜絕異機權限錯位造成的應用閃退。
- **⚡ 自動化調度支援**：
  - 支援廣播接收器：可透過 Tasker 或終端機發送 `am broadcast -a sync.all` 一鍵背景靜默全量同步。
  - 內建 WorkManager 背景巡檢：支援每 6 小時背景定時檢查並同調存檔。

---

## ⚖️ 方案對比表

| 評估維度 | 手動備份 + 網盤 | Tasker 腳本流 | DataBackup (笨蛋備份) | **SaveSync (本專案)** |
| :--- | :--- | :--- | :--- | :--- |
| **自動比對雙端時間戳記** | ❌ 無 | ⚠️ 需複雜自訂變數 | ❌ 無（僅手動清單） | ✅ **核心功能 (自動判斷最新)** |
| **離線使用與快照防護** | ❌ 易覆蓋舊檔 | ⚠️ 需額外刻邏輯 | ⚠️ 依賴手動還原 | ✅ **自動滾動保留本地 3 版備份** |
| **免 Root (Shizuku) 支援** | ❌ 無法讀沙盒 | ⚠️ 需配置 ADB Wi-Fi | ✅ 支援 | ✅ **原生支援 (雙軌自動切換)** |
| **多裝置免授權自由部署** | ✅ 免費 | ❌ 每台裝置需授權 | ✅ 免費開源 | ✅ **完全免授權自由安裝** |
| **操作繁瑣度** | 極高 (手動複製還原) | 低 (但維護門檻高) | 中 (需進入 App 手動點擊) | **極低 (背景自動化 / 一鍵無感)** |

---

## 🚀 快速上手指南

### 1. 提權準備
- **免 Root 實體機**：安裝並啟動 [Shizuku](https://shizuku.rikka.app/)（透過無線偵錯配對），開啟 SaveSync 給予 Shizuku 授權。
- **模擬器 / Root 機**：進入系統設定開啟 Root 權限，於彈窗給予 SaveSync 超級使用者授權。

### 2. 配置 WebDAV 伺服器
於 SaveSync 設定面板中填入你的 WebDAV 服務資訊：
- **CloudMe**：
  - 網址：`https://webdav.cloudme.com/<你的帳號>/CloudDrive/`（⚠️ 必須包含 `/CloudDrive/`）
  - 帳號：你的 CloudMe 使用者名稱
  - 密碼：你的登入密碼（支援 Digest Auth）
- **Nextcloud**：
  - 網址：`https://<你的主機>/remote.php/dav/files/<使用者名稱>/`
  - 密碼：個人設定 ➔ 安全性 ➔ 建立之應用程式專用密碼 (App Password)
- **個人 NAS (Synology / QNAP / 本機)**：填入對應 WebDAV 連線 URL 與帳密。

### 3. 加入同步應用
在首頁勾選內建範本或自訂包名：
- **Mihon 漫畫**：`app.mihon`（路徑：`databases/tachiyomi.db*`, `shared_prefs/`, `files/`）
- **Yuzu NS 模擬器**：`org.yuzu.yuzu_emu`（路徑：`files/nand/user/save/`）
- **同文輸入法 (Trime)**：`com.osfans.trime`（路徑：`/sdcard/rime/`）

### 4. Tasker 外部自動化串接
若需要「離開應用程式時自動同步」，可在 Tasker 新增任務：
- **Action**：`Send Intent`
- **Action 名稱**：`sync.all`（或完整名稱 `com.droidsave.sync.ACTION_SYNC_ALL`）
- **Cat**：`Default`
- **Target**：`Broadcast Receiver`

---

## 🛠️ 技術堆疊 (Tech Stack)

- **核心語言**：Kotlin (Android 原生)
- **UI 框架**：Jetpack Compose + Material 3
- **架構設計**：Clean Architecture + MVI
- **網路通訊**：OkHttp 4.12.0（自研 `WebDavAuthenticator` 雙模交涉）
- **背景調度**：Android WorkManager
- **提權抽象**：Shizuku API + Root `su` Process fallback

---

## 📚 相關文件

- [系統架構書與決策記錄 (docs/ARCHITECTURE.md)](docs/ARCHITECTURE.md)
- [開源授權協議 (LICENSE)](LICENSE)
