package io.endee.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.endee.client.support.CollectionFixtures;
import io.endee.client.support.CollectionFixtures.NamedCollection;
import io.endee.client.support.FieldConfigs;
import io.endee.client.support.TestConfig;
import io.endee.client.support.VectorGenerators;
import io.endee.client.types.ObjectItem;
import io.endee.client.types.SearchHit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// Covers Collection.upsert() and Collection.deleteObject().
class ObjectOperationsTest {

  private Endee client;
  private NamedCollection empty;

  @BeforeEach
  void setUp() {
    client = TestConfig.client();
    empty = CollectionFixtures.emptyDense();
  }

  @AfterEach
  void tearDown() {
    CollectionFixtures.safeDelete(client, empty.name());
  }

  private static int upserted(Map<String, Object> result) {
    return ((Number) result.get("upserted")).intValue();
  }

  // -- upsert -------------------------------------------------------------------

  @Test
  void upsertSingleObject() {
    Collection collection = empty.collection();
    ObjectItem item =
        ObjectItem.builder("v1").vector(FieldConfigs.DENSE_FIELD, VectorGenerators.denseVec(0)).build();
    Map<String, Object> result = collection.upsert(List.of(item));
    assertEquals(1, upserted(result));
  }

  @Test
  void upsertBatch10Objects() {
    Collection collection = empty.collection();
    List<ObjectItem> batch = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      batch.add(
          ObjectItem.builder("b_" + i)
              .vector(FieldConfigs.DENSE_FIELD, VectorGenerators.denseVec(i))
              .build());
    }
    Map<String, Object> result = collection.upsert(batch);
    assertEquals(10, upserted(result));
  }

  @Test
  void upsertBatch1000Objects() {
    Collection collection = empty.collection();
    List<ObjectItem> batch = new ArrayList<>();
    for (int i = 0; i < 1000; i++) {
      batch.add(
          ObjectItem.builder("big_" + i)
              .vector(FieldConfigs.DENSE_FIELD, VectorGenerators.denseVec(i))
              .build());
    }
    Map<String, Object> result = collection.upsert(batch);
    assertEquals(1000, upserted(result));
  }

  @Test
  void upsertCountReturned() {
    Collection collection = empty.collection();
    List<ObjectItem> batch = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      batch.add(
          ObjectItem.builder("cnt_" + i)
              .vector(FieldConfigs.DENSE_FIELD, VectorGenerators.denseVec(i))
              .build());
    }
    Map<String, Object> result = collection.upsert(batch);
    assertEquals(5, upserted(result));
  }

  @Test
  void upsertWithMetaOnly() {
    Collection collection = empty.collection();
    Map<String, Object> meta = Map.of("title", "Hello", "value", 42);
    ObjectItem item =
        ObjectItem.builder("meta_only")
            .meta(meta)
            .vector(FieldConfigs.DENSE_FIELD, VectorGenerators.denseVec(0))
            .build();
    Map<String, Object> result = collection.upsert(List.of(item));
    assertEquals(1, upserted(result));
  }

  @Test
  void upsertWithFilterOnly() {
    Collection collection = empty.collection();
    Map<String, Object> filter = Map.of("category", "X", "score", 5);
    ObjectItem item =
        ObjectItem.builder("filt_only")
            .filter(filter)
            .vector(FieldConfigs.DENSE_FIELD, VectorGenerators.denseVec(0))
            .build();
    Map<String, Object> result = collection.upsert(List.of(item));
    assertEquals(1, upserted(result));
  }

  @Test
  void upsertWithMetaAndFilter() {
    Collection collection = empty.collection();
    ObjectItem item =
        ObjectItem.builder("full")
            .meta(Map.of("text", "doc"))
            .filter(Map.of("category", "A", "score", 10, "tags", "important"))
            .vector(FieldConfigs.DENSE_FIELD, VectorGenerators.denseVec(0))
            .build();
    Map<String, Object> result = collection.upsert(List.of(item));
    assertEquals(1, upserted(result));
  }

  @Test
  void upsertWithoutMetaOrFilter() {
    Collection collection = empty.collection();
    ObjectItem item =
        ObjectItem.builder("bare").vector(FieldConfigs.DENSE_FIELD, VectorGenerators.denseVec(0)).build();
    Map<String, Object> result = collection.upsert(List.of(item));
    assertEquals(1, upserted(result));
  }

  @Test
  void upsertOverwritesExistingId() {
    Collection collection = empty.collection();
    double[] v = VectorGenerators.denseVec(0);
    collection.upsert(
        List.of(ObjectItem.builder("dup").meta(Map.of("v", 1)).vector(FieldConfigs.DENSE_FIELD, v).build()));
    Map<String, Object> result =
        collection.upsert(
            List.of(
                ObjectItem.builder("dup").meta(Map.of("v", 2)).vector(FieldConfigs.DENSE_FIELD, v).build()));
    assertEquals(1, upserted(result));
  }

  @Test
  void upsertAllPrecisionCollections() {
    for (String precision : FieldConfigs.ALL_PRECISIONS) {
      String name = CollectionFixtures.uid("prec");
      try {
        client.createCollection(
            name, List.of(FieldConfigs.denseField(VectorGenerators.DIM, "cosine", precision)));
        Collection collection = client.getCollection(name);
        Map<String, Object> result =
            collection.upsert(
                List.of(
                    ObjectItem.builder("v1")
                        .vector(FieldConfigs.DENSE_FIELD, VectorGenerators.denseVec(0))
                        .build()));
        assertTrue(result.containsKey("upserted"), "Failed for precision " + precision);
      } finally {
        CollectionFixtures.safeDelete(client, name);
      }
    }
  }

  @Test
  void upsertAllSpaceTypeCollections() {
    for (String spaceType : FieldConfigs.ALL_SPACE_TYPES) {
      String name = CollectionFixtures.uid("st");
      try {
        client.createCollection(
            name, List.of(FieldConfigs.denseField(VectorGenerators.DIM, spaceType, "int8")));
        Collection collection = client.getCollection(name);
        Map<String, Object> result =
            collection.upsert(
                List.of(
                    ObjectItem.builder("v1")
                        .vector(FieldConfigs.DENSE_FIELD, VectorGenerators.denseVec(0))
                        .build()));
        assertTrue(result.containsKey("upserted"), "Failed for space_type " + spaceType);
      } finally {
        CollectionFixtures.safeDelete(client, name);
      }
    }
  }

  @Test
  void upsertBinaryPrecisionWithBinaryVec() {
    String name = CollectionFixtures.uid("bin");
    try {
      client.createCollection(
          name, List.of(FieldConfigs.denseField(VectorGenerators.DIM, "cosine", "binary")));
      Collection collection = client.getCollection(name);
      List<ObjectItem> batch = new ArrayList<>();
      for (int i = 0; i < 10; i++) {
        batch.add(
            ObjectItem.builder("bv_" + i)
                .vector(FieldConfigs.DENSE_FIELD, VectorGenerators.binaryVec(VectorGenerators.DIM, i))
                .build());
      }
      Map<String, Object> result = collection.upsert(batch);
      assertTrue(result.containsKey("upserted"));
      assertEquals(10, upserted(result));
    } finally {
      CollectionFixtures.safeDelete(client, name);
    }
  }

  @Test
  void upsertEmptyBatchRejectedClientSide() {
    // Collection.upsert() rejects an empty list with IllegalArgumentException.
    Collection collection = empty.collection();
    assertThrows(IllegalArgumentException.class, () -> collection.upsert(List.of()));
  }

  @Test
  void upsertEmptyMetaAccepted() {
    Collection collection = empty.collection();
    ObjectItem item =
        ObjectItem.builder("empty_meta")
            .meta(Map.of())
            .vector(FieldConfigs.DENSE_FIELD, VectorGenerators.denseVec(0))
            .build();
    Map<String, Object> result = collection.upsert(List.of(item));
    assertEquals(1, upserted(result));
  }

  @Test
  void upsertUnicodeInMetaRoundTrips() {
    Collection collection = empty.collection();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("hindi", "नमस्ते");
    payload.put("kannada", "ನಮಸ್ಕಾರ");
    payload.put("emoji", "🚀");

    double[] vec = VectorGenerators.denseVec(1);
    collection.upsert(
        List.of(ObjectItem.builder("uni").meta(payload).vector(FieldConfigs.DENSE_FIELD, vec).build()));

    Map<String, Map<String, Object>> queryFields = new LinkedHashMap<>();
    queryFields.put(FieldConfigs.DENSE_FIELD, Map.of("query", vec, "limit", 1));
    List<SearchHit> results = collection.search(queryFields).get(FieldConfigs.DENSE_FIELD);

    assertEquals("uni", results.get(0).getId());
    assertEquals("नमस्ते", results.get(0).getMeta().get("hindi"));
    assertEquals("ನಮಸ್ಕಾರ", results.get(0).getMeta().get("kannada"));
    assertEquals("🚀", results.get(0).getMeta().get("emoji"));
  }

  @Test
  void upsertCountForMixedNewAndOverwrite() {
    Collection collection = empty.collection();
    List<ObjectItem> batchA = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      batchA.add(
          ObjectItem.builder("obj_" + i)
              .vector(FieldConfigs.DENSE_FIELD, VectorGenerators.denseVec(i))
              .build());
    }
    collection.upsert(batchA);

    List<ObjectItem> batchB = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      batchB.add(
          ObjectItem.builder("obj_" + i)
              .vector(FieldConfigs.DENSE_FIELD, VectorGenerators.denseVec(i + 100))
              .build());
    }
    for (int i = 0; i < 5; i++) {
      batchB.add(
          ObjectItem.builder("new_" + i)
              .vector(FieldConfigs.DENSE_FIELD, VectorGenerators.denseVec(i + 200))
              .build());
    }
    Map<String, Object> result = collection.upsert(batchB);
    assertEquals(10, upserted(result));
  }

  @Test
  void upsertUpdatedMetaReflectedInSearch() {
    Collection collection = empty.collection();
    double[] vec = VectorGenerators.denseVec(77);
    collection.upsert(
        List.of(
            ObjectItem.builder("meta_rt")
                .meta(Map.of("version", 1))
                .vector(FieldConfigs.DENSE_FIELD, vec)
                .build()));
    collection.upsert(
        List.of(
            ObjectItem.builder("meta_rt")
                .meta(Map.of("version", 2))
                .vector(FieldConfigs.DENSE_FIELD, vec)
                .build()));

    Map<String, Map<String, Object>> queryFields = new LinkedHashMap<>();
    queryFields.put(FieldConfigs.DENSE_FIELD, Map.of("query", vec, "limit", 1));
    List<SearchHit> results = collection.search(queryFields).get(FieldConfigs.DENSE_FIELD);
    assertEquals("meta_rt", results.get(0).getId());
    assertEquals(2, ((Number) results.get(0).getMeta().get("version")).intValue());
  }

  // -- deleteObject ---------------------------------------------------------------

  @Test
  void deleteObjectReturnsResponse() {
    NamedCollection populated = CollectionFixtures.populatedDense();
    try {
      Map<String, Object> result = populated.collection().deleteObject("vec_0040");
      assertEquals("vec_0040", result.get("deleted"));
    } finally {
      CollectionFixtures.safeDelete(client, populated.name());
    }
  }

  @Test
  void deleteObjectRemovedFromSearch() {
    NamedCollection populated = CollectionFixtures.populatedDense();
    try {
      String targetId = "vec_0042";
      double[] queryVec = VectorGenerators.denseVec(42);
      populated.collection().deleteObject(targetId);

      Map<String, Map<String, Object>> queryFields = new LinkedHashMap<>();
      queryFields.put(
          FieldConfigs.DENSE_FIELD,
          Map.of("query", queryVec, "limit", VectorGenerators.N_VECTORS));
      List<SearchHit> results =
          populated.collection().search(queryFields).get(FieldConfigs.DENSE_FIELD);
      boolean stillPresent = results.stream().anyMatch(h -> targetId.equals(h.getId()));
      assertTrue(!stillPresent);
    } finally {
      CollectionFixtures.safeDelete(client, populated.name());
    }
  }

  // -- duplicate ID in batch ------------------------------------------------------

  @Test
  void upsertDuplicateIdsInSameBatchRaises() {
    Collection collection = empty.collection();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            collection.upsert(
                List.of(
                    ObjectItem.builder("same_id")
                        .vector(FieldConfigs.DENSE_FIELD, VectorGenerators.denseVec(0))
                        .build(),
                    ObjectItem.builder("same_id")
                        .vector(FieldConfigs.DENSE_FIELD, VectorGenerators.denseVec(1))
                        .build())));
  }

  // -- NaN / Inf vectors -----------------------------------------------------------

  private static double[] withBadFirstElement(double bad) {
    double[] vec = new double[VectorGenerators.DIM];
    vec[0] = bad;
    for (int i = 1; i < vec.length; i++) {
      vec[i] = 0.5;
    }
    return vec;
  }

  @Test
  void upsertNanInVectorRaises() {
    Collection collection = empty.collection();
    double[] badVec = withBadFirstElement(Double.NaN);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            collection.upsert(
                List.of(ObjectItem.builder("nan_vec").vector(FieldConfigs.DENSE_FIELD, badVec).build())));
  }

  @Test
  void upsertInfInVectorRaises() {
    Collection collection = empty.collection();
    double[] badVec = withBadFirstElement(Double.POSITIVE_INFINITY);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            collection.upsert(
                List.of(ObjectItem.builder("inf_vec").vector(FieldConfigs.DENSE_FIELD, badVec).build())));
  }

  @Test
  void upsertNegInfInVectorRaises() {
    Collection collection = empty.collection();
    double[] badVec = withBadFirstElement(Double.NEGATIVE_INFINITY);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            collection.upsert(
                List.of(ObjectItem.builder("ninf_vec").vector(FieldConfigs.DENSE_FIELD, badVec).build())));
  }

  // -- upsert batch size limit ------------------------------------------------------

  @Test
  void upsertOverBatchLimitRaises() {
    Collection collection = empty.collection();
    int overLimit = 10_000 + 1;
    List<ObjectItem> batch = new ArrayList<>();
    for (int i = 0; i < overLimit; i++) {
      batch.add(
          ObjectItem.builder("x_" + i)
              .vector(FieldConfigs.DENSE_FIELD, VectorGenerators.denseVec(i))
              .build());
    }
    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> collection.upsert(batch));
    assertTrue(ex.getMessage().contains("10000"));
  }

  // -- unknown field name -----------------------------------------------------------

  @Test
  void upsertUnknownFieldNameRaises() {
    Collection collection = empty.collection();
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                collection.upsert(
                    List.of(
                        ObjectItem.builder("unk")
                            .vector("nonexistent_field_xyz", VectorGenerators.denseVec(0))
                            .build())));
    assertTrue(ex.getMessage().contains("Unknown field"));
  }
}
