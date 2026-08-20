package io.endee.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.endee.client.exception.ConflictException;
import io.endee.client.exception.EndeeException;
import io.endee.client.support.CollectionFixtures;
import io.endee.client.support.TestConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

// Covers self-service token lifecycle: createMyToken/listMyTokens/deleteMyToken.
class TokenManagementTest {

  private final Endee client = TestConfig.client();

  private void deleteTokenSilently(String name) {
    try {
      client.deleteMyToken(name);
    } catch (Exception ignored) {
      // best-effort teardown
    }
  }

  // -- listMyTokens ---------------------------------------------------------------

  @Test
  void listMyTokensReturnsList() {
    List<Map<String, Object>> result = client.listMyTokens();
    assertInstanceOf(List.class, result);
  }

  @Test
  void listMyTokensItemsAreMaps() {
    List<Map<String, Object>> result = client.listMyTokens();
    for (Object item : result) {
      assertInstanceOf(Map.class, item, "Expected map, got " + item.getClass() + ": " + item);
    }
  }

  // -- createMyToken ----------------------------------------------------------------

  @Test
  void createMyTokenReturnsString() {
    String tokName = CollectionFixtures.uid("tok");
    try {
      String result = client.createMyToken(tokName);
      assertNotNull(result);
      assertTrue(result.length() > 0);
    } finally {
      deleteTokenSilently(tokName);
    }
  }

  @Test
  void createMyTokenRwType() {
    String tokName = CollectionFixtures.uid("rwtok");
    try {
      String result = client.createMyToken(tokName, "rw");
      assertNotNull(result);
    } finally {
      deleteTokenSilently(tokName);
    }
  }

  @Test
  void createMyTokenReadonlyType() {
    String tokName = CollectionFixtures.uid("rtok");
    try {
      String result = client.createMyToken(tokName, "r");
      assertNotNull(result);
    } finally {
      deleteTokenSilently(tokName);
    }
  }

  @Test
  void createMyTokenAppearsInList() {
    String tokName = CollectionFixtures.uid("ltok");
    try {
      client.createMyToken(tokName);
      List<Map<String, Object>> tokens = client.listMyTokens();
      List<Object> names = tokens.stream().map(t -> t.get("name")).toList();
      assertTrue(names.contains(tokName), "Token '" + tokName + "' not found in listMyTokens(): " + names);
    } finally {
      deleteTokenSilently(tokName);
    }
  }

  @Test
  void createMyTokenDefaultTypeIsRw() {
    String tokName = CollectionFixtures.uid("deftok");
    try {
      client.createMyToken(tokName);
      List<Map<String, Object>> tokens = client.listMyTokens();
      Map<String, Object> tok =
          tokens.stream().filter(t -> tokName.equals(t.get("name"))).findFirst().orElse(null);
      assertNotNull(tok, "Token '" + tokName + "' not found in listMyTokens()");
      if (tok.containsKey("token_type")) {
        assertTrue(List.of("rw", "read_write", "readwrite").contains(tok.get("token_type")));
      }
    } finally {
      deleteTokenSilently(tokName);
    }
  }

  // -- deleteMyToken ------------------------------------------------------------------

  @Test
  void deleteMyTokenReturnsMap() {
    String tokName = CollectionFixtures.uid("dtok");
    client.createMyToken(tokName);
    Map<String, Object> result = client.deleteMyToken(tokName);
    assertInstanceOf(Map.class, result);
  }

  @Test
  void deleteMyTokenRemovesFromList() {
    String tokName = CollectionFixtures.uid("rmtok");
    client.createMyToken(tokName);
    client.deleteMyToken(tokName);
    List<Map<String, Object>> tokens = client.listMyTokens();
    List<Object> names = tokens.stream().map(t -> t.get("name")).toList();
    assertFalse(names.contains(tokName));
  }

  @Test
  void deleteMyTokenSecondDeleteRaises() {
    String tokName = CollectionFixtures.uid("dd");
    client.createMyToken(tokName);
    client.deleteMyToken(tokName);
    assertThrows(EndeeException.class, () -> client.deleteMyToken(tokName));
  }

  // -- create -> delete lifecycle -----------------------------------------------------

  @Test
  void tokenFullLifecycle() {
    String tokName = CollectionFixtures.uid("full");
    try {
      String tokenStr = client.createMyToken(tokName, "r");
      assertNotNull(tokenStr);

      List<Map<String, Object>> tokens = client.listMyTokens();
      List<Object> names = tokens.stream().map(t -> t.get("name")).toList();
      assertTrue(names.contains(tokName));

      client.deleteMyToken(tokName);

      List<Map<String, Object>> tokensAfter = client.listMyTokens();
      List<Object> namesAfter = tokensAfter.stream().map(t -> t.get("name")).toList();
      assertFalse(namesAfter.contains(tokName));
    } finally {
      deleteTokenSilently(tokName);
    }
  }

  // -- duplicate token name -------------------------------------------------------------

  @Test
  void createDuplicateTokenRaisesConflict() {
    String tokName = CollectionFixtures.uid("duptok");
    try {
      client.createMyToken(tokName);
      assertThrows(ConflictException.class, () -> client.createMyToken(tokName));
    } finally {
      deleteTokenSilently(tokName);
    }
  }

  // -- client-side validation -----------------------------------------------------------

  @Test
  void createMyTokenEmptyNameRaises() {
    assertThrows(IllegalArgumentException.class, () -> client.createMyToken(""));
  }

  @Test
  void createMyTokenInvalidTypeRaises() {
    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> client.createMyToken("any_name", "admin"));
    assertTrue(ex.getMessage().contains("token_type"), "Expected message to mention token_type: " + ex.getMessage());
  }

  @Test
  void deleteMyTokenEmptyNameRaises() {
    assertThrows(IllegalArgumentException.class, () -> client.deleteMyToken(""));
  }
}
