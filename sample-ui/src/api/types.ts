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

export type ReasonerPhaseEvent = {
  step: number;
  totalSteps: number;
  message: string;
};

export type ReasonerResultsResponse = {
  classifiedConceptCount: number | null;
  inferredChangesCount: number | null;
  navigationChangesCount: number | null;
  equivalentSetsCount: number | null;
  cyclesCount: number | null;
  orphansCount: number | null;
  durationMs: number | null;
  success: boolean;
  errorMessage: string | null;
  createdAt: number;
};

export type DescriptionType = 'FULLY_QUALIFIED_NAME' | 'REGULAR_NAME' | 'DEFINITION';

export type CaseSignificance =
  | 'CASE_SENSITIVE'
  | 'NOT_CASE_SENSITIVE'
  | 'INITIAL_CHARACTER_CASE_SENSITIVE';

export type DescriptionLanguage =
  | 'ENGLISH'
  | 'SPANISH'
  | 'FRENCH'
  | 'GERMAN'
  | 'DUTCH'
  | 'ITALIAN'
  | 'DANISH'
  | 'CZECH'
  | 'IRISH'
  | 'CHINESE';

export type AxiomSetType = 'NECESSARY' | 'SUFFICIENT';

export type ConceptDescription = {
  text: string;
  type: DescriptionType;
  caseSignificance: CaseSignificance;
  language: DescriptionLanguage;
};

export type ConceptAxiom = {
  setType: AxiomSetType;
  parentConceptIds: string[];
};

export type CreateConceptRequest = {
  descriptions: ConceptDescription[];
  axioms: ConceptAxiom[];
};

export type ConceptCreationResponse = {
  conceptId: string | null;
  fullyQualifiedName: string;
  parentConceptIds: string[] | null;
  success: boolean;
  errorMessage: string | null;
  createdAt: number;
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

// Field value in a semantic
export type SemanticFieldValue = {
  /** Position in the pattern's field list; the pattern gives it its meaning. */
  index: number;
  value: string;
};

// Stamp info for a semantic
export type SemanticStampInfo = {
  status: string;
  author: string;
  module: string;
  path: string;
  /** Epoch milliseconds. */
  time: number;
  /** ISO-8601, pre-rendered by the service. */
  formattedTime: string;
};

// Individual semantic info
export type SemanticInfo = {
  semanticId: string;
  patternName: string;
  fields: SemanticFieldValue[];
  stamp: SemanticStampInfo;
};

// Response for the semantics endpoint
export type ConceptSemanticsResponse = {
  conceptId: string;
  conceptDescription: string;
  totalCount?: number;
  semantics: SemanticInfo[];
  /** STAMP of the concept's own latest version, as opposed to any semantic's. */
  conceptStamp?: SemanticStampInfo;
  success: boolean;
  errorMessage: string | null;
};

// Field-level change within a version
export type FieldChange = {
  fieldName: string;
  fieldIndex: number;
  priorValue: string | null;
  currentValue: string;
  changeType: 'ADDED' | 'MODIFIED' | 'REMOVED';
};

// STAMP info attached to a historical version
export type StampHistoryInfo = {
  status: string;
  author: string;
  module: string;
  path: string;
  time: number;
  formattedTime: string;
};

// One version snapshot with its STAMP and field deltas
export type VersionChange = {
  stamp: StampHistoryInfo;
  fieldChanges: FieldChange[];
};

// Change history for a single semantic across its versions
export type SemanticChangeHistory = {
  semanticId: string;
  patternName: string;
  summary: string;
  versionChanges: VersionChange[];
};

// Response for /change-history endpoint
export type ChangeHistoryResponse = {
  entityId: string;
  entityDescription: string | null;
  totalVersions: number;
  versionChanges: VersionChange[];
  success: boolean;
  errorMessage: string | null;
};

// Response for /concept-change-history endpoint
export type ConceptChangeHistoryResponse = {
  conceptId: string;
  conceptDescription: string | null;
  conceptChanges: VersionChange[];
  semanticChanges: SemanticChangeHistory[];
  totalChanges: number;
  success: boolean;
  errorMessage?: string | null;
};

// Optional coordinate overrides for Tier 2 (Knowledge Graph) queries
export type CoordinateOverrideParams = {
  allowedStates?: 'ACTIVE' | 'INACTIVE' | 'ACTIVE_AND_INACTIVE';
  positionTime?: number;
  positionPath?: string;
  modules?: string[];
  excludedModules?: string[];
  modulePriority?: string[];
  premiseType?: 'STATED' | 'INFERRED';
};

// ── Saved Coordinate Types ───────────────────────────────────────────

export type StampCoordinateSettings = {
  allowedStates?: string;
  positionTime?: number;
  positionPathId?: string;
  moduleIds?: string[];
  excludedModuleIds?: string[];
  modulePriorityIds?: string[];
};

export type NavigationCoordinateSettings = {
  premiseType?: string;
};

export type SavedStampCoordinateResponse = {
  id: string;
  settings: StampCoordinateSettings;
  createdAt: string;
};

export type SavedNavigationCoordinateResponse = {
  id: string;
  settings: NavigationCoordinateSettings;
  createdAt: string;
};

export type LanguageCoordinateSettings = {
  languagePreset?: string;
};

export type SavedLanguageCoordinateResponse = {
  id: string;
  settings: LanguageCoordinateSettings;
  createdAt: string;
};

// ── Test Runner Types ────────────────────────────────────────────────

export type TestStatus = 'pending' | 'running' | 'pass' | 'fail' | 'skip';

export type TestResult = {
  id: string;
  name: string;
  status: TestStatus;
  detail?: string;
  responseData?: unknown;
  durationMs?: number;
};

export type TestGroup = {
  id: string;
  name: string;
  description?: string;
  tests: TestResult[];
};

export type TestRunSummary = {
  pass: number;
  fail: number;
  skip: number;
  total: number;
  isRunning: boolean;
};
