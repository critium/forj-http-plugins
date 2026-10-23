package forj.examples.http.users;

import forj.json.Codec;

/** A stored user. {@code derives Codec}: the JSON codec is generated at compile time, no reflection. */
public record User(long id, String name, String email) derives Codec {
}
