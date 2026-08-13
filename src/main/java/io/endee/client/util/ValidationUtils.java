package io.endee.client.util;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Validation utilities. */
public final class ValidationUtils {

  private static final Pattern COLLECTION_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]+$");
  private static final int MAX_COLLECTION_NAME_LENGTH = 48;
  private static final int MAX_FILTER_KEY_BYTES = 128;
  private static final int MAX_FILTER_VALUE_BYTES = 1024;

  private ValidationUtils() {}

  /**
   * Validates a collection name. Must be alphanumeric with underscores, max 48 characters, and must
   * not start with "__".
   */
  public static boolean isValidCollectionName(String name) {
    if (name == null || name.isEmpty()) {
      return false;
    }
    if (name.length() > MAX_COLLECTION_NAME_LENGTH) {
      return false;
    }
    if (name.startsWith("__")) {
      return false;
    }
    return COLLECTION_NAME_PATTERN.matcher(name).matches();
  }

  /** Validates that all object IDs are non-empty and unique. */
  public static void validateObjectIds(List<String> ids) {
    Set<String> seenIds = new HashSet<>();
    Set<String> duplicateIds = new HashSet<>();

    for (String id : ids) {
      if (id == null || id.isEmpty()) {
        throw new IllegalArgumentException("All objects must have a non-empty ID");
      }
      if (seenIds.contains(id)) {
        duplicateIds.add(id);
      } else {
        seenIds.add(id);
      }
    }

    if (!duplicateIds.isEmpty()) {
      throw new IllegalArgumentException("Duplicate IDs found: " + String.join(", ", duplicateIds));
    }
  }

  /** Validates filter key/value sizes (key ≤ 128 bytes, value ≤ 1024 bytes). */
  public static void validateFilter(Map<String, Object> filter) {
    if (filter == null) return;
    for (Map.Entry<String, Object> entry : filter.entrySet()) {
      String key = entry.getKey();
      if (key.getBytes(StandardCharsets.UTF_8).length > MAX_FILTER_KEY_BYTES) {
        throw new IllegalArgumentException(
            "Filter key '" + key + "' exceeds " + MAX_FILTER_KEY_BYTES + " bytes");
      }
      Object value = entry.getValue();
      if (value != null) {
        String valStr = String.valueOf(value);
        if (valStr.getBytes(StandardCharsets.UTF_8).length > MAX_FILTER_VALUE_BYTES) {
          throw new IllegalArgumentException(
              "Filter value for key '" + key + "' exceeds " + MAX_FILTER_VALUE_BYTES + " bytes");
        }
      }
    }
  }
}
