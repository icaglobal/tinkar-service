export type Descriptions = {
  fullyQualifiedName: string;
  regularName: string;
  definition: string;
};

export type Stamp = {
  statusPublicId: string | null;
  authorPublicId: string | null;
  modulePublicId: string | null;
  pathPublicId: string | null;
  time: number | null;
};

export type SearchResult = {
  publicId: string[];
  descriptions: Descriptions;
  stamp: Stamp;
};

export type ConceptSearchResponse = {
  query: string;
  totalCount: number;
  results: SearchResult[];
  success: boolean;
  errorMessage: string | null;
};

export type DescendantsResponse = {
  totalCount: number;
  results: SearchResult[];
  success: boolean;
  errorMessage: string | null;
};

export type DescendantOperationResponse = {
  parentConceptId: string;
  descendantConceptId: string;
  descendantDescription: string | null;
  operation: string | null;
  success: boolean;
  errorMessage: string | null;
};

// Sort options for search
export type SearchSortOption =
  | 'TOP_COMPONENT'
  | 'TOP_COMPONENT_ALPHA'
  | 'SEMANTIC'
  | 'SEMANTIC_ALPHA';

// Individual semantic search result with score
export type SemanticSearchResult = {
  publicId: string[];
  fullyQualifiedName: string;
  regularName: string | null;
  highlightedText: string | null;
  score: number;
  active: boolean;
};

// Matching semantic within a grouped result
export type MatchingSemantic = {
  highlightedText: string | null;
  plainText: string;
  score: number;
};

// Grouped search result by top-level component
export type GroupedSearchResult = {
  publicId: string[];
  fullyQualifiedName: string;
  active: boolean;
  topScore: number;
  matchingSemantics: MatchingSemantic[];
};

// Response for conceptSearchWithSort endpoint
export type ConceptSearchWithSortResponse = {
  query: string;
  totalCount: number;
  sortBy: SearchSortOption;
  results: SemanticSearchResult[] | null;
  groupedResults: GroupedSearchResult[] | null;
  success: boolean;
  errorMessage: string | null;
};
