package io.endee.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

// Covers collection lifecycle: createCollection, listCollections, getCollection, describe, deleteCollection.
class CollectionManagementTest {

  private Endee client;

  @BeforeEach
  void setUp() {
    client = TestConfig.client();
  }

  // -- createCollection --------------------------------------------------------

  @Test
  void createCollectionReturnsMapWithName() {
    String name = CollectionFixtures.uid("cret");
    try {
      Map<String, Object> result = client.createCollection(name, List.of(FieldConfigs.denseField()));
      assertNotNull(result);
      assertEquals(name, result.get("name"));
    } finally {
      CollectionFixtures.safeDelete(client, name);
    }
  }

  @Test
  void createCollectionWithMapFieldConfig() {
    String name = CollectionFixtures.uid("dictf");
    try {
      Map<String, Object> params = new LinkedHashMap<>();
      params.put("dimension", VectorGenerators.DIM);
      params.put("space_type", "cosine");
      params.put("precision", "int8");
      Map<String, Object> field = new LinkedHashMap<>();
      field.put("name", FieldConfigs.DENSE_FIELD);
      field.put("type", "vector");
      field.put("params", params);

      Map<String, Object> result = client.createCollection(name, List.of(field));
      assertNotNull(result);
      assertTrue(CollectionFixtures.collectionNames(client).contains(name));
    } finally {
      CollectionFixtures.safeDelete(client, name);
    }
  }

  @Test
  void createHybridCollectionDefaultSparse() {
    String name = CollectionFixtures.uid("hyb");
    try {
      client.createCollection(name, List.of(FieldConfigs.denseField(), FieldConfigs.sparseField()));
      Collection collection = client.getCollection(name);
      Map<String, Object> info = collection.describe();

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> fields = (List<Map<String, Object>>) info.get("fields");
      assertEquals(2, fields.size(), "Expected 2 fields, got " + fields.size());

      List<String> fieldNames = fields.stream().map(f -> (String) f.get("name")).toList();
      assertTrue(fieldNames.contains(FieldConfigs.DENSE_FIELD));
      assertTrue(fieldNames.contains(FieldConfigs.SPARSE_FIELD));

      Map<String, String> fieldTypes = new LinkedHashMap<>();
      for (Map<String, Object> f : fields) {
        fieldTypes.put((String) f.get("name"), (String) f.get("type"));
      }
      assertEquals("vector", fieldTypes.get(FieldConfigs.DENSE_FIELD));
      assertEquals("sparse", fieldTypes.get(FieldConfigs.SPARSE_FIELD));
    } finally {
      CollectionFixtures.safeDelete(client, name);
    }
  }

  @Test
  void createHybridCollectionBm25() {
    String name = CollectionFixtures.uid("bm25");
    try {
      client.createCollection(
          name, List.of(FieldConfigs.denseField(), FieldConfigs.sparseField("endee_bm25")));
      Collection collection = client.getCollection(name);
      Map<String, Object> info = collection.describe();
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> fields = (List<Map<String, Object>>) info.get("fields");
      assertEquals(2, fields.size());
    } catch (Exception e) {
      Assumptions.abort("endee_bm25 not supported on this server: " + e.getMessage());
    } finally {
      CollectionFixtures.safeDelete(client, name);
    }
  }

  static Stream<Arguments> precisionSpaceCombinations() {
    List<Arguments> args = new ArrayList<>();
    for (String precision : FieldConfigs.ALL_PRECISIONS) {
      for (String spaceType : FieldConfigs.ALL_SPACE_TYPES) {
        args.add(Arguments.of(precision, spaceType));
      }
    }
    return args.stream();
  }

  @ParameterizedTest
  @MethodSource("precisionSpaceCombinations")
  void createCollectionPrecisionSpaceCombinations(String precision, String spaceType) {
    String name = CollectionFixtures.uid("combo");
    try {
      Map<String, Object> result =
          client.createCollection(
              name, List.of(FieldConfigs.denseField(VectorGenerators.DIM, spaceType, precision)));
      assertNotNull(result);
      assertTrue(
          CollectionFixtures.collectionNames(client).contains(name),
          "Collection '" + name + "' missing from listCollections");
    } finally {
      CollectionFixtures.safeDelete(client, name);
    }
  }

  static Stream<Arguments> hnswParams() {
    return Stream.of(
        Arguments.of(4, 32),
        Arguments.of(8, 64),
        Arguments.of(16, 128),
        Arguments.of(32, 256),
        Arguments.of(64, 512));
  }

  @ParameterizedTest
  @MethodSource("hnswParams")
  void createCollectionCustomHnswParams(int m, int efConstruct) {
    String name = CollectionFixtures.uid("hnsw");
    try {
      client.createCollection(
          name,
          List.of(
              FieldConfigs.denseField(VectorGenerators.DIM, "cosine", "int8", m, efConstruct)));
      Collection collection = client.getCollection(name);
      Map<String, Object> info = collection.describe();
      Map<String, Object> field = findField(info, FieldConfigs.DENSE_FIELD);
      assertNotNull(field, "Field '" + FieldConfigs.DENSE_FIELD + "' not found in describe()");

      @SuppressWarnings("unchecked")
      Map<String, Object> params = (Map<String, Object>) field.getOrDefault("params", Map.of());
      Object mVal = params.containsKey("M") ? params.get("M") : params.get("m");
      assertEquals(m, ((Number) mVal).intValue(), "Expected m=" + m + ", got params=" + params);

      Object efVal =
          params.containsKey("ef_construct") ? params.get("ef_construct") : params.get("ef_con");
      assertEquals(
          efConstruct,
          ((Number) efVal).intValue(),
          "Expected ef_construct=" + efConstruct + ", got params=" + params);
    } finally {
      CollectionFixtures.safeDelete(client, name);
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {2, 8, 64, 128, 512})
  void createCollectionVariousDimensions(int dimension) {
    String name = CollectionFixtures.uid("dim");
    try {
      client.createCollection(name, List.of(FieldConfigs.denseField(dimension, "cosine", "int8")));
      Collection collection = client.getCollection(name);
      Map<String, Object> info = collection.describe();
      Map<String, Object> field = findField(info, FieldConfigs.DENSE_FIELD);
      assertNotNull(field);

      @SuppressWarnings("unchecked")
      Map<String, Object> params = (Map<String, Object>) field.getOrDefault("params", Map.of());
      assertEquals(dimension, ((Number) params.get("dimension")).intValue());
    } finally {
      CollectionFixtures.safeDelete(client, name);
    }
  }

  // -- listCollections -----------------------------------------------------------

  @Test
  void listCollectionsReturnsList() {
    List<Map<String, Object>> result = client.listCollections();
    assertNotNull(result);
  }

  @Test
  void listCollectionsItemsHaveNameKey() {
    String name = CollectionFixtures.uid("lname");
    try {
      client.createCollection(name, List.of(FieldConfigs.denseField()));
      List<Map<String, Object>> items = client.listCollections();
      for (Map<String, Object> item : items) {
        assertTrue(item.containsKey("name"), "Item missing 'name' key: " + item);
      }
    } finally {
      CollectionFixtures.safeDelete(client, name);
    }
  }

  @Test
  void listCollectionsContainsCreatedCollection() {
    String name = CollectionFixtures.uid("list");
    try {
      client.createCollection(name, List.of(FieldConfigs.denseField()));
      assertTrue(CollectionFixtures.collectionNames(client).contains(name));
    } finally {
      CollectionFixtures.safeDelete(client, name);
    }
  }

  @Test
  void listCollectionsDoesNotContainDeletedCollection() {
    String name = CollectionFixtures.uid("del");
    client.createCollection(name, List.of(FieldConfigs.denseField()));
    try {
      client.deleteCollection(name);
      assertFalse(CollectionFixtures.collectionNames(client).contains(name));
    } finally {
      CollectionFixtures.safeDelete(client, name);
    }
  }

  @Test
  void listCollectionsMultipleCollectionsAllVisible() {
    List<String> names = List.of(CollectionFixtures.uid("multi"), CollectionFixtures.uid("multi"),
        CollectionFixtures.uid("multi"));
    try {
      for (String n : names) {
        client.createCollection(n, List.of(FieldConfigs.denseField()));
      }
      List<String> listed = CollectionFixtures.collectionNames(client);
      for (String n : names) {
        assertTrue(listed.contains(n), "Collection '" + n + "' missing from listCollections");
      }
    } finally {
      for (String n : names) {
        CollectionFixtures.safeDelete(client, n);
      }
    }
  }

  // -- getCollection ---------------------------------------------------------

  @Test
  void getCollectionReturnsCollectionInstance() {
    String name = CollectionFixtures.uid("inst");
    try {
      client.createCollection(name, List.of(FieldConfigs.denseField()));
      Collection collection = client.getCollection(name);
      assertInstanceOf(Collection.class, collection);
    } finally {
      CollectionFixtures.safeDelete(client, name);
    }
  }

  @Test
  void getCollectionReturnsCorrectName() {
    String name = CollectionFixtures.uid("attrs");
    try {
      client.createCollection(name, List.of(FieldConfigs.denseField(VectorGenerators.DIM, "l2", "float16")));
      Collection collection = client.getCollection(name);
      assertEquals(name, collection.toString());
    } finally {
      CollectionFixtures.safeDelete(client, name);
    }
  }

  // -- describe ---------------------------------------------------------------

  @Test
  void describeNameMatchesCreation() {
    String name = CollectionFixtures.uid("desc");
    try {
      client.createCollection(name, List.of(FieldConfigs.denseField()));
      Collection collection = client.getCollection(name);
      Map<String, Object> info = collection.describe();
      assertEquals(name, info.get("name"));
    } finally {
      CollectionFixtures.safeDelete(client, name);
    }
  }

  @Test
  void describeFieldsIsList() {
    String name = CollectionFixtures.uid("fld");
    try {
      client.createCollection(name, List.of(FieldConfigs.denseField()));
      Collection collection = client.getCollection(name);
      Map<String, Object> info = collection.describe();
      assertInstanceOf(List.class, info.get("fields"));
      assertEquals(1, ((List<?>) info.get("fields")).size());
    } finally {
      CollectionFixtures.safeDelete(client, name);
    }
  }

  @Test
  void describeFieldEntriesHaveNameKey() {
    NamedCollection nc = CollectionFixtures.emptyDense();
    try {
      Map<String, Object> info = nc.collection().describe();
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> fields = (List<Map<String, Object>>) info.get("fields");
      for (Map<String, Object> field : fields) {
        assertTrue(field.containsKey("name"), "Field entry missing 'name': " + field);
      }
    } finally {
      CollectionFixtures.safeDelete(client, nc.name());
    }
  }

  @Test
  void describeFieldEntriesHaveTypeKey() {
    NamedCollection nc = CollectionFixtures.emptyDense();
    try {
      Map<String, Object> info = nc.collection().describe();
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> fields = (List<Map<String, Object>>) info.get("fields");
      for (Map<String, Object> field : fields) {
        assertTrue(field.containsKey("type"), "Field entry missing 'type': " + field);
      }
    } finally {
      CollectionFixtures.safeDelete(client, nc.name());
    }
  }

  @Test
  void describeFieldParamsReflectCreation() {
    String name = CollectionFixtures.uid("params");
    try {
      client.createCollection(name, List.of(FieldConfigs.denseField(32, "l2", "int8")));
      Collection collection = client.getCollection(name);
      Map<String, Object> info = collection.describe();
      Map<String, Object> field = findField(info, FieldConfigs.DENSE_FIELD);
      assertNotNull(field, "Field '" + FieldConfigs.DENSE_FIELD + "' not found in describe()");

      @SuppressWarnings("unchecked")
      Map<String, Object> params = (Map<String, Object>) field.getOrDefault("params", Map.of());
      assertEquals(32, ((Number) params.get("dimension")).intValue());
      assertEquals("l2", params.get("space_type"));
    } finally {
      CollectionFixtures.safeDelete(client, name);
    }
  }

  @Test
  void describeHybridCollectionHasTwoFields() {
    String name = CollectionFixtures.uid("hdesc");
    try {
      client.createCollection(name, List.of(FieldConfigs.denseField(), FieldConfigs.sparseField()));
      Collection collection = client.getCollection(name);
      Map<String, Object> info = collection.describe();
      assertEquals(2, ((List<?>) info.get("fields")).size());
    } finally {
      CollectionFixtures.safeDelete(client, name);
    }
  }

  // -- deleteCollection ---------------------------------------------------------

  @Test
  void deleteCollectionReturnsResponse() {
    String name = CollectionFixtures.uid("delok");
    client.createCollection(name, List.of(FieldConfigs.denseField()));
    try {
      Map<String, Object> result = client.deleteCollection(name);
      assertNotNull(result);
    } finally {
      CollectionFixtures.safeDelete(client, name);
    }
  }

  @Test
  void deleteCollectionResponseHasExpectedKey() {
    String name = CollectionFixtures.uid("delkey");
    client.createCollection(name, List.of(FieldConfigs.denseField()));
    try {
      Map<String, Object> result = client.deleteCollection(name);
      assertTrue(
          result.containsKey("message") || result.containsKey("deleted") || result.containsKey("name"),
          "Unexpected delete response keys: " + result.keySet());
    } finally {
      CollectionFixtures.safeDelete(client, name);
    }
  }

  @Test
  void deleteCollectionRemovesFromList() {
    String name = CollectionFixtures.uid("delist");
    client.createCollection(name, List.of(FieldConfigs.denseField()));
    try {
      client.deleteCollection(name);
      assertFalse(CollectionFixtures.collectionNames(client).contains(name));
    } finally {
      CollectionFixtures.safeDelete(client, name);
    }
  }

  // -- Collection toString() -----------------------------------------------------

  @Test
  void collectionToStringReturnsName() {
    String name = CollectionFixtures.uid("str");
    try {
      client.createCollection(name, List.of(FieldConfigs.denseField()));
      Collection collection = client.getCollection(name);
      assertEquals(name, collection.toString());
    } finally {
      CollectionFixtures.safeDelete(client, name);
    }
  }

  // -- minimum dimension (dim=2) upsert + search ----------------------------------

  @Test
  void upsertAndSearchMinimumDimension() {
    String name = CollectionFixtures.uid("d2");
    try {
      client.createCollection(name, List.of(FieldConfigs.denseField(2, "cosine", "int8")));
      Collection collection = client.getCollection(name);

      List<ObjectItem> batch = new ArrayList<>();
      for (int i = 0; i < 10; i++) {
        batch.add(
            ObjectItem.builder("v" + i)
                .vector(FieldConfigs.DENSE_FIELD, VectorGenerators.denseVec(2, i))
                .build());
      }
      Map<String, Object> result = collection.upsert(batch);
      assertEquals(10, ((Number) result.get("upserted")).intValue());

      Map<String, Map<String, Object>> queryFields = new LinkedHashMap<>();
      queryFields.put(
          FieldConfigs.DENSE_FIELD,
          Map.of("query", VectorGenerators.denseVec(2, 99), "limit", 5));
      List<SearchHit> hits = collection.search(queryFields).get(FieldConfigs.DENSE_FIELD);
      assertNotNull(hits);
      assertTrue(hits.size() > 0);
      for (SearchHit hit : hits) {
        assertNotNull(hit.getId());
      }
    } finally {
      CollectionFixtures.safeDelete(client, name);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> findField(Map<String, Object> describeInfo, String fieldName) {
    List<Map<String, Object>> fields = (List<Map<String, Object>>) describeInfo.get("fields");
    if (fields == null) {
      return null;
    }
    for (Map<String, Object> f : fields) {
      if (fieldName.equals(f.get("name"))) {
        return f;
      }
    }
    return null;
  }
}
