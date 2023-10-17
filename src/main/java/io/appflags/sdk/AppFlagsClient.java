package io.appflags.sdk;

import io.appflags.protos.BucketingResult;
import io.appflags.protos.ComputedFlag;
import io.appflags.protos.User;
import io.appflags.sdk.exceptions.AppFlagsException;
import io.appflags.sdk.managers.bucketing.BucketingManager;
import io.appflags.sdk.managers.configuration.ConfigurationUpdateCallback;
import io.appflags.sdk.models.ConfigurationChangedHandler;
import io.appflags.sdk.managers.configuration.ConfigurationManager;
import io.appflags.sdk.models.AppFlagsFlag;
import io.appflags.sdk.models.AppFlagsUser;
import io.appflags.sdk.options.AppFlagsClientOptions;
import io.appflags.sdk.options.ConfigurationOptions;
import io.appflags.sdk.utils.ProtobufConverter;
import lombok.NonNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AppFlagsClient {

    private static final String EDGE_URL = "https://edge.appflags.net";

    private final ConfigurationManager configurationManager;
    private final BucketingManager bucketingManager;

    private final ExecutorService callbackThreadPool = Executors.newCachedThreadPool();
    private final List<ConfigurationChangedHandler> changeHandlers = new ArrayList<>();

    public AppFlagsClient(final String sdkKey) {
        this(sdkKey, AppFlagsClientOptions.builder().build());
    }

    public AppFlagsClient(final String sdkKey, final AppFlagsClientOptions options) {

        final String edgeUrl = options.getEdgeUrlOverride() != null ? options.getEdgeUrlOverride() : EDGE_URL;

        final ConfigurationOptions configurationOptions =
            options.getConfigurationOptions() != null ? options.getConfigurationOptions() :
            ConfigurationOptions.builder().build();
        final ConfigurationUpdateCallback configurationUpdateCallback = this::handleConfigurationUpdate;
        configurationManager = new ConfigurationManager(sdkKey, edgeUrl, configurationUpdateCallback, configurationOptions);

        bucketingManager = new BucketingManager();
        bucketingManager.setConfiguration(configurationManager.getConfiguration());
    }

    public Boolean getBooleanVariation(@NonNull final String flagKey, @NonNull final AppFlagsUser user, @Nullable final Boolean defaultValue) {
        final AppFlagsFlag<Boolean> flag = getBooleanFlag(flagKey, user);
        if (flag == null) {
            return defaultValue;
        }
        return flag.getValue();
    }

    public Double getNumberVariation(@NonNull final String flagKey, @NonNull final AppFlagsUser user, @Nullable final Double defaultValue) {
        final AppFlagsFlag<Double> flag = getNumberFlag(flagKey, user);
        if (flag == null) {
            return defaultValue;
        }
        return flag.getValue();
    }

    public String getStringVariation(@NonNull final String flagKey, @NonNull final AppFlagsUser user, @Nullable final String defaultValue) {
        final AppFlagsFlag<String> flag = getStringFlag(flagKey, user);
        if (flag == null) {
            return defaultValue;
        }
        return flag.getValue();
    }

    public AppFlagsFlag<Boolean> getBooleanFlag(@NonNull final String flagKey, @NonNull final AppFlagsUser user) {
        return getFlag(flagKey, user, AppFlagsFlag.FlagType.BOOLEAN);
    }

    public AppFlagsFlag<Double> getNumberFlag(@NonNull final String flagKey, @NonNull final AppFlagsUser user) {
        return getFlag(flagKey, user, AppFlagsFlag.FlagType.NUMBER);
    }

    public AppFlagsFlag<String> getStringFlag(@NonNull final String flagKey, @NonNull final AppFlagsUser user) {
        return getFlag(flagKey, user, AppFlagsFlag.FlagType.STRING);
    }

    private <T> AppFlagsFlag<T> getFlag(@NonNull final String flagKey, @NonNull final AppFlagsUser user, final AppFlagsFlag.FlagType flagType) {
        final Map<String, AppFlagsFlag> flags = getAllFlags(user);
        final AppFlagsFlag flag = flags.get(flagKey);
        if (flag == null) {
            return null;
        }
        if (flag.getFlagType() != flagType) {
            throw new AppFlagsException("Flag " + flagKey + " is not of type " + flagType.name());
        }
        //noinspection unchecked
        return (AppFlagsFlag<T>) flag;
    }

    public Map<String, AppFlagsFlag> getAllFlags(@NonNull final AppFlagsUser user) {
        final User protoUser = ProtobufConverter.toProtoUser(user);
        final BucketingResult bucketingResult =  bucketingManager.bucket(protoUser);
        final Map<String, AppFlagsFlag> flags = new HashMap<>();
        for (ComputedFlag computedFlag : bucketingResult.getFlagsList()) {
            flags.put(computedFlag.getKey(), ProtobufConverter.fromComputedFlag(computedFlag));
        }
        return flags;
    }

    private void handleConfigurationUpdate() {
        this.bucketingManager.setConfiguration(this.configurationManager.getConfiguration());
        invokeConfigurationChangeHandlers();
    }

    public void addConfigurationChangedHandler(ConfigurationChangedHandler handler) {
        this.changeHandlers.add(handler);
    }

    private void invokeConfigurationChangeHandlers() {
        for (final ConfigurationChangedHandler handler : changeHandlers) {
            callbackThreadPool.execute(handler::onConfigurationChange);
        }
    }

    public void close() {
        if (configurationManager != null) {
            configurationManager.close();
        }
        callbackThreadPool.shutdown();
    }
}
