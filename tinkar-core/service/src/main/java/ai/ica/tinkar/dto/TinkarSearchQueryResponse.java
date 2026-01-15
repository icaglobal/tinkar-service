package ai.ica.tinkar.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Response object for Tinkar search queries.
 * Designed to be compatible with both REST and gRPC implementations.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Response containing search query results")
public record TinkarSearchQueryResponse(
                @Schema(description = "The original search query", example = "diabetes") String query,

                @Schema(description = "Total number of results found", example = "42") Long totalCount,

                @Schema(description = "List of search results") List<SearchResult> results,

                @Schema(description = "Whether the search was successful", example = "true") Boolean success,

                @Schema(description = "Error message if search failed", example = "null") String errorMessage) {

        /**
         * Individual search result item.
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(description = "A single search result")
        public record SearchResult(
                        @Schema(description = "Concept ID") String conceptId,

                        @Schema(description = "Name of the concept") String name,

                        @Schema(description = "Description of the concept") String description,

                        @Schema(description = "Fully qualified name of the concept") String fullyQualifiedName,

                        @Schema(description = "Regular description text") String regularName,

                        @Schema(description = "Status of the concept (e.g., ACTIVE, INACTIVE)") String status,

                        @Schema(description = "Last modified timestamp in epoch milliseconds") Long lastModifiedTime) {
        }

        /**
         * Factory method to create a successful response.
         */
        public static TinkarSearchQueryResponse success(String query, List<SearchResult> results) {
                return new TinkarSearchQueryResponse(
                                query,
                                results != null ? (long) results.size() : 0L,
                                results,
                                true,
                                null);
        }

        /**
         * Factory method to create an error response.
         */
        public static TinkarSearchQueryResponse error(String query, String errorMessage) {
                return new TinkarSearchQueryResponse(
                                query,
                                0L,
                                null,
                                false,
                                errorMessage);
        }
}
