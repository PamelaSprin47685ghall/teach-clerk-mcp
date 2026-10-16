#!/usr/bin/env python3
"""Real unauthenticated remote MCP classroom smoke test.

No OAuth discovery, registration, consent, bearer token or API key is involved.
Optionally set PUBLIC_BASE_URL for a deployed HTTPS endpoint.
"""
import json
import threading
import time
import urllib.parse

from oauth_smoke import BASE, mcp, request


def main():
    status, _, body = request("/healthz")
    assert status == 200 and json.loads(body)["status"] == "ok"

    status, _, _ = request("/mcp", method="GET")
    assert status == 405, "Unauthenticated discovery must not demand OAuth"
    initialized = mcp("initialize", {"protocolVersion": "2025-11-25"}, id=10)
    assert initialized["result"]["serverInfo"]["name"] == "teach-clerk-mcp"
    tools = mcp("tools/list", id=11)["result"]["tools"]
    assert "teach_then_ask" in [t["name"] for t in tools]
    teacher = next(t for t in tools if t["name"] == "teach_then_ask")
    entry = next(t for t in tools if t["name"] == "open_teach_clerk")
    both = [{"type": "noauth"}, {"type": "oauth2", "scopes": ["teacher.teach"]}]
    assert teacher["securitySchemes"] == both
    assert entry["securitySchemes"] == both
    assert teacher["_meta"]["securitySchemes"] == both
    assert entry["_meta"]["securitySchemes"] == both

    opened = mcp("tools/call", {"name": "open_teach_clerk",
                                "arguments": {}}, id=12)
    assert opened["result"]["structuredContent"]["connected"] is True

    bad, headers, body = request(
        "/mcp",
        {"jsonrpc": "2.0", "id": 13,
         "method": "tools/call",
         "params": {"name": "open_teach_clerk", "arguments": {}}},
        headers={"Authorization": "Bearer not-a-valid-token"})
    assert bad == 401, (bad, body)
    assert any(k.lower() == "www-authenticate" for k in headers)

    status, _, body = request("/api/session", {})
    assert status == 200
    student = json.loads(body)
    pair = student["pairing_code"]
    tab = student["tab_token"]
    assert len(pair) == 6 and pair.isdigit()

    def teach(source, id):
        return mcp("tools/call", {
            "name": "teach_then_ask",
            "arguments": {"pairing_code": pair, "source": source}
        }, id=id)

    try:
        for turn in range(2):
            received = []
            source = ('[(clerk/md "## 匿名教学，第 %d 回合") '
                      '(clerk/table [[1 2] [3 4]]) '
                      '(clerk/md "你会怎样解释这个规律？")]' % (turn + 1))

            def agent_call():
                received.append(teach(source, 20 + turn))

            pending = threading.Thread(target=agent_call)
            pending.start()
            qid = None
            for _ in range(120):
                status, _, response = request(
                    "/api/state?tab=" + urllib.parse.quote(tab))
                state = json.loads(response)
                if status == 200 and state["pending"]:
                    qid = state["question_id"]
                    break
                time.sleep(0.1)
            assert qid is not None, "No pending question"

            status, _, html = request(
                "/_clerk?tab=" + urllib.parse.quote(tab))
            assert status == 200
            assert "匿名教学，第 %d 回合" % (turn + 1) in html
            assert "table-markup-viewer" in html
            assert ":teach/value" not in html

            answer = "匿名回答第%d回合" % (turn + 1)
            status, _, body = request(
                "/api/answer",
                json.dumps({"tab_token": tab,
                            "question_id": qid,
                            "text": answer}),
                headers={"Content-Type": "application/json",
                         "Origin": BASE})
            assert status == 200, (status, body)
            pending.join(timeout=30)
            assert not pending.is_alive(), "MCP tool never completed"
            assert received and (
                received[0]["result"]["structuredContent"]["student_reply"]
                == answer
            ), received

        ended = teach(None, 30)
        assert ended["result"]["structuredContent"]["closed"] is True
        print("PASS: no OAuth or bearer; same pairing code completed two "
              "independent teaching rounds and ended the lesson")
    finally:
        request(
            "/api/close", json.dumps({"tab_token": tab}),
            headers={"Content-Type": "application/json", "Origin": BASE})


if __name__ == "__main__":
    main()
