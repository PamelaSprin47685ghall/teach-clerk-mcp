"use strict";

// Run against the local development service. The "ws" package is for this
// verification script only; production relies on Clerk and http-kit.
const WebSocket = require("ws");
const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const base = process.env.PUBLIC_BASE_URL || "http://127.0.0.1:11080";
let accessToken;

async function authorize() {
  const redirect = "http://127.0.0.1:3000/callback";
  const registered = await json("/oauth/register", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ redirect_uris: [redirect] })
  });
  const verifier = "v".repeat(58);
  const challenge = crypto.createHash("sha256").update(verifier)
    .digest("base64url");
  const fields = {
    response_type: "code",
    client_id: registered.client_id,
    redirect_uri: redirect,
    scope: "teacher.teach offline_access",
    state: "websocket-test-state",
    code_challenge: challenge,
    code_challenge_method: "S256",
    resource: base
  };
  const response = await fetch(base + "/oauth/authorize", {
    method: "POST",
    redirect: "manual",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams(fields)
  });
  assert.equal(response.status, 302, "Consent must redirect to client");
  const code = new URL(response.headers.get("location")).searchParams.get("code");
  const tokens = await json("/oauth/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "authorization_code",
      client_id: registered.client_id,
      redirect_uri: redirect,
      code,
      code_verifier: verifier,
      resource: base
    })
  });
  return tokens.access_token;
}

async function json(path, options = {}) {
  const response = await fetch(base + path, {
    ...options,
    headers: {
      origin: base,
      ...(options.headers || {})
    }
  });
  assert.equal(response.ok, true, path + " returned HTTP " + response.status);
  return response.json();
}

function call(pairingCode, source, id) {
  return json("/mcp", {
    method: "POST",
    headers: {
      authorization: "Bearer " + accessToken,
      "content-type": "application/json",
      accept: "application/json"
    },
    body: JSON.stringify({
      jsonrpc: "2.0",
      id,
      method: "tools/call",
      params: { name: "teach_then_ask",
                arguments: { pairing_code: pairingCode, source } }
    })
  });
}

async function waitForPending(tabToken) {
  for (let i = 0; i < 120; i++) {
    const state = await json("/api/state?tab=" + encodeURIComponent(tabToken));
    if (state.pending) return state.question_id;
    await sleep(100);
  }
  throw Error("MCP question was not ready");
}

async function run() {
  accessToken = await authorize();
  const students = await Promise.all([
    json("/api/session", {
      method: "POST", headers: { "content-type": "application/json" },
      body: "{}"
    }),
    json("/api/session", {
      method: "POST", headers: { "content-type": "application/json" },
      body: "{}"
    })
  ]);
  for (const student of students) {
    const initial = await fetch(
      base + "/_clerk?tab=" + encodeURIComponent(student.tab_token)
    );
    assert.equal(initial.status, 200, "Initial Clerk notebook must render");
    assert.match(await initial.text(), /clerk/i);
  }
  const received = [[], []];
  const sockets = await Promise.all(students.map((student, i) =>
    new Promise((resolve, reject) => {
      const ws = new WebSocket(
        base.replace(/^http/, "ws") + "/_ws/" + student.tab_token,
        { headers: { Origin: base } }
      );
      ws.on("message", value => received[i].push(value.toString()));
      ws.once("open", () => resolve(ws));
      ws.once("error", reject);
    })));

  const first = call(students[0].pairing_code,
                     '(clerk/md "## 实时增量验证")', 101);
  const question = await waitForPending(students[0].tab_token);
  await sleep(150);
  assert.ok(received[0].some(x => x.includes("patch-state!")),
            "Clerk did not send a patch-state! message");
  assert.equal(received[1].length, 0, "Other student received a patch");
  const beforeReply = received[0].length;
  await json("/api/answer", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      tab_token: students[0].tab_token,
      question_id: question,
      text: "学生回复"
    })
  });
  assert.equal((await first).result.structuredContent.student_reply, "学生回复");
  assert.ok(received[0].length > beforeReply, "Reply caused no Clerk update");
  assert.equal(received[1].length, 0, "Other student received the reply");
  assert.equal(
    (await call(students[0].pairing_code, null, 102)).result.structuredContent.closed,
    true
  );
  await sleep(100);
  assert.ok(received[0].length >= 3, "Expected teacher, student, finish patches");
  assert.equal(received[1].length, 0, "Other notebook was modified");
  console.log("PASS: isolated Clerk WebSocket patches, answer, and lesson finish");
  for (const ws of sockets) ws.close();
  for (const student of students) {
    await json("/api/close", {
      method: "POST", headers: { "content-type": "application/json" },
      body: JSON.stringify({ tab_token: student.tab_token })
    });
  }
}

run().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
