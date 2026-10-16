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

    if os.environ.get("RUN_CLASSROOM_LOOP") == "1":
        students = []
        for _ in range(2):
            status, _, data = request("/api/session", {})
            assert status == 200, data
            students.append(json.loads(data))
        assert students[0]["pairing_code"] != students[1]["pairing_code"]

        answers = [None, None]
        questions = [
            '(clerk/md "## 第一位同学的唯一问题 A")',
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
