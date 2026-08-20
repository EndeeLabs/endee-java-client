package io.endee.client.support;

import io.endee.client.types.SparseData;
import java.util.LinkedHashSet;
import java.util.Random;
import java.util.Set;

// Deterministic vector generators, seeded by object index so tests can recompute the same vector later.
public final class VectorGenerators {

  private VectorGenerators() {}

  public static final int DIM = 16;
  public static final int HYBRID_DIM = 16;
  public static final int SPARSE_DIM = 500;
  public static final int SPARSE_NNZ = 8;
  public static final int N_VECTORS = 50;
  public static final int MV_TOKENS = 4;

  public static double[] denseVec(int dim, long seed) {
    Random r = new Random(seed);
    double[] v = new double[dim];
    for (int i = 0; i < dim; i++) {
      v[i] = r.nextDouble();
    }
    return v;
  }

  public static double[] denseVec(long seed) {
    return denseVec(DIM, seed);
  }

  public static double[] binaryVec(int dim, long seed) {
    Random r = new Random(seed);
    double[] v = new double[dim];
    for (int i = 0; i < dim; i++) {
      v[i] = r.nextInt(2);
    }
    return v;
  }

  public static SparseData sparseVec(int sparseDim, int nnz, long seed) {
    Random indexRandom = new Random(seed);
    Set<Integer> chosen = new LinkedHashSet<>();
    while (chosen.size() < nnz) {
      chosen.add(indexRandom.nextInt(sparseDim));
    }
    int[] indices = chosen.stream().mapToInt(Integer::intValue).sorted().toArray();

    // Different seed derivation so values don't correlate with the chosen indices.
    Random valueRandom = new Random(seed * 1_000_003L + 7);
    double[] values = new double[indices.length];
    for (int i = 0; i < values.length; i++) {
      values[i] = valueRandom.nextDouble();
    }
    return new SparseData(indices, values);
  }

  public static SparseData sparseVec(long seed) {
    return sparseVec(SPARSE_DIM, SPARSE_NNZ, seed);
  }

  public static double[][] multiVec(int nTokens, int dim, long seed) {
    double[][] vecs = new double[nTokens][];
    for (int t = 0; t < nTokens; t++) {
      // Unique seed per token/object combo so no two multi-vector rows collide.
      vecs[t] = denseVec(dim, seed * nTokens + t);
    }
    return vecs;
  }

  public static double[][] multiVec(long seed) {
    return multiVec(MV_TOKENS, DIM, seed);
  }
}
