"use strict";

// Run against the local development service. The "ws" package is for this
// verification script only; production relies on Clerk and http-kit.
const WebSocket = require("ws");
const assert = require("node:assert/strict");
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const base = process.env.PUBLIC_BASE_URL || "http://127.0.0.1:11080";
const token = process.env.ROOM_TOKEN;
const key = process.env.MCP_API_KEY;

async function json(path, options = {}) {
  const response = await fetch(base + path, {
    ...options,
    headers: {
      cookie: "teach_room=" + token,
      origin: base,
      ...(options.headers || {})
    }
  });
  assert.equal(response.ok, true, path + " returned HTTP " + response.status);
  return response.json();
}

function call(source, id) {
  return json("/mcp", {
    method: "POST",
    headers: {
      authorization: "Bearer " + key,
      "content-type": "application/json",
      accept: "application/json"
    },
    body: JSON.stringify({
      jsonrpc: "2.0",
      id,
      method: "tools/call",
      params: { name: "teach_then_ask", arguments: { source } }
    })
  });
}

async function waitForPending() {
  for (let i = 0; i < 120; i++) {
    const state = await json("/api/state");
    if (state.pending) return state.question_id;
    await sleep(100);
  }
  throw Error("MCP question was not ready");
}

async function run() {
  assert.ok(token && key, "Provide ROOM_TOKEN and MCP_API_KEY");
  const ws = new WebSocket(base.replace(/^http/, "ws") + "/_ws", {
    headers: { cookie: "teach_room=" + token }
  });
  const received = [];
  ws.on("message", data => received.push(data.toString()));
  await new Promise((resolve, reject) => {
    ws.once("open", resolve);
    ws.once("error", reject);
  });

  const first = call('(clerk/md "## 实时增量验证")', 101);
  const question = await waitForPending();
  await sleep(150);
  assert.ok(received.some(x => x.includes("patch-state!")),
            "Clerk did not send a patch-state! message");
  const beforeReply = received.length;
  await json("/api/answer", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ question_id: question, text: "学生回复" })
  });
  assert.equal((await first).result.structuredContent.student_reply, "学生回复");
  assert.ok(received.length > beforeReply, "Student reply caused no Clerk update");
  assert.equal((await call(null, 102)).result.structuredContent.closed, true);
  await sleep(100);
  assert.ok(received.length >= 3, "Expected teacher, student, finish patches");
  console.log("PASS: Clerk WebSocket patch-state!, student reply, lesson finish");
  ws.close();
}

run().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
