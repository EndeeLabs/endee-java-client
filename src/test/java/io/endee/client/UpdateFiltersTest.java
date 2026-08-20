package io.endee.client;

import static io.endee.client.support.FieldConfigs.DENSE_FIELD;
import static io.endee.client.support.FieldConfigs.MV_FIELD;
import static io.endee.client.support.VectorGenerators.N_VECTORS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.endee.client.support.CollectionFixtures;
import io.endee.client.support.CollectionFixtures.NamedCollection;
import io.endee.client.support.TestConfig;
import io.endee.client.support.VectorGenerators;
import io.endee.client.types.ObjectInfo;
import io.endee.client.types.SearchHit;
import io.endee.client.types.UpdateFilterParams;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

// Covers Collection.updateFilters: value updates, new keys, batch updates, idempotency.
class UpdateFiltersTest {

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

  private static int updatedCount(Map<String, Object> result) {
    return ((Number) result.get("updated")).intValue();
  }

  private List<SearchHit> searchDense(Collection c, String field, Object value) {
    return c.search(
            Map.of(DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", N_VECTORS)),
            List.of(Map.of(field, Map.of("$eq", value))))
        .get(DENSE_FIELD);
  }

  private List<SearchHit> searchMv(Collection c, String field, Object value) {
    return c.search(
            Map.of(MV_FIELD, Map.of("query", VectorGenerators.multiVec(0), "limit", N_VECTORS)),
            List.of(Map.of(field, Map.of("$eq", value))))
        .get(MV_FIELD);
  }

  // -- return structure ---------------------------------------------------------

  @Test
  void updateFiltersReturnsMap() {
    Map<String, Object> result =
        dense().updateFilters(List.of(new UpdateFilterParams("vec_0000", Map.of("category", "X"))));
    assertTrue(result instanceof Map);
  }

  @Test
  void updateFiltersReturnsUpdatedKey() {
    Map<String, Object> result =
        dense().updateFilters(List.of(new UpdateFilterParams("vec_0001", Map.of("category", "X"))));
    assertTrue(result.containsKey("updated"), "Expected 'updated' key, got " + result.keySet());
  }

  @Test
  void updateFiltersUpdatedValueIsNonNegativeInt() {
    Map<String, Object> result =
        dense().updateFilters(List.of(new UpdateFilterParams("vec_0002", Map.of("category", "X"))));
    assertTrue(result.get("updated") instanceof Number);
    assertTrue(updatedCount(result) >= 0);
  }

  // -- count accuracy -----------------------------------------------------------

  @Test
  void updateFiltersSingleObjectCount() {
    Map<String, Object> result =
        dense().updateFilters(List.of(new UpdateFilterParams("vec_0003", Map.of("category", "Z"))));
    assertEquals(1, updatedCount(result));
  }

  @Test
  void updateFiltersMultipleObjectsCount() {
    List<UpdateFilterParams> updates = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      updates.add(new UpdateFilterParams(String.format("vec_%04d", i), Map.of("category", "Z")));
    }
    Map<String, Object> result = dense().updateFilters(updates);
    assertEquals(5, updatedCount(result));
  }

  @Test
  void updateFiltersBatchCountMatches() {
    List<UpdateFilterParams> updates = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      updates.add(new UpdateFilterParams(String.format("vec_%04d", i), Map.of("batch_tag", "yes")));
    }
    Map<String, Object> result = dense().updateFilters(updates);
    assertEquals(3, updatedCount(result));
  }

  // -- negative membership: old value must not appear after update --------------

  @Test
  void updateFiltersOldValueNoLongerMatches() {
    Collection c = dense();
    c.updateFilters(List.of(new UpdateFilterParams("vec_0000", Map.of("category", "CHANGED"))));
    List<SearchHit> results = searchDense(c, "category", "A");
    Set<String> returnedIds = results.stream().map(SearchHit::getId).collect(Collectors.toSet());
    assertFalse(returnedIds.contains("vec_0000"));
  }

  // -- updated value reflected in search ----------------------------------------

  @Test
  void updateFiltersValueReflectedInSearch() {
    Collection c = dense();
    c.updateFilters(List.of(new UpdateFilterParams("vec_0004", Map.of("category", "UPDATED"))));
    List<SearchHit> results = searchDense(c, "category", "UPDATED");
    Set<String> returnedIds = results.stream().map(SearchHit::getId).collect(Collectors.toSet());
    assertTrue(returnedIds.contains("vec_0004"));
  }

  @Test
  void updateFiltersNewValueSearchable() {
    Collection c = dense();
    c.updateFilters(List.of(new UpdateFilterParams("vec_0000", Map.of("category", "NEWCAT"))));
    List<SearchHit> results = searchDense(c, "category", "NEWCAT");
    assertTrue(results.stream().anyMatch(r -> r.getId().equals("vec_0000")));
  }

  @Test
  void updateFiltersCanAddNewKey() {
    Collection c = dense();
    c.updateFilters(List.of(new UpdateFilterParams("vec_0005", Map.of("new_label", "alpha"))));
    List<SearchHit> results = searchDense(c, "new_label", "alpha");
    Set<String> returnedIds = results.stream().map(SearchHit::getId).collect(Collectors.toSet());
    assertTrue(returnedIds.contains("vec_0005"));
  }

  @Test
  void updateFiltersBatchUpdates() {
    Collection c = dense();
    List<String> idsToUpdate = new ArrayList<>();
    for (int i = 10; i < 15; i++) {
      idsToUpdate.add(String.format("vec_%04d", i));
    }
    List<UpdateFilterParams> updates =
        idsToUpdate.stream()
            .map(id -> new UpdateFilterParams(id, Map.<String, Object>of("status", "processed")))
            .collect(Collectors.toList());
    c.updateFilters(updates);
    List<SearchHit> results = searchDense(c, "status", "processed");
    Set<String> returnedIds = results.stream().map(SearchHit::getId).collect(Collectors.toSet());
    for (String id : idsToUpdate) {
      assertTrue(returnedIds.contains(id), id + " not found after batch update");
    }
  }

  @Test
  void updateFiltersNumericValue() {
    Collection c = dense();
    c.updateFilters(List.of(new UpdateFilterParams("vec_0008", Map.of("priority", 99))));
    List<SearchHit> results = searchDense(c, "priority", 99);
    assertTrue(results.stream().anyMatch(r -> r.getId().equals("vec_0008")));
  }

  @Test
  void updateFiltersSameValueTwiceIsIdempotent() {
    Collection c = dense();
    List<UpdateFilterParams> update =
        List.of(new UpdateFilterParams("vec_0009", Map.of("status", "stable")));
    Map<String, Object> r1 = c.updateFilters(update);
    Map<String, Object> r2 = c.updateFilters(update);
    assertEquals(1, updatedCount(r1));
    assertEquals(1, updatedCount(r2));
    List<SearchHit> results = searchDense(c, "status", "stable");
    long count = results.stream().filter(r -> r.getId().equals("vec_0009")).count();
    assertEquals(1, count);
  }

  // -- multi_vector collection --------------------------------------------------

  @Test
  void mvUpdateFiltersReturnsMap() {
    Map<String, Object> result =
        mv().updateFilters(List.of(new UpdateFilterParams("mv_0000", Map.of("category", "Z"))));
    assertTrue(result instanceof Map);
  }

  @Test
  void mvUpdateFiltersHasUpdatedKey() {
    Map<String, Object> result =
        mv().updateFilters(List.of(new UpdateFilterParams("mv_0000", Map.of("category", "Z"))));
    assertTrue(result.containsKey("updated"));
  }

  @Test
  void mvUpdateFiltersCountCorrect() {
    Map<String, Object> result =
        mv().updateFilters(
                List.of(
                    new UpdateFilterParams("mv_0001", Map.of("category", "Z")),
                    new UpdateFilterParams("mv_0002", Map.of("category", "Z")),
                    new UpdateFilterParams("mv_0003", Map.of("category", "Z"))));
    assertEquals(3, updatedCount(result));
  }

  @Test
  void mvUpdateFiltersReflectedInGetObjects() {
    Collection c = mv();
    c.updateFilters(List.of(new UpdateFilterParams("mv_0010", Map.of("category", "X"))));
    List<ObjectInfo> objs = c.getObjects(List.of("mv_0010"));
    assertEquals("X", objs.get(0).getFilter().get("category"));
  }

  @Test
  void mvUpdateFiltersOldValueNotSearchable() {
    Collection c = mv();
    c.updateFilters(List.of(new UpdateFilterParams("mv_0000", Map.of("category", "Z"))));
    List<SearchHit> results = searchMv(c, "category", "A");
    assertFalse(results.stream().anyMatch(r -> r.getId().equals("mv_0000")));
  }

  @Test
  void mvUpdateFiltersIdempotent() {
    Collection c = mv();
    c.updateFilters(List.of(new UpdateFilterParams("mv_0004", Map.of("category", "W"))));
    Map<String, Object> result =
        c.updateFilters(List.of(new UpdateFilterParams("mv_0004", Map.of("category", "W"))));
    assertTrue(result instanceof Map);
  }

  @Test
  void mvUpdateFiltersReflectedInSearch() {
    Collection c = mv();
    c.updateFilters(List.of(new UpdateFilterParams("mv_0020", Map.of("category", "Y"))));
    List<SearchHit> results =
        c.search(
                Map.of(MV_FIELD, Map.of("query", VectorGenerators.multiVec(20), "limit", N_VECTORS)),
                List.of(Map.of("category", Map.of("$eq", "Y"))))
            .get(MV_FIELD);
    assertTrue(results.stream().anyMatch(r -> r.getId().equals("mv_0020")));
  }

  // -- non-existent ID ----------------------------------------------------------

  @Test
  void updateFiltersNonexistentIdReturnsZero() {
    Map<String, Object> result =
        dense()
            .updateFilters(
                List.of(
                    new UpdateFilterParams("definitely_not_here_xyz_000", Map.of("category", "Z"))));
    assertEquals(0, updatedCount(result));
  }

  @Test
  void mvUpdateFiltersNonexistentIdReturnsZero() {
    Map<String, Object> result =
        mv().updateFilters(
                List.of(
                    new UpdateFilterParams("definitely_not_here_xyz_001", Map.of("category", "Z"))));
    assertEquals(0, updatedCount(result));
  }
}
