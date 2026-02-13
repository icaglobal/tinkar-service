import type {
  ChangeHistoryResponse,
  ConceptChangeHistoryResponse,
  ConceptSearchResponse,
  ConceptSearchWithSortResponse,
  ConceptSemanticsResponse,
  CoordinateOverrideParams,
  DescendantsResponse,
  DescendantOperationResponse,
  SearchSortOption,
} from './types';

const API_BASE_URL = 'http://localhost:8085/api/tinkar';
const KG_API_BASE_URL = 'http://localhost:8085/api/ike/knowledgegraph';

export async function search(query: string): Promise<ConceptSearchResponse> {
  const params = new URLSearchParams({ query });

  const response = await fetch(`${API_BASE_URL}/search?${params}`, {
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

  const response = await fetch(`${API_BASE_URL}/conceptSearch?${params}`, {
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

  const response = await fetch(`${API_BASE_URL}/descendants/conceptId?${params}`, {
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

  const response = await fetch(`${API_BASE_URL}/descendants?${params}`, {
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

  const response = await fetch(`${API_BASE_URL}/descendants?${params}`, {
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

  const response = await fetch(`${API_BASE_URL}/descendants/create?${params}`, {
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

  const response = await fetch(`${API_BASE_URL}/conceptSearchWithSort?${params}`, {
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

export async function getSemantics(conceptId: string): Promise<ConceptSemanticsResponse> {
  const params = new URLSearchParams({ conceptId });

  const response = await fetch(`${API_BASE_URL}/semantics?${params}`, {
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

  const response = await fetch(`${API_BASE_URL}/conceptId?${params}`, {
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

  const response = await fetch(`${API_BASE_URL}/children/conceptId?${params}`, {
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

  const response = await fetch(`${API_BASE_URL}/lidr-records/testKitConceptId?${params}`, {
    method: 'GET',
    headers: { accept: '*/*' },
  });

  if (!response.ok) {
    throw new Error(`API error: ${response.status} ${response.statusText}`);
  }

  return response.json();
}

export async function getChangeHistory(entityId: string): Promise<ChangeHistoryResponse> {
  const params = new URLSearchParams({ entityId });

  const response = await fetch(`${API_BASE_URL}/change-history?${params}`, {
    method: 'GET',
    headers: { accept: '*/*' },
  });

  if (!response.ok) {
    throw new Error(`API error: ${response.status} ${response.statusText}`);
  }

  return response.json();
}

export async function getComments(conceptId: string): Promise<ConceptSemanticsResponse> {
  const params = new URLSearchParams({ conceptId });

  const response = await fetch(`${API_BASE_URL}/comments?${params}`, {
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
