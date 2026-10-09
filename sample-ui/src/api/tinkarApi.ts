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
  ReasonerRunEvent,
  ChangesetJobResult,
  ExportRequest,
  JobAttachedEvent,
  JobProgressEvent,
  JobSummary,
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
const SERVER_ORIGIN = 'http://localhost:8085';
const ADMIN_API_BASE_URL = `${SERVER_ORIGIN}/api/ike/admin`;

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

/** What a reasoner stream reports, in order: the run, its phases, then the outcome. */
export type ReasonerStreamHandlers = {
  onRun?: (run: ReasonerRunEvent) => void;
  onPhase: (phase: ReasonerPhaseEvent) => void;
};

/**
 * Starts the reasoner, or attaches to the run already going, resolving with its outcome.
 *
 * A run belongs to the server, not to this request: aborting `signal` (or closing the tab) only
 * stops watching, and the classification carries on. Get back to it with
 * {@link watchReasonerStreaming}; stop it with {@link cancelReasoner}.
 */
export async function runReasonerStreaming(
  handlers: ReasonerStreamHandlers,
  signal?: AbortSignal
): Promise<ReasonerResultsResponse> {
  const outcome = await streamReasoner('POST', handlers, signal);
  if (!outcome) {
    throw new Error('Reasoner stream ended without a result');
  }
  return outcome;
}

/**
 * Attaches to the running reasoner, or replays the last run if it has finished. Never starts a
 * run. Resolves with null if there has been no run since the server started.
 */
export async function watchReasonerStreaming(
  handlers: ReasonerStreamHandlers,
  signal?: AbortSignal
): Promise<ReasonerResultsResponse | null> {
  return streamReasoner('GET', handlers, signal);
}

/**
 * Asks the running reasoner to stop. Resolves once the request is accepted, not once the run has
 * stopped — anyone watching gets a result with `cancelled` set when it has. Throws if nothing is
 * running.
 */
export async function cancelReasoner(): Promise<void> {
  const response = await fetch(`${ADMIN_API_BASE_URL}/reasoner/cancel`, { method: 'POST' });
  if (response.status === 409) {
    throw new Error('The reasoner is not running');
  }
  if (!response.ok) {
    throw new Error(`API error: ${response.status} ${response.statusText}`);
  }
}

/**
 * Reads a reasoner event stream.
 *
 * With fetch rather than EventSource: EventSource only issues GET, and starting a run is a POST.
 * The framing is plain SSE — events separated by a blank line, with `event:` and `data:` fields —
 * so parsing it by hand is a few lines. Heartbeats are SSE comments and carry no data, so they
 * are skipped.
 *
 * @returns the outcome, or null if the server had no run to report (204)
 */
async function streamReasoner(
  method: 'GET' | 'POST',
  { onRun, onPhase }: ReasonerStreamHandlers,
  signal?: AbortSignal
): Promise<ReasonerResultsResponse | null> {
  const response = await fetch(`${ADMIN_API_BASE_URL}/reasoner/stream`, {
    method,
    headers: { accept: 'text/event-stream' },
    signal,
  });

  if (response.status === 204) {
    return null;
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
    if (eventName === 'run') onRun?.(payload as ReasonerRunEvent);
    else if (eventName === 'phase') onPhase(payload as ReasonerPhaseEvent);
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

// ── Changeset import / export jobs ──────────────────────────────────

/** What a job stream reports, in order: the job, queue position, start, progress, then the result. */
export type JobStreamHandlers = {
  onJob?: (job: JobAttachedEvent) => void;
  /** While waiting: the jobs ahead, running one first. Empty once it is next. */
  onQueued?: (ahead: JobSummary[]) => void;
  onStarted?: (startedAt: number) => void;
  onProgress?: (progress: JobProgressEvent) => void;
};

/**
 * Queues an import of `file` and follows it to the end. Imports cannot be cancelled; aborting
 * `signal` only stops watching.
 */
export async function importChangesetStreaming(
  file: File,
  handlers: JobStreamHandlers,
  signal?: AbortSignal
): Promise<ChangesetJobResult> {
  const body = new FormData();
  body.append('file', file);
  body.append('useMultiPass', 'true');
  return followJob(
    fetch(`${ADMIN_API_BASE_URL}/import/stream`, {
      method: 'POST',
      headers: { accept: 'text/event-stream' },
      body,
      signal,
    }),
    handlers
  );
}

/** Queues an export and follows it to the end; the result's downloadUrl fetches the zip. */
export async function exportStreaming(
  request: ExportRequest,
  handlers: JobStreamHandlers,
  signal?: AbortSignal
): Promise<ChangesetJobResult> {
  return followJob(
    fetch(`${ADMIN_API_BASE_URL}/export/stream`, {
      method: 'POST',
      headers: { accept: 'text/event-stream', 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
      signal,
    }),
    handlers
  );
}

/** Rejoins any import or export job, replaying what it has done so far. */
export async function watchJob(
  jobId: string,
  handlers: JobStreamHandlers,
  signal?: AbortSignal
): Promise<ChangesetJobResult> {
  return followJob(
    fetch(`${ADMIN_API_BASE_URL}/jobs/${jobId}/stream`, {
      headers: { accept: 'text/event-stream' },
      signal,
    }),
    handlers
  );
}

/** Jobs from the last hour — queued, running and finished — oldest first. */
export async function listJobs(): Promise<JobSummary[]> {
  const response = await fetch(`${ADMIN_API_BASE_URL}/jobs`);
  if (!response.ok) {
    throw new Error(`API error: ${response.status} ${response.statusText}`);
  }
  return response.json();
}

/** Asks a queued or running export or reasoner run to stop. Throws with the server's reason if it cannot. */
export async function cancelJob(jobId: string): Promise<void> {
  const response = await fetch(`${ADMIN_API_BASE_URL}/jobs/${jobId}/cancel`, { method: 'POST' });
  if (!response.ok) {
    const body = await response.json().catch(() => null);
    throw new Error(body?.error ?? `API error: ${response.status} ${response.statusText}`);
  }
}

/** The absolute URL for a result's server-relative downloadUrl. */
export function absoluteDownloadUrl(downloadUrl: string): string {
  return `${SERVER_ORIGIN}${downloadUrl}`;
}

async function followJob(
  request: Promise<Response>,
  { onJob, onQueued, onStarted, onProgress }: JobStreamHandlers
): Promise<ChangesetJobResult> {
  const response = await request;
  if (!response.ok || !response.body) {
    const body = await response.json().catch(() => null);
    throw new Error(body?.error ?? `API error: ${response.status} ${response.statusText}`);
  }
  let result: ChangesetJobResult | null = null;
  await readEventStream(response.body, (event, payload) => {
    if (event === 'job') onJob?.(payload as JobAttachedEvent);
    else if (event === 'queued') onQueued?.((payload as { ahead: JobSummary[] }).ahead);
    else if (event === 'started') onStarted?.((payload as { startedAt: number }).startedAt);
    else if (event === 'progress') onProgress?.(payload as JobProgressEvent);
    else if (event === 'result') result = payload as ChangesetJobResult;
  });
  if (!result) {
    throw new Error('Job stream ended without a result');
  }
  return result;
}

/**
 * Reads a server-sent event stream, calling `onEvent` with each named event's parsed JSON data.
 * Heartbeats are SSE comments with no data, so they are skipped.
 */
async function readEventStream(
  body: ReadableStream<Uint8Array>,
  onEvent: (event: string, payload: unknown) => void
): Promise<void> {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  const consumeFrame = (frame: string) => {
    let eventName = 'message';
    const dataLines: string[] = [];
    for (const line of frame.split('\n')) {
      if (line.startsWith('event:')) eventName = line.slice(6).trim();
      else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim());
    }
    if (dataLines.length > 0) onEvent(eventName, JSON.parse(dataLines.join('\n')));
  };
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n');
    let boundary = buffer.indexOf('\n\n');
    while (boundary !== -1) {
      consumeFrame(buffer.slice(0, boundary));
      buffer = buffer.slice(boundary + 2);
      boundary = buffer.indexOf('\n\n');
    }
  }
  if (buffer.trim()) consumeFrame(buffer);
}
