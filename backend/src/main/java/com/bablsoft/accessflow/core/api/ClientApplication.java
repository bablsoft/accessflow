package com.bablsoft.accessflow.core.api;

import java.util.Objects;

/** The calling application recorded on a request (#938): its name and how it was learned. */
public record ClientApplication(String name, ApplicationNameSource source) {

    public ClientApplication {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(source, "source");
    }

    public boolean trusted() {
        return source == ApplicationNameSource.API_KEY;
    }
}
