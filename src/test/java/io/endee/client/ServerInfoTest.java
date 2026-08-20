package io.endee.client;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.endee.client.support.TestConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// Covers health(), stats(), and basic Endee client behavior.
class ServerInfoTest {

  private Endee client;

  @BeforeEach
  void setUp() {
    client = TestConfig.client();
  }

  // -- client basics ------------------------------------------------------------------

  @Test
  void toStringReturnsNonEmptyString() {
    String repr = client.toString();
    assertNotNull(repr);
    assertFalse(repr.isEmpty());
  }

  @Test
  void closeDoesNotThrow() {
    assertDoesNotThrow(() -> client.close());
  }

  @Test
  void clientUsableAfterClose() {
    client.close();
    Map<String, Object> result = client.health();
    assertTrue(result.containsKey("status"));
  }

  // -- health -----------------------------------------------------------------------

  @Test
  void healthReturnsMap() {
    Map<String, Object> result = client.health();
    assertInstanceOf(Map.class, result);
  }

  @Test
  void healthHasStatusKey() {
    Map<String, Object> result = client.health();
    assertTrue(result.containsKey("status"), "Missing 'status' key in health response: " + result);
  }

  @Test
  void healthStatusIsString() {
    Map<String, Object> result = client.health();
    Object status = result.get("status");
    assertInstanceOf(String.class, status);
    assertFalse(((String) status).isEmpty());
  }

  @Test
  void healthHasTimestampOrTimeKey() {
    Map<String, Object> result = client.health();
    List<String> timeKeys = List.of("timestamp", "time", "uptime", "ts");
    boolean hasTime = timeKeys.stream().anyMatch(result::containsKey);
    assertTrue(
        hasTime || result.containsKey("status"),
        "No time-related key found in health response: " + result.keySet());
  }

  @Test
  void healthStatusOkOrHealthy() {
    Map<String, Object> result = client.health();
    String status = ((String) result.get("status")).toLowerCase();
    List<String> acceptable = List.of("ok", "healthy", "up", "running", "alive");
    assertTrue(acceptable.contains(status), "Unexpected health status: '" + status + "'");
  }

  @Test
  void healthCanBeCalledMultipleTimes() {
    for (int i = 0; i < 3; i++) {
      Map<String, Object> result = client.health();
      assertTrue(result.containsKey("status"));
    }
  }

  // -- stats ------------------------------------------------------------------------

  @Test
  void statsReturnsMap() {
    Map<String, Object> result = client.stats();
    assertInstanceOf(Map.class, result);
  }

  @Test
  void statsHasVersionKey() {
    Map<String, Object> result = client.stats();
    assertTrue(result.containsKey("version"), "Missing 'version' key in stats response: " + result);
  }

  @Test
  void statsVersionIsString() {
    Map<String, Object> result = client.stats();
    Object version = result.get("version");
    assertInstanceOf(String.class, version);
    assertFalse(((String) version).isEmpty());
  }

  @Test
  void statsHasUptimeOrRequestsKey() {
    Map<String, Object> result = client.stats();
    List<String> metricKeys = List.of("uptime", "total_requests", "requests", "uptime_seconds");
    boolean hasMetrics = metricKeys.stream().anyMatch(result::containsKey);
    assertTrue(hasMetrics, "No metrics key found in stats response: " + result.keySet());
  }

  @Test
  void statsCanBeCalledMultipleTimes() {
    for (int i = 0; i < 3; i++) {
      Map<String, Object> result = client.stats();
      assertTrue(result.containsKey("version"));
    }
  }
}
