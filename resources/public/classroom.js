(() => {
  "use strict";
  const board = document.querySelector("#board");
  const input = document.querySelector("#answer");
  const indicator = document.querySelector("#indicator");
  const send = document.querySelector("#send");
  const skip = document.querySelector("#skip");
  const bottom = document.querySelector("#to-bottom");
  const message = document.querySelector("#message");
  const pair = document.querySelector("#pairing");
  const codeDisplay = document.querySelector("#pairing-code");
  const prompt = document.querySelector("#pairing-prompt");
  const copy = document.querySelector("#copy-prompt");
  let tabToken = null;
  let pairingCode = null;
  let questionId = null;
  let pending = false;
  let ended = false;
  let submitting = false;
  let revision = -1;
  let follow = true;

  function applyStatus(next) {
    if (typeof next.revision === "number" && next.revision < revision) return;
    if (typeof next.revision === "number") revision = next.revision;
    const freshQuestion = next.question_id !== questionId;
    questionId = next.question_id;
    pending = Boolean(next.pending);
    ended = Boolean(next.closed);
    if (next.paired) {
      pair.classList.add("paired");
      prompt.textContent = "已配对 ChatGPT。老师可以开始授课了。";
      copy.hidden = true;
    }
    if (freshQuestion) {
      input.value = "";
      submitting = false;
      message.textContent = "";
    }
    const ready = pending && !submitting && !ended;
    input.disabled = !ready;
    send.disabled = !ready || !input.value.trim();
    skip.disabled = !ready;
    input.placeholder = ended ? "本节课已经结束" : pending ? "在这里回答老师..." : "老师正在准备下一步...";
    indicator.textContent = ended ? "课程结束" : pending ? "轮到你了" : "老师正在准备";
    if (freshQuestion && ready) input.focus();
  }

  async function submit(text) {
    if (!pending || submitting || ended) return;
    submitting = true;
    applyStatus({ question_id: questionId, pending: true, closed: false });
    try {
      const res = await fetch("/api/answer", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ tab_token: tabToken, question_id: questionId, text }),
      });
      const data = await res.json();
      if (!res.ok) throw Error(data.error || "提交失败");
      pending = false;
      input.value = "";
      applyStatus(data.state || { question_id: null, pending: false, closed: false });
    } catch (error) {
      submitting = false;
      message.textContent = error.message;
      applyStatus({ question_id: questionId, pending: true, closed: false });
    }
  }

  send.addEventListener("click", () => {
    if (input.value.trim()) submit(input.value.trim());
  });
  skip.addEventListener("click", () => submit(null));
  input.addEventListener("keydown", event => {
    if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
      event.preventDefault();
      if (input.value.trim()) submit(input.value.trim());
    }
  });
  input.addEventListener("input", () => {
    send.disabled = !pending || submitting || !input.value.trim();
  });

  function goToBottom() {
    try {
      const w = board.contentWindow;
      w.scrollTo({ top: w.document.documentElement.scrollHeight, behavior: "smooth" });
      follow = true;
      bottom.hidden = true;
    } catch { /* Same-origin frame may not be ready yet. */ }
  }

  board.addEventListener("load", () => {
    try {
      const w = board.contentWindow;
      const d = w.document;
      const css = d.createElement("style");
      css.textContent =
        "nav, .clerk-nav, .clerk-navbar { display: none !important; }" +
        "body { padding-bottom: 24px !important; }" +
        "article, main { max-width: 900px !important; }";
      d.head.append(css);
      w.addEventListener("scroll", () => {
        const root = d.documentElement;
        follow = root.scrollHeight - w.scrollY - w.innerHeight < 150;
        bottom.hidden = follow;
      }, { passive: true });
      const observer = new MutationObserver(() => {
        if (follow) w.requestAnimationFrame(() => {
          w.scrollTo(0, d.documentElement.scrollHeight);
        });
      });
      observer.observe(d.body, { childList: true, subtree: true });
      goToBottom();
    } catch { /* The board can still be used without auto-follow. */ }
  });
  bottom.addEventListener("click", goToBottom);

  async function pollStatus() {
    const response = await fetch(
      "/api/state?tab=" + encodeURIComponent(tabToken), { cache: "no-store" }
    );
    if (!response.ok) throw Error("课堂连接失效");
    applyStatus(await response.json());
  }

  copy.addEventListener("click", async () => {
    try {
      await navigator.clipboard.writeText(
        "我的配对码是 " + pairingCode + "，请使用 @Teach Clerk 让我学习:"
      );
      copy.textContent = "已复制 ✓";
    } catch {
      copy.textContent = "复制失败，请手动选择文字";
    }
  });

  async function start() {
    const response = await fetch("/api/session", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: "{}",
    });
    if (!response.ok) throw Error("暂时无法创建课堂");
    const room = await response.json();
    tabToken = room.tab_token;
    pairingCode = room.pairing_code;
    codeDisplay.textContent = pairingCode;
    prompt.textContent =
      "我的配对码是 " + pairingCode + "，请使用 @Teach Clerk 让我学习:";
    copy.disabled = false;
    board.removeAttribute("srcdoc");
    board.src = "/_clerk?tab=" + encodeURIComponent(tabToken);
    const stream = new EventSource(
      "/api/events?tab=" + encodeURIComponent(tabToken)
    );
    stream.addEventListener("status", event => applyStatus(JSON.parse(event.data)));
    stream.addEventListener("error", () => {
      indicator.textContent = "重新连接中...";
      pollStatus().catch(() => {});
    });
    pollStatus().catch(() => { indicator.textContent = "连接失败"; });
  }

  addEventListener("pagehide", () => {
    if (!tabToken) return;
    const payload = new Blob([JSON.stringify({ tab_token: tabToken })],
                             { type: "application/json" });
    navigator.sendBeacon("/api/close", payload);
  });
  start().catch(error => {
    indicator.textContent = "课堂连接失败";
    message.textContent = error.message;
  });
})();
