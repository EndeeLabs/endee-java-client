package io.endee.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.endee.client.support.CollectionFixtures;
import io.endee.client.support.CollectionFixtures.NamedCollection;
import io.endee.client.support.FieldConfigs;
import io.endee.client.support.TestConfig;
import io.endee.client.support.VectorGenerators;
import io.endee.client.types.ObjectInfo;
import io.endee.client.types.ObjectItem;
import io.endee.client.types.SearchHit;
import io.endee.client.types.UpdateFilterParams;
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

// Covers multi_vector (ColBERT-style) fields: creation, upsert, search, and maintenance operations.
class MultiVectorTest {

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

  // -- Collection creation ------------------------------------------------------

  @Test
  void testCreateMultiVectorCollection() {
    String name = CollectionFixtures.uid("mv");
    try {
      Map<String, Object> result = client.createCollection(name, List.of(FieldConfigs.mvField()));
      assertNotNull(result);
      assertTrue(CollectionFixtures.collectionNames(client).contains(name));
    } finally {
      CollectionFixtures.safeDelete(client, name);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"mean", "max"})
  void testCreateMultiVectorBothPoolingMethods(String poolingMethod) {
    String name = CollectionFixtures.uid("mvp");
    try {
      client.createCollection(
          name,
          List.of(
              FieldConfigs.mvField(VectorGenerators.DIM, "cosine", "int8", poolingMethod)));
      assertTrue(CollectionFixtures.collectionNames(client).contains(name));
    } finally {
      CollectionFixtures.safeDelete(client, name);
    }
  }

  static List<Arguments> precisionSpaceCombinations() {
    List<Arguments> args = new ArrayList<>();
    for (String precision : FieldConfigs.ALL_PRECISIONS) {
      for (String spaceType : FieldConfigs.ALL_SPACE_TYPES) {
        args.add(Arguments.of(precision, spaceType));
      }
    }
    return args;
  }

  @ParameterizedTest
  @MethodSource("precisionSpaceCombinations")
  void testCreateMultiVectorAllPrecisionSpaceCombinations(String precision, String spaceType) {
    String name = CollectionFixtures.uid("mvcombo");
    try {
      client.createCollection(
          name,
          List.of(FieldConfigs.mvField(VectorGenerators.DIM, spaceType, precision, "mean")));
      assertTrue(CollectionFixtures.collectionNames(client).contains(name));
    } finally {
      CollectionFixtures.safeDelete(client, name);
    }
  }

  // -- Upsert -------------------------------------------------------------------

  @Test
  void testMultiVectorUpsertSingleObject() {
    nc = CollectionFixtures.emptyMv();
    ObjectItem item =
        ObjectItem.builder("mv1")
            .multiVector(FieldConfigs.MV_FIELD, VectorGenerators.multiVec(0))
            .build();
    Map<String, Object> result = nc.collection().upsert(List.of(item));
    assertTrue(result.containsKey("upserted"));
  }

  @Test
  void testMultiVectorUpsertCount() {
    nc = CollectionFixtures.emptyMv();
    List<ObjectItem> batch = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      batch.add(
          ObjectItem.builder("mv_" + i)
              .multiVector(FieldConfigs.MV_FIELD, VectorGenerators.multiVec(i))
              .build());
    }
    Map<String, Object> result = nc.collection().upsert(batch);
    assertEquals(5, ((Number) result.get("upserted")).intValue());
  }

  // Collection.upsert rejects an empty list client-side with IllegalArgumentException.
  @Test
  void testMultiVectorUpsertEmptyBatchRejected() {
    nc = CollectionFixtures.emptyMv();
    assertThrows(IllegalArgumentException.class, () -> nc.collection().upsert(List.of()));
  }

  @Test
  void testMultiVectorUpsertSingleVector() {
    nc = CollectionFixtures.emptyMv();
    double[][] single = new double[][] {VectorGenerators.denseVec(0)};
    ObjectItem item =
        ObjectItem.builder("single_tok").multiVector(FieldConfigs.MV_FIELD, single).build();
    Map<String, Object> result = nc.collection().upsert(List.of(item));
    assertTrue(result.containsKey("upserted"));
  }

  @Test
  void testMultiVectorUpsertManyVectors() {
    nc = CollectionFixtures.emptyMv();
    double[][] many = VectorGenerators.multiVec(16, VectorGenerators.DIM, 0);
    ObjectItem item =
        ObjectItem.builder("many_tok").multiVector(FieldConfigs.MV_FIELD, many).build();
    Map<String, Object> result = nc.collection().upsert(List.of(item));
    assertTrue(result.containsKey("upserted"));
  }

  @Test
  void testMultiVectorUpsertWithMetaAndFilter() {
    nc = CollectionFixtures.emptyMv();
    ObjectItem item =
        ObjectItem.builder("mv_full")
            .meta(Map.of("title", "colbert doc"))
            .filter(Map.of("category", "A"))
            .multiVector(FieldConfigs.MV_FIELD, VectorGenerators.multiVec(1))
            .build();
    Map<String, Object> result = nc.collection().upsert(List.of(item));
    assertTrue(result.containsKey("upserted"));
  }

  @Test
  void testMultiVectorUpsertOverwrite() {
    nc = CollectionFixtures.emptyMv();
    nc.collection()
        .upsert(
            List.of(
                ObjectItem.builder("dup")
                    .meta(Map.of("v", 1))
                    .multiVector(FieldConfigs.MV_FIELD, VectorGenerators.multiVec(0))
                    .build()));
    Map<String, Object> result =
        nc.collection()
            .upsert(
                List.of(
                    ObjectItem.builder("dup")
                        .meta(Map.of("v", 2))
                        .multiVector(FieldConfigs.MV_FIELD, VectorGenerators.multiVec(1))
                        .build()));
    assertTrue(result.containsKey("upserted"));
  }

  // -- Search -------------------------------------------------------------------

  @Test
  void testMultiVectorSearchReturnsResults() {
    nc = CollectionFixtures.populatedMv();
    List<SearchHit> results =
        nc.collection()
            .search(queryField(FieldConfigs.MV_FIELD, VectorGenerators.multiVec(99), 5))
            .get(FieldConfigs.MV_FIELD);
    assertNotNull(results);
    assertFalse(results.isEmpty());
  }

  @Test
  void testMultiVectorSearchResultHasRequiredKeys() {
    nc = CollectionFixtures.populatedMv();
    List<SearchHit> results =
        nc.collection()
            .search(queryField(FieldConfigs.MV_FIELD, VectorGenerators.multiVec(0), 1))
            .get(FieldConfigs.MV_FIELD);
    assertNotNull(results.get(0).getId());
  }

  @Test
  void testMultiVectorSearchResultsSortedBySimilarity() {
    nc = CollectionFixtures.populatedMv();
    List<SearchHit> results =
        nc.collection()
            .search(queryField(FieldConfigs.MV_FIELD, VectorGenerators.multiVec(5), 10))
            .get(FieldConfigs.MV_FIELD);
    for (int i = 1; i < results.size(); i++) {
      assertTrue(results.get(i - 1).getSimilarity() >= results.get(i).getSimilarity());
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 5, 10, 20})
  void testMultiVectorSearchLimitRespected(int limit) {
    nc = CollectionFixtures.populatedMv();
    List<SearchHit> results =
        nc.collection()
            .search(queryField(FieldConfigs.MV_FIELD, VectorGenerators.multiVec(3), limit))
            .get(FieldConfigs.MV_FIELD);
    assertTrue(results.size() <= limit);
  }

  @Test
  void testMultiVectorSearchSingleQueryVector() {
    nc = CollectionFixtures.populatedMv();
    double[][] single = new double[][] {VectorGenerators.denseVec(77)};
    List<SearchHit> results =
        nc.collection().search(queryField(FieldConfigs.MV_FIELD, single, 5)).get(FieldConfigs.MV_FIELD);
    assertNotNull(results);
    assertFalse(results.isEmpty());
  }

  @ParameterizedTest
  @ValueSource(ints = {32, 64, 128, 256, 512})
  void testMultiVectorSearchEfSearchAccepted(int efSearch) {
    nc = CollectionFixtures.populatedMv();
    List<SearchHit> results =
        nc.collection()
            .search(
                queryField(FieldConfigs.MV_FIELD, VectorGenerators.multiVec(1), 5),
                null,
                efSearch,
                null,
                null)
            .get(FieldConfigs.MV_FIELD);
    assertNotNull(results);
  }

  // -- Meta round-trip ----------------------------------------------------------

  @Test
  void testMultiVectorMetaRoundTrips() {
    nc = CollectionFixtures.emptyMv();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("title", "colbert doc");
    payload.put("count", 9);
    nc.collection()
        .upsert(
            List.of(
                ObjectItem.builder("mv_meta")
                    .meta(payload)
                    .multiVector(FieldConfigs.MV_FIELD, VectorGenerators.multiVec(42))
                    .build()));
    List<SearchHit> results =
        nc.collection()
            .search(queryField(FieldConfigs.MV_FIELD, VectorGenerators.multiVec(42), 1))
            .get(FieldConfigs.MV_FIELD);
    assertEquals("mv_meta", results.get(0).getId());
    assertEquals("colbert doc", results.get(0).getMeta().get("title"));
    assertEquals(9, ((Number) results.get(0).getMeta().get("count")).intValue());
  }

  // -- delete_object --------------------------------------------------------------

  @Test
  void testMultiVectorDeleteObject() {
    nc = CollectionFixtures.populatedMv();
    String target = "mv_0010";
    Map<String, Object> result = nc.collection().deleteObject(target);
    assertEquals(target, result.get("deleted"));
    List<SearchHit> results =
        nc.collection()
            .search(queryField(FieldConfigs.MV_FIELD, VectorGenerators.multiVec(10), VectorGenerators.N_VECTORS))
            .get(FieldConfigs.MV_FIELD);
    assertTrue(results.stream().noneMatch(r -> r.getId().equals(target)));
  }

  // -- describe() -----------------------------------------------------------------

  @Test
  void testMultiVectorDescribeShowsCorrectField() {
    nc = CollectionFixtures.emptyMv();
    Map<String, Object> info = nc.collection().describe();
    assertTrue(fieldNames(info).contains(FieldConfigs.MV_FIELD));
  }

  @Test
  void testMultiVectorDescribeFieldType() {
    nc = CollectionFixtures.emptyMv();
    Map<String, Object> info = nc.collection().describe();
    Map<String, Object> field = findField(info, FieldConfigs.MV_FIELD);
    assertNotNull(field);
    assertEquals("multi_vector", field.get("type"));
  }

  // -- getObjects / deleteByFilter / updateFilters / shrink / rebuild / backup ---

  @Test
  void testMultiVectorGetObjects() {
    nc = CollectionFixtures.populatedMv();
    List<ObjectInfo> infos = nc.collection().getObjects(List.of("mv_0005"));
    assertEquals(1, infos.size());
    ObjectInfo info = infos.get(0);
    assertEquals("mv_0005", info.getId());
    assertTrue(info.getMultiVectors().containsKey(FieldConfigs.MV_FIELD));
    assertEquals(VectorGenerators.MV_TOKENS, info.getMultiVectors().get(FieldConfigs.MV_FIELD).length);
  }

  @Test
  void testMultiVectorDeleteByFilter() {
    nc = CollectionFixtures.populatedMv();
    Map<String, Object> result =
        nc.collection().deleteByFilter(List.of(Map.of("tags", Map.of("$eq", "important"))));
    assertNotNull(result);
  }

  @Test
  void testMultiVectorUpdateFilters() {
    nc = CollectionFixtures.populatedMv();
    Map<String, Object> newFilter = Map.of("category", "Z", "score", 999, "tags", "updated");
    nc.collection()
        .updateFilters(List.of(new UpdateFilterParams("mv_0002", newFilter)));
    List<ObjectInfo> infos = nc.collection().getObjects(List.of("mv_0002"));
    assertEquals("Z", infos.get(0).getFilter().get("category"));
    assertEquals("updated", infos.get(0).getFilter().get("tags"));
  }

  @Test
  void testMultiVectorShrink() {
    nc = CollectionFixtures.populatedMv();
    Map<String, Object> result = nc.collection().shrink();
    assertNotNull(result);
  }

  @Test
  void testMultiVectorRebuild() {
    nc = CollectionFixtures.populatedMv();
    Map<String, Object> result =
        nc.collection().rebuild(List.of(Map.of("field", FieldConfigs.MV_FIELD)));
    assertNotNull(result);
    Map<String, Object> status = nc.collection().rebuildStatus();
    assertNotNull(status);
  }

  @Test
  void testMultiVectorCreateBackup() {
    nc = CollectionFixtures.populatedMv();
    String backupName = CollectionFixtures.uid("bkp");
    try {
      Map<String, Object> result = nc.collection().createBackup(backupName);
      assertNotNull(result);
    } finally {
      CollectionFixtures.safeDeleteBackup(client, backupName);
    }
  }

  // -- Mixed: dense + multi_vector ----------------------------------------------

  @Test
  void testCreateCollectionDenseAndMultiVector() {
    String name = CollectionFixtures.uid("dmv");
    try {
      client.createCollection(name, List.of(FieldConfigs.denseField(), FieldConfigs.mvField()));
      Collection collection = client.getCollection(name);
      Map<String, Object> info = collection.describe();
      assertEquals(2, fieldNames(info).size());
    } finally {
      CollectionFixtures.safeDelete(client, name);
    }
  }

  @Test
  void testMixedDenseAndMultiVectorUpsertAndSearch() {
    String name = CollectionFixtures.uid("dmvs");
    try {
      client.createCollection(name, List.of(FieldConfigs.denseField(), FieldConfigs.mvField()));
      Collection collection = client.getCollection(name);

      List<ObjectItem> batch = new ArrayList<>();
      for (int i = 0; i < 10; i++) {
        batch.add(
            ObjectItem.builder("dmv_" + i)
                .vector(FieldConfigs.DENSE_FIELD, VectorGenerators.denseVec(i))
                .multiVector(FieldConfigs.MV_FIELD, VectorGenerators.multiVec(i))
                .build());
      }
      collection.upsert(batch);

      List<SearchHit> denseResults =
          collection
              .search(queryField(FieldConfigs.DENSE_FIELD, VectorGenerators.denseVec(0), 5))
              .get(FieldConfigs.DENSE_FIELD);
      assertEquals(5, denseResults.size());

      List<SearchHit> mvResults =
          collection.search(queryField(FieldConfigs.MV_FIELD, VectorGenerators.multiVec(0), 5)).get(FieldConfigs.MV_FIELD);
      assertEquals(5, mvResults.size());
    } finally {
      CollectionFixtures.safeDelete(client, name);
    }
  }

  // -- Mixed: RRF search --------------------------------------------------------

  @Test
  void testMixedDenseAndMultiVectorRrfSearch() {
    String name = CollectionFixtures.uid("dmvr");
    try {
      client.createCollection(name, List.of(FieldConfigs.denseField(), FieldConfigs.mvField()));
      Collection collection = client.getCollection(name);
      List<ObjectItem> batch = new ArrayList<>();
      for (int i = 0; i < 10; i++) {
        batch.add(
            ObjectItem.builder("dmvr_" + i)
                .vector(FieldConfigs.DENSE_FIELD, VectorGenerators.denseVec(i))
                .multiVector(FieldConfigs.MV_FIELD, VectorGenerators.multiVec(i))
                .build());
      }
      collection.upsert(batch);

      Map<String, Map<String, Object>> fields = new LinkedHashMap<>();
      fields.putAll(queryField(FieldConfigs.DENSE_FIELD, VectorGenerators.denseVec(0), 10));
      fields.putAll(queryField(FieldConfigs.MV_FIELD, VectorGenerators.multiVec(0), 10));

      Map<String, List<SearchHit>> raw = collection.search(fields);
      List<SearchHit> results = Reranker.rerank(raw, 5);
      assertEquals(5, results.size());
      for (SearchHit r : results) {
        assertNotNull(r.getId());
      }
    } finally {
      CollectionFixtures.safeDelete(client, name);
    }
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
