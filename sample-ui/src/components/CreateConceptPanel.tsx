import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { conceptSearch, createConcept } from '../api/tinkarApi';
import type {
  AxiomSetType,
  CaseSignificance,
  ConceptAxiom,
  ConceptDescription,
  DescriptionLanguage,
  DescriptionType,
  SearchResult,
} from '../api/types';

interface CreateConceptPanelProps {
  onBack: () => void;
}

/** A concept chosen for an axiom set. */
type ParentConcept = { id: string; name: string };

/** An axiom set being edited, with parents held as chips rather than bare ids. */
type AxiomDraft = { setType: AxiomSetType; parents: ParentConcept[] };

const DESCRIPTION_TYPES: { value: DescriptionType; label: string }[] = [
  { value: 'FULLY_QUALIFIED_NAME', label: 'Fully qualified name' },
  { value: 'REGULAR_NAME', label: 'Regular name description type' },
  { value: 'DEFINITION', label: 'Definition' },
];

const CASE_SIGNIFICANCES: { value: CaseSignificance; label: string }[] = [
  { value: 'NOT_CASE_SENSITIVE', label: 'Not case sensitive' },
  { value: 'CASE_SENSITIVE', label: 'Case sensitive' },
  { value: 'INITIAL_CHARACTER_CASE_SENSITIVE', label: 'Initial character case sensitive' },
];

const LANGUAGES: DescriptionLanguage[] = [
  'ENGLISH', 'SPANISH', 'FRENCH', 'GERMAN', 'DUTCH',
  'ITALIAN', 'DANISH', 'CZECH', 'IRISH', 'CHINESE',
];

const SET_TYPES: { value: AxiomSetType; label: string }[] = [
  { value: 'NECESSARY', label: 'Necessary set' },
  { value: 'SUFFICIENT', label: 'Sufficient set' },
];

function labelFor(result: SearchResult): string {
  return (
    result.descriptions?.regularName ||
    result.descriptions?.fullyQualifiedName ||
    result.publicId[0]
  );
}

const newDescription = (type: DescriptionType): ConceptDescription => ({
  text: '',
  type,
  caseSignificance: 'NOT_CASE_SENSITIVE',
  language: 'ENGLISH',
});

/**
 * Creates a concept, covering what Komet's New Concept editor can author: several descriptions
 * with their own type, language and case significance, and a stated axiom that may carry
 * necessary and sufficient sets together.
 */
export function CreateConceptPanel({ onBack }: CreateConceptPanelProps) {
  // The fully qualified name is seeded because exactly one is required; the server rejects
  // a request without it, so starting with an empty one keeps that rule visible in the form.
  const [descriptions, setDescriptions] = useState<ConceptDescription[]>([
    newDescription('FULLY_QUALIFIED_NAME'),
  ]);
  const [axioms, setAxioms] = useState<AxiomDraft[]>([{ setType: 'NECESSARY', parents: [] }]);
  const [parentQuery, setParentQuery] = useState('');
  const [parentResults, setParentResults] = useState<SearchResult[]>([]);
  const [activeAxiom, setActiveAxiom] = useState(0);
  const [message, setMessage] = useState<{ text: string; isError: boolean } | null>(null);

  const searchParents = useMutation({
    mutationFn: () => conceptSearch(parentQuery, 20),
    onSuccess: (data) => setParentResults(data.results ?? []),
    onError: (e) =>
      setMessage({
        text: `Parent search failed: ${e instanceof Error ? e.message : 'unknown error'}`,
        isError: true,
      }),
  });

  const create = useMutation({
    mutationFn: () => {
      const payload: ConceptAxiom[] = axioms.map((a) => ({
        setType: a.setType,
        parentConceptIds: a.parents.map((p) => p.id),
      }));
      return createConcept({ descriptions, axioms: payload });
    },
    onSuccess: (response) => {
      if (response.success) {
        setMessage({
          text: `Created "${response.fullyQualifiedName}" — ${response.conceptId}`,
          isError: false,
        });
        setDescriptions([newDescription('FULLY_QUALIFIED_NAME')]);
        setAxioms([{ setType: 'NECESSARY', parents: [] }]);
        setParentQuery('');
        setParentResults([]);
      } else {
        setMessage({ text: response.errorMessage ?? 'Concept creation failed', isError: true });
      }
    },
    onError: (e) =>
      setMessage({
        text: `Error: ${e instanceof Error ? e.message : 'Failed to create concept'}`,
        isError: true,
      }),
  });

  const updateDescription = (index: number, patch: Partial<ConceptDescription>) =>
    setDescriptions(descriptions.map((d, i) => (i === index ? { ...d, ...patch } : d)));

  const addParent = (result: SearchResult) => {
    const id = result.publicId[0];
    setAxioms(
      axioms.map((a, i) =>
        i === activeAxiom && !a.parents.some((p) => p.id === id)
          ? { ...a, parents: [...a.parents, { id, name: labelFor(result) }] }
          : a
      )
    );
  };

  const fqnCount = descriptions.filter((d) => d.type === 'FULLY_QUALIFIED_NAME').length;
  const hasText = descriptions.every((d) => d.text.trim().length > 0);
  const canSubmit = fqnCount === 1 && hasText && !create.isPending;

  return (
    <div className="create-concept-panel">
      <div className="create-concept-header">
        <button className="back-button" onClick={onBack}>&larr; Back</button>
        <h2>New Concept</h2>
      </div>

      <section className="create-concept-section">
        <div className="section-heading">
          <label>Description <span className="required">required</span></label>
          <div className="section-actions">
            {DESCRIPTION_TYPES.map(({ value, label }) => (
              <button
                key={value}
                type="button"
                onClick={() => setDescriptions([...descriptions, newDescription(value)])}
              >
                + {label}
              </button>
            ))}
          </div>
        </div>

        {descriptions.map((description, index) => (
          <div key={index} className="description-card">
            <div className="description-card-header">
              <span>{DESCRIPTION_TYPES.find((t) => t.value === description.type)?.label}</span>
              {descriptions.length > 1 && (
                <button
                  type="button"
                  aria-label="Remove description"
                  onClick={() => setDescriptions(descriptions.filter((_, i) => i !== index))}
                >
                  &times;
                </button>
              )}
            </div>

            <label htmlFor={`text-${index}`}>Text</label>
            <input
              id={`text-${index}`}
              type="text"
              className="create-concept-input"
              value={description.text}
              onChange={(e) => updateDescription(index, { text: e.target.value })}
              placeholder="e.g. New Medical Condition"
            />

            <div className="description-card-row">
              <div>
                <label htmlFor={`type-${index}`}>Description type</label>
                <select
                  id={`type-${index}`}
                  value={description.type}
                  onChange={(e) =>
                    updateDescription(index, { type: e.target.value as DescriptionType })
                  }
                >
                  {DESCRIPTION_TYPES.map(({ value, label }) => (
                    <option key={value} value={value}>{label}</option>
                  ))}
                </select>
              </div>
              <div>
                <label htmlFor={`case-${index}`}>Case significance</label>
                <select
                  id={`case-${index}`}
                  value={description.caseSignificance}
                  onChange={(e) =>
                    updateDescription(index, {
                      caseSignificance: e.target.value as CaseSignificance,
                    })
                  }
                >
                  {CASE_SIGNIFICANCES.map(({ value, label }) => (
                    <option key={value} value={value}>{label}</option>
                  ))}
                </select>
              </div>
              <div>
                <label htmlFor={`lang-${index}`}>Language</label>
                <select
                  id={`lang-${index}`}
                  value={description.language}
                  onChange={(e) =>
                    updateDescription(index, {
                      language: e.target.value as DescriptionLanguage,
                    })
                  }
                >
                  {LANGUAGES.map((language) => (
                    <option key={language} value={language}>{language}</option>
                  ))}
                </select>
              </div>
            </div>
          </div>
        ))}

        {fqnCount !== 1 && (
          <p className="create-concept-error">
            Exactly one Fully qualified name is required — found {fqnCount}.
          </p>
        )}
      </section>

      <section className="create-concept-section">
        <div className="section-heading">
          <label>Axiom</label>
          <div className="section-actions">
            {SET_TYPES.map(({ value, label }) => (
              <button
                key={value}
                type="button"
                onClick={() => setAxioms([...axioms, { setType: value, parents: [] }])}
              >
                + {label}
              </button>
            ))}
          </div>
        </div>
        <p className="create-concept-hint">
          A <em>necessary set</em> states conditions every instance meets; a <em>sufficient
          set</em> states conditions that define the concept, which is what lets the reasoner
          classify others underneath it. An empty set references <em>Anonymous concept</em>.
        </p>

        {axioms.map((axiom, index) => (
          <div
            key={index}
            className={`axiom-card ${index === activeAxiom ? 'active' : ''}`}
            onClick={() => setActiveAxiom(index)}
          >
            <div className="description-card-header">
              <span>{SET_TYPES.find((s) => s.value === axiom.setType)?.label}</span>
              {axioms.length > 1 && (
                <button
                  type="button"
                  aria-label="Remove axiom set"
                  onClick={() => {
                    setAxioms(axioms.filter((_, i) => i !== index));
                    setActiveAxiom(0);
                  }}
                >
                  &times;
                </button>
              )}
            </div>
            {axiom.parents.length > 0 ? (
              <ul className="parent-chips">
                {axiom.parents.map((parent) => (
                  <li key={parent.id} className="parent-chip">
                    {parent.name}
                    <button
                      type="button"
                      aria-label={`Remove ${parent.name}`}
                      onClick={() =>
                        setAxioms(
                          axioms.map((a, i) =>
                            i === index
                              ? { ...a, parents: a.parents.filter((p) => p.id !== parent.id) }
                              : a
                          )
                        )
                      }
                    >
                      &times;
                    </button>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="create-concept-hint">Anonymous concept</p>
            )}
          </div>
        ))}

        <div className="parent-search">
          <input
            type="text"
            className="create-concept-input"
            value={parentQuery}
            onChange={(e) => setParentQuery(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && parentQuery.trim()) searchParents.mutate();
            }}
            placeholder={`Search a concept to add to the ${
              SET_TYPES.find((s) => s.value === axioms[activeAxiom]?.setType)?.label ?? 'set'
            }`}
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

      <button className="create-concept-submit" onClick={() => create.mutate()} disabled={!canSubmit}>
        {create.isPending ? 'Creating…' : 'Create'}
      </button>

      {message && (
        <p className={message.isError ? 'create-concept-error' : 'create-concept-success'}>
          {message.text}
        </p>
      )}
    </div>
  );
}
