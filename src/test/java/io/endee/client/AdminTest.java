package io.endee.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.endee.client.support.CollectionFixtures;
import io.endee.client.support.FieldConfigs;
import io.endee.client.support.TestConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

// Covers database lifecycle, activate/deactivate, tier changes, and admin token management via the root token.
@EnabledIfEnvironmentVariable(named = "NDD_ROOT_TOKEN", matches = ".+")
class AdminTest {

  private static Endee adminClient;
  private static String dbName;

  @BeforeAll
  static void setUpDatabase() {
    adminClient = TestConfig.rootClientOrNull();
    Assumptions.assumeTrue(adminClient != null, "NDD_ROOT_TOKEN not set");
    dbName = CollectionFixtures.uid("adb");
    adminClient.createDatabase(dbName);
  }

  @AfterAll
  static void tearDownDatabase() {
    if (adminClient != null && dbName != null) {
      try {
        adminClient.deleteDatabase(dbName);
      } catch (Exception ignored) {
        // best-effort teardown
      }
    }
  }

  @BeforeEach
  void checkRootToken() {
    Assumptions.assumeTrue(TestConfig.rootClientOrNull() != null, "NDD_ROOT_TOKEN not set");
  }

  private static Endee dbClient(String token) {
    Endee c = new Endee(token);
    String baseUrl = System.getenv("ENDEE_BASE_URL");
    if (baseUrl != null && !baseUrl.isBlank()) {
      c.setBaseUrl(baseUrl);
    }
    return c;
  }

  // Checks each element's runtime type: listDbCollections() can return strings or maps.
  private static boolean containsCollectionName(List<?> collections, String name) {
    for (Object c : collections) {
      if (c instanceof String s && s.equals(name)) {
        return true;
      }
      if (c instanceof Map<?, ?> m && name.equals(m.get("name"))) {
        return true;
      }
    }
    return false;
  }

  // -- client-side validation -----------------------------------------------------------

  @Test
  void deleteDatabaseEmptyNameRaises() {
    Endee c = TestConfig.rootClientOrNull();
    assertThrows(IllegalArgumentException.class, () -> c.deleteDatabase(""));
  }

  @Test
  void setDatabaseTypeInvalidTypeRaises() {
    Endee c = TestConfig.rootClientOrNull();
    assertThrows(IllegalArgumentException.class, () -> c.setDatabaseType("some_db", "ultra"));
  }

  @Test
  void createTokenEmptyDbNameRaises() {
    Endee c = TestConfig.rootClientOrNull();
    assertThrows(IllegalArgumentException.class, () -> c.createToken("", "tok"));
  }

  @Test
  void createTokenEmptyTokenNameRaises() {
    Endee c = TestConfig.rootClientOrNull();
    assertThrows(IllegalArgumentException.class, () -> c.createToken("some_db", ""));
  }

  @Test
  void deleteTokenEmptyDbNameRaises() {
    Endee c = TestConfig.rootClientOrNull();
    assertThrows(IllegalArgumentException.class, () -> c.deleteToken("", "tok"));
  }

  @Test
  void deleteTokenEmptyTokenNameRaises() {
    Endee c = TestConfig.rootClientOrNull();
    assertThrows(IllegalArgumentException.class, () -> c.deleteToken("some_db", ""));
  }

  @Test
  void listDbCollectionsEmptyNameRaises() {
    Endee c = TestConfig.rootClientOrNull();
    assertThrows(IllegalArgumentException.class, () -> c.listDbCollections(""));
  }

  @Test
  void deleteDbCollectionEmptyDbNameRaises() {
    Endee c = TestConfig.rootClientOrNull();
    assertThrows(IllegalArgumentException.class, () -> c.deleteDbCollection("", "collection_name"));
  }

  @Test
  void deleteDbCollectionEmptyCollectionNameRaises() {
    Endee c = TestConfig.rootClientOrNull();
    assertThrows(IllegalArgumentException.class, () -> c.deleteDbCollection("some_db", ""));
  }

  @Test
  void getDatabaseEmptyNameRaises() {
    Endee c = TestConfig.rootClientOrNull();
    assertThrows(IllegalArgumentException.class, () -> c.getDatabase(""));
  }

  @Test
  void listTokensEmptyDbNameRaises() {
    Endee c = TestConfig.rootClientOrNull();
    assertThrows(IllegalArgumentException.class, () -> c.listTokens(""));
  }

  @Test
  void createTokenInvalidTypeRaises() {
    Endee c = TestConfig.rootClientOrNull();
    assertThrows(IllegalArgumentException.class, () -> c.createToken("some_db", "tok", "admin"));
  }

  // -- listDatabases --------------------------------------------------------------------

  @Test
  void listDatabasesReturnsList() {
    List<Map<String, Object>> result = adminClient.listDatabases();
    assertInstanceOf(List.class, result);
  }

  @Test
  void listDatabasesItemsAreMaps() {
    for (Object item : adminClient.listDatabases()) {
      assertInstanceOf(Map.class, item, "Expected map, got " + item.getClass() + ": " + item);
    }
  }

  // -- createDatabase / deleteDatabase ---------------------------------------------------

  @Test
  void createDatabaseReturnsTokenString() {
    String name = CollectionFixtures.uid("adb");
    try {
      String token = adminClient.createDatabase(name);
      assertNotNull(token);
      assertTrue(token.length() > 0);
    } finally {
      try {
        adminClient.deleteDatabase(name);
      } catch (Exception ignored) {
      }
    }
  }

  @Test
  void createDatabaseAppearsInList() {
    String name = CollectionFixtures.uid("adb");
    try {
      adminClient.createDatabase(name);
      List<Object> dbNames = adminClient.listDatabases().stream().map(d -> d.get("db_name")).toList();
      assertTrue(dbNames.contains(name), "'" + name + "' not found in listDatabases()");
    } finally {
      try {
        adminClient.deleteDatabase(name);
      } catch (Exception ignored) {
      }
    }
  }

  @Test
  void deleteDatabaseReturnsMap() {
    String name = CollectionFixtures.uid("adb");
    adminClient.createDatabase(name);
    Map<String, Object> result = adminClient.deleteDatabase(name);
    assertInstanceOf(Map.class, result);
  }

  @Test
  void deleteDatabaseRemovesFromList() {
    String name = CollectionFixtures.uid("adb");
    adminClient.createDatabase(name);
    adminClient.deleteDatabase(name);
    List<Object> dbNames = adminClient.listDatabases().stream().map(d -> d.get("db_name")).toList();
    assertFalse(dbNames.contains(name));
  }

  @Test
  void createDatabaseTokenIsUsable() {
    String name = CollectionFixtures.uid("adb");
    try {
      String token = adminClient.createDatabase(name);
      List<Map<String, Object>> collections = dbClient(token).listCollections();
      assertInstanceOf(List.class, collections);
    } finally {
      try {
        adminClient.deleteDatabase(name);
      } catch (Exception ignored) {
      }
    }
  }

  // -- getDatabase ------------------------------------------------------------------------

  @Test
  void getDatabaseReturnsMap() {
    Map<String, Object> result = adminClient.getDatabase(dbName);
    assertInstanceOf(Map.class, result);
  }

  @Test
  void getDatabaseNameMatches() {
    Map<String, Object> result = adminClient.getDatabase(dbName);
    assertEquals(dbName, result.get("db_name"));
  }

  // -- activate / deactivate ----------------------------------------------------------------

  @Test
  void deactivateDatabaseReturnsMap() {
    Map<String, Object> result = adminClient.deactivateDatabase(dbName);
    assertInstanceOf(Map.class, result);
    adminClient.activateDatabase(dbName); // restore so subsequent tests can use the DB
  }

  @Test
  void activateDatabaseReturnsMap() {
    adminClient.deactivateDatabase(dbName);
    Map<String, Object> result = adminClient.activateDatabase(dbName);
    assertInstanceOf(Map.class, result);
  }

  @Test
  void deactivateThenActivateLeavesDbActive() {
    adminClient.deactivateDatabase(dbName);
    adminClient.activateDatabase(dbName);
    List<Map<String, Object>> dbs = adminClient.listDatabases();
    Map<String, Object> db = dbs.stream().filter(d -> dbName.equals(d.get("db_name"))).findFirst().orElse(null);
    assertNotNull(db);
    if (db.containsKey("is_active")) {
      assertEquals(true, db.get("is_active"));
    }
  }

  // -- setDatabaseType ----------------------------------------------------------------------

  @Test
  void setDatabaseTypeReturnsMap() {
    Map<String, Object> result = adminClient.setDatabaseType(dbName, "pro");
    assertInstanceOf(Map.class, result);
  }

  @Test
  void setDatabaseTypeActuallyChangesTier() {
    adminClient.setDatabaseType(dbName, "starter");
    List<Map<String, Object>> dbs = adminClient.listDatabases();
    Map<String, Object> db = dbs.stream().filter(d -> dbName.equals(d.get("db_name"))).findFirst().orElse(null);
    assertNotNull(db, "Database '" + dbName + "' not found in listDatabases()");
    if (db.containsKey("db_type")) {
      // Server capitalizes the tier name in its response, e.g. "starter" -> "Starter".
      assertEquals("Starter", db.get("db_type"));
    }
  }

  // -- listDbCollections / listAllCollections ------------------------------------------------

  @Test
  void listDbCollectionsReturnsList() {
    List<?> result = adminClient.listDbCollections(dbName);
    assertInstanceOf(List.class, result);
  }

  @Test
  void listAllCollectionsReturnsList() {
    List<?> result = adminClient.listAllCollections();
    assertInstanceOf(List.class, result);
  }

  @Test
  void listDbCollectionsReflectsCreatedCollection() {
    String colName = CollectionFixtures.uid("ac");
    String tokName = CollectionFixtures.uid("tok");
    String freshToken = adminClient.createToken(dbName, tokName);
    Endee colClient = dbClient(freshToken);
    try {
      colClient.createCollection(colName, List.of(FieldConfigs.denseField()));
      List<?> cols = adminClient.listDbCollections(dbName);
      assertTrue(containsCollectionName(cols, colName), "'" + colName + "' not found in listDbCollections()");
    } finally {
      try {
        colClient.deleteCollection(colName);
      } catch (Exception ignored) {
      }
      try {
        adminClient.deleteToken(dbName, tokName);
      } catch (Exception ignored) {
      }
    }
  }

  // -- deleteDbCollection -------------------------------------------------------------------

  @Test
  void deleteDbCollectionRemovesFromList() {
    String colName = CollectionFixtures.uid("ac");
    String tokName = CollectionFixtures.uid("tok");
    String freshToken = adminClient.createToken(dbName, tokName);
    try {
      dbClient(freshToken).createCollection(colName, List.of(FieldConfigs.denseField()));
      adminClient.deleteDbCollection(dbName, colName);
      List<?> cols = adminClient.listDbCollections(dbName);
      assertFalse(containsCollectionName(cols, colName));
    } finally {
      try {
        adminClient.deleteToken(dbName, tokName);
      } catch (Exception ignored) {
      }
    }
  }

  @Test
  void deleteDbCollectionReturnsMap() {
    String colName = CollectionFixtures.uid("ac");
    String tokName = CollectionFixtures.uid("tok");
    String freshToken = adminClient.createToken(dbName, tokName);
    try {
      dbClient(freshToken).createCollection(colName, List.of(FieldConfigs.denseField()));
      Map<String, Object> result = adminClient.deleteDbCollection(dbName, colName);
      assertInstanceOf(Map.class, result);
    } finally {
      try {
        adminClient.deleteToken(dbName, tokName);
      } catch (Exception ignored) {
      }
    }
  }

  // -- admin token management -----------------------------------------------------------------

  @Test
  void createTokenReturnsString() {
    String tokName = CollectionFixtures.uid("tok");
    try {
      String result = adminClient.createToken(dbName, tokName);
      assertNotNull(result);
      assertTrue(result.length() > 0);
    } finally {
      try {
        adminClient.deleteToken(dbName, tokName);
      } catch (Exception ignored) {
      }
    }
  }

  @Test
  void listTokensReturnsList() {
    List<Map<String, Object>> result = adminClient.listTokens(dbName);
    assertInstanceOf(List.class, result);
  }

  @Test
  void createTokenAppearsInList() {
    String tokName = CollectionFixtures.uid("tok");
    try {
      adminClient.createToken(dbName, tokName);
      List<Object> tokenNames = adminClient.listTokens(dbName).stream().map(t -> t.get("name")).toList();
      assertTrue(tokenNames.contains(tokName), "'" + tokName + "' not found in listTokens()");
    } finally {
      try {
        adminClient.deleteToken(dbName, tokName);
      } catch (Exception ignored) {
      }
    }
  }

  @Test
  void deleteTokenRemovesFromList() {
    String tokName = CollectionFixtures.uid("tok");
    adminClient.createToken(dbName, tokName);
    adminClient.deleteToken(dbName, tokName);
    List<Object> tokenNames = adminClient.listTokens(dbName).stream().map(t -> t.get("name")).toList();
    assertFalse(tokenNames.contains(tokName));
  }

  @Test
  void createTokenRwType() {
    String tokName = CollectionFixtures.uid("rwtok");
    try {
      String result = adminClient.createToken(dbName, tokName, "rw");
      assertNotNull(result);
    } finally {
      try {
        adminClient.deleteToken(dbName, tokName);
      } catch (Exception ignored) {
      }
    }
  }

  @Test
  void createTokenReadonlyType() {
    String tokName = CollectionFixtures.uid("rtok");
    try {
      String result = adminClient.createToken(dbName, tokName, "r");
      assertNotNull(result);
    } finally {
      try {
        adminClient.deleteToken(dbName, tokName);
      } catch (Exception ignored) {
      }
    }
  }

  @Test
  void adminTokenFullLifecycle() {
    String tokName = CollectionFixtures.uid("full");
    try {
      String tokenStr = adminClient.createToken(dbName, tokName, "rw");
      assertNotNull(tokenStr);

      List<Object> names = adminClient.listTokens(dbName).stream().map(t -> t.get("name")).toList();
      assertTrue(names.contains(tokName));

      adminClient.deleteToken(dbName, tokName);

      List<Object> namesAfter = adminClient.listTokens(dbName).stream().map(t -> t.get("name")).toList();
      assertFalse(namesAfter.contains(tokName));
    } finally {
      try {
        adminClient.deleteToken(dbName, tokName);
      } catch (Exception ignored) {
      }
    }
  }
}
