package io.appflags.sdk.models;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class AppFlagsUser {

    @Builder.Default
    private String key = "";

    public static AppFlagsUser SYSTEM = AppFlagsUser.builder().build();
}
