import { useId, useState } from 'react';
import { conceptSearchWithSort } from '../api/tinkarApi';
import type { Choice } from '../api/types';

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

interface ConceptPickerProps {
  /** The visible label, also the accessible name of the search box. */
  label: string;
  value: Choice | null;
  onChange: (value: Choice | null) => void;
  disabled?: boolean;
}

/**
 * Chooses one concept: search by name and pick a result, or paste a concept's UUID. The service
 * checks a pasted UUID when the form is submitted.
 *
 * Every step works from the keyboard: Enter searches, the results are buttons, and Clear returns
 * to the search box.
 */
export function ConceptPicker({ label, value, onChange, disabled }: ConceptPickerProps) {
  const inputId = useId();
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<Choice[] | null>(null);
  const [searching, setSearching] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const search = async () => {
    const text = query.trim();
    if (!text) return;
    if (UUID_PATTERN.test(text)) {
      onChange({ id: text.toLowerCase(), name: text });
      setQuery('');
      setResults(null);
      return;
    }
    setSearching(true);
    setError(null);
    try {
      const response = await conceptSearchWithSort(text, 15, 'TOP_COMPONENT');
      if (!response.success) {
        setError(response.errorMessage || 'Search failed');
        setResults([]);
        return;
      }
      setResults(
        (response.groupedResults ?? [])
          .filter((r) => r.publicId.length > 0)
          .map((r) => ({ id: r.publicId[0], name: r.fullyQualifiedName }))
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Search failed');
      setResults([]);
    } finally {
      setSearching(false);
    }
  };

  const pick = (choice: Choice) => {
    onChange(choice);
    setQuery('');
    setResults(null);
  };

  if (value) {
    return (
      <div className="concept-picker">
        <span className="concept-picker-label">{label}</span>
        <div className="concept-picker-chosen">
          <span title={value.id}>{value.name}</span>
          <button type="button" className="link-button" onClick={() => onChange(null)} disabled={disabled}>
            Clear<span className="visually-hidden"> {label}</span>
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="concept-picker">
      <label htmlFor={inputId} className="concept-picker-label">{label}</label>
      <div className="concept-picker-search">
        <input
          id={inputId}
          type="search"
          placeholder="Search by name, or paste a concept UUID"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault();
              search();
            }
          }}
          disabled={disabled}
        />
        <button type="button" className="link-button" onClick={search} disabled={disabled || searching || !query.trim()}>
          {searching ? 'Searching...' : 'Search'}
        </button>
      </div>
      {error && <div className="error-message">{error}</div>}
      {results && results.length === 0 && !error && <p className="no-results">No concepts match.</p>}
      {results && results.length > 0 && (
        <ul className="concept-picker-results" aria-label={`Concepts for ${label}`}>
          {results.map((r) => (
            <li key={r.id}>
              <button type="button" onClick={() => pick(r)} title={r.id}>
                {r.name}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
