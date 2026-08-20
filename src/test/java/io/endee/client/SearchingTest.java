package io.endee.client;

import static io.endee.client.support.FieldConfigs.DENSE_FIELD;
import static io.endee.client.support.VectorGenerators.N_VECTORS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.endee.client.support.CollectionFixtures;
import io.endee.client.support.CollectionFixtures.NamedCollection;
import io.endee.client.support.TestConfig;
import io.endee.client.support.VectorGenerators;
import io.endee.client.types.ObjectItem;
import io.endee.client.types.SearchHit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// Covers Collection.search: result structure, ordering, limit, ef_search, parameter bounds.
class SearchingTest {

  private NamedCollection namedCollection;
  private Collection collection;

  @BeforeEach
  void setUp() {
    namedCollection = CollectionFixtures.populatedDense();
    collection = namedCollection.collection();
  }

  @AfterEach
  void tearDown() {
    if (namedCollection != null) {
      CollectionFixtures.safeDelete(TestConfig.client(), namedCollection.name());
    }
  }

  // -- response structure -------------------------------------------------------

  @Test
  void searchReturnsMapContainingFieldKey() {
    Map<String, List<SearchHit>> response =
        collection.search(
            Map.of(DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", N_VECTORS)));
    assertTrue(response.containsKey(DENSE_FIELD));
    assertTrue(response.get(DENSE_FIELD) instanceof List);
  }

  @Test
  void searchResultHasRequiredFields() {
    List<SearchHit> results =
        collection
            .search(Map.of(DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", 1)))
            .get(DENSE_FIELD);
    assertTrue(results.size() >= 1);
    SearchHit hit = results.get(0);
    assertTrue(hit.getId() != null, "Missing id in result");
    // similarity is a primitive double; presence is implicit.
  }

  @Test
  void searchResultIdIsString() {
    List<SearchHit> results =
        collection
            .search(Map.of(DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", 1)))
            .get(DENSE_FIELD);
    assertTrue(results.get(0).getId() instanceof String);
  }

  @Test
  void searchResultsOrderedByDescendingSimilarity() {
    List<SearchHit> results =
        collection
            .search(Map.of(DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", 10)))
            .get(DENSE_FIELD);
    for (int i = 1; i < results.size(); i++) {
      assertTrue(
          results.get(i - 1).getSimilarity() >= results.get(i).getSimilarity(),
          "Results not sorted by descending similarity");
    }
  }

  @Test
  void searchMetaIsPresentInResults() {
    List<SearchHit> results =
        collection
            .search(Map.of(DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", 1)))
            .get(DENSE_FIELD);
    Map<String, Object> meta = results.get(0).getMeta();
    assertTrue(meta.containsKey("index"));
    assertTrue(meta.containsKey("text"));
  }

  @Test
  void searchMetaValuesMatchUpsertedData() {
    List<SearchHit> results =
        collection
            .search(Map.of(DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", 1)))
            .get(DENSE_FIELD);
    Map<String, Object> meta = results.get(0).getMeta();
    Object idx = meta.get("index");
    assertEquals("Document " + idx, meta.get("text"));
  }

  @Test
  void searchFilterFieldPresentWhenUpserted() {
    List<SearchHit> results =
        collection
            .search(
                Map.of(DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", 10)))
            .get(DENSE_FIELD);
    for (SearchHit r : results) {
      Map<String, Object> filter = r.getFilter() != null ? r.getFilter() : Map.of();
      for (String key : List.of("category", "score", "priority", "tags")) {
        assertTrue(filter.containsKey(key), "Missing filter key '" + key + "' in result");
      }
    }
  }

  // -- limit parameter ------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(ints = {1, 5, 10, 20, 30, 50})
  void searchLimitReturnsAtMostNResults(int limit) {
    List<SearchHit> results =
        collection
            .search(
                Map.of(DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", limit)))
            .get(DENSE_FIELD);
    assertTrue(results.size() <= limit);
  }

  @Test
  void searchLimit1ReturnsSingleResult() {
    List<SearchHit> results =
        collection
            .search(Map.of(DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", 1)))
            .get(DENSE_FIELD);
    assertEquals(1, results.size());
  }

  // -- ef_search parameter ---------------------------------------------------------

  @ParameterizedTest
  @ValueSource(ints = {32, 64, 128, 256, 512, 1024})
  void searchEfSearchParameterAccepted(int efSearch) {
    List<SearchHit> results =
        collection
            .search(
                Map.of(
                    DENSE_FIELD,
                    Map.of(
                        "query", VectorGenerators.denseVec(0),
                        "limit", 5,
                        "ef_search", efSearch)))
            .get(DENSE_FIELD);
    assertTrue(results instanceof List);
  }

  // -- per-field query format --------------------------------------------------------

  @Test
  void searchPerFieldQueryMapFormat() {
    List<SearchHit> results =
        collection
            .search(
                Map.of(
                    DENSE_FIELD,
                    Map.of(
                        "query", VectorGenerators.denseVec(0),
                        "limit", 5,
                        "ef_search", 64)))
            .get(DENSE_FIELD);
    assertTrue(results.size() <= 5);
  }

  // -- edge cases -----------------------------------------------------------------

  @Test
  void searchLimitAtMaxReturnsWithoutError() {
    List<SearchHit> results =
        collection
            .search(
                Map.of(DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", 4096)))
            .get(DENSE_FIELD);
    assertTrue(results instanceof List);
  }

  @Test
  void searchEmptyFieldsMapRaises() {
    assertThrows(IllegalArgumentException.class, () -> collection.search(Map.of()));
  }

  @Test
  void searchRrfSingleFieldDoesNotError() {
    Map<String, List<SearchHit>> raw =
        collection.search(
            Map.of(DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", 5)));
    List<SearchHit> fused = Reranker.rerank(raw, 5);
    assertTrue(fused instanceof List);
  }

  // -- meta round-trip --------------------------------------------------------------

  @Test
  void searchMetaContentRoundTrips() {
    NamedCollection empty = CollectionFixtures.emptyDense();
    try {
      Collection c = empty.collection();
      Map<String, Object> payload = Map.of("title", "test doc", "count", 7, "flag", true);
      c.upsert(
          List.of(
              ObjectItem.builder("meta_rt")
                  .vector(DENSE_FIELD, VectorGenerators.denseVec(77))
                  .meta(payload)
                  .build()));
      List<SearchHit> results =
          c.search(Map.of(DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(77), "limit", 1)))
              .get(DENSE_FIELD);
      assertEquals("meta_rt", results.get(0).getId());
      assertEquals("test doc", results.get(0).getMeta().get("title"));
      assertEquals(7, ((Number) results.get(0).getMeta().get("count")).intValue());
      assertEquals(Boolean.TRUE, results.get(0).getMeta().get("flag"));
    } finally {
      CollectionFixtures.safeDelete(TestConfig.client(), empty.name());
    }
  }
}
