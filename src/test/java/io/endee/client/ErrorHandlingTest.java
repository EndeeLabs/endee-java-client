package io.endee.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.endee.client.exception.AuthenticationException;
import io.endee.client.exception.ConflictException;
import io.endee.client.exception.EndeeApiException;
import io.endee.client.exception.EndeeException;
import io.endee.client.exception.ForbiddenException;
import io.endee.client.exception.NotFoundException;
import io.endee.client.exception.ServerException;
import io.endee.client.exception.SubscriptionException;
import io.endee.client.support.CollectionFixtures;
import io.endee.client.support.CollectionFixtures.NamedCollection;
import io.endee.client.support.FieldConfigs;
import io.endee.client.support.TestConfig;
import io.endee.client.support.VectorGenerators;
import io.endee.client.types.ObjectItem;
import io.endee.client.types.SparseData;
import io.endee.client.types.UpdateFilterParams;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// Covers client-side validation and server-side error-code mapping (401/402/403/404/409/5xx).
class ErrorHandlingTest {

  private Endee client;

  @BeforeEach
  void setUp() {
    client = TestConfig.client();
  }

  // -- Collection name validation (client-side) ----------------------------------

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "has space",
        "has.dot",
        "has@at",
        "bad/name",
        "has-hyphen",
        "__reserved",
      })
  void createCollectionInvalidNameRaises(String badName) {
    try {
      assertThrows(
          IllegalArgumentException.class,
          () -> client.createCollection(badName, List.of(FieldConfigs.denseField())));
    } finally {
      CollectionFixtures.safeDelete(client, badName);
    }
  }

  @Test
  void createCollectionNameTooLongRaises() {
    String badName = "a".repeat(49);
    try {
      assertThrows(
          IllegalArgumentException.class,
          () -> client.createCollection(badName, List.of(FieldConfigs.denseField())));
    } finally {
      CollectionFixtures.safeDelete(client, badName);
    }
  }

  // -- Filter size validation (client-side) ----------------------------------------

  @Test
  void upsertFilterKeyTooLongRaises() {
    NamedCollection nc = CollectionFixtures.emptyDense();
    try {
      String longKey = "k".repeat(129);
      ObjectItem item =
          ObjectItem.builder("obj1")
              .vector(FieldConfigs.DENSE_FIELD, VectorGenerators.denseVec(0))
              .filter(Map.of(longKey, "v"))
              .build();
      assertThrows(IllegalArgumentException.class, () -> nc.collection().upsert(List.of(item)));
    } finally {
      CollectionFixtures.safeDelete(client, nc.name());
    }
  }

  @Test
  void upsertFilterValueTooLongRaises() {
    NamedCollection nc = CollectionFixtures.emptyDense();
    try {
      String longValue = "v".repeat(1025);
      ObjectItem item =
          ObjectItem.builder("obj1")
              .vector(FieldConfigs.DENSE_FIELD, VectorGenerators.denseVec(0))
              .filter(Map.of("key", longValue))
              .build();
      assertThrows(IllegalArgumentException.class, () -> nc.collection().upsert(List.of(item)));
    } finally {
      CollectionFixtures.safeDelete(client, nc.name());
    }
  }

  @Test
  void upsertFilterWithinSizeLimitsAccepted() {
    // Server's real cap is 255 chars, well under the client's 1024-byte check.
    NamedCollection nc = CollectionFixtures.emptyDense();
    try {
      ObjectItem item =
          ObjectItem.builder("obj1")
              .vector(FieldConfigs.DENSE_FIELD, VectorGenerators.denseVec(0))
              .filter(Map.of("k".repeat(128), "v".repeat(255)))
              .build();
      Map<String, Object> result = nc.collection().upsert(List.of(item));
      assertTrue(result.containsKey("upserted"));
    } finally {
      CollectionFixtures.safeDelete(client, nc.name());
    }
  }

  @Test
  void updateFiltersKeyTooLongRaises() {
    NamedCollection nc = CollectionFixtures.populatedDense();
    try {
      String longKey = "k".repeat(129);
      List<UpdateFilterParams> updates =
          List.of(new UpdateFilterParams("vec_0000", Map.of(longKey, "v")));
      assertThrows(IllegalArgumentException.class, () -> nc.collection().updateFilters(updates));
    } finally {
      CollectionFixtures.safeDelete(client, nc.name());
    }
  }

  @Test
  void updateFiltersValueTooLongRaises() {
    NamedCollection nc = CollectionFixtures.populatedDense();
    try {
      String longValue = "v".repeat(1025);
      List<UpdateFilterParams> updates =
          List.of(new UpdateFilterParams("vec_0000", Map.of("key", longValue)));
      assertThrows(IllegalArgumentException.class, () -> nc.collection().updateFilters(updates));
    } finally {
      CollectionFixtures.safeDelete(client, nc.name());
    }
  }

  // -- Duplicate collection (server-side, ConflictException) ----------------------

  @Test
  void createDuplicateCollectionRaisesConflict() {
    String name = CollectionFixtures.uid("dup");
    client.createCollection(name, List.of(FieldConfigs.denseField()));
    try {
      assertThrows(
          ConflictException.class,
          () -> client.createCollection(name, List.of(FieldConfigs.denseField())));
    } finally {
      CollectionFixtures.safeDelete(client, name);
    }
  }

  // -- Not found (server-side, NotFoundException) ---------------------------------

  @Test
  void getNonexistentCollectionRaisesNotFound() {
    assertThrows(
        NotFoundException.class,
        () -> client.getCollection("this_collection_does_not_exist_xyz123"));
  }

  @Test
  void deleteNonexistentCollectionRaisesNotFound() {
    assertThrows(NotFoundException.class, () -> client.deleteCollection("nonexistent_collection_xyz789"));
  }

  @Test
  void deleteObjectNonexistentIdRaisesNotFound() {
    NamedCollection empty = CollectionFixtures.emptyDense();
    try {
      assertThrows(
          NotFoundException.class, () -> empty.collection().deleteObject("id_that_does_not_exist_xyz"));
    } finally {
      CollectionFixtures.safeDelete(client, empty.name());
    }
  }

  @Test
  void deleteObjectTwiceRaisesNotFound() {
    NamedCollection populated = CollectionFixtures.populatedDense();
    try {
      populated.collection().deleteObject("vec_0001");
      assertThrows(NotFoundException.class, () -> populated.collection().deleteObject("vec_0001"));
    } finally {
      CollectionFixtures.safeDelete(client, populated.name());
    }
  }

  @Test
  void describeAfterCollectionDeletedRaisesNotFound() {
    String name = CollectionFixtures.uid("descerr");
    client.createCollection(name, List.of(FieldConfigs.denseField()));
    Collection collection = client.getCollection(name);
    client.deleteCollection(name);
    assertThrows(NotFoundException.class, collection::describe);
  }

  @Test
  void upsertAfterCollectionDeletedRaises() {
    String name = CollectionFixtures.uid("upserr");
    client.createCollection(name, List.of(FieldConfigs.denseField()));
    Collection collection = client.getCollection(name);
    client.deleteCollection(name);
    assertThrows(
        EndeeException.class,
        () ->
            collection.upsert(
                List.of(
                    ObjectItem.builder("x")
                        .vector(FieldConfigs.DENSE_FIELD, VectorGenerators.denseVec(0))
                        .build())));
  }

  @Test
  void createCollectionDuplicateFieldNamesRaises() {
    // The Java client does no client-side duplicate-field-name check (field maps are forwarded
    // as-is), so this is a server-side rejection - broadened to any RuntimeException.
    String name = CollectionFixtures.uid("dupf");
    try {
      assertThrows(
          RuntimeException.class,
          () ->
              client.createCollection(
                  name, List.of(FieldConfigs.denseField(), FieldConfigs.denseField())));
    } finally {
      CollectionFixtures.safeDelete(client, name);
    }
  }

  @Test
  void searchWithEmptyVectorRaises() {
    // Collection.search() does not validate query vector contents client-side; an empty query
    // vector is expected to be rejected by the server.
    NamedCollection populated = CollectionFixtures.populatedDense();
    try {
      Map<String, Map<String, Object>> queryFields = new LinkedHashMap<>();
      queryFields.put(FieldConfigs.DENSE_FIELD, Map.of("query", new double[0], "limit", 5));
      assertThrows(EndeeException.class, () -> populated.collection().search(queryFields));
    } finally {
      CollectionFixtures.safeDelete(client, populated.name());
    }
  }

  // -- Upsert errors ----------------------------------------------------------------

  @Test
  void upsertWrongDimensionRaises() {
    NamedCollection empty = CollectionFixtures.emptyDense();
    try {
      double[] wrongDimVec = VectorGenerators.denseVec(VectorGenerators.DIM + 1, 0);
      assertThrows(
          IllegalArgumentException.class,
          () ->
              empty
                  .collection()
                  .upsert(List.of(ObjectItem.builder("bad").vector(FieldConfigs.DENSE_FIELD, wrongDimVec).build())));
    } finally {
      CollectionFixtures.safeDelete(client, empty.name());
    }
  }

  @Test
  void upsertTooFewDimensionsRaises() {
    NamedCollection empty = CollectionFixtures.emptyDense();
    try {
      double[] shortVec = VectorGenerators.denseVec(VectorGenerators.DIM - 1, 0);
      assertThrows(
          IllegalArgumentException.class,
          () ->
              empty
                  .collection()
                  .upsert(List.of(ObjectItem.builder("short").vector(FieldConfigs.DENSE_FIELD, shortVec).build())));
    } finally {
      CollectionFixtures.safeDelete(client, empty.name());
    }
  }

  @Test
  void upsertEmptyIdRaises() {
    NamedCollection empty = CollectionFixtures.emptyDense();
    try {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              empty
                  .collection()
                  .upsert(
                      List.of(
                          ObjectItem.builder("")
                              .vector(FieldConfigs.DENSE_FIELD, VectorGenerators.denseVec(0))
                              .build())));
    } finally {
      CollectionFixtures.safeDelete(client, empty.name());
    }
  }

  @Test
  void sparseIndicesValuesLengthMismatchRaises() {
    // SparseData validates indices/values length equality in its constructor.
    int[] indices = {1, 2, 3};
    double[] shortValues = {0.1, 0.2};
    assertThrows(IllegalArgumentException.class, () -> new SparseData(indices, shortValues));
  }

  @Test
  void upsertMultiVectorEmptyVectorsRaises() {
    // The Java client's dimension check loops over the multi_vector's rows, so an empty
    // double[][] passes client-side validation trivially; the server is expected to reject it.
    NamedCollection emptyMv = CollectionFixtures.emptyMv();
    try {
      assertThrows(
          EndeeException.class,
          () ->
              emptyMv
                  .collection()
                  .upsert(
                      List.of(
                          ObjectItem.builder("empty_vec")
                              .multiVector(FieldConfigs.MV_FIELD, new double[0][])
                              .build())));
    } finally {
      CollectionFixtures.safeDelete(client, emptyMv.name());
    }
  }

  @Test
  void upsertMultiVectorInconsistentDimensionsRaises() {
    NamedCollection emptyMv = CollectionFixtures.emptyMv();
    try {
      double[][] mixed = {
        VectorGenerators.denseVec(VectorGenerators.DIM, 0), VectorGenerators.denseVec(VectorGenerators.DIM + 1, 1)
      };
      assertThrows(
          IllegalArgumentException.class,
          () ->
              emptyMv
                  .collection()
                  .upsert(List.of(ObjectItem.builder("bad_vec").multiVector(FieldConfigs.MV_FIELD, mixed).build())));
    } finally {
      CollectionFixtures.safeDelete(client, emptyMv.name());
    }
  }

  // -- Search errors ------------------------------------------------------------------

  @Test
  void searchWrongDimensionRaises() {
    // Collection.search() does not validate query vector dimension client-side; expected to be
    // rejected by the server.
    NamedCollection populated = CollectionFixtures.populatedDense();
    try {
      Map<String, Map<String, Object>> queryFields = new LinkedHashMap<>();
      queryFields.put(
          FieldConfigs.DENSE_FIELD,
          Map.of("query", VectorGenerators.denseVec(VectorGenerators.DIM + 2, 0), "limit", 5));
      assertThrows(EndeeException.class, () -> populated.collection().search(queryFields));
    } finally {
      CollectionFixtures.safeDelete(client, populated.name());
    }
  }

  // -- Search parameter bounds (client-side) -----------------------------------------

  @Test
  void searchLimitZeroRaises() {
    NamedCollection populated = CollectionFixtures.populatedDense();
    try {
      Map<String, Map<String, Object>> queryFields = new LinkedHashMap<>();
      queryFields.put(FieldConfigs.DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", 0));
      assertThrows(IllegalArgumentException.class, () -> populated.collection().search(queryFields));
    } finally {
      CollectionFixtures.safeDelete(client, populated.name());
    }
  }

  @Test
  void searchLimitNegativeRaises() {
    NamedCollection populated = CollectionFixtures.populatedDense();
    try {
      Map<String, Map<String, Object>> queryFields = new LinkedHashMap<>();
      queryFields.put(FieldConfigs.DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", -1));
      assertThrows(IllegalArgumentException.class, () -> populated.collection().search(queryFields));
    } finally {
      CollectionFixtures.safeDelete(client, populated.name());
    }
  }

  @Test
  void searchEfSearchZeroRaises() {
    NamedCollection populated = CollectionFixtures.populatedDense();
    try {
      Map<String, Map<String, Object>> queryFields = new LinkedHashMap<>();
      queryFields.put(FieldConfigs.DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", 5));
      assertThrows(
          IllegalArgumentException.class,
          () -> populated.collection().search(queryFields, null, 0, null, null));
    } finally {
      CollectionFixtures.safeDelete(client, populated.name());
    }
  }

  @Test
  void searchEfSearchOverMaxRaises() {
    NamedCollection populated = CollectionFixtures.populatedDense();
    try {
      Map<String, Map<String, Object>> queryFields = new LinkedHashMap<>();
      queryFields.put(FieldConfigs.DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", 5));
      assertThrows(
          IllegalArgumentException.class,
          () -> populated.collection().search(queryFields, null, 1025, null, null));
    } finally {
      CollectionFixtures.safeDelete(client, populated.name());
    }
  }

  @Test
  void searchPrefilterCardinalityThresholdBelowMinRaises() {
    NamedCollection populated = CollectionFixtures.populatedDense();
    try {
      Map<String, Map<String, Object>> queryFields = new LinkedHashMap<>();
      queryFields.put(FieldConfigs.DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", 5));
      assertThrows(
          IllegalArgumentException.class,
          () -> populated.collection().search(queryFields, null, 128, 999, null));
    } finally {
      CollectionFixtures.safeDelete(client, populated.name());
    }
  }

  @Test
  void searchPrefilterCardinalityThresholdAboveMaxRaises() {
    NamedCollection populated = CollectionFixtures.populatedDense();
    try {
      Map<String, Map<String, Object>> queryFields = new LinkedHashMap<>();
      queryFields.put(FieldConfigs.DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", 5));
      assertThrows(
          IllegalArgumentException.class,
          () -> populated.collection().search(queryFields, null, 128, 1_000_001, null));
    } finally {
      CollectionFixtures.safeDelete(client, populated.name());
    }
  }

  @Test
  void searchFilterBoostPercentageNegativeRaises() {
    NamedCollection populated = CollectionFixtures.populatedDense();
    try {
      Map<String, Map<String, Object>> queryFields = new LinkedHashMap<>();
      queryFields.put(FieldConfigs.DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", 5));
      assertThrows(
          IllegalArgumentException.class,
          () -> populated.collection().search(queryFields, null, 128, null, -1));
    } finally {
      CollectionFixtures.safeDelete(client, populated.name());
    }
  }

  @Test
  void searchFilterBoostPercentageAboveMaxRaises() {
    NamedCollection populated = CollectionFixtures.populatedDense();
    try {
      Map<String, Map<String, Object>> queryFields = new LinkedHashMap<>();
      queryFields.put(FieldConfigs.DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", 5));
      assertThrows(
          IllegalArgumentException.class,
          () -> populated.collection().search(queryFields, null, 128, null, 101));
    } finally {
      CollectionFixtures.safeDelete(client, populated.name());
    }
  }

  @Test
  void searchEmptyFieldsRaises() {
    NamedCollection populated = CollectionFixtures.populatedDense();
    try {
      assertThrows(IllegalArgumentException.class, () -> populated.collection().search(Map.of()));
    } finally {
      CollectionFixtures.safeDelete(client, populated.name());
    }
  }

  // -- Authentication errors (server-side) -------------------------------------------

  @Test
  void invalidTokenRaisesAuthenticationError() {
    Endee badClient = new Endee("invalid_token_xyz_12345");
    String baseUrl = System.getenv("ENDEE_BASE_URL");
    if (baseUrl != null && !baseUrl.isBlank()) {
      badClient.setBaseUrl(baseUrl);
    }
    assertThrows(AuthenticationException.class, badClient::listCollections);
  }

  @Test
  void emptyTokenRaisesAuthenticationError() {
    Endee badClient = new Endee("");
    String baseUrl = System.getenv("ENDEE_BASE_URL");
    if (baseUrl != null && !baseUrl.isBlank()) {
      badClient.setBaseUrl(baseUrl);
    }
    assertThrows(AuthenticationException.class, badClient::listCollections);
  }

  // -- Client-side validation for admin methods (no server call reached) -------------

  @Test
  void createDatabaseEmptyNameRaises() {
    assertThrows(IllegalArgumentException.class, () -> client.createDatabase(""));
  }

  @Test
  void createDatabaseInvalidTypeRaises() {
    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> client.createDatabase("testdb", "ultra"));
    assertTrue(ex.getMessage().contains("db_type"));
  }

  @Test
  void setDatabaseTypeInvalidRaises() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class, () -> client.setDatabaseType("testdb", "invalid_tier"));
    assertTrue(ex.getMessage().contains("db_type"));
  }

  @Test
  void createMyTokenEmptyNameRaises() {
    assertThrows(IllegalArgumentException.class, () -> client.createMyToken(""));
  }

  @Test
  void createMyTokenInvalidTypeRaises() {
    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> client.createMyToken("test_tok", "admin"));
    assertTrue(ex.getMessage().contains("token_type"));
  }

  // -- getObjects client-side validation ---------------------------------------------

  @Test
  void getObjectsEmptyListRaises() {
    NamedCollection empty = CollectionFixtures.emptyDense();
    try {
      assertThrows(IllegalArgumentException.class, () -> empty.collection().getObjects(List.of()));
    } finally {
      CollectionFixtures.safeDelete(client, empty.name());
    }
  }

  // -- deleteByFilter client-side validation -----------------------------------------

  @Test
  void deleteByFilterEmptyListRaises() {
    NamedCollection empty = CollectionFixtures.emptyDense();
    try {
      assertThrows(IllegalArgumentException.class, () -> empty.collection().deleteByFilter(List.of()));
    } finally {
      CollectionFixtures.safeDelete(client, empty.name());
    }
  }

  // -- updateFilters client-side validation ------------------------------------------

  @Test
  void updateFiltersEmptyListRaises() {
    NamedCollection empty = CollectionFixtures.emptyDense();
    try {
      assertThrows(IllegalArgumentException.class, () -> empty.collection().updateFilters(List.of()));
    } finally {
      CollectionFixtures.safeDelete(client, empty.name());
    }
  }

  // -- rebuild client-side validation -------------------------------------------------

  @Test
  void rebuildEmptyListRaises() {
    NamedCollection empty = CollectionFixtures.emptyDense();
    try {
      assertThrows(IllegalArgumentException.class, () -> empty.collection().rebuild(List.of()));
    } finally {
      CollectionFixtures.safeDelete(client, empty.name());
    }
  }

  @Test
  void rebuildMissingFieldKeyRaises() {
    NamedCollection empty = CollectionFixtures.emptyDense();
    try {
      IllegalArgumentException ex =
          assertThrows(
              IllegalArgumentException.class,
              () -> empty.collection().rebuild(List.of(Map.of("M", 8))));
      assertTrue(ex.getMessage().contains("must include a 'field' name"));
    } finally {
      CollectionFixtures.safeDelete(client, empty.name());
    }
  }

  // -- backup client-side validation --------------------------------------------------

  @Test
  void createBackupEmptyNameRaises() {
    NamedCollection empty = CollectionFixtures.emptyDense();
    try {
      assertThrows(IllegalArgumentException.class, () -> empty.collection().createBackup(""));
    } finally {
      CollectionFixtures.safeDelete(client, empty.name());
    }
  }

  // -- Exception message content (server-side) ---------------------------------------

  @Test
  void notFoundExceptionMessageIsNonEmpty() {
    NotFoundException ex =
        assertThrows(
            NotFoundException.class, () -> client.getCollection("this_collection_does_not_exist_xyz999"));
    assertFalse(ex.getMessage() == null || ex.getMessage().isEmpty(),
        "NotFoundException must carry a non-empty message");
  }

  @Test
  void conflictExceptionMessageIsNonEmpty() {
    String name = CollectionFixtures.uid("cmsg");
    client.createCollection(name, List.of(FieldConfigs.denseField()));
    try {
      ConflictException ex =
          assertThrows(
              ConflictException.class,
              () -> client.createCollection(name, List.of(FieldConfigs.denseField())));
      assertFalse(ex.getMessage() == null || ex.getMessage().isEmpty(),
          "ConflictException must carry a non-empty message");
    } finally {
      CollectionFixtures.safeDelete(client, name);
    }
  }

  // -- EndeeApiException.raiseException unit tests (no server needed) ---------------

  @Test
  void raiseException400ThrowsPlainApiException() {
    EndeeApiException ex =
        assertThrows(EndeeApiException.class, () -> EndeeApiException.raiseException(400, "{\"error\": \"bad request\"}"));
    assertEquals(EndeeApiException.class, ex.getClass());
    assertEquals(400, ex.getStatusCode());
  }

  @Test
  void raiseException401ThrowsAuthenticationException() {
    AuthenticationException ex =
        assertThrows(
            AuthenticationException.class,
            () -> EndeeApiException.raiseException(401, "{\"error\": \"unauthorized\"}"));
    assertEquals(401, ex.getStatusCode());
  }

  @Test
  void raiseException402ThrowsSubscriptionException() {
    SubscriptionException ex =
        assertThrows(
            SubscriptionException.class,
            () -> EndeeApiException.raiseException(402, "{\"error\": \"payment required\"}"));
    assertEquals(402, ex.getStatusCode());
  }

  @Test
  void raiseException403ThrowsForbiddenException() {
    ForbiddenException ex =
        assertThrows(
            ForbiddenException.class,
            () -> EndeeApiException.raiseException(403, "{\"error\": \"forbidden\"}"));
    assertEquals(403, ex.getStatusCode());
  }

  @Test
  void raiseException404ThrowsNotFoundException() {
    NotFoundException ex =
        assertThrows(
            NotFoundException.class,
            () -> EndeeApiException.raiseException(404, "{\"error\": \"not found\"}"));
    assertEquals(404, ex.getStatusCode());
  }

  @Test
  void raiseException409ThrowsConflictException() {
    ConflictException ex =
        assertThrows(
            ConflictException.class,
            () -> EndeeApiException.raiseException(409, "{\"error\": \"conflict\"}"));
    assertEquals(409, ex.getStatusCode());
  }

  @Test
  void raiseException500ThrowsServerException() {
    ServerException ex =
        assertThrows(
            ServerException.class, () -> EndeeApiException.raiseException(500, "internal server error"));
    assertEquals(500, ex.getStatusCode());
  }

  @Test
  void raiseException503ThrowsServerException() {
    ServerException ex =
        assertThrows(
            ServerException.class, () -> EndeeApiException.raiseException(503, "service unavailable"));
    assertEquals(503, ex.getStatusCode());
  }

  @Test
  void raiseExceptionPlainTextMessageFallsBackToRawText() {
    EndeeApiException ex =
        assertThrows(
            EndeeApiException.class, () -> EndeeApiException.raiseException(400, "plain text error message"));
    assertTrue(ex.getMessage().contains("plain text error message"));
  }

  @Test
  void raiseExceptionJsonErrorFieldExtracted() {
    EndeeApiException ex =
        assertThrows(
            EndeeApiException.class,
            () -> EndeeApiException.raiseException(400, "{\"error\": \"specific error details\"}"));
    assertTrue(ex.getMessage().contains("specific error details"));
  }

  @Test
  void raiseExceptionUnknownStatusCodeRaisesApiException() {
    EndeeApiException ex =
        assertThrows(
            EndeeApiException.class, () -> EndeeApiException.raiseException(418, "{\"error\": \"I am a teapot\"}"));
    assertEquals(EndeeApiException.class, ex.getClass());
    assertEquals(418, ex.getStatusCode());
  }
}
