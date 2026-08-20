package io.endee.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.endee.client.support.CollectionFixtures;
import io.endee.client.support.CollectionFixtures.NamedCollection;
import io.endee.client.support.FieldConfigs;
import io.endee.client.support.ObjectBuilders;
import io.endee.client.support.TestConfig;
import io.endee.client.support.VectorGenerators;
import io.endee.client.types.ObjectItem;
import io.endee.client.types.SearchHit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

// Covers rebuild()/rebuildStatus(): response shape, custom HNSW params, availability during rebuild.
class RebuildTest {

  private Endee client;
  private String collectionName;
  private Collection collection;

  @BeforeEach
  void setUp() {
    client = TestConfig.client();
    NamedCollection nc = CollectionFixtures.populatedDense();
    collectionName = nc.name();
    collection = nc.collection();
  }

  @AfterEach
  void tearDown() {
    CollectionFixtures.safeDelete(client, collectionName);
  }

  // Polls rebuildStatus() until status is "completed" or "idle".
  private static Map<String, Object> waitForRebuild(Collection collection, long timeoutMillis) {
    long deadline = System.currentTimeMillis() + timeoutMillis;
    while (System.currentTimeMillis() < deadline) {
      Map<String, Object> status = collection.rebuildStatus();
      Object s = status.get("status");
      if ("completed".equals(s) || "idle".equals(s)) {
        return status;
      }
      if ("failed".equals(s)) {
        throw new RuntimeException("Rebuild failed: " + status.get("error"));
      }
      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }
    }
    throw new RuntimeException("Rebuild did not complete within " + timeoutMillis + "ms");
  }

  private static Map<String, Object> waitForRebuild(Collection collection) {
    return waitForRebuild(collection, 120_000);
  }

  // -- rebuild - initial response ------------------------------------------------

  @Test
  void rebuildReturnsMap() {
    Map<String, Object> result =
        collection.rebuild(List.of(Map.of("field", FieldConfigs.DENSE_FIELD, "M", 8, "ef_con", 64)));
    assertInstanceOf(Map.class, result);
    waitForRebuild(collection);
  }

  @Test
  void rebuildInitialStatusIsInProgress() {
    Map<String, Object> result =
        collection.rebuild(List.of(Map.of("field", FieldConfigs.DENSE_FIELD, "M", 8, "ef_con", 64)));
    assertEquals("in_progress", result.get("status"), "Expected status='in_progress', got: " + result);
    waitForRebuild(collection);
  }

  @Test
  void rebuildResponseHasTotalObjects() {
    Map<String, Object> result =
        collection.rebuild(List.of(Map.of("field", FieldConfigs.DENSE_FIELD, "M", 8, "ef_con", 64)));
    assertTrue(result.containsKey("total_objects"), "Missing 'total_objects' in: " + result);
    assertInstanceOf(Number.class, result.get("total_objects"));
    waitForRebuild(collection);
  }

  @Test
  void rebuildResponseHasNewConfig() {
    Map<String, Object> result =
        collection.rebuild(List.of(Map.of("field", FieldConfigs.DENSE_FIELD, "M", 8, "ef_con", 64)));
    assertTrue(result.containsKey("new_config"), "Missing 'new_config' in: " + result);
    assertInstanceOf(Map.class, result.get("new_config"));
    waitForRebuild(collection);
  }

  @Test
  void rebuildResponseHasPreviousConfig() {
    Map<String, Object> result =
        collection.rebuild(List.of(Map.of("field", FieldConfigs.DENSE_FIELD, "M", 8, "ef_con", 64)));
    assertTrue(result.containsKey("previous_config"), "Missing 'previous_config' in: " + result);
    waitForRebuild(collection);
  }

  // -- rebuild - custom HNSW parameters -----------------------------------------

  @Test
  @SuppressWarnings("unchecked")
  void rebuildWithSameConfig() {
    Map<String, Object> current =
        (Map<String, Object>) collection.rebuildStatus().getOrDefault("current_config", Map.of());
    int m = current.get("M") instanceof Number n ? n.intValue() : 16;
    int efCon = current.get("ef_con") instanceof Number n ? n.intValue() : 100;
    Map<String, Object> result =
        collection.rebuild(List.of(Map.of("field", FieldConfigs.DENSE_FIELD, "M", m, "ef_con", efCon)));
    assertInstanceOf(Map.class, result);
    assertEquals("in_progress", result.get("status"));
    waitForRebuild(collection);
  }

  @Test
  void rebuildWithCustomM() {
    Map<String, Object> result =
        collection.rebuild(List.of(Map.of("field", FieldConfigs.DENSE_FIELD, "M", 8)));
    assertInstanceOf(Map.class, result);
    waitForRebuild(collection);
  }

  @Test
  @SuppressWarnings("unchecked")
  void rebuildCustomMReflectedInNewConfig() {
    Map<String, Object> result =
        collection.rebuild(List.of(Map.of("field", FieldConfigs.DENSE_FIELD, "M", 8)));
    Map<String, Object> newConfig = (Map<String, Object>) result.getOrDefault("new_config", Map.of());
    Object m = newConfig.get("M");
    assertTrue(m instanceof Number && ((Number) m).intValue() == 8, "Expected M=8 in new_config: " + newConfig);
    waitForRebuild(collection);
  }

  @Test
  void rebuildWithCustomEfCon() {
    Map<String, Object> result =
        collection.rebuild(List.of(Map.of("field", FieldConfigs.DENSE_FIELD, "ef_con", 64)));
    assertInstanceOf(Map.class, result);
    waitForRebuild(collection);
  }

  @Test
  void rebuildWithBothHnswParams() {
    Map<String, Object> result =
        collection.rebuild(List.of(Map.of("field", FieldConfigs.DENSE_FIELD, "M", 8, "ef_con", 64)));
    assertInstanceOf(Map.class, result);
    waitForRebuild(collection);
  }

  @ParameterizedTest
  @CsvSource({"4,32", "8,64", "32,256"})
  void rebuildVariousHnswParamsAccepted(int m, int efCon) {
    Map<String, Object> result =
        collection.rebuild(List.of(Map.of("field", FieldConfigs.DENSE_FIELD, "M", m, "ef_con", efCon)));
    assertInstanceOf(Map.class, result);
    waitForRebuild(collection);
  }

  // -- rebuild - collection still usable during/after rebuild -------------------

  @Test
  void rebuildCollectionStillSearchableDuring() {
    collection.rebuild(List.of(Map.of("field", FieldConfigs.DENSE_FIELD, "M", 8, "ef_con", 64)));
    List<SearchHit> results =
        collection
            .search(Map.of(FieldConfigs.DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", 5)))
            .get(FieldConfigs.DENSE_FIELD);
    assertTrue(results.size() > 0);
    waitForRebuild(collection);
  }

  @Test
  void rebuildCollectionSearchableAfterCompletion() {
    collection.rebuild(List.of(Map.of("field", FieldConfigs.DENSE_FIELD, "M", 8, "ef_con", 64)));
    waitForRebuild(collection);
    List<SearchHit> results =
        collection
            .search(Map.of(FieldConfigs.DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", 5)))
            .get(FieldConfigs.DENSE_FIELD);
    assertTrue(results.size() > 0);
  }

  // -- rebuildStatus - response shape ------------------------------------------

  @Test
  void rebuildStatusReturnsMap() {
    Map<String, Object> result = collection.rebuildStatus();
    assertInstanceOf(Map.class, result);
  }

  @Test
  void rebuildStatusHasStatusKey() {
    Map<String, Object> result = collection.rebuildStatus();
    assertTrue(result.containsKey("status"), "Missing 'status' key in: " + result);
  }

  @Test
  void rebuildStatusValidValues() {
    Map<String, Object> result = collection.rebuildStatus();
    Object status = result.get("status");
    assertTrue(
        List.of("idle", "in_progress", "completed", "failed").contains(status),
        "Unexpected status value: " + status);
  }

  @Test
  void rebuildStatusOnEmptyCollectionIsIdle() {
    NamedCollection nc = CollectionFixtures.emptyDense();
    try {
      Map<String, Object> result = nc.collection().rebuildStatus();
      Object status = result.get("status");
      assertTrue(
          "idle".equals(status) || "completed".equals(status),
          "Expected 'idle' or 'completed' on fresh collection, got: " + result);
    } finally {
      CollectionFixtures.safeDelete(client, nc.name());
    }
  }

  @Test
  void rebuildStatusInProgressAfterTrigger() {
    collection.rebuild(List.of(Map.of("field", FieldConfigs.DENSE_FIELD, "M", 8, "ef_con", 64)));
    Map<String, Object> status = collection.rebuildStatus();
    Object s = status.get("status");
    assertTrue(
        "in_progress".equals(s) || "completed".equals(s),
        "Unexpected status right after rebuild(): " + status);
    waitForRebuild(collection);
  }

  @Test
  void rebuildStatusHasObjectsProcessed() {
    collection.rebuild(List.of(Map.of("field", FieldConfigs.DENSE_FIELD, "M", 8, "ef_con", 64)));
    Map<String, Object> status = collection.rebuildStatus();
    assertTrue(status.containsKey("objects_processed"), "Missing 'objects_processed' in: " + status);
    assertInstanceOf(Number.class, status.get("objects_processed"));
    waitForRebuild(collection);
  }

  @Test
  void rebuildStatusHasTotalObjects() {
    collection.rebuild(List.of(Map.of("field", FieldConfigs.DENSE_FIELD, "M", 8, "ef_con", 64)));
    Map<String, Object> status = collection.rebuildStatus();
    assertTrue(status.containsKey("total_objects"), "Missing 'total_objects' in: " + status);
    assertInstanceOf(Number.class, status.get("total_objects"));
    waitForRebuild(collection);
  }

  @Test
  void rebuildStatusHasPercentComplete() {
    collection.rebuild(List.of(Map.of("field", FieldConfigs.DENSE_FIELD, "M", 8, "ef_con", 64)));
    Map<String, Object> status = collection.rebuildStatus();
    assertTrue(status.containsKey("percent_complete"), "Missing 'percent_complete' in: " + status);
    waitForRebuild(collection);
  }

  @Test
  void rebuildStatusCompletedHasStartedAt() {
    collection.rebuild(List.of(Map.of("field", FieldConfigs.DENSE_FIELD, "M", 8, "ef_con", 64)));
    Map<String, Object> status = waitForRebuild(collection);
    assertTrue(status.containsKey("started_at"), "Missing 'started_at' in completed status: " + status);
  }

  @Test
  void rebuildStatusCompletedHasCompletedAt() {
    collection.rebuild(List.of(Map.of("field", FieldConfigs.DENSE_FIELD, "M", 8, "ef_con", 64)));
    Map<String, Object> status = waitForRebuild(collection);
    assertTrue(status.containsKey("completed_at"), "Missing 'completed_at' in completed status: " + status);
  }

  @Test
  void rebuildStatusCompletedPercentIs100() {
    collection.rebuild(List.of(Map.of("field", FieldConfigs.DENSE_FIELD, "M", 8, "ef_con", 64)));
    Map<String, Object> status = waitForRebuild(collection);
    Object percent = status.get("percent_complete");
    assertTrue(
        percent instanceof Number && ((Number) percent).intValue() == 100,
        "Expected percent_complete == 100, got: " + status);
  }

  @Test
  void rebuildStatusCompletedObjectsMatch() {
    collection.rebuild(List.of(Map.of("field", FieldConfigs.DENSE_FIELD, "M", 8, "ef_con", 64)));
    Map<String, Object> status = waitForRebuild(collection);
    assertEquals(
        status.get("total_objects"),
        status.get("objects_processed"),
        "objects_processed "
            + status.get("objects_processed")
            + " != total_objects "
            + status.get("total_objects"));
  }

  // -- rebuild - multi_vector field ----------------------------------------------

  @Test
  void rebuildMultiVectorField() {
    String name = CollectionFixtures.uid("rbd_mv");
    try {
      client.createCollection(name, List.of(FieldConfigs.mvField()));
      Collection mvCollection = client.getCollection(name);
      List<ObjectItem> items = new ArrayList<>();
      for (int i = 0; i < 10; i++) {
        items.add(ObjectBuilders.mvItem(i));
      }
      mvCollection.upsert(items);

      Map<String, Object> result =
          mvCollection.rebuild(List.of(Map.of("field", FieldConfigs.MV_FIELD, "M", 8, "ef_con", 64)));
      assertInstanceOf(Map.class, result);
      assertEquals("in_progress", result.get("status"));
      waitForRebuild(mvCollection);
    } finally {
      CollectionFixtures.safeDelete(client, name);
    }
  }

  @Test
  void rebuildMultiVectorCompletesSuccessfully() {
    String name = CollectionFixtures.uid("rbd_mv2");
    try {
      client.createCollection(name, List.of(FieldConfigs.mvField()));
      Collection mvCollection = client.getCollection(name);
      List<ObjectItem> items = new ArrayList<>();
      for (int i = 0; i < 20; i++) {
        items.add(ObjectBuilders.mvItem(i));
      }
      mvCollection.upsert(items);

      mvCollection.rebuild(List.of(Map.of("field", FieldConfigs.MV_FIELD, "M", 8, "ef_con", 64)));
      Map<String, Object> status = waitForRebuild(mvCollection);
      assertTrue(List.of("completed", "idle").contains(status.get("status")));
    } finally {
      CollectionFixtures.safeDelete(client, name);
    }
  }

  @Test
  void rebuildMultiVectorSearchableAfterCompletion() {
    String name = CollectionFixtures.uid("rbd_mv3");
    try {
      client.createCollection(name, List.of(FieldConfigs.mvField()));
      Collection mvCollection = client.getCollection(name);
      List<ObjectItem> items = new ArrayList<>();
      for (int i = 0; i < 20; i++) {
        items.add(ObjectBuilders.mvItem(i));
      }
      mvCollection.upsert(items);

      mvCollection.rebuild(List.of(Map.of("field", FieldConfigs.MV_FIELD, "M", 8, "ef_con", 64)));
      waitForRebuild(mvCollection, 300_000);

      List<SearchHit> results =
          mvCollection
              .search(
                  Map.of(
                      FieldConfigs.MV_FIELD,
                      Map.of("query", VectorGenerators.multiVec(0), "limit", 5)))
              .get(FieldConfigs.MV_FIELD);
      assertTrue(results.size() > 0);
    } finally {
      CollectionFixtures.safeDelete(client, name);
    }
  }
}
