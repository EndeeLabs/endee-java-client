package io.endee.client.support;

import io.endee.client.types.ObjectItem;
import io.endee.client.types.SparseData;
import java.util.LinkedHashMap;
import java.util.Map;

// Deterministic object builders. Filter fields for position i: category = i%3, priority = i%5, score = i, tags = even/odd.
public final class ObjectBuilders {

  private ObjectBuilders() {}

  private static final String[] CATEGORIES = {"A", "B", "C"};

  public static Map<String, Object> denseFilterFor(int i) {
    Map<String, Object> filter = new LinkedHashMap<>();
    filter.put("category", CATEGORIES[i % 3]);
    filter.put("priority", i % 5);
    filter.put("score", i);
    filter.put("tags", i % 2 == 0 ? "important" : "normal");
    return filter;
  }

  public static Map<String, Object> simpleFilterFor(int i) {
    Map<String, Object> filter = new LinkedHashMap<>();
    filter.put("category", CATEGORIES[i % 3]);
    filter.put("score", i);
    filter.put("tags", i % 2 == 0 ? "important" : "normal");
    return filter;
  }

  public static Map<String, Object> metaFor(int i) {
    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("index", i);
    meta.put("text", "Document " + i);
    return meta;
  }

  public static ObjectItem denseItem(int i, int dim) {
    return ObjectItem.builder(String.format("vec_%04d", i))
        .vector(FieldConfigs.DENSE_FIELD, VectorGenerators.denseVec(dim, i))
        .meta(metaFor(i))
        .filter(denseFilterFor(i))
        .build();
  }

  public static ObjectItem denseItem(int i) {
    return denseItem(i, VectorGenerators.DIM);
  }

  public static ObjectItem hybridItem(int i, int dim) {
    SparseData sd = VectorGenerators.sparseVec(i);
    return ObjectItem.builder(String.format("vec_%04d", i))
        .vector(FieldConfigs.DENSE_FIELD, VectorGenerators.denseVec(dim, i))
        .sparse(FieldConfigs.SPARSE_FIELD, sd)
        .meta(metaFor(i))
        .filter(denseFilterFor(i))
        .build();
  }

  public static ObjectItem sparseItem(int i) {
    return ObjectItem.builder(String.format("sp_%04d", i))
        .sparse(FieldConfigs.SPARSE_FIELD, VectorGenerators.sparseVec(i))
        .meta(metaFor(i))
        .filter(simpleFilterFor(i))
        .build();
  }

  public static ObjectItem mvItem(int i, int dim) {
    return ObjectItem.builder(String.format("mv_%04d", i))
        .multiVector(
            FieldConfigs.MV_FIELD,
            VectorGenerators.multiVec(VectorGenerators.MV_TOKENS, dim, i))
        .meta(metaFor(i))
        .filter(simpleFilterFor(i))
        .build();
  }

  public static ObjectItem mvItem(int i) {
    return mvItem(i, VectorGenerators.DIM);
  }
}
