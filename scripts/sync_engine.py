"""SaveSync 雙向狀態同調引擎 (Bidirectional State Machine Sync Engine).

實作雙端時間戳記比對、三版滾動回滾、防衛性凍結與權限規格化。
Bidirectional timestamp comparison, rolling 3-version rollback, defensive freezing, and permission normalization.
"""

from __future__ import annotations

import enum
import hashlib
import json
import os
import shutil
import subprocess
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from storage_backend import BaseStorageBackend


class SyncStatus(enum.Enum):
    """同步狀態機 (Synchronization Status Enum)."""

    UP_TO_DATE = "UP_TO_DATE"  # 兩端版本一致，無需動作
    NEED_PUSH = "NEED_PUSH"  # 本地有最新進度，需推送到雲端
    NEED_PULL = "NEED_PULL"  # 雲端有最新進度，需拉取到本機
    CONFLICT = "CONFLICT"  # 兩端皆有修改衝突，觸發防衛性保護
    NO_REMOTE = "NO_REMOTE"  # 雲端尚無存檔，需初次推送


@dataclass
class SyncManifest:
    """存檔中繼資料收據 (Save metadata receipt)."""

    schema_version: int = 1
    package_name: str = ""
    device_name: str = ""
    updated_at_epoch_ms: int = 0
    save_version: int = 1
    sha256: str = ""
    archive_size_bytes: int = 0
    relative_paths: List[str] = None


class AdbDeviceBridge:
    """ADB 設備通訊橋接器 (ADB Device Communication Bridge)."""

    def __init__(self, adb_path: str = "adb", device_serial: str = "127.0.0.1:16384"):
        self.adb_path = adb_path
        self.device_serial = device_serial

    def connect(self) -> bool:
        res = subprocess.run([self.adb_path, "connect", self.device_serial], capture_output=True, text=True)
        return "connected" in res.stdout.lower() or "already" in res.stdout.lower()

    def shell(self, command: str) -> str:
        cmd = [self.adb_path, "-s", self.device_serial, "shell", command]
        res = subprocess.run(cmd, capture_output=True, text=True)
        return res.stdout.strip()

    def push(self, local_path: str, remote_path: str) -> bool:
        res = subprocess.run([self.adb_path, "-s", self.device_serial, "push", local_path, remote_path], capture_output=True)
        return res.returncode == 0

    def pull(self, remote_path: str, local_path: str) -> bool:
        res = subprocess.run([self.adb_path, "-s", self.device_serial, "pull", remote_path, local_path], capture_output=True)
        return res.returncode == 0


class SaveSyncEngine:
    """雙向自動同調核心引擎 (Bidirectional Auto-Sync Core Engine)."""

    DEFAULT_EXCLUDES = ["cache", "code_cache", "app_webview", "no_backup"]

    def __init__(self, bridge: AdbDeviceBridge, storage: BaseStorageBackend, workspace_dir: Path):
        self.bridge = bridge
        self.storage = storage
        self.workspace = workspace_dir
        self.ledger_dir = self.workspace / "ledger"
        self.backup_vault = self.workspace / "backups"
        self.ledger_dir.mkdir(parents=True, exist_ok=True)
        self.backup_vault.mkdir(parents=True, exist_ok=True)

    def freeze_app(self, package_name: str) -> None:
        """程序凍結：確保資料庫無鎖定且快取回寫磁碟 (Freeze app)."""
        self.bridge.shell(f"am force-stop {package_name}")

    def get_package_uid_gid(self, package_name: str) -> Tuple[int, int]:
        """動態探測目標應用之 UID 與 GID (Detect target UID & GID)."""
        out = self.bridge.shell(f"stat -c '%u %g' /data/data/{package_name}")
        parts = out.split()
        if len(parts) >= 2:
            return int(parts[0]), int(parts[1])
        raise RuntimeError(f"無法取得應用身分 (Failed to get UID/GID for {package_name}): {out}")

    def get_local_latest_mtime_ms(self, package_name: str) -> int:
        """取得裝置上該應用目錄之最新修改時間 (Get latest mtime on device)."""
        out = self.bridge.shell(f"stat -c '%Y' /data/data/{package_name}")
        try:
            return int(out.strip()) * 1000
        except ValueError:
            return 0

    def read_local_ledger(self, package_name: str) -> Optional[Dict[str, Any]]:
        """讀取本機最後同步帳本 (Read local ledger)."""
        ledger_file = self.ledger_dir / f"{package_name}.json"
        if not ledger_file.exists():
            return None
        return json.loads(ledger_file.read_text(encoding="utf-8"))

    def write_local_ledger(self, package_name: str, manifest_data: Dict[str, Any]) -> None:
        """更新本機最後同步帳本 (Update local ledger)."""
        ledger_file = self.ledger_dir / f"{package_name}.json"
        ledger_file.write_text(json.dumps(manifest_data, indent=2, ensure_ascii=False), encoding="utf-8")

    def check_status(self, package_name: str) -> Tuple[SyncStatus, Optional[Dict[str, Any]], Optional[Dict[str, Any]]]:
        """比對雙端收據與狀態 (Check synchronization status)."""
        remote_manifest = self.storage.read_manifest(package_name)
        local_ledger = self.read_local_ledger(package_name)

        if not remote_manifest:
            return SyncStatus.NO_REMOTE, local_ledger, None

        if not local_ledger:
            # 本機從未同步過，但雲端已有存檔 -> 需拉取
            return SyncStatus.NEED_PULL, None, remote_manifest

        remote_time = remote_manifest.get("updated_at_epoch_ms", 0)
        local_synced_time = local_ledger.get("updated_at_epoch_ms", 0)
        remote_hash = remote_manifest.get("sha256", "")
        local_hash = local_ledger.get("sha256", "")

        device_mtime = self.get_local_latest_mtime_ms(package_name)
        local_has_newer_change = device_mtime > (local_synced_time + 2000)

        if remote_hash == local_hash and not local_has_newer_change:
            return SyncStatus.UP_TO_DATE, local_ledger, remote_manifest

        if remote_time > local_synced_time and not local_has_newer_change:
            return SyncStatus.NEED_PULL, local_ledger, remote_manifest

        if local_has_newer_change and remote_time <= local_synced_time:
            return SyncStatus.NEED_PUSH, local_ledger, remote_manifest

        if local_has_newer_change and remote_time > local_synced_time:
            # 兩端自上次同步後皆有新變更 -> 衝突
            return SyncStatus.CONFLICT, local_ledger, remote_manifest

        return SyncStatus.UP_TO_DATE, local_ledger, remote_manifest

    def push(self, package_name: str, device_name: str = "Device") -> SyncManifest:
        """打包並推送至雲端 (Package and push to remote storage)."""
        self.freeze_app(package_name)
        remote_tmp_tar = f"/data/local/tmp/{package_name}_sync.tar.gz"

        exclude_args = " ".join([f"--exclude='{exc}'" for exc in self.DEFAULT_EXCLUDES])
        tar_cmd = f"cd /data/data/{package_name} && toybox tar -czf {remote_tmp_tar} {exclude_args} ."
        self.bridge.shell(tar_cmd)

        staging_dir = self.workspace / "staging"
        staging_dir.mkdir(parents=True, exist_ok=True)
        local_archive = staging_dir / f"{package_name}_latest.tar.gz"

        if not self.bridge.pull(remote_tmp_tar, str(local_archive)):
            raise RuntimeError(f"存檔拉取失敗 (Failed to pull from device: {remote_tmp_tar})")

        self.bridge.shell(f"rm -f {remote_tmp_tar}")

        file_bytes = local_archive.read_bytes()
        sha256 = hashlib.sha256(file_bytes).hexdigest()
        now_ms = int(time.time() * 1000)

        # 取得既有版本號
        current_ledger = self.read_local_ledger(package_name) or {}
        new_version = current_ledger.get("save_version", 0) + 1

        manifest = SyncManifest(
            package_name=package_name,
            device_name=device_name,
            updated_at_epoch_ms=now_ms,
            save_version=new_version,
            sha256=sha256,
            archive_size_bytes=len(file_bytes),
            relative_paths=["databases/", "shared_prefs/", "files/"],
        )

        manifest_dict = asdict(manifest)

        # 上傳雲端
        remote_archive_path = f"{package_name}/latest.tar.gz"
        if not self.storage.upload_file(remote_archive_path, local_archive):
            raise RuntimeError("上傳雲端存檔失敗 (Failed to upload archive to storage)")

        if not self.storage.write_manifest(package_name, manifest_dict):
            raise RuntimeError("更新雲端帳本失敗 (Failed to write manifest to storage)")

        # 更新本地帳本
        self.write_local_ledger(package_name, manifest_dict)
        return manifest

    def pull(self, package_name: str) -> bool:
        """自雲端下載並安全還原 (Download from remote and safely restore)."""
        staging_dir = self.workspace / "staging"
        staging_dir.mkdir(parents=True, exist_ok=True)
        local_archive = staging_dir / f"{package_name}_download.tar.gz"

        remote_archive_path = f"{package_name}/latest.tar.gz"
        if not self.storage.download_file(remote_archive_path, local_archive):
            raise RuntimeError("下載雲端存檔失敗 (Failed to download archive from storage)")

        remote_manifest = self.storage.read_manifest(package_name)
        if not remote_manifest:
            raise RuntimeError("無法讀取雲端帳本 (Remote manifest not found)")

        # 防衛性設計：覆寫前將當前本地存檔建立滾動備份 (Rolling 3 snapshots)
        self._create_local_rollback_snapshot(package_name)

        uid, gid = self.get_package_uid_gid(package_name)
        self.freeze_app(package_name)

        remote_tmp_tar = f"/data/local/tmp/{package_name}_restore.tar.gz"
        if not self.bridge.push(str(local_archive), remote_tmp_tar):
            raise RuntimeError("推送還原檔至裝置失敗 (Failed to push archive to device)")

        restore_cmd = (
            f"cd /data/data/{package_name} && "
            f"toybox tar -xzf {remote_tmp_tar} && "
            f"chown -R {uid}:{gid} /data/data/{package_name} && "
            f"restorecon -R /data/data/{package_name} && "
            f"rm -f {remote_tmp_tar}"
        )
        self.bridge.shell(restore_cmd)

        # 更新本機帳本
        self.write_local_ledger(package_name, remote_manifest)
        return True

    def _create_local_rollback_snapshot(self, package_name: str) -> None:
        """防衛性滾動備份 (Rolling 3 versions rollback snapshot)."""
        app_backup_dir = self.backup_vault / package_name
        app_backup_dir.mkdir(parents=True, exist_ok=True)

        timestamp_str = time.strftime("%Y%m%d_%H%M%S")
        backup_file = app_backup_dir / f"backup_{timestamp_str}.tar.gz"

        remote_tmp_tar = f"/data/local/tmp/{package_name}_pre_rollback.tar.gz"
        tar_cmd = f"cd /data/data/{package_name} && toybox tar -czf {remote_tmp_tar} --exclude='cache' --exclude='code_cache' ."
        self.bridge.shell(tar_cmd)
        self.bridge.pull(remote_tmp_tar, str(backup_file))
        self.bridge.shell(f"rm -f {remote_tmp_tar}")

        # 維持最多 3 個版本輪替
        backups = sorted(app_backup_dir.glob("backup_*.tar.gz"), key=lambda p: p.stat().st_mtime)
        while len(backups) > 3:
            oldest = backups.pop(0)
            oldest.unlink(missing_ok=True)

    def auto_sync(self, package_name: str, device_name: str = "Device") -> str:
        """全自動比對並同步 (Auto-Sync based on state machine)."""
        status, local_l, remote_m = self.check_status(package_name)

        if status == SyncStatus.UP_TO_DATE:
            return "UP_TO_DATE: 雙端進度一致，略過同步。"

        if status in (SyncStatus.NEED_PUSH, SyncStatus.NO_REMOTE):
            manifest = self.push(package_name, device_name)
            return f"PUSH_SUCCESS: 本地進度已推送至雲端 (Version {manifest.save_version}, {manifest.archive_size_bytes} Bytes)。"

        if status == SyncStatus.NEED_PULL:
            self.pull(package_name)
            ver = remote_m.get("save_version", 1) if remote_m else 1
            return f"PULL_SUCCESS: 雲端新進度已無縫還原 (Version {ver})，並已建立本地防衛備份。"

        if status == SyncStatus.CONFLICT:
            # 衝突時：先將本地推為衝突備份，不盲目覆蓋
            self._create_local_rollback_snapshot(package_name)
            return "CONFLICT_WARNING: 偵測到雙端同時修改！已保留本地防衛快照，請手動確認欲保留之版本。"

        return f"UNKNOWN_STATUS: {status}"
