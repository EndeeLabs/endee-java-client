package io.endee.client.types;

/** Precision types for vector quantization. */
public enum Precision {
  BINARY("binary"),
  INT8("int8"),
  INT16("int16"),
  FLOAT32("float32"),
  FLOAT16("float16"),
  INT8E("int8e");

  private final String value;

  Precision(String value) {
    this.value = value;
  }

  public String getValue() {
    return value;
  }

  public static Precision fromValue(String value) {
    for (Precision p : values()) {
      if (p.value.equalsIgnoreCase(value)) {
        return p;
      }
    }
    throw new IllegalArgumentException("Unknown precision: " + value);
  }

  @Override
  public String toString() {
    return value;
  }
}
