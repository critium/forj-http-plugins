package forj.bench;

import static forj.http.testing.HttpTest.assertThat;
import static forj.http.testing.HttpTest.run;

import forj.bench.ForjHttpMain.JsonMode;
import forj.effect.IO;
import forj.http.HttpApp;
import forj.http.Request;
import org.junit.jupiter.api.Test;

/** The bodies bin/bench checks every server against, before it loads any of them, in each JSON mode. */
class ForjHttpMainTest {

    @Test
    void servesTheBenchEndpointsInEveryJsonMode() throws Exception {
        for (JsonMode mode : JsonMode.values()) {
            HttpApp<IO> app = ForjHttpMain.app(mode);
            assertThat(run(app, Request.get("/plaintext"))).hasStatus(200).hasBody("Hello, World!");
            assertThat(run(app, Request.get("/json")))
                    .hasStatus(200)
                    .hasHeader("Content-Type", "application/json")
                    .hasBody("{\"message\":\"Hello, World!\"}");
            assertThat(run(app, Request.get("/users/1")))
                    .hasStatus(200)
                    .hasHeader("Content-Type", "application/json")
                    .hasBody("{\"id\":1,\"name\":\"user1\",\"email\":\"user1@example.com\"}");
            assertThat(run(app, Request.get("/users/1000"))).hasStatus(200);
            assertThat(run(app, Request.get("/users/1001"))).hasStatus(404);
            assertThat(run(app, Request.get("/users/first/2")))
                    .hasStatus(200)
                    .hasBody("[{\"id\":1,\"name\":\"user1\",\"email\":\"user1@example.com\"},{\"id\":2,\"name\":\"user2\",\"email\":\"user2@example.com\"}]");
        }
    }
}
