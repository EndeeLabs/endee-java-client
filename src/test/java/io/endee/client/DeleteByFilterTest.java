package io.endee.client;

import static io.endee.client.support.FieldConfigs.DENSE_FIELD;
import static io.endee.client.support.FieldConfigs.MV_FIELD;
import static io.endee.client.support.VectorGenerators.N_VECTORS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.endee.client.support.CollectionFixtures;
import io.endee.client.support.CollectionFixtures.NamedCollection;
import io.endee.client.support.TestConfig;
import io.endee.client.support.VectorGenerators;
import io.endee.client.types.SearchHit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

// Covers Collection.deleteByFilter: count accuracy, all operators, AND conditions, no-match case.
class DeleteByFilterTest {

  private NamedCollection dense;
  private NamedCollection mv;

  @AfterEach
  void tearDown() {
    if (dense != null) CollectionFixtures.safeDelete(TestConfig.client(), dense.name());
    if (mv != null) CollectionFixtures.safeDelete(TestConfig.client(), mv.name());
  }

  private Collection dense() {
    dense = CollectionFixtures.populatedDense();
    return dense.collection();
  }

  private Collection mv() {
    mv = CollectionFixtures.populatedMv();
    return mv.collection();
  }

  private static int deletedCount(Map<String, Object> result) {
    return ((Number) result.get("deleted")).intValue();
  }

  private static Map<String, Object> filterOf(SearchHit hit) {
    return hit.getFilter() != null ? hit.getFilter() : Map.of();
  }

  // -- return structure ---------------------------------------------------------

  @Test
  void deleteByFilterReturnsMap() {
    Map<String, Object> result =
        dense().deleteByFilter(List.of(Map.of("category", Map.of("$eq", "C"))));
    assertTrue(result instanceof Map);
  }

  @Test
  void deleteByFilterReturnsDeletedKey() {
    Map<String, Object> result =
        dense().deleteByFilter(List.of(Map.of("category", Map.of("$eq", "C"))));
    assertTrue(result.containsKey("deleted"), "Expected 'deleted' key, got " + result.keySet());
  }

  @Test
  void deleteByFilterDeletedValueIsNonNegativeInt() {
    Map<String, Object> result =
        dense().deleteByFilter(List.of(Map.of("category", Map.of("$eq", "A"))));
    assertTrue(result.get("deleted") instanceof Number);
    assertTrue(deletedCount(result) >= 0);
  }

  // -- correctness: objects must be gone from search ----------------------------

  @Test
  void deleteByFilterEqObjectsAbsentFromSearch() {
    Collection c = dense();
    c.deleteByFilter(List.of(Map.of("category", Map.of("$eq", "B"))));
    List<SearchHit> results =
        c.search(Map.of(DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", N_VECTORS)))
            .get(DENSE_FIELD);
    for (SearchHit r : results) {
      assertTrue(
          !"B".equals(filterOf(r).get("category")), "Deleted object still present: " + r.getId());
    }
  }

  @Test
  void deleteByFilterPreservesNonMatchingObjects() {
    Collection c = dense();
    c.deleteByFilter(List.of(Map.of("category", Map.of("$eq", "C"))));
    List<SearchHit> results =
        c.search(Map.of(DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", N_VECTORS)))
            .get(DENSE_FIELD);
    Set<Object> remainingCats = new HashSet<>();
    for (SearchHit r : results) {
      remainingCats.add(filterOf(r).get("category"));
    }
    assertTrue(remainingCats.contains("A") || remainingCats.contains("B"));
  }

  // -- count accuracy -----------------------------------------------------------

  @Test
  void deleteByFilterEqCountExact() {
    Map<String, Object> result =
        dense().deleteByFilter(List.of(Map.of("category", Map.of("$eq", "B"))));
    assertEquals(17, deletedCount(result));
  }

  @Test
  void deleteByFilterEqTagsImportantCount() {
    Map<String, Object> result =
        dense().deleteByFilter(List.of(Map.of("tags", Map.of("$eq", "important"))));
    assertEquals(25, deletedCount(result));
  }

  // -- $in operator -------------------------------------------------------------

  @Test
  void deleteByFilterInSingleValue() {
    Map<String, Object> result =
        dense().deleteByFilter(List.of(Map.of("category", Map.of("$in", List.of("C")))));
    assertEquals(16, deletedCount(result));
  }

  @Test
  void deleteByFilterInTwoValues() {
    Map<String, Object> result =
        dense().deleteByFilter(List.of(Map.of("category", Map.of("$in", List.of("A", "B")))));
    assertEquals(34, deletedCount(result));
  }

  // -- $range operator ----------------------------------------------------------

  @Test
  void deleteByFilterRangeCountExact() {
    Map<String, Object> result =
        dense().deleteByFilter(List.of(Map.of("score", Map.of("$range", List.of(40, 49)))));
    assertEquals(10, deletedCount(result));
  }

  @Test
  void deleteByFilterRangeObjectsGone() {
    Collection c = dense();
    c.deleteByFilter(List.of(Map.of("score", Map.of("$range", List.of(40, 49)))));
    List<SearchHit> results =
        c.search(Map.of(DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", N_VECTORS)))
            .get(DENSE_FIELD);
    for (SearchHit r : results) {
      Object scoreObj = filterOf(r).get("score");
      int score = scoreObj instanceof Number ? ((Number) scoreObj).intValue() : -1;
      assertTrue(!(40 <= score && score <= 49), "Deleted object still present: score=" + score);
    }
  }

  // -- $gt / $gte / $lt / $lte operators (score = i, 0..49) --------------------

  @Test
  void deleteByFilterGtCountExact() {
    Map<String, Object> result = dense().deleteByFilter(List.of(Map.of("score", Map.of("$gt", 40))));
    assertEquals(9, deletedCount(result));
  }

  @Test
  void deleteByFilterGteCountExact() {
    Map<String, Object> result =
        dense().deleteByFilter(List.of(Map.of("score", Map.of("$gte", 40))));
    assertEquals(10, deletedCount(result));
  }

  @Test
  void deleteByFilterLtCountExact() {
    Map<String, Object> result = dense().deleteByFilter(List.of(Map.of("score", Map.of("$lt", 10))));
    assertEquals(10, deletedCount(result));
  }

  @Test
  void deleteByFilterLteCountExact() {
    Map<String, Object> result =
        dense().deleteByFilter(List.of(Map.of("score", Map.of("$lte", 10))));
    assertEquals(11, deletedCount(result));
  }

  @Test
  void deleteByFilterGtAndLtOpenRange() {
    Map<String, Object> result =
        dense()
            .deleteByFilter(
                List.of(Map.of("score", Map.of("$gt", 10)), Map.of("score", Map.of("$lt", 20))));
    assertEquals(9, deletedCount(result));
  }

  @Test
  void deleteByFilterGteAndLteClosedRange() {
    Map<String, Object> result =
        dense()
            .deleteByFilter(
                List.of(Map.of("score", Map.of("$gte", 10)), Map.of("score", Map.of("$lte", 20))));
    assertEquals(11, deletedCount(result));
  }

  // -- no-match case ------------------------------------------------------------

  @Test
  void deleteByFilterNoMatchReturnsZero() {
    Map<String, Object> result =
        dense().deleteByFilter(List.of(Map.of("category", Map.of("$eq", "ZZZNOMATCH"))));
    assertEquals(0, deletedCount(result));
  }

  // -- multi-condition AND logic ------------------------------------------------

  @Test
  void deleteByFilterAndEqAndEq() {
    Map<String, Object> result =
        dense()
            .deleteByFilter(
                List.of(
                    Map.of("category", Map.of("$eq", "A")),
                    Map.of("tags", Map.of("$eq", "important"))));
    assertEquals(9, deletedCount(result));
  }

  // -- delete entire corpus -----------------------------------------------------

  @Test
  void deleteByFilterAllObjects() {
    Collection c = dense();
    Map<String, Object> r1 = c.deleteByFilter(List.of(Map.of("category", Map.of("$eq", "A"))));
    Map<String, Object> r2 = c.deleteByFilter(List.of(Map.of("category", Map.of("$eq", "B"))));
    Map<String, Object> r3 = c.deleteByFilter(List.of(Map.of("category", Map.of("$eq", "C"))));
    int total = deletedCount(r1) + deletedCount(r2) + deletedCount(r3);
    assertEquals(N_VECTORS, total);
    List<SearchHit> results =
        c.search(Map.of(DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", N_VECTORS)))
            .get(DENSE_FIELD);
    assertTrue(results.isEmpty());
  }

  // -- multi_vector collection --------------------------------------------------

  @Test
  void mvDeleteByFilterReturnsMap() {
    Map<String, Object> result =
        mv().deleteByFilter(List.of(Map.of("category", Map.of("$eq", "C"))));
    assertTrue(result instanceof Map);
  }

  @Test
  void mvDeleteByFilterHasDeletedKey() {
    Map<String, Object> result =
        mv().deleteByFilter(List.of(Map.of("category", Map.of("$eq", "C"))));
    assertTrue(result.containsKey("deleted"));
  }

  @Test
  void mvDeleteByFilterCountExact() {
    Map<String, Object> result =
        mv().deleteByFilter(List.of(Map.of("category", Map.of("$eq", "C"))));
    assertEquals(16, deletedCount(result));
  }

  @Test
  void mvDeleteByFilterObjectsRemovedFromSearch() {
    Collection c = mv();
    c.deleteByFilter(List.of(Map.of("category", Map.of("$eq", "C"))));
    List<SearchHit> results =
        c.search(
                Map.of(MV_FIELD, Map.of("query", VectorGenerators.multiVec(0), "limit", N_VECTORS)),
                List.of(Map.of("category", Map.of("$eq", "C"))))
            .get(MV_FIELD);
    assertEquals(0, results.size());
  }

  @Test
  void mvDeleteByFilterNoMatchReturnsZero() {
    Map<String, Object> result =
        mv().deleteByFilter(List.of(Map.of("category", Map.of("$eq", "NONEXISTENT_CATEGORY"))));
    assertEquals(0, deletedCount(result));
  }

  @Test
  void mvDeleteByFilterInOperator() {
    Map<String, Object> result =
        mv().deleteByFilter(List.of(Map.of("category", Map.of("$in", List.of("A", "B")))));
    assertEquals(34, deletedCount(result));
  }

  @Test
  void mvDeleteByFilterAndConditions() {
    Map<String, Object> result =
        mv()
            .deleteByFilter(
                List.of(
                    Map.of("category", Map.of("$eq", "A")),
                    Map.of("tags", Map.of("$eq", "important"))));
    assertEquals(9, deletedCount(result));
  }
}
