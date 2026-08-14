"""Local fake MCP servers used by AI remote-MCP integration tests.

Serves both transports on one port:
  - SSE: GET /sse  + POST /message?sessionId=
  - Streamable HTTP: POST /mcp
  - GET /health  GET /stats

``echo_ping`` appends a transport-specific suffix so the caller can prove the
remote process executed the tool, not a locally synthesized stub.
"""

from __future__ import annotations

import json
import queue
import threading
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.parse import parse_qs, urlparse

AUTH = "Bearer databuff-mcp-it"
TOOL_NAME = "echo_ping"
SSE_SUFFIX = "sse-executed"
STREAMABLE_SUFFIX = "streamable-executed"


class McpFakeState:
    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.stopping = False
        self.auth_hits = 0
        self.unauthorized = 0
        self.sse_initialize = 0
        self.streamable_initialize = 0
        self.tools_list = 0
        self.tools_call = 0
        self.last_call: dict[str, Any] = {}
        self.sse_sessions: dict[str, queue.Queue[str | None]] = {}
        self.streamable_sessions: set[str] = set()

    def snapshot(self) -> dict[str, Any]:
        with self.lock:
            return {
                "auth_hits": self.auth_hits,
                "unauthorized": self.unauthorized,
                "sse_initialize": self.sse_initialize,
                "streamable_initialize": self.streamable_initialize,
                "tools_list": self.tools_list,
                "tools_call": self.tools_call,
                "last_call": dict(self.last_call),
            }


def _echo_text(token: str, suffix: str) -> str:
    return f"echo:{token}:{suffix}"


def _tool_list() -> list[dict[str, Any]]:
    return [
        {
            "name": TOOL_NAME,
            "description": "Echo a token to prove remote MCP execution",
            "inputSchema": {
                "type": "object",
                "properties": {"token": {"type": "string"}},
                "required": ["token"],
            },
        }
    ]


class _Handler(BaseHTTPRequestHandler):
    server: "McpFakeServer"

    def log_message(self, fmt: str, *args: Any) -> None:  # noqa: A003
        return

    def do_GET(self) -> None:  # noqa: N802
        path = urlparse(self.path).path
        if path == "/health":
            self._write(200, {"ok": True})
            return
        if path == "/stats":
            self._write(200, self.server.state.snapshot())
            return
        if path == "/sse":
            self._handle_sse()
            return
        self._write(404, {"error": "not found"})

    def do_POST(self) -> None:  # noqa: N802
        path = urlparse(self.path).path
        if path == "/mcp":
            self._handle_streamable()
            return
        if path == "/message":
            self._handle_sse_message()
            return
        self._write(404, {"error": "not found"})

    def do_DELETE(self) -> None:  # noqa: N802
        if urlparse(self.path).path == "/mcp":
            self._write_empty(200)
            return
        self._write(404, {"error": "not found"})

    def _auth_ok(self) -> bool:
        got = self.headers.get("Authorization", "")
        if got == AUTH:
            with self.server.state.lock:
                self.server.state.auth_hits += 1
            return True
        with self.server.state.lock:
            self.server.state.unauthorized += 1
        self._write(401, {"jsonrpc": "2.0", "error": {"code": -32000, "message": "unauthorized"}})
        return False

    def _handle_sse(self) -> None:
        if not self._auth_ok():
            return
        session_id = "sse-" + uuid.uuid4().hex
        q: queue.Queue[str | None] = queue.Queue()
        self.server.state.sse_sessions[session_id] = q
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "keep-alive")
        self.end_headers()
        self.wfile.write(f"event: endpoint\ndata: /message?sessionId={session_id}\n\n".encode("utf-8"))
        self.wfile.flush()
        try:
            while not self.server.state.stopping:
                try:
                    payload = q.get(timeout=0.3)
                except queue.Empty:
                    continue
                if payload is None:
                    break
                self.wfile.write(f"event: message\ndata: {payload}\n\n".encode("utf-8"))
                self.wfile.flush()
        except BrokenPipeError:
            pass
        finally:
            self.server.state.sse_sessions.pop(session_id, None)

    def _handle_sse_message(self) -> None:
        if not self._auth_ok():
            return
        query = parse_qs(urlparse(self.path).query)
        session_id = (query.get("sessionId") or [""])[0]
        q = self.server.state.sse_sessions.get(session_id)
        body = self._read_json()
        method = str(body.get("method") or "")
        rpc_id = body.get("id")
        self._write_empty(202)
        if rpc_id is None or q is None:
            return
        reply = self._rpc_reply(method, rpc_id, body, "sse", SSE_SUFFIX)
        if method == "initialize":
            with self.server.state.lock:
                self.server.state.sse_initialize += 1
        q.put(json.dumps(reply, ensure_ascii=False))

    def _handle_streamable(self) -> None:
        if not self._auth_ok():
            return
        body = self._read_json()
        method = str(body.get("method") or "")
        rpc_id = body.get("id")
        if method == "initialize":
            session_id = "sess-" + uuid.uuid4().hex
            with self.server.state.lock:
                self.server.state.streamable_sessions.add(session_id)
                self.server.state.streamable_initialize += 1
            extra = {"Mcp-Session-Id": session_id}
            if rpc_id is None:
                self._write_empty(202)
                return
            self._write(
                200,
                self._rpc_reply(method, rpc_id, body, "streamable", STREAMABLE_SUFFIX),
                extra_headers=extra,
            )
            return
        if method.startswith("notifications/"):
            self._write_empty(202)
            return
        session_id = self.headers.get("mcp-session-id") or self.headers.get("Mcp-Session-Id") or ""
        if session_id not in self.server.state.streamable_sessions:
            self._write(
                400,
                {
                    "jsonrpc": "2.0",
                    "id": rpc_id,
                    "error": {"code": -32000, "message": "Session ID required in mcp-session-id header"},
                },
            )
            return
        if rpc_id is None:
            self._write_empty(202)
            return
        self._write(200, self._rpc_reply(method, rpc_id, body, "streamable", STREAMABLE_SUFFIX))

    def _rpc_reply(
        self, method: str, rpc_id: Any, body: dict[str, Any], transport: str, suffix: str
    ) -> dict[str, Any]:
        if method == "initialize":
            return {
                "jsonrpc": "2.0",
                "id": rpc_id,
                "result": {
                    "protocolVersion": "2024-11-05",
                    "capabilities": {"tools": {}},
                    "serverInfo": {"name": f"fake-{transport}-mcp", "version": "1.0.0"},
                },
            }
        if method == "tools/list":
            with self.server.state.lock:
                self.server.state.tools_list += 1
            return {"jsonrpc": "2.0", "id": rpc_id, "result": {"tools": _tool_list()}}
        if method == "tools/call":
            params = body.get("params") or {}
            name = str(params.get("name") or "")
            arguments = params.get("arguments") or {}
            token = str(arguments.get("token") or "")
            text = _echo_text(token, suffix) if name == TOOL_NAME else f"unknown tool: {name}"
            with self.server.state.lock:
                self.server.state.tools_call += 1
                self.server.state.last_call = {
                    "transport": transport,
                    "name": name,
                    "token": token,
                    "text": text,
                }
            return {
                "jsonrpc": "2.0",
                "id": rpc_id,
                "result": {
                    "content": [{"type": "text", "text": text}],
                    "isError": False,
                },
            }
        return {
            "jsonrpc": "2.0",
            "id": rpc_id,
            "error": {"code": -32601, "message": f"Method not found: {method}"},
        }

    def _read_json(self) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length") or "0")
        raw = self.rfile.read(length) if length else b""
        if not raw:
            return {}
        try:
            data = json.loads(raw.decode("utf-8"))
        except json.JSONDecodeError:
            return {}
        return data if isinstance(data, dict) else {}

    def _write(
        self, status: int, payload: dict[str, Any], extra_headers: dict[str, str] | None = None
    ) -> None:
        raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        if extra_headers:
            for key, value in extra_headers.items():
                self.send_header(key, value)
        self.end_headers()
        self.wfile.write(raw)

    def _write_empty(self, status: int) -> None:
        self.send_response(status)
        self.send_header("Content-Length", "0")
        self.end_headers()


class McpFakeServer(ThreadingHTTPServer):
    def __init__(self, host: str = "0.0.0.0", port: int = 0) -> None:
        super().__init__((host, port), _Handler)
        self.state = McpFakeState()
        self.thread: threading.Thread | None = None

    @property
    def port(self) -> int:
        return int(self.server_address[1])

    def start(self) -> "McpFakeServer":
        self.thread = threading.Thread(target=self.serve_forever, name="mcp-fake", daemon=True)
        self.thread.start()
        return self

    def stop(self) -> None:
        self.state.stopping = True
        for q in list(self.state.sse_sessions.values()):
            q.put(None)
        self.shutdown()
        if self.thread is not None:
            self.thread.join(timeout=3)
        self.server_close()
