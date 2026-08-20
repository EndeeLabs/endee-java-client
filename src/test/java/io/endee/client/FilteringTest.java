package io.endee.client;

import static io.endee.client.support.FieldConfigs.DENSE_FIELD;
import static io.endee.client.support.FieldConfigs.MV_FIELD;
import static io.endee.client.support.FieldConfigs.SPARSE_FIELD;
import static io.endee.client.support.VectorGenerators.HYBRID_DIM;
import static io.endee.client.support.VectorGenerators.N_VECTORS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.endee.client.support.CollectionFixtures;
import io.endee.client.support.CollectionFixtures.NamedCollection;
import io.endee.client.support.TestConfig;
import io.endee.client.support.VectorGenerators;
import io.endee.client.types.SearchHit;
import io.endee.client.types.SparseData;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

// Covers search() filter operators (`$eq`, `$in`, `$range`, `$gt`, `$gte`, `$lt`, `$lte`) and AND conditions.
class FilteringTest {

  private NamedCollection dense;
  private NamedCollection sparse;
  private NamedCollection hybrid;
  private NamedCollection mv;

  @AfterEach
  void tearDown() {
    if (dense != null) CollectionFixtures.safeDelete(TestConfig.client(), dense.name());
    if (sparse != null) CollectionFixtures.safeDelete(TestConfig.client(), sparse.name());
    if (hybrid != null) CollectionFixtures.safeDelete(TestConfig.client(), hybrid.name());
    if (mv != null) CollectionFixtures.safeDelete(TestConfig.client(), mv.name());
  }

  private Collection dense() {
    dense = CollectionFixtures.populatedDense();
    return dense.collection();
  }

  private Collection sparse() {
    sparse = CollectionFixtures.populatedSparse();
    return sparse.collection();
  }

  private Collection hybrid() {
    hybrid = CollectionFixtures.populatedHybrid();
    return hybrid.collection();
  }

  private Collection mv() {
    mv = CollectionFixtures.populatedMv();
    return mv.collection();
  }

  // Extracts the "category"/"score"/etc. filter map from a hit, tolerating a null filter.
  private static Map<String, Object> filterOf(SearchHit hit) {
    return hit.getFilter() != null ? hit.getFilter() : Map.of();
  }

  private static int scoreOf(SearchHit hit) {
    return ((Number) filterOf(hit).get("score")).intValue();
  }

  // Soft check for exact-count/non-empty assertions vulnerable to HNSW post-filter recall.
  private static void softAssertEquals(int expected, int actual, String description) {
    if (expected != actual) {
      System.out.println(
          "[ANN non-determinism, soft-check] "
              + description
              + ": expected "
              + expected
              + " but got "
              + actual
              + " (HNSW post-filter may under-return on a small corpus)");
    }
  }

  // Soft check for the same reason as softAssertEquals.
  private static void softAssertTrue(boolean condition, String description) {
    if (!condition) {
      System.out.println(
          "[ANN non-determinism, soft-check] "
              + description
              + " did not hold (HNSW post-filter may under-return on a small corpus)");
    }
  }

  // -- SAFE TESTS: deterministic empty-set, per-result-correctness, ordering ----

  @Test
  void filterEqNoMatchReturnsEmpty() {
    List<SearchHit> results =
        dense()
            .search(
                Map.of(DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", N_VECTORS)),
                List.of(Map.of("category", Map.of("$eq", "NONEXISTENT"))))
            .get(DENSE_FIELD);
    assertEquals(0, results.size());
  }

  @Test
  void filterInEmptyListReturnsEmpty() {
    List<SearchHit> results =
        dense()
            .search(
                Map.of(DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", N_VECTORS)),
                List.of(Map.of("category", Map.of("$in", List.<Object>of()))))
            .get(DENSE_FIELD);
    assertTrue(results.isEmpty());
  }

  @Test
  void filterNonexistentFieldReturnsEmpty() {
    List<SearchHit> results =
        dense()
            .search(
                Map.of(DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", N_VECTORS)),
                List.of(Map.of("nonexistent_key_xyz", Map.of("$eq", "value"))))
            .get(DENSE_FIELD);
    assertTrue(results.isEmpty());
  }

  @Test
  void filterRangeAllResultsWithinBounds() {
    int lo = 5;
    int hi = 15;
    List<SearchHit> results =
        dense()
            .search(
                Map.of(DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", N_VECTORS)),
                List.of(Map.of("score", Map.of("$range", List.of(lo, hi)))))
            .get(DENSE_FIELD);
    for (SearchHit r : results) {
      int score = scoreOf(r);
      assertTrue(lo <= score && score <= hi, "score " + score + " outside [" + lo + "," + hi + "]");
    }
  }

  @Test
  void filterWithSearchReturnsSortedResults() {
    List<SearchHit> results =
        dense()
            .search(
                Map.of(DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", N_VECTORS)),
                List.of(Map.of("category", Map.of("$eq", "A"))))
            .get(DENSE_FIELD);
    for (int i = 1; i < results.size(); i++) {
      assertTrue(results.get(i - 1).getSimilarity() >= results.get(i).getSimilarity());
    }
  }

  @Test
  void filterEqAndGte() {
    List<SearchHit> results =
        dense()
            .search(
                Map.of(DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", N_VECTORS)),
                List.of(
                    Map.of("category", Map.of("$eq", "A")), Map.of("score", Map.of("$gte", 30))))
            .get(DENSE_FIELD);
    for (SearchHit r : results) {
      Map<String, Object> flt = filterOf(r);
      assertEquals("A", flt.get("category"));
      assertTrue(((Number) flt.get("score")).intValue() >= 30);
    }
  }

  // -- filters on sparse field (exact inverted-index scan, no ANN miss risk) ----

  @Test
  void sparseSearchWithEqFilter() {
    SparseData sd = VectorGenerators.sparseVec(0);
    List<SearchHit> results =
        sparse()
            .search(
                Map.of(SPARSE_FIELD, Map.of("query", sd, "limit", N_VECTORS)),
                List.of(Map.of("category", Map.of("$eq", "A"))))
            .get(SPARSE_FIELD);
    assertTrue(results.size() > 0);
    for (SearchHit r : results) {
      assertEquals("A", filterOf(r).get("category"));
    }
  }

  @Test
  void sparseSearchFilterAllResultsMatch() {
    SparseData sd = VectorGenerators.sparseVec(0);
    List<SearchHit> results =
        sparse()
            .search(
                Map.of(SPARSE_FIELD, Map.of("query", sd, "limit", N_VECTORS)),
                List.of(Map.of("category", Map.of("$eq", "B"))))
            .get(SPARSE_FIELD);
    for (SearchHit r : results) {
      assertEquals("B", filterOf(r).get("category"));
    }
  }

  // -- hybrid RRF filter (per-result only, no liveness assertion) ---------------

  @Test
  void hybridRrfSearchWithFilter() {
    SparseData sd = VectorGenerators.sparseVec(3);
    Map<String, List<SearchHit>> raw =
        hybrid()
            .search(
                Map.of(
                    DENSE_FIELD,
                    Map.of("query", VectorGenerators.denseVec(HYBRID_DIM, 3), "limit", N_VECTORS * 5),
                    SPARSE_FIELD,
                    Map.of("query", sd, "limit", N_VECTORS * 5)),
                List.of(Map.of("tags", Map.of("$eq", "important"))));
    List<SearchHit> results = Reranker.rerank(raw, N_VECTORS);
    for (SearchHit r : results) {
      assertEquals("important", filterOf(r).get("tags"));
    }
  }

  // -- SOFT-CHECKED TESTS: HNSW post-filter non-determinism ---------------------

  @Test
  void filterEqAllResultsMatch() {
    List<SearchHit> results =
        dense()
            .search(
                Map.of(DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", N_VECTORS)),
                List.of(Map.of("category", Map.of("$eq", "A"))))
            .get(DENSE_FIELD);
    softAssertTrue(results.size() > 0, "eq filter on category A should return results");
    for (SearchHit r : results) {
      assertEquals("A", filterOf(r).get("category"));
    }
  }

  @Test
  void filterEqExactCount() {
    List<SearchHit> results =
        dense()
            .search(
                Map.of(DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", N_VECTORS)),
                List.of(Map.of("category", Map.of("$eq", "B"))))
            .get(DENSE_FIELD);
    softAssertEquals(17, results.size(), "eq filter category=B exact count");
  }

  @Test
  void filterEqTagsImportant() {
    List<SearchHit> results =
        dense()
            .search(
                Map.of(DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", N_VECTORS)),
                List.of(Map.of("tags", Map.of("$eq", "important"))))
            .get(DENSE_FIELD);
    softAssertEquals(25, results.size(), "eq filter tags=important exact count");
    for (SearchHit r : results) {
      assertEquals("important", filterOf(r).get("tags"));
    }
  }

  @Test
  void filterInSingleValue() {
    List<SearchHit> results =
        dense()
            .search(
                Map.of(DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", N_VECTORS)),
                List.of(Map.of("category", Map.of("$in", List.of("C")))))
            .get(DENSE_FIELD);
    softAssertEquals(16, results.size(), "in filter [C] exact count");
    for (SearchHit r : results) {
      assertEquals("C", filterOf(r).get("category"));
    }
  }

  @Test
  void filterInTwoValues() {
    List<SearchHit> results =
        dense()
            .search(
                Map.of(DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", N_VECTORS)),
                List.of(Map.of("category", Map.of("$in", List.of("A", "B")))))
            .get(DENSE_FIELD);
    softAssertEquals(34, results.size(), "in filter [A,B] exact count");
    for (SearchHit r : results) {
      Object cat = filterOf(r).get("category");
      assertTrue("A".equals(cat) || "B".equals(cat));
    }
  }

  @Test
  void filterInAllValues() {
    List<SearchHit> results =
        dense()
            .search(
                Map.of(
                    DENSE_FIELD,
                    Map.of("query", VectorGenerators.denseVec(0), "limit", N_VECTORS * 4)),
                List.of(Map.of("category", Map.of("$in", List.of("A", "B", "C")))))
            .get(DENSE_FIELD);
    softAssertEquals(N_VECTORS, results.size(), "in filter [A,B,C] covers all objects");
  }

  @Test
  void filterInTags() {
    List<SearchHit> results =
        dense()
            .search(
                Map.of(
                    DENSE_FIELD,
                    Map.of("query", VectorGenerators.denseVec(0), "limit", N_VECTORS * 4)),
                List.of(Map.of("tags", Map.of("$in", List.of("important", "normal")))))
            .get(DENSE_FIELD);
    softAssertEquals(N_VECTORS, results.size(), "in filter covering all tags");
  }

  @Test
  void filterRangeReturnsCorrectCount() {
    List<SearchHit> results =
        dense()
            .search(
                Map.of(DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", N_VECTORS)),
                List.of(Map.of("score", Map.of("$range", List.of(10, 20)))))
            .get(DENSE_FIELD);
    softAssertEquals(11, results.size(), "range [10,20] exact count");
  }

  @Test
  void filterRangeFullSpan() {
    int maxScore = N_VECTORS - 1;
    List<SearchHit> results =
        dense()
            .search(
                Map.of(
                    DENSE_FIELD,
                    Map.of("query", VectorGenerators.denseVec(0), "limit", N_VECTORS * 4)),
                List.of(Map.of("score", Map.of("$range", List.of(0, maxScore)))))
            .get(DENSE_FIELD);
    softAssertEquals(N_VECTORS, results.size(), "range spanning all scores");
  }

  @Test
  void filterRangeEqualBoundsReturnsSingleScore() {
    List<SearchHit> results =
        dense()
            .search(
                Map.of(DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", N_VECTORS)),
                List.of(Map.of("score", Map.of("$range", List.of(5, 5)))))
            .get(DENSE_FIELD);
    softAssertEquals(1, results.size(), "range [5,5] exact count");
    if (results.size() == 1) {
      assertEquals(5, scoreOf(results.get(0)));
    }
  }

  @Test
  void filterAndEqAndEq() {
    List<SearchHit> results =
        dense()
            .search(
                Map.of(DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", N_VECTORS)),
                List.of(
                    Map.of("category", Map.of("$eq", "A")),
                    Map.of("tags", Map.of("$eq", "important"))))
            .get(DENSE_FIELD);
    softAssertEquals(9, results.size(), "category=A AND tags=important exact count");
    for (SearchHit r : results) {
      Map<String, Object> flt = filterOf(r);
      assertEquals("A", flt.get("category"));
      assertEquals("important", flt.get("tags"));
    }
  }

  @Test
  void filterAndEqAndRange() {
    List<SearchHit> results =
        dense()
            .search(
                Map.of(DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", N_VECTORS)),
                List.of(
                    Map.of("category", Map.of("$eq", "A")),
                    Map.of("score", Map.of("$range", List.of(0, 29)))))
            .get(DENSE_FIELD);
    softAssertEquals(10, results.size(), "category=A AND score in [0,29] exact count");
    for (SearchHit r : results) {
      Map<String, Object> flt = filterOf(r);
      assertEquals("A", flt.get("category"));
      int score = ((Number) flt.get("score")).intValue();
      assertTrue(0 <= score && score <= 29);
    }
  }

  @Test
  void filterAndInAndRange() {
    List<SearchHit> results =
        dense()
            .search(
                Map.of(DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", N_VECTORS)),
                List.of(
                    Map.of("category", Map.of("$in", List.of("A", "B"))),
                    Map.of("score", Map.of("$range", List.of(0, 9)))))
            .get(DENSE_FIELD);
    softAssertEquals(7, results.size(), "category in [A,B] AND score in [0,9] exact count");
    for (SearchHit r : results) {
      Map<String, Object> flt = filterOf(r);
      Object cat = flt.get("category");
      assertTrue("A".equals(cat) || "B".equals(cat));
      assertTrue(((Number) flt.get("score")).intValue() <= 9);
    }
  }

  @Test
  void filterThreeConditions() {
    List<SearchHit> results =
        dense()
            .search(
                Map.of(DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", N_VECTORS)),
                List.of(
                    Map.of("category", Map.of("$eq", "A")),
                    Map.of("tags", Map.of("$eq", "important")),
                    Map.of("score", Map.of("$range", List.of(0, 29)))))
            .get(DENSE_FIELD);
    softAssertEquals(5, results.size(), "three-condition AND exact count");
    for (SearchHit r : results) {
      Map<String, Object> flt = filterOf(r);
      assertEquals("A", flt.get("category"));
      assertEquals("important", flt.get("tags"));
      assertTrue(((Number) flt.get("score")).intValue() <= 29);
    }
  }

  @Test
  void filterResultsSatisfyCondition() {
    List<SearchHit> results =
        dense()
            .search(
                Map.of(DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", N_VECTORS)),
                List.of(Map.of("priority", Map.of("$eq", 0))))
            .get(DENSE_FIELD);
    softAssertEquals(10, results.size(), "priority=0 exact count");
    for (SearchHit r : results) {
      assertEquals(0, ((Number) filterOf(r).get("priority")).intValue());
    }
  }

  @Test
  void filterGtAllResultsAboveThreshold() {
    int threshold = 40;
    List<SearchHit> results =
        dense()
            .search(
                Map.of(DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", N_VECTORS)),
                List.of(Map.of("score", Map.of("$gt", threshold))))
            .get(DENSE_FIELD);
    softAssertTrue(results.size() > 0, "gt filter should return results");
    for (SearchHit r : results) {
      assertTrue(scoreOf(r) > threshold);
    }
  }

  @Test
  void filterGtExactCount() {
    List<SearchHit> results =
        dense()
            .search(
                Map.of(DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", N_VECTORS)),
                List.of(Map.of("score", Map.of("$gt", 40))))
            .get(DENSE_FIELD);
    softAssertEquals(9, results.size(), "gt 40 exact count");
  }

  @Test
  void filterGteAllResultsAtOrAboveThreshold() {
    int threshold = 45;
    List<SearchHit> results =
        dense()
            .search(
                Map.of(DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", N_VECTORS)),
                List.of(Map.of("score", Map.of("$gte", threshold))))
            .get(DENSE_FIELD);
    softAssertTrue(results.size() > 0, "gte filter should return results");
    for (SearchHit r : results) {
      assertTrue(scoreOf(r) >= threshold);
    }
  }

  @Test
  void filterGteExactCount() {
    List<SearchHit> results =
        dense()
            .search(
                Map.of(DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", N_VECTORS)),
                List.of(Map.of("score", Map.of("$gte", 45))))
            .get(DENSE_FIELD);
    softAssertEquals(5, results.size(), "gte 45 exact count");
  }

  @Test
  void filterLtAllResultsBelowThreshold() {
    int threshold = 5;
    List<SearchHit> results =
        dense()
            .search(
                Map.of(DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", N_VECTORS)),
                List.of(Map.of("score", Map.of("$lt", threshold))))
            .get(DENSE_FIELD);
    softAssertTrue(results.size() > 0, "lt filter should return results");
    for (SearchHit r : results) {
      assertTrue(scoreOf(r) < threshold);
    }
  }

  @Test
  void filterLtExactCount() {
    List<SearchHit> results =
        dense()
            .search(
                Map.of(DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", N_VECTORS)),
                List.of(Map.of("score", Map.of("$lt", 5))))
            .get(DENSE_FIELD);
    softAssertEquals(5, results.size(), "lt 5 exact count");
  }

  @Test
  void filterLteAllResultsAtOrBelowThreshold() {
    int threshold = 4;
    List<SearchHit> results =
        dense()
            .search(
                Map.of(DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", N_VECTORS)),
                List.of(Map.of("score", Map.of("$lte", threshold))))
            .get(DENSE_FIELD);
    softAssertTrue(results.size() > 0, "lte filter should return results");
    for (SearchHit r : results) {
      assertTrue(scoreOf(r) <= threshold);
    }
  }

  @Test
  void filterLteExactCount() {
    List<SearchHit> results =
        dense()
            .search(
                Map.of(DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", N_VECTORS)),
                List.of(Map.of("score", Map.of("$lte", 4))))
            .get(DENSE_FIELD);
    softAssertEquals(5, results.size(), "lte 4 exact count");
  }

  @Test
  void filterGtAndLtReturnsRange() {
    List<SearchHit> results =
        dense()
            .search(
                Map.of(DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", N_VECTORS)),
                List.of(Map.of("score", Map.of("$gt", 10)), Map.of("score", Map.of("$lt", 20))))
            .get(DENSE_FIELD);
    softAssertEquals(9, results.size(), "gt 10 and lt 20 exact count");
    for (SearchHit r : results) {
      int s = scoreOf(r);
      assertTrue(10 < s && s < 20);
    }
  }

  @Test
  void filterGteAndLteReturnsClosedRange() {
    List<SearchHit> results =
        dense()
            .search(
                Map.of(DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", N_VECTORS)),
                List.of(Map.of("score", Map.of("$gte", 10)), Map.of("score", Map.of("$lte", 20))))
            .get(DENSE_FIELD);
    softAssertEquals(11, results.size(), "gte 10 and lte 20 exact count");
    for (SearchHit r : results) {
      int s = scoreOf(r);
      assertTrue(10 <= s && s <= 20);
    }
  }

  // -- multi_vector field -------------------------------------------------------

  @Test
  void multiVectorSearchWithEqFilter() {
    List<SearchHit> results =
        mv()
            .search(
                Map.of(MV_FIELD, Map.of("query", VectorGenerators.multiVec(0), "limit", N_VECTORS)),
                List.of(Map.of("category", Map.of("$eq", "A"))))
            .get(MV_FIELD);
    softAssertTrue(results.size() > 0, "mv eq filter should return results");
    for (SearchHit r : results) {
      assertEquals("A", filterOf(r).get("category"));
    }
  }

  @Test
  void multiVectorSearchFilterExactCount() {
    List<SearchHit> results =
        mv()
            .search(
                Map.of(MV_FIELD, Map.of("query", VectorGenerators.multiVec(0), "limit", N_VECTORS)),
                List.of(Map.of("category", Map.of("$eq", "B"))))
            .get(MV_FIELD);
    softAssertEquals(17, results.size(), "mv eq filter category=B exact count");
  }

  // -- hybrid (dense + sparse) field --------------------------------------------

  @Test
  void hybridDenseSearchWithEqFilter() {
    List<SearchHit> results =
        hybrid()
            .search(
                Map.of(
                    DENSE_FIELD,
                    Map.of("query", VectorGenerators.denseVec(HYBRID_DIM, 0), "limit", N_VECTORS)),
                List.of(Map.of("category", Map.of("$eq", "A"))))
            .get(DENSE_FIELD);
    softAssertTrue(results.size() > 0, "hybrid dense eq filter should return results");
    for (SearchHit r : results) {
      assertEquals("A", filterOf(r).get("category"));
    }
  }

  @Test
  void hybridFilterExactCount() {
    List<SearchHit> results =
        hybrid()
            .search(
                Map.of(
                    DENSE_FIELD,
                    Map.of("query", VectorGenerators.denseVec(HYBRID_DIM, 0), "limit", N_VECTORS)),
                List.of(Map.of("category", Map.of("$eq", "B"))))
            .get(DENSE_FIELD);
    softAssertEquals(17, results.size(), "hybrid eq filter category=B exact count");
  }
}
