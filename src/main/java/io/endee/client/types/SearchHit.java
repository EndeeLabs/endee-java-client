package io.endee.client.types;

import java.util.Map;

/** A single hit from a search result. */
public class SearchHit {
  private String id;
  private double similarity;
  private Map<String, Object> meta;
  private Map<String, Object> filter;

  public SearchHit() {}

  public SearchHit(
      String id, double similarity, Map<String, Object> meta, Map<String, Object> filter) {
    this.id = id;
    this.similarity = similarity;
    this.meta = meta;
    this.filter = filter;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public double getSimilarity() {
    return similarity;
  }

  public void setSimilarity(double similarity) {
    this.similarity = similarity;
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

  @Override
  public String toString() {
    return "SearchHit{id='" + id + "', similarity=" + similarity + "}";
  }
}
