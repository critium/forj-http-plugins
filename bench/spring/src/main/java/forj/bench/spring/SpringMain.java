package forj.bench.spring;

import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Spring Boot MVC in bin/bench, as a Spring team would write it: annotated controller, Tomcat on
 * virtual threads, records serialized by Jackson. Same endpoints, store and port
 * ({@code FORJ_HTTP_PORT}) as forj.bench.ForjHttpMain.
 */
@SpringBootApplication
@RestController
public class SpringMain {

    public record Message(String message) {
    }

    public record User(long id, String name, String email) {
    }

    private final ConcurrentSkipListMap<Long, User> users = new ConcurrentSkipListMap<>();

    public SpringMain() {
        for (long i = 1; i <= 1000; i++) users.put(i, new User(i, "user" + i, "user" + i + "@example.com"));
    }

    @GetMapping(value = "/plaintext", produces = MediaType.TEXT_PLAIN_VALUE)
    public String plaintext() {
        return "Hello, World!";
    }

    @GetMapping("/json")
    public Message json() {
        return new Message("Hello, World!");
    }

    @GetMapping("/users/{id}")
    public ResponseEntity<User> user(@PathVariable("id") long id) {
        User user = users.get(id);
        return user == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(user);
    }

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(SpringMain.class);
        app.setDefaultProperties(Map.of(
                "server.port", System.getenv().getOrDefault("FORJ_HTTP_PORT", "8080"),
                "spring.threads.virtual.enabled", "true",
                "spring.main.banner-mode", "off"));
        app.run(args);
    }
}
