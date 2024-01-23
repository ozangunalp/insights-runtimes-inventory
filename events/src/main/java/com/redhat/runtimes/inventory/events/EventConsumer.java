/* Copyright (C) Red Hat 2023-2024 */
package com.redhat.runtimes.inventory.events;

import static com.redhat.runtimes.inventory.events.Utils.*;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.quarkus.logging.Log;
import io.smallrye.reactive.messaging.annotations.Blocking;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.util.*;
import org.eclipse.microprofile.reactive.messaging.Incoming;

@ApplicationScoped
public class EventConsumer {
  public static final String INGRESS_CHANNEL = "ingress";
  public static final String EGG_CHANNEL = "egg";
  public static final String REJECTED_COUNTER_NAME = "input.rejected";
  public static final String PROCESSING_ERROR_COUNTER_NAME = "input.processing.error";
  public static final String PROCESSING_EXCEPTION_COUNTER_NAME = "input.processing.exception";
  public static final String DUPLICATE_COUNTER_NAME = "input.duplicate";
  public static final String CONSUMED_TIMER_NAME = "input.consumed";

  static final String VALID_CONTENT_TYPE =
      "application/vnd.redhat.runtimes-java-general.analytics+tgz";

  private static final String EVENT_TYPE_NOT_FOUND_MSG =
      "No event type found for [bundleName=%s, applicationName=%s, eventTypeName=%s]";

  @Inject MeterRegistry registry;

  @Inject EventPersistence persistence;

  @Inject ArchiveFetcher archiveFetcher;

  private ArchiveAnnouncementParser jsonParser = new ArchiveAnnouncementParser();

  private Counter rejectedCounter;
  private Counter processingErrorCounter;
  private Counter duplicateCounter;
  private Counter processingExceptionCounter;

  private Clock clock = Clock.systemDefaultZone();

  @PostConstruct
  public void init() {
    rejectedCounter = registry.counter(REJECTED_COUNTER_NAME);
    processingErrorCounter = registry.counter(PROCESSING_ERROR_COUNTER_NAME);
    processingExceptionCounter = registry.counter(PROCESSING_EXCEPTION_COUNTER_NAME);
    duplicateCounter = registry.counter(DUPLICATE_COUNTER_NAME);
    new ProcessorMetrics().bindTo(registry);
    new JvmMemoryMetrics().bindTo(registry);
  }

  @Incoming(INGRESS_CHANNEL)
  @Blocking
  public void processMainFlow(String payload) {
    // This timer will have dynamic tag values based on the action parsed from the received message.
    Timer.Sample consumedTimer = Timer.start(registry);
    try {
      Log.debugf("Processing received Kafka message %s", payload);

      // Parse JSON using Jackson
      var announce = jsonParser.fromJsonString(payload);
      if (announce.getContentType().equals(VALID_CONTENT_TYPE)) {

        // Get data back from S3
        Log.infof("Processed message URL: %s", announce.getUrl());
        var archiveJson = archiveFetcher.getJsonFromS3(announce.getUrl());
        Log.debugf("Retrieved from S3: %s", archiveJson);
        if (shouldProcessMessage(archiveJson, clock, false)) {
          persistence.processMessage(announce, archiveJson);
        }
      }

    } catch (Throwable t) {
      processingExceptionCounter.increment();
      Log.errorf(t, "Could not process the payload");
      Log.debugf(t, "payload: %s", payload);
    } finally {
      // FIXME Might need tags
      consumedTimer.stop(registry.timer(CONSUMED_TIMER_NAME));
    }
  }

  @Incoming(EGG_CHANNEL)
  @Blocking
  public void processEggFlow(String payload) {
    // This timer will have dynamic tag values based on the action parsed from the received message.
    Timer.Sample consumedTimer = Timer.start(registry);

    try {
      Log.debugf("Processing received Kafka message from egg %s", payload);

      // Parse JSON using Jackson
      var announce = jsonParser.fromJsonString(payload);
      if (VALID_CONTENT_TYPE.equals(announce.getContentType()) || announce.isRuntimes()) {
        var url = announce.getUrl();
        if (url != null) {
          // Get data back from S3
          Log.infof("Processed message URL: %s", url);
          var jsonFiles = archiveFetcher.getJsonsFromArchiveStream(announce.getUrl());
          Log.debugf("Found [%s] files in the S3 archive.", jsonFiles.size());
          for (String json : jsonFiles) {
            if (shouldProcessMessage(json, clock, true)) {
              persistence.processMessage(announce, json);
            }
          }
        }
      }
    } catch (Throwable t) {
      processingExceptionCounter.increment();
      Log.errorf(t, "Could not process the egg payload.");
      Log.debugf(t, "payload: %s", payload);
    } finally {
      // FIXME Might need tags
      consumedTimer.stop(registry.timer(CONSUMED_TIMER_NAME));
    }
  }

  void setClock(Clock clock) {
    this.clock = clock;
  }
}
