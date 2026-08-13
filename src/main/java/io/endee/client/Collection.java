package io.endee.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.endee.client.exception.EndeeApiException;
import io.endee.client.exception.EndeeException;
import io.endee.client.types.*;
import io.endee.client.util.CryptoUtils;
import io.endee.client.util.JsonUtils;
import io.endee.client.util.MessagePackUtils;
import io.endee.client.util.ValidationUtils;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Collection client for Endee-DB vector operations (v2 API).
 *
 * <p>Obtain via {@link Endee#getCollection(String)}.
 */
public class Collection {
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
  private static final int MAX_BATCH_SIZE = 10_000;
  private static final int MAX_TOP_K = 4096;
  private static final int MAX_EF_SEARCH = 1024;
  private static final int MAX_FILTER_BOOST_PERCENTAGE = 100;
  private static final int MIN_PREFILTER_THRESHOLD = 1_000;
  private static final int MAX_PREFILTER_THRESHOLD = 1_000_000;
  private static final String NORMS_KEY = "internal_";

  private final String name;
  private final String token;
  private final String baseUrl;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private List<Map<String, Object>> fields;

  public Collection(String name, String token, String baseUrl, Map<String, Object> metadata) {
    this.name = name;
    this.token = token;
    this.baseUrl = baseUrl;
    this.objectMapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> fieldsList =
        (List<Map<String, Object>>) metadata.getOrDefault("fields", List.of());
    this.fields = fieldsList;

    this.httpClient =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(DEFAULT_TIMEOUT)
            .build();
  }

  @Override
  public String toString() {
    return name;
  }

  /** Returns field metadata: name → {type, space_type, dimension}. */
  private Map<String, Map<String, Object>> fieldMap() {
    Map<String, Map<String, Object>> idx = new LinkedHashMap<>();
    for (Map<String, Object> f : fields) {
      @SuppressWarnings("unchecked")
      Map<String, Object> params = (Map<String, Object>) f.getOrDefault("params", Map.of());
      Map<String, Object> entry = new HashMap<>();
      entry.put("type", f.getOrDefault("type", "vector"));
      entry.put("space_type", params.getOrDefault("space_type", "cosine"));
      entry.put("dimension", params.getOrDefault("dimension", 0));
      idx.put((String) f.get("name"), entry);
    }
    return idx;
  }

  // ── upsert ──────────────────────────────────────────────────────────────────

  /**
   * Upserts objects into the collection.
   *
   * @param objects list of objects to upsert (max 10,000)
   * @return server response
   */
  public Map<String, Object> upsert(List<ObjectItem> objects) {
    if (objects == null || objects.isEmpty()) {
      throw new IllegalArgumentException("Must provide at least one object to upsert");
    }
    if (objects.size() > MAX_BATCH_SIZE) {
      throw new IllegalArgumentException(
          "Cannot upsert more than " + MAX_BATCH_SIZE + " objects at a time");
    }

    List<String> ids = objects.stream().map(ObjectItem::getId).collect(Collectors.toList());
    ValidationUtils.validateObjectIds(ids);

    Map<String, Map<String, Object>> fMap = fieldMap();
    List<Object[]> wireObjects = new ArrayList<>();

    for (ObjectItem item : objects) {
      String filterStr = "";
      if (item.getFilter() != null && !item.getFilter().isEmpty()) {
        ValidationUtils.validateFilter(item.getFilter());
        filterStr = JsonUtils.toJson(item.getFilter());
      }

      Map<String, double[]> vectors = new LinkedHashMap<>();
      Map<String, Object[]> sparses = new LinkedHashMap<>();
      Map<String, double[][]> multiVectors = new LinkedHashMap<>();
      Map<String, Object> norms = new LinkedHashMap<>();

      if (item.getFields() != null) {
        for (Map.Entry<String, Object> fe : item.getFields().entrySet()) {
          String fname = fe.getKey();
          Object fdata = fe.getValue();
          Map<String, Object> cfg = fMap.get(fname);
          if (cfg == null) {
            throw new IllegalArgumentException(
                "Unknown field '" + fname + "'. Collection fields: " + fMap.keySet());
          }

          String ftype = (String) cfg.get("type");
          String space = (String) cfg.getOrDefault("space_type", "cosine");
          int dim =
              cfg.get("dimension") instanceof Number
                  ? ((Number) cfg.get("dimension")).intValue()
                  : 0;

          if ("vector".equals(ftype)) {
            double[] vec = (double[]) fdata;
            validateVectorValues(vec, item.getId());
            double[] normalized = normalizeDense(vec, space);
            double norm = computeNorm(vec);
            if (dim > 0 && vec.length != dim) {
              throw new IllegalArgumentException(
                  "Field '" + fname + "': expected dimension " + dim + ", got " + vec.length);
            }
            vectors.put(fname, normalized);
            if ("cosine".equals(space)) {
              norms.put(fname, norm);
            }
          } else if ("sparse".equals(ftype)) {
            SparseData sd = (SparseData) fdata;
            sparses.put(fname, new Object[] {sd.getIndices(), sd.getValues()});
          } else if ("multi_vector".equals(ftype)) {
            double[][] vecs = (double[][]) fdata;
            double[][] normalizedVecs = new double[vecs.length][];
            List<Double> vecNorms = new ArrayList<>();
            for (int i = 0; i < vecs.length; i++) {
              validateVectorValues(vecs[i], item.getId());
              if (dim > 0 && vecs[i].length != dim) {
                throw new IllegalArgumentException(
                    "Field '" + fname + "': every multi_vector must have dimension " + dim);
              }
              normalizedVecs[i] = normalizeDense(vecs[i], space);
              if ("cosine".equals(space)) {
                vecNorms.add(computeNorm(vecs[i]));
              }
            }
            multiVectors.put(fname, normalizedVecs);
            if ("cosine".equals(space) && !vecNorms.isEmpty()) {
              norms.put(fname, vecNorms);
            }
          } else {
            throw new IllegalArgumentException(
                "Field '" + fname + "' has unknown type '" + ftype + "'");
          }
        }
      }

      // Build meta with norms
      Map<String, Object> rawMeta =
          item.getMeta() != null ? new HashMap<>(item.getMeta()) : new HashMap<>();
      if (!norms.isEmpty()) {
        rawMeta.put(NORMS_KEY, norms);
      }
      byte[] metaBytes = CryptoUtils.jsonZip(rawMeta);

      wireObjects.add(
          new Object[] {item.getId(), metaBytes, filterStr, vectors, sparses, multiVectors});
    }

    byte[] payload = MessagePackUtils.packObjects(wireObjects);

    try {
      HttpRequest request = buildPostMsgpackRequest("/collection/" + name + "/objects", payload);
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() != 200) {
        EndeeApiException.raiseException(response.statusCode(), response.body());
      }

      @SuppressWarnings("unchecked")
      Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
      return result;
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new EndeeException("Failed to upsert objects", e);
    }
  }

  // ── search ──────────────────────────────────────────────────────────────────

  /**
   * Searches the collection across one or more fields.
   *
   * @param queryFields field_name → {query, limit?, ef_search?}
   * @param filter optional filter conditions
   * @param efSearch default ef_search (max 1024)
   * @param prefilterThreshold optional prefilter cardinality threshold (1000-1000000)
   * @param boostPercentage optional filter boost percentage (0-100)
   * @return per-field results: field_name → list of hits
   */
  public Map<String, List<SearchHit>> search(
      Map<String, Map<String, Object>> queryFields,
      List<Map<String, Object>> filter,
      int efSearch,
      Integer prefilterThreshold,
      Integer boostPercentage) {

    if (queryFields == null || queryFields.isEmpty()) {
      throw new IllegalArgumentException("search requires at least one field");
    }
    if (efSearch < 1 || efSearch > MAX_EF_SEARCH) {
      throw new IllegalArgumentException("ef_search must be between 1 and " + MAX_EF_SEARCH);
    }
    if (prefilterThreshold != null
        && (prefilterThreshold < MIN_PREFILTER_THRESHOLD
            || prefilterThreshold > MAX_PREFILTER_THRESHOLD)) {
      throw new IllegalArgumentException(
          "prefilter_cardinality_threshold must be between "
              + MIN_PREFILTER_THRESHOLD
              + " and "
              + MAX_PREFILTER_THRESHOLD);
    }
    if (boostPercentage != null
        && (boostPercentage < 0 || boostPercentage > MAX_FILTER_BOOST_PERCENTAGE)) {
      throw new IllegalArgumentException(
          "filter_boost_percentage must be between 0 and " + MAX_FILTER_BOOST_PERCENTAGE);
    }

    Map<String, Map<String, Object>> fMap = fieldMap();
    List<Map<String, Object>> fieldsArray = new ArrayList<>();
    Map<String, Integer> fieldLimits = new LinkedHashMap<>();

    for (Map.Entry<String, Map<String, Object>> entry : queryFields.entrySet()) {
      String fname = entry.getKey();
      Map<String, Object> fdata = new LinkedHashMap<>(entry.getValue());

      // Normalize query vectors for cosine fields
      Object query = fdata.get("query");
      Map<String, Object> fld = fMap.get(fname);
      if (fld != null) {
        String ftype = (String) fld.get("type");
        String space = (String) fld.getOrDefault("space_type", "cosine");
        if ("vector".equals(ftype) && "cosine".equals(space) && query instanceof double[]) {
          fdata.put("query", normalizeDense((double[]) query, space));
        } else if ("multi_vector".equals(ftype)
            && "cosine".equals(space)
            && query instanceof double[][]) {
          double[][] qVecs = (double[][]) query;
          double[][] normalized = new double[qVecs.length][];
          for (int i = 0; i < qVecs.length; i++) {
            normalized[i] = normalizeDense(qVecs[i], space);
          }
          fdata.put("query", normalized);
        }
      }

      // Resolve limit
      int limit = 10;
      if (fdata.containsKey("limit") && fdata.get("limit") instanceof Number) {
        limit = ((Number) fdata.get("limit")).intValue();
        if (limit < 1 || limit > MAX_TOP_K) {
          throw new IllegalArgumentException(
              "Search field '" + fname + "': limit must be between 1 and " + MAX_TOP_K);
        }
      }
      fieldLimits.put(fname, limit);

      // Build entry
      Map<String, Object> fieldEntry = new LinkedHashMap<>(fdata);
      fieldEntry.put("limit", limit);
      if (!fieldEntry.containsKey("ef_search")) {
        fieldEntry.put("ef_search", efSearch);
      }

      // Convert SparseData query to map format for JSON
      Object q = fieldEntry.get("query");
      if (q instanceof SparseData sd) {
        Map<String, Object> sparseQuery = new LinkedHashMap<>();
        sparseQuery.put("indices", sd.getIndices());
        sparseQuery.put("values", sd.getValues());
        fieldEntry.put("query", sparseQuery);
      }

      fieldsArray.add(Map.of(fname, fieldEntry));
    }

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("fields", fieldsArray);
    if (filter != null) {
      payload.put("filter", filter);
    }
    if (prefilterThreshold != null || boostPercentage != null) {
      Map<String, Object> filterParams = new LinkedHashMap<>();
      filterParams.put(
          "prefilter_threshold", prefilterThreshold != null ? prefilterThreshold : 10_000);
      filterParams.put("boost_percentage", boostPercentage != null ? boostPercentage : 0);
      payload.put("filter_params", filterParams);
    }

    try {
      String jsonBody = JsonUtils.toJson(payload);
      HttpRequest request = buildPostJsonRequest("/collection/" + name + "/search", jsonBody);
      HttpResponse<byte[]> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

      if (response.statusCode() != 200) {
        EndeeApiException.raiseException(response.statusCode(), new String(response.body()));
      }

      Object[] decoded = MessagePackUtils.unpackSearchResponse(response.body());
      @SuppressWarnings("unchecked")
      Map<Integer, Object[]> objectsMap = (Map<Integer, Object[]>) decoded[0];
      @SuppressWarnings("unchecked")
      Map<String, List<Object[]>> resultsMap = (Map<String, List<Object[]>>) decoded[1];

      Map<String, List<SearchHit>> perField = new LinkedHashMap<>();
      for (String fname : queryFields.keySet()) {
        List<Object[]> hits = resultsMap.getOrDefault(fname, List.of());
        int limit = fieldLimits.getOrDefault(fname, 10);
        List<SearchHit> fieldHits = new ArrayList<>();
        for (Object[] hit : hits) {
          if (fieldHits.size() >= limit) break;
          int intId = (Integer) hit[0];
          double score = (Double) hit[1];
          Object[] objMeta = objectsMap.get(intId);
          SearchHit sh = new SearchHit();
          if (objMeta != null) {
            sh.setId((String) objMeta[0]);
            Map<String, Object> meta = CryptoUtils.jsonUnzip((byte[]) objMeta[1]);
            if (meta != null) {
              meta.remove(NORMS_KEY);
            }
            sh.setMeta(meta);
            String filterStr = (String) objMeta[2];
            if (filterStr != null && !filterStr.isEmpty()) {
              @SuppressWarnings("unchecked")
              Map<String, Object> parsedFilter = JsonUtils.fromJson(filterStr, Map.class);
              sh.setFilter(parsedFilter);
            }
          } else {
            sh.setId(String.valueOf(intId));
            sh.setMeta(Map.of());
          }
          sh.setSimilarity(score);
          fieldHits.add(sh);
        }
        perField.put(fname, fieldHits);
      }

      return perField;
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new EndeeException("Failed to search collection", e);
    }
  }

  /** Convenience overload with defaults: efSearch=128, no filter tuning. */
  public Map<String, List<SearchHit>> search(
      Map<String, Map<String, Object>> queryFields, List<Map<String, Object>> filter) {
    return search(queryFields, filter, 128, null, null);
  }

  /** Convenience overload: no filter. */
  public Map<String, List<SearchHit>> search(Map<String, Map<String, Object>> queryFields) {
    return search(queryFields, null, 128, null, null);
  }

  // ── get objects ──────────────────────────────────────────────────────────────

  /**
   * Fetches full objects by ID.
   *
   * @param ids list of object IDs
   * @return list of objects with vectors, sparses, multi_vectors
   */
  public List<ObjectInfo> getObjects(List<String> ids) {
    if (ids == null || ids.isEmpty()) {
      throw new IllegalArgumentException("getObjects requires a non-empty list of ids");
    }

    try {
      String jsonBody = JsonUtils.toJson(Map.of("ids", ids));
      HttpRequest request =
          buildPostJsonRequest("/collection/" + name + "/objects/query", jsonBody);
      HttpResponse<byte[]> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

      if (response.statusCode() != 200) {
        EndeeApiException.raiseException(response.statusCode(), new String(response.body()));
      }

      List<Object[]> batch = MessagePackUtils.unpackObjectBatch(response.body());
      List<ObjectInfo> results = new ArrayList<>();

      for (Object[] obj : batch) {
        ObjectInfo info = new ObjectInfo();
        info.setId((String) obj[0]);

        Map<String, Object> meta = CryptoUtils.jsonUnzip((byte[]) obj[1]);
        @SuppressWarnings("unchecked")
        Map<String, Object> normsMap =
            meta != null ? (Map<String, Object>) meta.remove(NORMS_KEY) : null;
        info.setMeta(meta != null ? meta : Map.of());

        String filterStr = (String) obj[2];
        if (filterStr != null && !filterStr.isEmpty()) {
          @SuppressWarnings("unchecked")
          Map<String, Object> parsedFilter = JsonUtils.fromJson(filterStr, Map.class);
          info.setFilter(parsedFilter);
        } else {
          info.setFilter(Map.of());
        }

        // Vectors — reconstruct originals using norms
        @SuppressWarnings("unchecked")
        Map<String, double[]> vectors = (Map<String, double[]>) obj[3];
        if (normsMap != null && vectors != null) {
          for (Map.Entry<String, double[]> ve : vectors.entrySet()) {
            Object normVal = normsMap.get(ve.getKey());
            if (normVal instanceof Number n) {
              double norm = n.doubleValue();
              double[] vec = ve.getValue();
              for (int i = 0; i < vec.length; i++) {
                vec[i] *= norm;
              }
            }
          }
        }
        info.setVectors(vectors != null ? vectors : Map.of());

        // Sparses
        @SuppressWarnings("unchecked")
        Map<String, Object[]> sparsesRaw = (Map<String, Object[]>) obj[4];
        Map<String, SparseData> sparses = new LinkedHashMap<>();
        if (sparsesRaw != null) {
          for (Map.Entry<String, Object[]> se : sparsesRaw.entrySet()) {
            sparses.put(
                se.getKey(), new SparseData((int[]) se.getValue()[0], (double[]) se.getValue()[1]));
          }
        }
        info.setSparses(sparses);

        // Multi-vectors — reconstruct originals using norms
        @SuppressWarnings("unchecked")
        Map<String, double[][]> multiVecs = (Map<String, double[][]>) obj[5];
        if (normsMap != null && multiVecs != null) {
          for (Map.Entry<String, double[][]> mve : multiVecs.entrySet()) {
            Object normVal = normsMap.get(mve.getKey());
            if (normVal instanceof List<?> normsList) {
              double[][] vecs = mve.getValue();
              for (int i = 0; i < vecs.length && i < normsList.size(); i++) {
                double n = ((Number) normsList.get(i)).doubleValue();
                for (int j = 0; j < vecs[i].length; j++) {
                  vecs[i][j] *= n;
                }
              }
            }
          }
        }
        info.setMultiVectors(multiVecs != null ? multiVecs : Map.of());

        results.add(info);
      }

      return results;
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new EndeeException("Failed to get objects", e);
    }
  }

  // ── get neighbors ────────────────────────────────────────────────────────────

  /**
   * Gets the HNSW graph neighbors of an object for a given field.
   *
   * @param id object ID
   * @param field field name
   * @return map with id, field, and links
   */
  public Map<String, Object> getNeighborsById(String id, String field) {
    if (id == null || id.isEmpty()) {
      throw new IllegalArgumentException("id is required");
    }
    if (field == null || field.isEmpty()) {
      throw new IllegalArgumentException("field is required");
    }

    try {
      HttpRequest request =
          buildGetRequest("/collection/" + name + "/objects/" + id + "/field/" + field + "/links");
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() != 200) {
        EndeeApiException.raiseException(response.statusCode(), response.body());
      }

      @SuppressWarnings("unchecked")
      Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
      return result;
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new EndeeException("Failed to get neighbors", e);
    }
  }

  // ── delete ──────────────────────────────────────────────────────────────────

  /** Deletes a single object by ID. */
  public Map<String, Object> deleteObject(String id) {
    try {
      HttpRequest request = buildDeleteRequest("/collection/" + name + "/objects/" + id);
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() != 200) {
        EndeeApiException.raiseException(response.statusCode(), response.body());
      }

      @SuppressWarnings("unchecked")
      Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
      return result;
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new EndeeException("Failed to delete object", e);
    }
  }

  /** Deletes objects matching a filter. */
  public Map<String, Object> deleteByFilter(List<Map<String, Object>> filter) {
    if (filter == null || filter.isEmpty()) {
      throw new IllegalArgumentException(
          "filter must be a non-empty array, e.g. [{'field': {'$op': value}}]");
    }

    try {
      String jsonBody = JsonUtils.toJson(Map.of("filter", filter));
      HttpRequest request = buildDeleteJsonRequest("/collection/" + name + "/objects", jsonBody);
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() != 200) {
        EndeeApiException.raiseException(response.statusCode(), response.body());
      }

      @SuppressWarnings("unchecked")
      Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
      return result;
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new EndeeException("Failed to delete by filter", e);
    }
  }

  // ── update filters ──────────────────────────────────────────────────────────

  /** Updates filter tags on existing objects. */
  public Map<String, Object> updateFilters(List<UpdateFilterParams> updates) {
    if (updates == null || updates.isEmpty()) {
      throw new IllegalArgumentException("updates must be a non-empty list");
    }

    List<Map<String, Object>> payload = new ArrayList<>();
    for (UpdateFilterParams update : updates) {
      if (update.getFilter() != null) {
        ValidationUtils.validateFilter(update.getFilter());
      }
      Map<String, Object> entry = new HashMap<>();
      entry.put("id", update.getId());
      entry.put("filter", update.getFilter() != null ? update.getFilter() : Map.of());
      payload.add(entry);
    }

    try {
      String jsonBody = JsonUtils.toJson(Map.of("updates", payload));
      HttpRequest request = buildPostJsonRequest("/collection/" + name + "/filters", jsonBody);
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() != 200) {
        EndeeApiException.raiseException(response.statusCode(), response.body());
      }

      @SuppressWarnings("unchecked")
      Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
      return result;
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new EndeeException("Failed to update filters", e);
    }
  }

  // ── describe ────────────────────────────────────────────────────────────────

  /** Fetches collection metadata from the server. */
  public Map<String, Object> describe() {
    try {
      HttpRequest request = buildGetRequest("/collection/" + name);
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() != 200) {
        EndeeApiException.raiseException(response.statusCode(), response.body());
      }

      @SuppressWarnings("unchecked")
      Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);

      // Update local fields metadata
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> updatedFields =
          (List<Map<String, Object>>) result.getOrDefault("fields", List.of());
      this.fields = updatedFields;

      return result;
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new EndeeException("Failed to describe collection", e);
    }
  }

  // ── rebuild ─────────────────────────────────────────────────────────────────

  /**
   * Rebuilds HNSW graphs for one or more fields (async).
   *
   * @param fieldSpecs list of field specs, e.g. [{"field": "embedding", "M": 20, "ef_con": 200}]
   */
  public Map<String, Object> rebuild(List<Map<String, Object>> fieldSpecs) {
    if (fieldSpecs == null || fieldSpecs.isEmpty()) {
      throw new IllegalArgumentException("rebuild requires a non-empty list of field specs");
    }
    for (Map<String, Object> spec : fieldSpecs) {
      if (!spec.containsKey("field") || spec.get("field") == null) {
        throw new IllegalArgumentException("Each field spec must include a 'field' name");
      }
    }

    try {
      String jsonBody = JsonUtils.toJson(Map.of("fields", fieldSpecs));
      HttpRequest request = buildPostJsonRequest("/collection/" + name + "/rebuild", jsonBody);
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() != 200 && response.statusCode() != 202) {
        EndeeApiException.raiseException(response.statusCode(), response.body());
      }

      @SuppressWarnings("unchecked")
      Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
      return result;
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new EndeeException("Failed to rebuild collection", e);
    }
  }

  /** Returns the current rebuild status (database-level). */
  public Map<String, Object> rebuildStatus() {
    try {
      HttpRequest request = buildGetRequest("/status/rebuild");
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() != 200) {
        EndeeApiException.raiseException(response.statusCode(), response.body());
      }

      @SuppressWarnings("unchecked")
      Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
      return result;
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new EndeeException("Failed to get rebuild status", e);
    }
  }

  // ── maintenance ─────────────────────────────────────────────────────────────

  /** Defragments the collection's storage in place. */
  public Map<String, Object> shrink() {
    try {
      HttpRequest request = buildPostJsonRequest("/collection/" + name + "/shrink", "{}");
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() != 200) {
        EndeeApiException.raiseException(response.statusCode(), response.body());
      }

      @SuppressWarnings("unchecked")
      Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
      return result;
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new EndeeException("Failed to shrink collection", e);
    }
  }

  /** Creates a backup of this collection (async). */
  public Map<String, Object> createBackup(String backupName) {
    if (backupName == null || backupName.isEmpty()) {
      throw new IllegalArgumentException("backup name is required");
    }

    try {
      String jsonBody = JsonUtils.toJson(Map.of("name", backupName));
      HttpRequest request = buildPostJsonRequest("/collection/" + name + "/backup", jsonBody);
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() != 200
          && response.statusCode() != 201
          && response.statusCode() != 202) {
        EndeeApiException.raiseException(response.statusCode(), response.body());
      }

      @SuppressWarnings("unchecked")
      Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
      return result;
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new EndeeException("Failed to create backup", e);
    }
  }

  // ── vector normalization helpers ────────────────────────────────────────────

  private static double[] normalizeDense(double[] vector, String spaceType) {
    if (!"cosine".equals(spaceType)) {
      return vector;
    }
    double norm = computeNorm(vector);
    if (norm < 1e-10) {
      return vector;
    }
    double[] normalized = new double[vector.length];
    for (int i = 0; i < vector.length; i++) {
      normalized[i] = vector[i] / norm;
    }
    return normalized;
  }

  private static double computeNorm(double[] vector) {
    double sumSquares = 0;
    for (double v : vector) {
      sumSquares += v * v;
    }
    return Math.sqrt(sumSquares);
  }

  private static void validateVectorValues(double[] vector, String objectId) {
    for (double v : vector) {
      if (Double.isNaN(v) || Double.isInfinite(v)) {
        throw new IllegalArgumentException(
            "Object '" + objectId + "' contains non-finite vector value (NaN or Inf)");
      }
    }
  }

  // ── HTTP helpers ────────────────────────────────────────────────────────────

  private HttpRequest buildGetRequest(String path) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .header("Content-Type", "application/json")
            .timeout(DEFAULT_TIMEOUT)
            .GET();

    if (token != null && !token.isBlank()) {
      builder.header("Authorization", token);
    }
    return builder.build();
  }

  private HttpRequest buildPostJsonRequest(String path, String jsonBody) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .header("Content-Type", "application/json")
            .timeout(DEFAULT_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody));

    if (token != null && !token.isBlank()) {
      builder.header("Authorization", token);
    }
    return builder.build();
  }

  private HttpRequest buildPostMsgpackRequest(String path, byte[] body) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .header("Content-Type", "application/msgpack")
            .timeout(DEFAULT_TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body));

    if (token != null && !token.isBlank()) {
      builder.header("Authorization", token);
    }
    return builder.build();
  }

  private HttpRequest buildDeleteRequest(String path) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder().uri(URI.create(baseUrl + path)).timeout(DEFAULT_TIMEOUT).DELETE();

    if (token != null && !token.isBlank()) {
      builder.header("Authorization", token);
    }
    return builder.build();
  }

  private HttpRequest buildDeleteJsonRequest(String path, String jsonBody) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .header("Content-Type", "application/json")
            .timeout(DEFAULT_TIMEOUT)
            .method("DELETE", HttpRequest.BodyPublishers.ofString(jsonBody));

    if (token != null && !token.isBlank()) {
      builder.header("Authorization", token);
    }
    return builder.build();
  }
}
