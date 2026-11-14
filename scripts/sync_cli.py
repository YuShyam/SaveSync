"""DroidSaveSync 指令列工具 (Command Line Interface Tool).

提供 check, push, pull, sync 等自動化與手動調度功能。
Command line interface supporting check, push, pull, and auto-sync.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from storage_backend import LocalMockStorageBackend, WebDavStorageBackend
from sync_engine import AdbDeviceBridge, DroidSaveSyncEngine


def load_config() -> dict:
    config_file = Path(__file__).parent.parent / "config.json"
    if not config_file.exists():
        raise FileNotFoundError(f"找不到設定檔 (Config not found: {config_file})")
    return json.loads(config_file.read_text(encoding="utf-8"))


def get_engine(config: dict, device_serial: str = "127.0.0.1:16384", adb_path: str = "") -> DroidSaveSyncEngine:
    if not adb_path:
        adb_path = r"C:\Program Files\Netease\MuMuPlayer\nx_main\adb.exe"

    bridge = AdbDeviceBridge(adb_path=adb_path, device_serial=device_serial)
    bridge.connect()

    backend_type = config.get("storage_backend", "local_mock")
    workspace_root = Path(__file__).parent.parent / "runtime"

    if backend_type == "webdav":
        dav_cfg = config.get("webdav", {})
        storage = WebDavStorageBackend(
            base_url=dav_cfg.get("url", ""),
            username=dav_cfg.get("username", ""),
            password=dav_cfg.get("password", ""),
        )
    else:
        cloud_mock_dir = Path(__file__).parent.parent / "cloud_vault"
        storage = LocalMockStorageBackend(cloud_mock_dir)

    return DroidSaveSyncEngine(bridge=bridge, storage=storage, workspace_dir=workspace_root)


def main():
    parser = argparse.ArgumentParser(description="DroidSaveSync CLI")
    parser.add_argument("action", choices=["check", "push", "pull", "sync"], help="執行動作")
    parser.add_argument("app", nargs="?", default="app.mihon", help="目標應用包名或 ID (預設: app.mihon)")
    parser.add_argument("--serial", default="127.0.0.1:16384", help="ADB 裝置序號 (預設: 127.0.0.1:16384)")
    parser.add_argument("--device-name", default="", help="裝置識別名稱")

    args = parser.parse_args()
    config = load_config()
    device_name = args.device_name or config.get("device_name", "Device_01")
    engine = get_engine(config, device_serial=args.serial)

    package_name = args.app
    # 支援透過 id 查詢包名 (例如 mihon -> app.mihon)
    for app_item in config.get("apps", []):
        if app_item.get("id") == args.app:
            package_name = app_item.get("package_name")
            break

    print(f"[{args.action.upper()}] 目標應用: {package_name} | 裝置: {device_name}")

    if args.action == "check":
        status, local_l, remote_m = engine.check_status(package_name)
        print(f"狀態比對結果: {status.value}")
        if local_l:
            print(f"  - 本機版本: {local_l.get('save_version')} (時間: {local_l.get('updated_at_epoch_ms')})")
        else:
            print("  - 本機尚未記錄同步歷史")
        if remote_m:
            print(f"  - 雲端版本: {remote_m.get('save_version')} (時間: {remote_m.get('updated_at_epoch_ms')})")
        else:
            print("  - 雲端無此應用存檔收據")

    elif args.action == "push":
        manifest = engine.push(package_name, device_name)
        print(f"成功推送存檔！版本: {manifest.save_version}, 大小: {manifest.archive_size_bytes} Bytes")

    elif args.action == "pull":
        engine.pull(package_name)
        print(f"成功拉取並還原存檔！已自動修復 UID 與 SELinux 標籤。")

    elif args.action == "sync":
        result = engine.auto_sync(package_name, device_name)
        print(f"自動同步結果: {result}")


if __name__ == "__main__":
    main()

