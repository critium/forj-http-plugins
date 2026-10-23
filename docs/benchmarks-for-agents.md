# Benchmarks: process, reasoning and history (for agents)

For AI agents and maintainers continuing the end-to-end benchmark in forj-http-plugins: how the
numbers were measured, the reasoning behind the setup, what is still open, and how to continue.

The human-facing page is [benchmarks.md](benchmarks.md); keep it to the current state only (no
history or reasoning) and record process here.

## 1. Rules for measuring

- **Fairness invariant.** Every server must give exactly the same body and content type on each
  endpoint. `bin/bench` checks that before loading a server (`bin/bench --check` does only
  that), and `bench.test` (`ForjHttpMainTest`) pins forj-http's bodies in every JSON mode. A
  row that answers differently is invalid.
- **One machine, one run at a time.** The Mac is shared with forj-codec's JMH runs and other
  sessions' builds. Before a load run, check with the forj-orchestrator session; tell it when
  the run starts and ends so it can hold and release the other sessions.
- **Mains power only.** Check `pmset -g batt` says AC Power before a run and log it during the
  run (every 5 s); a run with any battery period is discarded (run 4).
- **Same flags for every row:** `-Xmx1g` (plus `-XX:ActiveProcessorCount` under `SERVER_CPUS`),
  same port, same oha settings. Compare RSS only within a JDK (see the runs below).
- **No reflection in forj-http or the backend.** Spring and Jackson are reference rows in
  `bench` only, never a dependency of `helidon`.

## 2. Why the rows are what they are

- From the plan (`../../planning/lets-make-something-meaningfully-warm-lark.md`, section 6):
  forj-http on Helidon, raw Helidon SE as the floor, http4s Ember as the FP design forj-http
  is modelled on. The user added Spring Boot MVC (virtual threads) as what most Java teams run.
  Vert.x and Javalin were offered and declined.
- **Raw Helidon SE on JDK 28 and 25:** Scala, Spring and http4s run on JDK 25 (Mill fetches
  Temurin 25 via `jvmId`, nothing installed system-wide), forj needs JDK 28 EA preview, so
  Helidon runs on both to separate a JDK difference from a framework one.
- **http4s uses jsoniter-scala, not circe,** so the JSON rows compare frameworks, not a slow
  codec (plan's choice).
- **`forj-http-hand`** (user's request): raw Helidon writes JSON by hand, so the gap between
  `forj-http` and `helidon-se-28` mixed forj-http's cost with forj-codec's. With JSON written by
  hand on both sides, the gap is forj-http alone.
- **`forj-http-stream`** (user's request, "the best feature of codec"): `forj-http` already uses
  the `derives Codec` straight-line encoder, so this row shows forj-codec's other headline
  feature, streaming straight to the connection (`Json.encode(value, out)` in a
  `Body.writer`, pooled bounded buffer, no `byte[]`).
- **No middleware on the forj-http rows,** because the other servers run none.

## 3. Chronological log

### Run 1 (2026-10-23 16:51): uncapped, all 7 rows

`bin/bench`, 64 connections, 10 s warmup + 15 s measured per endpoint. Raw output in
`out/bench/20261023-164253/` (local only).

- Every Helidon-based row, raw or forj-http, JDK 25 or 28, landed at 107–109k req/s with the
  same p50 (0.58–0.59 ms). That flat line means the client side (oha + loopback, on the same
  10-core machine) was the ceiling, not the servers.
- http4s 88–104k req/s, p99 3–3.5 ms. Spring 76–99k, p99 1.2–1.9 ms.
- `forj-http-stream` 63k req/s on `/json` and `/users` (41% fewer), plaintext unchanged.
- RSS: JDK 28 rows 165–255 MB, JDK 25 rows 420–500 MB, raw Helidon included, so it's the JDK
  (GC heuristics), not the framework.

### Run 2 (2026-10-23 17:02): servers capped at 4 cores

Added `SERVER_CPUS` to `bin/bench` to make the servers the bottleneck. macOS has no CPU
pinning (no `taskset`/cpusets; thread affinity is only a hint), so the cap is
`-XX:ActiveProcessorCount=N`: the JVM sizes virtual-thread carriers, cats-effect's compute
pool and GC threads for N cores. JIT threads and a framework's own platform threads (Tomcat's
acceptor and poller) aren't capped. `SERVER_CPUS=4 bin/bench forj-http forj-http-hand
helidon-se-28 http4s spring`; raw output in `out/bench/20261023-165556/`.

- Helidon-based rows only dropped from ~107k to ~105k req/s, still within 1% of each other.
  Four carriers are enough for Helidon to keep up with oha, so the client is still the
  ceiling, and forj-http's per-request overhead is still unmeasured.
- http4s is the row the cap reaches: 68–80k req/s (24–35% behind forj-http), p99 1.6–1.7 ms.
- Spring about as in run 1 (10% behind on plaintext/JSON, 28% on users).

### Run 3 (2026-10-23 18:01): the streaming slowdown, no-flush codec variant

Lead: forj-codec (suspected `ForjWriter.close()` calling `out.flush()`). Before any load, single
curl requests showed the mechanism: `json(...)` answered `Content-Length: 51` in one read; the
stream row answered `Transfer-Encoding: chunked` in two reads (57 bytes: chunk header, JSON,
CRLF; then 5 bytes: `0\r\n\r\n`).

forj-codec published `forj:forj-json:0.1.0-noflush-SNAPSHOT` (close() writes its buffer but
doesn't flush the stream). `bench` was pinned to it temporarily (`.forceVersion()` in
`build.mill`, restored after, `./mill clean` both sides). With it, the stream response went out
with `Content-Length: 51` in one read, and in the same session:

- no-flush: forj-http 107.0–108.0k req/s; forj-http-stream 107.1–108.1k, p99 0.84–1.0 ms;
- baseline (normal forj-json): forj-http 107.1–108.0k; forj-http-stream json 63.1k, users
  63.5k, p99 1.24–1.26 ms (the 41% again).

Conclusion: the flush made Helidon commit status and headers before the body was complete, so
it had to send chunked, with the closing chunk as a separate write. Unflushed, Helidon buffers
a small body and sets `Content-Length` itself. Raw: `out/bench/20261023-180101/` (no-flush),
`20261023-180341/` (baseline).

**The fix the user chose (option K), in forj-http, not forj-codec:** `Body.writer(w, len)`
hands the writer a guarded stream whose `flush()` and `close()` do nothing, so the backend's
close is the one commit. A new `Body.streaming(w)` passes flushes through (SSE, early sends;
length -1, chunked). forj-json is unchanged. The backend needed no change (AppHandler already
closes after `writeTo` and never flushes); `HelidonBackendTest.streamedBodies` now pins the
three behaviours.

### Run 4 (2026-10-23 ~18:25): discarded

Started on battery (`pmset`: 94%, discharging); stopped about 2 minutes in, numbers thrown
away. Runs now log `pmset -g batt` every 5 s and abort if the power source isn't AC.

### Run 5 (2026-10-23 18:31): after the fix, on AC power

`bin/bench forj-http forj-http-stream` against the republished forj-http (design K) and the
normal forj-json, then codec-vs-stream on bigger bodies via `GET /users/first/{n}` (added to
`ForjHttpMain` for this; 5 s warmup, 10 s measured). 44 power samples, all AC. Raw:
`out/bench/20261023-183128/`.

- forj-http-stream 106.9–107.1k req/s, p99 0.8–0.85 ms; forj-http 107.0–107.4k. Fixed.
- Framing (single curl requests): stream has `Content-Length` at 51 B, 4.1 KB and 7.8 KB;
  chunked at 9.0 KB and 22.9 KB (`json(...)` is `Content-Length` at every size). Before the
  fix the stream row was chunked at every size.
- 7.8 KB: json(...) 108.8k, stream 108.7k req/s.
- 22.9 KB: json(...) 68.8k req/s (p50 0.85, p99 2.19 ms), stream 43.4k (p50 0.52, p99 1.51 ms).
  With 64 connections, 43.4k req/s means a mean latency of about 1.48 ms, above the p99, so a
  small share of stream requests are very slow. Not investigated; oha's JSON for this wasn't
  kept (`sizes.sh` printed only the summary).

## 4. Open questions

- **forj-http's real overhead over raw Helidon SE.** Plan target: within ~10–15% on the same
  JDK. Both runs show ≤2%, but only because neither reached the server's limit; the target is
  met but the margin is unknown.
- **Streaming slowdown on small bodies: solved** (runs 3 and 5). Kept here for the record.
- **Chunked stream bodies over ~8 KB lose 37% of throughput (22.9 KB) with a long tail beyond
  the p99.** Unexplained. Candidates to test, none measured: the extra write for the closing
  chunk interacting with delayed ACKs/Nagle on loopback, or chunk-size framing overhead. Keep
  oha's JSON (`slowest`, the latency histogram) next time.
- **"Well above http4s"** (plan): on throughput, only clearly so under the 4-core cap; on p99
  latency it holds in both runs.

## 5. How to continue

1. `SERVER_CPUS=2 bin/bench forj-http forj-http-hand helidon-se-28` (about 4 minutes): may push
   Helidon below oha's ceiling so the forj-http vs raw Helidon gap shows. If the rows still
   match, try `SERVER_CPUS=1`.
2. The proper fix: oha on a second machine (`bin/bench` would need a `HOST` option and a way to
   start the server remotely), or Linux with `taskset` for strict pinning.
3. The large-body stream tail (section 4): rerun `/users/first/400` codec vs stream keeping
   oha's full JSON, then look at write counts per response; compare a `Body.writer` with a
   known length.
4. Before any run: on AC power (`pmset -g batt`), others on hold via forj-orchestrator.
5. After any run: add a section to this log, then update the tables and summary in
   [benchmarks.md](benchmarks.md) and the README's status.
