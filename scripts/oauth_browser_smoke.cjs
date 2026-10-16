"use strict";

// Browser-level OAuth consent check: form POST may redirect across origins.
const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const fs = require("node:fs");
const http = require("node:http");
const os = require("node:os");
const path = require("node:path");
const { spawn } = require("node:child_process");
const WebSocket = require("ws");

const base = process.env.PUBLIC_BASE_URL || "http://127.0.0.1:11089";
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

async function waitUntil(check, ms = 15000) {
  const deadline = Date.now() + ms;
  while (Date.now() < deadline) {
    const value = await check();
    if (value) return value;
    await sleep(100);
  }
  throw Error("Timed out while waiting for browser OAuth navigation");
}

async function main() {
  assert.match(base, /^http:\/\/127\.0\.0\.1:\d+$/);
  const callback = http.createServer((request, response) => {
    const url = new URL(request.url, "http://127.0.0.1");
    if (url.pathname === "/callback") callback.seen = url;
    response.writeHead(200, { "content-type": "text/html" });
    response.end("<p>OAuth callback reached</p>");
  });
  await new Promise(resolve => callback.listen(0, "127.0.0.1", resolve));
  const redirect = "http://127.0.0.1:" + callback.address().port + "/callback";

  const register = await fetch(base + "/oauth/register", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ redirect_uris: [redirect] })
  });
  assert.equal(register.status, 201);
  const clientId = (await register.json()).client_id;
  const verifier = "v".repeat(64);
  const challenge = crypto.createHash("sha256")
    .update(verifier).digest("base64url");
  const request = new URL(base + "/oauth/authorize");
  for (const [key, value] of Object.entries({
    response_type: "code",
    client_id: clientId,
    redirect_uri: redirect,
    scope: "teacher.teach offline_access",
    state: "browser-consent-test-123",
    code_challenge: challenge,
    code_challenge_method: "S256",
    resource: base
  })) request.searchParams.set(key, value);

  const profile = fs.mkdtempSync(path.join(os.tmpdir(), "teach-clerk-oauth-"));
  const chrome = spawn("google-chrome", [
    "--headless=new", "--no-sandbox", "--disable-dev-shm-usage",
    "--disable-gpu", "--disable-extensions", "--no-first-run",
    "--no-default-browser-check", "--remote-debugging-port=0",
    "--user-data-dir=" + profile, "about:blank"
  ], { stdio: "ignore" });

  let ws;
  try {
    const portFile = path.join(profile, "DevToolsActivePort");
    const debuggerPort = await waitUntil(() => {
      if (!fs.existsSync(portFile)) return null;
      return fs.readFileSync(portFile, "utf8").split("\n")[0];
    });
    const targets = await (
      await fetch("http://127.0.0.1:" + debuggerPort + "/json/list")
    ).json();
    const target = targets.find(x => x.type === "page");
    assert.ok(target);
    ws = new WebSocket(target.webSocketDebuggerUrl);
    await new Promise((resolve, reject) => {
      ws.once("open", resolve);
      ws.once("error", reject);
    });
    let serial = 0;
    const pending = new Map();
    ws.on("message", data => {
      const result = JSON.parse(data);
      if (pending.has(result.id)) {
        pending.get(result.id)(result);
        pending.delete(result.id);
      }
    });
    function call(method, params = {}) {
      const id = ++serial;
      return new Promise(resolve => {
        pending.set(id, resolve);
        ws.send(JSON.stringify({ id, method, params }));
      });
    }
    async function evaluate(expression) {
      const answer = await call("Runtime.evaluate", {
        expression, returnByValue: true
      });
      return answer.result?.result?.value;
    }
    await call("Page.enable");
    await call("Runtime.enable");
    await call("Page.navigate", { url: request.toString() });
    await waitUntil(async () =>
      await evaluate("Boolean(document.querySelector('form button'))"));
    const consent = await evaluate(
      "document.querySelector('form button').textContent"
    );
    assert.match(consent, /同意连接/);
    await evaluate("document.querySelector('form button').click()");
    const destination = await waitUntil(() => callback.seen, 10000);
    assert.equal(destination.pathname, "/callback");
    assert.ok(destination.searchParams.has("code"));
    assert.equal(destination.searchParams.get("state"),
                 "browser-consent-test-123");
    assert.equal(destination.searchParams.get("iss"), base);
    console.log("PASS: Chrome consent button navigates to the OAuth callback");
  } finally {
    if (ws) ws.close();
    chrome.kill("SIGTERM");
    callback.close();
    fs.rmSync(profile, { recursive: true, force: true });
  }
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
