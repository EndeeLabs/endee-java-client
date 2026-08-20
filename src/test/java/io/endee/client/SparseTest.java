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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// Covers sparse vector fields: creation (default/BM25), upsert, search, describe, delete.
class SparseTest {

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

  // -- collection creation ------------------------------------------------------

  @Test
  void testCreateSparseCollection() {
    String name = CollectionFixtures.uid("sp");
    try {
      Map<String, Object> result = client.createCollection(name, List.of(FieldConfigs.sparseField()));
      assertNotNull(result);
      assertTrue(CollectionFixtures.collectionNames(client).contains(name));
    } finally {
      CollectionFixtures.safeDelete(client, name);
    }
  }

  @Test
  void testCreateSparseCollectionBm25() {
    String name = CollectionFixtures.uid("spbm");
    try {
      client.createCollection(name, List.of(FieldConfigs.sparseField("endee_bm25")));
      assertTrue(CollectionFixtures.collectionNames(client).contains(name));
    } catch (Exception e) {
      Assumptions.abort("endee_bm25 not supported on this server: " + e.getMessage());
    } finally {
      CollectionFixtures.safeDelete(client, name);
    }
  }

  // -- upsert ---------------------------------------------------------------

  @Test
  void testSparseUpsertSingleObject() {
    nc = CollectionFixtures.emptySparse();
    SparseData sd = VectorGenerators.sparseVec(0);
    ObjectItem item =
        ObjectItem.builder("sp1").sparse(FieldConfigs.SPARSE_FIELD, sd).build();
    Map<String, Object> result = nc.collection().upsert(List.of(item));
    assertEquals(1, ((Number) result.get("upserted")).intValue());
  }

  @Test
  void testSparseUpsertBatch() {
    nc = CollectionFixtures.emptySparse();
    List<ObjectItem> batch = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      batch.add(ObjectBuilders.sparseItem(i));
    }
    Map<String, Object> result = nc.collection().upsert(batch);
    assertEquals(10, ((Number) result.get("upserted")).intValue());
  }

  @Test
  void testSparseUpsertWithMetaAndFilter() {
    nc = CollectionFixtures.emptySparse();
    SparseData sd = VectorGenerators.sparseVec(1);
    ObjectItem item =
        ObjectItem.builder("sp_full")
            .meta(Map.of("title", "sparse doc"))
            .filter(Map.of("category", "A"))
            .sparse(FieldConfigs.SPARSE_FIELD, sd)
            .build();
    Map<String, Object> result = nc.collection().upsert(List.of(item));
    assertEquals(1, ((Number) result.get("upserted")).intValue());
  }

  // An empty upsert list throws IllegalArgumentException.
  @Test
  void testSparseUpsertEmptyBatchRejected() {
    nc = CollectionFixtures.emptySparse();
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> nc.collection().upsert(List.of()));
  }

  @Test
  void testSparseUpsertOverwrite() {
    nc = CollectionFixtures.emptySparse();
    SparseData sd = VectorGenerators.sparseVec(2);
    nc.collection()
        .upsert(
            List.of(
                ObjectItem.builder("dup")
                    .meta(Map.of("v", 1))
                    .sparse(FieldConfigs.SPARSE_FIELD, sd)
                    .build()));
    Map<String, Object> result =
        nc.collection()
            .upsert(
                List.of(
                    ObjectItem.builder("dup")
                        .meta(Map.of("v", 2))
                        .sparse(FieldConfigs.SPARSE_FIELD, sd)
                        .build()));
    assertEquals(1, ((Number) result.get("upserted")).intValue());
  }

  // -- search -----------------------------------------------------------------

  @Test
  void testSparseSearchReturnsResults() {
    nc = CollectionFixtures.populatedSparse();
    SparseData sd = VectorGenerators.sparseVec(99);
    List<SearchHit> results =
        nc.collection().search(queryField(FieldConfigs.SPARSE_FIELD, sd, 5)).get(FieldConfigs.SPARSE_FIELD);
    assertNotNull(results);
    assertFalse(results.isEmpty());
  }

  @Test
  void testSparseSearchResultHasRequiredKeys() {
    nc = CollectionFixtures.populatedSparse();
    SparseData sd = VectorGenerators.sparseVec(0);
    List<SearchHit> results =
        nc.collection().search(queryField(FieldConfigs.SPARSE_FIELD, sd, 1)).get(FieldConfigs.SPARSE_FIELD);
    SearchHit r = results.get(0);
    assertNotNull(r.getId());
    // similarity is a primitive double; presence is structural (always populated by SearchHit).
  }

  @Test
  void testSparseSearchResultsSortedBySimilarity() {
    nc = CollectionFixtures.populatedSparse();
    SparseData sd = VectorGenerators.sparseVec(5);
    List<SearchHit> results =
        nc.collection().search(queryField(FieldConfigs.SPARSE_FIELD, sd, 10)).get(FieldConfigs.SPARSE_FIELD);
    for (int i = 1; i < results.size(); i++) {
      assertTrue(results.get(i - 1).getSimilarity() >= results.get(i).getSimilarity());
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 5, 10, 20})
  void testSparseSearchLimitRespected(int limit) {
    nc = CollectionFixtures.populatedSparse();
    SparseData sd = VectorGenerators.sparseVec(3);
    List<SearchHit> results =
        nc.collection().search(queryField(FieldConfigs.SPARSE_FIELD, sd, limit)).get(FieldConfigs.SPARSE_FIELD);
    assertTrue(results.size() <= limit);
  }

  @ParameterizedTest
  @ValueSource(ints = {32, 64, 128, 256})
  void testSparseSearchEfSearchAccepted(int efSearch) {
    nc = CollectionFixtures.populatedSparse();
    SparseData sd = VectorGenerators.sparseVec(1);
    List<SearchHit> results =
        nc.collection()
            .search(queryField(FieldConfigs.SPARSE_FIELD, sd, 5), null, efSearch, null, null)
            .get(FieldConfigs.SPARSE_FIELD);
    assertNotNull(results);
  }

  // -- meta round-trip ----------------------------------------------------------

  @Test
  void testSparseMetaRoundTrips() {
    nc = CollectionFixtures.emptySparse();
    SparseData sd = VectorGenerators.sparseVec(42);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("title", "sparse doc");
    payload.put("count", 7);
    nc.collection()
        .upsert(
            List.of(
                ObjectItem.builder("sp_meta")
                    .meta(payload)
                    .sparse(FieldConfigs.SPARSE_FIELD, sd)
                    .build()));
    List<SearchHit> results =
        nc.collection().search(queryField(FieldConfigs.SPARSE_FIELD, sd, 1)).get(FieldConfigs.SPARSE_FIELD);
    assertEquals("sp_meta", results.get(0).getId());
    assertEquals("sparse doc", results.get(0).getMeta().get("title"));
    assertEquals(7, ((Number) results.get(0).getMeta().get("count")).intValue());
  }

  // -- delete_object -------------------------------------------------------------

  @Test
  void testSparseDeleteObjectReturnsResponse() {
    nc = CollectionFixtures.populatedSparse();
    Map<String, Object> result = nc.collection().deleteObject("sp_0040");
    assertEquals("sp_0040", result.get("deleted"));
  }

  @Test
  void testSparseDeleteObjectRemovedFromSearch() {
    nc = CollectionFixtures.populatedSparse();
    String target = "sp_0041";
    nc.collection().deleteObject(target);
    SparseData sd = VectorGenerators.sparseVec(41);
    List<SearchHit> results =
        nc.collection()
            .search(queryField(FieldConfigs.SPARSE_FIELD, sd, VectorGenerators.N_VECTORS))
            .get(FieldConfigs.SPARSE_FIELD);
    assertTrue(results.stream().noneMatch(r -> r.getId().equals(target)));
  }

  // -- describe -------------------------------------------------------------------

  @Test
  void testSparseDescribeShowsCorrectField() {
    nc = CollectionFixtures.emptySparse();
    Map<String, Object> info = nc.collection().describe();
    List<String> fieldNames = fieldNames(info);
    assertTrue(fieldNames.contains(FieldConfigs.SPARSE_FIELD));
  }

  @Test
  void testSparseDescribeFieldTypeIsSparse() {
    nc = CollectionFixtures.emptySparse();
    Map<String, Object> info = nc.collection().describe();
    Map<String, Object> field = findField(info, FieldConfigs.SPARSE_FIELD);
    assertNotNull(field);
    assertEquals("sparse", field.get("type"));
  }

  @SuppressWarnings("unchecked")
  private static List<String> fieldNames(Map<String, Object> info) {
    List<Object> fields = (List<Object>) info.getOrDefault("fields", List.of());
    List<String> names = new ArrayList<>();
    for (Object f : fields) {
      if (f instanceof Map<?, ?> m) {
        names.add(String.valueOf(m.get("name")));
      } else {
        names.add(String.valueOf(f));
      }
    }
    return names;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> findField(Map<String, Object> info, String name) {
    List<Object> fields = (List<Object>) info.getOrDefault("fields", List.of());
    for (Object f : fields) {
      if (f instanceof Map<?, ?> m && name.equals(m.get("name"))) {
        return (Map<String, Object>) m;
      }
    }
    return null;
  }
}
