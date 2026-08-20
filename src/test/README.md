# Endee Java Client - Functional Test Suite

End-to-end functional tests for the [Endee](https://endee.io) Java client (v2 Collections API).
Runs against a live server and covers collections, objects, search, filtering, backup/restore,
maintenance, tokens, and admin management.

---

## Prerequisites

- Java 17 or higher, Maven
- A running Endee server (cloud or local)
- A valid `ENDEE_TOKEN` (database-level API token)
- `NDD_ROOT_TOKEN` (root token - optional, only needed for `AdminTest`)

---

## Running the tests

### Shell script (macOS / Linux) - recommended

The script sets environment variables and invokes `mvn test` from the project root.

```bash
# Show all options and examples
./src/test/run_tests.sh --help

# Full suite against the cloud endpoint
./src/test/run_tests.sh --token <api_token>

# Full suite against a local server
./src/test/run_tests.sh --token <api_token> --base-url http://localhost:8080/api/v2

# Include AdminTest (requires a root token)
./src/test/run_tests.sh --token <api_token> --root-token <root_token>

# Full local run with admin tests
./src/test/run_tests.sh --token <api_token> --root-token <root_token> \
  --base-url http://localhost:8080/api/v2

# Single class or single method
./src/test/run_tests.sh --token <api_token> -- -Dtest=FilteringTest
./src/test/run_tests.sh --token <api_token> -- -Dtest=FilteringTest#filterByEqOperator
```

### Manual setup (all platforms)

```bash
# 1. Set environment variables (see table below)
export ENDEE_TOKEN=<api_token>
export ENDEE_BASE_URL=http://localhost:8080/api/v2   # optional; defaults to the client's own default
export NDD_ROOT_TOKEN=<root_token>                   # optional; enables AdminTest

# 2. Run from the project root (where pom.xml lives)
mvn test                              # full suite
mvn test -Dtest=BackupTest            # single class
mvn test -Dtest=FilteringTest#filterByEqOperator   # single method
mvn test -Dsurefire.printSummary=true # verbose per-test output
```

There's also a manual smoke-test entrypoint (no `@Test` methods) at
`src/test/java/io/endee/client/ManualTest.java`, run via `mvn test-compile exec:java`.

---

## Environment variables

| Variable | Required | Description |
|----------|----------|-------------|
| `ENDEE_TOKEN` | Yes | Database-level API token. Used by all non-admin tests. |
| `ENDEE_BASE_URL` | No | Server base URL (e.g. `http://localhost:8080/api/v2`). Defaults to the `Endee` client's own default - derived from the token's region, or the local server. |
| `NDD_ROOT_TOKEN` | No | Root/admin token. If omitted, `AdminTest` is skipped automatically (`@EnabledIfEnvironmentVariable`). |

---

## Test files

Tests are grouped by feature area. Each class is self-contained with its own `@BeforeEach`/
`@AfterEach` fixture setup and teardown.

### Core collection and object operations

| Class | What is tested |
|-------|-----------------|
| `CollectionManagementTest` | `createCollection` (precisions, space types, HNSW params), `listCollections`, `getCollection`, `describe`, `deleteCollection`, hybrid creation |
| `ObjectOperationsTest` | `upsert` (single, batch, overwrite, meta/filter, NaN/Inf rejection, duplicate-ID detection, batch size limit, unknown field) and `deleteObject` |
| `GetObjectsTest` | `getObjects` - return shape, meta/filter/vector round-trips, non-existent IDs, mixed IDs, sparse and multi_vector collections |
| `NeighborsTest` | `getNeighborsById` - return shape, link validity/symmetry, sparse/unknown field rejection, multi_vector fields, client-side argument validation |

### Search and filtering

| Class | What is tested |
|-------|-----------------|
| `SearchingTest` | `search` - result structure, ordering, `limit`, `efSearch`, meta round-trips, parameter bounds |
| `FilteringTest` | Filter operators: `$eq`, `$in`, `$range`, `$gt`, `$gte`, `$lt`, `$lte`; multi-condition AND; per-result value correctness; sorted results. Post-filter ANN non-determinism uses soft log-and-continue checks instead of a hard assertion (see Design notes). |
| `FilterParamsTest` | `prefilterThreshold` (1,000-1,000,000) and `boostPercentage` (0-100) - accepted ranges and validation |
| `DeleteByFilterTest` | `deleteByFilter` - return shape, count accuracy, all filter operators, AND conditions, no-match case, corpus integrity |
| `UpdateFiltersTest` | `updateFilters` - return shape, updated values reflected in search/getObjects, new key addition, batch updates, numeric values, idempotency |

### Field types

| Class | What is tested |
|-------|-----------------|
| `SparseTest` | Sparse-only collections (default and `endee_bm25` models), upsert, search, meta round-trips, delete, describe |
| `HybridSearchTest` | Dense+sparse (hybrid) collections - dense-only, sparse-only, RRF hybrid search, per-field limits, filters |
| `MultiVectorTest` | ColBERT-style `multi_vector` fields - pooling methods, upsert, search, `getObjects`, `deleteByFilter`, `updateFilters`, `shrink`, `rebuild`, `createBackup` |
| `MultiFieldSearchTest` | Multi-field search - per-field result map, RRF fusion, field weights, `rrfK`, per-field limits, filters |
| `RerankTest` | Standalone `Reranker.rerank()` - result shape, limit, ordering, field weights, `rrfK`, dedup across fields, validation errors |

### Maintenance and backup

| Class | What is tested |
|-------|-----------------|
| `RebuildTest` | `rebuild` - response shape, custom M/ef_con, dense and multi_vector fields, collection stays searchable during rebuild; `rebuildStatus` |
| `ShrinkTest` | `shrink` - response shape, empty and populated collections, after deletions, dense and multi_vector |
| `BackupTest` | `createBackup`, `backupStatus`, `restoreStatus`, `listBackups`, `backupInfo`, `restoreBackup`, `deleteBackup`, `downloadBackup` (including directory-destination), `uploadBackup` (including the custom-name overload) - full lifecycle including async polling and download/upload round-trip via temp files |

### Infrastructure and admin

| Class | What is tested |
|-------|-----------------|
| `ServerInfoTest` | `health()` and `stats()` - return shapes, required keys, repeated calls; `toString()`/`close()` basics |
| `TokenManagementTest` | `createMyToken`, `listMyTokens`, `deleteMyToken` - full lifecycle, rw/r types, duplicate conflict, validation |
| `ErrorHandlingTest` | Client-side validation (names, NaN/Inf, batch limits, search bounds, filter key/value size limits), server-side error mapping (404/409), and pure unit tests of `EndeeApiException.raiseException` across every status code |
| `AdminTest` | Database lifecycle (create/get/list/delete), activate/deactivate, tier changes, `listDbCollections`, `listAllCollections`, `deleteDbCollection`, admin token management. **Skipped automatically if `NDD_ROOT_TOKEN` is not set** (`@EnabledIfEnvironmentVariable`). |

The Java client only has one HTTP backend (the JDK's built-in `HttpClient`), so there's no
backend-choice test file here.

---

## Support files

| File | Purpose |
|------|---------|
| `support/TestConfig.java` | Lazily-built shared `Endee` client from `ENDEE_TOKEN`/`ENDEE_BASE_URL`; verifies server reachability and sweeps stale test collections on first use. `rootClientOrNull()` builds an admin client from `NDD_ROOT_TOKEN`, or returns `null`. |
| `support/VectorGenerators.java` | Deterministic vector generators seeded by object index: `denseVec`, `binaryVec`, `sparseVec`, `multiVec`, plus shared constants (`DIM`, `N_VECTORS`, `SPARSE_DIM`, `MV_TOKENS`). |
| `support/FieldConfigs.java` | `createCollection` field-config builders: `denseField(...)`, `sparseField(...)`, `mvField(...)`, plus `ALL_PRECISIONS`/`ALL_SPACE_TYPES`. |
| `support/ObjectBuilders.java` | Deterministic object builders: `denseItem`, `hybridItem`, `sparseItem`, `mvItem` - documents the exact filter layout (category/priority/score/tags) and expected match counts for `N_VECTORS=50`. |
| `support/CollectionFixtures.java` | `emptyDense()`/`populatedDense()` and equivalents for hybrid, sparse, and multi_vector, each returning a `NamedCollection(name, collection)` record; `safeDelete`, `uid`, `collectionNames`. |
| `support/TestProgressWatcher.java` | Prints `[PASS]`/`[FAIL]`/`[ABORTED]`/`[SKIP]` for every test. Auto-registered suite-wide via `META-INF/services` - no per-class `@ExtendWith` needed. |
| `run_tests.sh` | Shell wrapper - sets environment variables and invokes `mvn test` from the project root. Run with `--help` for full usage. |
| `resources/simplelogger.properties` | Silences `io.endee.client`'s own ERROR-level logging during test runs (see Design notes below) - test-only, doesn't affect the published client. |

---

## Design notes

**Console output:** every test prints `[PASS]`/`[FAIL]`/`[ABORTED]`/`[SKIP]` via `TestProgressWatcher`. Some tests deliberately trigger a 404/409 to check error handling, which would otherwise log noisy ERROR lines from `Endee` itself. `resources/simplelogger.properties` silences just that logger for test runs; real failures still show up fully in the console and in `target/surefire-reports/`.

**Unique names:** Every test resource is created with a unique name via `CollectionFixtures.uid("prefix")` (a short UUID suffix). This prevents collisions between concurrent or interrupted runs.

**Async operations:** `createBackup` and `rebuild` return immediately (HTTP 200/201/202) rather than blocking until complete. Tests that need completion poll `rebuildStatus()`/`backupStatus()`/`restoreStatus()` in a short loop.

**Cleanup:** Every test class deletes its fixture collection in `@AfterEach`, even if the test fails. `BackupTest` also removes any backups and temp files it created.

**Stale collections:** `TestConfig.client()` runs once per test session, sweeping any leftover collection matching the `^[a-z]+_[0-9a-f]{10}$` naming pattern (the shape `uid()` produces) - left behind by a previous interrupted run.

**Post-filter ANN non-determinism:** HNSW search can miss a matching node on a small corpus, so a filtered *search*'s exact hit count isn't guaranteed every run. `FilteringTest` soft-checks that count (log and continue) but still hard-asserts that every hit matches the filter and results stay sorted. `deleteByFilter` is an exact scan, not ANN-based, so `DeleteByFilterTest` keeps hard assertions throughout.
