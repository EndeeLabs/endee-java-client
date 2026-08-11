# Endee - Java Vector Database Client

Java client for the [Endee](https://endee.io) vector database. Supports multi-field collections (dense, sparse, multi-vector), filtered search, client-side RRF reranking, backups, and admin operations.

## Key Features

- **Multi-field collections** — combine dense, sparse, and multi-vector fields in one collection
- **Client-side RRF reranking** — fuse results from multiple fields with weighted Reciprocal Rank Fusion
- **Flexible filters** — `$eq`, `$in`, `$range`, `$gt`, `$gte`, `$lt`, `$lte`
- **High performance** — HTTP/2, MessagePack wire format, DEFLATE-compressed metadata
- **Typed exceptions** — specific exception types for each HTTP error code
- **Admin & backup** — database CRUD, token management, backup/restore/download/upload
- **Java 17+** — modern APIs, builder patterns, compile-time type safety

## Requirements

- Java 17 or higher
- Endee server running (see [Quick Start](https://docs.endee.io/quick-start))

## Installation

### Maven

```xml
<dependency>
    <groupId>io.endee</groupId>
    <artifactId>endee-java-client</artifactId>
    <version>2.0.0</version>
</dependency>
```

### Gradle

```groovy
implementation 'io.endee:endee-java-client:2.0.0'
```

---

## Initialize the Client

```java
import io.endee.client.Endee;
import io.endee.client.Collection;
import io.endee.client.types.*;

// Local server (defaults to http://127.0.0.1:8080/api/v2)
Endee client = new Endee();

// With an auth token
Endee client = new Endee("db_name:secret");

// With a region (connects to https://{region}.endee.io/api/v2)
Endee client = new Endee("db_name:secret:us-east-1");

// Custom base URL
client.setBaseUrl("http://0.0.0.0:8081/api/v2");
```

---

## Collection Management

### Create a Collection

Collections hold one or more typed fields. Each field is either `vector` (dense), `sparse`, or `multi_vector`.

```java
// Dense + sparse hybrid collection
Map<String, Object> result = client.createCollection("my_docs", List.of(
    Map.of(
        "name", "embedding",
        "type", "vector",
        "params", Map.of(
            "dimension", 768,
            "space_type", "cosine",   // "cosine", "l2", or "ip"
            "precision", "int8",      // "binary", "int8", "int8e", "int16", "float16", "float32"
            "M", 16,                  // HNSW connectivity
            "ef_con", 128             // HNSW construction quality
        )
    ),
    Map.of(
        "name", "keywords",
        "type", "sparse",
        "sparse_model", "default"     // "default" or "endee_bm25"
    )
));
// Output: {message=collection created}
```

**Field types:**

| Type | Description | Query type |
|------|-------------|------------|
| `vector` | Dense embedding | `double[]` |
| `sparse` | Sparse term weights | `SparseData(int[] indices, double[] values)` |
| `multi_vector` | Multiple dense vectors per object | `double[][]` |

### List, Get, Delete

```java
// List all collections
List<Map<String, Object>> collections = client.listCollections();
// Output: [{name=my_docs, fields=[...], count=1000}, ...]

// Get a collection reference (for upsert, search, etc.)
Collection collection = client.getCollection("my_docs");

// Describe a collection (refreshes metadata from server)
Map<String, Object> desc = collection.describe();
// Output: {name=my_docs, fields=[{name=embedding, type=vector, params={...}}, ...], count=1000}

// Delete a collection (irreversible)
client.deleteCollection("my_docs");
// Output: {message=collection deleted}
```

---

## Upserting Objects

Use the `ObjectItem` builder to construct objects with any combination of field types.

```java
Collection collection = client.getCollection("my_docs");

List<ObjectItem> objects = List.of(
    ObjectItem.builder("doc1")
        .vector("embedding", new double[] {0.1, 0.2, 0.3, /* ... 768 dims */})
        .sparse("keywords", new SparseData(
            new int[] {10, 500, 12000},       // term positions
            new double[] {0.8, 0.5, 0.3}      // term weights
        ))
        .meta(Map.of("title", "First Document", "author", "Alice"))
        .filter(Map.of("category", "tech", "year", 2024))
        .build(),

    ObjectItem.builder("doc2")
        .vector("embedding", new double[] {0.4, 0.5, 0.6, /* ... */})
        .sparse("keywords", new SparseData(
            new int[] {25, 9000, 20000},
            new double[] {0.3, 0.7, 0.1}
        ))
        .meta(Map.of("title", "Second Document", "author", "Bob"))
        .filter(Map.of("category", "science", "year", 2023))
        .build()
);

Map<String, Object> result = collection.upsert(objects);
// Output: {message=2 objects upserted}
```

**ObjectItem fields:**

| Field | Required | Description |
|-------|----------|-------------|
| `id` | Yes | Unique string identifier |
| `.vector(fieldName, double[])` | Per field | Dense vector (length must match field dimension) |
| `.sparse(fieldName, SparseData)` | Per field | Sparse vector (indices + values) |
| `.multiVector(fieldName, double[][])` | Per field | Multiple dense vectors |
| `.meta(Map)` | No | Arbitrary metadata — stored compressed, returned on search |
| `.filter(Map)` | No | Key-value pairs for filtered queries |

**Limits:**
- Max 10,000 objects per `upsert` call
- IDs must be unique within a batch
- Vector values must be finite (no `NaN` or `Inf`)
- Max vector dimension: 8,000

---

## Searching

### Single-field Search

```java
Map<String, List<SearchHit>> results = collection.search(
    Map.of("embedding", Map.of(
        "query", new double[] {0.15, 0.25, 0.35, /* ... */},
        "limit", 10                // results per field (default: 10, max: 4,096)
    ))
);

// Results are per-field
for (SearchHit hit : results.get("embedding")) {
    System.out.printf("ID: %s  Score: %.4f  Meta: %s  Filter: %s%n",
        hit.getId(), hit.getSimilarity(), hit.getMeta(), hit.getFilter());
}
// Output:
// ID: doc1  Score: 0.9823  Meta: {title=First Document, author=Alice}  Filter: {category=tech, year=2024}
// ID: doc2  Score: 0.9156  Meta: {title=Second Document, author=Bob}  Filter: {category=science, year=2023}
```

### Filtered Search

All filter conditions are combined with **logical AND**:

```java
Map<String, List<SearchHit>> results = collection.search(
    Map.of("embedding", Map.of(
        "query", new double[] {0.15, 0.25, 0.35, /* ... */},
        "limit", 5
    )),
    List.of(
        Map.of("category", Map.of("$eq", "tech")),
        Map.of("year", Map.of("$gte", 2023))
    )
);
```

**Filter operators:**

| Operator | Description | Example |
|----------|-------------|---------|
| `$eq` | Exact match | `Map.of("status", Map.of("$eq", "published"))` |
| `$in` | Match any value in list | `Map.of("tags", Map.of("$in", List.of("ai", "ml")))` |
| `$range` | Numeric range (inclusive) | `Map.of("score", Map.of("$range", List.of(70, 95)))` |
| `$gt` | Greater than | `Map.of("year", Map.of("$gt", 2020))` |
| `$gte` | Greater than or equal | `Map.of("year", Map.of("$gte", 2020))` |
| `$lt` | Less than | `Map.of("score", Map.of("$lt", 50))` |
| `$lte` | Less than or equal | `Map.of("score", Map.of("$lte", 50))` |

### Multi-field Search

Search across multiple fields simultaneously:

```java
Map<String, Map<String, Object>> queryFields = new LinkedHashMap<>();
queryFields.put("embedding", Map.of(
    "query", new double[] {0.5, 0.5, 0.5, /* ... */},
    "limit", 10
));
queryFields.put("keywords", Map.of(
    "query", new SparseData(new int[] {42, 999}, new double[] {0.8, 0.6}),
    "limit", 10
));

Map<String, List<SearchHit>> results = collection.search(queryFields);
// results.get("embedding") — dense search results
// results.get("keywords") — sparse search results
```

### Client-side RRF Reranking

Fuse per-field results into a single ranked list using Reciprocal Rank Fusion:

```java
import io.endee.client.Reranker;

// Fuse with weighted fields
List<SearchHit> fused = Reranker.rerank(
    results,                                     // per-field results from search()
    10,                                          // max results to return
    Map.of("embedding", 0.6, "keywords", 0.4),  // field weights (must sum to 1.0)
    60                                           // RRF rank constant k
);

for (SearchHit hit : fused) {
    System.out.printf("ID: %s  RRF Score: %.6f%n", hit.getId(), hit.getSimilarity());
}
// Output:
// ID: doc1  RRF Score: 0.016393
// ID: doc2  RRF Score: 0.013115
// ...

// Convenience: uniform weights, default limit (10) and k (60)
List<SearchHit> fused = Reranker.rerank(results, Map.of("embedding", 0.5, "keywords", 0.5));
```

### Advanced Search Options

```java
Map<String, List<SearchHit>> results = collection.search(
    queryFields,
    filter,              // List<Map<String, Object>> — filter conditions (null for none)
    128,                 // ef_search — HNSW search depth (default: 128, max: 1,024)
    10_000,              // prefilter_cardinality_threshold (1,000–1,000,000)
    0                    // filter_boost_percentage (0–100)
);
```

---

## Get Objects

Fetch full objects by ID, including all vector data:

```java
List<ObjectInfo> objects = collection.getObjects(List.of("doc1", "doc2"));

for (ObjectInfo obj : objects) {
    System.out.println("ID: " + obj.getId());
    System.out.println("Meta: " + obj.getMeta());
    System.out.println("Filter: " + obj.getFilter());
    System.out.println("Dense fields: " + obj.getVectors().keySet());
    System.out.println("Sparse fields: " + obj.getSparses().keySet());
    System.out.println("Multi-vector fields: " + obj.getMultiVectors().keySet());
}
// Output:
// ID: doc1
// Meta: {title=First Document, author=Alice}
// Filter: {category=tech, year=2024}
// Dense fields: [embedding]
// Sparse fields: [keywords]
// Multi-vector fields: []
```

**ObjectInfo fields:**

| Field | Type | Description |
|-------|------|-------------|
| `id` | `String` | Object ID |
| `meta` | `Map<String, Object>` | Metadata |
| `filter` | `Map<String, Object>` | Filter values |
| `vectors` | `Map<String, double[]>` | Dense vectors by field name |
| `sparses` | `Map<String, SparseData>` | Sparse vectors by field name |
| `multiVectors` | `Map<String, double[][]>` | Multi-vectors by field name |

---

## Delete Objects

```java
// Delete by ID
Map<String, Object> result = collection.deleteObject("doc1");
// Output: {message=1 rows deleted}

// Delete by filter
Map<String, Object> result = collection.deleteByFilter(
    List.of(Map.of("category", Map.of("$eq", "tech")))
);
// Output: {message=5 rows deleted}
```

---

## Update Filters

Update filter fields on existing objects without re-upserting. The entire filter object is replaced:

```java
import io.endee.client.types.UpdateFilterParams;

Map<String, Object> result = collection.updateFilters(List.of(
    new UpdateFilterParams("doc1", Map.of("category", "ml", "year", 2025)),
    new UpdateFilterParams("doc2", Map.of("category", "physics", "year", 2024))
));
// Output: {message=2 filters updated}
```

---

## Index Maintenance

### Rebuild

Rebuilds HNSW graphs with new parameters. Runs asynchronously — poll `rebuildStatus()` until complete:

```java
// Trigger rebuild
Map<String, Object> result = collection.rebuild(
    List.of(Map.of("field", "embedding", "M", 20, "ef_con", 200))
);
// Output: {message=rebuild started}

// Poll until complete
while (true) {
    Map<String, Object> status = collection.rebuildStatus();
    System.out.println(status);
    // Output: {status=in_progress, vectors_processed=500, total_vectors=1000, percent_complete=50}
    if ("completed".equals(status.get("status"))) break;
    Thread.sleep(2000);
}
```

### Shrink

Defragments the collection's storage after deletions:

```java
Map<String, Object> result = collection.shrink();
// Output: {message=shrink complete}
```

---

## Backups

### Collection-level Backup

```java
// Create a backup (async — poll activeBackup() until done)
Map<String, Object> result = collection.createBackup("my_backup");
// Output: {message=backup started}

// Poll until complete
while (true) {
    Map<String, Object> active = client.activeBackup();
    if (!Boolean.TRUE.equals(active.get("active"))) break;
    Thread.sleep(2000);
}
```

### Backup Management

```java
// List all backups
Object backups = client.listBackups();

// Get backup info
Map<String, Object> info = client.backupInfo("my_backup");

// Active backup status
Map<String, Object> active = client.activeBackup();

// Restore a backup into a new collection
Map<String, Object> result = client.restoreBackup("my_backup", "restored_collection");

// Delete a backup
client.deleteBackup("my_backup");
```

### Download & Upload Backups

```java
// Download a backup as a .tar file
String path = client.downloadBackup("my_backup", "/tmp/my_backup.tar");
// Output: "/tmp/my_backup.tar"

// Download with db_name (for root-token multi-database targeting)
client.downloadBackup("my_backup", "/tmp/my_backup.tar", "my_database");

// Upload a .tar backup file
Map<String, Object> result = client.uploadBackup("/tmp/my_backup.tar");
// Output: {message=backup uploaded}
```

---

## Server Info

```java
// Health check
Map<String, Object> health = client.health();
// Output: {status=ok, timestamp=1234567890}

// Server stats
Map<String, Object> stats = client.stats();
// Output: {version=2.0.0, uptime=3600, total_requests=15000}
```

---

## Admin Features

Admin operations require a root token.

### Database Management

```java
Endee admin = new Endee("root_token");

// Create a database (returns the new db token)
String dbToken = admin.createDatabase("my_db", "enterprise");
// db_type options: "starter", "pro", "scale", "enterprise"

// List all databases
List<Map<String, Object>> dbs = admin.listDatabases();

// Get database info
Map<String, Object> info = admin.getDatabase("my_db");

// Activate / deactivate
admin.activateDatabase("my_db");
admin.deactivateDatabase("my_db");

// Change database tier
admin.setDatabaseType("my_db", "pro");

// Delete a database
admin.deleteDatabase("my_db");
```

### Admin Collection Views

```java
// List collections in a specific database
List<Map<String, Object>> cols = admin.listDbCollections("my_db");

// List all collections across all databases
List<Map<String, Object>> allCols = admin.listAllCollections();

// Delete a collection in a specific database
admin.deleteDbCollection("my_db", "my_collection");
```

### Token Management (Admin)

```java
// Create a token for a database
String token = admin.createToken("my_db", "analytics_token", "r");
// token_type: "rw" (read-write) or "r" (read-only)

// List tokens
List<Map<String, Object>> tokens = admin.listTokens("my_db");

// Delete a token
admin.deleteToken("my_db", "analytics_token");
```

### Self-service Token Management

Available to any authenticated user for their own database:

```java
Endee client = new Endee("my_db:my_secret");

// Create a token
String token = client.createMyToken("my_token", "rw");

// List my tokens
List<Map<String, Object>> tokens = client.listMyTokens();

// Delete a token
client.deleteMyToken("my_token");
```

---

## Precision Options

| Value | Wire | Description |
|-------|------|-------------|
| `binary` | `binary` | 1 bit/dim — maximum compression, fastest search |
| `int8` | `int8` | Default — best balance of accuracy and performance |
| `int8e` | `int8e` | Enhanced INT8 with error correction |
| `int16` | `int16` | Higher accuracy than INT8 |
| `float16` | `float16` | Good compromise for embeddings |
| `float32` | `float32` | Maximum precision |

## Space Types

| Value | Wire | Best For |
|-------|------|----------|
| `cosine` | `cosine` | Normalized embeddings (default) |
| `l2` | `l2` | Spatial / Euclidean distance |
| `ip` | `ip` | Unnormalized embeddings (dot product) |

---

## Error Handling

The client uses a typed exception hierarchy. All exceptions extend `EndeeException`:

```java
import io.endee.client.exception.*;

try {
    collection.getObjects(List.of("missing_id"));
} catch (NotFoundException e) {
    // 404 — object or collection not found
    System.err.println("Not found: " + e.getMessage());
} catch (AuthenticationException e) {
    // 401 — invalid or expired token
    System.err.println("Auth failed: " + e.getMessage());
} catch (EndeeApiException e) {
    // Catch-all for any API error
    System.err.println("HTTP " + e.getStatusCode() + ": " + e.getErrorBody());
} catch (EndeeException e) {
    // Network or serialization errors
    System.err.println("Client error: " + e.getMessage());
} catch (IllegalArgumentException e) {
    // Validation errors (invalid params, dimension mismatch, etc.)
    System.err.println("Validation: " + e.getMessage());
}
```

**Exception types:**

| Exception | HTTP Status | Trigger |
|-----------|-------------|---------|
| `EndeeApiException` | 400 | Bad request (base for all API errors) |
| `AuthenticationException` | 401 | Invalid or expired token |
| `SubscriptionException` | 402 | Quota exceeded or tier limit |
| `ForbiddenException` | 403 | Insufficient permissions |
| `NotFoundException` | 404 | Collection or object not found |
| `ConflictException` | 409 | Resource already exists |
| `ServerException` | 5xx | Server error |

---

## Complete Example

```java
import io.endee.client.Endee;
import io.endee.client.Collection;
import io.endee.client.Reranker;
import io.endee.client.types.*;
import java.util.*;

public class Example {
    public static void main(String[] args) throws Exception {
        Endee client = new Endee("db_name:secret:region");

        // 1. Create a hybrid collection
        client.createCollection("docs", List.of(
            Map.of("name", "embedding", "type", "vector",
                "params", Map.of("dimension", 768, "space_type", "cosine",
                    "precision", "int8", "M", 16, "ef_con", 128)),
            Map.of("name", "keywords", "type", "sparse",
                "sparse_model", "default")
        ));

        // 2. Get collection reference
        Collection collection = client.getCollection("docs");

        // 3. Upsert objects
        collection.upsert(List.of(
            ObjectItem.builder("doc1")
                .vector("embedding", new double[768])
                .sparse("keywords", new SparseData(
                    new int[] {10, 500, 1200},
                    new double[] {0.8, 0.5, 0.3}))
                .meta(Map.of("title", "Hello World"))
                .filter(Map.of("category", "tech", "score", 90))
                .build()
        ));

        // 4. Multi-field search
        Map<String, Map<String, Object>> query = new LinkedHashMap<>();
        query.put("embedding", Map.of(
            "query", new double[768], "limit", 5));
        query.put("keywords", Map.of(
            "query", new SparseData(new int[] {10, 500}, new double[] {0.9, 0.4}),
            "limit", 5));

        Map<String, List<SearchHit>> results = collection.search(
            query,
            List.of(Map.of("category", Map.of("$eq", "tech")))
        );

        // 5. Fuse results with RRF
        List<SearchHit> fused = Reranker.rerank(results, 10,
            Map.of("embedding", 0.6, "keywords", 0.4), 60);

        for (SearchHit hit : fused) {
            System.out.printf("ID: %s  Score: %.6f  Meta: %s%n",
                hit.getId(), hit.getSimilarity(), hit.getMeta());
        }

        // 6. Get full objects
        List<ObjectInfo> objects = collection.getObjects(List.of("doc1"));
        System.out.println("Vectors: " + objects.get(0).getVectors().keySet());

        // 7. Update filters
        collection.updateFilters(List.of(
            new UpdateFilterParams("doc1", Map.of("category", "ml", "score", 95))
        ));

        // 8. Rebuild and wait
        collection.rebuild(List.of(Map.of("field", "embedding", "M", 20, "ef_con", 200)));
        while (!"completed".equals(collection.rebuildStatus().get("status"))) {
            Thread.sleep(2000);
        }

        // 9. Backup, download, restore
        collection.createBackup("my_backup");
        while (Boolean.TRUE.equals(client.activeBackup().get("active"))) {
            Thread.sleep(2000);
        }
        client.downloadBackup("my_backup", "/tmp/my_backup.tar");
        client.restoreBackup("my_backup", "docs_restored");

        // 10. Cleanup
        client.deleteCollection("docs");
        client.deleteCollection("docs_restored");
        client.deleteBackup("my_backup");
    }
}
```

---

## API Reference

### `Endee` (Client)

| Method | Returns | Description |
|--------|---------|-------------|
| `Endee()` | — | Connect to local server |
| `Endee(String token)` | — | Connect with auth token |
| `setBaseUrl(String url)` | `void` | Override the base URL |
| `setToken(String token)` | `void` | Set the auth token |
| `createCollection(name, fields)` | `Map` | Create a new collection |
| `listCollections()` | `List<Map>` | List all collections |
| `getCollection(name)` | `Collection` | Get a Collection reference |
| `deleteCollection(name)` | `Map` | Delete a collection |
| `health()` | `Map` | Server health check |
| `stats()` | `Map` | Server stats |
| `listBackups()` | `Object` | List backups |
| `backupInfo(name)` | `Map` | Get backup metadata |
| `activeBackup()` | `Map` | Get active backup status |
| `restoreBackup(name, target)` | `Map` | Restore backup to new collection |
| `deleteBackup(name)` | `Map` | Delete a backup |
| `downloadBackup(name, destPath)` | `String` | Download backup as .tar |
| `downloadBackup(name, destPath, dbName)` | `String` | Download backup (multi-db) |
| `uploadBackup(filePath)` | `Map` | Upload a .tar backup |
| `createDatabase(name, type)` | `String` | Create database (admin) |
| `listDatabases()` | `List<Map>` | List databases (admin) |
| `getDatabase(name)` | `Map` | Get database info (admin) |
| `deleteDatabase(name)` | `Map` | Delete database (admin) |
| `activateDatabase(name)` | `Map` | Activate database (admin) |
| `deactivateDatabase(name)` | `Map` | Deactivate database (admin) |
| `setDatabaseType(name, type)` | `Map` | Change database tier (admin) |
| `listDbCollections(dbName)` | `List<Map>` | List collections in db (admin) |
| `listAllCollections()` | `List<Map>` | List all collections (admin) |
| `deleteDbCollection(db, col)` | `Map` | Delete collection in db (admin) |
| `createToken(db, name, type)` | `String` | Create db token (admin) |
| `listTokens(db)` | `List<Map>` | List db tokens (admin) |
| `deleteToken(db, name)` | `Map` | Delete db token (admin) |
| `createMyToken(name, type)` | `String` | Create own token |
| `listMyTokens()` | `List<Map>` | List own tokens |
| `deleteMyToken(name)` | `Map` | Delete own token |

### `Collection`

| Method | Returns | Description |
|--------|---------|-------------|
| `upsert(List<ObjectItem>)` | `Map` | Insert or update objects (max 10,000) |
| `search(queryFields)` | `Map<String, List<SearchHit>>` | Search (no filter) |
| `search(queryFields, filter)` | `Map<String, List<SearchHit>>` | Search with filter |
| `search(queryFields, filter, efSearch, prefilterThreshold, boostPct)` | `Map<String, List<SearchHit>>` | Search with all options |
| `getObjects(List<String> ids)` | `List<ObjectInfo>` | Fetch full objects by ID |
| `deleteObject(String id)` | `Map` | Delete object by ID |
| `deleteByFilter(List<Map>)` | `Map` | Delete objects matching filter |
| `updateFilters(List<UpdateFilterParams>)` | `Map` | Update filter fields |
| `describe()` | `Map` | Get collection metadata |
| `rebuild(List<Map> fieldSpecs)` | `Map` | Trigger HNSW rebuild |
| `rebuildStatus()` | `Map` | Poll rebuild progress |
| `shrink()` | `Map` | Defragment storage |
| `createBackup(String name)` | `Map` | Create a backup |

### `Reranker`

| Method | Returns | Description |
|--------|---------|-------------|
| `rerank(results, limit, fieldWeights, rrfK)` | `List<SearchHit>` | RRF fusion with all options |
| `rerank(results, fieldWeights)` | `List<SearchHit>` | RRF with default limit (10) and k (60) |
| `rerank(results, limit)` | `List<SearchHit>` | RRF with uniform weights |

---

## Code Formatting

This project uses [Spotless](https://github.com/diffplug/spotless) with [Google Java Format](https://github.com/google/google-java-format).

```bash
mvn spotless:apply   # auto-format all source files
mvn spotless:check   # verify formatting (runs in CI)
```

## Dependencies

- **Jackson** — JSON serialization
- **MessagePack** — binary serialization for vector payloads
- **SLF4J** — logging facade

## License

MIT

## Author

Pankaj Singh
