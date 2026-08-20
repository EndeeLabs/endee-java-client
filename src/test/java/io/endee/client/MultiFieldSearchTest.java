package io.endee.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.endee.client.support.CollectionFixtures;
import io.endee.client.support.CollectionFixtures.NamedCollection;
import io.endee.client.support.FieldConfigs;
import io.endee.client.support.TestConfig;
import io.endee.client.support.VectorGenerators;
import io.endee.client.types.ObjectItem;
import io.endee.client.types.SearchHit;
import io.endee.client.types.SparseData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// Covers multi-field search and RRF reranking via Reranker: field weights, rrfK, limits, filters.
class MultiFieldSearchTest {

  private final Endee client = TestConfig.client();
  private NamedCollection nc;

  @AfterEach
  void tearDown() {
    if (nc != null) {
      CollectionFixtures.safeDelete(client, nc.name());
      nc = null;
    }
  }

  private static Map<String, Object> fieldQuery(Object query, int limit) {
    Map<String, Object> inner = new LinkedHashMap<>();
    inner.put("query", query);
    inner.put("limit", limit);
    return inner;
  }

  private Map<String, Map<String, Object>> denseSparseFields(long seed, int limit) {
    SparseData sd = VectorGenerators.sparseVec(seed);
    Map<String, Map<String, Object>> fields = new LinkedHashMap<>();
    fields.put(
        FieldConfigs.DENSE_FIELD,
        fieldQuery(VectorGenerators.denseVec(VectorGenerators.HYBRID_DIM, seed), limit));
    fields.put(FieldConfigs.SPARSE_FIELD, fieldQuery(sd, limit));
    return fields;
  }

  // -- multi-field search WITHOUT reranker (per-field format) --------------------

  @Test
  void testMultiFieldNoRerankerReturnsMapWithBothFields() {
    nc = CollectionFixtures.populatedHybrid();
    Map<String, List<SearchHit>> results = nc.collection().search(denseSparseFields(0, 5));
    assertNotNull(results);
    assertTrue(results.containsKey(FieldConfigs.DENSE_FIELD));
    assertTrue(results.containsKey(FieldConfigs.SPARSE_FIELD));
  }

  @Test
  void testMultiFieldNoRerankerKeysAreFieldNames() {
    nc = CollectionFixtures.populatedHybrid();
    Map<String, List<SearchHit>> results = nc.collection().search(denseSparseFields(2, 5));
    assertTrue(results.containsKey(FieldConfigs.DENSE_FIELD), "Missing dense key in per-field results");
    assertTrue(results.containsKey(FieldConfigs.SPARSE_FIELD), "Missing sparse key in per-field results");
  }

  @Test
  void testMultiFieldNoRerankerEachFieldIsList() {
    nc = CollectionFixtures.populatedHybrid();
    Map<String, List<SearchHit>> results = nc.collection().search(denseSparseFields(3, 5));
    assertInstanceOf(List.class, results.get(FieldConfigs.DENSE_FIELD));
    assertInstanceOf(List.class, results.get(FieldConfigs.SPARSE_FIELD));
  }

  @Test
  void testMultiFieldNoRerankerEachHitHasRequiredKeys() {
    nc = CollectionFixtures.populatedHybrid();
    Map<String, List<SearchHit>> results = nc.collection().search(denseSparseFields(4, 3));
    for (Map.Entry<String, List<SearchHit>> e : results.entrySet()) {
      for (SearchHit hit : e.getValue()) {
        assertNotNull(hit.getId(), "Missing id in " + e.getKey() + " hit");
      }
    }
  }

  @Test
  void testMultiFieldNoRerankerLimitRespectedPerField() {
    nc = CollectionFixtures.populatedHybrid();
    int limit = 7;
    Map<String, List<SearchHit>> results = nc.collection().search(denseSparseFields(5, limit));
    assertTrue(results.get(FieldConfigs.DENSE_FIELD).size() <= limit);
    assertTrue(results.get(FieldConfigs.SPARSE_FIELD).size() <= limit);
  }

  @Test
  void testMultiFieldNoRerankerResultsSortedPerField() {
    nc = CollectionFixtures.populatedHybrid();
    Map<String, List<SearchHit>> results = nc.collection().search(denseSparseFields(6, 10));
    for (Map.Entry<String, List<SearchHit>> e : results.entrySet()) {
      List<SearchHit> hits = e.getValue();
      for (int i = 1; i < hits.size(); i++) {
        assertTrue(
            hits.get(i - 1).getSimilarity() >= hits.get(i).getSimilarity(),
            "Field '" + e.getKey() + "' results not sorted by descending similarity");
      }
    }
  }

  // -- multi-field search WITH RRF reranking -------------------------------------

  @Test
  void testMultiFieldRrfReturnsFlatList() {
    nc = CollectionFixtures.populatedHybrid();
    Map<String, List<SearchHit>> raw =
        nc.collection().search(denseSparseFields(10, VectorGenerators.N_VECTORS * 5));
    List<SearchHit> results = Reranker.rerank(raw, 10);
    assertNotNull(results);
  }

  @Test
  void testMultiFieldRrfResultHasRequiredKeys() {
    nc = CollectionFixtures.populatedHybrid();
    Map<String, List<SearchHit>> raw =
        nc.collection().search(denseSparseFields(11, VectorGenerators.N_VECTORS * 5));
    List<SearchHit> results = Reranker.rerank(raw, 5);
    for (SearchHit r : results) {
      assertNotNull(r.getId());
    }
  }

  @Test
  void testMultiFieldRrfLimitRespected() {
    nc = CollectionFixtures.populatedHybrid();
    Map<String, List<SearchHit>> raw =
        nc.collection().search(denseSparseFields(12, VectorGenerators.N_VECTORS * 5));
    List<SearchHit> results = Reranker.rerank(raw, 8);
    assertTrue(results.size() <= 8);
  }

  @Test
  void testMultiFieldRrfResultsSorted() {
    nc = CollectionFixtures.populatedHybrid();
    Map<String, List<SearchHit>> raw =
        nc.collection().search(denseSparseFields(13, VectorGenerators.N_VECTORS * 5));
    List<SearchHit> results = Reranker.rerank(raw, 10);
    for (int i = 1; i < results.size(); i++) {
      assertTrue(results.get(i - 1).getSimilarity() >= results.get(i).getSimilarity());
    }
  }

  // -- field_weights for RRF -----------------------------------------------------

  @Test
  void testRrfFieldWeightsAccepted() {
    nc = CollectionFixtures.populatedHybrid();
    Map<String, List<SearchHit>> raw =
        nc.collection().search(denseSparseFields(20, VectorGenerators.N_VECTORS * 5));
    List<SearchHit> results =
        Reranker.rerank(raw, 5, Map.of(FieldConfigs.DENSE_FIELD, 0.7, FieldConfigs.SPARSE_FIELD, 0.3), 60);
    assertNotNull(results);
  }

  @Test
  void testRrfFieldWeightsEqualSplit() {
    nc = CollectionFixtures.populatedHybrid();
    Map<String, List<SearchHit>> raw =
        nc.collection().search(denseSparseFields(21, VectorGenerators.N_VECTORS * 5));
    List<SearchHit> results =
        Reranker.rerank(raw, 5, Map.of(FieldConfigs.DENSE_FIELD, 0.5, FieldConfigs.SPARSE_FIELD, 0.5), 60);
    assertFalse(results.isEmpty());
  }

  @Test
  void testRrfFieldWeightsNotSummingToOneRaises() {
    nc = CollectionFixtures.populatedHybrid();
    Map<String, List<SearchHit>> raw =
        nc.collection().search(denseSparseFields(22, VectorGenerators.N_VECTORS * 5));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            Reranker.rerank(
                raw, 5, Map.of(FieldConfigs.DENSE_FIELD, 0.6, FieldConfigs.SPARSE_FIELD, 0.6), 60));
  }

  @Test
  void testRrfFieldWeightsMissingFieldRaises() {
    nc = CollectionFixtures.populatedHybrid();
    Map<String, List<SearchHit>> raw =
        nc.collection().search(denseSparseFields(23, VectorGenerators.N_VECTORS * 5));
    assertThrows(
        IllegalArgumentException.class,
        () -> Reranker.rerank(raw, 5, Map.of(FieldConfigs.DENSE_FIELD, 1.0), 60));
  }

  // -- rrfK parameter -------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(ints = {10, 30, 60, 120})
  void testRrfKParameterAccepted(int rrfK) {
    nc = CollectionFixtures.populatedHybrid();
    Map<String, List<SearchHit>> raw =
        nc.collection().search(denseSparseFields(30, VectorGenerators.N_VECTORS * 5));
    List<SearchHit> results = Reranker.rerank(raw, 5, null, rrfK);
    assertNotNull(results);
  }

  // -- per-field limit in query dict format --------------------------------------

  @Test
  void testPerFieldLimitInQueryDictFormat() {
    nc = CollectionFixtures.populatedHybrid();
    Map<String, Map<String, Object>> fields = new LinkedHashMap<>();
    fields.put(FieldConfigs.DENSE_FIELD, fieldQuery(VectorGenerators.denseVec(VectorGenerators.HYBRID_DIM, 40), 20));
    fields.put(FieldConfigs.SPARSE_FIELD, fieldQuery(VectorGenerators.sparseVec(40), 10));
    Map<String, List<SearchHit>> raw = nc.collection().search(fields);
    List<SearchHit> results = Reranker.rerank(raw, 5);
    assertTrue(results.size() <= 5);
  }

  // -- filter in multi-field search ----------------------------------------------

  @Test
  void testMultiFieldRrfWithFilter() {
    nc = CollectionFixtures.populatedHybrid();
    Map<String, List<SearchHit>> raw =
        nc.collection()
            .search(
                denseSparseFields(50, VectorGenerators.N_VECTORS * 5),
                List.of(Map.of("tags", Map.of("$eq", "important"))));
    List<SearchHit> results = Reranker.rerank(raw, VectorGenerators.N_VECTORS);
    for (SearchHit r : results) {
      assertEquals("important", r.getFilter().get("tags"));
    }
  }

  @Test
  void testMultiFieldNoRerankerWithFilter() {
    nc = CollectionFixtures.populatedHybrid();
    Map<String, List<SearchHit>> results =
        nc.collection()
            .search(
                denseSparseFields(51, VectorGenerators.N_VECTORS),
                List.of(Map.of("category", Map.of("$eq", "A"))));
    for (Map.Entry<String, List<SearchHit>> e : results.entrySet()) {
      for (SearchHit hit : e.getValue()) {
        assertEquals("A", hit.getFilter().get("category"));
      }
    }
  }

  // -- dense + multi_vector multi-field search -----------------------------------

  @Test
  void testDenseAndMvMultiFieldRrf() {
    String name = CollectionFixtures.uid("dmvrf");
    try {
      client.createCollection(name, List.of(FieldConfigs.denseField(), FieldConfigs.mvField()));
      Collection col = client.getCollection(name);
      List<ObjectItem> batch = new ArrayList<>();
      for (int i = 0; i < 20; i++) {
        batch.add(
            ObjectItem.builder("obj_" + i)
                .vector(FieldConfigs.DENSE_FIELD, VectorGenerators.denseVec(i))
                .multiVector(FieldConfigs.MV_FIELD, VectorGenerators.multiVec(i))
                .build());
      }
      col.upsert(batch);

      Map<String, Map<String, Object>> fields = new LinkedHashMap<>();
      fields.put(FieldConfigs.DENSE_FIELD, fieldQuery(VectorGenerators.denseVec(0), 20 * 5));
      fields.put(FieldConfigs.MV_FIELD, fieldQuery(VectorGenerators.multiVec(0), 20 * 5));

      Map<String, List<SearchHit>> raw = col.search(fields);
      List<SearchHit> results = Reranker.rerank(raw, 5);
      assertTrue(results.size() <= 5);
      for (SearchHit r : results) {
        assertNotNull(r.getId());
      }
    } finally {
      CollectionFixtures.safeDelete(client, name);
    }
  }

  @Test
  void testDenseAndMvMultiFieldNoReranker() {
    String name = CollectionFixtures.uid("dmvnr");
    try {
      client.createCollection(name, List.of(FieldConfigs.denseField(), FieldConfigs.mvField()));
      Collection col = client.getCollection(name);
      List<ObjectItem> batch = new ArrayList<>();
      for (int i = 0; i < 10; i++) {
        batch.add(
            ObjectItem.builder("obj_" + i)
                .vector(FieldConfigs.DENSE_FIELD, VectorGenerators.denseVec(i))
                .multiVector(FieldConfigs.MV_FIELD, VectorGenerators.multiVec(i))
                .build());
      }
      col.upsert(batch);

      Map<String, Map<String, Object>> fields = new LinkedHashMap<>();
      fields.put(FieldConfigs.DENSE_FIELD, fieldQuery(VectorGenerators.denseVec(0), 5));
      fields.put(FieldConfigs.MV_FIELD, fieldQuery(VectorGenerators.multiVec(0), 5));

      Map<String, List<SearchHit>> results = col.search(fields);
      assertTrue(results.containsKey(FieldConfigs.DENSE_FIELD));
      assertTrue(results.containsKey(FieldConfigs.MV_FIELD));
    } finally {
      CollectionFixtures.safeDelete(client, name);
    }
  }

  // -- three-field search (dense + sparse + multi_vector) ------------------------

  @Test
  void testThreeFieldRrfSearch() {
    String name = CollectionFixtures.uid("tri");
    try {
      client.createCollection(
          name, List.of(FieldConfigs.denseField(), FieldConfigs.sparseField(), FieldConfigs.mvField()));
      Collection col = client.getCollection(name);
      List<ObjectItem> batch = new ArrayList<>();
      for (int i = 0; i < 20; i++) {
        batch.add(
            ObjectItem.builder("tri_" + i)
                .vector(FieldConfigs.DENSE_FIELD, VectorGenerators.denseVec(i))
                .sparse(FieldConfigs.SPARSE_FIELD, VectorGenerators.sparseVec(i))
                .multiVector(FieldConfigs.MV_FIELD, VectorGenerators.multiVec(i))
                .build());
      }
      col.upsert(batch);

      Map<String, Map<String, Object>> fields = new LinkedHashMap<>();
      fields.put(FieldConfigs.DENSE_FIELD, fieldQuery(VectorGenerators.denseVec(99), 20));
      fields.put(FieldConfigs.SPARSE_FIELD, fieldQuery(VectorGenerators.sparseVec(99), 20));
      fields.put(FieldConfigs.MV_FIELD, fieldQuery(VectorGenerators.multiVec(99), 20));

      Map<String, List<SearchHit>> raw = col.search(fields);
      List<SearchHit> results = Reranker.rerank(raw, 5);
      assertFalse(results.isEmpty());
      for (SearchHit r : results) {
        assertNotNull(r.getId());
      }
    } finally {
      CollectionFixtures.safeDelete(client, name);
    }
  }

  @Test
  void testThreeFieldNoRerankerReturnsAllFieldKeys() {
    String name = CollectionFixtures.uid("trnr");
    try {
      client.createCollection(
          name, List.of(FieldConfigs.denseField(), FieldConfigs.sparseField(), FieldConfigs.mvField()));
      Collection col = client.getCollection(name);
      List<ObjectItem> batch = new ArrayList<>();
      for (int i = 0; i < 10; i++) {
        batch.add(
            ObjectItem.builder("tri_" + i)
                .vector(FieldConfigs.DENSE_FIELD, VectorGenerators.denseVec(i))
                .sparse(FieldConfigs.SPARSE_FIELD, VectorGenerators.sparseVec(i))
                .multiVector(FieldConfigs.MV_FIELD, VectorGenerators.multiVec(i))
                .build());
      }
      col.upsert(batch);

      Map<String, Map<String, Object>> fields = new LinkedHashMap<>();
      fields.put(FieldConfigs.DENSE_FIELD, fieldQuery(VectorGenerators.denseVec(0), 5));
      fields.put(FieldConfigs.SPARSE_FIELD, fieldQuery(VectorGenerators.sparseVec(0), 5));
      fields.put(FieldConfigs.MV_FIELD, fieldQuery(VectorGenerators.multiVec(0), 5));

      Map<String, List<SearchHit>> results = col.search(fields);
      assertTrue(results.containsKey(FieldConfigs.DENSE_FIELD));
      assertTrue(results.containsKey(FieldConfigs.SPARSE_FIELD));
      assertTrue(results.containsKey(FieldConfigs.MV_FIELD));
    } finally {
      CollectionFixtures.safeDelete(client, name);
    }
  }
}
