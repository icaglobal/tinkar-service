import type {
  ChangeHistoryResponse,
  ConceptChangeHistoryResponse,
  ConceptSearchResponse,
  ConceptSearchWithSortResponse,
  ConceptSemanticsResponse,
  CoordinateOverrideParams,
  DescendantsResponse,
  ConceptCreationResponse,
  CreateConceptRequest,
  ReasonerPhaseEvent,
  ReasonerResultsResponse,
  DescendantOperationResponse,
  LanguageCoordinateSettings,
  NavigationCoordinateSettings,
  SavedLanguageCoordinateResponse,
  SavedNavigationCoordinateResponse,
  SavedStampCoordinateResponse,
  StampCoordinateSettings,
  SearchSortOption,
} from './types';

const GR_API_BASE_URL = 'http://localhost:8085/api/ike/graphrag';
const KG_API_BASE_URL = 'http://localhost:8085/api/ike/knowledgegraph';
const ADMIN_API_BASE_URL = 'http://localhost:8085/api/ike/admin';

export async function search(query: string): Promise<ConceptSearchResponse> {
  const params = new URLSearchParams({ query });

  const response = await fetch(`${GR_API_BASE_URL}/search?${params}`, {
    method: 'GET',
    headers: { accept: '*/*' },
  });

  if (!response.ok) {
    throw new Error(`API error: ${response.status} ${response.statusText}`);
  }

  return response.json();
}

export async function conceptSearch(
  query: string,
  maxResults: number = 200
): Promise<ConceptSearchResponse> {
  const params = new URLSearchParams({
    query,
    maxResults: maxResults.toString(),
  });

  const response = await fetch(`${GR_API_BASE_URL}/concept-search?${params}`, {
    method: 'GET',
    headers: {
      accept: '*/*',
    },
  });

  if (!response.ok) {
    throw new Error(`API error: ${response.status} ${response.statusText}`);
  }

  return response.json();
}

export async function getDescendants(conceptId: string): Promise<DescendantsResponse> {
  const params = new URLSearchParams({ conceptId });

  const response = await fetch(`${GR_API_BASE_URL}/descendants?${params}`, {
    method: 'GET',
    headers: {
      accept: '*/*',
    },
  });

  if (!response.ok) {
    throw new Error(`API error: ${response.status} ${response.statusText}`);
  }

  return response.json();
}

export async function removeDescendant(
  parentConceptId: string,
  descendantConceptId: string
): Promise<DescendantOperationResponse> {
  const params = new URLSearchParams({ parentConceptId, descendantConceptId });

  const response = await fetch(`${KG_API_BASE_URL}/descendants?${params}`, {
    method: 'DELETE',
    headers: {
      accept: '*/*',
    },
  });

  if (!response.ok) {
    throw new Error(`API error: ${response.status} ${response.statusText}`);
  }

  return response.json();
}

export async function addDescendant(
  parentConceptId: string,
  descendantConceptId: string
): Promise<DescendantOperationResponse> {
  const params = new URLSearchParams({ parentConceptId, descendantConceptId });

  const response = await fetch(`${KG_API_BASE_URL}/descendants?${params}`, {
    method: 'POST',
    headers: {
      accept: '*/*',
    },
  });

  if (!response.ok) {
    throw new Error(`API error: ${response.status} ${response.statusText}`);
  }

  return response.json();
}

export async function createAndAddDescendant(
  parentConceptId: string,
  conceptName: string
): Promise<DescendantOperationResponse> {
  const params = new URLSearchParams({ parentConceptId, conceptName });

  const response = await fetch(`${KG_API_BASE_URL}/descendants/create?${params}`, {
    method: 'POST',
    headers: {
      accept: '*/*',
    },
  });

  if (!response.ok) {
    throw new Error(`API error: ${response.status} ${response.statusText}`);
  }

  return response.json();
}

/**
 * Creates a concept from its descriptions and stated axiom.
 *
 * Exactly one description must be FULLY_QUALIFIED_NAME. Axioms may carry necessary and
 * sufficient sets together; an axiom with no parents references "Anonymous concept", the
 * placeholder Komet writes for an unfinished definition.
 */
export async function createConcept(
  request: CreateConceptRequest
): Promise<ConceptCreationResponse> {
  const response = await fetch(`${KG_API_BASE_URL}/concepts`, {
    method: 'POST',
    headers: {
      accept: '*/*',
      'content-type': 'application/json',
    },
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    throw new Error(`API error: ${response.status} ${response.statusText}`);
  }

  return response.json();
}

export async function conceptSearchWithSort(
  query: string,
  maxResults: number = 200,
  sortBy: SearchSortOption = 'TOP_COMPONENT'
): Promise<ConceptSearchWithSortResponse> {
  const params = new URLSearchParams({
    query,
    maxResults: maxResults.toString(),
    sortBy,
  });

  const response = await fetch(`${GR_API_BASE_URL}/concept-search-sorted?${params}`, {
    method: 'GET',
    headers: {
      accept: '*/*',
    },
  });

  if (!response.ok) {
    throw new Error(`API error: ${response.status} ${response.statusText}`);
  }

  return response.json();
}


export async function getConceptById(conceptId: string): Promise<ConceptSearchResponse> {
  const params = new URLSearchParams({ conceptId });

  const response = await fetch(`${GR_API_BASE_URL}/entity?${params}`, {
    method: 'GET',
    headers: { accept: '*/*' },
  });

  if (!response.ok) {
    throw new Error(`API error: ${response.status} ${response.statusText}`);
  }

  return response.json();
}

export async function getChildren(conceptId: string): Promise<ConceptSearchResponse> {
  const params = new URLSearchParams({ conceptId });

  const response = await fetch(`${GR_API_BASE_URL}/children?${params}`, {
    method: 'GET',
    headers: { accept: '*/*' },
  });

  if (!response.ok) {
    throw new Error(`API error: ${response.status} ${response.statusText}`);
  }

  return response.json();
}

export async function getLidrRecords(testKitConceptId: string): Promise<ConceptSearchResponse> {
  const params = new URLSearchParams({ testKitConceptId });

  const response = await fetch(`${GR_API_BASE_URL}/lidr-records?${params}`, {
    method: 'GET',
    headers: { accept: '*/*' },
  });

  if (!response.ok) {
    throw new Error(`API error: ${response.status} ${response.statusText}`);
  }

  return response.json();
}



// ── Tier 2: Knowledge Graph API (with coordinate overrides) ─────────

function appendCoordinateParams(params: URLSearchParams, coords?: CoordinateOverrideParams): void {
  if (!coords) return;
  if (coords.allowedStates) params.set('allowedStates', coords.allowedStates);
  if (coords.positionTime != null) params.set('positionTime', coords.positionTime.toString());
  if (coords.positionPath) params.set('positionPath', coords.positionPath);
  if (coords.modules) {
    for (const m of coords.modules) params.append('modules', m);
  }
  if (coords.excludedModules) {
    for (const m of coords.excludedModules) params.append('excludedModules', m);
  }
  if (coords.modulePriority) {
    for (const m of coords.modulePriority) params.append('modulePriority', m);
  }
  if (coords.premiseType) params.set('premiseType', coords.premiseType);
}

export async function kgGetSemantics(
  conceptId: string,
  coords?: CoordinateOverrideParams,
): Promise<ConceptSemanticsResponse> {
  const params = new URLSearchParams({ conceptId });
  appendCoordinateParams(params, coords);

  const response = await fetch(`${KG_API_BASE_URL}/semantics?${params}`, {
    method: 'GET',
    headers: { accept: '*/*' },
  });

  if (!response.ok) {
    throw new Error(`API error: ${response.status} ${response.statusText}`);
  }

  return response.json();
}

export async function kgGetComments(
  conceptId: string,
  coords?: CoordinateOverrideParams,
): Promise<ConceptSemanticsResponse> {
  const params = new URLSearchParams({ conceptId });
  appendCoordinateParams(params, coords);

  const response = await fetch(`${KG_API_BASE_URL}/comments?${params}`, {
    method: 'GET',
    headers: { accept: '*/*' },
  });

  if (!response.ok) {
    throw new Error(`API error: ${response.status} ${response.statusText}`);
  }

  return response.json();
}

export async function kgGetChangeHistory(
  entityId: string,
  coords?: CoordinateOverrideParams,
): Promise<ChangeHistoryResponse> {
  const params = new URLSearchParams({ entityId });
  appendCoordinateParams(params, coords);

  const response = await fetch(`${KG_API_BASE_URL}/change-history?${params}`, {
    method: 'GET',
    headers: { accept: '*/*' },
  });

  if (!response.ok) {
    throw new Error(`API error: ${response.status} ${response.statusText}`);
  }

  return response.json();
}

export async function kgGetChildren(
  conceptId: string,
  coords?: CoordinateOverrideParams,
): Promise<ConceptSearchResponse> {
  const params = new URLSearchParams({ conceptId });
  appendCoordinateParams(params, coords);

  const response = await fetch(`${KG_API_BASE_URL}/children?${params}`, {
    method: 'GET',
    headers: { accept: '*/*' },
  });

  if (!response.ok) {
    throw new Error(`API error: ${response.status} ${response.statusText}`);
  }

  return response.json();
}

export async function kgGetDescendants(
  conceptId: string,
  coords?: CoordinateOverrideParams,
): Promise<ConceptSearchResponse> {
  const params = new URLSearchParams({ conceptId });
  appendCoordinateParams(params, coords);

  const response = await fetch(`${KG_API_BASE_URL}/descendants?${params}`, {
    method: 'GET',
    headers: { accept: '*/*' },
  });

  if (!response.ok) {
    throw new Error(`API error: ${response.status} ${response.statusText}`);
  }

  return response.json();
}

export async function kgGetConceptChangeHistory(
  conceptId: string,
  coords?: CoordinateOverrideParams,
): Promise<ConceptChangeHistoryResponse> {
  const params = new URLSearchParams({ conceptId });
  appendCoordinateParams(params, coords);

  const response = await fetch(`${KG_API_BASE_URL}/concept-change-history?${params}`, {
    method: 'GET',
    headers: { accept: '*/*' },
  });

  if (!response.ok) {
    throw new Error(`API error: ${response.status} ${response.statusText}`);
  }

  return response.json();
}

// ── Tier 2: Coordinate Save & Retrieve ──────────────────────────────

export async function saveStampCoordinate(
  settings: StampCoordinateSettings,
): Promise<SavedStampCoordinateResponse> {
  const response = await fetch(`${KG_API_BASE_URL}/coordinates/stamp`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', accept: '*/*' },
    body: JSON.stringify(settings),
  });

  if (!response.ok) {
    throw new Error(`API error: ${response.status} ${response.statusText}`);
  }

  return response.json();
}

export async function listStampCoordinates(): Promise<SavedStampCoordinateResponse[]> {
  const response = await fetch(`${KG_API_BASE_URL}/coordinates/stamp`, {
    method: 'GET',
    headers: { accept: '*/*' },
  });

  if (!response.ok) {
    throw new Error(`API error: ${response.status} ${response.statusText}`);
  }

  return response.json();
}

export async function saveNavigationCoordinate(
  settings: NavigationCoordinateSettings,
): Promise<SavedNavigationCoordinateResponse> {
  const response = await fetch(`${KG_API_BASE_URL}/coordinates/navigation`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', accept: '*/*' },
    body: JSON.stringify(settings),
  });

  if (!response.ok) {
    throw new Error(`API error: ${response.status} ${response.statusText}`);
  }

  return response.json();
}

export async function listNavigationCoordinates(): Promise<SavedNavigationCoordinateResponse[]> {
  const response = await fetch(`${KG_API_BASE_URL}/coordinates/navigation`, {
    method: 'GET',
    headers: { accept: '*/*' },
  });

  if (!response.ok) {
    throw new Error(`API error: ${response.status} ${response.statusText}`);
  }

  return response.json();
}

export async function saveLanguageCoordinate(
  settings: LanguageCoordinateSettings,
): Promise<SavedLanguageCoordinateResponse> {
  const response = await fetch(`${KG_API_BASE_URL}/coordinates/language`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', accept: '*/*' },
    body: JSON.stringify(settings),
  });

  if (!response.ok) {
    throw new Error(`API error: ${response.status} ${response.statusText}`);
  }

  return response.json();
}

export async function listLanguageCoordinates(): Promise<SavedLanguageCoordinateResponse[]> {
  const response = await fetch(`${KG_API_BASE_URL}/coordinates/language`, {
    method: 'GET',
    headers: { accept: '*/*' },
  });

  if (!response.ok) {
    throw new Error(`API error: ${response.status} ${response.statusText}`);
  }

  return response.json();
}

export async function getSemanticsWithCoordinate(
  conceptId: string,
  stampCoordinateId?: string,
  navigationCoordinateId?: string,
  languageCoordinateId?: string,
): Promise<ConceptSemanticsResponse> {
  const params = new URLSearchParams({ conceptId });
  if (stampCoordinateId) params.set('stampCoordinateId', stampCoordinateId);
  if (navigationCoordinateId) params.set('navigationCoordinateId', navigationCoordinateId);
  if (languageCoordinateId) params.set('languageCoordinateId', languageCoordinateId);

  const response = await fetch(`${KG_API_BASE_URL}/semantics-by-coordinate?${params}`, {
    method: 'GET',
    headers: { accept: '*/*' },
  });

  if (!response.ok) {
    throw new Error(`API error: ${response.status} ${response.statusText}`);
  }

  return response.json();
}

/**
 * Runs the reasoner, calling `onPhase` as each phase completes, resolving with the outcome.
 *
 * Reads the stream with fetch rather than EventSource: EventSource only issues GET, and running
 * a classification writes inferred results, so the endpoint is a POST. The framing is plain SSE
 * — events separated by a blank line, with `event:` and `data:` fields — so parsing it by hand
 * is a few lines.
 */
export async function runReasonerStreaming(
  onPhase: (phase: ReasonerPhaseEvent) => void
): Promise<ReasonerResultsResponse> {
  const response = await fetch(`${ADMIN_API_BASE_URL}/reasoner/stream`, {
    method: 'POST',
    headers: { accept: 'text/event-stream' },
  });

  if (response.status === 409) {
    throw new Error('A classification is already running');
  }
  if (!response.ok || !response.body) {
    throw new Error(`API error: ${response.status} ${response.statusText}`);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let result: ReasonerResultsResponse | null = null;

  const consumeFrame = (frame: string) => {
    let eventName = 'message';
    const dataLines: string[] = [];
    for (const line of frame.split('\n')) {
      if (line.startsWith('event:')) eventName = line.slice(6).trim();
      else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim());
    }
    if (dataLines.length === 0) return;
    const payload = JSON.parse(dataLines.join('\n'));
    if (eventName === 'phase') onPhase(payload as ReasonerPhaseEvent);
    else if (eventName === 'result') result = payload as ReasonerResultsResponse;
  };

  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    // Normalise line endings so a frame boundary is always a blank line.
    buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n');
    let boundary = buffer.indexOf('\n\n');
    while (boundary !== -1) {
      consumeFrame(buffer.slice(0, boundary));
      buffer = buffer.slice(boundary + 2);
      boundary = buffer.indexOf('\n\n');
    }
  }
  if (buffer.trim()) consumeFrame(buffer);

  if (!result) {
    throw new Error('Reasoner stream ended without a result');
  }
  return result;
}
