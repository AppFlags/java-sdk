package io.appflags.sdk.managers.configuration;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import io.appflags.sdk.exceptions.AppFlagsException;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class ConfigurationUpdateListener {

    private static final Logger logger = LoggerFactory.getLogger(ConfigurationUpdateListener.class);

    private static final Moshi MOSHI = new Moshi.Builder().build();
    private static final JsonAdapter<GetSseUrlResponse> getSseUrlResponseJsonAdapter = MOSHI.adapter(GetSseUrlResponse.class);
    private static final JsonAdapter<EventSourceMessage> eventSourceMessageJsonAdapter = MOSHI.adapter(EventSourceMessage.class);
    private static final JsonAdapter<ConfigurationUpdateEvent> configurationUpdateEventJsonAdapter = MOSHI.adapter(ConfigurationUpdateEvent.class);

    private static final OkHttpClient httpClient = new OkHttpClient();
    private static final OkHttpClient sseClient = new OkHttpClient.Builder()
        .connectTimeout(0, TimeUnit.MILLISECONDS) // no timeout
        .readTimeout(0, TimeUnit.MILLISECONDS) // no timeout
        .writeTimeout(0, TimeUnit.MILLISECONDS) // no timeout
        .build();

    private final EventSourceListener listener = new ConfigurationEventListener();

    private final String edgeUrl;
    private final String environmentId;
    private final UpdateEventHandler updateEventHandler;

    @Nullable
    private EventSource eventSource;
    @Nullable
    private String lastEventId;

    public ConfigurationUpdateListener(final String edgeUrl, final String environmentId, final UpdateEventHandler updateEventHandler) {
        this.edgeUrl = edgeUrl;
        this.environmentId = environmentId;
        this.updateEventHandler = updateEventHandler;

        createNewEventSource();
    }

    private void createNewEventSource()  {
        if (eventSource != null) {
            eventSource.cancel();
            logger.info("EventSource closed, creating new SSE EventSource");
        }

        String sseUrl;
        try {
            sseUrl = getSseUrl();
        } catch (IOException e) {
            throw new AppFlagsException("Unable to get SSE URL for new EventSource", e);
        }

        // Start with lastEvent if one is recorded (when restarting a connection after receiving a message)
        if (lastEventId != null) {
            sseUrl += "&lastEvent=" + lastEventId;
        }

        final Request request = new Request.Builder()
            .url(sseUrl)
            .build();
        final EventSource.Factory factory = EventSources.createFactory(sseClient);
        this.eventSource = factory.newEventSource(request, listener);
    }

    private String getSseUrl() throws IOException {
        final String url = edgeUrl + "/realtimeToken/" + environmentId  + "/eventSource";
        final Request request = new Request.Builder()
            .url(url)
            .build();
        final Response response = httpClient.newCall(request).execute();
        final String responseBodyJson = response.body().string();
        final GetSseUrlResponse getSseUrlResponse = getSseUrlResponseJsonAdapter.fromJson(responseBodyJson);
        return getSseUrlResponse.url;
    }

    public void close() {
        if (eventSource != null) {
            eventSource.cancel();
        }
    }

    private final class ConfigurationEventListener extends EventSourceListener {
        @Override
        public void onClosed(@NotNull EventSource eventSource) {
            logger.debug("ConfigurationUpdaterListener EventSource closed, starting a new one");
            createNewEventSource();
        }

        @Override
        public void onEvent(@NotNull EventSource eventSource, @Nullable String id, @Nullable String type, @NotNull String data) {
            logger.debug("ConfigurationUpdaterListener EventSource, handling event of type: " + type);
            lastEventId = id;
            if ("message".equals(type)) {
                try {
                    final EventSourceMessage eventSourceMessage = eventSourceMessageJsonAdapter.fromJson(data);
                    final ConfigurationUpdateEvent configurationUpdateEvent = configurationUpdateEventJsonAdapter.fromJson(eventSourceMessage.data);
                    updateEventHandler.onConfigurationUpdateEvent(configurationUpdateEvent.published);
                } catch (IOException e) {
                    throw new AppFlagsException("Error handling ConfigurationUpdaterListener EventSource message", e);
                }
            }
        }

        @Override
        public void onFailure(@NotNull EventSource eventSource, @Nullable Throwable t, @Nullable Response response) {
            logger.error("ConfigurationUpdaterListener EventSource failure, starting a new one", t);
            createNewEventSource();
        }

        @Override
        public void onOpen(@NotNull EventSource eventSource, @NotNull Response response) {
            logger.trace("ConfigurationUpdaterListener opened");
        }
    }

    public interface UpdateEventHandler {
        void onConfigurationUpdateEvent(double published);
    }

    private static final class GetSseUrlResponse {
        String url;
    }

    private static final class EventSourceMessage {
        String data;
    }

    private static final class ConfigurationUpdateEvent {
        double published;
    }
}
