package com.northstar.decision.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkflowEdgesTest {
  @Test
  void applicationStatusesAreReachableAndFinalStatesHaveNoEscape() {
    Set<String> required =
        Set.of(
            "draft",
            "submitted",
            "in_review",
            "needs_documents",
            "counteroffered",
            "approved",
            "rejected",
            "withdrawn");
    assertEquals(required, WorkflowService.APPLICATION_EDGES.keySet());
    assertTrue(WorkflowService.APPLICATION_EDGES.get("draft").contains("submitted"));
    assertTrue(
        WorkflowService.APPLICATION_EDGES
            .get("in_review")
            .containsAll(Set.of("approved", "rejected", "counteroffered")));
    assertTrue(WorkflowService.APPLICATION_EDGES.get("approved").isEmpty());
    assertFalse(WorkflowService.APPLICATION_EDGES.get("submitted").contains("approved"));
  }

  @Test
  void documentReplacementMustPassThroughUploaded() {
    assertEquals(Set.of("uploaded"), WorkflowService.DOCUMENT_EDGES.get("needs_resubmission"));
    assertFalse(WorkflowService.DOCUMENT_EDGES.get("requested").contains("accepted"));
    assertTrue(
        WorkflowService.DOCUMENT_EDGES
            .get("uploaded")
            .containsAll(Set.of("accepted", "rejected", "needs_resubmission")));
  }
}
