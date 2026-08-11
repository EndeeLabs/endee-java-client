package io.endee.client.util;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Validation utilities. */
public final class ValidationUtils {

  private static final Pattern COLLECTION_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]+$");
  private static final int MAX_COLLECTION_NAME_LENGTH = 48;

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
}
