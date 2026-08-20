package io.endee.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.endee.client.support.CollectionFixtures;
import io.endee.client.support.CollectionFixtures.NamedCollection;
import io.endee.client.support.FieldConfigs;
import io.endee.client.support.TestConfig;
import io.endee.client.support.VectorGenerators;
import io.endee.client.types.ObjectInfo;
import io.endee.client.types.ObjectItem;
import io.endee.client.types.SparseData;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// Covers Collection.getObjects(): return shape, round-trips, non-existent and mixed ids.
class GetObjectsTest {

  private Endee client;
  private NamedCollection populated;

  @BeforeEach
  void setUp() {
    client = TestConfig.client();
    populated = CollectionFixtures.populatedDense();
  }

  @AfterEach
  void tearDown() {
    CollectionFixtures.safeDelete(client, populated.name());
  }

  // -- return structure ---------------------------------------------------------

  @Test
  void getObjectsReturnsList() {
    List<ObjectInfo> result = populated.collection().getObjects(List.of("vec_0000"));
    assertNotNull(result);
  }

  @Test
  void getObjectsSingleIdReturnsOneObject() {
    List<ObjectInfo> result = populated.collection().getObjects(List.of("vec_0000"));
    assertEquals(1, result.size());
  }

  @Test
  void getObjectsMultipleIdsReturnAll() {
    List<String> ids = List.of("vec_0001", "vec_0002", "vec_0003");
    List<ObjectInfo> result = populated.collection().getObjects(ids);
    assertEquals(ids.size(), result.size());
  }

  @Test
  void getObjectsResultHasRequiredFields() {
    List<ObjectInfo> result = populated.collection().getObjects(List.of("vec_0005"));
    ObjectInfo obj = result.get(0);
    assertNotNull(obj.getId(), "Missing 'id' in getObjects result");
    assertNotNull(obj.getMeta(), "Missing 'meta' in getObjects result");
    assertNotNull(obj.getFilter(), "Missing 'filter' in getObjects result");
    assertNotNull(obj.getVectors(), "Missing 'vectors' in getObjects result");
    assertNotNull(obj.getSparses(), "Missing 'sparses' in getObjects result");
    assertNotNull(obj.getMultiVectors(), "Missing 'multiVectors' in getObjects result");
  }

  @Test
  void getObjectsIdMatchesRequested() {
    List<ObjectInfo> result = populated.collection().getObjects(List.of("vec_0010"));
    assertEquals("vec_0010", result.get(0).getId());
  }

  // -- meta round-trip ------------------------------------------------------------

  @Test
  void getObjectsMetaRoundTrips() {
    NamedCollection empty = CollectionFixtures.emptyDense();
    try {
      Map<String, Object> payload = Map.of("title", "round-trip doc", "count", 42, "flag", true);
      empty
          .collection()
          .upsert(
              List.of(
                  ObjectItem.builder("rt1")
                      .meta(payload)
                      .vector(FieldConfigs.DENSE_FIELD, VectorGenerators.denseVec(7))
                      .build()));
      List<ObjectInfo> result = empty.collection().getObjects(List.of("rt1"));
      assertEquals(1, result.size());
      Map<String, Object> meta = result.get(0).getMeta();
      assertEquals("round-trip doc", meta.get("title"));
      assertEquals(42, ((Number) meta.get("count")).intValue());
      assertEquals(true, meta.get("flag"));
    } finally {
      CollectionFixtures.safeDelete(client, empty.name());
    }
  }

  @Test
  void getObjectsMetaIndexMatchesUpsert() {
    List<ObjectInfo> result = populated.collection().getObjects(List.of("vec_0007"));
    Map<String, Object> meta = result.get(0).getMeta();
    assertEquals(7, ((Number) meta.get("index")).intValue());
    assertEquals("Document 7", meta.get("text"));
  }

  // -- filter round-trip ------------------------------------------------------------

  @Test
  void getObjectsFilterPresent() {
    List<ObjectInfo> result = populated.collection().getObjects(List.of("vec_0003"));
    Map<String, Object> filter = result.get(0).getFilter();
    assertNotNull(filter);
    assertTrue(filter.containsKey("category"));
  }

  @Test
  void getObjectsFilterValuesCorrect() {
    List<ObjectInfo> result = populated.collection().getObjects(List.of("vec_0006"));
    Map<String, Object> filter = result.get(0).getFilter();
    // vec_0006: i=6, category = A (6%3==0), tags = important (even)
    assertEquals("A", filter.get("category"));
    assertEquals("important", filter.get("tags"));
    assertEquals(6, ((Number) filter.get("score")).intValue());
  }

  // -- vector round-trip ------------------------------------------------------------

  @Test
  void getObjectsVectorsPresentForDenseField() {
    List<ObjectInfo> result = populated.collection().getObjects(List.of("vec_0000"));
    Map<String, double[]> vectors = result.get(0).getVectors();
    assertNotNull(vectors);
    assertTrue(vectors.containsKey(FieldConfigs.DENSE_FIELD));
  }

  @Test
  void getObjectsVectorHasCorrectDimension() {
    List<ObjectInfo> result = populated.collection().getObjects(List.of("vec_0000"));
    double[] vec = result.get(0).getVectors().get(FieldConfigs.DENSE_FIELD);
    assertNotNull(vec);
    assertEquals(VectorGenerators.DIM, vec.length);
  }

  // -- non-existent IDs ------------------------------------------------------------

  @Test
  void getObjectsNonexistentIdReturnsEmpty() {
    List<ObjectInfo> result = populated.collection().getObjects(List.of("this_id_does_not_exist_xyz"));
    assertTrue(result.isEmpty());
  }

  @Test
  void getObjectsMixExistingAndNonexistent() {
    List<ObjectInfo> result =
        populated.collection().getObjects(List.of("vec_0000", "no_such_id_xyz", "vec_0001"));
    List<String> returnedIds = result.stream().map(ObjectInfo::getId).toList();
    assertTrue(returnedIds.contains("vec_0000"));
    assertTrue(returnedIds.contains("vec_0001"));
    assertFalse(returnedIds.contains("no_such_id_xyz"));
  }

  // -- after upsert round-trip --------------------------------------------------------

  @Test
  void getObjectsRetrievesUpsertedObject() {
    NamedCollection empty = CollectionFixtures.emptyDense();
    try {
      double[] vec = VectorGenerators.denseVec(100);
      empty
          .collection()
          .upsert(
              List.of(
                  ObjectItem.builder("obj_rt")
                      .meta(Map.of("msg", "hello"))
                      .filter(Map.of("cat", "X"))
                      .vector(FieldConfigs.DENSE_FIELD, vec)
                      .build()));
      List<ObjectInfo> result = empty.collection().getObjects(List.of("obj_rt"));
      assertEquals(1, result.size());
      ObjectInfo obj = result.get(0);
      assertEquals("obj_rt", obj.getId());
      assertEquals("hello", obj.getMeta().get("msg"));
      assertEquals("X", obj.getFilter().get("cat"));
    } finally {
      CollectionFixtures.safeDelete(client, empty.name());
    }
  }

  @Test
  void getObjectsAfterDeleteReturnsEmpty() {
    populated.collection().deleteObject("vec_0020");
    List<ObjectInfo> result = populated.collection().getObjects(List.of("vec_0020"));
    assertTrue(result.isEmpty());
  }

  // -- multiple objects at once ----------------------------------------------------

  @Test
  void getObjectsBatchFetchAllPresent() {
    List<String> ids =
        java.util.stream.IntStream.range(0, VectorGenerators.N_VECTORS)
            .mapToObj(i -> String.format("vec_%04d", i))
            .toList();
    List<ObjectInfo> result = populated.collection().getObjects(ids);
    assertEquals(VectorGenerators.N_VECTORS, result.size());
  }

  @Test
  void getObjectsBatchIdsAreUnique() {
    List<String> ids =
        java.util.stream.IntStream.range(0, 10).mapToObj(i -> String.format("vec_%04d", i)).toList();
    List<ObjectInfo> result = populated.collection().getObjects(ids);
    List<String> returnedIds = result.stream().map(ObjectInfo::getId).toList();
    assertEquals(returnedIds.size(), java.util.Set.copyOf(returnedIds).size());
  }

  // -- sparse collection ------------------------------------------------------------

  @Test
  void getObjectsSparseCollectionSparsesPresent() {
    NamedCollection sparse = CollectionFixtures.populatedSparse();
    try {
      List<ObjectInfo> result = sparse.collection().getObjects(List.of("sp_0000"));
      assertEquals(1, result.size());
      Map<String, SparseData> sparses = result.get(0).getSparses();
      assertNotNull(sparses);
      assertTrue(sparses.containsKey(FieldConfigs.SPARSE_FIELD));
    } finally {
      CollectionFixtures.safeDelete(client, sparse.name());
    }
  }

  @Test
  void getObjectsSparseHasIndicesAndValues() {
    NamedCollection sparse = CollectionFixtures.populatedSparse();
    try {
      List<ObjectInfo> result = sparse.collection().getObjects(List.of("sp_0001"));
      SparseData sd = result.get(0).getSparses().get(FieldConfigs.SPARSE_FIELD);
      assertNotNull(sd.getIndices());
      assertNotNull(sd.getValues());
      assertEquals(sd.getIndices().length, sd.getValues().length);
    } finally {
      CollectionFixtures.safeDelete(client, sparse.name());
    }
  }

  // -- multi_vector collection --------------------------------------------------------

  @Test
  void getObjectsMultiVectorCollectionPresent() {
    NamedCollection mv = CollectionFixtures.populatedMv();
    try {
      List<ObjectInfo> result = mv.collection().getObjects(List.of("mv_0000"));
      assertEquals(1, result.size());
      Map<String, double[][]> multiVectors = result.get(0).getMultiVectors();
      assertNotNull(multiVectors);
      assertTrue(multiVectors.containsKey(FieldConfigs.MV_FIELD));
    } finally {
      CollectionFixtures.safeDelete(client, mv.name());
    }
  }

  @Test
  void getObjectsMultiVectorIsListOfLists() {
    NamedCollection mv = CollectionFixtures.populatedMv();
    try {
      List<ObjectInfo> result = mv.collection().getObjects(List.of("mv_0000"));
      double[][] tokens = result.get(0).getMultiVectors().get(FieldConfigs.MV_FIELD);
      assertNotNull(tokens);
      assertTrue(tokens.length > 0);
      assertNotNull(tokens[0]);
    } finally {
      CollectionFixtures.safeDelete(client, mv.name());
    }
  }
}
