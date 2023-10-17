package io.appflags.sdk.managers.configuration;

import com.google.protobuf.util.Timestamps;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import io.appflags.protos.Configuration;
import io.appflags.protos.ConfigurationLoadMetadata;
import io.appflags.protos.ConfigurationLoadType;
import io.appflags.protos.PlatformData;
import io.appflags.sdk.exceptions.AppFlagsException;
import io.appflags.sdk.options.ConfigurationOptions;
import io.appflags.sdk.utils.DaemonThreadFactory;
import io.appflags.sdk.utils.PlatformDataUtil;
import lombok.NonNull;
import okhttp3.*;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


public class ConfigurationManager {

    private static final Logger logger = LoggerFactory.getLogger(ConfigurationManager.class);

    private static final Moshi MOSHI = new Moshi.Builder().build();
    private static final JsonAdapter<GetConfigurationRequest> getConfigurationRequestJsonAdapter = MOSHI.adapter(GetConfigurationRequest.class);
    private static final JsonAdapter<GetConfigurationResponse> getConfigurationResponseJsonAdapter = MOSHI.adapter(GetConfigurationResponse.class);

    private static final OkHttpClient httpClient = new OkHttpClient();
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private static final int ONE_MIN_MS = 60000;
    private static final int DEFAULT_POLLING_PERIOD = 10 * ONE_MIN_MS;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(new DaemonThreadFactory());
    private final ExecutorService reloadExecutor = Executors.newSingleThreadExecutor();

    private final String skdKey;
    private final String edgeUrl;
    private final ConfigurationUpdateCallback updateCallback;

    private final PlatformData platformData;

    private Configuration configuration;
    private ConfigurationUpdateListener configurationUpdateListener;

    public ConfigurationManager(String skdKey, String edgeUrl, ConfigurationUpdateCallback updateCallback, ConfigurationOptions options) {
        this.skdKey = skdKey;
        this.edgeUrl = edgeUrl;
        this.updateCallback = updateCallback;
        this.platformData = PlatformDataUtil.getPlatformData();

        try {
            this.configuration = loadConfiguration(ConfigurationLoadType.INITIAL_LOAD);
        } catch (IOException e) {
            throw new AppFlagsException("Error loading AppFlags configuration", e);
        }

        int pollingPeriod = DEFAULT_POLLING_PERIOD;
        if (options.getPollingPeriodMs() != null) {
            pollingPeriod = Math.max(ONE_MIN_MS, options.getPollingPeriodMs());
            logger.info("Configuration polling period set to " + pollingPeriod + " ms.");
        }
        pollForConfigurationUpdates(pollingPeriod);

        if (this.configuration.hasEnvironmentId()) {
            try {
                listenForConfigurationUpdates(this.configuration.getEnvironmentId());
            } catch (IOException e) {
                logger.error("Error listening for realtime updates, no realtime updates will occur.", e);
            }
        }
    }

    public Configuration getConfiguration() {
        if (this.configuration == null) {
            throw new AppFlagsException("ConfigurationManager not initialized");
        }
        return this.configuration;
    }

    private Configuration loadConfiguration(@NonNull ConfigurationLoadType loadType) throws IOException {
        return loadConfiguration(loadType, null);
    }

    private Configuration loadConfiguration(@NonNull ConfigurationLoadType loadType, @Nullable Double getUpdateAt) throws IOException {
        final ConfigurationLoadMetadata configurationLoadMetadata = ConfigurationLoadMetadata.newBuilder()
            .setLoadType(loadType)
            .setPlatformData(this.platformData)
            .build();
        final String encodedMetadata = Base64.getEncoder().encodeToString(configurationLoadMetadata.toByteArray());

        final GetConfigurationRequest getConfigurationBody = new GetConfigurationRequest(encodedMetadata);
        final String requestBodyJson = getConfigurationRequestJsonAdapter.toJson(getConfigurationBody);

        String url = this.edgeUrl + "/configuration/v1/config";
        if (getUpdateAt != null) {
            url += "?getUpdateAt=" + getUpdateAt;
        }

        final RequestBody body = RequestBody.create(requestBodyJson, JSON);
        final Request request = new Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer: " + this.skdKey)
            .post(body)
            .build();
        final Response response = httpClient.newCall(request).execute();
        final String responseBodyJson = response.body().string();

        final GetConfigurationResponse getConfigurationResponse = getConfigurationResponseJsonAdapter.fromJson(responseBodyJson);
        final Configuration configuration = Configuration.parseFrom(Base64.getDecoder().decode(getConfigurationResponse.configuration));
        logger.debug("Loaded configuration published at " + Timestamps.toString(configuration.getPublished()) + ", contains " + configuration.getFlagsCount() + " flags.");
        return configuration;
    }

    private void pollForConfigurationUpdates(final int pollingPeriodMs) {
        final Runnable runnable = () -> {
            try {
                logger.debug("Triggering periodic configuration reload");
                final Configuration newConfig = loadConfiguration(ConfigurationLoadType.PERIODIC_RELOAD);
                updateConfigurationIfNewer(newConfig);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };
        scheduler.schedule(runnable, pollingPeriodMs, TimeUnit.MILLISECONDS);
    }

    private void handleConfigurationUpdateEvent(final double published) {
        reloadExecutor.execute(() -> {
            try {
                logger.debug("Notified of configuration change, retrieving updated configuration now");
                Configuration newConfig = loadConfiguration(ConfigurationLoadType.REALTIME_RELOAD, published);
                updateConfigurationIfNewer(newConfig);
            } catch (IOException e) {
                throw new AppFlagsException("Error loading configuration during realtime update", e);
            }
        });
    }
    private void listenForConfigurationUpdates(final String environmentId) throws IOException {
        configurationUpdateListener = new ConfigurationUpdateListener(this.edgeUrl, environmentId, this::handleConfigurationUpdateEvent);
    }

    private void updateConfigurationIfNewer(@NonNull final Configuration newConfig) {
        if (this.configuration == null) {
            throw new RuntimeException("Not initialized");
        }
        if (!this.configuration.hasPublished()) {
            throw new RuntimeException("Current configuration is missing `published` property");
        }
        if (!newConfig.hasPublished()) {
            throw new RuntimeException("New configuration is missing `published` property");
        }
        if (Timestamps.compare(newConfig.getPublished(), this.configuration.getPublished()) > 0) {
            this.configuration = newConfig;
            logger.info("Updated configuration with new configuration published at " + Timestamps.toString(this.configuration.getPublished()));
            updateCallback.handleUpdate();
        } else {
            logger.debug("Not updating configuration because the new configuration is not newer than the current configuration");
        }
    }


    private static class GetConfigurationRequest {
        @SuppressWarnings("FieldCanBeLocal")
        private final String metadata;

        public GetConfigurationRequest(String metadata) {
            this.metadata = metadata;
        }
    }

    private static class GetConfigurationResponse {
        private final String configuration;

        private GetConfigurationResponse(String configuration) {
            this.configuration = configuration;
        }
    }

    public void close() {
        if (configurationUpdateListener != null) {
            configurationUpdateListener.close();
        }
        scheduler.shutdown();
        reloadExecutor.shutdown();
    }
}
