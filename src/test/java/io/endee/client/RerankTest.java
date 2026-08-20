package io.endee.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.endee.client.support.CollectionFixtures;
import io.endee.client.support.CollectionFixtures.NamedCollection;
import io.endee.client.support.FieldConfigs;
import io.endee.client.support.TestConfig;
import io.endee.client.support.VectorGenerators;
import io.endee.client.types.SearchHit;
import io.endee.client.types.SparseData;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

// Covers Reranker.rerank: shape, ordering, field weights, rrfK, dedup, error cases.
class RerankTest {

  private final Endee client = TestConfig.client();
  private NamedCollection nc;

  @AfterEach
  void tearDown() {
    if (nc != null) {
      CollectionFixtures.safeDelete(client, nc.name());
      nc = null;
    }
  }

  private static Map<String, Object> fieldQuery(Object query, int limit) {
    Map<String, Object> inner = new LinkedHashMap<>();
    inner.put("query", query);
    inner.put("limit", limit);
    return inner;
  }

  // Returns a per-field search response from a populated hybrid collection.
  private Map<String, List<SearchHit>> rawSearch(long seed) {
    SparseData sd = VectorGenerators.sparseVec(seed);
    Map<String, Map<String, Object>> fields = new LinkedHashMap<>();
    fields.put(
        FieldConfigs.DENSE_FIELD,
        fieldQuery(
            VectorGenerators.denseVec(VectorGenerators.HYBRID_DIM, seed), VectorGenerators.N_VECTORS));
    fields.put(FieldConfigs.SPARSE_FIELD, fieldQuery(sd, VectorGenerators.N_VECTORS));
    return nc.collection().search(fields);
  }

  // -- return structure -----------------------------------------------------------

  @Test
  void testRerankResultsIsList() {
    nc = CollectionFixtures.populatedHybrid();
    List<SearchHit> results = Reranker.rerank(rawSearch(3), 10);
    assertNotNull(results);
  }

  @Test
  void testRerankLimitRespected() {
    nc = CollectionFixtures.populatedHybrid();
    List<SearchHit> results = Reranker.rerank(rawSearch(4), 5);
    assertTrue(results.size() <= 5);
  }

  @Test
  void testRerankResultsHaveRequiredKeys() {
    nc = CollectionFixtures.populatedHybrid();
    List<SearchHit> results = Reranker.rerank(rawSearch(5), 10);
    assertFalse(results.isEmpty());
    for (SearchHit hit : results) {
      assertNotNull(hit.getId(), "Hit missing id: " + hit);
    }
  }

  // -- result ordering --------------------------------------------------------------

  @Test
  void testRerankResultsSortedBySimilarity() {
    nc = CollectionFixtures.populatedHybrid();
    List<SearchHit> results = Reranker.rerank(rawSearch(6), 10);
    for (int i = 1; i < results.size(); i++) {
      assertTrue(
          results.get(i - 1).getSimilarity() >= results.get(i).getSimilarity(),
          "Results not sorted by similarity desc");
    }
  }

  // -- deduplication ------------------------------------------------------------------

  @Test
  void testRerankDeduplicatesAcrossFields() {
    nc = CollectionFixtures.populatedHybrid();
    List<SearchHit> results = Reranker.rerank(rawSearch(13), VectorGenerators.N_VECTORS);
    List<String> ids = results.stream().map(SearchHit::getId).collect(Collectors.toList());
    assertEquals(ids.size(), ids.stream().distinct().count(), "Duplicate ids found in rerank() output");
  }

  @Test
  void testRerankDeduplicatesSyntheticSameIdAcrossFields() {
    // Synthetic case: the same object id appears in both fields' result lists at different
    // ranks/similarities; rerank() must fuse it into a single entry, not duplicate it.
    SearchHit denseHit = new SearchHit("shared", 0.9, Map.of("k", "v"), Map.of());
    SearchHit sparseHit = new SearchHit("shared", 0.5, Map.of("k", "v"), Map.of());
    SearchHit onlyInDense = new SearchHit("only_dense", 0.8, Map.of(), Map.of());

    Map<String, List<SearchHit>> synthetic = new LinkedHashMap<>();
    synthetic.put("dense", List.of(denseHit, onlyInDense));
    synthetic.put("sparse", List.of(sparseHit));

    List<SearchHit> results = Reranker.rerank(synthetic, 10);
    List<String> ids = results.stream().map(SearchHit::getId).collect(Collectors.toList());
    assertEquals(2, ids.size());
    assertEquals(ids.size(), ids.stream().distinct().count());
  }

  // -- field_weights parameter --------------------------------------------------------

  @Test
  void testRerankFieldWeightsAccepted() {
    nc = CollectionFixtures.populatedHybrid();
    List<SearchHit> results =
        Reranker.rerank(rawSearch(7), 10, Map.of(FieldConfigs.DENSE_FIELD, 0.6, FieldConfigs.SPARSE_FIELD, 0.4), 60);
    assertNotNull(results);
  }

  @Test
  void testRerankFieldWeightsChangeResult() {
    nc = CollectionFixtures.populatedHybrid();
    Map<String, List<SearchHit>> raw = rawSearch(8);
    List<SearchHit> resultsDenseHeavy =
        Reranker.rerank(raw, 10, Map.of(FieldConfigs.DENSE_FIELD, 0.9, FieldConfigs.SPARSE_FIELD, 0.1), 60);
    List<SearchHit> resultsSparseHeavy =
        Reranker.rerank(raw, 10, Map.of(FieldConfigs.DENSE_FIELD, 0.1, FieldConfigs.SPARSE_FIELD, 0.9), 60);
    List<String> idsDense = resultsDenseHeavy.stream().map(SearchHit::getId).collect(Collectors.toList());
    List<String> idsSparse = resultsSparseHeavy.stream().map(SearchHit::getId).collect(Collectors.toList());
    assertNotEquals(idsDense, idsSparse, "Expected field_weights to affect ranking, but results are identical");
  }

  // -- rrfK parameter -------------------------------------------------------------------

  @Test
  void testRerankRrfKAccepted() {
    nc = CollectionFixtures.populatedHybrid();
    List<SearchHit> results = Reranker.rerank(rawSearch(9), 10, null, 20);
    assertNotNull(results);
  }

  // -- error handling -------------------------------------------------------------------

  @Test
  void testRerankFieldWeightsNotSummingToOneRaises() {
    Map<String, List<SearchHit>> synthetic = new LinkedHashMap<>();
    synthetic.put("dense", List.of(new SearchHit("a", 1.0, Map.of(), Map.of())));
    synthetic.put("sparse", List.of(new SearchHit("b", 1.0, Map.of(), Map.of())));

    assertThrows(
        IllegalArgumentException.class,
        () -> Reranker.rerank(synthetic, 10, Map.of("dense", 0.5, "sparse", 0.3), 60));
  }

  @Test
  void testRerankMissingFieldWeightRaises() {
    Map<String, List<SearchHit>> synthetic = new LinkedHashMap<>();
    synthetic.put("dense", List.of(new SearchHit("a", 1.0, Map.of(), Map.of())));
    synthetic.put("sparse", List.of(new SearchHit("b", 1.0, Map.of(), Map.of())));

    assertThrows(
        IllegalArgumentException.class,
        () -> Reranker.rerank(synthetic, 10, Map.of("dense", 1.0), 60));
  }

  @Test
  void testRerankEmptyFieldsRaises() {
    Map<String, List<SearchHit>> empty = Map.of();
    assertThrows(IllegalArgumentException.class, () -> Reranker.rerank(empty, 10));
  }
}
