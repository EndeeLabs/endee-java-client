package io.endee.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.endee.client.exception.EndeeException;
import io.endee.client.support.CollectionFixtures;
import io.endee.client.support.CollectionFixtures.NamedCollection;
import io.endee.client.support.FieldConfigs;
import io.endee.client.support.ObjectBuilders;
import io.endee.client.support.TestConfig;
import io.endee.client.support.VectorGenerators;
import io.endee.client.types.ObjectItem;
import io.endee.client.types.SearchHit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

// Covers the full backup lifecycle: create, status, list, info, restore, delete, download, upload.
class BackupTest {

  private final Endee client = TestConfig.client();
  private final List<String> collectionsToDelete = new ArrayList<>();
  private final List<String> backupsToDelete = new ArrayList<>();
  private final List<Path> tempDirsToDelete = new ArrayList<>();

  @AfterEach
  void tearDown() {
    for (String backup : backupsToDelete) {
      cleanupBackup(backup);
    }
    backupsToDelete.clear();
    for (String name : collectionsToDelete) {
      CollectionFixtures.safeDelete(client, name);
    }
    collectionsToDelete.clear();
    for (Path dir : tempDirsToDelete) {
      deleteRecursively(dir);
    }
    tempDirsToDelete.clear();
  }

  // -- helpers --------------------------------------------------------------------

  private static Set<String> backupNames(Object result) {
    Set<String> names = new HashSet<>();
    if (result instanceof List<?> list) {
      for (Object item : list) {
        if (item instanceof Map<?, ?> m && m.get("name") instanceof String s) {
          names.add(s);
        }
      }
    } else if (result instanceof Map<?, ?> map) {
      for (Object key : map.keySet()) {
        if (key instanceof String s) {
          names.add(s);
        }
      }
    }
    return names;
  }

  private static boolean containsBackup(Object result, String backupName) {
    if (backupNames(result).contains(backupName)) {
      return true;
    }
    return String.valueOf(result).contains(backupName);
  }

  private void deleteBackupSilently(String name) {
    CollectionFixtures.safeDeleteBackup(client, name);
  }

  // Polls listBackups() until backupName appears.
  private void waitForBackup(String backupName, long timeoutMillis) {
    long deadline = System.currentTimeMillis() + timeoutMillis;
    while (System.currentTimeMillis() < deadline) {
      Object result = client.listBackups();
      if (containsBackup(result, backupName)) {
        return;
      }
      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }
    }
    throw new RuntimeException(
        "Backup '" + backupName + "' did not appear in listBackups() within " + timeoutMillis + "ms");
  }

  private void waitForBackup(String backupName) {
    waitForBackup(backupName, 120_000);
  }

  // Waits for the async restore job to finish before the restored collection can be used.
  private void waitForRestore(long timeoutMillis) {
    long deadline = System.currentTimeMillis() + timeoutMillis;
    while (System.currentTimeMillis() < deadline) {
      Object status = client.restoreStatus().get("status");
      if (!"running".equals(status)) {
        return;
      }
      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }
    }
    throw new RuntimeException("Restore did not complete within " + timeoutMillis + "ms");
  }

  private void waitForRestore() {
    waitForRestore(120_000);
  }

  // Waits for an in-progress backup then always deletes it; safe to call from teardown.
  private void cleanupBackup(String backupName) {
    try {
      Object result = client.listBackups();
      boolean inList = containsBackup(result, backupName);
      if (!inList) {
        Object status = client.backupStatus().get("status");
        if ("running".equals(status) || "in_progress".equals(status)) {
          try {
            waitForBackup(backupName, 120_000);
          } catch (Exception ignored) {
            // fall through to delete anyway
          }
        }
      }
    } catch (Exception ignored) {
      // fall through to delete anyway
    }
    deleteBackupSilently(backupName);
  }

  private Path newTempDir() {
    try {
      Path dir = Files.createTempDirectory("endee-backup");
      tempDirsToDelete.add(dir);
      return dir;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static void deleteRecursively(Path dir) {
    try {
      if (!Files.exists(dir)) {
        return;
      }
      try (var stream = Files.walk(dir)) {
        stream
            .sorted((a, b) -> b.compareTo(a))
            .forEach(
                p -> {
                  try {
                    Files.deleteIfExists(p);
                  } catch (IOException ignored) {
                    // best-effort
                  }
                });
      }
    } catch (IOException ignored) {
      // best-effort
    }
  }

  private NamedCollection populatedDense() {
    NamedCollection nc = CollectionFixtures.populatedDense();
    collectionsToDelete.add(nc.name());
    return nc;
  }

  private NamedCollection emptyDense() {
    NamedCollection nc = CollectionFixtures.emptyDense();
    collectionsToDelete.add(nc.name());
    return nc;
  }

  // -- createBackup (Collection) --------------------------------------------------

  @Test
  void createBackupReturnsMap() {
    Collection collection = populatedDense().collection();
    String backupName = CollectionFixtures.uid("bk");
    backupsToDelete.add(backupName);
    Map<String, Object> result = collection.createBackup(backupName);
    assertInstanceOf(Map.class, result);
  }

  @Test
  void createBackupStatusIsInProgress() {
    Collection collection = populatedDense().collection();
    String backupName = CollectionFixtures.uid("bkst");
    backupsToDelete.add(backupName);
    Map<String, Object> result = collection.createBackup(backupName);
    assertEquals("in_progress", result.get("status"), "Expected status='in_progress', got: " + result);
  }

  @Test
  void createBackupResponseContainsBackupName() {
    Collection collection = populatedDense().collection();
    String backupName = CollectionFixtures.uid("bkn");
    backupsToDelete.add(backupName);
    Map<String, Object> result = collection.createBackup(backupName);
    boolean hasName =
        backupName.equals(result.get("backup_name"))
            || backupName.equals(result.get("name"))
            || String.valueOf(result).contains(backupName);
    assertTrue(hasName, "backup name not found in response: " + result);
  }

  @Test
  void createBackupOnEmptyCollectionAccepted() {
    Collection collection = emptyDense().collection();
    String backupName = CollectionFixtures.uid("bke");
    backupsToDelete.add(backupName);
    Map<String, Object> result = collection.createBackup(backupName);
    assertInstanceOf(Map.class, result);
  }

  @Test
  void createBackupTwoUniqueNamesBothSucceed() {
    Collection collection = populatedDense().collection();
    String b1 = CollectionFixtures.uid("bka");
    String b2 = CollectionFixtures.uid("bkb");
    backupsToDelete.add(b1);
    backupsToDelete.add(b2);
    Map<String, Object> r1 = collection.createBackup(b1);
    waitForBackup(b1);
    Map<String, Object> r2 = collection.createBackup(b2);
    waitForBackup(b2);
    assertInstanceOf(Map.class, r1);
    assertInstanceOf(Map.class, r2);
  }

  @Test
  void createBackupEmptyNameRaises() {
    Collection collection = emptyDense().collection();
    assertThrows(IllegalArgumentException.class, () -> collection.createBackup(""));
  }

  // -- backupStatus (Endee client) -------------------------------------------------

  @Test
  void backupStatusReturnsMap() {
    Map<String, Object> result = client.backupStatus();
    assertInstanceOf(Map.class, result);
  }

  @Test
  void backupStatusHasStatusKey() {
    Map<String, Object> result = client.backupStatus();
    assertTrue(result.containsKey("status"), "Missing 'status' key in response: " + result);
  }

  @Test
  void backupStatusReflectsCompletedJob() {
    Collection collection = populatedDense().collection();
    String backupName = CollectionFixtures.uid("bkstat");
    backupsToDelete.add(backupName);
    collection.createBackup(backupName);
    waitForBackup(backupName);
    Map<String, Object> status = client.backupStatus();
    assertEquals(
        "completed", status.get("status"), "Expected status='completed' after completion, got: " + status);
  }

  @Test
  void backupStatusNotRunningAfterCompletion() {
    Collection collection = populatedDense().collection();
    String backupName = CollectionFixtures.uid("bkdone");
    backupsToDelete.add(backupName);
    collection.createBackup(backupName);
    waitForBackup(backupName);
    Map<String, Object> status = client.backupStatus();
    assertNotEquals(
        "running", status.get("status"), "Expected a finished status after completion, got: " + status);
  }

  // -- restoreStatus (Endee client) -------------------------------------------------

  @Test
  void restoreStatusReturnsMap() {
    Map<String, Object> result = client.restoreStatus();
    assertInstanceOf(Map.class, result);
  }

  @Test
  void restoreStatusHasStatusKey() {
    Map<String, Object> result = client.restoreStatus();
    assertTrue(result.containsKey("status"), "Missing 'status' key in response: " + result);
  }

  // -- listBackups (Endee client) --------------------------------------------------

  @Test
  void listBackupsReturnsListOrMap() {
    Object result = client.listBackups();
    assertTrue(result instanceof List || result instanceof Map);
  }

  @Test
  void listBackupsAfterCreateContainsBackup() {
    Collection collection = populatedDense().collection();
    String backupName = CollectionFixtures.uid("bklst");
    backupsToDelete.add(backupName);
    collection.createBackup(backupName);
    waitForBackup(backupName);
    Object result = client.listBackups();
    assertTrue(containsBackup(result, backupName), "Backup '" + backupName + "' not found in listBackups(): " + result);
  }

  // -- backupInfo (Endee client) ---------------------------------------------------

  @Test
  void backupInfoReturnsMap() {
    Collection collection = populatedDense().collection();
    String backupName = CollectionFixtures.uid("bkinf");
    backupsToDelete.add(backupName);
    collection.createBackup(backupName);
    waitForBackup(backupName);
    Map<String, Object> result = client.backupInfo(backupName);
    assertInstanceOf(Map.class, result);
  }

  @Test
  void backupInfoContainsName() {
    Collection collection = populatedDense().collection();
    String backupName = CollectionFixtures.uid("bkinfn");
    backupsToDelete.add(backupName);
    collection.createBackup(backupName);
    waitForBackup(backupName);
    Map<String, Object> result = client.backupInfo(backupName);
    assertTrue(
        result.containsKey("original_index")
            || result.containsKey("params")
            || String.valueOf(result).contains(backupName),
        "Unexpected backupInfo() response: " + result);
  }

  @Test
  void backupInfoNonexistentRaises() {
    assertThrows(EndeeException.class, () -> client.backupInfo("definitely_does_not_exist_xyz_99999"));
  }

  @Test
  void backupInfoEmptyNameRaises() {
    assertThrows(IllegalArgumentException.class, () -> client.backupInfo(""));
  }

  // -- restoreBackup (Endee client) -------------------------------------------------

  @Test
  void restoreBackupReturnsMap() {
    Collection collection = populatedDense().collection();
    String backupName = CollectionFixtures.uid("bkrst");
    String targetName = CollectionFixtures.uid("rstcol");
    backupsToDelete.add(backupName);
    collectionsToDelete.add(targetName);
    collection.createBackup(backupName);
    waitForBackup(backupName);
    Map<String, Object> result = client.restoreBackup(backupName, targetName);
    waitForRestore();
    assertInstanceOf(Map.class, result);
  }

  @Test
  void restoreBackupCreatesCollection() {
    Collection collection = populatedDense().collection();
    String backupName = CollectionFixtures.uid("bkrc");
    String targetName = CollectionFixtures.uid("rscol");
    backupsToDelete.add(backupName);
    collectionsToDelete.add(targetName);
    collection.createBackup(backupName);
    waitForBackup(backupName);
    client.restoreBackup(backupName, targetName);
    waitForRestore();
    assertTrue(
        CollectionFixtures.collectionNames(client).contains(targetName),
        "Restored collection '" + targetName + "' not in listCollections()");
  }

  @Test
  void restoreBackupCollectionIsSearchable() {
    Collection collection = populatedDense().collection();
    String backupName = CollectionFixtures.uid("bkrs");
    String targetName = CollectionFixtures.uid("rssrch");
    backupsToDelete.add(backupName);
    collectionsToDelete.add(targetName);
    collection.createBackup(backupName);
    waitForBackup(backupName);
    client.restoreBackup(backupName, targetName);
    waitForRestore();
    Collection restored = client.getCollection(targetName);
    List<SearchHit> results =
        restored
            .search(Map.of(FieldConfigs.DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", 5)))
            .get(FieldConfigs.DENSE_FIELD);
    assertTrue(results.size() > 0);
  }

  @Test
  void restoreBackupCollectionAcceptsUpsert() {
    Collection collection = populatedDense().collection();
    String backupName = CollectionFixtures.uid("bkup");
    String targetName = CollectionFixtures.uid("rsupsrt");
    backupsToDelete.add(backupName);
    collectionsToDelete.add(targetName);
    collection.createBackup(backupName);
    waitForBackup(backupName);
    client.restoreBackup(backupName, targetName);
    waitForRestore();
    Collection restored = client.getCollection(targetName);
    Map<String, Object> result = restored.upsert(List.of(ObjectBuilders.denseItem(999)));
    assertEquals(1, ((Number) result.get("upserted")).intValue());
  }

  @Test
  void restoreBackupEmptyNameRaises() {
    assertThrows(IllegalArgumentException.class, () -> client.restoreBackup("", "some_col"));
  }

  @Test
  void restoreBackupEmptyTargetRaises() {
    assertThrows(IllegalArgumentException.class, () -> client.restoreBackup("some_backup", ""));
  }

  @Test
  void restoreBackupNonexistentRaises() {
    String ghostTarget = CollectionFixtures.uid("ghost");
    assertThrows(
        EndeeException.class,
        () -> client.restoreBackup("definitely_does_not_exist_xyz_99999", ghostTarget));
  }

  // -- deleteBackup (Endee client) --------------------------------------------------

  @Test
  void deleteBackupReturnsMap() {
    Collection collection = populatedDense().collection();
    String backupName = CollectionFixtures.uid("bkdel");
    backupsToDelete.add(backupName);
    collection.createBackup(backupName);
    waitForBackup(backupName);
    Map<String, Object> result = client.deleteBackup(backupName);
    assertInstanceOf(Map.class, result);
  }

  @Test
  void deleteBackupRemovesFromList() {
    Collection collection = populatedDense().collection();
    String backupName = CollectionFixtures.uid("bkrm");
    backupsToDelete.add(backupName);
    collection.createBackup(backupName);
    waitForBackup(backupName);
    client.deleteBackup(backupName);
    Object result = client.listBackups();
    assertFalse(containsBackup(result, backupName), "Backup '" + backupName + "' still in list after delete");
  }

  @Test
  void deleteBackupSecondDeleteRaises() {
    Collection collection = populatedDense().collection();
    String backupName = CollectionFixtures.uid("bkdd");
    backupsToDelete.add(backupName);
    collection.createBackup(backupName);
    waitForBackup(backupName);
    client.deleteBackup(backupName);
    assertThrows(EndeeException.class, () -> client.deleteBackup(backupName));
  }

  @Test
  void deleteBackupNonexistentRaises() {
    assertThrows(EndeeException.class, () -> client.deleteBackup("definitely_does_not_exist_xyz_99999"));
  }

  @Test
  void deleteBackupEmptyNameRaises() {
    assertThrows(IllegalArgumentException.class, () -> client.deleteBackup(""));
  }

  // -- multi_vector collection backup ----------------------------------------------

  @Test
  void createBackupMvCollectionReturnsMap() {
    String name = CollectionFixtures.uid("mvbkc");
    String backupName = CollectionFixtures.uid("mvbk");
    collectionsToDelete.add(name);
    backupsToDelete.add(backupName);
    client.createCollection(name, List.of(FieldConfigs.mvField()));
    Collection col = client.getCollection(name);
    List<ObjectItem> items = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      items.add(ObjectBuilders.mvItem(i));
    }
    col.upsert(items);
    Map<String, Object> result = col.createBackup(backupName);
    assertInstanceOf(Map.class, result);
    assertEquals("in_progress", result.get("status"));
  }

  @Test
  void restoreBackupMvCollectionIsSearchable() {
    String name = CollectionFixtures.uid("mvbkrs");
    String backupName = CollectionFixtures.uid("mvbkrsbk");
    String targetName = CollectionFixtures.uid("mvrstcol");
    collectionsToDelete.add(name);
    collectionsToDelete.add(targetName);
    backupsToDelete.add(backupName);
    client.createCollection(name, List.of(FieldConfigs.mvField()));
    Collection col = client.getCollection(name);
    List<ObjectItem> items = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      items.add(ObjectBuilders.mvItem(i));
    }
    col.upsert(items);
    col.createBackup(backupName);
    waitForBackup(backupName);
    client.restoreBackup(backupName, targetName);
    waitForRestore();
    Collection restored = client.getCollection(targetName);
    List<SearchHit> results =
        restored
            .search(Map.of(FieldConfigs.MV_FIELD, Map.of("query", VectorGenerators.multiVec(0), "limit", 5)))
            .get(FieldConfigs.MV_FIELD);
    assertTrue(results.size() > 0);
  }

  // -- sparse collection backup / restore -------------------------------------------

  @Test
  void createBackupSparseCollectionReturnsMap() {
    String name = CollectionFixtures.uid("spbkc");
    String backupName = CollectionFixtures.uid("spbk");
    collectionsToDelete.add(name);
    backupsToDelete.add(backupName);
    client.createCollection(name, List.of(FieldConfigs.sparseField()));
    Collection col = client.getCollection(name);
    List<ObjectItem> items = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      items.add(ObjectBuilders.sparseItem(i));
    }
    col.upsert(items);
    Map<String, Object> result = col.createBackup(backupName);
    assertInstanceOf(Map.class, result);
    assertEquals("in_progress", result.get("status"));
  }

  @Test
  void restoreBackupSparseCollectionIsSearchable() {
    String name = CollectionFixtures.uid("spbkrs");
    String backupName = CollectionFixtures.uid("spbkrsbk");
    String targetName = CollectionFixtures.uid("sprstcol");
    collectionsToDelete.add(name);
    collectionsToDelete.add(targetName);
    backupsToDelete.add(backupName);
    client.createCollection(name, List.of(FieldConfigs.sparseField()));
    Collection col = client.getCollection(name);
    List<ObjectItem> items = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      items.add(ObjectBuilders.sparseItem(i));
    }
    col.upsert(items);
    col.createBackup(backupName);
    waitForBackup(backupName);
    client.restoreBackup(backupName, targetName);
    waitForRestore();
    Collection restored = client.getCollection(targetName);
    List<SearchHit> results =
        restored
            .search(Map.of(FieldConfigs.SPARSE_FIELD, Map.of("query", VectorGenerators.sparseVec(0), "limit", 5)))
            .get(FieldConfigs.SPARSE_FIELD);
    assertTrue(results.size() > 0);
  }

  // -- multi-field (dense + sparse) collection backup / restore ---------------------

  @Test
  void createBackupMultiFieldCollectionReturnsMap() {
    String name = CollectionFixtures.uid("mfbkc");
    String backupName = CollectionFixtures.uid("mfbk");
    collectionsToDelete.add(name);
    backupsToDelete.add(backupName);
    client.createCollection(
        name,
        List.of(
            FieldConfigs.denseField(VectorGenerators.HYBRID_DIM, "cosine", "int8"),
            FieldConfigs.sparseField()));
    Collection col = client.getCollection(name);
    List<ObjectItem> items = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      items.add(ObjectBuilders.hybridItem(i, VectorGenerators.HYBRID_DIM));
    }
    col.upsert(items);
    Map<String, Object> result = col.createBackup(backupName);
    assertInstanceOf(Map.class, result);
    assertEquals("in_progress", result.get("status"));
  }

  @Test
  void restoreBackupMultiFieldCollectionIsSearchable() {
    String name = CollectionFixtures.uid("mfbkrs");
    String backupName = CollectionFixtures.uid("mfbkrsbk");
    String targetName = CollectionFixtures.uid("mfrstcol");
    collectionsToDelete.add(name);
    collectionsToDelete.add(targetName);
    backupsToDelete.add(backupName);
    client.createCollection(
        name,
        List.of(
            FieldConfigs.denseField(VectorGenerators.HYBRID_DIM, "cosine", "int8"),
            FieldConfigs.sparseField()));
    Collection col = client.getCollection(name);
    List<ObjectItem> items = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      items.add(ObjectBuilders.hybridItem(i, VectorGenerators.HYBRID_DIM));
    }
    col.upsert(items);
    col.createBackup(backupName);
    waitForBackup(backupName);
    client.restoreBackup(backupName, targetName);
    waitForRestore();
    Collection restored = client.getCollection(targetName);

    List<SearchHit> denseResults =
        restored
            .search(
                Map.of(
                    FieldConfigs.DENSE_FIELD,
                    Map.of("query", VectorGenerators.denseVec(VectorGenerators.HYBRID_DIM, 0), "limit", 5)))
            .get(FieldConfigs.DENSE_FIELD);
    assertTrue(denseResults.size() > 0);

    List<SearchHit> sparseResults =
        restored
            .search(Map.of(FieldConfigs.SPARSE_FIELD, Map.of("query", VectorGenerators.sparseVec(0), "limit", 5)))
            .get(FieldConfigs.SPARSE_FIELD);
    assertTrue(sparseResults.size() > 0);
  }

  // -- full backup lifecycle ---------------------------------------------------------

  @Test
  void backupFullLifecycle() {
    Collection collection = populatedDense().collection();
    String backupName = CollectionFixtures.uid("bklife");
    String targetName = CollectionFixtures.uid("lifecol");
    backupsToDelete.add(backupName);
    collectionsToDelete.add(targetName);

    Map<String, Object> result = collection.createBackup(backupName);
    assertEquals("in_progress", result.get("status"));

    waitForBackup(backupName);

    assertNotEquals("running", client.backupStatus().get("status"));

    Map<String, Object> info = client.backupInfo(backupName);
    assertInstanceOf(Map.class, info);

    client.restoreBackup(backupName, targetName);
    waitForRestore();
    assertTrue(CollectionFixtures.collectionNames(client).contains(targetName));

    client.deleteBackup(backupName);

    Object listed = client.listBackups();
    assertFalse(containsBackup(listed, backupName));
  }

  // -- downloadBackup (Endee client) --------------------------------------------------

  @Test
  void downloadBackupReturnsPath() {
    Collection collection = populatedDense().collection();
    String backupName = CollectionFixtures.uid("bkdl");
    backupsToDelete.add(backupName);
    Path tmpDir = newTempDir();
    String localPath = tmpDir.resolve(backupName + ".tar").toString();

    collection.createBackup(backupName);
    waitForBackup(backupName);
    String result = client.downloadBackup(backupName, localPath);
    assertEquals(localPath, result);
  }

  @Test
  void downloadBackupDirectoryDestinationAppendsBackupName() {
    Collection collection = populatedDense().collection();
    String backupName = CollectionFixtures.uid("bkdld");
    backupsToDelete.add(backupName);
    Path tmpDir = newTempDir();

    collection.createBackup(backupName);
    waitForBackup(backupName);
    String result = client.downloadBackup(backupName, tmpDir.toString());
    assertEquals(tmpDir.resolve(backupName + ".tar").toString(), result);
    assertTrue(Files.exists(Path.of(result)), "Expected file at " + result);
  }

  @Test
  void downloadBackupFileExistsAndNonempty() {
    Collection collection = populatedDense().collection();
    String backupName = CollectionFixtures.uid("bkdlf");
    backupsToDelete.add(backupName);
    Path tmpDir = newTempDir();
    Path localPath = tmpDir.resolve(backupName + ".tar");

    collection.createBackup(backupName);
    waitForBackup(backupName);
    client.downloadBackup(backupName, localPath.toString());
    assertTrue(Files.exists(localPath), "Expected file at " + localPath);
    try {
      assertTrue(Files.size(localPath) > 0, "Downloaded file is empty");
    } catch (IOException e) {
      fail(e);
    }
  }

  @Test
  void downloadBackupEmptyNameRaises() {
    Path tmpDir = newTempDir();
    String dest = tmpDir.resolve("x.tar").toString();
    assertThrows(IllegalArgumentException.class, () -> client.downloadBackup("", dest));
  }

  @Test
  void downloadBackupEmptyDestRaises() {
    assertThrows(IllegalArgumentException.class, () -> client.downloadBackup("some_backup", ""));
  }

  @Test
  void downloadBackupNonexistentRaises() {
    Path tmpDir = newTempDir();
    String dest = tmpDir.resolve("ghost.tar").toString();
    assertThrows(
        EndeeException.class, () -> client.downloadBackup("definitely_does_not_exist_xyz_99999", dest));
  }

  // -- uploadBackup (Endee client) -----------------------------------------------------

  @Test
  void uploadBackupReturnsMap() {
    Collection collection = populatedDense().collection();
    String backupName = CollectionFixtures.uid("bkul");
    String uploadName = CollectionFixtures.uid("bkulup");
    backupsToDelete.add(backupName);
    backupsToDelete.add(uploadName);
    Path tmpDir = newTempDir();
    String localPath = tmpDir.resolve(uploadName + ".tar").toString();

    collection.createBackup(backupName);
    waitForBackup(backupName);
    client.downloadBackup(backupName, localPath);
    Map<String, Object> result = client.uploadBackup(localPath);
    waitForBackup(uploadName);
    assertInstanceOf(Map.class, result);
  }

  @Test
  void uploadBackupAppearsInListBackups() {
    Collection collection = populatedDense().collection();
    String backupName = CollectionFixtures.uid("bkulst");
    String uploadName = CollectionFixtures.uid("bkulstup");
    backupsToDelete.add(backupName);
    backupsToDelete.add(uploadName);
    Path tmpDir = newTempDir();
    String localPath = tmpDir.resolve(uploadName + ".tar").toString();

    collection.createBackup(backupName);
    waitForBackup(backupName);
    client.downloadBackup(backupName, localPath);
    client.uploadBackup(localPath);
    waitForBackup(uploadName);
    assertTrue(
        backupNames(client.listBackups()).contains(uploadName),
        "Uploaded backup '" + uploadName + "' not found in listBackups()");
  }

  @Test
  void uploadBackupNonTarRaises() {
    Path tmpDir = newTempDir();
    Path badPath = tmpDir.resolve("backup.zip");
    try {
      Files.write(badPath, "fake content".getBytes());
    } catch (IOException e) {
      fail(e);
    }
    assertThrows(IllegalArgumentException.class, () -> client.uploadBackup(badPath.toString()));
  }

  @Test
  void uploadBackupWithCustomNameOverload() {
    Collection collection = populatedDense().collection();
    String backupName = CollectionFixtures.uid("bkulcn");
    String uploadName = CollectionFixtures.uid("bkulcnup");
    backupsToDelete.add(backupName);
    backupsToDelete.add(uploadName);
    Path tmpDir = newTempDir();
    // Downloaded under an unrelated filename - the custom backupName argument, not the file's own
    // name, must be what determines the restored backup's name.
    String localPath = tmpDir.resolve("staged_download.tar").toString();

    collection.createBackup(backupName);
    waitForBackup(backupName);
    client.downloadBackup(backupName, localPath);
    Map<String, Object> result = client.uploadBackup(localPath, uploadName);
    waitForBackup(uploadName);
    assertInstanceOf(Map.class, result);
    assertTrue(
        backupNames(client.listBackups()).contains(uploadName),
        "Uploaded backup '" + uploadName + "' (custom name) not found in listBackups()");
  }

  // -- download + upload roundtrip / lifecycle -----------------------------------------

  @Test
  void downloadUploadRoundtrip() {
    Collection collection = populatedDense().collection();
    String backupName = CollectionFixtures.uid("bkrt");
    String uploadName = CollectionFixtures.uid("bkrtup");
    backupsToDelete.add(backupName);
    backupsToDelete.add(uploadName);
    Path tmpDir = newTempDir();
    Path localPath = tmpDir.resolve(uploadName + ".tar");

    collection.createBackup(backupName);
    waitForBackup(backupName);

    client.downloadBackup(backupName, localPath.toString());
    assertTrue(Files.exists(localPath));

    client.uploadBackup(localPath.toString());
    waitForBackup(uploadName);

    Set<String> current = backupNames(client.listBackups());
    assertTrue(current.contains(backupName), "Original backup '" + backupName + "' missing");
    assertTrue(current.contains(uploadName), "Uploaded backup '" + uploadName + "' missing");
  }

  @Test
  void downloadUploadRestoreLifecycle() {
    Collection collection = populatedDense().collection();
    String backupName = CollectionFixtures.uid("bklc");
    String uploadName = CollectionFixtures.uid("bklcup");
    String targetName = CollectionFixtures.uid("uplrstcol");
    backupsToDelete.add(backupName);
    backupsToDelete.add(uploadName);
    collectionsToDelete.add(targetName);
    Path tmpDir = newTempDir();
    Path localPath = tmpDir.resolve(uploadName + ".tar");

    collection.createBackup(backupName);
    waitForBackup(backupName);

    client.downloadBackup(backupName, localPath.toString());
    assertTrue(Files.exists(localPath));
    try {
      assertTrue(Files.size(localPath) > 0);
    } catch (IOException e) {
      fail(e);
    }

    client.uploadBackup(localPath.toString());
    waitForBackup(uploadName);

    client.restoreBackup(uploadName, targetName);
    waitForRestore();
    assertTrue(
        CollectionFixtures.collectionNames(client).contains(targetName),
        "Restored collection '" + targetName + "' not found after upload+restore");

    Collection restored = client.getCollection(targetName);
    List<SearchHit> results =
        restored
            .search(Map.of(FieldConfigs.DENSE_FIELD, Map.of("query", VectorGenerators.denseVec(0), "limit", 5)))
            .get(FieldConfigs.DENSE_FIELD);
    assertTrue(results.size() > 0);

    client.deleteBackup(backupName);
    client.deleteBackup(uploadName);

    Set<String> remaining = backupNames(client.listBackups());
    assertFalse(remaining.contains(backupName));
    assertFalse(remaining.contains(uploadName));
  }
}
