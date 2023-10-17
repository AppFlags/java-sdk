package io.appflags.sdk.options;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class AppFlagsClientOptions {

    private String edgeUrlOverride;

    private ConfigurationOptions configurationOptions;

}
