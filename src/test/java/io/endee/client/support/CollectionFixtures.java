package io.endee.client.support;

import io.endee.client.Collection;
import io.endee.client.Endee;
import io.endee.client.types.ObjectItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Builds empty/populated collections per field type for use in @BeforeEach/@AfterEach.
public final class CollectionFixtures {

  private CollectionFixtures() {}

  // A freshly created (or populated) collection and the name it was created under.
  public record NamedCollection(String name, Collection collection) {}

  public static String uid(String prefix) {
    return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
  }

  // Best-effort teardown delete, retrying while the collection is briefly busy or lagging.
  public static void safeDelete(Endee client, String name) {
    retryWhilePresent(() -> client.deleteCollection(name), () -> collectionNames(client).contains(name));
  }

  // Same retry rationale as safeDelete, for backups.
  public static void safeDeleteBackup(Endee client, String name) {
    retryWhilePresent(() -> client.deleteBackup(name), () -> backupNames(client).contains(name));
  }

  private static void retryWhilePresent(Runnable action, java.util.function.BooleanSupplier stillPresent) {
    final int maxAttempts = 20;
    final long retryDelayMillis = 1000;
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        action.run();
        return;
      } catch (Exception e) {
        if (attempt == maxAttempts) {
          return; // give up silently - still best-effort teardown
        }
        // Skip the presence check on the first failure only - a just-created resource can lag.
        if (attempt > 1) {
          boolean present;
          try {
            present = stillPresent.getAsBoolean();
          } catch (Exception checkFailed) {
            return; // can't confirm it still exists - stop rather than spin blindly
          }
          if (!present) {
            return; // nothing left to delete - the earlier failure was permanent, not transient
          }
        }
        try {
          Thread.sleep(retryDelayMillis);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  private static List<String> backupNames(Endee client) {
    Object result = client.listBackups();
    List<String> names = new ArrayList<>();
    if (result instanceof Map<?, ?> map) {
      for (Object key : map.keySet()) {
        if (key instanceof String s) {
          names.add(s);
        }
      }
    } else if (result instanceof List<?> list) {
      for (Object item : list) {
        if (item instanceof Map<?, ?> m && m.get("name") instanceof String s) {
          names.add(s);
        }
      }
    }
    return names;
  }

  public static List<String> collectionNames(Endee client) {
    List<Map<String, Object>> collections = client.listCollections();
    List<String> names = new ArrayList<>();
    for (Map<String, Object> c : collections) {
      Object n = c.get("name");
      if (n instanceof String s) {
        names.add(s);
      }
    }
    return names;
  }

  // -- Dense --------------------------------------------------------------------

  public static NamedCollection emptyDense() {
    Endee client = TestConfig.client();
    String name = uid("t");
    client.createCollection(name, List.of(FieldConfigs.denseField()));
    return new NamedCollection(name, client.getCollection(name));
  }

  public static NamedCollection populatedDense() {
    NamedCollection nc = emptyDense();
    List<ObjectItem> items = new ArrayList<>();
    for (int i = 0; i < VectorGenerators.N_VECTORS; i++) {
      items.add(ObjectBuilders.denseItem(i));
    }
    nc.collection().upsert(items);
    return nc;
  }

  // -- Hybrid (dense + sparse) ----------------------------------------------------

  public static NamedCollection emptyHybrid() {
    Endee client = TestConfig.client();
    String name = uid("h");
    client.createCollection(
        name,
        List.of(
            FieldConfigs.denseField(VectorGenerators.HYBRID_DIM, "cosine", "int8"),
            FieldConfigs.sparseField()));
    return new NamedCollection(name, client.getCollection(name));
  }

  public static NamedCollection populatedHybrid() {
    NamedCollection nc = emptyHybrid();
    List<ObjectItem> items = new ArrayList<>();
    for (int i = 0; i < VectorGenerators.N_VECTORS; i++) {
      items.add(ObjectBuilders.hybridItem(i, VectorGenerators.HYBRID_DIM));
    }
    nc.collection().upsert(items);
    return nc;
  }

  // -- Sparse only ----------------------------------------------------------------

  public static NamedCollection emptySparse() {
    Endee client = TestConfig.client();
    String name = uid("sp");
    client.createCollection(name, List.of(FieldConfigs.sparseField()));
    return new NamedCollection(name, client.getCollection(name));
  }

  public static NamedCollection populatedSparse() {
    NamedCollection nc = emptySparse();
    List<ObjectItem> items = new ArrayList<>();
    for (int i = 0; i < VectorGenerators.N_VECTORS; i++) {
      items.add(ObjectBuilders.sparseItem(i));
    }
    nc.collection().upsert(items);
    return nc;
  }

  // -- Multi-vector (ColBERT-style) -------------------------------------------------

  public static NamedCollection emptyMv() {
    Endee client = TestConfig.client();
    String name = uid("mv");
    client.createCollection(name, List.of(FieldConfigs.mvField()));
    return new NamedCollection(name, client.getCollection(name));
  }

  public static NamedCollection populatedMv() {
    NamedCollection nc = emptyMv();
    List<ObjectItem> items = new ArrayList<>();
    for (int i = 0; i < VectorGenerators.N_VECTORS; i++) {
      items.add(ObjectBuilders.mvItem(i));
    }
    nc.collection().upsert(items);
    return nc;
  }
}
