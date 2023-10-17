package io.appflags.sdk.exceptions;

public class AppFlagsException extends RuntimeException {

    public AppFlagsException(String message) {
        super(message);
    }

    public AppFlagsException(String s, Exception e) {
    }
}
