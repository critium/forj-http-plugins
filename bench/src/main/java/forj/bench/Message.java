package forj.bench;

import forj.json.Codec;

/** The JSON endpoint's body, {@code {"message":"Hello, World!"}}, as in TechEmpower's JSON test. */
public record Message(String message) derives Codec {
}
