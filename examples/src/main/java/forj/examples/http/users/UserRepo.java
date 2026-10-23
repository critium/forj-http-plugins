package forj.examples.http.users;

import forj.typeclass.Sync;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Where users are kept: a tagless algebra, so the routes are written once for any {@code F},
 * and a database-backed version would be another interpreter of the same interface.
 */
public interface UserRepo<F<_>> {

    /** Every user, by id; those whose name starts with {@code prefix} when it's not empty. */
    F<List<User>> list(String prefix);

    F<Optional<User>> find(long id);

    F<Optional<User>> findByName(String name);

    F<User> add(NewUser user);

    /** The replaced user, or empty if there's none with that id. */
    F<Optional<User>> replace(long id, NewUser user);

    /** Whether there was one to delete. */
    F<Boolean> delete(long id);

    /** An in-memory interpreter for any effect that can suspend side effects. */
    static <F<_>> UserRepo<F> inMemory()(using Sync<F> sync) {
        ConcurrentSkipListMap<Long, User> users = new ConcurrentSkipListMap<>();
        AtomicLong ids = new AtomicLong();
        return new UserRepo<F>() {
            public F<List<User>> list(String prefix) {
                return sync.delay(() -> users.values().stream().filter(u -> u.name().startsWith(prefix)).toList());
            }

            public F<Optional<User>> find(long id) {
                return sync.delay(() -> Optional.ofNullable(users.get(id)));
            }

            public F<Optional<User>> findByName(String name) {
                return sync.delay(() -> users.values().stream().filter(u -> u.name().equals(name)).findFirst());
            }

            public F<User> add(NewUser user) {
                return sync.delay(() -> {
                    long id = ids.incrementAndGet();
                    User added = new User(id, user.name(), user.email());
                    users.put(id, added);
                    return added;
                });
            }

            public F<Optional<User>> replace(long id, NewUser user) {
                return sync.delay(() -> Optional.ofNullable(
                        users.computeIfPresent(id, (key, old) -> new User(id, user.name(), user.email()))));
            }

            public F<Boolean> delete(long id) {
                return sync.delay(() -> users.remove(id) != null);
            }
        };
    }
}
