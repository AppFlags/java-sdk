package io.appflags.sdk.models;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AppFlagsFlag<T> {

    public enum FlagType {
        BOOLEAN,
        NUMBER,
        STRING
    }

    private String key;

    private FlagType flagType;

    private T value;

}
