package forj.examples.http.users;

import static forj.http.Dsl.*;
import static forj.http.json.HttpJson.*;

import forj.http.HttpFailure;
import forj.http.Path;
import forj.http.Path1;
import forj.http.Problem;
import forj.http.Response;
import forj.http.Routes;
import forj.http.Status;
import forj.typeclass.MonadError;

/**
 * The users API, written once for any {@code F}. The repository comes in as a context
 * parameter, like the effect's instances, so nothing threads it by hand:
 *
 * <pre>{@code
 * given UserRepo<IO> repo = UserRepo.inMemory();
 * Routes<IO> routes = UserRoutes.routes();
 * }</pre>
 */
public final class UserRoutes {
    private UserRoutes() {
    }

    static final Path1<Long> USER = Path.of("/users").then(LongVar);

    /** Needs only {@code MonadError}: it maps, sequences and fails; the repository is what touches the world. */
    public static <F<_>> Routes<F> routes()(using MonadError<F, Throwable> errors, UserRepo<F> repo) {
        return Routes.of(
                GET("/").respond(req -> Ok("""
                        forj-http users example
                          GET    /users[?name=prefix]
                          GET    /users/{id}
                          POST   /users          {"name": ..., "email": ...}
                          PUT    /users/{id}     {"name": ..., "email": ...}
                          DELETE /users/{id}
                        """)),

                GET("/health").respond(req -> Ok("up")),

                GET("/users").to(req -> repo.list(req.query("name").orElse("")).map(users -> Ok(json(users)))),

                GET(USER).to((req, id) -> repo.find(id)
                        .map(found -> found.map(user -> Ok(json(user))).orElseGet(() -> missing(id)))),

                POST("/users").to(req -> {
                    NewUser body = req.jsonBody();
                    NewUser valid = body.valid();
                    return forj {
                        taken <- repo.findByName(valid.name());
                        user <- taken.isEmpty()
                                ? repo.add(valid)
                                : errors.<User>raiseError(new HttpFailure(Status.CONFLICT, s"the name ${valid.name()} is taken"));
                    } yield Created(json(user)).withHeader("Location", s"/users/${user.id()}");
                }),

                PUT(USER).to((req, id) -> {
                    NewUser body = req.jsonBody();
                    return repo.replace(id, body.valid())
                            .map(found -> found.map(user -> Ok(json(user))).orElseGet(() -> missing(id)));
                }),

                DELETE(USER).to((req, id) -> repo.delete(id).map(deleted -> deleted ? NoContent() : missing(id))));
    }

    private static Response missing(long id) {
        return new Problem(Status.NOT_FOUND, s"no user with id $id").toResponse();
    }
}
