import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import type {
  ConceptSemanticsResponse,
  SemanticInfo,
  VersionChange,
  SemanticChangeHistory,
} from '../api/types';
import { kgGetChildren, kgGetConceptChangeHistory } from '../api/tinkarApi';

type ConceptDetailTab = 'descriptions' | 'general' | 'axioms' | 'hierarchy' | 'history';

// ── Pattern classification ───────────────────────────────────────────

function isAxiomPattern(name: string | null | undefined): boolean {
  const lower = (name ?? '').toLowerCase();
  return lower.includes('axiom') || lower.includes('el++') || lower.includes('terminological');
}

function isNavigationPattern(name: string | null | undefined): boolean {
  return (name ?? '').toLowerCase().includes('navigation');
}

// ── Shared sub-components ────────────────────────────────────────────

/** Description Pattern field positions, in the order the pattern declares them. */
const DESCRIPTION_FIELD = { LANGUAGE: 0, TEXT: 1, CASE_SIGNIFICANCE: 2, TYPE: 3 } as const;

/**
 * Navigation Pattern field positions: destination first, then origin
 * (`Lists.immutable.of(destinationSet, originSet)` service-side). Origin holds the parents.
 */
const NAVIGATION_FIELD = { DESTINATION: 0, ORIGIN: 1 } as const;

function isDescriptionPattern(name: string | null | undefined): boolean {
  return (name ?? '').toLowerCase().includes('description pattern');
}

function fieldAt(semantic: SemanticInfo, index: number): string {
  return semantic.fields.find((f) => f.index === index)?.value ?? '';
}

/**
 * Descriptions grouped the way Komet's concept view groups them — definition, fully qualified
 * names, then other names — with each entry annotated by case significance and language.
 */
function DescriptionsTab({ semantics }: { semantics: SemanticInfo[] }) {
  const descriptions = semantics.filter((s) => isDescriptionPattern(s.patternName));

  if (descriptions.length === 0) {
    return <p className="no-results">No descriptions found for this concept.</p>;
  }

  const byType = (match: string) =>
    descriptions.filter((s) =>
      fieldAt(s, DESCRIPTION_FIELD.TYPE).toLowerCase().includes(match)
    );

  const definitions = byType('definition');
  const fullyQualified = byType('fully qualified');
  // Whatever is left is an "other name" — usually the regular name description type.
  const otherNames = descriptions.filter(
    (s) => !definitions.includes(s) && !fullyQualified.includes(s)
  );

  const section = (label: string, entries: SemanticInfo[]) =>
    entries.length === 0 ? null : (
      <div className="description-group" key={label}>
        <h3 className="pattern-group-header">
          {label}
          <span className="pattern-count">({entries.length})</span>
        </h3>
        {entries.map((entry) => (
          <div key={entry.semanticId} className="description-entry">
            <div className="description-text">{fieldAt(entry, DESCRIPTION_FIELD.TEXT)}</div>
            <div className="description-meta">
              {fieldAt(entry, DESCRIPTION_FIELD.CASE_SIGNIFICANCE)}
              {' · '}
              {fieldAt(entry, DESCRIPTION_FIELD.LANGUAGE)}
              {entry.stamp?.formattedTime ? ` · Added ${entry.stamp.formattedTime.slice(0, 10)}` : ''}
            </div>
          </div>
        ))}
      </div>
    );

  return (
    <div className="semantics-groups">
      {section('Definition', definitions)}
      {section('Fully qualified names', fullyQualified)}
      {section('Other names', otherNames)}
    </div>
  );
}

function SemanticCard({ semantic }: { semantic: SemanticInfo }) {
  const [showStamp, setShowStamp] = useState(false);

  return (
    <div className="semantic-card">
      <div className="semantic-card-header">
        <h4 className="semantic-pattern-name">{semantic.patternName}</h4>
        <span className="semantic-id">{semantic.semanticId}</span>
      </div>

      <div className="semantic-fields">
        {semantic.fields.map((field) => (
          <div key={field.index} className="semantic-field">
            {/* The service sends field position, not name — the pattern is what gives each
                position its meaning, and only some patterns are known here by name. */}
            <span className="field-name">Field {field.index}:</span>
            <span className="field-value">
              {field.value || <em className="empty-value">(empty)</em>}
            </span>
          </div>
        ))}
      </div>

      <button className="stamp-toggle" onClick={() => setShowStamp(!showStamp)}>
        {showStamp ? '▼' : '▶'} Stamp Info
      </button>

      {showStamp && (
        <div className="semantic-stamp">
          {[
            { label: 'Status', value: semantic.stamp.status },
            { label: 'Time',   value: semantic.stamp.formattedTime },
            { label: 'Author', value: semantic.stamp.author },
            { label: 'Module', value: semantic.stamp.module },
            { label: 'Path',   value: semantic.stamp.path   },
          ].map(({ label, value }) => (
            <div key={label} className="stamp-row">
              <span className="stamp-label">{label}:</span>
              <span className="stamp-value">{value}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function SemanticPatternGroups({ semantics }: { semantics: SemanticInfo[] }) {
  const grouped = semantics.reduce((acc, s) => {
    (acc[s.patternName] ??= []).push(s);
    return acc;
  }, {} as Record<string, SemanticInfo[]>);

  const patternNames = Object.keys(grouped).sort();

  return (
    <div className="semantics-groups">
      {patternNames.map((name) => (
        <div key={name} className="semantics-pattern-group">
          <h3 className="pattern-group-header">
            {name}
            <span className="pattern-count">({grouped[name].length})</span>
          </h3>
          <div className="semantics-list">
            {grouped[name].map((s) => (
              <SemanticCard key={s.semanticId} semantic={s} />
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}

// ── Tab: General ─────────────────────────────────────────────────────

function GeneralTab({ semantics }: { semantics: SemanticInfo[] }) {
  // Descriptions and axioms have tabs of their own; navigation is shown under Hierarchy.
  const filtered = semantics.filter(
    (s) =>
      !isAxiomPattern(s.patternName) &&
      !isNavigationPattern(s.patternName) &&
      !isDescriptionPattern(s.patternName),
  );

  if (filtered.length === 0) {
    return <p className="no-results">No general semantics found for this concept.</p>;
  }

  return <SemanticPatternGroups semantics={filtered} />;
}

// ── Tab: Axioms ──────────────────────────────────────────────────────

function AxiomsTab({ semantics }: { semantics: SemanticInfo[] }) {
  const filtered = semantics.filter((s) => isAxiomPattern(s.patternName));

  if (filtered.length === 0) {
    return <p className="no-results">No axiom semantics found for this concept.</p>;
  }

  return <SemanticPatternGroups semantics={filtered} />;
}

// ── Tab: Hierarchy ───────────────────────────────────────────────────

function HierarchyTab({ conceptId, semantics }: { conceptId: string; semantics: SemanticInfo[] }) {
  const { data: childrenData, isLoading: childrenLoading } = useQuery({
    queryKey: ['children', conceptId],
    queryFn: () => kgGetChildren(conceptId),
  });

  // Navigation semantics hold parent (Relationship Origin) and child
  // (Relationship Destination) references as field values.
  const navSemantics = semantics.filter((s) => isNavigationPattern(s.patternName));

  // Pull out only the "origin" fields for the parents section.
  const parentNavSemantics = navSemantics
    .map((s) => ({
      ...s,
      // By position, not name: the service sends field indexes, so the previous name-based
      // filter matched nothing and this section was always empty.
      fields: (s.fields ?? []).filter((f) => f.index === NAVIGATION_FIELD.ORIGIN),
    }))
    .filter((s) => s.fields.length > 0);

  return (
    <div className="hierarchy-tab">
      {/* Parents */}
      <div className="hierarchy-section">
        <h3 className="hierarchy-section-title">Parents</h3>
        {parentNavSemantics.length === 0 ? (
          <p className="no-results">No parent relationships found.</p>
        ) : (
          <div className="hierarchy-nav-list">
            {parentNavSemantics.map((s) => (
              <div key={s.semanticId} className="hierarchy-nav-semantic">
                <div className="hierarchy-nav-pattern-label">{s.patternName}</div>
                {s.fields.map((f, i) => (
                  <div key={i} className="hierarchy-nav-field">
                    <span className="hierarchy-nav-field-label">
                      {f.index === NAVIGATION_FIELD.ORIGIN ? 'Parents' : 'Children'}:
                    </span>
                    <span className="hierarchy-nav-field-value">{f.value || '(none)'}</span>
                  </div>
                ))}
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Children */}
      <div className="hierarchy-section">
        <h3 className="hierarchy-section-title">
          Children
          {childrenData?.success && ` (${childrenData.totalCount})`}
        </h3>

        {childrenLoading && <p className="loading">Loading children…</p>}

        {childrenData && !childrenData.success && (
          <p className="error-message">{childrenData.errorMessage || 'Failed to load children.'}</p>
        )}

        {childrenData?.success && (childrenData.results?.length ?? 0) === 0 && (
          <p className="no-results">No children found for this concept.</p>
        )}

        {childrenData?.success && (childrenData.results?.length ?? 0) > 0 && (
          <ul className="hierarchy-children-list">
            {[...(childrenData.results ?? [])]
              .sort((a, b) =>
                a.descriptions.fullyQualifiedName.localeCompare(b.descriptions.fullyQualifiedName),
              )
              .map((child, i) => (
                <li key={i} className="hierarchy-child-item">
                  <span className="hierarchy-child-name">
                    {child.descriptions.fullyQualifiedName}
                  </span>
                  {child.descriptions.regularName &&
                    child.descriptions.regularName !== child.descriptions.fullyQualifiedName && (
                      <span className="hierarchy-child-regular">
                        {' '}({child.descriptions.regularName})
                      </span>
                    )}
                </li>
              ))}
          </ul>
        )}
      </div>
    </div>
  );
}

// ── Tab: History ─────────────────────────────────────────────────────

function VersionChangeEntry({ change }: { change: VersionChange }) {
  const [expanded, setExpanded] = useState(false);
  const { stamp, fieldChanges } = change;
  const timeDisplay =
    stamp.formattedTime || (stamp.time ? new Date(stamp.time).toLocaleString() : 'Unknown time');
  const statusClass = stamp.status?.toLowerCase().replace(/\s+/g, '-') ?? 'unknown';

  return (
    <div className="version-entry">
      <button className="version-entry-header" onClick={() => setExpanded(!expanded)}>
        <span className={`version-status-badge status-${statusClass}`}>{stamp.status}</span>
        <span className="version-time">{timeDisplay}</span>
        <span className="version-author">{stamp.author}</span>
        <span className="version-chevron">{expanded ? '▼' : '▶'}</span>
      </button>

      {expanded && (
        <div className="version-entry-details">
          <div className="version-detail-row">
            <span className="version-detail-label">Module:</span>
            <span>{stamp.module}</span>
          </div>
          <div className="version-detail-row">
            <span className="version-detail-label">Path:</span>
            <span>{stamp.path}</span>
          </div>

          {fieldChanges && fieldChanges.length > 0 && (
            <div className="version-field-changes">
              <div className="version-field-changes-label">Field Changes</div>
              {fieldChanges.map((fc, i) => (
                <div
                  key={i}
                  className={`field-change-entry change-type-${fc.changeType?.toLowerCase()}`}
                >
                  <span className="field-change-badge">{fc.changeType}</span>
                  <span className="field-change-name">{fc.fieldName}</span>
                  {fc.priorValue != null && (
                    <span className="field-change-prior">← {fc.priorValue}</span>
                  )}
                  <span className="field-change-current">→ {fc.currentValue}</span>
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function SemanticHistorySection({ sc }: { sc: SemanticChangeHistory }) {
  const [collapsed, setCollapsed] = useState(false);

  return (
    <div className="history-section">
      <button className="history-section-title" onClick={() => setCollapsed(!collapsed)}>
        <span>{sc.patternName}</span>
        {sc.summary && <span className="history-section-summary"> — {sc.summary}</span>}
        <span className="history-section-chevron">{collapsed ? '▶' : '▼'}</span>
      </button>
      {!collapsed &&
        sc.versionChanges.map((vc, j) => <VersionChangeEntry key={j} change={vc} />)}
    </div>
  );
}

function HistoryTab({ conceptId }: { conceptId: string }) {
  const { data, isLoading, isError } = useQuery({
    queryKey: ['conceptChangeHistory', conceptId],
    queryFn: () => kgGetConceptChangeHistory(conceptId),
  });

  if (isLoading) return <p className="loading">Loading change history…</p>;
  if (isError)   return <p className="error-message">Failed to load change history.</p>;
  if (!data)     return null;
  if (!data.success) {
    return <p className="error-message">{data.errorMessage || 'Failed to load change history.'}</p>;
  }

  const hasConceptChanges  = data.conceptChanges  && data.conceptChanges.length  > 0;
  const hasSemanticChanges = data.semanticChanges && data.semanticChanges.length > 0;

  if (!hasConceptChanges && !hasSemanticChanges) {
    return <p className="no-results">No change history found for this concept.</p>;
  }

  return (
    <div className="history-tab">
      {hasConceptChanges && (
        <div className="history-section">
          <div className="history-section-title">
            <span>Concept Versions</span>
          </div>
          {data.conceptChanges.map((vc, i) => (
            <VersionChangeEntry key={i} change={vc} />
          ))}
        </div>
      )}

      {hasSemanticChanges &&
        data.semanticChanges.map((sc, i) => <SemanticHistorySection key={i} sc={sc} />)}
    </div>
  );
}

// ── Main export ──────────────────────────────────────────────────────

interface SemanticsViewProps {
  data: ConceptSemanticsResponse;
  conceptName: string;
  conceptId: string;
  onBack: () => void;
}

export function SemanticsView({ data, conceptName, conceptId, onBack }: SemanticsViewProps) {
  const [activeTab, setActiveTab] = useState<ConceptDetailTab>('descriptions');

  const axiomCount = data.semantics.filter((s) => isAxiomPattern(s.patternName)).length;
  const descriptionCount = data.semantics.filter((s) => isDescriptionPattern(s.patternName)).length;

  const tabs: { id: ConceptDetailTab; label: string; badge?: number }[] = [
    { id: 'descriptions', label: 'Descriptions', badge: descriptionCount },
    { id: 'general',   label: 'General'   },
    { id: 'axioms',    label: 'Axioms',    badge: axiomCount },
    { id: 'hierarchy', label: 'Hierarchy' },
    { id: 'history',   label: 'History'   },
  ];

  return (
    <div className="semantics-view">
      <button className="back-button" onClick={onBack}>
        &larr; Back to search results
      </button>

      <div className="concept-detail-header">
        <div className="concept-identity">
          <h2 className="results-title">{conceptName}</h2>
          <p className="concept-id-display">ID: {data.conceptId}</p>
        </div>
        {data.conceptStamp && (
          <dl className="concept-stamp">
            {[
              { label: 'Status', value: data.conceptStamp.status },
              { label: 'Last Updated', value: data.conceptStamp.formattedTime },
              { label: 'Author', value: data.conceptStamp.author },
              { label: 'Module', value: data.conceptStamp.module },
              { label: 'Path', value: data.conceptStamp.path },
            ].map(({ label, value }) => (
              <div className="concept-stamp-row" key={label}>
                <dt>{label}</dt>
                <dd>{value ?? '—'}</dd>
              </div>
            ))}
          </dl>
        )}
      </div>

      <div className="detail-tab-bar">
        {tabs.map((tab) => (
          <button
            key={tab.id}
            className={`detail-tab-button${activeTab === tab.id ? ' detail-tab-active' : ''}`}
            onClick={() => setActiveTab(tab.id)}
          >
            {tab.label}
            {tab.badge !== undefined && tab.badge > 0 && (
              <span className="tab-count-badge">{tab.badge}</span>
            )}
          </button>
        ))}
      </div>

      <div className="detail-tab-content">
        {activeTab === 'descriptions' && <DescriptionsTab semantics={data.semantics} />}
        {activeTab === 'general'   && <GeneralTab   semantics={data.semantics} />}
        {activeTab === 'axioms'    && <AxiomsTab    semantics={data.semantics} />}
        {activeTab === 'hierarchy' && <HierarchyTab conceptId={conceptId} semantics={data.semantics} />}
        {activeTab === 'history'   && <HistoryTab   conceptId={conceptId} />}
      </div>
    </div>
  );
}
