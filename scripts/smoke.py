#!/usr/bin/env python3
"""Integration check against a running, real Teach Clerk MCP server."""
import http.cookiejar
import json
import os
import threading
import time
import urllib.request

BASE = os.environ.get("PUBLIC_BASE_URL", "http://localhost:10000").rstrip("/")
TOKEN = os.environ["ROOM_TOKEN"]
KEY = os.environ["MCP_API_KEY"]
cookies = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))


def json_request(method, path, payload=None, agent=False):
    headers = {"Accept": "application/json"}
    if agent:
        headers["Authorization"] = f"Bearer {KEY}"
    if payload is not None:
        headers.update({"Content-Type": "application/json", "Origin": BASE})
    data = None if payload is None else json.dumps(payload).encode()
    req = urllib.request.Request(BASE + path, headers=headers, data=data, method=method)
    with opener.open(req, timeout=30) as res:
        return json.load(res)


def main():
    assert json_request("GET", "/healthz")["status"] == "ok"
    with opener.open(BASE + "/room/" + TOKEN, timeout=30) as res:
        assert b"Clerk" in res.read()
    with opener.open(BASE + "/_clerk", timeout=30) as res:
        html = res.read(300000).decode(errors="replace")
        assert "clerk" in html.lower()
    listed = json_request("POST", "/mcp", {
        "jsonrpc": "2.0", "id": 1, "method": "tools/list", "params": {}
    }, agent=True)
    assert [x["name"] for x in listed["result"]["tools"]] == ["teach_then_ask"]

    received = []
    def invoke():
        received.append(json_request("POST", "/mcp", {
            "jsonrpc": "2.0", "id": 2, "method": "tools/call",
            "params": {"name": "teach_then_ask", "arguments": {
                "source": '(clerk/fragment (clerk/md "## Test") (clerk/table [[1 2] [3 4]]))'
            }}
        }, agent=True))

    t = threading.Thread(target=invoke)
    t.start()
    question_id = None
    for _ in range(100):
        status = json_request("GET", "/api/state")
        if status["pending"]:
            question_id = status["question_id"]
            break
        time.sleep(0.2)
    assert question_id is not None, "Board was not ready after 20 seconds"
    json_request("POST", "/api/answer", {"question_id": question_id, "text": "我会"})
    t.join(timeout=30)
    assert not t.is_alive(), "MCP call did not resume after student reply"
    assert received[0]["result"]["structuredContent"]["student_reply"] == "我会"

    ended = json_request("POST", "/mcp", {
        "jsonrpc": "2.0", "id": 3, "method": "tools/call",
        "params": {"name": "teach_then_ask", "arguments": {"source": None}}
    }, agent=True)
    assert ended["result"]["structuredContent"]["closed"] is True
    print("PASS: MCP tool, Clerk board, student reply, end lesson")


if __name__ == "__main__":
    main()
