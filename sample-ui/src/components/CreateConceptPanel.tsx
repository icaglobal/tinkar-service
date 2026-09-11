import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { conceptSearch, createConcept } from '../api/tinkarApi';
import type { SearchResult } from '../api/types';

interface CreateConceptPanelProps {
  onBack: () => void;
}

/** A concept chosen for the necessary set. */
type ParentConcept = {
  id: string;
  name: string;
};

/** Prefers a readable label, falling back to the raw id when a concept has no description. */
function labelFor(result: SearchResult): string {
  return (
    result.descriptions?.regularName ||
    result.descriptions?.fullyQualifiedName ||
    result.publicId[0]
  );
}

/**
 * Creates a concept, mirroring what Komet's "New Concept" editor writes: a fully qualified
 * name plus an EL++ stated axiom whose necessary set references the chosen concepts.
 */
export function CreateConceptPanel({ onBack }: CreateConceptPanelProps) {
  const [fullyQualifiedName, setFullyQualifiedName] = useState('');
  const [parents, setParents] = useState<ParentConcept[]>([]);
  const [parentQuery, setParentQuery] = useState('');
  const [parentResults, setParentResults] = useState<SearchResult[]>([]);
  const [message, setMessage] = useState<{ text: string; isError: boolean } | null>(null);

  const searchParents = useMutation({
    mutationFn: () => conceptSearch(parentQuery, 20),
    onSuccess: (data) => setParentResults(data.results ?? []),
    onError: (error) =>
      setMessage({
        text: `Parent search failed: ${error instanceof Error ? error.message : 'unknown error'}`,
        isError: true,
      }),
  });

  const create = useMutation({
    mutationFn: () =>
      createConcept(
        fullyQualifiedName.trim(),
        parents.map((parent) => parent.id)
      ),
    onSuccess: (response) => {
      if (response.success) {
        setMessage({
          text: `Created "${response.fullyQualifiedName}" — ${response.conceptId}`,
          isError: false,
        });
        // Clear the form so the next concept starts fresh; the id stays on screen in the message.
        setFullyQualifiedName('');
        setParents([]);
        setParentQuery('');
        setParentResults([]);
      } else {
        setMessage({ text: response.errorMessage ?? 'Concept creation failed', isError: true });
      }
    },
    onError: (error) =>
      setMessage({
        text: `Error: ${error instanceof Error ? error.message : 'Failed to create concept'}`,
        isError: true,
      }),
  });

  const addParent = (result: SearchResult) => {
    const id = result.publicId[0];
    if (!parents.some((parent) => parent.id === id)) {
      setParents([...parents, { id, name: labelFor(result) }]);
    }
  };

  const canSubmit = fullyQualifiedName.trim().length > 0 && !create.isPending;

  return (
    <div className="create-concept-panel">
      <div className="create-concept-header">
        <button className="back-button" onClick={onBack}>&larr; Back</button>
        <h2>Create Concept</h2>
      </div>

      <section className="create-concept-section">
        <label htmlFor="fqn-input">Fully qualified name <span className="required">required</span></label>
        <input
          id="fqn-input"
          type="text"
          value={fullyQualifiedName}
          onChange={(e) => setFullyQualifiedName(e.target.value)}
          placeholder="e.g. New Medical Condition"
          className="create-concept-input"
        />
      </section>

      <section className="create-concept-section">
        <label>Necessary set</label>
        <p className="create-concept-hint">
          Concepts the new concept is defined against. Leave empty to use{' '}
          <em>Anonymous concept</em>, the placeholder for a definition that is not finished yet.
        </p>

        {parents.length > 0 && (
          <ul className="parent-chips">
            {parents.map((parent) => (
              <li key={parent.id} className="parent-chip">
                {parent.name}
                <button
                  type="button"
                  aria-label={`Remove ${parent.name}`}
                  onClick={() => setParents(parents.filter((p) => p.id !== parent.id))}
                >
                  &times;
                </button>
              </li>
            ))}
          </ul>
        )}

        <div className="parent-search">
          <input
            type="text"
            value={parentQuery}
            onChange={(e) => setParentQuery(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && parentQuery.trim()) {
                searchParents.mutate();
              }
            }}
            placeholder="Search for a concept to add"
            className="create-concept-input"
          />
          <button
            type="button"
            onClick={() => searchParents.mutate()}
            disabled={!parentQuery.trim() || searchParents.isPending}
          >
            {searchParents.isPending ? 'Searching…' : 'Search'}
          </button>
        </div>

        {parentResults.length > 0 && (
          <ul className="parent-results">
            {parentResults.map((result) => (
              <li key={result.publicId[0]}>
                <button type="button" onClick={() => addParent(result)}>
                  {labelFor(result)}
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>

      <button
        className="create-concept-submit"
        onClick={() => create.mutate()}
        disabled={!canSubmit}
      >
        {create.isPending ? 'Creating…' : 'Create Concept'}
      </button>

      {message && (
        <p className={message.isError ? 'create-concept-error' : 'create-concept-success'}>
          {message.text}
        </p>
      )}
    </div>
  );
}
