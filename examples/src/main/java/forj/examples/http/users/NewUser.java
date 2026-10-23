package forj.examples.http.users;

import forj.http.HttpFailure;
import forj.http.Status;
import forj.json.Codec;

/** What a client sends to create or replace a user. */
public record NewUser(String name, String email) derives Codec {

    /** Itself, if it's acceptable. @throws HttpFailure 422, which the errors middleware turns into a problem response */
    public NewUser valid() {
        if (name == null || name.isBlank()) throw new HttpFailure(Status.UNPROCESSABLE_CONTENT, "name is required");
        if (email == null || !email.contains("@")) throw new HttpFailure(Status.UNPROCESSABLE_CONTENT, s"not an email address: $email");
        return this;
    }
}
