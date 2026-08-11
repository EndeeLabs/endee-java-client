package io.endee.client;

import io.endee.client.types.SearchHit;
import java.util.*;

/**
 * Client-side reranking utilities for fusing per-field search results.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * Map<String, List<SearchHit>> results = collection.search(queryFields);
 * List<SearchHit> fused = Reranker.rerank(results, 10,
 *     Map.of("embedding", 0.6, "keywords", 0.4), 60);
 * }</pre>
 */
public final class Reranker {

  private static final int DEFAULT_LIMIT = 10;
  private static final int DEFAULT_RRF_K = 60;

  private Reranker() {}

  /**
   * Fuses per-field search results using Reciprocal Rank Fusion (RRF).
   *
   * @param searchResults per-field results from {@link Collection#search}
   * @param limit max number of fused hits to return
   * @param fieldWeights per-field weights (must sum to 1.0); null for uniform
   * @param rrfK RRF rank constant (default 60)
   * @return fused and sorted list of hits
   */
  public static List<SearchHit> rerank(
      Map<String, List<SearchHit>> searchResults,
      int limit,
      Map<String, Double> fieldWeights,
      int rrfK) {

    if (searchResults == null || searchResults.isEmpty()) {
      throw new IllegalArgumentException("searchResults must be a non-empty per-field map");
    }

    List<String> fieldNames = new ArrayList<>(searchResults.keySet());

    // Resolve weights
    Map<String, Double> weights;
    if (fieldWeights == null) {
      weights = new LinkedHashMap<>();
      double uniform = 1.0 / fieldNames.size();
      for (String f : fieldNames) {
        weights.put(f, uniform);
      }
    } else {
      for (String f : fieldNames) {
        if (!fieldWeights.containsKey(f)) {
          throw new IllegalArgumentException("field_weights missing entry for: " + f);
        }
      }
      double total = 0;
      for (String f : fieldNames) {
        total += fieldWeights.get(f);
      }
      if (Math.abs(total - 1.0) > 1e-6) {
        throw new IllegalArgumentException(
            "field_weights must sum to 1.0 (got " + String.format("%.8f", total) + ")");
      }
      weights = fieldWeights;
    }

    // Compute RRF scores
    Map<String, Double> scores = new LinkedHashMap<>();
    Map<String, SearchHit> hitById = new LinkedHashMap<>();

    for (String fname : fieldNames) {
      double weight = weights.getOrDefault(fname, 0.0);
      List<SearchHit> hits = searchResults.getOrDefault(fname, List.of());
      int rank = 1;
      for (SearchHit hit : hits) {
        String hid = hit.getId();
        scores.merge(hid, weight / (rrfK + rank), Double::sum);
        hitById.putIfAbsent(hid, hit);
        rank++;
      }
    }

    // Sort by score descending, take top limit
    List<Map.Entry<String, Double>> ranked = new ArrayList<>(scores.entrySet());
    ranked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

    List<SearchHit> results = new ArrayList<>();
    for (int i = 0; i < Math.min(limit, ranked.size()); i++) {
      Map.Entry<String, Double> entry = ranked.get(i);
      SearchHit original = hitById.get(entry.getKey());
      SearchHit fused =
          new SearchHit(
              original.getId(), entry.getValue(), original.getMeta(), original.getFilter());
      results.add(fused);
    }

    return results;
  }

  /** Convenience: rerank with default limit (10) and rrfK (60). */
  public static List<SearchHit> rerank(
      Map<String, List<SearchHit>> searchResults, Map<String, Double> fieldWeights) {
    return rerank(searchResults, DEFAULT_LIMIT, fieldWeights, DEFAULT_RRF_K);
  }

  /** Convenience: rerank with uniform weights. */
  public static List<SearchHit> rerank(Map<String, List<SearchHit>> searchResults, int limit) {
    return rerank(searchResults, limit, null, DEFAULT_RRF_K);
  }
}
