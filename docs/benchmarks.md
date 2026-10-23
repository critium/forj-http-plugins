# Benchmarks

The end-to-end benchmark: one forj-http app on Helidon against the servers it is compared with,
all serving the same three endpoints. `bin/bench` runs it; the module is `bench` in
`build.mill`.

## Summary

Two runs, 2026-10-23, on one Apple M2 Pro (10 cores), with the load generator (oha) on the same
machine:

- **forj-http matches raw Helidon SE** on every endpoint, with a p99 of 0.8 ms, whether its JSON
  comes from forj-codec or is written by hand: within 1–2% at about 107k req/s uncapped
  ([run 1](#run-1-uncapped)), and within 1% at about 105k req/s with the servers capped at 4
  cores ([run 2](#run-2-servers-capped-at-4-cores)).
- **That figure is the ceiling of the setup, not of the servers.** Every Helidon-based row lands
  on the same throughput and p50, and capping the servers at 4 cores (`SERVER_CPUS=4`) moved
  them only from about 107k to 105k req/s. oha and the loopback connection are the limit, so
  forj-http's own per-request cost is below what these runs can measure. Measuring it needs
  load from a second machine.
- **http4s on Ember** handles 3–22% fewer requests than forj-http in run 1 and 24–35% fewer in
  run 2, with a p99 four times as high in run 1 and twice as high in run 2.
- **Spring Boot MVC** handles 8–29% fewer requests than forj-http in run 1 and 10–28% fewer in
  run 2, with a p99 1.5–2.3 times as high.
- **Startup:** about 330 ms for forj-http and Helidon, 760 ms for http4s, 860 ms for Spring.
  **Size on disk:** 3 MB for forj-http (forj, forj-http, forj-json and Helidon), 2.8 MB for
  Helidon alone, 19 MB for Spring, 34 MB for http4s (Scala's library included).
- **Streaming JSON** (`forj-http-stream`) served 63k req/s on the JSON endpoints, 41% fewer than
  the default `json(...)`. Its plaintext numbers match the other forj-http rows.
- **Memory (RSS)** follows the JDK more than the framework: under the same `-Xmx1g`, the JDK 28
  rows used 140–255 MB and the JDK 25 rows 380–500 MB, raw Helidon included. Compare RSS only
  within a JDK.

## Run 1: uncapped

`bin/bench`: every server, each free to use all 10 cores.

Machine: Apple M2 Pro, 10 cores, 32 GB; macOS 27.0.1.
Load: oha 1.16.0, 64 connections, 10s warmup then 15s measured per endpoint, on the same machine. JVM flags: `-Xmx1g`.

| server | JDK | endpoint | req/s | p50 ms | p99 ms | non-2xx | startup ms | RSS MB | size MB |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|
| forj-http | 28-ea+18-1391 | plaintext | 107482 | 0.59 | 0.81 | 0 | 329 | 189 | 3 |
| forj-http | 28-ea+18-1391 | json | 107139 | 0.59 | 0.81 | 0 | 329 | 190 | 3 |
| forj-http | 28-ea+18-1391 | users | 107529 | 0.59 | 0.8 | 0 | 329 | 206 | 3 |
| forj-http-hand | 28-ea+18-1391 | plaintext | 107106 | 0.59 | 0.8 | 0 | 334 | 220 | 3 |
| forj-http-hand | 28-ea+18-1391 | json | 106913 | 0.59 | 0.79 | 0 | 334 | 218 | 3 |
| forj-http-hand | 28-ea+18-1391 | users | 107830 | 0.59 | 0.83 | 0 | 334 | 217 | 3 |
| forj-http-stream | 28-ea+18-1391 | plaintext | 106935 | 0.59 | 0.78 | 0 | 340 | 254 | 3 |
| forj-http-stream | 28-ea+18-1391 | json | 63295 | 1.01 | 1.19 | 0 | 340 | 241 | 3 |
| forj-http-stream | 28-ea+18-1391 | users | 63335 | 1.01 | 1.18 | 0 | 340 | 242 | 3 |
| helidon-se-28 | 28-ea+18-1391 | plaintext | 106902 | 0.59 | 0.8 | 0 | 335 | 165 | 2.8 |
| helidon-se-28 | 28-ea+18-1391 | json | 107143 | 0.59 | 0.81 | 0 | 335 | 168 | 2.8 |
| helidon-se-28 | 28-ea+18-1391 | users | 108989 | 0.58 | 0.91 | 0 | 335 | 178 | 2.8 |
| helidon-se-25 | 25.0.2+10-LTS | plaintext | 109220 | 0.58 | 0.81 | 0 | 304 | 435 | 2.8 |
| helidon-se-25 | 25.0.2+10-LTS | json | 109229 | 0.58 | 0.79 | 0 | 304 | 437 | 2.8 |
| helidon-se-25 | 25.0.2+10-LTS | users | 109219 | 0.58 | 0.82 | 0 | 304 | 447 | 2.8 |
| http4s | 25.0.2+10-LTS | plaintext | 88069 | 0.55 | 3.51 | 0 | 758 | 465 | 34.4 |
| http4s | 25.0.2+10-LTS | json | 104027 | 0.45 | 3 | 0 | 758 | 494 | 34.4 |
| http4s | 25.0.2+10-LTS | users | 99860 | 0.46 | 3.31 | 0 | 758 | 502 | 34.4 |
| spring | 25.0.2+10-LTS | plaintext | 98933 | 0.63 | 1.22 | 0 | 862 | 419 | 18.8 |
| spring | 25.0.2+10-LTS | json | 98467 | 0.63 | 1.21 | 0 | 862 | 419 | 18.8 |
| spring | 25.0.2+10-LTS | users | 76402 | 0.79 | 1.87 | 0 | 862 | 433 | 18.8 |

## Run 2: servers capped at 4 cores

`SERVER_CPUS=4 bin/bench forj-http forj-http-hand helidon-se-28 http4s spring`: each server's JVM
sizes its thread pools for 4 cores (`-XX:ActiveProcessorCount=4`), and oha has the other 6.

Machine: Apple M2 Pro, 10 cores, 32 GB; macOS 27.0.1.
Load: oha 1.16.0, 64 connections, 10s warmup then 15s measured per endpoint, on the same machine. JVM flags: `-Xmx1g -XX:ActiveProcessorCount=4`.

| server | JDK | endpoint | req/s | p50 ms | p99 ms | non-2xx | startup ms | RSS MB | size MB |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|
| forj-http | 28-ea+18-1391 | plaintext | 105395 | 0.6 | 0.86 | 0 | 346 | 145 | 3 |
| forj-http | 28-ea+18-1391 | json | 104687 | 0.61 | 0.81 | 0 | 346 | 152 | 3 |
| forj-http | 28-ea+18-1391 | users | 104788 | 0.6 | 0.82 | 0 | 346 | 199 | 3 |
| forj-http-hand | 28-ea+18-1391 | plaintext | 104389 | 0.59 | 1.11 | 0 | 334 | 168 | 3 |
| forj-http-hand | 28-ea+18-1391 | json | 105099 | 0.6 | 0.93 | 0 | 334 | 166 | 3 |
| forj-http-hand | 28-ea+18-1391 | users | 105392 | 0.6 | 0.83 | 0 | 334 | 176 | 3 |
| helidon-se-28 | 28-ea+18-1391 | plaintext | 105140 | 0.6 | 0.79 | 0 | 334 | 140 | 2.8 |
| helidon-se-28 | 28-ea+18-1391 | json | 105467 | 0.6 | 0.8 | 0 | 334 | 141 | 2.8 |
| helidon-se-28 | 28-ea+18-1391 | users | 105400 | 0.6 | 0.8 | 0 | 334 | 149 | 2.8 |
| http4s | 25.0.2+10-LTS | plaintext | 68530 | 0.9 | 1.73 | 0 | 765 | 428 | 34.4 |
| http4s | 25.0.2+10-LTS | json | 80075 | 0.72 | 1.61 | 0 | 765 | 458 | 34.4 |
| http4s | 25.0.2+10-LTS | users | 77815 | 0.81 | 1.65 | 0 | 765 | 476 | 34.4 |
| spring | 25.0.2+10-LTS | plaintext | 95088 | 0.66 | 1.28 | 0 | 865 | 380 | 18.8 |
| spring | 25.0.2+10-LTS | json | 94706 | 0.66 | 1.27 | 0 | 865 | 386 | 18.8 |
| spring | 25.0.2+10-LTS | users | 75914 | 0.82 | 1.58 | 0 | 865 | 397 | 18.8 |

## What runs

| Row | What | JDK | JSON |
|---|---|---|---|
| `forj-http` | forj-http on Helidon (`forj.bench.ForjHttpMain`), routes generic in `F`, run in `IO`, no middleware. JSON as an app writes it: `Ok(json(user))` | 28 EA (preview, as forj requires) | forj-json, `derives Codec`, into a `byte[]` |
| `forj-http-hand` | the same server, JSON written by hand as in the raw Helidon SE row, so the gap to that row is forj-http alone | 28 EA | written by hand |
| `forj-http-stream` | the same server, with forj-codec's streaming writer: `Json.encode(value, out)` in a `Body.writer`, straight onto the connection through a pooled, bounded buffer, with no `byte[]` per response and no `Content-Length` (the response is chunked) | 28 EA | forj-json, `derives Codec`, streamed |
| `helidon-se-28` | raw Helidon SE 4 (`HelidonSeMain`): Helidon's routing and handlers, nothing on top. The floor: forj-http's overhead is the gap to this row | 28 EA | written by hand |
| `helidon-se-25` | the same, on JDK 25, so a JDK difference shows apart from a framework one | 25 LTS | written by hand |
| `http4s` | http4s 0.23 on Ember (`Http4sMain`), the pure-FP design forj-http is modelled on | 25 LTS | jsoniter-scala, compile-time derived |
| `spring` | Spring Boot 4 MVC on Tomcat with virtual threads (`SpringMain`): what most Java services run. Reflection and DI; a reference row only, never a dependency of anything published | 25 LTS | Jackson |

The three forj-http rows are one server (`FORJ_BENCH_JSON=codec|hand|stream`), so they differ
only in how JSON is written. `/plaintext` is the same in all three.

The JDK 25 rows exist because Scala, Spring and http4s don't need preview features and may not
read JDK 28 class files; forj's preview class files need 28.

## Endpoints

- `GET /plaintext`: `Hello, World!`, `text/plain`;
- `GET /json`: `{"message":"Hello, World!"}`, serialized per request;
- `GET /users/{id}`: one of 1000 users from an in-memory store (`ConcurrentSkipListMap`), as
  JSON. Each request asks for a random id from 1 to 999. forj-http reads it through the users
  example's `UserRepo<IO>`, http4s through `IO`, the others directly.

Before loading a server, `bin/bench` checks that it gives exactly the expected body and content
type on each endpoint, so no row wins by doing less.

## Method

- Servers run one at a time, outside Mill, with the same JVM flags (`-Xmx1g`, plus
  `-XX:ActiveProcessorCount=N` when `SERVER_CPUS=N` is set) and port.
- Each endpoint gets a warmup run (10 s), then a measured run (15 s), with 64 connections and
  keep-alive (oha).
- Recorded per row: requests per second; p50 and p99 latency; non-2xx responses (0 in every row
  above); startup time, from launching the JVM to the first 200 response; RSS after the
  endpoint's run; and size on disk (the app's classpath: its classes and every jar it ships
  with).
- **The load generator shares the machine.** oha and the server compete for the same cores and
  talk over loopback, so when a server is fast enough, the row measures oha rather than the
  server. That is the case for every Helidon-based row above. Compare rows; don't read the
  absolute numbers as a server's capacity.
- **`SERVER_CPUS` is a cap, not pinning.** macOS has no CPU affinity: the JVM sizes its pools
  (virtual-thread carriers, cats-effect's compute pool, GC threads) for that many cores, but
  the OS still schedules its threads anywhere. On Linux, `taskset` would pin strictly.

## Running it

```sh
brew install oha            # and jq, if missing
bin/bench --check           # start each server, check its answers, no load
bin/bench                   # everything, about 10 minutes
bin/bench forj-http helidon-se-28
DURATION=30s CONNECTIONS=128 bin/bench
SERVER_CPUS=4 bin/bench     # each server sized for 4 cores; oha keeps the rest
```

Results go to `out/bench/<time>/`: `results.md` (a run's table, as above), `results.tsv`, oha's
JSON for every run, and each server's log. Don't run it while anything else timing-sensitive
runs on the machine.

For agents and maintainers continuing the work: [benchmarks-for-agents.md](benchmarks-for-agents.md).
