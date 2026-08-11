package io.endee.client.util;

import io.endee.client.exception.EndeeException;
import java.io.IOException;
import java.util.*;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.Value;

/**
 * MessagePack serialization utilities for the v2 wire format.
 *
 * <p>Upsert wire: {@code ObjectBatch = [objects]} where each object is {@code [id, meta, filter,
 * vectors_map, sparses_map, multi_vectors_map]}.
 *
 * <p>Search response: {@code [objects_map, results_map]} where objects_map is {@code {int_id:
 * [str_id, meta_bytes, filter_str]}} and results_map is {@code {field_name: [[int_id, score],
 * ...]}}.
 *
 * <p>Get objects response: {@code ObjectBatch = [objects]} where each object is {@code [id, meta,
 * filter, vectors_map, sparses_map, multi_vectors_map]}.
 */
public final class MessagePackUtils {

  private MessagePackUtils() {}

  // ── Upsert packing ──────────────────────────────────────────────────────────

  /**
   * Packs objects for upsert. Each tuple: [id, meta_bytes, filter_str, vectors_map, sparses_map,
   * multi_vectors_map].
   *
   * @param objects list of 6-element arrays
   */
  @SuppressWarnings("unchecked")
  public static byte[] packObjects(List<Object[]> objects) {
    try (MessageBufferPacker packer = MessagePack.newDefaultBufferPacker()) {
      // ObjectBatch = [objects] — single-element outer array
      packer.packArrayHeader(1);
      packer.packArrayHeader(objects.size());

      for (Object[] obj : objects) {
        packer.packArrayHeader(6);

        // [0] id (string)
        packer.packString((String) obj[0]);

        // [1] meta (bytes)
        byte[] meta = (byte[]) obj[1];
        packer.packBinaryHeader(meta.length);
        packer.writePayload(meta);

        // [2] filter (string)
        packer.packString((String) obj[2]);

        // [3] vectors map {field_name: [float, ...]}
        Map<String, double[]> vectors = (Map<String, double[]>) obj[3];
        packDenseVectorsMap(packer, vectors);

        // [4] sparses map {field_name: [indices, values]}
        Map<String, Object[]> sparses = (Map<String, Object[]>) obj[4];
        packSparsesMap(packer, sparses);

        // [5] multi_vectors map {field_name: [[float, ...], ...]}
        Map<String, double[][]> multiVectors = (Map<String, double[][]>) obj[5];
        packMultiVectorsMap(packer, multiVectors);
      }

      return packer.toByteArray();
    } catch (IOException e) {
      throw new EndeeException("Failed to pack objects", e);
    }
  }

  private static void packDenseVectorsMap(MessageBufferPacker packer, Map<String, double[]> vectors)
      throws IOException {
    if (vectors == null || vectors.isEmpty()) {
      packer.packMapHeader(0);
      return;
    }
    packer.packMapHeader(vectors.size());
    for (Map.Entry<String, double[]> entry : vectors.entrySet()) {
      packer.packString(entry.getKey());
      double[] vec = entry.getValue();
      packer.packArrayHeader(vec.length);
      for (double v : vec) {
        packer.packFloat((float) v);
      }
    }
  }

  private static void packSparsesMap(MessageBufferPacker packer, Map<String, Object[]> sparses)
      throws IOException {
    if (sparses == null || sparses.isEmpty()) {
      packer.packMapHeader(0);
      return;
    }
    packer.packMapHeader(sparses.size());
    for (Map.Entry<String, Object[]> entry : sparses.entrySet()) {
      packer.packString(entry.getKey());
      // Sparse = [indices, values]
      int[] indices = (int[]) entry.getValue()[0];
      double[] values = (double[]) entry.getValue()[1];
      packer.packArrayHeader(2);
      packer.packArrayHeader(indices.length);
      for (int idx : indices) {
        packer.packInt(idx);
      }
      packer.packArrayHeader(values.length);
      for (double val : values) {
        packer.packFloat((float) val);
      }
    }
  }

  private static void packMultiVectorsMap(
      MessageBufferPacker packer, Map<String, double[][]> multiVectors) throws IOException {
    if (multiVectors == null || multiVectors.isEmpty()) {
      packer.packMapHeader(0);
      return;
    }
    packer.packMapHeader(multiVectors.size());
    for (Map.Entry<String, double[][]> entry : multiVectors.entrySet()) {
      packer.packString(entry.getKey());
      double[][] vecs = entry.getValue();
      packer.packArrayHeader(vecs.length);
      for (double[] vec : vecs) {
        packer.packArrayHeader(vec.length);
        for (double v : vec) {
          packer.packFloat((float) v);
        }
      }
    }
  }

  // ── Search response unpacking ────────────────────────────────────────────────

  /**
   * Unpacks a search response: [objects_map, results_map].
   *
   * @return [objectsMap, resultsMap] where objectsMap = Map&lt;Integer, Object[]&gt; (int_id →
   *     [str_id, meta_bytes, filter_str]) and resultsMap = Map&lt;String, List&lt;Object[]&gt;&gt;
   *     (field_name → [[int_id, score], ...])
   */
  public static Object[] unpackSearchResponse(byte[] data) {
    try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(data)) {
      int outerSize = unpacker.unpackArrayHeader();

      // [0] objects_map: {int_id: [str_id, meta_bytes, filter_str]}
      int objectsMapSize = unpacker.unpackMapHeader();
      Map<Integer, Object[]> objectsMap = new LinkedHashMap<>();
      for (int i = 0; i < objectsMapSize; i++) {
        int intId = unpacker.unpackInt();
        int arrSize = unpacker.unpackArrayHeader();
        String strId = unpacker.unpackString();
        int metaLen = unpacker.unpackBinaryHeader();
        byte[] metaBytes = unpacker.readPayload(metaLen);
        String filterStr = arrSize > 2 ? unpacker.unpackString() : "";
        // skip any extra fields
        for (int j = 3; j < arrSize; j++) {
          unpacker.skipValue();
        }
        objectsMap.put(intId, new Object[] {strId, metaBytes, filterStr});
      }

      // [1] results_map: {field_name: [[int_id, score], ...]}
      Map<String, List<Object[]>> resultsMap = new LinkedHashMap<>();
      if (outerSize > 1) {
        int resultsMapSize = unpacker.unpackMapHeader();
        for (int i = 0; i < resultsMapSize; i++) {
          String fieldName = unpacker.unpackString();
          int hitsSize = unpacker.unpackArrayHeader();
          List<Object[]> hits = new ArrayList<>();
          for (int j = 0; j < hitsSize; j++) {
            unpacker.unpackArrayHeader(); // 2
            int intId = unpacker.unpackInt();
            double score = unpackNumberAsDouble(unpacker);
            hits.add(new Object[] {intId, score});
          }
          resultsMap.put(fieldName, hits);
        }
      }

      return new Object[] {objectsMap, resultsMap};
    } catch (IOException e) {
      throw new EndeeException("Failed to unpack search response", e);
    }
  }

  // ── Get objects response unpacking ───────────────────────────────────────────

  /**
   * Unpacks an ObjectBatch response: [[objects]] where each object is [id, meta, filter, vectors,
   * sparses, multi_vectors].
   *
   * @return list of 6-element arrays: [id, meta_bytes, filter_str, vectors_map, sparses_map,
   *     multi_vectors_map]
   */
  public static List<Object[]> unpackObjectBatch(byte[] data) {
    try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(data)) {
      // ObjectBatch = [objects]
      int batchSize = unpacker.unpackArrayHeader();
      if (batchSize == 0) {
        return List.of();
      }
      int objectsSize = unpacker.unpackArrayHeader();
      List<Object[]> results = new ArrayList<>();

      for (int i = 0; i < objectsSize; i++) {
        int tupleSize = unpacker.unpackArrayHeader();
        Object[] tuple = new Object[6];

        // [0] id
        tuple[0] = unpacker.unpackString();

        // [1] meta bytes
        if (tupleSize > 1) {
          int metaLen = unpacker.unpackBinaryHeader();
          tuple[1] = unpacker.readPayload(metaLen);
        } else {
          tuple[1] = new byte[0];
        }

        // [2] filter string
        tuple[2] = tupleSize > 2 ? unpacker.unpackString() : "";

        // [3] vectors map {field_name: [float, ...]}
        tuple[3] = tupleSize > 3 ? unpackDenseVectorsMap(unpacker) : Map.of();

        // [4] sparses map {field_name: [indices, values]}
        tuple[4] = tupleSize > 4 ? unpackSparsesMap(unpacker) : Map.of();

        // [5] multi_vectors map {field_name: [[float, ...], ...]}
        tuple[5] = tupleSize > 5 ? unpackMultiVectorsMap(unpacker) : Map.of();

        results.add(tuple);
      }

      return results;
    } catch (IOException e) {
      throw new EndeeException("Failed to unpack object batch", e);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, double[]> unpackDenseVectorsMap(MessageUnpacker unpacker)
      throws IOException {
    int mapSize = unpacker.unpackMapHeader();
    Map<String, double[]> vectors = new LinkedHashMap<>();
    for (int i = 0; i < mapSize; i++) {
      String name = unpacker.unpackString();
      int vecLen = unpacker.unpackArrayHeader();
      double[] vec = new double[vecLen];
      for (int j = 0; j < vecLen; j++) {
        vec[j] = unpackNumberAsDouble(unpacker);
      }
      vectors.put(name, vec);
    }
    return vectors;
  }

  private static Map<String, Object[]> unpackSparsesMap(MessageUnpacker unpacker)
      throws IOException {
    int mapSize = unpacker.unpackMapHeader();
    Map<String, Object[]> sparses = new LinkedHashMap<>();
    for (int i = 0; i < mapSize; i++) {
      String name = unpacker.unpackString();
      unpacker.unpackArrayHeader(); // 2
      int indicesLen = unpacker.unpackArrayHeader();
      int[] indices = new int[indicesLen];
      for (int j = 0; j < indicesLen; j++) {
        indices[j] = unpacker.unpackInt();
      }
      int valuesLen = unpacker.unpackArrayHeader();
      double[] values = new double[valuesLen];
      for (int j = 0; j < valuesLen; j++) {
        values[j] = unpackNumberAsDouble(unpacker);
      }
      sparses.put(name, new Object[] {indices, values});
    }
    return sparses;
  }

  private static Map<String, double[][]> unpackMultiVectorsMap(MessageUnpacker unpacker)
      throws IOException {
    int mapSize = unpacker.unpackMapHeader();
    Map<String, double[][]> multiVectors = new LinkedHashMap<>();
    for (int i = 0; i < mapSize; i++) {
      String name = unpacker.unpackString();
      int numVecs = unpacker.unpackArrayHeader();
      double[][] vecs = new double[numVecs][];
      for (int j = 0; j < numVecs; j++) {
        int vecLen = unpacker.unpackArrayHeader();
        vecs[j] = new double[vecLen];
        for (int k = 0; k < vecLen; k++) {
          vecs[j][k] = unpackNumberAsDouble(unpacker);
        }
      }
      multiVectors.put(name, vecs);
    }
    return multiVectors;
  }

  private static double unpackNumberAsDouble(MessageUnpacker unpacker) throws IOException {
    Value value = unpacker.unpackValue();
    if (value.isFloatValue()) {
      return value.asFloatValue().toDouble();
    }
    if (value.isIntegerValue()) {
      return value.asIntegerValue().toDouble();
    }
    throw new IllegalStateException(
        "Expected numeric value (int/float), got " + value.getValueType());
  }
}
