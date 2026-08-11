package io.endee.client.types;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An object to upsert into a collection.
 *
 * <p>Field values in the {@code fields} map must be one of:
 *
 * <ul>
 *   <li>{@code double[]} — dense vector field
 *   <li>{@link SparseData} — sparse vector field
 *   <li>{@code double[][]} — multi-vector field
 * </ul>
 */
public class ObjectItem {
  private final String id;
  private Map<String, Object> meta;
  private Map<String, Object> filter;
  private Map<String, Object> fields;

  private ObjectItem(String id) {
    this.id = id;
  }

  public static Builder builder(String id) {
    return new Builder(id);
  }

  public String getId() {
    return id;
  }

  public Map<String, Object> getMeta() {
    return meta;
  }

  public Map<String, Object> getFilter() {
    return filter;
  }

  public Map<String, Object> getFields() {
    return fields;
  }

  public static class Builder {
    private final ObjectItem item;

    private Builder(String id) {
      this.item = new ObjectItem(id);
      this.item.fields = new LinkedHashMap<>();
    }

    public Builder meta(Map<String, Object> meta) {
      item.meta = meta;
      return this;
    }

    public Builder filter(Map<String, Object> filter) {
      item.filter = filter;
      return this;
    }

    /** Set a dense vector field. */
    public Builder vector(String fieldName, double[] vector) {
      item.fields.put(fieldName, vector);
      return this;
    }

    /** Set a sparse vector field. */
    public Builder sparse(String fieldName, SparseData sparse) {
      item.fields.put(fieldName, sparse);
      return this;
    }

    /** Set a multi-vector field. */
    public Builder multiVector(String fieldName, double[][] vectors) {
      item.fields.put(fieldName, vectors);
      return this;
    }

    public ObjectItem build() {
      return item;
    }
  }
}
