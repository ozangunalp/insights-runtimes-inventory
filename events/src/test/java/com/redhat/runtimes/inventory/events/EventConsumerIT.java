/* Copyright (C) Red Hat 2023-2024 */
package com.redhat.runtimes.inventory.events;

import static com.redhat.runtimes.inventory.events.EventConsumer.CONSUMED_TIMER_NAME;
import static com.redhat.runtimes.inventory.events.EventConsumer.PROCESSING_EXCEPTION_COUNTER_NAME;
import static com.redhat.runtimes.inventory.events.TestUtils.inputStreamFromResources;
import static com.redhat.runtimes.inventory.events.TestUtils.readBytesFromResources;
import static com.redhat.runtimes.inventory.events.TestUtils.readFromResources;
import static com.redhat.runtimes.inventory.events.Utils.eapInstanceOf;
import static com.redhat.runtimes.inventory.events.Utils.instanceOf;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redhat.runtimes.inventory.models.EapInstance;
import com.redhat.runtimes.inventory.models.InsightsMessage;
import com.redhat.runtimes.inventory.models.JvmInstance;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kafka.InjectKafkaCompanion;
import io.quarkus.test.kafka.KafkaCompanionResource;
import io.smallrye.reactive.messaging.kafka.companion.KafkaCompanion;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(KafkaCompanionResource.class)
public class EventConsumerIT {

  @Inject EntityManager entityManager;

  @InjectKafkaCompanion KafkaCompanion companion;
  @Inject MicrometerAssertionHelper micrometerAssertionHelper;

  @Inject ArchiveFetcher archiveFetcher;

  @Inject EventConsumer eventConsumer;

  private static final String fixedDate = "2023-04-01T01:00:00Z";
  private static final String eggTopic = "platform.inventory.events";
  private static final String ingressTopic = "platform.upload.announce";

  @BeforeEach
  void beforeEach() {
    TestUtils.clearTables(entityManager);
    micrometerAssertionHelper.saveCounterValuesBeforeTest(PROCESSING_EXCEPTION_COUNTER_NAME);
    micrometerAssertionHelper.removeDynamicTimer(CONSUMED_TIMER_NAME);
    eventConsumer.setClock(Clock.fixed(Instant.parse(fixedDate), ZoneId.systemDefault()));
  }

  @AfterEach
  void clear() {
    micrometerAssertionHelper.clearSavedValues();
    micrometerAssertionHelper.removeDynamicTimer(CONSUMED_TIMER_NAME);
  }

  @Test
  @SuppressWarnings("unchecked")
  // This complains about not being able to find the egg channel
  // Which is odd because it works in Prod
  void testValidEggPayload() throws IOException, InterruptedException {
    HttpClient mockClient = mock(HttpClient.class);
    HttpResponse<InputStream> mockResponse = mock(HttpResponse.class);
    InputStream archive = inputStreamFromResources("egg_upload.tar.gz");
    when(mockClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(mockResponse);
    when(mockResponse.body()).thenReturn(archive);

    archiveFetcher.setHttpClient(mockClient);

    String kafkaMessage = readFromResources("egg_is_runtimes.json");
    companion
        .produceStrings()
        .fromRecords(new ProducerRecord<>(eggTopic, kafkaMessage))
        .awaitCompletion();

    micrometerAssertionHelper.awaitAndAssertTimerIncrement(CONSUMED_TIMER_NAME, 1);
    micrometerAssertionHelper.assertCounterIncrement(PROCESSING_EXCEPTION_COUNTER_NAME, 0);

    TestUtils.await_entity_count(entityManager, "JvmInstance", 1L);
  }

  @Test
  @SuppressWarnings("unchecked")
  void testValidJvmInstancePayload() throws IOException, InterruptedException {
    HttpClient mockClient = mock(HttpClient.class);
    HttpResponse<byte[]> mockResponse = mock(HttpResponse.class);
    byte[] buffy = readBytesFromResources("jdk8_MWTELE-66.gz");
    when(mockClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(mockResponse);
    when(mockResponse.body()).thenReturn(buffy);

    archiveFetcher.setHttpClient(mockClient);
    String kafkaMessage = readFromResources("incoming_kafka1.json");
    companion
        .produceStrings()
        .fromRecords(new ProducerRecord<>(ingressTopic, kafkaMessage))
        .awaitCompletion();

    micrometerAssertionHelper.awaitAndAssertTimerIncrement(CONSUMED_TIMER_NAME, 1);
    micrometerAssertionHelper.assertCounterIncrement(PROCESSING_EXCEPTION_COUNTER_NAME, 0);

    TestUtils.await_entity_count(entityManager, "JvmInstance", 1L);
  }

  @Test
  void testInvalidPayload() {
    companion
        .produceStrings()
        .fromRecords(new ProducerRecord<>(ingressTopic, "not a real payload"))
        .awaitCompletion();
    micrometerAssertionHelper.awaitAndAssertTimerIncrement(CONSUMED_TIMER_NAME, 1);
    micrometerAssertionHelper.assertCounterIncrement(PROCESSING_EXCEPTION_COUNTER_NAME, 1);
  }

  @Test
  @Transactional
  void testJvmInstanceBasicPostgresTransactions() throws IOException {
    ArchiveAnnouncement dummy = new ArchiveAnnouncement();
    dummy.setAccountId("dummy account id");
    dummy.setOrgId("dummy org");
    dummy.setTimestamp(Instant.now());

    byte[] buffy = readBytesFromResources("jdk8_MWTELE-66.gz");
    String json = ArchiveFetcher.unzipJson(buffy);

    InsightsMessage inst = instanceOf(dummy, json);
    assertTrue(inst instanceof JvmInstance);

    assertEquals(0L, TestUtils.entity_count(entityManager, "JvmInstance"));
    assertEquals(0L, TestUtils.entity_count(entityManager, "JarHash"));

    entityManager.persist(inst);
    assertEquals(1L, TestUtils.entity_count(entityManager, "JvmInstance"));
    assertEquals(1074L, TestUtils.entity_count(entityManager, "JarHash"));
    assertEquals(1L, TestUtils.table_count(entityManager, "jvm_instance_jar_hash"));

    entityManager.remove(inst);
    assertEquals(0L, TestUtils.entity_count(entityManager, "JvmInstance"));
    // TODO: This won't pass right now because orphans still need to be handled
    // assertEquals(0L, TestUtils.entity_count(entityManager, "JarHash"));
    assertEquals(0L, TestUtils.table_count(entityManager, "jvm_instance_jar_hash"));
  }

  @Test
  @Transactional
  void testEapInstanceBasicPostgresTransactions() throws IOException {
    ArchiveAnnouncement dummy = new ArchiveAnnouncement();
    dummy.setAccountId("dummy account id");
    dummy.setOrgId("dummy org");
    dummy.setTimestamp(Instant.now());

    String json = readFromResources("eap_example1.json");

    InsightsMessage msg = eapInstanceOf(dummy, json);
    assertTrue(msg instanceof EapInstance);
    EapInstance inst = (EapInstance) msg;

    /*******************
     *  Pre-persist checks
     *******************/
    assertEquals(0L, TestUtils.entity_count(entityManager, "EapInstance"));
    assertEquals(0L, TestUtils.entity_count(entityManager, "JarHash"));
    assertEquals(0L, TestUtils.entity_count(entityManager, "EapConfiguration"));
    assertEquals(0L, TestUtils.entity_count(entityManager, "EapDeployment"));
    assertEquals(0L, TestUtils.entity_count(entityManager, "EapExtension"));

    /*******************
     *  Persist and check counts
     *******************/
    entityManager.persist(inst);
    assertEquals(1L, TestUtils.entity_count(entityManager, "EapInstance"));
    assertEquals(1L, TestUtils.entity_count(entityManager, "EapConfiguration"));
    assertEquals(2L, TestUtils.table_count(entityManager, "eap_configuration_deployments"));
    assertEquals(40L, TestUtils.table_count(entityManager, "eap_configuration_subsystems"));
    assertEquals(2L, TestUtils.entity_count(entityManager, "EapDeployment"));
    assertEquals(39L, TestUtils.entity_count(entityManager, "EapExtension"));
    assertEquals(41L, TestUtils.table_count(entityManager, "eap_extension_subsystems"));

    // Jars are stored in multiple places, so lots of JarHashes
    assertEquals(3561L, TestUtils.entity_count(entityManager, "JarHash"));
    assertEquals(1L, TestUtils.table_count(entityManager, "jvm_instance_jar_hash"));
    assertEquals(3555L, TestUtils.table_count(entityManager, "eap_instance_module_jar_hash"));
    assertEquals(5L, TestUtils.table_count(entityManager, "eap_deployment_archive_jar_hash"));

    // The JarHash entities should equal the totals in these tables
    assertEquals(
        TestUtils.entity_count(entityManager, "JarHash"),
        TestUtils.table_count(entityManager, "jvm_instance_jar_hash")
            + TestUtils.table_count(entityManager, "eap_instance_module_jar_hash")
            + TestUtils.table_count(entityManager, "eap_deployment_archive_jar_hash"));

    /*******************
     *  Instance removal checks
     *******************/
    entityManager.remove(inst);
    assertEquals(0L, TestUtils.entity_count(entityManager, "EapInstance"));
    assertEquals(0L, TestUtils.entity_count(entityManager, "EapConfiguration"));
    assertEquals(0L, TestUtils.table_count(entityManager, "eap_configuration_deployments"));
    assertEquals(0L, TestUtils.table_count(entityManager, "eap_configuration_subsystems"));
    assertEquals(0L, TestUtils.entity_count(entityManager, "EapDeployment"));

    // TODO: These won't pass right now because orphans still need to be handled
    // assertEquals(0L, TestUtils.entity_count(entityManager, "EapExtension"));
    // assertEquals(0L, TestUtils.table_count(entityManager, "eap_extension_subsystems"));
    // assertEquals(0L, TestUtils.entity_count(entityManager, "JarHash"));
    assertEquals(0L, TestUtils.table_count(entityManager, "jvm_instance_jar_hash"));
    assertEquals(0L, TestUtils.table_count(entityManager, "eap_instance_module_jar_hash"));
    assertEquals(0L, TestUtils.table_count(entityManager, "eap_deployment_archive_jar_hash"));
  }

  // We saw hibernate exceptions causing issues with messages being received
  // Let's try to make sure that doesn't happen.
  @Test
  @SuppressWarnings("unchecked")
  void testHibernateExceptionCausesHangs() throws IOException, InterruptedException {
    HttpClient mockClient = mock(HttpClient.class);
    HttpResponse<byte[]> mockResponse = mock(HttpResponse.class);
    byte[] buffy = readBytesFromResources("jdk8_MWTELE-66.gz");
    when(mockClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(mockResponse);
    when(mockResponse.body()).thenReturn(buffy);

    archiveFetcher.setHttpClient(mockClient);
    String kafkaFirst = readFromResources("incoming_kafka1.json");
    String kafkaSecond = "";
    String kafkaThird = "";
    var mapper = new ObjectMapper();
    try {
      TypeReference<Map<String, Object>> typeRef = new TypeReference<>() {};
      var o = mapper.readValue(kafkaFirst, typeRef);
      String request_id = String.valueOf(o.get("request_id"));
      o.put("request_id", request_id + "2");
      kafkaSecond = mapper.writeValueAsString(o);
      o.put("request_id", request_id + "3");
      kafkaThird = mapper.writeValueAsString(o);
    } catch (JsonProcessingException e) {
      throw new RuntimeException("Error in unmarshalling JSON", e);
    }
    // First submit a good object
    companion
        .produceStrings()
        .fromRecords(new ProducerRecord<>(ingressTopic, kafkaFirst))
        .awaitCompletion();

    micrometerAssertionHelper.awaitAndAssertTimerIncrement(CONSUMED_TIMER_NAME, 1);
    micrometerAssertionHelper.assertCounterIncrement(PROCESSING_EXCEPTION_COUNTER_NAME, 0);
    TestUtils.await_entity_count(entityManager, "JvmInstance", 1L);

    // This should error because of a duplicate object
    companion
        .produceStrings()
        .fromRecords(new ProducerRecord<>(ingressTopic, kafkaSecond))
        .awaitCompletion();

    micrometerAssertionHelper.awaitAndAssertTimerIncrement(CONSUMED_TIMER_NAME, 2);
    micrometerAssertionHelper.assertCounterIncrement(PROCESSING_EXCEPTION_COUNTER_NAME, 1);
    TestUtils.await_entity_count(entityManager, "JvmInstance", 1L);

    // Now we submit a new object and see that it persists
    buffy = readBytesFromResources("eap_example1.json.gz");
    when(mockResponse.body()).thenReturn(buffy);
    companion
        .produceStrings()
        .fromRecords(new ProducerRecord<>(ingressTopic, kafkaSecond))
        .awaitCompletion();

    micrometerAssertionHelper.awaitAndAssertTimerIncrement(CONSUMED_TIMER_NAME, 3);
    micrometerAssertionHelper.assertCounterIncrement(PROCESSING_EXCEPTION_COUNTER_NAME, 1);
    TestUtils.await_entity_count(entityManager, "JvmInstance", 2L);
  }
}
