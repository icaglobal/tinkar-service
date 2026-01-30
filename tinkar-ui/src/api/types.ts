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
