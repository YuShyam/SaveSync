"""極簡本機 WebDAV 模擬伺服器 (Minimal Local WebDAV Server for Zero-Account Testing).

提供 GET, PUT, MKCOL, PROPFIND, OPTIONS 等標準 WebDAV 協定方法，供模擬器與手機進行免帳號測試。
Minimal local WebDAV server implementing standard HTTP/WebDAV methods for zero-account testing.
"""

from __future__ import annotations

import os
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT_DIR = Path(__file__).parent.parent / "cloud_vault"
ROOT_DIR.mkdir(parents=True, exist_ok=True)


class MiniWebDavHandler(BaseHTTPRequestHandler):

    def log_message(self, format, *args):
        print(f"[WebDAV Server] {self.command} {self.path} -> {args[0]}")

    def _resolve_path(self) -> Path:
        unquoted = urllib.parse.unquote(self.path.split("?")[0].strip("/"))
        safe_rel = os.path.normpath(unquoted).lstrip("\\/.")
        return ROOT_DIR / safe_rel

    def do_OPTIONS(self):
        self.send_response(200)
        self.send_header("DAV", "1, 2")
        self.send_header("Allow", "GET, HEAD, PUT, MKCOL, PROPFIND, OPTIONS, DELETE")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_PROPFIND(self):
        target = self._resolve_path()
        if not target.exists():
            self.send_response(404)
            self.end_headers()
            return

        xml_response = f"""<?xml version="1.0" encoding="utf-8" ?>
<D:multistatus xmlns:D="DAV:">
  <D:response>
    <D:href>{self.path}</D:href>
    <D:propstat>
      <D:status>HTTP/1.1 200 OK</D:status>
    </D:propstat>
  </D:response>
</D:multistatus>"""
        body = xml_response.encode("utf-8")
        self.send_response(207)
        self.send_header("Content-Type", "application/xml; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_MKCOL(self):
        target = self._resolve_path()
        target.mkdir(parents=True, exist_ok=True)
        self.send_response(201)
        self.end_headers()

    def do_GET(self):
        target = self._resolve_path()
        if not target.exists() or target.is_dir():
            self.send_response(404)
            self.end_headers()
            return
        data = target.read_bytes()
        self.send_response(200)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_HEAD(self):
        target = self._resolve_path()
        if not target.exists():
            self.send_response(404)
        else:
            self.send_response(200)
        self.end_headers()

    def do_PUT(self):
        target = self._resolve_path()
        target.parent.mkdir(parents=True, exist_ok=True)
        content_length = int(self.headers.get("Content-Length", 0))
        data = self.rfile.read(content_length)
        target.write_bytes(data)
        self.send_response(201)
        self.end_headers()


def run_server(port: int = 8080):
    server = ThreadingHTTPServer(("0.0.0.0", port), MiniWebDavHandler)
    print(f"=== 本機免帳號 WebDAV 伺服器啟動於 port {port} ===")
    print(f"存檔庫目錄: {ROOT_DIR}")
    print(f"模擬器請連線至: http://10.0.2.2:{port}/ (若為實體機請使用本機區域網路 IP)")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n伺服器已停止。")


if __name__ == "__main__":
    run_server(8080)

