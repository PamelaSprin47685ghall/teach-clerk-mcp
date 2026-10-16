#!/usr/bin/env python3
"""Check OAuth 2.1 discovery, login, PKCE, refresh and MCP authorization.

Requires PUBLIC_BASE_URL; local startup also requires OAUTH_SIGNING_SECRET.
Set RUN_CLASSROOM_LOOP=1 for concurrent teacher/student round trips.
"""
import base64
import hashlib
import json
import os
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = os.environ.get("PUBLIC_BASE_URL", "http://127.0.0.1:10000").rstrip("/")
VERIFIER = "v" * 58
CHALLENGE = base64.urlsafe_b64encode(
    hashlib.sha256(VERIFIER.encode()).digest()
).decode().rstrip("=")
RESOURCE = BASE
CALLBACK = (
    "http://127.0.0.1:3000/callback"
    if BASE.startswith("http://127.0.0.1:")
    else "https://chatgpt.com/connector_platform_oauth_redirect"
)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


client = urllib.request.build_opener(NoRedirect)


def request(path, payload=None, headers=None, method=None):
    data = None
    content_type = None
    if isinstance(payload, dict):
        data = json.dumps(payload).encode()
        content_type = "application/json"
    elif isinstance(payload, str):
        data = payload.encode()
        content_type = "application/x-www-form-urlencoded"
    hs = {"Accept": "application/json"}
    if content_type:
        hs["Content-Type"] = content_type
    hs.update(headers or {})
    req = urllib.request.Request(
        BASE + path, data=data, headers=hs,
        method=method or ("POST" if data is not None else "GET")
    )
    try:
        with client.open(req, timeout=40) as r:
            return r.status, dict(r.headers), r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read().decode()


def form(values):
    return urllib.parse.urlencode(values)


def mcp(method, params=None, token=None, id=5):
    headers = {"Authorization": "Bearer " + token} if token else {}
    status, _, body = request(
        "/mcp",
        {"jsonrpc": "2.0", "method": method, "id": id, "params": params or {}},
        headers
    )
    assert status == 200, (status, body)
    return json.loads(body)


def main():
    code, _, body = request("/.well-known/oauth-protected-resource")
    assert code == 200 and json.loads(body)["resource"] == RESOURCE
    code, _, body = request("/.well-known/oauth-authorization-server")
    metadata = json.loads(body)
    assert metadata["code_challenge_methods_supported"] == ["S256"]
    assert metadata["token_endpoint_auth_methods_supported"] == ["none"]
    assert metadata["authorization_response_iss_parameter_supported"] is True

    assert mcp("tools/list")["result"]["tools"][0]["name"] == "teach_then_ask"
    denied, denied_headers, _ = request("/mcp", {
        "jsonrpc": "2.0", "method": "tools/call", "id": 17,
        "params": {"name": "teach_then_ask",
                   "arguments": {"pairing_code": "000000", "source": None}}
    })
    assert denied == 401
    challenge = next((v for k, v in denied_headers.items()
                      if k.lower() == "www-authenticate"), "")
    assert "resource_metadata" in challenge, denied_headers

    code, _, body = request(
        "/oauth/register", {"redirect_uris": [CALLBACK],
                            "token_endpoint_auth_method": "none"}
    )
    assert code == 201, (code, body)
    client_id = json.loads(body)["client_id"]
    values = {
        "response_type": "code",
        "client_id": client_id,
        "redirect_uri": CALLBACK,
        "scope": "teacher.teach offline_access",
        "state": "oauth-smoke-state-2026",
        "code_challenge": CHALLENGE,
        "code_challenge_method": "S256",
        "resource": RESOURCE,
    }
    code, _, page = request("/oauth/authorize?" + form(values))
    assert code == 200 and "同意连接 ChatGPT" in page
    code, headers, _ = request("/oauth/authorize", form(values))
    assert code == 302, (code, headers)
    location = next(v for k, v in headers.items() if k.lower() == "location")
    redirect = urllib.parse.parse_qs(urllib.parse.urlparse(location).query)
    assert redirect["iss"][0] == RESOURCE
    assert redirect["state"][0] == values["state"]
    authorization_code = redirect["code"][0]

    params = {
        "grant_type": "authorization_code",
        "client_id": client_id,
        "redirect_uri": CALLBACK,
        "code": authorization_code,
        "code_verifier": VERIFIER,
        "resource": RESOURCE,
    }
    code, _, body = request("/oauth/token", form(params))
    assert code == 200, (code, body)
    issued = json.loads(body)
    assert issued["token_type"] == "Bearer"
    access = issued["access_token"]
    refresh = issued["refresh_token"]
    assert mcp("tools/list", token=access)["result"]["tools"][0]["name"] == "teach_then_ask"
    code, _, _ = request("/oauth/token", form(params))
    assert code == 400, "The authorization code was replayed"
    code, _, body = request(
        "/oauth/token",
        form({"grant_type": "refresh_token", "client_id": client_id,
              "refresh_token": refresh, "resource": RESOURCE})
    )
    assert code == 200, (code, body)
    assert json.loads(body)["access_token"]

    if os.environ.get("RUN_TIMEOUT_LOOP") == "1":
        status, _, data = request("/api/session", {})
        assert status == 200
        student = json.loads(data)
        pairing_code = student["pairing_code"]
        tab = student["tab_token"]

        first_prompt = '(clerk/md "## 第一题：请慢慢思考")'
        started = time.monotonic()
        timed_out = mcp("tools/call",
                        {"name": "teach_then_ask",
                         "arguments": {"pairing_code": pairing_code,
                                       "source": first_prompt}},
                        token=access, id=40)
        elapsed = time.monotonic() - started
        observation = timed_out["result"]["structuredContent"]
        assert 1 <= elapsed <= 15, elapsed
        assert observation["status"] == "student_timeout", timed_out
        assert observation["must_call_again"] is True
        assert observation["student_still_thinking"] is True
        assert timed_out["result"].get("isError") is not True
        assert "MANDATORY NEXT ACTION" in timed_out["result"]["content"][0]["text"]

        status, _, state_body = request(
            "/api/state?tab=" + urllib.parse.quote(tab)
        )
        assert status == 200
        state = json.loads(state_body)
        assert state["pending"] is True
        assert state["student_timed_out"] is True
        previous_qid = state["question_id"]

        premature_end = mcp("tools/call",
                            {"name": "teach_then_ask",
                             "arguments": {"pairing_code": pairing_code,
                                           "source": None}},
                            token=access, id=41)
        assert premature_end["result"]["isError"] is True
        assert "MUST call teach_then_ask again" in (
            premature_end["result"]["content"][0]["text"]
        )
        status, _, state_body = request(
            "/api/state?tab=" + urllib.parse.quote(tab)
        )
        assert json.loads(state_body)["pending"] is True

        # The student is slow, not gone: the original textbox still accepts.
        status, _, body = request(
            "/api/answer",
            json.dumps({"tab_token": tab,
                        "question_id": previous_qid,
                        "text": "终于算出来了"}),
            headers={"Content-Type": "application/json", "Origin": BASE}
        )
        assert status == 200, (status, body)
        late = mcp("tools/call",
                   {"name": "teach_then_ask",
                    "arguments": {"pairing_code": pairing_code,
                                  "source": '(clerk/md "这条提示暂时不要显示")'}},
                   token=access, id=42)
        delayed = late["result"]["structuredContent"]
        assert delayed["status"] == "late_student_reply", late
        assert delayed["student_reply"] == "终于算出来了"
        assert delayed["must_call_again"] is True
        assert "MANDATORY NEXT ACTION" in late["result"]["content"][0]["text"]

        status, _, html = request("/_clerk?tab=" + urllib.parse.quote(tab))
        assert status == 200
        assert "这条提示暂时不要显示" not in html
        assert "第一题" in html

        followup = []
        def continue_teacher():
            followup.append(mcp(
                "tools/call",
                {"name": "teach_then_ask",
                 "arguments": {"pairing_code": pairing_code,
                               "source": '(clerk/md "## 第二题：再来一次")'}},
                token=access, id=43
            ))

        teacher = threading.Thread(target=continue_teacher)
        teacher.start()
        next_question_id = None
        for _ in range(100):
            status, _, body = request(
                "/api/state?tab=" + urllib.parse.quote(tab)
            )
            data = json.loads(body)
            if data["pending"] and data["question_id"] != previous_qid:
                next_question_id = data["question_id"]
                break
            time.sleep(0.05)
        assert next_question_id is not None, "Agent must be able to continue teaching"
        status, _, body = request(
            "/api/answer",
            json.dumps({"tab_token": tab,
                        "question_id": next_question_id,
                        "text": "第二题也完成了"}),
            headers={"Content-Type": "application/json", "Origin": BASE}
        )
        assert status == 200, (status, body)
        teacher.join(20)
        assert not teacher.is_alive()
        assert followup[0]["result"]["structuredContent"]["student_reply"] == "第二题也完成了"
        finished = mcp("tools/call",
                       {"name": "teach_then_ask",
                        "arguments": {"pairing_code": pairing_code,
                                      "source": None}},
                       token=access, id=44)
        assert finished["result"]["structuredContent"]["closed"] is True

        # Second classroom: the teacher follows up BEFORE the slow student
        # answers. The timeout must not block the next teaching tool call.
        status, _, data = request("/api/session", {})
        assert status == 200
        slow_student = json.loads(data)
        slow_code = slow_student["pairing_code"]
        slow_tab = slow_student["tab_token"]
        first_slow = mcp(
            "tools/call",
            {"name": "teach_then_ask",
             "arguments": {"pairing_code": slow_code,
                           "source": '(clerk/md "## 请先思考这道题")'}},
            token=access, id=45
        )
        assert first_slow["result"]["structuredContent"]["status"] == "student_timeout"
        status, _, data = request(
            "/api/state?tab=" + urllib.parse.quote(slow_tab)
        )
        old_id = json.loads(data)["question_id"]
        next_slow = []

        def followup_before_answer():
            next_slow.append(mcp(
                "tools/call",
                {"name": "teach_then_ask",
                 "arguments": {"pairing_code": slow_code,
                               "source": '(clerk/md "你可以再想一想，我在这里等你。")'}},
                token=access, id=46
            ))

        followup_thread = threading.Thread(target=followup_before_answer)
        followup_thread.start()
        next_id = None
        for _ in range(100):
            status, _, data = request(
                "/api/state?tab=" + urllib.parse.quote(slow_tab)
            )
            state = json.loads(data)
            if state["pending"] and state["question_id"] != old_id:
                next_id = state["question_id"]
                break
            time.sleep(0.05)
        assert next_id is not None, "Teacher follow-up must replace expired wait"
        status, _, data = request(
            "/api/answer",
            json.dumps({"tab_token": slow_tab,
                        "question_id": next_id,
                        "text": "现在可以回答了"}),
            headers={"Content-Type": "application/json", "Origin": BASE}
        )
        assert status == 200, (status, data)
        followup_thread.join(20)
        assert not followup_thread.is_alive()
        assert next_slow[0]["result"]["structuredContent"]["student_reply"] == "现在可以回答了"
        status, _, data = request(
            "/_clerk?tab=" + urllib.parse.quote(slow_tab)
        )
        assert status == 200
        assert "你可以再想一想" in data
        assert "请先思考这道题" in data
        finished = mcp(
            "tools/call",
            {"name": "teach_then_ask",
             "arguments": {"pairing_code": slow_code, "source": None}},
            token=access, id=47
        )
        assert finished["result"]["structuredContent"]["closed"] is True

        # ChatGPT/MCP may negotiate SSE rather than application/json.
        # Verify that the successful timeout event terminates that stream.
        status, _, data = request("/api/session", {})
        assert status == 200
        streamed_student = json.loads(data)
        body = json.dumps(
            {"jsonrpc": "2.0", "id": 48, "method": "tools/call",
             "params": {"name": "teach_then_ask",
                        "arguments": {
                            "pairing_code": streamed_student["pairing_code"],
                            "source": '(clerk/md "## SSE 慢回答")'
                        }}}).encode()
        streaming_request = urllib.request.Request(
            BASE + "/mcp",
            data=body,
            method="POST",
            headers={"Content-Type": "application/json",
                     "Accept": "text/event-stream, application/json",
                     "Authorization": "Bearer " + access}
        )
        streamed_result = None
        with client.open(streaming_request, timeout=15) as stream:
            assert "text/event-stream" in stream.headers["Content-Type"]
            for line in stream:
                if line.startswith(b"data: "):
                    streamed_result = json.loads(line[6:])
                    break
        assert streamed_result is not None
        assert (streamed_result["result"]["structuredContent"]["status"]
                == "student_timeout")
        status, _, data = request(
            "/api/state?tab="
            + urllib.parse.quote(streamed_student["tab_token"])
        )
        assert json.loads(data)["pending"] is True
        status, _, data = request(
            "/api/close",
            json.dumps({"tab_token": streamed_student["tab_token"]}),
            headers={"Content-Type": "application/json", "Origin": BASE}
        )
        assert status == 200
        print("PASS: timeout returns before client deadline, keeps textbox,"
              " rejects premature end, delivers late reply, permits a"
              " nonblocking follow-up, and terminates SSE correctly")

    elif os.environ.get("RUN_CLASSROOM_LOOP") == "1":
        students = []
        for _ in range(2):
            status, _, data = request("/api/session", {})
            assert status == 200, data
            students.append(json.loads(data))
        assert students[0]["pairing_code"] != students[1]["pairing_code"]

        answers = [None, None]
        questions = [
            ('[(clerk/md "## 第一位同学的唯一问题 A") '
             '(clerk/table {:head ["第一列" "第二列"] '
             ':rows [["乘法" "生成器"]]}) '
             '(clerk/md "### 从表格中观察规律")]'),
            '(clerk/md "## 第二位同学的唯一问题 B")'
        ]

        def teacher_call(i):
            answers[i] = mcp(
                "tools/call",
                {"name": "teach_then_ask",
                 "arguments": {"pairing_code": students[i]["pairing_code"],
                               "source": questions[i]}},
                token=access, id=10 + i
            )

        threads = [threading.Thread(target=teacher_call, args=(i,))
                   for i in range(2)]
        for t in threads:
            t.start()

        pending = {}
        for _ in range(150):
            for i, student in enumerate(students):
                status, _, body = request(
                    "/api/state?tab=" + urllib.parse.quote(student["tab_token"])
                )
                if status == 200 and json.loads(body)["pending"]:
                    pending[i] = json.loads(body)["question_id"]
            if len(pending) == 2:
                break
            time.sleep(0.1)
        assert len(pending) == 2, "Both notebooks must have pending questions"

        for i, student in enumerate(students):
            status, _, html = request(
                "/_clerk?tab=" + urllib.parse.quote(student["tab_token"])
            )
            assert status == 200
            assert "唯一问题 " + ("A" if i == 0 else "B") in html
            assert "唯一问题 " + ("B" if i == 0 else "A") not in html
            assert ":teach/value" not in html, "Generated viewers leaked as raw EDN"
            if i == 0:
                assert "table-markup-viewer" in html
                assert "从表格中观察规律" in html

        for i, student in enumerate(students):
            status, _, body = request(
                "/api/answer",
                json.dumps({"tab_token": student["tab_token"],
                            "question_id": pending[i],
                            "text": ["回答甲", "回答乙"][i]}),
                headers={"Content-Type": "application/json", "Origin": BASE}
            )
            assert status == 200, (status, body)
        for t in threads:
            t.join(30)
            assert not t.is_alive(), "MCP request did not resume"
        assert answers[0]["result"]["structuredContent"]["student_reply"] == "回答甲"
        assert answers[1]["result"]["structuredContent"]["student_reply"] == "回答乙"

        for i, student in enumerate(students):
            finished = mcp(
                "tools/call",
                {"name": "teach_then_ask",
                 "arguments": {"pairing_code": student["pairing_code"],
                               "source": None}},
                token=access, id=30 + i)
            assert finished["result"]["structuredContent"]["closed"] is True
            closed, _, _ = request(
                "/api/close",
                json.dumps({"tab_token": student["tab_token"]}),
                headers={"Content-Type": "application/json", "Origin": BASE}
            )
            assert closed == 200
        print("PASS: OAuth + two isolated Clerk notebooks + independent replies")
    else:
        print("PASS: OAuth metadata, consent, DCR, PKCE, replay, refresh and MCP auth")


if __name__ == "__main__":
    main()
