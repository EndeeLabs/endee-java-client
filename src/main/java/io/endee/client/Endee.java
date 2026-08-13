package io.endee.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.endee.client.exception.EndeeApiException;
import io.endee.client.exception.EndeeException;
import io.endee.client.util.ValidationUtils;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main Endee client for the Endee vector database (v2 Collections API).
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * Endee client = new Endee("db_name:secret:region");
 *
 * // Create a collection
 * client.createCollection("my_docs", List.of(
 *     Map.of("name", "embedding", "type", "vector",
 *            "params", Map.of("dimension", 768, "space_type", "cosine", "precision", "int8")),
 *     Map.of("name", "keywords", "type", "sparse", "sparse_model", "default")
 * ));
 *
 * // Get a collection
 * Collection collection = client.getCollection("my_docs");
 * }</pre>
 */
public class Endee {
  private static final Logger logger = LoggerFactory.getLogger(Endee.class);
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
  private static final Set<String> VALID_DB_TYPES = Set.of("starter", "pro", "scale", "enterprise");
  private static final Set<String> VALID_TOKEN_TYPES = Set.of("rw", "r");

  private String token;
  private String baseUrl;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;

  /** Creates a new Endee client without authentication. Uses local server. */
  public Endee() {
    this(null);
  }

  /**
   * Creates a new Endee client.
   *
   * @param token the auth token. Format: {@code "db_name:secret"} or {@code
   *     "db_name:secret:region"}
   */
  public Endee(String token) {
    this.token = token;
    this.baseUrl = "http://127.0.0.1:8080/api/v2";
    this.objectMapper = new ObjectMapper();

    if (token != null && !token.isEmpty()) {
      String[] tokenParts = token.split(":");
      if (tokenParts.length > 2) {
        this.baseUrl = "https://" + tokenParts[2] + ".endee.io/api/v2";
        this.token = tokenParts[0] + ":" + tokenParts[1];
      }
    }

    this.httpClient =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(DEFAULT_TIMEOUT)
            .build();
  }

  /** Sets a custom base URL for the API. */
  public void setBaseUrl(String url) {
    this.baseUrl = url;
  }

  /** Sets the authentication token. */
  public void setToken(String token) {
    this.token = token;
  }

  /** Closes the underlying HTTP client and releases resources. */
  public void close() {
    // Java's HttpClient doesn't have an explicit close in JDK 17,
    // but we null the reference to allow GC
  }

  @Override
  public String toString() {
    return "Endee{baseUrl='" + baseUrl + "'}";
  }

  // ── Collection API ──────────────────────────────────────────────────────────

  /**
   * Creates a new collection with typed fields.
   *
   * @param name collection name
   * @param fields list of field definitions as maps
   * @return server response
   */
  public Map<String, Object> createCollection(String name, List<Map<String, Object>> fields) {
    if (!ValidationUtils.isValidCollectionName(name)) {
      throw new IllegalArgumentException(
          "Invalid collection name. Must be alphanumeric with underscores, max 48 chars, no '__' prefix.");
    }
    if (fields == null || fields.isEmpty()) {
      throw new IllegalArgumentException("At least one field is required");
    }

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("name", name);
    data.put("fields", fields);

    return call("POST", "/collection", data, Set.of(200, 201));
  }

  /** Lists all collections. */
  @SuppressWarnings("unchecked")
  public List<Map<String, Object>> listCollections() {
    Map<String, Object> result = call("GET", "/collection", null, Set.of(200));
    Object collections = result.get("collections");
    return collections instanceof List ? (List<Map<String, Object>>) collections : List.of();
  }

  /**
   * Gets a Collection object for performing operations.
   *
   * @param name collection name
   * @return Collection object
   */
  public Collection getCollection(String name) {
    Map<String, Object> metadata = call("GET", "/collection/" + name, null, Set.of(200));
    return new Collection(name, token, baseUrl, metadata);
  }

  /** Deletes a collection and all its data. */
  public Map<String, Object> deleteCollection(String name) {
    return call("DELETE", "/collection/" + name, null, Set.of(200));
  }

  // ── Database Admin (root token) ─────────────────────────────────────────────

  /** Creates a database. Returns the new db token string. */
  public String createDatabase(String dbName, String dbType) {
    requireNonEmpty(dbName, "db_name");
    String dt = dbType != null ? dbType.toLowerCase() : "enterprise";
    validateIn(dt, VALID_DB_TYPES, "db_type");
    Map<String, Object> result =
        call("POST", "/admin/dbs", Map.of("db_name", dbName, "db_type", dt), Set.of(200, 201));
    return (String) result.get("db_token");
  }

  /** Creates a database with default type "enterprise". */
  public String createDatabase(String dbName) {
    return createDatabase(dbName, "enterprise");
  }

  /** Lists all databases. */
  @SuppressWarnings("unchecked")
  public List<Map<String, Object>> listDatabases() {
    Map<String, Object> result = call("GET", "/admin/dbs", null, Set.of(200));
    Object dbs = result.get("dbs");
    return dbs instanceof List ? (List<Map<String, Object>>) dbs : List.of();
  }

  /** Gets a single database's info. */
  public Map<String, Object> getDatabase(String dbName) {
    requireNonEmpty(dbName, "db_name");
    return call("GET", "/dbs/" + dbName + "/info", null, Set.of(200));
  }

  /** Deletes a database and all its data. */
  public Map<String, Object> deleteDatabase(String dbName) {
    requireNonEmpty(dbName, "db_name");
    return call("DELETE", "/admin/dbs/" + dbName, null, Set.of(200));
  }

  /** Activates a previously deactivated database. */
  public Map<String, Object> activateDatabase(String dbName) {
    return call("POST", "/admin/dbs/" + dbName + "/activate", null, Set.of(200));
  }

  /** Deactivates a database. */
  public Map<String, Object> deactivateDatabase(String dbName) {
    return call("POST", "/admin/dbs/" + dbName + "/deactivate", null, Set.of(200));
  }

  /** Changes a database's tier. */
  public Map<String, Object> setDatabaseType(String dbName, String dbType) {
    String dt = dbType.toLowerCase();
    validateIn(dt, VALID_DB_TYPES, "db_type");
    return call("PUT", "/admin/dbs/" + dbName + "/type", Map.of("db_type", dt), Set.of(200));
  }

  // ── Admin collection views ──────────────────────────────────────────────────

  /** Lists collection names in a specific database. */
  @SuppressWarnings("unchecked")
  public List<String> listDbCollections(String dbName) {
    requireNonEmpty(dbName, "db_name");
    Map<String, Object> result =
        call("GET", "/admin/dbs/" + dbName + "/collection", null, Set.of(200));
    Object c = result.get("collections");
    return c instanceof List ? (List<String>) c : List.of();
  }

  /** Lists all collections across all databases (grouped by database). */
  @SuppressWarnings("unchecked")
  public List<Map<String, Object>> listAllCollections() {
    Map<String, Object> result = call("GET", "/admin/collection", null, Set.of(200));
    Object c = result.get("collections");
    return c instanceof List ? (List<Map<String, Object>>) c : List.of();
  }

  /** Deletes a collection inside a specific database. */
  public Map<String, Object> deleteDbCollection(String dbName, String collectionName) {
    requireNonEmpty(dbName, "db_name");
    requireNonEmpty(collectionName, "collection_name");
    return call(
        "DELETE", "/admin/dbs/" + dbName + "/collection/" + collectionName, null, Set.of(200));
  }

  // ── Token management (admin) ────────────────────────────────────────────────

  /** Creates a token for a database. Returns the new db token string. */
  public String createToken(String dbName, String name, String tokenType) {
    requireNonEmpty(dbName, "db_name");
    requireNonEmpty(name, "name");
    String tt = tokenType != null ? tokenType.toLowerCase() : "rw";
    validateIn(tt, VALID_TOKEN_TYPES, "token_type");
    Map<String, Object> result =
        call(
            "POST",
            "/admin/dbs/" + dbName + "/tokens",
            Map.of("name", name, "token_type", tt),
            Set.of(200, 201));
    return (String) result.get("db_token");
  }

  /** Creates a read-write token. */
  public String createToken(String dbName, String name) {
    return createToken(dbName, name, "rw");
  }

  /** Lists a database's tokens. */
  @SuppressWarnings("unchecked")
  public List<Map<String, Object>> listTokens(String dbName) {
    requireNonEmpty(dbName, "db_name");
    Map<String, Object> result = call("GET", "/admin/dbs/" + dbName + "/tokens", null, Set.of(200));
    Object t = result.get("tokens");
    return t instanceof List ? (List<Map<String, Object>>) t : List.of();
  }

  /** Deletes a database token by name. */
  public Map<String, Object> deleteToken(String dbName, String name) {
    requireNonEmpty(dbName, "db_name");
    requireNonEmpty(name, "name");
    return call("DELETE", "/admin/dbs/" + dbName + "/tokens/" + name, null, Set.of(200));
  }

  // ── Self-service token management ───────────────────────────────────────────

  /** Creates a token for your own database. Returns the new db token string. */
  public String createMyToken(String name, String tokenType) {
    requireNonEmpty(name, "name");
    String tt = tokenType != null ? tokenType.toLowerCase() : "rw";
    validateIn(tt, VALID_TOKEN_TYPES, "token_type");
    Map<String, Object> result =
        call("POST", "/tokens", Map.of("name", name, "token_type", tt), Set.of(200, 201));
    return (String) result.get("db_token");
  }

  /** Creates a read-write token for your own database. */
  public String createMyToken(String name) {
    return createMyToken(name, "rw");
  }

  /** Lists your own database's tokens. */
  @SuppressWarnings("unchecked")
  public List<Map<String, Object>> listMyTokens() {
    Map<String, Object> result = call("GET", "/tokens", null, Set.of(200));
    Object t = result.get("tokens");
    return t instanceof List ? (List<Map<String, Object>>) t : List.of();
  }

  /** Deletes one of your own database's tokens by name. */
  public Map<String, Object> deleteMyToken(String name) {
    requireNonEmpty(name, "name");
    return call("DELETE", "/tokens/" + name, null, Set.of(200));
  }

  // ── Server info ─────────────────────────────────────────────────────────────

  /** Server health check. Returns {status, timestamp}. */
  public Map<String, Object> health() {
    return call("GET", "/health", null, Set.of(200));
  }

  /** Server stats. Returns {version, uptime, total_requests}. */
  public Map<String, Object> stats() {
    return call("GET", "/stats", null, Set.of(200));
  }

  // ── Backups ─────────────────────────────────────────────────────────────────

  /** Lists this database's backups. */
  public Object listBackups() {
    return call("GET", "/backup", null, Set.of(200));
  }

  /** Gets metadata for one backup. */
  public Map<String, Object> backupInfo(String backupName) {
    requireNonEmpty(backupName, "backup_name");
    return call("GET", "/backup/" + backupName + "/info", null, Set.of(200));
  }

  /** Gets the current backup status. */
  public Map<String, Object> backupStatus() {
    return call("GET", "/status/backup", null, Set.of(200));
  }

  /** Gets the current restore status. */
  public Map<String, Object> restoreStatus() {
    return call("GET", "/status/restore", null, Set.of(200));
  }

  /** Restores a backup into a new collection. */
  public Map<String, Object> restoreBackup(String backupName, String targetCollectionName) {
    requireNonEmpty(backupName, "backup_name");
    requireNonEmpty(targetCollectionName, "target_collection_name");
    return call(
        "POST",
        "/backup/" + backupName + "/restore",
        Map.of("target_collection_name", targetCollectionName),
        Set.of(200, 201, 202));
  }

  /** Deletes a backup. */
  public Map<String, Object> deleteBackup(String backupName) {
    requireNonEmpty(backupName, "backup_name");
    return call("DELETE", "/backup/" + backupName, null, Set.of(200, 204));
  }

  /**
   * Downloads a backup as a .tar file.
   *
   * @param backupName name of the backup
   * @param destPath local file path to write the .tar to
   * @param dbName optional database name (for root-token multi-db targeting)
   * @return the destination path
   */
  public String downloadBackup(String backupName, String destPath, String dbName) {
    requireNonEmpty(backupName, "backup_name");
    requireNonEmpty(destPath, "dest_path");

    Path dest = Path.of(destPath);
    if (Files.isDirectory(dest)) {
      dest = dest.resolve(backupName + ".tar");
    }
    String resolvedPath = dest.toString();

    StringBuilder url =
        new StringBuilder(baseUrl)
            .append("/backup/")
            .append(backupName)
            .append("/download?token=")
            .append(URLEncoder.encode(token != null ? token : "", StandardCharsets.UTF_8));
    if (dbName != null && !dbName.isEmpty()) {
      url.append("&db=").append(URLEncoder.encode(dbName, StandardCharsets.UTF_8));
    }

    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(url.toString()))
              .timeout(DEFAULT_TIMEOUT)
              .GET()
              .build();
      HttpResponse<byte[]> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() != 200) {
        EndeeApiException.raiseException(response.statusCode(), new String(response.body()));
      }
      Files.write(Path.of(resolvedPath), response.body());
      return resolvedPath;
    } catch (EndeeException e) {
      throw e;
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new EndeeException("Download backup failed", e);
    }
  }

  /** Downloads a backup (no db_name). */
  public String downloadBackup(String backupName, String destPath) {
    return downloadBackup(backupName, destPath, null);
  }

  /**
   * Uploads a backup .tar file.
   *
   * @param filePath path to a .tar backup file
   * @param backupName optional name for the backup (defaults to filename without extension)
   * @return server response
   */
  @SuppressWarnings("unchecked")
  public Map<String, Object> uploadBackup(String filePath, String backupName) {
    requireNonEmpty(filePath, "file_path");
    Path path = Path.of(filePath);
    String fileName = path.getFileName().toString();
    if (!fileName.endsWith(".tar")) {
      throw new IllegalArgumentException("backup file must be a .tar");
    }

    String name =
        (backupName != null && !backupName.isEmpty())
            ? backupName
            : fileName.substring(0, fileName.length() - 4);

    try {
      byte[] fileBytes = Files.readAllBytes(path);

      String url =
          baseUrl + "/backup/upload?name=" + URLEncoder.encode(name, StandardCharsets.UTF_8);

      HttpRequest.Builder builder =
          HttpRequest.newBuilder()
              .uri(URI.create(url))
              .timeout(DEFAULT_TIMEOUT)
              .header("Content-Type", "application/x-tar")
              .POST(HttpRequest.BodyPublishers.ofByteArray(fileBytes));

      if (token != null && !token.isEmpty()) {
        builder.header("Authorization", token);
      }

      HttpResponse<String> response =
          httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() != 200 && response.statusCode() != 201) {
        EndeeApiException.raiseException(response.statusCode(), response.body());
      }

      String body = response.body();
      if (body == null || body.isBlank()) return Map.of();
      try {
        return objectMapper.readValue(body, Map.class);
      } catch (Exception e) {
        return Map.of("message", body);
      }
    } catch (EndeeException e) {
      throw e;
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new EndeeException("Upload backup failed", e);
    }
  }

  /** Uploads a backup .tar file (name derived from filename). */
  public Map<String, Object> uploadBackup(String filePath) {
    return uploadBackup(filePath, null);
  }

  // ── Internal HTTP helpers ───────────────────────────────────────────────────

  @SuppressWarnings("unchecked")
  private Map<String, Object> call(
      String method, String path, Map<String, Object> json, Set<Integer> okStatuses) {
    try {
      HttpRequest request = buildRequest(method, path, json);
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());

      if (!okStatuses.contains(response.statusCode())) {
        logger.error("Error: {}", response.body());
        EndeeApiException.raiseException(response.statusCode(), response.body());
      }

      String body = response.body();
      if (body == null || body.isBlank()) {
        return Map.of();
      }
      return objectMapper.readValue(body, Map.class);
    } catch (EndeeException e) {
      throw e;
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new EndeeException("Request failed: " + method + " " + path, e);
    }
  }

  private HttpRequest buildRequest(String method, String path, Map<String, Object> json) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder().uri(URI.create(baseUrl + path)).timeout(DEFAULT_TIMEOUT);

    if (token != null && !token.isEmpty()) {
      builder.header("Authorization", token);
    }

    if (json != null) {
      String jsonBody;
      try {
        jsonBody = objectMapper.writeValueAsString(json);
      } catch (Exception e) {
        throw new EndeeException("Failed to serialize request body", e);
      }
      builder.header("Content-Type", "application/json");
      builder.method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
    } else {
      switch (method) {
        case "GET" -> builder.GET();
        case "DELETE" -> builder.DELETE();
        case "POST" ->
            builder
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody());
        case "PUT" ->
            builder
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.noBody());
        default -> builder.method(method, HttpRequest.BodyPublishers.noBody());
      }
    }

    return builder.build();
  }

  private static void requireNonEmpty(String value, String name) {
    if (value == null || value.isEmpty()) {
      throw new IllegalArgumentException(name + " is required");
    }
  }

  private static void validateIn(String value, Set<String> valid, String name) {
    if (!valid.contains(value)) {
      throw new IllegalArgumentException(name + " must be one of " + valid);
    }
  }
}
