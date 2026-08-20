package io.endee.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.endee.client.support.CollectionFixtures;
import io.endee.client.support.CollectionFixtures.NamedCollection;
import io.endee.client.support.FieldConfigs;
import io.endee.client.support.ObjectBuilders;
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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

// Covers hybrid (dense + sparse) collections: upsert, dense/sparse/RRF search, per-field limits.
class HybridSearchTest {

  private final Endee client = TestConfig.client();
  private NamedCollection nc;

  @AfterEach
  void tearDown() {
    if (nc != null) {
      CollectionFixtures.safeDelete(client, nc.name());
      nc = null;
    }
  }

  private static Map<String, Map<String, Object>> queryField(
      String fieldName, Object query, int limit) {
    Map<String, Object> inner = new LinkedHashMap<>();
    inner.put("query", query);
    inner.put("limit", limit);
    Map<String, Map<String, Object>> outer = new LinkedHashMap<>();
    outer.put(fieldName, inner);
    return outer;
  }

  private static Map<String, Map<String, Object>> twoFieldQuery(
      double[] denseQuery, int denseLimit, SparseData sparseQuery, int sparseLimit) {
    Map<String, Map<String, Object>> fields = new LinkedHashMap<>();
    Map<String, Object> d = new LinkedHashMap<>();
    d.put("query", denseQuery);
    d.put("limit", denseLimit);
    fields.put(FieldConfigs.DENSE_FIELD, d);
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("query", sparseQuery);
    s.put("limit", sparseLimit);
    fields.put(FieldConfigs.SPARSE_FIELD, s);
    return fields;
  }

  // -- upsert -------------------------------------------------------------------

  @Test
  void testHybridUpsertSingleObject() {
    nc = CollectionFixtures.emptyHybrid();
    SparseData sd = VectorGenerators.sparseVec(0);
    ObjectItem item =
        ObjectItem.builder("hv1")
            .vector(FieldConfigs.DENSE_FIELD, VectorGenerators.denseVec(VectorGenerators.HYBRID_DIM, 0))
            .sparse(FieldConfigs.SPARSE_FIELD, sd)
            .build();
    Map<String, Object> result = nc.collection().upsert(List.of(item));
    assertTrue(result.containsKey("upserted"));
  }

  @Test
  void testHybridUpsertWithMetaAndFilter() {
    nc = CollectionFixtures.emptyHybrid();
    SparseData sd = VectorGenerators.sparseVec(1);
    ObjectItem item =
        ObjectItem.builder("hv_full")
            .meta(Map.of("title", "hybrid doc"))
            .filter(Map.of("category", "A"))
            .vector(FieldConfigs.DENSE_FIELD, VectorGenerators.denseVec(VectorGenerators.HYBRID_DIM, 1))
            .sparse(FieldConfigs.SPARSE_FIELD, sd)
            .build();
    Map<String, Object> result = nc.collection().upsert(List.of(item));
    assertTrue(result.containsKey("upserted"));
  }

  @Test
  void testHybridUpsertBatch() {
    nc = CollectionFixtures.emptyHybrid();
    List<ObjectItem> batch = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      batch.add(ObjectBuilders.hybridItem(i, VectorGenerators.HYBRID_DIM));
    }
    Map<String, Object> result = nc.collection().upsert(batch);
    assertTrue(result.containsKey("upserted"));
  }

  @Test
  void testHybridUpsertCountReturned() {
    nc = CollectionFixtures.emptyHybrid();
    List<ObjectItem> batch = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      batch.add(ObjectBuilders.hybridItem(i, VectorGenerators.HYBRID_DIM));
    }
    Map<String, Object> result = nc.collection().upsert(batch);
    assertEquals(5, ((Number) result.get("upserted")).intValue());
  }

  // -- dense-only search --------------------------------------------------------

  @Test
  void testHybridDenseOnlySearchReturnsResults() {
    nc = CollectionFixtures.populatedHybrid();
    List<SearchHit> results =
        nc.collection()
            .search(queryField(FieldConfigs.DENSE_FIELD, VectorGenerators.denseVec(VectorGenerators.HYBRID_DIM, 0), 5))
            .get(FieldConfigs.DENSE_FIELD);
    assertEquals(5, results.size());
  }

  @Test
  void testHybridDenseOnlyResultStructure() {
    nc = CollectionFixtures.populatedHybrid();
    List<SearchHit> results =
        nc.collection()
            .search(queryField(FieldConfigs.DENSE_FIELD, VectorGenerators.denseVec(VectorGenerators.HYBRID_DIM, 0), 1))
            .get(FieldConfigs.DENSE_FIELD);
    assertNotNull(results.get(0).getId());
  }

  @Test
  void testHybridDenseOnlyResultsSorted() {
    nc = CollectionFixtures.populatedHybrid();
    List<SearchHit> results =
        nc.collection()
            .search(queryField(FieldConfigs.DENSE_FIELD, VectorGenerators.denseVec(VectorGenerators.HYBRID_DIM, 0), 10))
            .get(FieldConfigs.DENSE_FIELD);
    for (int i = 1; i < results.size(); i++) {
      assertTrue(results.get(i - 1).getSimilarity() >= results.get(i).getSimilarity());
    }
  }

  // -- sparse-only search -------------------------------------------------------

  @Test
  void testHybridSparseOnlySearchReturnsResults() {
    nc = CollectionFixtures.populatedHybrid();
    SparseData sd = VectorGenerators.sparseVec(99);
    List<SearchHit> results =
        nc.collection().search(queryField(FieldConfigs.SPARSE_FIELD, sd, 5)).get(FieldConfigs.SPARSE_FIELD);
    assertNotNull(results);
    assertFalse(results.isEmpty());
  }

  @Test
  void testHybridSparseOnlyResultStructure() {
    nc = CollectionFixtures.populatedHybrid();
    SparseData sd = VectorGenerators.sparseVec(7);
    List<SearchHit> results =
        nc.collection().search(queryField(FieldConfigs.SPARSE_FIELD, sd, 1)).get(FieldConfigs.SPARSE_FIELD);
    assertNotNull(results.get(0).getId());
  }

  // -- full hybrid search (RRF) -------------------------------------------------

  @Test
  void testHybridFullRrfSearchReturnsResults() {
    nc = CollectionFixtures.populatedHybrid();
    SparseData sd = VectorGenerators.sparseVec(42);
    Map<String, List<SearchHit>> raw =
        nc.collection()
            .search(
                twoFieldQuery(
                    VectorGenerators.denseVec(VectorGenerators.HYBRID_DIM, 42),
                    VectorGenerators.N_VECTORS * 5,
                    sd,
                    VectorGenerators.N_VECTORS * 5));
    List<SearchHit> results = Reranker.rerank(raw, 10);
    assertEquals(10, results.size());
  }

  @Test
  void testHybridFullRrfResultStructure() {
    nc = CollectionFixtures.populatedHybrid();
    SparseData sd = VectorGenerators.sparseVec(11);
    Map<String, List<SearchHit>> raw =
        nc.collection()
            .search(
                twoFieldQuery(
                    VectorGenerators.denseVec(VectorGenerators.HYBRID_DIM, 11),
                    VectorGenerators.N_VECTORS * 5,
                    sd,
                    VectorGenerators.N_VECTORS * 5));
    List<SearchHit> results = Reranker.rerank(raw, 1);
    assertNotNull(results.get(0).getId());
  }

  @Test
  void testHybridFullRrfResultsSorted() {
    nc = CollectionFixtures.populatedHybrid();
    SparseData sd = VectorGenerators.sparseVec(13);
    Map<String, List<SearchHit>> raw =
        nc.collection()
            .search(
                twoFieldQuery(
                    VectorGenerators.denseVec(VectorGenerators.HYBRID_DIM, 13),
                    VectorGenerators.N_VECTORS * 5,
                    sd,
                    VectorGenerators.N_VECTORS * 5));
    List<SearchHit> results = Reranker.rerank(raw, 10);
    for (int i = 1; i < results.size(); i++) {
      assertTrue(results.get(i - 1).getSimilarity() >= results.get(i).getSimilarity());
    }
  }

  @Test
  void testHybridRrfLimitRespected() {
    nc = CollectionFixtures.populatedHybrid();
    SparseData sd = VectorGenerators.sparseVec(5);
    Map<String, List<SearchHit>> raw =
        nc.collection()
            .search(
                twoFieldQuery(
                    VectorGenerators.denseVec(VectorGenerators.HYBRID_DIM, 5),
                    VectorGenerators.N_VECTORS * 5,
                    sd,
                    VectorGenerators.N_VECTORS * 5));
    List<SearchHit> results = Reranker.rerank(raw, 5);
    assertTrue(results.size() <= 5);
  }

  // -- per-field limit ----------------------------------------------------------

  @Test
  void testHybridPerFieldLimitRrf() {
    nc = CollectionFixtures.populatedHybrid();
    SparseData sd = VectorGenerators.sparseVec(9);
    Map<String, List<SearchHit>> raw =
        nc.collection().search(twoFieldQuery(VectorGenerators.denseVec(VectorGenerators.HYBRID_DIM, 9), 20, sd, 10));
    List<SearchHit> results = Reranker.rerank(raw, 10);
    assertNotNull(results);
    assertTrue(results.size() <= 10);
  }

  // -- ef_search parameter ------------------------------------------------------

  @ParameterizedTest
  @ValueSource(ints = {32, 64, 128, 256, 512})
  void testHybridEfSearchAccepted(int efSearch) {
    nc = CollectionFixtures.populatedHybrid();
    List<SearchHit> results =
        nc.collection()
            .search(
                queryField(FieldConfigs.DENSE_FIELD, VectorGenerators.denseVec(VectorGenerators.HYBRID_DIM, 0), 5),
                null,
                efSearch,
                null,
                null)
            .get(FieldConfigs.DENSE_FIELD);
    assertNotNull(results);
  }

  // -- meta round-trip ----------------------------------------------------------

  @Test
  void testHybridMetaRoundTrips() {
    nc = CollectionFixtures.emptyHybrid();
    SparseData sd = VectorGenerators.sparseVec(5);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("title", "hybrid doc");
    payload.put("count", 3);
    nc.collection()
        .upsert(
            List.of(
                ObjectItem.builder("hrt")
                    .meta(payload)
                    .vector(FieldConfigs.DENSE_FIELD, VectorGenerators.denseVec(VectorGenerators.HYBRID_DIM, 5))
                    .sparse(FieldConfigs.SPARSE_FIELD, sd)
                    .build()));
    List<SearchHit> results =
        nc.collection()
            .search(queryField(FieldConfigs.DENSE_FIELD, VectorGenerators.denseVec(VectorGenerators.HYBRID_DIM, 5), 1))
            .get(FieldConfigs.DENSE_FIELD);
    assertEquals("hrt", results.get(0).getId());
    assertEquals("hybrid doc", results.get(0).getMeta().get("title"));
    assertEquals(3, ((Number) results.get(0).getMeta().get("count")).intValue());
  }

  // -- delete_object in hybrid collection ---------------------------------------

  @Test
  void testHybridDeleteObjectRemovesFromSearch() {
    nc = CollectionFixtures.populatedHybrid();
    String targetId = "vec_0010";
    nc.collection().deleteObject(targetId);

    SparseData sd = VectorGenerators.sparseVec(10);
    Map<String, List<SearchHit>> raw =
        nc.collection()
            .search(
                twoFieldQuery(
                    VectorGenerators.denseVec(VectorGenerators.HYBRID_DIM, 10),
                    VectorGenerators.N_VECTORS * 5,
                    sd,
                    VectorGenerators.N_VECTORS * 5));
    List<SearchHit> results = Reranker.rerank(raw, VectorGenerators.N_VECTORS);
    assertTrue(results.stream().noneMatch(r -> r.getId().equals(targetId)));
  }

  // -- precision + space_type combinations for hybrid collections ---------------

  static List<Arguments> precisionSpaceCombinations() {
    return List.of(
        Arguments.of("int8", "cosine"),
        Arguments.of("float32", "cosine"),
        Arguments.of("float16", "l2"),
        Arguments.of("int16", "ip"));
  }

  @ParameterizedTest
  @MethodSource("precisionSpaceCombinations")
  void testHybridCreateVariousFieldConfigs(String precision, String spaceType) {
    String name = CollectionFixtures.uid("hcfg");
    try {
      client.createCollection(
          name,
          List.of(
              FieldConfigs.denseField(VectorGenerators.HYBRID_DIM, spaceType, precision),
              FieldConfigs.sparseField()));
      Collection collection = client.getCollection(name);
      Map<String, Object> info = collection.describe();
      @SuppressWarnings("unchecked")
      List<Object> fields = (List<Object>) info.getOrDefault("fields", List.of());
      assertEquals(2, fields.size());
    } finally {
      CollectionFixtures.safeDelete(client, name);
    }
  }
}
