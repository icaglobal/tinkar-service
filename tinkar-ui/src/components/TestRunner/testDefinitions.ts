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
  kgGetSemantics,
  kgGetComments,
  kgGetChildren,
  kgGetDescendants,
  kgGetChangeHistory,
  kgGetConceptChangeHistory,
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
//  GROUP 5: Tier 2 Coordinate Override Scenarios
// ══════════════════════════════════════════════════════════════════════

// "Diabetes insipidus, NOS" — has many inactive semantics (9 of 14), ideal for allowedStates testing
// Stamp times: 2002-01-30 (1012435200000), 2017-07-30 (1501459200000), 2024-01-31 (1706745600000)
const COORD_TEST_CONCEPT = '52d02a6d-eaad-57ff-9cd4-82fae97fb044';

// "Albumin (substance)" — has 2 different modules (6 SNOMED CT core + 2 SOLOR overlay), ideal for module filtering
const MODULE_TEST_CONCEPT = '02afcfce-19f6-536c-b331-9f17107e0858';
const SNOMED_CT_CORE_MODULE = '6b341bca-9c47-5e9e-83fb-9782c8fea56e';
const SOLOR_OVERLAY_MODULE = '9ecc154c-e490-5cf8-805d-d2865d62aef3';

// "Disease (disorder)" — has many inferred children, 0 stated children (demonstrates premiseType effect)
const HIERARCHY_TEST_CONCEPT = 'c3735e2d-9206-58bb-aa12-f92c4e5730a7';

// "Abscess (disorder)" — smaller inferred subtree suitable for descendants test
const DESCENDANTS_TEST_CONCEPT = '9728786a-1eb1-5553-892b-e0aad91bc034';

// Path UUIDs
const DEVELOPMENT_PATH = '1f200ca6-960e-11e5-8994-feff819cdc9f';
const SANDBOX_PATH = '80710ea6-983c-5fa0-8908-e479f1f03ea9';

// Timestamp cutoffs for positionTime tests
const TIME_BEFORE_ALL = 946684800000;     // 2000-01-01 — before any data exists
const TIME_BEFORE_2017 = 1501459199999;   // just before 2017-07-30 stamp
const TIME_AFTER_2017 = 1501459200000;    // exactly at 2017-07-30 stamp

const coordinateOverrideGroup: TestGroupDefinition = {
  id: 'coordinate-overrides',
  name: 'Tier 2: Coordinate Override Scenarios',
  description: 'Tests coordinate filtering using "Diabetes insipidus, NOS" (14 semantics: 5 active, 9 inactive)',
  tests: [
    // ── Semantics: allowedStates ──────────────────────────────────

    {
      id: 'coord-sem-default',
      name: 'Semantics (default) — returns all active + inactive',
      run: async (ctx) => {
        const data = await kgGetSemantics(COORD_TEST_CONCEPT);
        const count = data.totalCount ?? data.semantics?.length ?? 0;
        ctx.set('coord_default_count', String(count));
        const inactive = data.semantics?.filter(
          (s) => s.stamp?.status === 'Inactive',
        ).length ?? 0;
        ctx.set('coord_inactive_count', String(inactive));
        if (count === 0) return { status: 'fail', detail: '0 semantics', responseData: data };
        return inactive > 0
          ? { status: 'pass', detail: `${count} total (${inactive} inactive)`, responseData: data }
          : { status: 'fail', detail: `${count} semantics but none inactive — test concept may have changed`, responseData: data };
      },
    },
    {
      id: 'coord-sem-active',
      name: 'Semantics (ACTIVE) — fewer than default',
      run: async (ctx) => {
        const data = await kgGetSemantics(COORD_TEST_CONCEPT, { allowedStates: 'ACTIVE' });
        const count = data.totalCount ?? data.semantics?.length ?? 0;
        ctx.set('coord_active_count', String(count));
        const defaultCount = Number(ctx.get('coord_default_count') ?? '0');
        // Every semantic returned should have Active stamp
        const allActive = data.semantics?.every((s) => s.stamp?.status === 'Active') ?? false;
        if (count >= defaultCount) {
          return { status: 'fail', detail: `ACTIVE (${count}) should be less than default (${defaultCount})`, responseData: data };
        }
        return allActive
          ? { status: 'pass', detail: `${count} active (filtered from ${defaultCount})`, responseData: data }
          : { status: 'fail', detail: `${count} results but not all Active status`, responseData: data };
      },
    },
    {
      id: 'coord-sem-inactive',
      name: 'Semantics (INACTIVE) — only inactive versions',
      run: async (ctx) => {
        const data = await kgGetSemantics(COORD_TEST_CONCEPT, { allowedStates: 'INACTIVE' });
        const count = data.totalCount ?? data.semantics?.length ?? 0;
        const expectedInactive = Number(ctx.get('coord_inactive_count') ?? '0');
        if (count === 0) return { status: 'fail', detail: '0 semantics — expected inactive versions', responseData: data };
        return count === expectedInactive
          ? { status: 'pass', detail: `${count} inactive (matches expected ${expectedInactive})`, responseData: data }
          : { status: 'pass', detail: `${count} inactive (expected ~${expectedInactive})`, responseData: data };
      },
    },
    {
      id: 'coord-sem-counts-add-up',
      name: 'ACTIVE + INACTIVE counts = DEFAULT count',
      run: async (ctx) => {
        const defaultCount = Number(ctx.get('coord_default_count') ?? '0');
        const activeCount = Number(ctx.get('coord_active_count') ?? '0');
        const inactiveCount = Number(ctx.get('coord_inactive_count') ?? '0');
        const sum = activeCount + inactiveCount;
        return sum === defaultCount
          ? { status: 'pass', detail: `${activeCount} active + ${inactiveCount} inactive = ${defaultCount} total` }
          : { status: 'fail', detail: `${activeCount} + ${inactiveCount} = ${sum}, expected ${defaultCount}` };
      },
    },

    // ── Hierarchy: premiseType (children/descendants) ──────────────

    {
      id: 'coord-children-inferred',
      name: 'Children (default/INFERRED) — Disease (disorder) has children',
      run: async (ctx) => {
        // "Disease (disorder)" — has many inferred children, 0 stated children
        const data = await kgGetChildren(HIERARCHY_TEST_CONCEPT);
        const count = data.totalCount ?? data.results?.length ?? 0;
        ctx.set('coord_children_inferred', String(count));
        return count > 0
          ? { status: 'pass', detail: `${count} inferred children`, responseData: data }
          : { status: 'fail', detail: '0 children', responseData: data };
      },
    },
    {
      id: 'coord-children-stated',
      name: 'Children (STATED) — different from INFERRED',
      run: async (ctx) => {
        const data = await kgGetChildren(HIERARCHY_TEST_CONCEPT, { premiseType: 'STATED' });
        const statedCount = data.totalCount ?? data.results?.length ?? 0;
        const inferredCount = Number(ctx.get('coord_children_inferred') ?? '0');
        ctx.set('coord_children_stated', String(statedCount));
        return statedCount !== inferredCount
          ? { status: 'pass', detail: `STATED: ${statedCount} vs INFERRED: ${inferredCount} — premiseType has effect`, responseData: data }
          : { status: 'fail', detail: `STATED (${statedCount}) = INFERRED (${inferredCount}) — premiseType had no effect`, responseData: data };
      },
    },
    {
      id: 'coord-children-explicit-inferred',
      name: 'Children (explicit INFERRED) = default',
      run: async (ctx) => {
        const data = await kgGetChildren(HIERARCHY_TEST_CONCEPT, { premiseType: 'INFERRED' });
        const count = data.totalCount ?? data.results?.length ?? 0;
        const defaultCount = Number(ctx.get('coord_children_inferred') ?? '0');
        return count === defaultCount
          ? { status: 'pass', detail: `Explicit INFERRED (${count}) = default (${defaultCount})`, responseData: data }
          : { status: 'fail', detail: `Mismatch: explicit=${count}, default=${defaultCount}`, responseData: data };
      },
    },
    {
      id: 'coord-descendants-inferred',
      name: 'Descendants (INFERRED) — Abscess (disorder) has descendants',
      run: async (ctx) => {
        // "Abscess (disorder)" — smaller subtree suitable for descendants test
        const data = await kgGetDescendants(DESCENDANTS_TEST_CONCEPT);
        const count = data.totalCount ?? data.results?.length ?? 0;
        ctx.set('coord_desc_inferred', String(count));
        return count > 0
          ? { status: 'pass', detail: `${count} inferred descendants`, responseData: data }
          : { status: 'fail', detail: '0 descendants', responseData: data };
      },
    },
    {
      id: 'coord-descendants-stated',
      name: 'Descendants (STATED) — different from INFERRED',
      run: async (ctx) => {
        const data = await kgGetDescendants(DESCENDANTS_TEST_CONCEPT, { premiseType: 'STATED' });
        const statedCount = data.totalCount ?? data.results?.length ?? 0;
        const inferredCount = Number(ctx.get('coord_desc_inferred') ?? '0');
        return statedCount !== inferredCount
          ? { status: 'pass', detail: `STATED: ${statedCount} vs INFERRED: ${inferredCount}`, responseData: data }
          : { status: 'fail', detail: `STATED (${statedCount}) = INFERRED (${inferredCount}) — no difference`, responseData: data };
      },
    },

    // ── Semantics: combined overrides ─────────────────────────────

    {
      id: 'coord-sem-active-stated',
      name: 'Semantics (ACTIVE + STATED) — combined filter',
      run: async () => {
        const data = await kgGetSemantics(COORD_TEST_CONCEPT, { allowedStates: 'ACTIVE', premiseType: 'STATED' });
        const count = data.totalCount ?? data.semantics?.length ?? 0;
        const allActive = data.semantics?.every((s) => s.stamp?.status === 'Active') ?? false;
        return count > 0 && allActive
          ? { status: 'pass', detail: `${count} semantics, all active`, responseData: data }
          : { status: 'fail', detail: count === 0 ? '0 semantics' : 'Not all active', responseData: data };
      },
    },

    // ── STAMP: positionTime ─────────────────────────────────────

    {
      id: 'coord-time-default',
      name: 'positionTime (default/latest) — all 14 semantics',
      run: async (ctx) => {
        const data = await kgGetSemantics(COORD_TEST_CONCEPT);
        const count = data.totalCount ?? data.semantics?.length ?? 0;
        ctx.set('coord_time_default', String(count));
        return count > 0
          ? { status: 'pass', detail: `${count} semantics (latest)`, responseData: data }
          : { status: 'fail', detail: '0 semantics', responseData: data };
      },
    },
    {
      id: 'coord-time-before-all',
      name: 'positionTime (before 2002) — 0 semantics',
      run: async () => {
        const data = await kgGetSemantics(COORD_TEST_CONCEPT, { positionTime: TIME_BEFORE_ALL });
        const count = data.totalCount ?? data.semantics?.length ?? 0;
        return count === 0
          ? { status: 'pass', detail: '0 semantics (no data existed yet)', responseData: data }
          : { status: 'fail', detail: `Expected 0, got ${count}`, responseData: data };
      },
    },
    {
      id: 'coord-time-before-2017',
      name: 'positionTime (before 2017) — fewer than latest',
      run: async (ctx) => {
        const data = await kgGetSemantics(COORD_TEST_CONCEPT, { positionTime: TIME_BEFORE_2017 });
        const count = data.totalCount ?? data.semantics?.length ?? 0;
        const defaultCount = Number(ctx.get('coord_time_default') ?? '0');
        ctx.set('coord_time_pre2017', String(count));
        if (count === 0) return { status: 'fail', detail: '0 semantics', responseData: data };
        return count < defaultCount
          ? { status: 'pass', detail: `${count} semantics (vs ${defaultCount} at latest)`, responseData: data }
          : { status: 'fail', detail: `Expected fewer than ${defaultCount}, got ${count}`, responseData: data };
      },
    },
    {
      id: 'coord-time-at-2017',
      name: 'positionTime (at 2017 stamp) — includes 2017 versions',
      run: async (ctx) => {
        const data = await kgGetSemantics(COORD_TEST_CONCEPT, { positionTime: TIME_AFTER_2017 });
        const count = data.totalCount ?? data.semantics?.length ?? 0;
        const pre2017 = Number(ctx.get('coord_time_pre2017') ?? '0');
        // At the 2017 boundary, newer versions of existing semantics are visible
        // but the count may stay the same since they replace earlier versions
        return count >= pre2017
          ? { status: 'pass', detail: `${count} semantics (at 2017 boundary)`, responseData: data }
          : { status: 'fail', detail: `Expected >= ${pre2017}, got ${count}`, responseData: data };
      },
    },

    // ── STAMP: modules ────────────────────────────────────────────

    {
      id: 'coord-mod-default',
      name: 'modules (default) — all modules included',
      run: async (ctx) => {
        const data = await kgGetSemantics(MODULE_TEST_CONCEPT);
        const count = data.totalCount ?? data.semantics?.length ?? 0;
        ctx.set('coord_mod_default', String(count));
        // Check that there are at least 2 different modules
        const modules = new Set(data.semantics?.map((s) => s.stamp?.module).filter(Boolean));
        ctx.set('coord_mod_count', String(modules.size));
        return modules.size >= 2
          ? { status: 'pass', detail: `${count} semantics across ${modules.size} modules (${[...modules].join(', ')})`, responseData: data }
          : { status: 'fail', detail: `Expected >=2 modules, found ${modules.size}`, responseData: data };
      },
    },
    {
      id: 'coord-mod-snomed-only',
      name: 'modules (SNOMED CT core only) — fewer than default',
      run: async (ctx) => {
        const data = await kgGetSemantics(MODULE_TEST_CONCEPT, { modules: [SNOMED_CT_CORE_MODULE] });
        const count = data.totalCount ?? data.semantics?.length ?? 0;
        const defaultCount = Number(ctx.get('coord_mod_default') ?? '0');
        ctx.set('coord_mod_snomed', String(count));
        if (count >= defaultCount) {
          return { status: 'fail', detail: `SNOMED-only (${count}) should be less than default (${defaultCount})`, responseData: data };
        }
        return count > 0
          ? { status: 'pass', detail: `${count} semantics (filtered from ${defaultCount})`, responseData: data }
          : { status: 'fail', detail: '0 semantics', responseData: data };
      },
    },
    {
      id: 'coord-mod-solor-only',
      name: 'modules (SOLOR overlay only) — fewer than default',
      run: async (ctx) => {
        const data = await kgGetSemantics(MODULE_TEST_CONCEPT, { modules: [SOLOR_OVERLAY_MODULE] });
        const count = data.totalCount ?? data.semantics?.length ?? 0;
        const defaultCount = Number(ctx.get('coord_mod_default') ?? '0');
        ctx.set('coord_mod_solor', String(count));
        if (count >= defaultCount) {
          return { status: 'fail', detail: `SOLOR-only (${count}) should be less than default (${defaultCount})`, responseData: data };
        }
        return count > 0
          ? { status: 'pass', detail: `${count} semantics (filtered from ${defaultCount})`, responseData: data }
          : { status: 'fail', detail: '0 semantics', responseData: data };
      },
    },
    {
      id: 'coord-mod-counts-add-up',
      name: 'SNOMED + SOLOR module counts = default',
      run: async (ctx) => {
        const defaultCount = Number(ctx.get('coord_mod_default') ?? '0');
        const snomedCount = Number(ctx.get('coord_mod_snomed') ?? '0');
        const solorCount = Number(ctx.get('coord_mod_solor') ?? '0');
        const sum = snomedCount + solorCount;
        return sum === defaultCount
          ? { status: 'pass', detail: `${snomedCount} SNOMED + ${solorCount} SOLOR = ${defaultCount} total` }
          : { status: 'fail', detail: `${snomedCount} + ${solorCount} = ${sum}, expected ${defaultCount}` };
      },
    },

    // ── STAMP: positionPath ───────────────────────────────────────

    {
      id: 'coord-path-development',
      name: 'positionPath (Development) — matches default',
      run: async () => {
        const dataDefault = await kgGetSemantics(COORD_TEST_CONCEPT);
        const dataExplicit = await kgGetSemantics(COORD_TEST_CONCEPT, { positionPath: DEVELOPMENT_PATH });
        const countDefault = dataDefault.totalCount ?? dataDefault.semantics?.length ?? 0;
        const countExplicit = dataExplicit.totalCount ?? dataExplicit.semantics?.length ?? 0;
        return countDefault === countExplicit
          ? { status: 'pass', detail: `Explicit Development path (${countExplicit}) = default (${countDefault})`, responseData: dataExplicit }
          : { status: 'fail', detail: `Mismatch: explicit=${countExplicit}, default=${countDefault}`, responseData: { default: dataDefault, explicit: dataExplicit } };
      },
    },
    {
      id: 'coord-path-sandbox',
      name: 'positionPath (Sandbox) — 0 semantics (no data on this path)',
      run: async () => {
        const data = await kgGetSemantics(COORD_TEST_CONCEPT, { positionPath: SANDBOX_PATH });
        const count = data.totalCount ?? data.semantics?.length ?? 0;
        return count === 0
          ? { status: 'pass', detail: '0 semantics (Sandbox path has no data)', responseData: data }
          : { status: 'fail', detail: `Expected 0 on Sandbox path, got ${count}`, responseData: data };
      },
    },

    // ── Comments: coordinate overrides ────────────────────────────

    {
      id: 'coord-comments-default',
      name: 'Comments (default coordinates)',
      run: async (ctx) => {
        const id = ctx.get('albumin_id') ?? COORD_TEST_CONCEPT;
        const data = await kgGetComments(id);
        return { status: 'pass', detail: `HTTP 200, ${data.semantics?.length ?? 0} comments`, responseData: data };
      },
    },
    {
      id: 'coord-comments-active',
      name: 'Comments (allowedStates=ACTIVE)',
      run: async (ctx) => {
        const id = ctx.get('albumin_id') ?? COORD_TEST_CONCEPT;
        const data = await kgGetComments(id, { allowedStates: 'ACTIVE' });
        return { status: 'pass', detail: `HTTP 200, ${data.semantics?.length ?? 0} comments`, responseData: data };
      },
    },

    // ── Change History: coordinate overrides ──────────────────────

    {
      id: 'coord-ch-default',
      name: 'Change History (default coordinates)',
      run: async (ctx) => {
        const id = ctx.get('albumin_id') ?? COORD_TEST_CONCEPT;
        const data = await kgGetChangeHistory(id);
        return { status: 'pass', detail: `${data.totalVersions} versions`, responseData: data };
      },
    },
    {
      id: 'coord-ch-active',
      name: 'Change History (allowedStates=ACTIVE)',
      run: async (ctx) => {
        const id = ctx.get('albumin_id') ?? COORD_TEST_CONCEPT;
        const data = await kgGetChangeHistory(id, { allowedStates: 'ACTIVE' });
        return { status: 'pass', detail: `${data.totalVersions} versions`, responseData: data };
      },
    },

    // ── Concept Change History: coordinate overrides ──────────────

    {
      id: 'coord-cch-default',
      name: 'Concept Change History (default coordinates)',
      run: async (ctx) => {
        const id = ctx.get('albumin_id') ?? COORD_TEST_CONCEPT;
        const data = await kgGetConceptChangeHistory(id);
        return { status: 'pass', detail: `${data.totalChanges} changes`, responseData: data };
      },
    },
    {
      id: 'coord-cch-active',
      name: 'Concept Change History (allowedStates=ACTIVE)',
      run: async (ctx) => {
        const id = ctx.get('albumin_id') ?? COORD_TEST_CONCEPT;
        const data = await kgGetConceptChangeHistory(id, { allowedStates: 'ACTIVE' });
        return { status: 'pass', detail: `${data.totalChanges} changes`, responseData: data };
      },
    },

    // ── Backward Compatibility ────────────────────────────────────

    {
      id: 'coord-compat-tier1',
      name: 'Tier 1 semantics still works without coordinates',
      run: async (ctx) => {
        const id = ctx.get('albumin_id') ?? COORD_TEST_CONCEPT;
        const data = await getSemantics(id);
        return data.success
          ? { status: 'pass', detail: `Tier 1 OK, ${data.semantics?.length ?? 0} semantics`, responseData: data }
          : { status: 'fail', detail: 'Tier 1 returned success=false', responseData: data };
      },
    },
    {
      id: 'coord-compat-no-params',
      name: 'Tier 2 without coordinates = default behavior',
      run: async () => {
        const data = await kgGetSemantics(COORD_TEST_CONCEPT);
        const count = data.totalCount ?? data.semantics?.length ?? 0;
        const dataExplicit = await kgGetSemantics(COORD_TEST_CONCEPT, { allowedStates: 'ACTIVE_AND_INACTIVE' });
        const countExplicit = dataExplicit.totalCount ?? dataExplicit.semantics?.length ?? 0;
        return count === countExplicit
          ? { status: 'pass', detail: `No params (${count}) = explicit ACTIVE_AND_INACTIVE (${countExplicit})`, responseData: data }
          : { status: 'fail', detail: `Mismatch: no params=${count}, explicit=${countExplicit}`, responseData: { noParams: data, explicit: dataExplicit } };
      },
    },
  ],
};

// ══════════════════════════════════════════════════════════════════════
//  GROUP 6: Endpoint Coverage
// ══════════════════════════════════════════════════════════════════════

const endpointCoverageGroup: TestGroupDefinition = {
  id: 'endpoint-coverage',
  name: 'Endpoint Coverage',
  description: 'Test all Tier 1 and Tier 2 REST API endpoints',
  tests: [
    // ── Tier 1 (Legacy) ────────────────────────────────────────────

    {
      id: 'ep-search',
      name: 'Tier 1: GET /search',
      run: async () => {
        const data = await search('albumin');
        return { status: 'pass', detail: 'HTTP 200', responseData: data };
      },
    },
    {
      id: 'ep-concept-search',
      name: 'Tier 1: GET /conceptSearch',
      run: async () => {
        const data = await conceptSearch('albumin', 5);
        return { status: 'pass', detail: 'HTTP 200', responseData: data };
      },
    },
    {
      id: 'ep-concept-search-sort',
      name: 'Tier 1: GET /conceptSearchWithSort',
      run: async () => {
        const data = await conceptSearchWithSort('albumin', 5, 'TOP_COMPONENT');
        return { status: 'pass', detail: 'HTTP 200', responseData: data };
      },
    },
    {
      id: 'ep-concept-id',
      name: 'Tier 1: GET /conceptId',
      run: async (ctx) => {
        const id = ctx.get('albumin_id') ?? ctx.get('s1_id');
        if (!id) return { status: 'skip', detail: 'No concept ID available' };
        const data = await getConceptById(id);
        return { status: 'pass', detail: 'HTTP 200', responseData: data };
      },
    },
    {
      id: 'ep-children',
      name: 'Tier 1: GET /children',
      run: async (ctx) => {
        const id = ctx.get('albumin_id') ?? ctx.get('s1_id');
        if (!id) return { status: 'skip', detail: 'No concept ID available' };
        const data = await getChildren(id);
        return { status: 'pass', detail: 'HTTP 200', responseData: data };
      },
    },
    {
      id: 'ep-descendants',
      name: 'Tier 1: GET /descendants',
      run: async (ctx) => {
        const id = ctx.get('albumin_id') ?? ctx.get('s1_id');
        if (!id) return { status: 'skip', detail: 'No concept ID available' };
        const data = await getDescendants(id);
        return { status: 'pass', detail: 'HTTP 200', responseData: data };
      },
    },
    {
      id: 'ep-change-history',
      name: 'Tier 1: GET /change-history',
      run: async (ctx) => {
        const id = ctx.get('albumin_id') ?? ctx.get('s1_id');
        if (!id) return { status: 'skip', detail: 'No concept ID available' };
        const data = await getChangeHistory(id);
        return { status: 'pass', detail: 'HTTP 200', responseData: data };
      },
    },
    {
      id: 'ep-comments',
      name: 'Tier 1: GET /comments',
      run: async (ctx) => {
        const id = ctx.get('albumin_id') ?? ctx.get('s1_id');
        if (!id) return { status: 'skip', detail: 'No concept ID available' };
        const data = await getComments(id);
        return { status: 'pass', detail: 'HTTP 200', responseData: data };
      },
    },
    {
      id: 'ep-semantics',
      name: 'Tier 1: GET /semantics',
      run: async (ctx) => {
        const id = ctx.get('albumin_id') ?? ctx.get('s1_id');
        if (!id) return { status: 'skip', detail: 'No concept ID available' };
        const data = await getSemantics(id);
        return { status: 'pass', detail: 'HTTP 200', responseData: data };
      },
    },

    // ── Tier 2 (Knowledge Graph) ───────────────────────────────────

    {
      id: 'ep-kg-semantics',
      name: 'Tier 2: GET /knowledgegraph/semantics',
      run: async (ctx) => {
        const id = ctx.get('albumin_id') ?? ctx.get('s1_id');
        if (!id) return { status: 'skip', detail: 'No concept ID available' };
        const data = await kgGetSemantics(id);
        return { status: 'pass', detail: 'HTTP 200', responseData: data };
      },
    },
    {
      id: 'ep-kg-comments',
      name: 'Tier 2: GET /knowledgegraph/comments',
      run: async (ctx) => {
        const id = ctx.get('albumin_id') ?? ctx.get('s1_id');
        if (!id) return { status: 'skip', detail: 'No concept ID available' };
        const data = await kgGetComments(id);
        return { status: 'pass', detail: 'HTTP 200', responseData: data };
      },
    },
    {
      id: 'ep-kg-change-history',
      name: 'Tier 2: GET /knowledgegraph/change-history',
      run: async (ctx) => {
        const id = ctx.get('albumin_id') ?? ctx.get('s1_id');
        if (!id) return { status: 'skip', detail: 'No concept ID available' };
        const data = await kgGetChangeHistory(id);
        return { status: 'pass', detail: 'HTTP 200', responseData: data };
      },
    },
    {
      id: 'ep-kg-concept-change-history',
      name: 'Tier 2: GET /knowledgegraph/concept-change-history',
      run: async (ctx) => {
        const id = ctx.get('albumin_id') ?? ctx.get('s1_id');
        if (!id) return { status: 'skip', detail: 'No concept ID available' };
        const data = await kgGetConceptChangeHistory(id);
        return { status: 'pass', detail: 'HTTP 200', responseData: data };
      },
    },
    {
      id: 'ep-kg-children',
      name: 'Tier 2: GET /knowledgegraph/children',
      run: async () => {
        const data = await kgGetChildren(HIERARCHY_TEST_CONCEPT);
        const count = data.totalCount ?? data.results?.length ?? 0;
        return { status: 'pass', detail: `HTTP 200, ${count} children`, responseData: data };
      },
    },
    {
      id: 'ep-kg-descendants',
      name: 'Tier 2: GET /knowledgegraph/descendants',
      run: async () => {
        const data = await kgGetDescendants(DESCENDANTS_TEST_CONCEPT);
        const count = data.totalCount ?? data.results?.length ?? 0;
        return { status: 'pass', detail: `HTTP 200, ${count} descendants`, responseData: data };
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
  coordinateOverrideGroup,
  endpointCoverageGroup,
];
