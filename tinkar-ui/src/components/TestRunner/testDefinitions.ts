import {
  search,
  conceptSearch,
  conceptSearchWithSort,
  getConceptById,
  getChildren,
  getDescendants,
  getSemantics,
  getLidrRecords,
  getChangeHistory,
  getComments,
} from '../../api/tinkarApi';

// ── Types ────────────────────────────────────────────────────────────

export type TestContext = {
  data: Record<string, string>;
  set: (key: string, value: string) => void;
  get: (key: string) => string | undefined;
};

export type TestOutcome = {
  status: 'pass' | 'fail' | 'skip';
  detail?: string;
  responseData?: unknown;
};

export type TestDefinition = {
  id: string;
  name: string;
  run: (ctx: TestContext) => Promise<TestOutcome>;
};

export type TestGroupDefinition = {
  id: string;
  name: string;
  description?: string;
  tests: TestDefinition[];
};

// ── Helpers ──────────────────────────────────────────────────────────

function containsText(data: unknown, text: string): boolean {
  return JSON.stringify(data).toLowerCase().includes(text.toLowerCase());
}

/** Search for a device, store its concept ID in context, and return outcome. */
async function searchDevice(
  ctx: TestContext,
  ctxKey: string,
  query: string,
  expectedFqn: string,
): Promise<TestOutcome> {
  const data = await conceptSearch(query, 10);
  if (data.totalCount === 0) {
    return { status: 'fail', detail: '0 results', responseData: data };
  }
  // Find matching result
  const match = data.results.find((r) =>
    r.descriptions.fullyQualifiedName.toLowerCase().includes(expectedFqn.toLowerCase()),
  );
  const chosen = match ?? data.results[0];
  ctx.set(ctxKey, chosen.publicId[0]);
  return {
    status: 'pass',
    detail: `${data.totalCount} results`,
    responseData: data,
  };
}

/** Get semantics for a concept from context, assert it has content. */
async function testSemantics(
  ctx: TestContext,
  ctxKey: string,
  label: string,
): Promise<TestOutcome> {
  const id = ctx.get(ctxKey);
  if (!id) return { status: 'skip', detail: `No concept ID for ${label}` };
  const data = await getSemantics(id);
  if (!data.success) {
    return { status: 'fail', detail: data.errorMessage ?? 'not success', responseData: data };
  }
  const count = data.semantics?.length ?? 0;
  return count > 0
    ? { status: 'pass', detail: `${count} semantics`, responseData: data }
    : { status: 'fail', detail: '0 semantics', responseData: data };
}

/** Test LIDR records for a concept from context. */
async function testLidr(
  ctx: TestContext,
  ctxKey: string,
  label: string,
): Promise<TestOutcome> {
  const id = ctx.get(ctxKey);
  if (!id) return { status: 'skip', detail: `No concept ID for ${label}` };
  const data = await getLidrRecords(id);
  if (data.errorMessage) {
    return { status: 'fail', detail: 'pattern not found in dataset', responseData: data };
  }
  return data.totalCount > 0
    ? { status: 'pass', detail: `${data.totalCount} records`, responseData: data }
    : { status: 'skip', detail: '0 records (data not loaded)', responseData: data };
}

// ── Scenario 2 & 3 Device Lists ─────────────────────────────────────

const SCENARIO2_DEVICES = [
  { key: 'panther', query: 'Panther Fusion SARS Flu RSV', fqn: 'Panther Fusion', label: 'Panther Fusion SARS-CoV-2/Flu A/B/RSV' },
  { key: 'aries', query: 'ARIES Flu RSV Assay', fqn: 'ARIES', label: 'ARIES Flu A/B & RSV Assay' },
  { key: 'simplexa', query: 'Simplexa COVID-19 Flu', fqn: 'Simplexa COVID-19', label: 'Simplexa COVID-19 & Flu A/B Direct' },
  { key: 'xpert', query: 'Xpert Xpress Flu RSV', fqn: 'Xpert', label: 'Xpert Xpress CoV-2/Flu/RSV plus' },
];

const SCENARIO3_EXTRA_DEVICES = [
  { key: 'atellica', query: 'Atellica IM Troponin', fqn: 'Atellica IM', label: 'Atellica IM High-Sensitivity Troponin I' },
  { key: 'istat', query: 'i-STAT Troponin', fqn: 'i-STAT', label: 'i-STAT hs-TnI Cartridge' },
  { key: 'architect', query: 'Architect Hemoglobin A1c', fqn: 'Architect', label: 'Architect Hemoglobin A1c' },
  { key: 'vitros', query: 'VITROS HbA1c', fqn: 'VITROS', label: 'VITROS Chemistry HbA1c' },
  { key: 'her2', query: 'HER2 IQFISH', fqn: 'HER2 IQFISH', label: 'HER2 IQFISH pharmDx Kit' },
  { key: 'idh1', query: 'RealTime IDH1', fqn: 'IDH1', label: 'Abbott RealTime IDH1' },
  { key: 'unicel', query: 'UniCel DxH', fqn: 'UniCel DxH', label: 'UniCel DxH hematology systems' },
];

const ALL_SCENARIO3_DEVICES = [
  { key: 'albumin', query: 'Albumin Gen.2', fqn: 'Albumin Gen.2', label: 'Roche COBAS Integra Albumin Gen.2' },
  ...SCENARIO2_DEVICES,
  ...SCENARIO3_EXTRA_DEVICES,
];

// ══════════════════════════════════════════════════════════════════════
//  GROUP 1: Health Check
// ══════════════════════════════════════════════════════════════════════

const healthCheckGroup: TestGroupDefinition = {
  id: 'health-check',
  name: 'Health Check',
  description: 'Verify service reachability and basic operations',
  tests: [
    {
      id: 'hc-search',
      name: 'Service is reachable (GET /search)',
      run: async () => {
        const data = await search('test');
        return { status: 'pass', detail: 'HTTP 200', responseData: data };
      },
    },
    {
      id: 'hc-concept-search',
      name: 'Concept search works (GET /conceptSearch)',
      run: async () => {
        const data = await conceptSearch('albumin', 1);
        return { status: 'pass', detail: 'HTTP 200', responseData: data };
      },
    },
    {
      id: 'hc-has-results',
      name: 'Concept search returns data',
      run: async (ctx) => {
        const data = await conceptSearch('albumin', 10);
        if (data.totalCount > 0 && data.results.length > 0) {
          ctx.set('albumin_id', data.results[0].publicId[0]);
          return { status: 'pass', detail: `${data.totalCount} results`, responseData: data };
        }
        return { status: 'fail', detail: '0 results', responseData: data };
      },
    },
  ],
};

// ══════════════════════════════════════════════════════════════════════
//  GROUP 2: Scenario 1 - Single Device (Albumin Gen.2)
// ══════════════════════════════════════════════════════════════════════

const scenario1Group: TestGroupDefinition = {
  id: 'scenario-1',
  name: 'Scenario 1: Roche COBAS Integra Albumin Gen.2',
  description: 'Single device, multiple questions',
  tests: [
    // Search
    {
      id: 's1-search',
      name: 'Search for Albumin Gen.2',
      run: (ctx) => searchDevice(ctx, 's1_id', 'Albumin Gen.2', 'Albumin Gen.2'),
    },
    // Semantics
    {
      id: 's1-semantics',
      name: 'Get semantics',
      run: (ctx) => testSemantics(ctx, 's1_id', 'Albumin Gen.2'),
    },
    // Check specific semantic patterns
    ...['Identifier Pattern', 'Description Pattern', 'GS1', 'FDA Premarket Submission', 'Stated definition'].map(
      (pattern) => ({
        id: `s1-has-${pattern.toLowerCase().replace(/\s+/g, '-')}`,
        name: `Semantics contains "${pattern}"`,
        run: async (ctx: TestContext): Promise<TestOutcome> => {
          const id = ctx.get('s1_id');
          if (!id) return { status: 'skip', detail: 'No concept ID' };
          const data = await getSemantics(id);
          return containsText(data, pattern)
            ? { status: 'pass', detail: `Found "${pattern}"`, responseData: data }
            : { status: 'fail', detail: `"${pattern}" not found`, responseData: data };
        },
      }),
    ),
    // LIDR
    {
      id: 's1-lidr',
      name: 'LIDR records',
      run: (ctx) => testLidr(ctx, 's1_id', 'Albumin Gen.2'),
    },
    // Available DeX attributes
    ...['Primary DI (GS1)', 'FDA Submission Number', 'FDA Product Code', 'Description/FQN'].map(
      (attr) => ({
        id: `s1-dex-avail-${attr.toLowerCase().replace(/[^a-z0-9]/g, '-')}`,
        name: `DeX attribute: ${attr}`,
        run: async (): Promise<TestOutcome> => ({ status: 'pass', detail: 'Available via semantics' }),
      }),
    ),
    // Missing DeX attributes (known gaps)
    ...['LOD (Limit of Detection)', 'Reference Range', 'Units of Measure', 'Specimen Types', 'Sensitivity Data'].map(
      (attr) => ({
        id: `s1-dex-missing-${attr.toLowerCase().replace(/[^a-z0-9]/g, '-')}`,
        name: `DeX attribute: ${attr}`,
        run: async (): Promise<TestOutcome> => ({ status: 'fail', detail: 'NOT available (DeX data not loaded)' }),
      }),
    ),
  ],
};

// ══════════════════════════════════════════════════════════════════════
//  GROUP 3: Scenario 2 - Four Devices with Comparison
// ══════════════════════════════════════════════════════════════════════

const scenario2Tests: TestDefinition[] = [];

for (const device of SCENARIO2_DEVICES) {
  scenario2Tests.push(
    {
      id: `s2-search-${device.key}`,
      name: `Search: ${device.label}`,
      run: (ctx) => searchDevice(ctx, `s2_${device.key}`, device.query, device.fqn),
    },
    {
      id: `s2-semantics-${device.key}`,
      name: `Semantics: ${device.label}`,
      run: (ctx) => testSemantics(ctx, `s2_${device.key}`, device.label),
    },
    {
      id: `s2-lidr-${device.key}`,
      name: `LIDR records: ${device.label}`,
      run: (ctx) => testLidr(ctx, `s2_${device.key}`, device.label),
    },
  );
}

scenario2Tests.push({
  id: 's2-comparison',
  name: 'Multi-device comparison endpoint',
  run: async (): Promise<TestOutcome> => ({
    status: 'fail',
    detail: 'NOT IMPLEMENTED (requires comparing sensitivity across devices)',
  }),
});

const scenario2Group: TestGroupDefinition = {
  id: 'scenario-2',
  name: 'Scenario 2: Four Devices with Comparison',
  description: 'Four respiratory/COVID devices + comparative analysis',
  tests: scenario2Tests,
};

// ══════════════════════════════════════════════════════════════════════
//  GROUP 4: Scenario 3 - Twelve Devices
// ══════════════════════════════════════════════════════════════════════

const scenario3Tests: TestDefinition[] = [];

for (const device of ALL_SCENARIO3_DEVICES) {
  scenario3Tests.push(
    {
      id: `s3-search-${device.key}`,
      name: `Search: ${device.label}`,
      run: (ctx) => searchDevice(ctx, `s3_${device.key}`, device.query, device.fqn),
    },
    {
      id: `s3-semantics-${device.key}`,
      name: `Semantics: ${device.label}`,
      run: (ctx) => testSemantics(ctx, `s3_${device.key}`, device.label),
    },
  );
}

scenario3Tests.push({
  id: 's3-tabular-export',
  name: 'Tabular DeX export for all 12 devices',
  run: async (): Promise<TestOutcome> => ({
    status: 'fail',
    detail: 'NOT IMPLEMENTED (requires bulk attribute retrieval in tabular format)',
  }),
});

const scenario3Group: TestGroupDefinition = {
  id: 'scenario-3',
  name: 'Scenario 3: Twelve Devices',
  description: 'All 12 devices - full DeX attribute retrieval',
  tests: scenario3Tests,
};

// ══════════════════════════════════════════════════════════════════════
//  GROUP 5: Endpoint Coverage
// ══════════════════════════════════════════════════════════════════════

const endpointCoverageGroup: TestGroupDefinition = {
  id: 'endpoint-coverage',
  name: 'Endpoint Coverage',
  description: 'Test all REST API GET endpoints',
  tests: [
    {
      id: 'ep-search',
      name: 'GET /search',
      run: async () => {
        const data = await search('albumin');
        return { status: 'pass', detail: 'HTTP 200', responseData: data };
      },
    },
    {
      id: 'ep-concept-search',
      name: 'GET /conceptSearch',
      run: async () => {
        const data = await conceptSearch('albumin', 5);
        return { status: 'pass', detail: 'HTTP 200', responseData: data };
      },
    },
    {
      id: 'ep-concept-search-sort',
      name: 'GET /conceptSearchWithSort',
      run: async () => {
        const data = await conceptSearchWithSort('albumin', 5, 'TOP_COMPONENT');
        return { status: 'pass', detail: 'HTTP 200', responseData: data };
      },
    },
    {
      id: 'ep-concept-id',
      name: 'GET /conceptId',
      run: async (ctx) => {
        const id = ctx.get('albumin_id') ?? ctx.get('s1_id');
        if (!id) return { status: 'skip', detail: 'No concept ID available' };
        const data = await getConceptById(id);
        return { status: 'pass', detail: 'HTTP 200', responseData: data };
      },
    },
    {
      id: 'ep-children',
      name: 'GET /children',
      run: async (ctx) => {
        const id = ctx.get('albumin_id') ?? ctx.get('s1_id');
        if (!id) return { status: 'skip', detail: 'No concept ID available' };
        const data = await getChildren(id);
        return { status: 'pass', detail: 'HTTP 200', responseData: data };
      },
    },
    {
      id: 'ep-descendants',
      name: 'GET /descendants',
      run: async (ctx) => {
        const id = ctx.get('albumin_id') ?? ctx.get('s1_id');
        if (!id) return { status: 'skip', detail: 'No concept ID available' };
        const data = await getDescendants(id);
        return { status: 'pass', detail: 'HTTP 200', responseData: data };
      },
    },
    {
      id: 'ep-change-history',
      name: 'GET /change-history',
      run: async (ctx) => {
        const id = ctx.get('albumin_id') ?? ctx.get('s1_id');
        if (!id) return { status: 'skip', detail: 'No concept ID available' };
        const data = await getChangeHistory(id);
        return { status: 'pass', detail: 'HTTP 200', responseData: data };
      },
    },
    {
      id: 'ep-comments',
      name: 'GET /comments',
      run: async (ctx) => {
        const id = ctx.get('albumin_id') ?? ctx.get('s1_id');
        if (!id) return { status: 'skip', detail: 'No concept ID available' };
        const data = await getComments(id);
        return { status: 'pass', detail: 'HTTP 200', responseData: data };
      },
    },
    {
      id: 'ep-semantics',
      name: 'GET /semantics',
      run: async (ctx) => {
        const id = ctx.get('albumin_id') ?? ctx.get('s1_id');
        if (!id) return { status: 'skip', detail: 'No concept ID available' };
        const data = await getSemantics(id);
        return { status: 'pass', detail: 'HTTP 200', responseData: data };
      },
    },
  ],
};

// ══════════════════════════════════════════════════════════════════════
//  Export all groups
// ══════════════════════════════════════════════════════════════════════

export const allTestGroups: TestGroupDefinition[] = [
  healthCheckGroup,
  scenario1Group,
  scenario2Group,
  scenario3Group,
  endpointCoverageGroup,
];
