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

## 4. Open questions

- **forj-http's real overhead over raw Helidon SE.** Plan target: within ~10–15% on the same
  JDK. Both runs show ≤2%, but only because neither reached the server's limit; the target is
  met but the margin is unknown.
- **Why `forj-http-stream` is 41% slower on small bodies.** Deferred by the user to next week
  (2026-10-23); not investigated. Observed facts only: the response has no `Content-Length`
  and goes out chunked; plaintext on the same server is unaffected. Relevant to forj-codec and
  forj-http (whether streaming is a recommended path for small bodies); don't document a cause
  in the human docs until it's measured.
- **"Well above http4s"** (plan): on throughput, only clearly so under the 4-core cap; on p99
  latency it holds in both runs.

## 5. How to continue

1. `SERVER_CPUS=2 bin/bench forj-http forj-http-hand helidon-se-28` (about 4 minutes): may push
   Helidon below oha's ceiling so the forj-http vs raw Helidon gap shows. If the rows still
   match, try `SERVER_CPUS=1`.
2. The proper fix: oha on a second machine (`bin/bench` would need a `HOST` option and a way to
   start the server remotely), or Linux with `taskset` for strict pinning.
3. The streaming question (section 4), when the user picks it up: compare write counts or
   syscalls per response (e.g. `dtruss`), and try a known length or a single flush.
4. After any run: add a section to this log, then update the tables and summary in
   [benchmarks.md](benchmarks.md) and the README's status.
