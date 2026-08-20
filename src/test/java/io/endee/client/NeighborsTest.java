package io.endee.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.endee.client.exception.EndeeApiException;
import io.endee.client.exception.NotFoundException;
import io.endee.client.support.CollectionFixtures;
import io.endee.client.support.CollectionFixtures.NamedCollection;
import io.endee.client.support.FieldConfigs;
import io.endee.client.support.TestConfig;
import io.endee.client.types.ObjectInfo;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// Covers Collection.getNeighborsById(): HNSW link inspection and error handling.
class NeighborsTest {

  private final Endee client = TestConfig.client();
  private NamedCollection nc;

  @BeforeEach
  void setUp() {
    nc = CollectionFixtures.populatedDense();
  }

  @AfterEach
  void tearDown() {
    CollectionFixtures.safeDelete(client, nc.name());
  }

  // -- return structure -----------------------------------------------------------

  @Test
  void neighborsReturnsExpectedKeys() {
    Map<String, Object> result =
        nc.collection().getNeighborsById("vec_0000", FieldConfigs.DENSE_FIELD);
    assertEquals("vec_0000", result.get("id"));
    assertEquals(FieldConfigs.DENSE_FIELD, result.get("field"));
    assertInstanceOf(List.class, result.get("links"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void neighborsLinksAreStringIds() {
    Map<String, Object> result =
        nc.collection().getNeighborsById("vec_0010", FieldConfigs.DENSE_FIELD);
    List<Object> links = (List<Object>) result.get("links");
    assertTrue(!links.isEmpty(), "a populated collection's node must have level 0 links");
    for (Object link : links) {
      assertInstanceOf(String.class, link);
    }
    assertFalse(links.contains("vec_0010"), "a node must not link to itself");
  }

  @Test
  @SuppressWarnings("unchecked")
  void neighborsLinksReferenceExistingObjects() {
    Map<String, Object> result =
        nc.collection().getNeighborsById("vec_0010", FieldConfigs.DENSE_FIELD);
    List<String> links = (List<String>) result.get("links");
    List<ObjectInfo> fetched = nc.collection().getObjects(links);
    assertEquals(links.size(), fetched.size());
  }

  @Test
  @SuppressWarnings("unchecked")
  void neighborsAreSymmetricForAtLeastOneLink() {
    Map<String, Object> result = nc.collection().getNeighborsById("vec_0010", FieldConfigs.DENSE_FIELD);
    List<String> links = (List<String>) result.get("links");
    boolean anyBackLink = false;
    for (String link : links) {
      Map<String, Object> backResult = nc.collection().getNeighborsById(link, FieldConfigs.DENSE_FIELD);
      List<String> backLinks = (List<String>) backResult.get("links");
      if (backLinks.contains("vec_0010")) {
        anyBackLink = true;
        break;
      }
    }
    assertTrue(anyBackLink, "HNSW links are bidirectional - some neighbour must link back");
  }

  @Test
  void neighborsOnMultiVectorField() {
    NamedCollection mvNc = CollectionFixtures.populatedMv();
    try {
      Map<String, Object> result =
          mvNc.collection().getNeighborsById("mv_0000", FieldConfigs.MV_FIELD);
      assertEquals(FieldConfigs.MV_FIELD, result.get("field"));
      assertInstanceOf(List.class, result.get("links"));
    } finally {
      CollectionFixtures.safeDelete(client, mvNc.name());
    }
  }

  // -- error handling ---------------------------------------------------------------

  @Test
  void neighborsUnknownIdRaises() {
    assertThrows(
        NotFoundException.class,
        () -> nc.collection().getNeighborsById("does_not_exist", FieldConfigs.DENSE_FIELD));
  }

  @Test
  void neighborsUnknownFieldRaises() {
    assertThrows(
        EndeeApiException.class,
        () -> nc.collection().getNeighborsById("vec_0000", "no_such_field"));
  }

  @Test
  void neighborsSparseFieldRaises() {
    NamedCollection spNc = CollectionFixtures.populatedSparse();
    try {
      assertThrows(
          EndeeApiException.class,
          () -> spNc.collection().getNeighborsById("sp_0000", FieldConfigs.SPARSE_FIELD));
    } finally {
      CollectionFixtures.safeDelete(client, spNc.name());
    }
  }

  @Test
  void neighborsEmptyIdRaises() {
    assertThrows(
        IllegalArgumentException.class,
        () -> nc.collection().getNeighborsById("", FieldConfigs.DENSE_FIELD));
  }

  @Test
  void neighborsEmptyFieldRaises() {
    assertThrows(
        IllegalArgumentException.class, () -> nc.collection().getNeighborsById("vec_0000", ""));
  }
}
