"""In-memory HTTP MCP acceptance fixture, not a production service (Python 3.10+)."""
import argparse
import copy
import hmac
import json
import os
import secrets
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LATEST = "2026-07-28"
LEGACY = "2025-11-25"
MAX_BODY = 65536


def tool(name, properties, required=(), read_only=True):
    return {
        "name": name, "description": "Fixture-only " + name,
        "inputSchema": {"type": "object", "properties": properties,
                        "required": list(required), "additionalProperties": False},
        "annotations": {"readOnlyHint": read_only, "destructiveHint": False},
    }


class FixtureState:
    def __init__(self, tokens=None, control_token=None):
        self.tokens = tokens or {
            os.environ.get("FIXTURE_PERSONAL_TOKEN", "fixture-personal-only"): "personal",
            os.environ.get("FIXTURE_WORK_TOKEN", "fixture-work-only"): "work",
        }
        self.control_token = control_token or os.environ.get(
            "FIXTURE_CONTROL_TOKEN", "fixture-control-only")
        if len(self.tokens) != 2 or not all(self.tokens) or not self.control_token:
            raise ValueError("Configure two distinct, nonempty fixture account tokens")
        if self.control_token in self.tokens:
            raise ValueError("Control credential must differ from account credentials")
        self.lock = threading.RLock()
        self.sessions = {}
        self.tools = [
            tool("list_documents", {}),
            tool("search_documents", {"query": {"type": "string"}}, ["query"]),
            tool("create_draft", {"title": {"type": "string"}, "body": {"type": "string"}},
                 ["title", "body"], read_only=False),
        ]
        self.documents = {account: [{"id": account + "-seed", "title": account + " notes",
                                    "body": "Fixture content for " + account}]
                          for account in self.tokens.values()}
        self.failures = {}

    def account(self, authorization):
        for token, account in self.tokens.items():
            if hmac.compare_digest(authorization.encode(), ("Bearer " + token).encode()):
                return account
        return None

    def control(self, command):
        """Separate operator endpoint; never exposed to MCP tools or Skills."""
        action = command.get("action")
        if action == "add_tool":
            if not any(t["name"] == "count_documents" for t in self.tools):
                self.tools.append(tool("count_documents", {}))
        elif action == "change_definition":
            self.tools[2]["description"] += " Definition revised; review authorization."
            self.tools[2]["inputSchema"]["properties"]["tag"] = {"type": "string"}
        elif action == "fail_next":
            account = command.get("account")
            method = command.get("method", "tools/call")
            mode = command.get("mode", "http_503")
            if account not in self.documents or method not in ("tools/list", "tools/call"):
                raise ValueError("Unknown account or method")
            if mode not in ("http_401", "http_503", "rpc_error", "tool_error", "after_write"):
                raise ValueError("Unknown failure mode")
            if mode in ("tool_error", "after_write") and method != "tools/call":
                raise ValueError("This failure mode requires tools/call")
            self.failures[(account, method)] = mode
        elif action == "expire_sessions":
            self.sessions.clear()
        else:
            raise ValueError("Unknown control action")
        return {"ok": True}

    def execute(self, account, name, arguments):
        definition = next((t for t in self.tools if t["name"] == name), None)
        if definition is None:
            raise LookupError("Unknown tool")
        schema = definition["inputSchema"]
        if not isinstance(arguments, dict) or not all(k in arguments for k in schema["required"]):
            raise ValueError("Missing arguments")
        if any(k not in schema["properties"] or not isinstance(v, str) for k, v in arguments.items()):
            raise ValueError("Invalid arguments")
        documents = self.documents[account]
        if name == "list_documents":
            value = {"account": account, "documents": documents}
        elif name == "search_documents":
            query = arguments["query"].casefold()
            value = {"account": account, "documents": [d for d in documents
                     if query in (d["title"] + " " + d["body"]).casefold()]}
        elif name == "count_documents":
            value = {"account": account, "count": len(documents)}
        else:
            draft = {"id": account + "-draft-" + str(len(documents)),
                     "title": arguments["title"], "body": arguments["body"], "status": "draft"}
            documents.append(draft)
            value = {"account": account, "draft": draft}
        return {"content": [{"type": "text", "text": json.dumps(value)}], "isError": False}


class FixtureServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, state=None):
        self.state = state or FixtureState()
        super().__init__(address, FixtureHandler)


class FixtureHandler(BaseHTTPRequestHandler):
    # Neither request bodies nor authorization headers are logged.
    def log_message(self, *_args):
        pass

    def respond(self, status, body=None, session=None, sse=False):
        raw = b"" if body is None else json.dumps(body).encode()
        if sse and body is not None:
            raw = b"event: message\ndata: " + raw + b"\n\n"
        self.send_response(status)
        self.send_header("Content-Type", "text/event-stream" if sse else "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        if session:
            self.send_header("Mcp-Session-Id", session)
        self.end_headers()
        self.wfile.write(raw)

    def rpc_error(self, request_id, code, message, status=200):
        self.respond(status, {"jsonrpc": "2.0", "id": request_id,
                              "error": {"code": code, "message": message}})

    def read_json(self):
        size = int(self.headers.get("Content-Length", "0"))
        if not 0 < size <= MAX_BODY:
            raise ValueError("Invalid body size")
        body = json.loads(self.rfile.read(size))
        if not isinstance(body, dict):
            raise ValueError("Object expected")
        return body

    def do_POST(self):
        state = self.server.state
        authorization = self.headers.get("Authorization", "")
        if self.path == "/control":
            if not hmac.compare_digest(authorization.encode(), ("Bearer " + state.control_token).encode()):
                self.respond(401)
                return
            try:
                command = self.read_json()
                with state.lock:
                    result = state.control(command)
                self.respond(200, result)
            except (ValueError, TypeError):
                self.respond(400, {"error": "Invalid fixture control command"})
            return
        if self.path not in ("/mcp", "/legacy", "/sse"):
            self.respond(404)
            return
        account = state.account(authorization)
        if account is None:
            self.respond(401)
            return
        try:
            body = self.read_json()
        except (ValueError, TypeError):
            self.rpc_error(None, -32700, "Invalid JSON body", 400)
            return
        request_id = body.get("id")
        method = body.get("method")
        params = body.get("params", {})
        if body.get("jsonrpc") != "2.0" or not isinstance(params, dict) or not isinstance(method, str):
            self.rpc_error(request_id, -32600, "Invalid request", 400)
            return
        version = self.headers.get("MCP-Protocol-Version")
        if version not in (LATEST, LEGACY):
            self.rpc_error(request_id, -32600, "Unsupported fixture protocol", 400)
            return
        if self.path == "/legacy" and version == LATEST:
            self.respond(400)  # Exercises the client's AUTO fallback to initialize.
            return
        if version == LATEST:
            meta = params.get("_meta", {})
            if not isinstance(meta, dict) or meta.get("io.modelcontextprotocol/protocolVersion") != LATEST:
                self.rpc_error(request_id, -32600, "Missing modern protocol metadata", 400)
                return
        with state.lock:
            if method == "initialize" and version == LEGACY:
                session = secrets.token_hex(16)
                state.sessions[session] = account
                self.respond(200, {"jsonrpc": "2.0", "id": request_id, "result": {
                    "protocolVersion": LEGACY, "capabilities": {"tools": {}},
                    "serverInfo": {"name": "theta-acceptance-fixture", "version": "1"}}}, session)
                return
            if version == LEGACY and state.sessions.get(self.headers.get("Mcp-Session-Id")) != account:
                self.respond(404)
                return
            if method == "notifications/initialized":
                self.respond(202)
                return
            mode = state.failures.pop((account, method), None)
            if mode in ("http_401", "http_503"):
                self.respond(int(mode.split("_")[1]))
                return
            if mode == "rpc_error":
                self.rpc_error(request_id, -32603, "Injected fixture error")
                return
            if method == "tools/list":
                result = {"tools": copy.deepcopy(state.tools)}
                if version == LATEST:
                    result.update(resultType="complete", ttlMs=60000)
            elif method == "tools/call":
                if mode == "tool_error":
                    result = {"content": [{"type": "text", "text": "Injected failure before execution"}],
                              "isError": True}
                else:
                    try:
                        result = state.execute(account, params.get("name"), params.get("arguments", {}))
                    except LookupError:
                        self.rpc_error(request_id, -32602, "Unknown tool")
                        return
                    except (ValueError, TypeError):
                        self.rpc_error(request_id, -32602, "Invalid tool arguments")
                        return
                    if mode == "after_write" and params.get("name") == "create_draft":
                        self.respond(503)  # The write already happened. Never assume safe replay.
                        return
            else:
                self.rpc_error(request_id, -32601, "Method not found")
                return
            self.respond(200, {"jsonrpc": "2.0", "id": request_id, "result": result},
                         sse=self.path == "/sse")

    def do_DELETE(self):
        if self.path not in ("/mcp", "/legacy", "/sse"):
            self.respond(404)
            return
        state = self.server.state
        account = state.account(self.headers.get("Authorization", ""))
        with state.lock:
            session = self.headers.get("Mcp-Session-Id")
            if account is None:
                self.respond(401)
            elif state.sessions.get(session) != account:
                self.respond(404)
            else:
                del state.sessions[session]
                self.respond(204)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    with FixtureServer((args.host, args.port)) as server:
        print("Fixture listening; in-memory test data only. Credentials are not logged.", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
