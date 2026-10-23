# forj-http-plugins

Optional add-ons for [forj-http](https://github.com/critium/forj-http), the http4s-style server
framework for [forj](https://github.com/critium/forj): backends, and the example apps that need
one.

API docs (javadoc): https://critium.github.io/forj-http-plugins/api/

**Status:** the Helidon backend is published locally (`forj-http-helidon`), and the users example
runs on it. Next: the end-to-end benchmark (forj-http on Helidon vs raw Helidon SE vs http4s
Ember).

## The Helidon backend

`forj-http-helidon` serves any forj-http app on [Helidon WebServer 4](https://helidon.io):

```java
HttpApp<IO> app = routes.orNotFound().through(errors()).through(requestId()).through(accessLog());
try (Server server = HelidonBackend.create().start(ServerConfig.fromEnvironment(), app)) {
    server.await();
}
```

- **One virtual thread per request, no hand-off:** Helidon runs each request on a virtual
  thread, and the app runs right there, blocking (`effect.unsafeRunSync`). Any `F` with an
  `Effect` works: `IO`, `Callable`, ...
- **Helidon is only the engine:** one handler, no Helidon routing, media support or error
  pages in the way.
- **Body limit** (`ServerConfig.maxBodyBytes`): 413 for a declared `Content-Length` over it,
  before the app runs; a chunked body fails with a 413 `HttpFailure` as it's read, which
  `errors()` answers as a problem.
- **Failures:** anything that escapes the app is a bare 500, logged; a connection never just
  closes.
- **Shutdown:** `close()` (or the JVM exiting) stops accepting connections and lets requests in
  flight finish, for up to `ServerConfig.shutdownGrace`.

## The users example

A CRUD service in `examples/`, written the way forj-http intends:

- `User` and `NewUser` are records that `derives Codec`, so their JSON codecs are generated at
  compile time;
- `UserRepo<F<_>>` is a tagless algebra with an in-memory interpreter for any `Sync<F>`;
- `UserRoutes.routes()(using MonadError<F, Throwable>, UserRepo<F>)` is written once for any
  `F`, asking only for what it uses, with a forj comprehension where steps depend on each other;
- `Main` serves it in `IO` with `errors()`, `timeout(...)`, `cors(...)`, `requestId()` and
  `accessLog()`.

```java
POST("/users").to(req -> {
    NewUser body = req.jsonBody();
    NewUser valid = body.valid();                                   // 422 if not
    return forj {
        taken <- repo.findByName(valid.name());
        user <- taken.isEmpty()
                ? repo.add(valid)
                : errors.<User>raiseError(new HttpFailure(Status.CONFLICT, s"the name ${valid.name()} is taken"));
    } yield Created(json(user)).withHeader("Location", s"/users/${user.id()}");
}),
```

Run it:

```sh
./mill examples.runMain forj.examples.http.users.Main          # FORJ_HTTP_PORT=9000 to move it

curl -XPOST localhost:8080/users -H 'Content-Type: application/json' \
     -d '{"name":"ana","email":"ana@example.com"}'
# {"id":1,"name":"ana","email":"ana@example.com"}               201, Location: /users/1
curl localhost:8080/users/1
curl localhost:8080/users?name=a
curl -XPUT localhost:8080/users/1 -H 'Content-Type: application/json' \
     -d '{"name":"ana","email":"ana@new.example"}'
curl -XDELETE localhost:8080/users/1                            # 204
curl localhost:8080/users/1
# {"type":"about:blank","title":"Not Found","status":404,"detail":"no user with id 1"}
```

The server logs one line per request: `POST /users 201 5.3ms id=8e4810ebedc787ad`.

## Modules

| Module | Artifact | Contents |
|---|---|---|
| `helidon` | `forj-http-helidon` (published) | `HelidonBackend`: forj-http's `Backend` on Helidon WebServer 4 |
| `examples` | not published | `forj.examples.http.users`: the users example, its route tests and a live test |
| `bench` (planned) | not published | the end-to-end benchmark, `bin/bench`, `docs/benchmarks.md` |

## Building

This repo builds against forj, forj-codec and forj-http published to `~/.m2`, on the JDK build
forj is pinned to.

```sh
bin/fetch-jdk                                   # once: the pinned JDK 28 EA in .jdk/
(cd ../../forj && ./mill __.publishM2Local)     # then, after any upstream change, in order:
(cd ../codec && ./mill __.publishM2Local)
(cd ../server && ./mill __.publishM2Local)
./mill clean && ./mill __.test
```
