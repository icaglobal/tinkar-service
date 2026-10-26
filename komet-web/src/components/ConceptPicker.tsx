import { useEffect, useId, useRef, useState } from 'react';
import { conceptSearchWithSort } from '../api/tinkarApi';
import type { Choice } from '../api/types';

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** How long typing pauses before the search runs, as Komet's search boxes wait. */
const SEARCH_DELAY_MS = 300;

/** The fewest characters searched for. */
const MIN_QUERY = 2;

interface ConceptPickerProps {
  /** The visible label, also the accessible name of the search box. */
  label: string;
  value: Choice | null;
  onChange: (value: Choice | null) => void;
  disabled?: boolean;
  /** Leave inactive concepts out of the results, as the main search does by default. */
  activeOnly?: boolean;
  /** The search box's placeholder. */
  placeholder?: string;
}

/**
 * Chooses one concept from a drop-down of search results. The search runs as you type, after a
 * short pause, as it does in Komet; a pasted concept UUID is offered as a choice of its own, and
 * the service checks it when the form is submitted.
 *
 * A combobox in the WAI-ARIA sense: the arrow keys move through the results, Enter chooses,
 * Escape closes the list, and a screen reader hears the highlighted result.
 */
export function ConceptPicker({ label, value, onChange, disabled, activeOnly, placeholder }: ConceptPickerProps) {
  const inputId = useId();
  const listId = useId();
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<Choice[]>([]);
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(-1);
  const [searching, setSearching] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Each search is numbered, so an answer to an older one never replaces a newer one's.
  const searchNumber = useRef(0);

  useEffect(() => {
    const text = query.trim();
    const number = ++searchNumber.current;
    if (UUID_PATTERN.test(text)) {
      setResults([{ id: text.toLowerCase(), name: `Concept ${text.toLowerCase()}` }]);
      setSearching(false);
      setError(null);
      return;
    }
    if (text.length < MIN_QUERY) {
      setResults([]);
      setSearching(false);
      setError(null);
      return;
    }
    setSearching(true);
    const timer = setTimeout(async () => {
      try {
        const response = await conceptSearchWithSort(text, 15, 'TOP_COMPONENT');
        if (number !== searchNumber.current) return;
        if (!response.success) {
          setError(response.errorMessage || 'Search failed');
          setResults([]);
        } else {
          setError(null);
          setResults(
            (response.groupedResults ?? [])
              .filter((r) => r.publicId.length > 0 && (!activeOnly || r.active))
              .map((r) => ({ id: r.publicId[0], name: r.fullyQualifiedName }))
          );
        }
      } catch (e) {
        if (number !== searchNumber.current) return;
        setError(e instanceof Error ? e.message : 'Search failed');
        setResults([]);
      } finally {
        if (number === searchNumber.current) setSearching(false);
      }
    }, SEARCH_DELAY_MS);
    return () => clearTimeout(timer);
  }, [query, activeOnly]);

  const choose = (choice: Choice) => {
    onChange(choice);
    setQuery('');
    setResults([]);
    setOpen(false);
    setActive(-1);
  };

  const onKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setOpen(true);
      setActive((i) => Math.min(i + 1, results.length - 1));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setActive((i) => Math.max(i - 1, 0));
    } else if (e.key === 'Enter') {
      // Enter chooses; it never submits the form around the picker.
      e.preventDefault();
      if (open && active >= 0 && active < results.length) {
        choose(results[active]);
      } else if (results.length === 1) {
        choose(results[0]);
      }
    } else if (e.key === 'Escape') {
      setOpen(false);
      setActive(-1);
    }
  };

  if (value) {
    return (
      <div className="concept-picker">
        <span className="concept-picker-label">{label}</span>
        <div className="concept-picker-chosen">
          <span title={value.id}>{value.name}</span>
          <button type="button" className="link-button" onClick={() => onChange(null)} disabled={disabled}>
            Change<span className="visually-hidden"> {label}</span>
          </button>
        </div>
      </div>
    );
  }

  const showList = open && query.trim().length >= MIN_QUERY;
  const status = searching
    ? 'Searching...'
    : error
      ? error
      : results.length === 0
        ? 'No concepts match.'
        : null;

  return (
    <div className="concept-picker">
      <label htmlFor={inputId} className="concept-picker-label">{label}</label>
      <div className="concept-picker-box">
        <input
          id={inputId}
          type="text"
          className="concept-picker-input"
          role="combobox"
          aria-expanded={showList}
          aria-controls={listId}
          aria-autocomplete="list"
          aria-activedescendant={showList && active >= 0 ? `${listId}-${active}` : undefined}
          autoComplete="off"
          placeholder={placeholder ?? 'Type to search, or paste a concept UUID'}
          value={query}
          onChange={(e) => {
            setQuery(e.target.value);
            setOpen(true);
            setActive(-1);
          }}
          onFocus={() => setOpen(true)}
          // Closed after the click that chooses a result has landed.
          onBlur={() => setTimeout(() => setOpen(false), 150)}
          onKeyDown={onKeyDown}
          disabled={disabled}
        />
        {showList && (
          <ul id={listId} role="listbox" className="concept-picker-dropdown" aria-label={`Concepts for ${label}`}>
            {status && (
              <li className={`concept-picker-status ${error ? 'is-error' : ''}`} role="presentation">
                {status}
              </li>
            )}
            {!searching &&
              results.map((r, i) => (
                <li
                  key={r.id}
                  id={`${listId}-${i}`}
                  role="option"
                  aria-selected={i === active}
                  className={i === active ? 'is-active' : ''}
                  title={r.id}
                  // mousedown, not click: it lands before the input's blur closes the list.
                  onMouseDown={(e) => {
                    e.preventDefault();
                    choose(r);
                  }}
                  onMouseEnter={() => setActive(i)}
                >
                  {r.name}
                </li>
              ))}
          </ul>
        )}
      </div>
    </div>
  );
}
