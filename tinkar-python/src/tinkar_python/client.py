"""High-level Python client for Tinkar gRPC service."""

from __future__ import annotations

from enum import Enum

import grpc
from pydantic import BaseModel

from ._generated import tinkar_search_pb2 as pb2
from ._generated import tinkar_search_pb2_grpc as pb2_grpc
from ._generated import Tinkar_pb2 as schema_pb2


# ============================================================================
# Models
# ============================================================================

class SearchResult(BaseModel):
    """A single search result."""
    public_id: list[str]
    fully_qualified_name: str
    regular_name: str
    definition: str


class SearchResponse(BaseModel):
    """Response from a search operation."""
    query: str
    total_count: int
    results: list[SearchResult]
    success: bool
    error_message: str | None = None


class SortOption(str, Enum):
    """Sort options for concept search."""
    TOP_COMPONENT = "TOP_COMPONENT"
    TOP_COMPONENT_ALPHA = "TOP_COMPONENT_ALPHA"
    SEMANTIC = "SEMANTIC"
    SEMANTIC_ALPHA = "SEMANTIC_ALPHA"


class MatchingSemantic(BaseModel):
    """A matching semantic within a grouped result."""
    highlighted_text: str
    plain_text: str
    score: float


class GroupedSearchResult(BaseModel):
    """A grouped search result (TOP_COMPONENT modes)."""
    public_id: list[str]
    fully_qualified_name: str
    is_active: bool
    top_score: float
    matching_semantics: list[MatchingSemantic]


class SemanticSearchResult(BaseModel):
    """A flat semantic search result (SEMANTIC modes)."""
    public_id: list[str]
    fully_qualified_name: str
    regular_name: str
    highlighted_text: str
    score: float
    is_active: bool


class SortedSearchResponse(BaseModel):
    """Response from a sorted search operation."""
    query: str
    total_count: int
    sort_by: SortOption
    results: list[SemanticSearchResult]  # For SEMANTIC modes
    grouped_results: list[GroupedSearchResult]  # For TOP_COMPONENT modes
    success: bool
    error_message: str | None = None


class RebuildIndexResponse(BaseModel):
    """Response from rebuild index operation."""
    message: str
    success: bool


# ============================================================================
# Client
# ============================================================================

class TinkarClient:
    """Client for interacting with Tinkar gRPC service.

    Example:
        >>> with TinkarClient() as client:
        ...     response = client.search("diabetes")
        ...     for result in response.results:
        ...         print(result.fully_qualified_name)
    """

    def __init__(self, host: str = "localhost", port: int = 9095):
        self._address = f"{host}:{port}"
        self._channel: grpc.Channel | None = None
        self._stub: pb2_grpc.TinkarSearchServiceStub | None = None

    def connect(self) -> None:
        """Establish connection to the gRPC server."""
        self._channel = grpc.insecure_channel(self._address)
        self._stub = pb2_grpc.TinkarSearchServiceStub(self._channel)

    def close(self) -> None:
        """Close the connection."""
        if self._channel:
            self._channel.close()
            self._channel = None
            self._stub = None

    def __enter__(self) -> TinkarClient:
        self.connect()
        return self

    def __exit__(self, *args) -> None:
        self.close()

    def _ensure_connected(self) -> None:
        if self._stub is None:
            self.connect()

    # ========================================================================
    # Search Methods
    # ========================================================================

    def search(self, query: str) -> SearchResponse:
        """Basic search for concepts."""
        self._ensure_connected()
        request = pb2.TinkarSearchQueryRequest(query=query)
        response = self._stub.Search(request)
        return self._convert_search_response(response)

    def concept_search(self, query: str, max_results: int = 100) -> SearchResponse:
        """Search for concepts with result limit."""
        self._ensure_connected()
        request = pb2.TinkarConceptSearchRequest(query=query, max_results=max_results)
        response = self._stub.ConceptSearch(request)
        return self._convert_search_response(response)

    def concept_search_sorted(
        self,
        query: str,
        sort_by: SortOption = SortOption.TOP_COMPONENT,
        max_results: int = 100,
    ) -> SortedSearchResponse:
        """Search for concepts with configurable sorting."""
        self._ensure_connected()

        grpc_sort = {
            SortOption.TOP_COMPONENT: pb2.SearchSortOption.TOP_COMPONENT,
            SortOption.TOP_COMPONENT_ALPHA: pb2.SearchSortOption.TOP_COMPONENT_ALPHA,
            SortOption.SEMANTIC: pb2.SearchSortOption.SEMANTIC,
            SortOption.SEMANTIC_ALPHA: pb2.SearchSortOption.SEMANTIC_ALPHA,
        }[sort_by]

        request = pb2.TinkarConceptSearchWithSortRequest(
            query=query,
            max_results=max_results,
            sort_by=grpc_sort,
        )
        response = self._stub.ConceptSearchWithSort(request)
        return self._convert_sorted_response(response, sort_by)

    # ========================================================================
    # Entity Methods
    # ========================================================================

    def get_entity(self, concept_id: str) -> SearchResponse:
        """Get a specific entity by its UUID."""
        self._ensure_connected()
        request = self._make_concept_id_request(concept_id)
        response = self._stub.GetEntity(request)
        return self._convert_search_response(response)

    def get_child_concepts(self, concept_id: str) -> SearchResponse:
        """Get direct children of a concept."""
        self._ensure_connected()
        request = self._make_concept_id_request(concept_id)
        response = self._stub.GetChildConcepts(request)
        return self._convert_search_response(response)

    def get_descendant_concepts(self, concept_id: str) -> SearchResponse:
        """Get all descendants of a concept."""
        self._ensure_connected()
        request = self._make_concept_id_request(concept_id)
        response = self._stub.GetDescendantConcepts(request)
        return self._convert_search_response(response)

    # ========================================================================
    # LIDR Methods (Lab/Diagnostics)
    # ========================================================================

    def get_lidr_record_concepts(self, test_kit_id: str) -> SearchResponse:
        """Get LIDR record concepts from a test kit."""
        self._ensure_connected()
        request = self._make_concept_id_request(test_kit_id)
        response = self._stub.GetLIDRRecordConceptsFromTestKit(request)
        return self._convert_search_response(response)

    def get_result_conformance_concepts(self, lidr_record_id: str) -> SearchResponse:
        """Get result conformance concepts from a LIDR record."""
        self._ensure_connected()
        request = self._make_concept_id_request(lidr_record_id)
        response = self._stub.GetResultConformanceConceptsFromLIDRRecord(request)
        return self._convert_search_response(response)

    def get_allowed_result_concepts(self, conformance_id: str) -> SearchResponse:
        """Get allowed result concepts from a result conformance."""
        self._ensure_connected()
        request = self._make_concept_id_request(conformance_id)
        response = self._stub.GetAllowedResultConceptsFromResultConformance(request)
        return self._convert_search_response(response)

    # ========================================================================
    # Admin Methods
    # ========================================================================

    def rebuild_search_index(self) -> RebuildIndexResponse:
        """Trigger a rebuild of the search index."""
        self._ensure_connected()
        request = pb2.TinkarRebuildIndexRequest()
        response = self._stub.RebuildSearchIndex(request)
        return RebuildIndexResponse(
            message=response.message,
            success=response.success,
        )

    # ========================================================================
    # Private Helpers
    # ========================================================================

    def _make_concept_id_request(self, concept_id: str) -> pb2.TinkarConceptIdRequest:
        """Create a concept ID request from a UUID string."""
        public_id = schema_pb2.PublicId(uuids=[concept_id])
        return pb2.TinkarConceptIdRequest(public_id=public_id)

    def _convert_search_response(self, response) -> SearchResponse:
        """Convert gRPC response to Pydantic model."""
        results = [
            SearchResult(
                public_id=list(r.public_id.uuids),
                fully_qualified_name=r.descriptions.fully_qualified_name,
                regular_name=r.descriptions.regular_name,
                definition=r.descriptions.definition,
            )
            for r in response.results
        ]
        return SearchResponse(
            query=response.query,
            total_count=response.total_count,
            results=results,
            success=response.success,
            error_message=response.error_message or None,
        )

    def _convert_sorted_response(self, response, sort_by: SortOption) -> SortedSearchResponse:
        """Convert sorted search gRPC response to Pydantic model."""
        results = [
            SemanticSearchResult(
                public_id=list(r.public_id),
                fully_qualified_name=r.fully_qualified_name,
                regular_name=r.regular_name,
                highlighted_text=r.highlighted_text,
                score=r.score,
                is_active=r.active,
            )
            for r in response.results
        ]

        grouped_results = [
            GroupedSearchResult(
                public_id=list(r.public_id),
                fully_qualified_name=r.fully_qualified_name,
                is_active=r.active,
                top_score=r.top_score,
                matching_semantics=[
                    MatchingSemantic(
                        highlighted_text=m.highlighted_text,
                        plain_text=m.plain_text,
                        score=m.score,
                    )
                    for m in r.matching_semantics
                ],
            )
            for r in response.grouped_results
        ]

        return SortedSearchResponse(
            query=response.query,
            total_count=response.total_count,
            sort_by=sort_by,
            results=results,
            grouped_results=grouped_results,
            success=response.success,
            error_message=response.error_message or None,
        )
