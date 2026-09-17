import { useState } from 'react';
import { runReasonerStreaming } from '../api/tinkarApi';
import type { ReasonerPhaseEvent, ReasonerResultsResponse } from '../api/types';

interface ReasonerPanelProps {
  onBack: () => void;
}

/**
 * The result sections, named and ordered as Komet's reasoner results accordion names them
 * (`ReasonerResultsInterface.fxml`), so someone who knows the Komet view recognises this one.
 */
const RESULT_SECTIONS: {
  label: string;
  key: keyof ReasonerResultsResponse;
  /** Komet leaves the concept set as a bare size and never lets it expand; say so here too. */
  note?: string;
}[] = [
  { label: 'Concept set size', key: 'classifiedConceptCount' },
  { label: 'Inferred changes', key: 'inferredChangesCount' },
  { label: 'Navigation changes', key: 'navigationChangesCount' },
  { label: 'Equivalencies', key: 'equivalentSetsCount' },
  { label: 'Cycles', key: 'cyclesCount' },
  { label: 'Orphans', key: 'orphansCount' },
];

/** Komet writes "none" rather than 0, so an empty result reads as a finding, not a blank. */
function formatCount(value: number | null | undefined): string {
  if (value == null) return '—';
  return value === 0 ? 'none' : value.toLocaleString();
}

/**
 * Runs the reasoner and shows its four phases live, then the counts.
 *
 * Progress comes from the same `ReasonerPhaseListener` the gRPC call uses, so the wording
 * matches what Komet shows for a local run.
 */
export function ReasonerPanel({ onBack }: ReasonerPanelProps) {
  const [phase, setPhase] = useState<ReasonerPhaseEvent | null>(null);
  const [isRunning, setIsRunning] = useState(false);
  const [result, setResult] = useState<ReasonerResultsResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  const run = async () => {
    setIsRunning(true);
    setPhase(null);
    setResult(null);
    setError(null);
    try {
      const outcome = await runReasonerStreaming(setPhase);
      if (outcome.success) {
        setResult(outcome);
      } else {
        setError(outcome.errorMessage ?? 'Reasoner failed');
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Reasoner failed');
    } finally {
      setIsRunning(false);
    }
  };

  const percent = phase ? Math.round((phase.step / phase.totalSteps) * 100) : 0;

  return (
    <div className="reasoner-panel">
      <div className="reasoner-header">
        <button className="back-button" onClick={onBack}>&larr; Back</button>
        <h2>Reasoner</h2>
      </div>

      <p className="reasoner-hint">
        
      </p>

      <button className="reasoner-run" onClick={run} disabled={isRunning}>
        {isRunning ? 'Running…' : 'Run Reasoner'}
      </button>

      {(isRunning || phase) && (
        <div className="reasoner-progress">
          <div className="reasoner-steps">
            {Array.from({ length: phase?.totalSteps ?? 4 }, (_, i) => i + 1).map((step) => (
              <span
                key={step}
                className={`reasoner-step ${phase && step <= phase.step ? 'done' : ''}`}
              />
            ))}
          </div>
          <div className="reasoner-progress-bar">
            <div className="reasoner-progress-fill" style={{ width: `${percent}%` }} />
          </div>
          <p className="reasoner-progress-label">
            {phase
              ? `Step ${phase.step} of ${phase.totalSteps}: ${phase.message}`
              : 'Starting…'}
          </p>
        </div>
      )}

      {result && (
        <div className="reasoner-results">
          <h3>Results</h3>
          <table>
            <tbody>
              {RESULT_SECTIONS.map(({ label, key, note }) => (
                <tr key={key}>
                  <th>{label}</th>
                  <td>{note ?? formatCount(result[key] as number | null)}</td>
                </tr>
              ))}
              <tr>
                <th>Duration</th>
                <td>
                  {result.durationMs != null
                    ? `${(result.durationMs / 1000).toFixed(1)}s`
                    : '—'}
                </td>
              </tr>
            </tbody>
          </table>
          <p className="reasoner-hint">
            Counts only. Komet's results view can expand each section to list the concepts; this
            endpoint returns totals, which is what a dashboard shows without moving hundreds of
            thousands of ids over the wire.
          </p>
        </div>
      )}

      {error && <p className="reasoner-error">{error}</p>}
    </div>
  );
}
