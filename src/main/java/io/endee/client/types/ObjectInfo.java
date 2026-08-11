package io.endee.client.types;

import java.util.Map;

/** Full object retrieved from a collection via {@code getObjects}. */
public class ObjectInfo {
  private String id;
  private Map<String, Object> meta;
  private Map<String, Object> filter;
  private Map<String, double[]> vectors;
  private Map<String, SparseData> sparses;
  private Map<String, double[][]> multiVectors;

  public ObjectInfo() {}

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public Map<String, Object> getMeta() {
    return meta;
  }

  public void setMeta(Map<String, Object> meta) {
    this.meta = meta;
  }

  public Map<String, Object> getFilter() {
    return filter;
  }

  public void setFilter(Map<String, Object> filter) {
    this.filter = filter;
  }

  public Map<String, double[]> getVectors() {
    return vectors;
  }

  public void setVectors(Map<String, double[]> vectors) {
    this.vectors = vectors;
  }

  public Map<String, SparseData> getSparses() {
    return sparses;
  }

  public void setSparses(Map<String, SparseData> sparses) {
    this.sparses = sparses;
  }

  public Map<String, double[][]> getMultiVectors() {
    return multiVectors;
  }

  public void setMultiVectors(Map<String, double[][]> multiVectors) {
    this.multiVectors = multiVectors;
  }

  @Override
  public String toString() {
    return "ObjectInfo{id='"
        + id
        + "', vectors="
        + (vectors != null ? vectors.keySet() : "[]")
        + ", sparses="
        + (sparses != null ? sparses.keySet() : "[]")
        + ", multiVectors="
        + (multiVectors != null ? multiVectors.keySet() : "[]")
        + "}";
  }
}
