import { useEffect, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import {
  cancelReasoner,
  runReasonerStreaming,
  watchReasonerStreaming,
  type ReasonerStreamHandlers,
} from '../api/tinkarApi';
import type { ReasonerPhaseEvent, ReasonerResultsResponse, ReasonerRunEvent } from '../api/types';

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

function formatTime(epochMs: number): string {
  return new Date(epochMs).toLocaleTimeString();
}

/**
 * Runs the reasoner and shows its four phases live, then the counts.
 *
 * Progress comes from the same `ReasonerPhaseListener` the gRPC call uses, so the wording
 * matches what Komet shows for a local run.
 *
 * The run belongs to the server, not to this page. Opening the panel reconnects to a run that
 * is still going — or shows how the last one ended — and leaving it only stops watching.
 * Stopping the run is the Cancel button, which asks the server to.
 */
export function ReasonerPanel({ onBack }: ReasonerPanelProps) {
  const queryClient = useQueryClient();
  const [runInfo, setRunInfo] = useState<ReasonerRunEvent | null>(null);
  const [phase, setPhase] = useState<ReasonerPhaseEvent | null>(null);
  const [isRunning, setIsRunning] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const [result, setResult] = useState<ReasonerResultsResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [cancelled, setCancelled] = useState(false);
  const abortRef = useRef<AbortController | null>(null);

  /** Follows one stream to its end, whether it started the run or joined it. */
  const follow = async (
    open: (handlers: ReasonerStreamHandlers, signal: AbortSignal) => Promise<ReasonerResultsResponse | null>
  ) => {
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    setPhase(null);
    setResult(null);
    setError(null);
    setCancelled(false);
    setCancelling(false);
    setRunInfo(null);
    setIsRunning(true);
    try {
      const outcome = await open(
        {
          onRun: setRunInfo,
          onPhase: setPhase,
        },
        controller.signal
      );
      if (controller.signal.aborted) return;
      if (!outcome) {
        // No run since the server started: nothing to show.
      } else if (outcome.success) {
        // Classification rewrites the inferred hierarchy, so every cached hierarchy, children
        // list and semantics view may now be wrong. Stale, so each re-fetches on next view.
        queryClient.invalidateQueries();
        setResult(outcome);
      } else if (outcome.cancelled) {
        setCancelled(true);
      } else {
        setError(outcome.errorMessage ?? 'Reasoner failed');
      }
    } catch (e) {
      // Aborted means this page stopped watching — the run is unaffected, so not an error.
      if (!controller.signal.aborted) {
        setError(e instanceof Error ? e.message : 'Reasoner failed');
      }
    } finally {
      if (abortRef.current === controller) {
        abortRef.current = null;
        setIsRunning(false);
        setCancelling(false);
      }
    }
  };

  // Reconnect on open; stop watching (only) on leave.
  useEffect(() => {
    follow(watchReasonerStreaming);
    return () => abortRef.current?.abort();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const run = () => follow(runReasonerStreaming);

  const cancel = async () => {
    setCancelling(true);
    try {
      await cancelReasoner();
      // The stream stays open: the run reports its own end, with `cancelled` set.
    } catch (e) {
      setCancelling(false);
      setError(e instanceof Error ? e.message : 'Cancel failed');
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
        The classification runs on the server. You can leave this page or close the browser and
        it carries on; come back here to reconnect to it, or to see how it ended.
      </p>

      <div className="reasoner-actions">
        <button className="reasoner-run" onClick={run} disabled={isRunning}>
          {isRunning ? 'Running…' : 'Run Reasoner'}
        </button>
        {isRunning && (
          <button className="reasoner-cancel" onClick={cancel} disabled={cancelling}>
            {cancelling ? 'Cancelling…' : 'Cancel'}
          </button>
        )}
      </div>

      {runInfo && !runInfo.started && (
        <p className="reasoner-hint">
          {isRunning
            ? `Reconnected to a run started at ${formatTime(runInfo.startedAt)}.`
            : `Last run, started at ${formatTime(runInfo.startedAt)}.`}
        </p>
      )}

      {/* Only while running: after a cancel or failure the last phase would otherwise stay on
          screen and read as a run still in progress. */}
      {isRunning && (
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

      {cancelled && (
        <p className="reasoner-hint">
          Cancelled{phase ? ` during step ${phase.step} of ${phase.totalSteps} (${phase.message})` : ''}.
          The classification stopped on the server and nothing was written.
        </p>
      )}

      {error && <p className="reasoner-error">{error}</p>}
    </div>
  );
}
