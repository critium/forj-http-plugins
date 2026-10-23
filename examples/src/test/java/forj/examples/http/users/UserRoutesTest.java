package forj.examples.http.users;

import static forj.http.middleware.Middlewares.*;
import static forj.http.testing.HttpTest.assertThat;
import static forj.http.testing.HttpTest.run;

import forj.effect.IO;
import forj.http.HttpApp;
import forj.http.Request;
import forj.http.Routes;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;

/** The routes with no server: requests in, responses out. */
@SuppressWarnings("rawtypes")
class UserRoutesTest {

    static Request json(Request request, String body) {
        return request.withHeader("Content-Type", "application/json").withBody(body);
    }

    static final String ANA = "{\"name\":\"ana\",\"email\":\"ana@example.com\"}";

    @Test
    void crud() {
        given UserRepo<IO> repo = UserRepo.inMemory();
        Routes<IO> routes = UserRoutes.routes();
        HttpApp<IO> app = routes.orNotFound().through(errors());

        assertThat(run(app, json(Request.post("/users"), ANA)))
                .hasStatus(201)
                .hasHeader("Location", "/users/1")
                .hasHeader("Content-Type", "application/json")
                .hasBody("{\"id\":1,\"name\":\"ana\",\"email\":\"ana@example.com\"}");
        assertThat(run(app, json(Request.post("/users"), "{\"name\":\"bo\",\"email\":\"bo@example.com\"}"))).hasStatus(201);

        assertThat(run(app, Request.get("/users/1"))).hasStatus(200).hasBodyContaining("\"name\":\"ana\"");
        assertThat(run(app, Request.get("/users")))
                .hasBody("[{\"id\":1,\"name\":\"ana\",\"email\":\"ana@example.com\"},{\"id\":2,\"name\":\"bo\",\"email\":\"bo@example.com\"}]");
        assertThat(run(app, Request.get("/users?name=b"))).hasBody("[{\"id\":2,\"name\":\"bo\",\"email\":\"bo@example.com\"}]");

        assertThat(run(app, json(Request.put("/users/1"), "{\"name\":\"ana\",\"email\":\"ana@new.example\"}")))
                .hasStatus(200)
                .hasBodyContaining("ana@new.example");

        assertThat(run(app, Request.delete("/users/1"))).hasStatus(204).hasEmptyBody();
        assertThat(run(app, Request.get("/users/1")))
                .hasStatus(404)
                .hasHeader("Content-Type", "application/problem+json")
                .hasBodyContaining("no user with id 1");
        assertThat(run(app, Request.delete("/users/1"))).hasStatus(404);
    }

    @Test
    void clientErrorsAreProblems() {
        given UserRepo<IO> repo = UserRepo.inMemory();
        Routes<IO> routes = UserRoutes.routes();
        HttpApp<IO> app = routes.orNotFound().through(errors());

        assertThat(run(app, json(Request.post("/users"), ANA))).hasStatus(201);
        assertThat(run(app, json(Request.post("/users"), ANA))).hasStatus(409).hasBodyContaining("the name ana is taken");
        assertThat(run(app, json(Request.post("/users"), "{\"name\":\"\",\"email\":\"x@y\"}")))
                .hasStatus(422)
                .hasBodyContaining("name is required");
        assertThat(run(app, json(Request.post("/users"), "{\"name\":\"cy\"")))
                .hasStatus(400)
                .hasBodyContaining("invalid JSON");
        assertThat(run(app, Request.post("/users").withHeader("Content-Type", "text/plain").withBody(ANA))).hasStatus(415);
        assertThat(run(app, Request.get("/users/abc"))).hasStatus(404);
        assertThat(run(app, Request.patch("/users/1"))).hasStatus(405).hasHeader("Allow", "GET, PUT, DELETE");
    }

    @Test
    void theSameRoutesRunInCallable() {
        given UserRepo<Callable> repo = UserRepo.inMemory();
        Routes<Callable> routes = UserRoutes.routes();
        HttpApp<Callable> app = routes.orNotFound().through(errors());

        assertThat(run(app, json(Request.post("/users"), ANA))).hasStatus(201);
        assertThat(run(app, json(Request.post("/users"), ANA))).hasStatus(409);
        assertThat(run(app, Request.get("/users/1"))).hasStatus(200).hasBodyContaining("\"id\":1");
    }
}
