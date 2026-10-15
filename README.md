# Teach Clerk MCP

A one-room, append-only Clojure/Clerk classroom driven by exactly one MCP tool.

The teacher Agent owns the curriculum goal and style. This server does not call
an LLM or control the teaching strategy. The teacher calls
`teach_then_ask({"source": "(clerk/md \"...\")"})` to show an executable Clerk
view and wait for a student's typed response. The student can submit text or
select **暂不回答** to return JSON `null`. Sending `source: null` ends the class.

## Run

Java 21+ and the Clojure CLI are required:

```sh
export PORT=10000
export PUBLIC_BASE_URL=http://127.0.0.1:10000
export MCP_API_KEY="$(openssl rand -hex 24)"
export ROOM_TOKEN="$(openssl rand -hex 24)"
clojure -M:run
```

The process prints the student's private room URL. Connect an MCP client to
`<PUBLIC_BASE_URL>/mcp` with `Authorization: Bearer <MCP_API_KEY>`.
The server offers only `teach_then_ask` and supports MCP JSON-RPC over
Streamable HTTP. A question is a pending tool call, not a separate Agent turn.

The student's page embeds real Clerk rendering, with a fixed bottom input
area. Clerk sends `patch-state!` editscript diffs over WebSocket; this is
not a full iframe/page refresh. Source expressions are evaluated only once.
Clerk still traverses the current document to calculate each incremental diff,
so the total server-side processing is not O(1) with history length.

## Example

```clojure
(clerk/fragment
  (clerk/md "## 九九乘法表")
  (clerk/table
    (mapv (fn [i]
            (mapv (fn [j]
                    (if (<= j i)
                      (str j " × " i " = " (* i j))
                      ""))
                  (range 1 10)))
          (range 1 10)))
  (clerk/md "观察第七行，相邻两个乘积相差多少？"))
```

The available viewers are `md`, `tex`, `code`, `table`, `html` (limited Hiccup/SVG),
`plotly`, `vl`, `fragment`, `row`, and `col`. Pure Clojure functions,
loops, and data generation work through SCI. Student input is plain text.

## Tests

```sh
clojure -M:test
node --check resources/public/classroom.js
```

For the HTTP/Clerk smoke check, run the service with your env variables,
then run `python3 scripts/smoke.py` in another shell. This test ends the
current classroom session. `node scripts/ws_smoke.cjs` checks Clerk's
incremental WebSocket updates (requires Node.js and the `ws` package).

## Deployment

The `Dockerfile` and `render.yaml` target a single Render web service.
Keep one instance: all lesson state is currently held in memory. A Cloudflare
Quick Tunnel may be useful for temporary demos but has no availability
guarantees; use a named tunnel or Render for a stable URL.

## Safety and limitations

The service is a single-room demonstration, not a hardened multi-tenant
application. Run it with a private MCP token and a distinct, unguessable room
token. Never expose the standard Clerk development WebSocket to students:
it accepts JVM evaluation requests. This implementation registers a
receive-only Clerk patch channel instead.

Untrusted Clojure runs in an independent JVM, interpreted by SCI, with a
10-second evaluation budget and 192 MiB heap. The child process has no
inherited MCP token and direct JVM interop is not exposed by the interpreter.
Do not rely on this as a complete operating-system sandbox. Classroom state
is not persisted; a restart resets the lesson.
