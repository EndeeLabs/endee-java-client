package io.endee.client.support;

import io.endee.client.Endee;
import java.util.List;
import java.util.regex.Pattern;

// Lazily builds one shared Endee client from ENDEE_TOKEN/ENDEE_BASE_URL.
public final class TestConfig {

  private TestConfig() {}

  // Only matches our uid()-style test names, so real/user collections are never touched.
  private static final Pattern STALE_COLLECTION_PATTERN = Pattern.compile("^[a-z]+_[0-9a-f]{10}$");

  private static volatile Endee client;

  // Returns the shared client for the test session, verifying the server on first use.
  public static synchronized Endee client() {
    if (client == null) {
      Endee c = buildClient(System.getenv("ENDEE_TOKEN"));
      verifyServerAndCleanup(c);
      client = c;
    }
    return client;
  }

  // Returns a client built from NDD_ROOT_TOKEN, or null if it isn't set.
  public static Endee rootClientOrNull() {
    String rootToken = System.getenv("NDD_ROOT_TOKEN");
    if (rootToken == null || rootToken.isBlank()) {
      return null;
    }
    return buildClient(rootToken);
  }

  private static Endee buildClient(String token) {
    Endee c = (token != null && !token.isBlank()) ? new Endee(token) : new Endee();
    String baseUrl = System.getenv("ENDEE_BASE_URL");
    if (baseUrl != null && !baseUrl.isBlank()) {
      c.setBaseUrl(baseUrl);
    }
    return c;
  }

  private static void verifyServerAndCleanup(Endee c) {
    List<String> existing;
    try {
      existing = CollectionFixtures.collectionNames(c);
    } catch (Exception e) {
      throw new IllegalStateException("Server unreachable - aborting test session", e);
    }
    for (String name : existing) {
      if (STALE_COLLECTION_PATTERN.matcher(name).matches()) {
        CollectionFixtures.safeDelete(c, name);
      }
    }
  }
}
