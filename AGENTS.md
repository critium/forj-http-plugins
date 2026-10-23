# AGENTS.md: forj-http-plugins

forj-http-plugins holds forj-http's optional add-ons: backends (Helidon first), and the example
apps and the end-to-end benchmark, which need a backend. It's written **in forj**, a javac
plugin that adds Scala features to `.java` files. **Code here is not valid plain Java**, so an
editor, a linter, or your own knowledge of Java will flag it as broken. It isn't. The Mill build
is the source of truth: if `./mill __.test` passes, the code is correct.

Two parts:
- **Part 1** is how to read and write forj code. It's copied from forj's own `AGENTS.md`
  (`../../forj/AGENTS.md`, Part 1, as of forj `d4120bd`), via `../server/AGENTS.md`. When
  forj changes, resync it from there; forj's copy wins.
- **Part 2** is specific to this repo: design rules, layout, and how to work here.

Background: the plan is `../planning/lets-make-something-meaningfully-warm-lark.md`, forj-http's
own rules are in `../server/AGENTS.md`, forj's full guide is `../../forj/docs/java-developers.md`,
and forj's known limits are in `../../forj/TODO.md`.

## Part 1: code that uses forj

### Never "fix" these: they are forj syntax

| You see | It is | Compiles to |
|---|---|---|
| `forj { x <- fa; y <- fb; } yield x + y` | comprehension (Scala `for`/`yield`) | `forjFlatMap(fa, x -> forjMap(fb, y -> x + y))` with the `Monad<F>` passed in |
| `x <- e;` / `_ <- e;` | generator / generator whose value is discarded | a lambda parameter |
| `guard(cond);` | filter, directly after a generator | `forjFilter` (needs `FunctorFilter<F>`) |
| `f(A a)(using Show<A> s)` | context parameters (Scala 3 `using`) | `f(A a, @forj.Using Show<A> s)` |
| `f(x)(using hex)` at a call | passing an instance explicitly | `f(x, hex)` |
| `f(x)` where `f` has a `using` clause | **instances filled in by the compiler** | `f(x, Show.integer)` |
| `given Show<Integer> integer = ...;` | an instance (a member: `public static` by default) | `@forj.Given public static final Show<Integer> integer = ...;` |
| `given <A> Show<List<A>> list(using Show<A> e) {...}` | an instance built from others | a `@forj.Given` static method |
| `extension <A> String show(A a)(using Show<A> s)` | extension method | `@forj.Extension public static` |
| `money.show()` with no `show` on `Money` | extension call | `Show.show(money, Money.show)` |
| `interface Functor<F<_>>`, `<F<_>> F<A> pure(A a)` | higher-kinded type parameter | `F` is a plain type parameter |
| `F<A>` where `F` is declared `F<_>` | a type applied to a type constructor | `forj.Kind<F, A>` |
| `record User(long id, String name) derives Codec {}` | derived instance (Scala 3 `derives`) | adds `given Codec<User> codec = Codec.product(...)` to the record |
| `s"Hi $name, ${a + b}"` / `f"$x%.2f"` / `raw"C:\$p"` | string interpolation | `+` concatenation / `String.format` / raw text |
| `import static forj.Language.lifting;` | opt-in automatic lifting for this file | nothing; it changes how calls in the file resolve |

The keywords are contextual: `forj`, `given`, `using`, `extension` and `derives` remain
usable as ordinary names elsewhere. Each keyword form has a plain-Java spelling (`@Given`,
`@Using`, `@Extension`, `@Derives(X.class)`) with identical behaviour. Write that spelling
only where a file must stay parseable by plain tools.

### Invisible behaviour to account for when reading code

- **Calls can have arguments you don't see.** Any call to a method with a `using` clause gets
  its instances inserted. To know what runs, find the given. The search goes nearest first,
  and the first level with a match wins; two matches at one level is a compile error:
  1. local `given`s;
  2. the enclosing method's `using` parameters;
  3. the enclosing classes (and their supertypes);
  4. `import static`;
  5. the companions of the required type and its type arguments;
  6. `forj.Instances` (JDK types).
- **`x.m(...)` may not be a method of `x`.** It's an extension call when `x`'s type has no
  `m`. Lookup goes nearest first:
  1. the enclosing classes;
  2. `import static`;
  3. the type classes of givens in scope;
  4. the receiver's class and its type arguments' classes;
  5. `forj.Syntax`, which provides `map`, `flatMap`, `handleErrorWith`, `attempt`,
     `combine`, `sequence` and more on any `F<A>`.

  A real method always wins: `optional.map(f)` is `Optional.map`.
- **`F` is inferred from context,** not only from arguments:
  - a typed local: `IO<Receipt> r = checkout(order);` gives `F = IO`;
  - a `return`, or an assignment;
  - the parameter the call is passed to: `app.through(errors())` on an `HttpApp<IO>`;
  - the receiver of an instance method: `routes.orNotFound()` on a `Routes<IO>` needs
    `Applicative<IO>`.

  It is *not* inferred through a generic outer call (`describe(checkout(order))`), nor for a
  call used as a receiver (`tag("a").andThen(...)`). There, use a typed local or
  `Owner.<IO>checkout(order)`.
- **A `using` clause after varargs moves in front of them** in the compiled signature:
  `of(Route<F>... rs)(using Applicative<F> ap)` is `of(Applicative<F> ap, Route<F>... rs)` to
  plain Java callers and reflection.
- **Lifting**, only in files importing `forj.Language.*`:
  - `liftArguments` wraps a JDK `List`/`Optional`/`Callable` passed where a `Kind` is
    expected;
  - `liftReceivers` lets `list.map(f)` reach `Syntax.map`;
  - `liftResults` unwraps a `Kind` assigned to a JDK type.

### Rules when writing forj code

- Every generator ends with `;`. Generators are top-level statements of the block, never
  inside `if`/loops.
- `guard(...)` must directly follow a generator or another guard.
- No `return` and no `yield` inside a `forj { }` block; the result goes after it,
  `} yield expr`. (`yield` inside a `switch` expression is fine.)
- All generators in one block use the same `F`. Nested blocks may differ.
- Binder names are lambda parameters: they can't reuse an enclosing local's name and can't be
  reassigned.
- Put `using` parameters in their own clause, after the ordinary ones:
  `f(A a)(using Monad<F> m, Show<A> s)`.
- Comprehensions need `Monad<F>`. Ask for the weakest capability that works:
  - `Applicative` with `map2` for independent steps;
  - `Functor` for `map` alone.
- Two `using` overloads must differ in their ordinary parameter types. forj doesn't rank "more
  specific" overloads.
- For `derives X`, the type class `X` must declare:
  - static `field(String, Function<T, F>)(using X<F>)` and
    `product(Function<Object[], T>, Field...)`;
  - optionally `deriveProduct`/`deriveField`, for a generated straight-line encoder.

  Every component needs an `X` given in scope.
- No Jackson by default in forj projects: forj-http's JSON is its own streaming parser, and
  other parsers plug in behind an SPI.

### Reading errors

- `forj: ...` errors come from the plugin and point at your code. Read them first. javac
  often adds a second "cannot be applied to given types" error for the same call, which is
  noise once the `forj:` error is fixed.
- `forj: no given X<T> for m` means no instance of that type is in scope. Either import or
  declare one, or the effect lacks that capability: `FunctorFilter<Callable>` doesn't exist.
- `forj: cannot infer the type of using parameter ...` means `F` has no context to come from.
  Add a typed local, or explicit type arguments.
- `forj: ambiguous givens for ...` lists the clashing instances. Remove one, or pass one
  explicitly with `(using x)`.
- `plug-in not found: Forj` in a build with annotation processors means the plugin is missing
  from the processor path. This repo's `ForjModule` (in `build.mill`) handles it.
- An `IndexOutOfBoundsException` or other crash inside `forj.plugin.*` is a plugin bug, not
  your code. Make a minimal repro, then report it (see Part 2, "When forj gets in the way").
- Editors (IntelliJ, VS Code, Metals) show red on forj syntax and can't navigate into it.
  Ignore them; build instead.

## Part 2: this repo

### What lives where

| Path | Contents |
|---|---|
| `helidon/src/main/java/forj/http/helidon/` | `HelidonBackend` (the `Backend`), `AppHandler` (Helidon request in, app run, response out), `LimitedInputStream` (the body limit), `HelidonServer` (drain on close or JVM exit) |
| `helidon/src/test/java/forj/http/helidon/` | `HelidonBackendTest`: live tests on a random port with the JDK `HttpClient`, in `IO` and `Callable` |
| `examples/src/main/java/forj/examples/http/users/` | the users example: `User`/`NewUser` (`derives Codec`), `UserRepo<F<_>>` (tagless, in-memory interpreter), `UserRoutes`, `Main` |
| `examples/src/test/java/forj/examples/http/users/` | `UserRoutesTest` (no server, `IO` and `Callable`), `UsersLiveTest` (`Main.app()` on Helidon) |
| `build.mill` | `PinnedJdk`, `ForjModule`, `docStyle`: copied from forj's recipe via `../server/build.mill`; keep in step with them |

Planned (see the plan's "Order of work"): `bench`, the end-to-end benchmark (forj-http on
Helidon vs raw Helidon SE vs http4s Ember), `bin/bench` and `docs/benchmarks.md`.

### Design rules (don't undo these without asking)

- **forj-http's rules hold here** (`../server/AGENTS.md`, Part 2): no reflection in our code,
  generic in `F`, responses are plain values, errors never leak, middleware outside-in.
  Helidon itself uses `ServiceLoader` internally; that's the engine's business, not ours.
- **A backend follows the contract in `forj.http.server.Backend`'s javadoc:**
  - the app runs on the request's own virtual thread, with `effect.unsafeRunSync`, blocking;
    no hand-off to another pool;
  - the body limit gives 413: a declared `Content-Length` over it is answered before the app
    runs; a chunked body fails with an `HttpFailure(413)` as it's read, which `errors()`
    answers. Helidon's own `maxPayloadSize` is off so both cases look the same;
  - anything thrown out of the app is a bare 500, logged; a connection never just closes;
  - `close()` drains (Helidon's stop with `shutdownGrace`), runs once, and is also the JVM
    shutdown hook (Helidon's own hook is off).
- **Helidon is only the engine:** one `any(...)` handler, no Helidon routing, media support or
  error pages in the way.
- **Examples are written as users would write them**, with the recommended style: routes
  generic in `F` with the repository as a context parameter, JSON via `forj-http-json`, the
  standard middleware stack, `Main` reading `ServerConfig.fromEnvironment()`.
- **`examples` and `bench` aren't published;** `helidon` is (`forj-http-helidon`).

### Style

As in `../server/AGENTS.md`: `s"..."` instead of `+`, extension syntax from `forj.Syntax`,
comprehensions for sequential effects, `using` for instances and context, middleware and helpers
generic in `F` with the weakest capability, 4-space indentation, javadoc saying *why*, and
`{@link}` to a method with a `using` clause by name only.

### forj limits currently worked around here

None.

### When forj gets in the way

As in `../server/AGENTS.md`: a minimal repro outside the repo, the simplest workaround here
with a comment, an entry in `../../forj/TODO.md`, and dropping the workaround once forj fixes
it. Time-box hard javac debugging and write down what you found.

### Build and verify

- `bin/fetch-jdk`: run once, or point `.jdk/current` at forj's JDK (same pinned build) and
  run `bin/make-preview-jdk`.
- This repo builds against forj, forj-codec and forj-http from `~/.m2`. Publish order: forj,
  codec, server, plugins (`./mill __.publishM2Local` in each). After any of them republishes,
  run `./mill clean` here first: snapshot jars keep their path, and Mill tracks them by path.
- `./mill __.test` runs all tests; `./mill helidon.test` the backend's live tests.
- `./mill examples.runMain forj.examples.http.users.Main` serves the users example on 8080
  (`FORJ_HTTP_PORT` / `-Dforj.http.port` to change it).
- `./mill helidon.publishM2Local` publishes `forj:forj-http-helidon`.
- **Hosted docs:** `bin/publish-docs` (copied from forj; `MODULES=("helidon:api")`,
  `PAGES=("README.md")`). `--push` force-pushes `gh-pages`: **only with the user's OK**, and
  the same for enabling Pages.

### Working rules

- Commit only when asked.
- Don't modify other repos (`../../forj`, `../codec`, `../server`, `../planning`) without
  asking: other sessions work there. Logging a limit in forj's `TODO.md` is the exception.
- Every step of the plan ends with `./mill __.test` green. The plan pauses for review after
  step 4 (a server runs) and step 8 (benchmark numbers exist).
- Finishing a change users see: update `README.md` (status and examples) and, if it changes
  the plan, say so.
