package io.endee.client;

import static io.endee.client.support.FieldConfigs.DENSE_FIELD;
import static io.endee.client.support.VectorGenerators.N_VECTORS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.endee.client.support.CollectionFixtures;
import io.endee.client.support.CollectionFixtures.NamedCollection;
import io.endee.client.support.TestConfig;
import io.endee.client.support.VectorGenerators;
import io.endee.client.types.SearchHit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// Covers prefilterThreshold (1,000-1,000,000) and boostPercentage (0-100) on search.
class FilterParamsTest {

  private static final int DEFAULT_EF_SEARCH = 128;

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

  private Map<String, Map<String, Object>> denseQuery() {
    return Map.of(DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", N_VECTORS));
  }

  // -- prefilter_cardinality_threshold ------------------------------------------

  @Test
  void prefilterThresholdAccepted() {
    assertDoesNotThrow(
        () ->
            collection.search(
                denseQuery(),
                List.of(Map.of("category", Map.of("$eq", "A"))),
                DEFAULT_EF_SEARCH,
                10000,
                null));
  }

  @Test
  void prefilterThresholdReturnsResults() {
    List<SearchHit> results =
        collection
            .search(
                denseQuery(),
                List.of(Map.of("category", Map.of("$eq", "A"))),
                DEFAULT_EF_SEARCH,
                10000,
                null)
            .get(DENSE_FIELD);
    assertTrue(results.size() > 0);
  }

  @Test
  void prefilterThresholdFilterCorrectness() {
    List<SearchHit> results =
        collection
            .search(
                denseQuery(),
                List.of(Map.of("category", Map.of("$eq", "B"))),
                DEFAULT_EF_SEARCH,
                10000,
                null)
            .get(DENSE_FIELD);
    assertTrue(results.size() > 0);
    for (SearchHit r : results) {
      Map<String, Object> flt = r.getFilter() != null ? r.getFilter() : Map.of();
      assertEquals("B", flt.get("category"), "Expected 'B', got " + flt.get("category"));
    }
  }

  @Test
  void prefilterThresholdMinBoundary() {
    assertDoesNotThrow(
        () ->
            collection.search(
                denseQuery(),
                List.of(Map.of("category", Map.of("$eq", "A"))),
                DEFAULT_EF_SEARCH,
                1000,
                null));
  }

  @Test
  void prefilterThresholdMaxBoundary() {
    assertDoesNotThrow(
        () ->
            collection.search(
                denseQuery(),
                List.of(Map.of("category", Map.of("$eq", "A"))),
                DEFAULT_EF_SEARCH,
                1000000,
                null));
  }

  @Test
  void prefilterThresholdBelowMinRaises() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            collection.search(
                denseQuery(),
                List.of(Map.of("category", Map.of("$eq", "A"))),
                DEFAULT_EF_SEARCH,
                999,
                null));
  }

  @Test
  void prefilterThresholdAboveMaxRaises() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            collection.search(
                denseQuery(),
                List.of(Map.of("category", Map.of("$eq", "A"))),
                DEFAULT_EF_SEARCH,
                1000001,
                null));
  }

  // -- filter_boost_percentage --------------------------------------------------

  @Test
  void filterBoostAccepted() {
    assertDoesNotThrow(
        () ->
            collection.search(
                denseQuery(),
                List.of(Map.of("category", Map.of("$eq", "A"))),
                DEFAULT_EF_SEARCH,
                null,
                50));
  }

  @Test
  void filterBoostReturnsResults() {
    List<SearchHit> results =
        collection
            .search(
                denseQuery(),
                List.of(Map.of("category", Map.of("$eq", "A"))),
                DEFAULT_EF_SEARCH,
                null,
                50)
            .get(DENSE_FIELD);
    assertTrue(results.size() > 0);
  }

  @Test
  void filterBoostZeroAccepted() {
    assertDoesNotThrow(
        () ->
            collection.search(
                denseQuery(),
                List.of(Map.of("category", Map.of("$eq", "A"))),
                DEFAULT_EF_SEARCH,
                null,
                0));
  }

  @Test
  void filterBoost100Accepted() {
    assertDoesNotThrow(
        () ->
            collection.search(
                denseQuery(),
                List.of(Map.of("category", Map.of("$eq", "A"))),
                DEFAULT_EF_SEARCH,
                null,
                100));
  }

  @Test
  void filterBoostBelowMinRaises() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            collection.search(
                denseQuery(),
                List.of(Map.of("category", Map.of("$eq", "A"))),
                DEFAULT_EF_SEARCH,
                null,
                -1));
  }

  @Test
  void filterBoostAboveMaxRaises() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            collection.search(
                denseQuery(),
                List.of(Map.of("category", Map.of("$eq", "A"))),
                DEFAULT_EF_SEARCH,
                null,
                101));
  }

  // -- combined and edge-case tests ---------------------------------------------

  @Test
  void bothParamsTogether() {
    List<SearchHit> results =
        collection
            .search(
                denseQuery(),
                List.of(Map.of("category", Map.of("$eq", "A"))),
                DEFAULT_EF_SEARCH,
                10000,
                25)
            .get(DENSE_FIELD);
    assertTrue(results instanceof List);
  }

  @Test
  void filterParamsWithoutFilter() {
    List<SearchHit> results =
        collection.search(denseQuery(), null, DEFAULT_EF_SEARCH, 10000, 50).get(DENSE_FIELD);
    assertTrue(results instanceof List);
    assertTrue(results.size() > 0);
  }
}
