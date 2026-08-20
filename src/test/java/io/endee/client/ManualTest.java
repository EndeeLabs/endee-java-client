package io.endee.client;

import io.endee.client.types.ObjectItem;
import io.endee.client.types.SearchHit;
import java.util.List;
import java.util.Map;

// Manual test, not run by `mvn test`. Run with `mvn test-compile exec:java`.
public class ManualTest {

  public static void main(String[] args) throws Exception {
    String token = System.getenv("ENDEE_TOKEN");
    String baseUrl = System.getenv("ENDEE_BASE_URL");

    Endee client = (token != null && !token.isBlank()) ? new Endee(token) : new Endee();
    if (baseUrl != null && !baseUrl.isBlank()) {
      client.setBaseUrl(baseUrl);
    }

    String collectionName = "manual_test_" + System.currentTimeMillis();

    System.out.println("1. health() -> " + client.health());

    System.out.println("2. createCollection(" + collectionName + ")");
    Map<String, Object> created =
        client.createCollection(
            collectionName,
            List.of(
                Map.of(
                    "name", "embedding",
                    "type", "vector",
                    "params",
                        Map.of(
                            "dimension", 4,
                            "space_type", "cosine",
                            "precision", "float32"))));
    System.out.println("   -> " + created);

    System.out.println("3. listCollections() -> " + client.listCollections());

    Collection collection = client.getCollection(collectionName);
    System.out.println("4. getCollection() -> describe() = " + collection.describe());

    System.out.println("5. upsert() one object");
    Map<String, Object> upserted =
        collection.upsert(
            List.of(
                ObjectItem.builder("obj-1")
                    .vector("embedding", new double[] {0.1, 0.2, 0.3, 0.4})
                    .meta(Map.of("label", "hello world"))
                    .build()));
    System.out.println("   -> " + upserted);

    System.out.println("6. getObjects([\"obj-1\"])");
    System.out.println("   -> " + collection.getObjects(List.of("obj-1")));

    System.out.println("7. search() for nearest neighbours");
    Map<String, List<SearchHit>> results =
        collection.search(
            Map.of("embedding", Map.of("query", new double[] {0.1, 0.2, 0.3, 0.4}, "limit", 5)));
    System.out.println("   -> " + results);

    System.out.println("8. deleteCollection(" + collectionName + ")");
    System.out.println("   -> " + client.deleteCollection(collectionName));

    System.out.println("\nAll steps completed without an exception - the client is working.");
  }
}
