package io.endee.client;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.endee.client.support.CollectionFixtures;
import io.endee.client.support.CollectionFixtures.NamedCollection;
import io.endee.client.support.FieldConfigs;
import io.endee.client.support.TestConfig;
import io.endee.client.support.VectorGenerators;
import io.endee.client.types.SearchHit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

// Covers Collection.shrink(): response shape, empty/populated collections, after deletions.
class ShrinkTest {

  private final Endee client = TestConfig.client();
  private String toDelete;

  @AfterEach
  void tearDown() {
    if (toDelete != null) {
      CollectionFixtures.safeDelete(client, toDelete);
      toDelete = null;
    }
  }

  // -- dense collection ----------------------------------------------------------

  @Test
  void shrinkReturnsMap() {
    NamedCollection nc = CollectionFixtures.populatedDense();
    toDelete = nc.name();
    Map<String, Object> result = nc.collection().shrink();
    assertInstanceOf(Map.class, result);
  }

  @Test
  void shrinkOnEmptyCollectionReturnsMap() {
    NamedCollection nc = CollectionFixtures.emptyDense();
    toDelete = nc.name();
    Map<String, Object> result = nc.collection().shrink();
    assertInstanceOf(Map.class, result);
  }

  @Test
  void shrinkCollectionStillSearchableAfter() {
    NamedCollection nc = CollectionFixtures.populatedDense();
    toDelete = nc.name();
    Collection collection = nc.collection();
    collection.shrink();
    List<SearchHit> results =
        collection
            .search(Map.of(FieldConfigs.DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", 5)))
            .get(FieldConfigs.DENSE_FIELD);
    assertTrue(results.size() > 0);
  }

  @Test
  void shrinkAfterDeleteReturnsMap() {
    NamedCollection nc = CollectionFixtures.populatedDense();
    toDelete = nc.name();
    Collection collection = nc.collection();
    collection.deleteObject("vec_0000");
    collection.deleteObject("vec_0001");
    Map<String, Object> result = collection.shrink();
    assertInstanceOf(Map.class, result);
  }

  // -- multi_vector collection ---------------------------------------------------

  @Test
  void mvShrinkReturnsMap() {
    NamedCollection nc = CollectionFixtures.populatedMv();
    toDelete = nc.name();
    Map<String, Object> result = nc.collection().shrink();
    assertInstanceOf(Map.class, result);
  }

  @Test
  void mvShrinkCollectionStillSearchableAfter() {
    NamedCollection nc = CollectionFixtures.populatedMv();
    toDelete = nc.name();
    Collection collection = nc.collection();
    collection.shrink();
    List<SearchHit> results =
        collection
            .search(Map.of(FieldConfigs.MV_FIELD, Map.of("query", VectorGenerators.multiVec(0), "limit", 5)))
            .get(FieldConfigs.MV_FIELD);
    assertTrue(results.size() > 0);
  }

  @Test
  void mvShrinkAfterDeleteByFilter() {
    NamedCollection nc = CollectionFixtures.populatedMv();
    toDelete = nc.name();
    Collection collection = nc.collection();
    collection.deleteByFilter(List.of(Map.of("category", Map.of("$eq", "C"))));
    Map<String, Object> result = collection.shrink();
    assertInstanceOf(Map.class, result);
  }
}
