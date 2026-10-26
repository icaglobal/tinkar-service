import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { createPattern, listFieldDataTypes } from '../api/tinkarApi';
import type { Choice, PatternSummary } from '../api/types';
import { ConceptPicker } from './ConceptPicker';

interface NewPatternFormProps {
  /** Called with the pattern the service created. */
  onCreated: (pattern: PatternSummary) => void;
  onCancel: () => void;
}

/** A field being added: what it means, what it is for, and its data type. */
type DraftField = { key: number; meaning: Choice | null; purpose: Choice | null; dataTypeId: string };

let nextKey = 0;
const emptyField = (): DraftField => ({ key: nextKey++, meaning: null, purpose: null, dataTypeId: '' });

/**
 * Creates a pattern with what Komet's pattern window asks for: a fully qualified name and any
 * other names, the concepts its semantics mean and are for, and its fields in order. Like that
 * window, it offers no choice of status, module or path: the pattern is written active, in the
 * SOLOR overlay module, on the development path, by the signed-in author.
 */
export function NewPatternForm({ onCreated, onCancel }: NewPatternFormProps) {
  const [fullyQualifiedName, setFullyQualifiedName] = useState('');
  const [otherNames, setOtherNames] = useState<string[]>([]);
  const [meaning, setMeaning] = useState<Choice | null>(null);
  const [purpose, setPurpose] = useState<Choice | null>(null);
  const [fields, setFields] = useState<DraftField[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const dataTypes = useQuery({ queryKey: ['field-data-types'], queryFn: listFieldDataTypes, staleTime: Infinity });

  const updateField = (key: number, change: Partial<DraftField>) =>
    setFields((all) => all.map((f) => (f.key === key ? { ...f, ...change } : f)));

  const moveField = (index: number, by: -1 | 1) =>
    setFields((all) => {
      const next = [...all];
      const [moved] = next.splice(index, 1);
      next.splice(index + by, 0, moved);
      return next;
    });

  /** What is still missing, in the order the form asks for it; null when it can be published. */
  const missing = (): string | null => {
    if (!fullyQualifiedName.trim()) return 'Enter a fully qualified name.';
    if (!meaning) return 'Choose the meaning.';
    if (!purpose) return 'Choose the purpose.';
    for (let i = 0; i < fields.length; i++) {
      const f = fields[i];
      if (!f.meaning || !f.purpose || !f.dataTypeId) {
        return `Field ${i + 1} needs a meaning, a purpose and a data type.`;
      }
    }
    return null;
  };

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    const problem = missing();
    if (problem) {
      setError(problem);
      return;
    }
    setSaving(true);
    setError(null);
    try {
      const response = await createPattern({
        descriptions: [
          { text: fullyQualifiedName.trim(), type: 'FULLY_QUALIFIED_NAME' },
          ...otherNames.map((n) => n.trim()).filter(Boolean).map((text) => ({ text, type: 'REGULAR_NAME' as const })),
        ],
        meaningId: meaning!.id,
        purposeId: purpose!.id,
        fields: fields.map((f) => ({ meaningId: f.meaning!.id, purposeId: f.purpose!.id, dataTypeId: f.dataTypeId })),
      });
      if (!response.success || !response.pattern) {
        setError(response.errorMessage || 'The pattern was not created.');
        return;
      }
      onCreated(response.pattern);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'The pattern was not created.');
    } finally {
      setSaving(false);
    }
  };

  return (
    <form className="new-pattern" onSubmit={submit} aria-labelledby="new-pattern-title">
      <h3 id="new-pattern-title" className="patterns-name">New pattern</h3>
      <p className="reasoner-hint">
        Written active, in the SOLOR overlay module, on the development path, as you, the same as a pattern
        created in Komet&rsquo;s pattern window.
      </p>

      <fieldset className="new-pattern-section">
        <legend>Description</legend>
        <label htmlFor="new-pattern-fqn" className="concept-picker-label">Fully qualified name</label>
        <input
          id="new-pattern-fqn"
          type="text"
          value={fullyQualifiedName}
          onChange={(e) => setFullyQualifiedName(e.target.value)}
          disabled={saving}
          required
        />
        {otherNames.map((name, i) => (
          <div key={i} className="new-pattern-row">
            <label htmlFor={`new-pattern-other-${i}`} className="visually-hidden">Other name {i + 1}</label>
            <input
              id={`new-pattern-other-${i}`}
              type="text"
              placeholder={`Other name ${i + 1}`}
              value={name}
              onChange={(e) => setOtherNames((all) => all.map((n, j) => (j === i ? e.target.value : n)))}
              disabled={saving}
            />
            <button type="button" className="link-button" disabled={saving}
                    onClick={() => setOtherNames((all) => all.filter((_, j) => j !== i))}>
              Remove<span className="visually-hidden"> other name {i + 1}</span>
            </button>
          </div>
        ))}
        <button type="button" className="link-button new-pattern-add" disabled={saving}
                onClick={() => setOtherNames((all) => [...all, ''])}>
          + Add other name
        </button>
      </fieldset>

      <fieldset className="new-pattern-section">
        <legend>Pattern definition</legend>
        <div className="new-pattern-pair">
          <ConceptPicker label="Meaning" value={meaning} onChange={setMeaning} disabled={saving} />
          <ConceptPicker label="Purpose" value={purpose} onChange={setPurpose} disabled={saving} />
        </div>
      </fieldset>

      <fieldset className="new-pattern-section">
        <legend>Fields</legend>
        {fields.length === 0 && <p className="no-results">No fields. A pattern without fields marks membership.</p>}
        {fields.map((field, i) => (
          <div key={field.key} className="new-pattern-field">
            <div className="new-pattern-field-head">
              <strong>Field {i + 1}</strong>
              <span>
                <button type="button" className="link-button" disabled={saving || i === 0} onClick={() => moveField(i, -1)}>
                  Move up<span className="visually-hidden"> field {i + 1}</span>
                </button>
                <button type="button" className="link-button" disabled={saving || i === fields.length - 1}
                        onClick={() => moveField(i, 1)}>
                  Move down<span className="visually-hidden"> field {i + 1}</span>
                </button>
                <button type="button" className="link-button" disabled={saving}
                        onClick={() => setFields((all) => all.filter((f) => f.key !== field.key))}>
                  Remove<span className="visually-hidden"> field {i + 1}</span>
                </button>
              </span>
            </div>
            <div className="new-pattern-pair">
              <ConceptPicker label={`Field ${i + 1} meaning`} value={field.meaning}
                             onChange={(meaningChoice) => updateField(field.key, { meaning: meaningChoice })} disabled={saving} />
              <ConceptPicker label={`Field ${i + 1} purpose`} value={field.purpose}
                             onChange={(purposeChoice) => updateField(field.key, { purpose: purposeChoice })} disabled={saving} />
            </div>
            <label htmlFor={`new-pattern-type-${field.key}`} className="concept-picker-label">
              Field {i + 1} data type
            </label>
            <select
              id={`new-pattern-type-${field.key}`}
              value={field.dataTypeId}
              onChange={(e) => updateField(field.key, { dataTypeId: e.target.value })}
              disabled={saving || dataTypes.isLoading}
            >
              <option value="" disabled>
                {dataTypes.isLoading ? 'Loading data types...' : 'Choose a data type'}
              </option>
              {dataTypes.data?.dataTypes.map((t) => (
                <option key={t.id} value={t.id}>{t.name}</option>
              ))}
            </select>
          </div>
        ))}
        <button type="button" className="link-button new-pattern-add" disabled={saving}
                onClick={() => setFields((all) => [...all, emptyField()])}>
          + Add field
        </button>
      </fieldset>

      {dataTypes.isError && <div className="error-message">Could not load the field data types.</div>}
      {error && <div className="error-message" role="alert">{error}</div>}

      <div className="new-pattern-actions">
        <button type="submit" className="changeset-run" disabled={saving}>
          {saving ? 'Publishing...' : 'Publish'}
        </button>
        <button type="button" className="link-button" onClick={onCancel} disabled={saving}>
          Cancel
        </button>
      </div>
    </form>
  );
}
