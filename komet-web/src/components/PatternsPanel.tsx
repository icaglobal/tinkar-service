import { useMemo, useState } from 'react';
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query';
import { listPatternMembers, listPatterns } from '../api/tinkarApi';
import type { PatternMember, PatternSummary } from '../api/types';
import { NewPatternForm } from './NewPatternForm';

interface PatternsPanelProps {
  /** Opens a member's referenced component in the semantics view. */
  onViewSemantics: (conceptId: string, conceptName?: string) => void;
}

const PAGE_SIZES = [25, 50, 100, 250];

/** The filter applied to the member list: by the referenced component's name, or by its UUID. */
type MemberFilter = { kind: 'none' } | { kind: 'text'; text: string } | { kind: 'id'; id: string };

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * The knowledge base's patterns and their members, as Komet's Patterns tab and Pattern window
 * show them.
 *
 * The list on the left holds every pattern. Selecting one shows its definition (what its
 * semantics mean and are for, and their fields) and its members a page at a time. The members can
 * be narrowed to those whose referenced component's name contains some text, or to one component
 * by UUID. A name filter reads through the members in order on the server, so on a pattern with
 * millions of members it can take a while.
 */
export function PatternsPanel({ onViewSemantics }: PatternsPanelProps) {
  const [patternFilter, setPatternFilter] = useState('');
  const [selected, setSelected] = useState<PatternSummary | null>(null);
  const [pageSize, setPageSize] = useState(50);
  const [offset, setOffset] = useState(0);
  const [filterInput, setFilterInput] = useState('');
  const [filter, setFilter] = useState<MemberFilter>({ kind: 'none' });
  const [creating, setCreating] = useState(false);
  const queryClient = useQueryClient();

  const patterns = useQuery({
    queryKey: ['patterns'],
    queryFn: listPatterns,
    staleTime: 60_000,
  });

  const members = useQuery({
    queryKey: ['pattern-members', selected?.patternId, offset, pageSize, filter],
    queryFn: () =>
      listPatternMembers({
        patternId: selected!.patternId,
        offset,
        limit: pageSize,
        referencedComponentText: filter.kind === 'text' ? filter.text : undefined,
        referencedComponentId: filter.kind === 'id' ? filter.id : undefined,
      }),
    enabled: !!selected,
    placeholderData: keepPreviousData,
  });

  const shownPatterns = useMemo(() => {
    const all = patterns.data?.patterns ?? [];
    const text = patternFilter.trim().toLowerCase();
    return text ? all.filter((p) => p.name.toLowerCase().includes(text)) : all;
  }, [patterns.data, patternFilter]);

  const select = (pattern: PatternSummary) => {
    setCreating(false);
    setSelected(pattern);
    setOffset(0);
    setFilterInput('');
    setFilter({ kind: 'none' });
  };

  const applyFilter = (e: React.FormEvent) => {
    e.preventDefault();
    const text = filterInput.trim();
    setOffset(0);
    if (!text) setFilter({ kind: 'none' });
    else if (UUID_PATTERN.test(text)) setFilter({ kind: 'id', id: text });
    else setFilter({ kind: 'text', text });
  };

  const clearFilter = () => {
    setFilterInput('');
    setFilter({ kind: 'none' });
    setOffset(0);
  };

  const page = members.data;
  const rows = page?.success ? page.members ?? [] : [];
  // The field names come from the pattern's definition; a member may carry fewer.
  const fieldNames = selected?.fields.map((f) => f.meaning) ?? [];

  return (
    <div className="patterns-panel">
      <div className="reasoner-header">
        <h2>Patterns</h2>
      </div>

      <div className="patterns-layout">
        <aside className="patterns-list">
          <button type="button" className="changeset-run patterns-new" onClick={() => setCreating(true)} disabled={creating}>
            + New pattern
          </button>
          <input
            type="search"
            className="patterns-list-filter"
            placeholder="Filter patterns by name"
            value={patternFilter}
            onChange={(e) => setPatternFilter(e.target.value)}
          />
          {patterns.isLoading && <p className="loading">Loading patterns...</p>}
          {patterns.isError && (
            <div className="error-message">
              Error: {patterns.error instanceof Error ? patterns.error.message : 'Failed to load patterns'}
            </div>
          )}
          {patterns.data && !patterns.data.success && (
            <div className="error-message">Error: {patterns.data.errorMessage || 'Failed to load patterns'}</div>
          )}
          {patterns.data?.success && (
            <>
              <p className="patterns-count">
                {shownPatterns.length === (patterns.data.patterns?.length ?? 0)
                  ? `${shownPatterns.length} patterns`
                  : `${shownPatterns.length} of ${patterns.data.patterns?.length ?? 0} patterns`}
              </p>
              <ul>
                {shownPatterns.map((p) => (
                  <li key={p.patternId}>
                    <button
                      className={`patterns-list-item ${selected?.patternId === p.patternId ? 'active' : ''}`}
                      onClick={() => select(p)}
                      title={p.patternId}
                    >
                      {p.name}
                    </button>
                  </li>
                ))}
              </ul>
            </>
          )}
        </aside>

        <section className="patterns-detail">
          {creating && (
            <NewPatternForm
              onCancel={() => setCreating(false)}
              onCreated={(pattern) => {
                queryClient.invalidateQueries({ queryKey: ['patterns'] });
                select(pattern);
              }}
            />
          )}

          {!creating && !selected && <p className="instructions">Select a pattern to see its definition and members.</p>}

          {!creating && selected && (
            <>
              <h3 className="patterns-name">{selected.name}</h3>
              <p className="concept-id patterns-id">{selected.patternId}</p>

              <dl className="patterns-definition">
                <dt>Meaning</dt>
                <dd>{selected.meaning ?? '—'}</dd>
                <dt>Purpose</dt>
                <dd>{selected.purpose ?? '—'}</dd>
                {selected.stamp && (
                  <>
                    <dt>Latest version</dt>
                    <dd>
                      {selected.stamp.status} · {selected.stamp.formattedTime} · {selected.stamp.author} ·{' '}
                      {selected.stamp.module} · {selected.stamp.path}
                    </dd>
                  </>
                )}
              </dl>

              <h4 className="patterns-subhead">Fields</h4>
              {selected.fields.length === 0 ? (
                <p className="no-results">This pattern defines no fields.</p>
              ) : (
                <table className="results-table patterns-fields">
                  <thead>
                    <tr>
                      <th>#</th>
                      <th>Meaning</th>
                      <th>Purpose</th>
                      <th>Data type</th>
                    </tr>
                  </thead>
                  <tbody>
                    {selected.fields.map((f) => (
                      <tr key={f.index}>
                        <td>{f.index}</td>
                        <td>{f.meaning}</td>
                        <td>{f.purpose ?? ''}</td>
                        <td>{f.dataType ?? ''}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}

              <h4 className="patterns-subhead">
                Members
                {page?.success && page.totalCount != null && (
                  <span className="patterns-total"> — {page.totalCount.toLocaleString()} in all</span>
                )}
              </h4>

              <form className="patterns-member-filter" onSubmit={applyFilter}>
                <input
                  type="search"
                  placeholder="Referenced component name, or its UUID"
                  value={filterInput}
                  onChange={(e) => setFilterInput(e.target.value)}
                />
                <button type="submit" className="changeset-run" disabled={members.isFetching}>
                  Filter
                </button>
                {filter.kind !== 'none' && (
                  <button type="button" className="link-button" onClick={clearFilter}>
                    Clear
                  </button>
                )}
                <label>
                  Page size{' '}
                  <select
                    value={pageSize}
                    onChange={(e) => {
                      setPageSize(Number(e.target.value));
                      setOffset(0);
                    }}
                  >
                    {PAGE_SIZES.map((n) => (
                      <option key={n} value={n}>{n}</option>
                    ))}
                  </select>
                </label>
              </form>
              {filter.kind === 'text' && (
                <p className="reasoner-hint">
                  Members whose referenced component&rsquo;s name contains &ldquo;{filter.text}&rdquo;. The server reads
                  through the members in order to find them, which takes a while on a large pattern.
                </p>
              )}

              {members.isError && (
                <div className="error-message">
                  Error: {members.error instanceof Error ? members.error.message : 'Failed to load members'}
                </div>
              )}
              {page && !page.success && (
                <div className="error-message">Error: {page.errorMessage || 'Failed to load members'}</div>
              )}
              {members.isLoading && <p className="loading">Loading members...</p>}

              {page?.success && (
                <>
                  <Pager
                    offset={offset}
                    shown={rows.length}
                    hasMore={!!page.hasMore}
                    busy={members.isFetching}
                    onPrevious={() => setOffset(Math.max(0, offset - pageSize))}
                    onNext={() => setOffset(offset + pageSize)}
                  />
                  {rows.length === 0 ? (
                    <p className="no-results">No members match.</p>
                  ) : (
                    <div className="patterns-members-scroll">
                      <table className={`results-table patterns-members ${members.isFetching ? 'stale' : ''}`}>
                        <thead>
                          <tr>
                            <th>Referenced component</th>
                            {fieldNames.map((name, i) => (
                              <th key={i}>{name}</th>
                            ))}
                            <th>Status</th>
                          </tr>
                        </thead>
                        <tbody>
                          {rows.map((m) => (
                            <MemberRow key={m.semanticId} member={m} fieldCount={fieldNames.length} onOpen={onViewSemantics} />
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}
                </>
              )}
            </>
          )}
        </section>
      </div>
    </div>
  );
}

function MemberRow({
  member,
  fieldCount,
  onOpen,
}: {
  member: PatternMember;
  fieldCount: number;
  onOpen: (conceptId: string, conceptName?: string) => void;
}) {
  const cells = Array.from({ length: Math.max(fieldCount, member.fields.length) }, (_, i) => member.fields[i]?.value ?? '');
  return (
    <tr>
      <td>
        <button
          className="link-button patterns-component"
          onClick={() => onOpen(member.referencedComponentId, member.referencedComponentName)}
          title={member.referencedComponentId}
        >
          {member.referencedComponentName}
        </button>
      </td>
      {cells.map((value, i) => (
        <td key={i} className="patterns-value" title={value}>
          {value}
        </td>
      ))}
      <td>{member.stamp?.status ?? ''}</td>
    </tr>
  );
}

function Pager({
  offset,
  shown,
  hasMore,
  busy,
  onPrevious,
  onNext,
}: {
  offset: number;
  shown: number;
  hasMore: boolean;
  busy: boolean;
  onPrevious: () => void;
  onNext: () => void;
}) {
  return (
    <div className="patterns-pager">
      <button className="link-button" onClick={onPrevious} disabled={offset === 0 || busy}>
        &larr; Previous
      </button>
      <span>{shown === 0 ? 'None' : `${(offset + 1).toLocaleString()}–${(offset + shown).toLocaleString()}`}</span>
      <button className="link-button" onClick={onNext} disabled={!hasMore || busy}>
        Next &rarr;
      </button>
    </div>
  );
}
