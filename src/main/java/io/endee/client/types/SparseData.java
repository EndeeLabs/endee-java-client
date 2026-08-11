package io.endee.client.types;

/** Holder for sparse vector data (indices + values). */
public class SparseData {
  private final int[] indices;
  private final double[] values;

  public SparseData(int[] indices, double[] values) {
    if (indices == null || values == null) {
      throw new IllegalArgumentException("indices and values must not be null");
    }
    if (indices.length != values.length) {
      throw new IllegalArgumentException(
          "indices and values must have the same length ("
              + indices.length
              + " vs "
              + values.length
              + ")");
    }
    this.indices = indices;
    this.values = values;
  }

  public int[] getIndices() {
    return indices;
  }

  public double[] getValues() {
    return values;
  }
}
