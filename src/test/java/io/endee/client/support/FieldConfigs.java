package io.endee.client.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Builds createCollection field-config maps for dense, sparse, and multi_vector fields.
public final class FieldConfigs {

  private FieldConfigs() {}

  public static final String DENSE_FIELD = "dense";
  public static final String SPARSE_FIELD = "sparse";
  public static final String MV_FIELD = "colbert";

  public static final List<String> ALL_PRECISIONS =
      List.of("float32", "float16", "int16", "int8", "int8e", "binary");
  public static final List<String> ALL_SPACE_TYPES = List.of("cosine", "l2", "ip");

  public static Map<String, Object> denseField(
      int dim, String spaceType, String precision, Integer m, Integer efCon) {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("dimension", dim);
    params.put("space_type", spaceType);
    params.put("precision", precision);
    if (m != null) {
      params.put("M", m);
    }
    if (efCon != null) {
      params.put("ef_con", efCon);
    }
    Map<String, Object> field = new LinkedHashMap<>();
    field.put("name", DENSE_FIELD);
    field.put("type", "vector");
    field.put("params", params);
    return field;
  }

  public static Map<String, Object> denseField(int dim, String spaceType, String precision) {
    return denseField(dim, spaceType, precision, 16, 128);
  }

  public static Map<String, Object> denseField() {
    return denseField(VectorGenerators.DIM, "cosine", "int8");
  }

  public static Map<String, Object> sparseField(String sparseModel) {
    Map<String, Object> field = new LinkedHashMap<>();
    field.put("name", SPARSE_FIELD);
    field.put("type", "sparse");
    field.put("sparse_model", sparseModel);
    return field;
  }

  public static Map<String, Object> sparseField() {
    return sparseField("default");
  }

  public static Map<String, Object> mvField(
      int dim, String spaceType, String precision, String poolingMethod) {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("dimension", dim);
    params.put("space_type", spaceType);
    params.put("precision", precision);
    params.put("pooling", poolingMethod);
    Map<String, Object> field = new LinkedHashMap<>();
    field.put("name", MV_FIELD);
    field.put("type", "multi_vector");
    field.put("params", params);
    return field;
  }

  public static Map<String, Object> mvField() {
    return mvField(VectorGenerators.DIM, "cosine", "int8", "mean");
  }
}
