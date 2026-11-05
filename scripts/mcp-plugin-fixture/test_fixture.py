"""Run directly: python -I /absolute/path/test_fixture.py. No external network."""
import http.client
import importlib.util
import json
from pathlib import Path
import threading
import unittest

spec = importlib.util.spec_from_file_location("fixture", Path(__file__).with_name("fixture.py"))
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)


class FixtureTests(unittest.TestCase):
    def setUp(self):
        self.state = fixture.FixtureState({"test-personal": "personal", "test-work": "work"}, "test-control")
        self.server = fixture.FixtureServer(("127.0.0.1", 0), self.state)
        self.worker = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.01})
        self.worker.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.worker.join(timeout=2)
        self.assertFalse(self.worker.is_alive())

    def http(self, path, body=None, token="test-personal", headers=None, verb="POST"):
        connection = http.client.HTTPConnection(*self.server.server_address, timeout=2)
        try:
            merged = {"Authorization": "Bearer " + token, "Content-Type": "application/json"}
            merged.update(headers or {})
            connection.request(verb, path, None if body is None else json.dumps(body), merged)
            response = connection.getresponse()
            raw = response.read().decode()
            if raw.startswith("event:"):
                raw = raw.split("data: ", 1)[1].strip()
            return response.status, dict(response.getheaders()), json.loads(raw) if raw else None
        finally:
            connection.close()

    def rpc(self, method="tools/list", params=None, token="test-personal", path="/mcp",
            version=fixture.LATEST, session=None):
        params = dict(params or {})
        headers = {"MCP-Protocol-Version": version, "Mcp-Method": method,
                   "Accept": "application/json, text/event-stream"}
        if "name" in params:
            headers["Mcp-Name"] = params["name"]
        if session:
            headers["Mcp-Session-Id"] = session
        if version == fixture.LATEST:
            params["_meta"] = {"io.modelcontextprotocol/protocolVersion": version,
                               "io.modelcontextprotocol/clientInfo": {"name": "Eta", "version": "1"},
                               "io.modelcontextprotocol/clientCapabilities": {}}
        body = {"jsonrpc": "2.0", "id": 7, "method": method, "params": params}
        if method.startswith("notifications/"):
            del body["id"]
        return self.http(path, body, token, headers)

    def control(self, **command):
        status, _, body = self.http("/control", command, "test-control")
        self.assertEqual(200, status)
        self.assertTrue(body["ok"])

    def call(self, name, arguments=None, **kwargs):
        return self.rpc("tools/call", {"name": name, "arguments": arguments or {}}, **kwargs)

    def payload(self, response):
        self.assertEqual(200, response[0])
        self.assertEqual(7, response[2]["id"])
        return json.loads(response[2]["result"]["content"][0]["text"])

    def test_modern_discovery_and_sse_match_client_shapes(self):
        for path in ("/mcp", "/sse"):
            status, _, body = self.rpc(path=path)
            self.assertEqual(200, status)
            self.assertEqual(7, body["id"])
            self.assertEqual("complete", body["result"]["resultType"])
            self.assertEqual(60000, body["result"]["ttlMs"])
            self.assertEqual(3, len(body["result"]["tools"]))

    def test_legacy_fallback_session_binding_and_delete(self):
        self.assertEqual(400, self.rpc(path="/legacy")[0])
        status, headers, body = self.rpc("initialize", {"protocolVersion": fixture.LEGACY},
                                       version=fixture.LEGACY, path="/legacy")
        self.assertEqual(200, status)
        self.assertEqual(fixture.LEGACY, body["result"]["protocolVersion"])
        session = headers["Mcp-Session-Id"]
        args = {"version": fixture.LEGACY, "session": session, "path": "/legacy"}
        self.assertEqual(202, self.rpc("notifications/initialized", **args)[0])
        self.assertNotIn("resultType", self.rpc(**args)[2]["result"])
        self.assertEqual("personal", self.payload(self.call("list_documents", **args))["account"])
        self.assertEqual(404, self.rpc(token="test-work", **args)[0])
        self.assertEqual(204, self.http("/legacy", headers={"Mcp-Session-Id": session}, verb="DELETE")[0])
        self.assertEqual(404, self.rpc(**args)[0])

    def test_same_tool_names_separate_accounts_and_drafts(self):
        personal = self.payload(self.call("list_documents"))
        work = self.payload(self.call("list_documents", token="test-work"))
        self.assertNotEqual(personal["documents"], work["documents"])
        draft = self.payload(self.call("create_draft", {"title": "unique draft", "body": "private"}))
        self.assertEqual("personal", draft["account"])
        self.assertEqual("draft", draft["draft"]["status"])
        self.assertEqual(1, len(self.payload(self.call("search_documents", {"query": "unique"}))["documents"]))
        self.assertEqual([], self.payload(self.call("search_documents", {"query": "unique"}, token="test-work"))["documents"])

    def test_catalog_addition_and_definition_change_are_explicit(self):
        before = self.rpc()[2]["result"]["tools"]
        self.assertEqual(401, self.http("/control", {"action": "add_tool"})[0])
        self.control(action="add_tool")
        self.assertEqual(4, len(self.rpc()[2]["result"]["tools"]))
        self.assertEqual(1, self.payload(self.call("count_documents"))["count"])
        self.control(action="change_definition")
        after = self.rpc()[2]["result"]["tools"]
        self.assertEqual(before[2]["name"], after[2]["name"])
        self.assertNotEqual(before[2]["description"], after[2]["description"])
        self.assertNotEqual(before[2]["inputSchema"], after[2]["inputSchema"])

    def test_failure_injection_is_one_shot_and_account_scoped(self):
        for mode, status in (("http_401", 401), ("http_503", 503), ("rpc_error", 200), ("tool_error", 200)):
            self.control(action="fail_next", account="personal", mode=mode)
            self.assertEqual(200, self.call("list_documents", token="test-work")[0])
            failed = self.call("list_documents")
            self.assertEqual(status, failed[0])
            if mode == "rpc_error":
                self.assertEqual(-32603, failed[2]["error"]["code"])
            if mode == "tool_error":
                self.assertTrue(failed[2]["result"]["isError"])
            self.assertEqual(200, self.call("list_documents")[0])

    def test_write_then_error_preserves_effect_without_automatic_retry(self):
        self.control(action="fail_next", account="personal", mode="after_write")
        self.assertEqual(503, self.call("create_draft", {"title": "uncertain", "body": "written"})[0])
        documents = self.payload(self.call("list_documents"))["documents"]
        self.assertEqual(1, sum(d["title"] == "uncertain" for d in documents))

    def test_unauthorized_invalid_arguments_and_unknown_tools_fail(self):
        self.assertEqual(401, self.rpc(token="not-an-account")[0])
        self.assertEqual(-32602, self.call("create_draft", {"title": "missing body"})[2]["error"]["code"])
        self.assertEqual(-32602, self.call("missing")[2]["error"]["code"])
        self.assertEqual(1, len(self.payload(self.call("list_documents"))["documents"]))

    def test_discovery_failure_and_session_expiry(self):
        self.control(action="fail_next", account="personal", method="tools/list", mode="http_503")
        self.assertEqual(503, self.rpc()[0])
        self.assertEqual(200, self.rpc()[0])
        _, headers, _ = self.rpc("initialize", version=fixture.LEGACY)
        self.control(action="expire_sessions")
        self.assertEqual(404, self.rpc(version=fixture.LEGACY, session=headers["Mcp-Session-Id"])[0])


if __name__ == "__main__":
    unittest.main(verbosity=2)
