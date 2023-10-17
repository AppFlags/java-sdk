package io.appflags.sdk.options;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class ConfigurationOptions {

    private Integer pollingPeriodMs;
}
