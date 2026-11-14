"""SaveSync 雲端儲存適配層 (Storage Backend Adapter).

支援標準 WebDAV 協定與本機模擬測試庫 (Local Mock Vault)。
Supports standard WebDAV protocol and Local Mock Vault for testing.
"""

from __future__ import annotations

import base64
import json
import os
import shutil
import urllib.error
import urllib.request
from abc import ABC, abstractmethod
from pathlib import Path
from typing import Any, Dict, Optional


class BaseStorageBackend(ABC):
    """雲端儲存後端抽象介面 (Abstract Storage Backend Interface)."""

    @abstractmethod
    def upload_file(self, remote_rel_path: str, local_path: Path) -> bool:
        """上傳檔案至雲端 (Upload file to remote storage)."""

    @abstractmethod
    def download_file(self, remote_rel_path: str, local_path: Path) -> bool:
        """自雲端下載檔案 (Download file from remote storage)."""

    @abstractmethod
    def read_manifest(self, package_name: str) -> Optional[Dict[str, Any]]:
        """讀取遠端中繼收據 (Read remote manifest)."""

    @abstractmethod
    def write_manifest(self, package_name: str, manifest_data: Dict[str, Any]) -> bool:
        """寫入遠端中繼收據 (Write remote manifest)."""


class LocalMockStorageBackend(BaseStorageBackend):
    """本機模擬雲端儲存庫 (Local Mock Storage Backend for Zero-Cost Testing)."""

    def __init__(self, root_dir: Path):
        self.root = root_dir
        self.root.mkdir(parents=True, exist_ok=True)

    def upload_file(self, remote_rel_path: str, local_path: Path) -> bool:
        dest = self.root / remote_rel_path
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(local_path, dest)
        return True

    def download_file(self, remote_rel_path: str, local_path: Path) -> bool:
        src = self.root / remote_rel_path
        if not src.exists():
            return False
        local_path.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, local_path)
        return True

    def read_manifest(self, package_name: str) -> Optional[Dict[str, Any]]:
        target = self.root / package_name / "manifest.json"
        if not target.exists():
            return None
        return json.loads(target.read_text(encoding="utf-8"))

    def write_manifest(self, package_name: str, manifest_data: Dict[str, Any]) -> bool:
        target = self.root / package_name / "manifest.json"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(manifest_data, indent=2, ensure_ascii=False), encoding="utf-8")
        return True


class WebDavStorageBackend(BaseStorageBackend):
    """標準 WebDAV 儲存後端 (Standard WebDAV Storage Backend via HTTP).

    支援 Basic 與 Digest 雙模身分驗證 (Supports dual Basic and Digest authentication).
    """

    def __init__(self, base_url: str, username: str = "", password: str = ""):
        self.base_url = base_url.rstrip("/")
        self.username = username
        self.password = password

        # 建立雙模密碼管理器與認證處理器 (Dual-mode password manager & handlers)
        self.password_mgr = urllib.request.HTTPPasswordMgrWithDefaultRealm()
        if self.username and self.password:
            self.password_mgr.add_password(None, self.base_url, self.username, self.password)

        handlers = [
            urllib.request.HTTPBasicAuthHandler(self.password_mgr),
            urllib.request.HTTPDigestAuthHandler(self.password_mgr),
        ]
        self.opener = urllib.request.build_opener(*handlers)

    def _get_headers(self) -> Dict[str, str]:
        return {"User-Agent": "SaveSync/1.0"}

    def ensure_remote_dir(self, remote_dir: str) -> bool:
        """建立遠端目錄 MKCOL (Ensure remote directory exists)."""
        url = f"{self.base_url}/{remote_dir.strip('/')}/"
        req = urllib.request.Request(url, headers=self._get_headers(), method="MKCOL")
        try:
            with self.opener.open(req) as resp:
                return resp.status in (201, 204, 405)
        except urllib.error.HTTPError as e:
            return e.code in (201, 204, 405)
        except Exception:
            return False

    def upload_file(self, remote_rel_path: str, local_path: Path) -> bool:
        parts = remote_rel_path.replace("\\", "/").split("/")
        if len(parts) > 1:
            self.ensure_remote_dir("/".join(parts[:-1]))

        url = f"{self.base_url}/{remote_rel_path.lstrip('/')}"
        data = local_path.read_bytes()
        req = urllib.request.Request(url, data=data, headers=self._get_headers(), method="PUT")
        try:
            with self.opener.open(req) as resp:
                return resp.status in (200, 201, 204)
        except Exception as e:
            print(f"[WebDAV Upload Error] {e}")
            return False

    def download_file(self, remote_rel_path: str, local_path: Path) -> bool:
        url = f"{self.base_url}/{remote_rel_path.lstrip('/')}"
        req = urllib.request.Request(url, headers=self._get_headers(), method="GET")
        try:
            with self.opener.open(req) as resp:
                local_path.parent.mkdir(parents=True, exist_ok=True)
                local_path.write_bytes(resp.read())
                return True
        except Exception as e:
            print(f"[WebDAV Download Error] {e}")
            return False

    def read_manifest(self, package_name: str) -> Optional[Dict[str, Any]]:
        url = f"{self.base_url}/{package_name}/manifest.json"
        req = urllib.request.Request(url, headers=self._get_headers(), method="GET")
        try:
            with self.opener.open(req) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            print(f"[WebDAV Manifest Read Error] HTTP {e.code}")
            return None
        except Exception as e:
            print(f"[WebDAV Manifest Read Error] {e}")
            return False

    def write_manifest(self, package_name: str, manifest_data: Dict[str, Any]) -> bool:
        self.ensure_remote_dir(package_name)
        url = f"{self.base_url}/{package_name}/manifest.json"
        data = json.dumps(manifest_data, indent=2, ensure_ascii=False).encode("utf-8")
        req = urllib.request.Request(url, data=data, headers=self._get_headers(), method="PUT")
        try:
            with self.opener.open(req) as resp:
                return resp.status in (200, 201, 204)
        except Exception as e:
            print(f"[WebDAV Manifest Write Error] {e}")
            return False

