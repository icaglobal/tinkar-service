import { useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  absoluteDownloadUrl,
  cancelJob,
  exportStreaming,
  importChangesetStreaming,
  listJobs,
  watchJob,
  type JobStreamHandlers,
} from '../api/tinkarApi';
import type {
  ChangesetJobResult,
  ExportRequest,
  ExportType,
  JobAttachedEvent,
  JobProgressEvent,
  JobSummary,
} from '../api/types';

interface ChangesetPanelProps {
  onBack: () => void;
}

/** The job this panel is following: what the server has said about it so far. */
type FollowedJob = {
  job: JobAttachedEvent | null;
  ahead: JobSummary[];
  started: boolean;
  progress: JobProgressEvent | null;
  result: ChangesetJobResult | null;
  error: string | null;
};

const EMPTY: FollowedJob = { job: null, ahead: [], started: false, progress: null, result: null, error: null };

const EXPORT_TYPES: { value: ExportType; label: string }[] = [
  { value: 'TEMPORAL', label: 'Changes in a time range' },
  { value: 'FULL', label: 'Everything' },
  { value: 'MEMBERSHIP', label: 'Members of tags' },
];

/** A `datetime-local` input value for `date`, in local time. */
function toLocalInput(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function formatTime(epochMs: number): string {
  return new Date(epochMs).toLocaleTimeString();
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function kindLabel(kind: JobSummary['kind']): string {
  return kind === 'REASONER' ? 'Reasoner' : kind === 'IMPORT' ? 'Import' : 'Export';
}

/**
 * Imports and exports changesets — the zips Komet's Import/Export Dataset reads and writes.
 *
 * Both run as server jobs, one at a time behind any reasoner run or other import or export, so a
 * request may wait before it starts; the panel says what it is waiting behind. Leaving the page
 * only stops watching: the job carries on, and reopening the panel picks it back up. Imports
 * cannot be cancelled — stopped part way they would leave some entities written.
 */
export function ChangesetPanel({ onBack }: ChangesetPanelProps) {
  const queryClient = useQueryClient();
  const [followed, setFollowed] = useState<FollowedJob>(EMPTY);
  const [busy, setBusy] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const abortRef = useRef<AbortController | null>(null);
  // The kind of job being followed, set when it is started or rejoined: its result is handled
  // after the stream ends, when React state may still be a render behind.
  const followedKindRef = useRef<JobSummary['kind'] | null>(null);

  const [file, setFile] = useState<File | null>(null);
  const [exportType, setExportType] = useState<ExportType>('TEMPORAL');
  const [from, setFrom] = useState(() => toLocalInput(new Date(Date.now() - 7 * 24 * 3600 * 1000)));
  const [to, setTo] = useState(() => toLocalInput(new Date()));
  const [tagIds, setTagIds] = useState('');

  const jobs = useQuery({
    queryKey: ['admin-jobs'],
    queryFn: listJobs,
    refetchInterval: 3000,
    staleTime: 0,
  });

  /** Follows one job stream to its end, replacing whatever was followed before. */
  const follow = async (open: (handlers: JobStreamHandlers, signal: AbortSignal) => Promise<ChangesetJobResult>) => {
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    setFollowed(EMPTY);
    setBusy(true);
    setCancelling(false);
    try {
      const result = await open(
        {
          onJob: (job) => setFollowed((f) => ({ ...f, job })),
          onQueued: (ahead) => setFollowed((f) => ({ ...f, ahead })),
          onStarted: () => setFollowed((f) => ({ ...f, started: true, ahead: [] })),
          onProgress: (progress) => setFollowed((f) => ({ ...f, progress })),
        },
        controller.signal
      );
      if (controller.signal.aborted) return;
      setFollowed((f) => ({ ...f, result }));
      if (result.success && followedKindRef.current === 'IMPORT') {
        // New entities: every cached search, hierarchy and semantics view may now be stale.
        queryClient.invalidateQueries();
      }
    } catch (e) {
      if (!controller.signal.aborted) {
        setFollowed((f) => ({ ...f, error: e instanceof Error ? e.message : 'Request failed' }));
      }
    } finally {
      if (abortRef.current === controller) {
        abortRef.current = null;
        setBusy(false);
        setCancelling(false);
        jobs.refetch();
      }
    }
  };

  // Reconnect to the newest unfinished import or export on open; stop watching (only) on leave.
  const reconnected = useRef(false);
  useEffect(() => {
    if (reconnected.current || !jobs.data) return;
    reconnected.current = true;
    const active = [...jobs.data]
      .reverse()
      .find((j) => j.kind !== 'REASONER' && (j.state === 'QUEUED' || j.state === 'RUNNING'));
    if (active) {
      followedKindRef.current = active.kind;
      follow((handlers, signal) => watchJob(active.id, handlers, signal));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [jobs.data]);
  useEffect(() => () => abortRef.current?.abort(), []);

  const startImport = () => {
    if (!file) return;
    followedKindRef.current = 'IMPORT';
    follow((handlers, signal) => importChangesetStreaming(file, handlers, signal));
  };

  const exportRequest = (): ExportRequest | string => {
    if (exportType === 'FULL') return { type: 'FULL' };
    if (exportType === 'TEMPORAL') {
      // The inputs only go down to the minute, so the range covers the whole of its last minute:
      // "to 11:05" must include a commit at 11:05:00.4, which a reasoner run just finished can make.
      const fromMs = new Date(from).getTime();
      const toMs = new Date(to).getTime() + 59_999;
      if (Number.isNaN(fromMs) || Number.isNaN(toMs)) return 'Choose both a start and an end time.';
      if (fromMs > toMs) return 'The start time is after the end time.';
      return { type: 'TEMPORAL', fromEpochMillis: fromMs, toEpochMillis: toMs };
    }
    const ids = tagIds.split(/[\s,]+/).filter(Boolean);
    if (ids.length === 0) return 'Enter at least one tag pattern UUID.';
    return { type: 'MEMBERSHIP', membershipTagIds: ids };
  };

  const startExport = () => {
    const request = exportRequest();
    if (typeof request === 'string') {
      setFollowed({ ...EMPTY, error: request });
      return;
    }
    followedKindRef.current = 'EXPORT';
    follow((handlers, signal) => exportStreaming(request, handlers, signal));
  };

  const cancel = async (jobId: string) => {
    setCancelling(true);
    try {
      await cancelJob(jobId);
    } catch (e) {
      setCancelling(false);
      setFollowed((f) => ({ ...f, error: e instanceof Error ? e.message : 'Cancel failed' }));
    }
    jobs.refetch();
  };

  const rejoin = (job: JobSummary) => {
    followedKindRef.current = job.kind;
    follow((handlers, signal) => watchJob(job.id, handlers, signal));
  };

  const { job, ahead, started, progress, result, error } = followed;
  const determinate = progress != null && progress.total > 1 && progress.done >= 0;
  const percent = determinate ? Math.min(100, Math.round((progress!.done / progress!.total) * 100)) : 0;

  return (
    <div className="changeset-panel">
      <div className="reasoner-header">
        <button className="back-button" onClick={onBack}>&larr; Back</button>
        <h2>Import / Export</h2>
      </div>

      <p className="reasoner-hint">
        Changesets are the zips Komet&rsquo;s Import / Export Dataset reads and writes. Imports and exports run
        on the server one at a time, behind any reasoner run. You can leave this page and come back to a
        running job.
      </p>

      <section className="changeset-section">
        <h3>Import</h3>
        <div className="changeset-row">
          <input
            id="changeset-file"
            type="file"
            accept=".zip"
            onChange={(e) => setFile(e.target.files?.[0] ?? null)}
          />
          <button className="changeset-run" onClick={startImport} disabled={!file || busy}>
            Import
          </button>
        </div>
        <p className="reasoner-hint">Imports cannot be cancelled once started. Importing a file twice is harmless.</p>
      </section>

      <section className="changeset-section">
        <h3>Export</h3>
        <div className="changeset-row">
          <select id="export-type" value={exportType} onChange={(e) => setExportType(e.target.value as ExportType)}>
            {EXPORT_TYPES.map((t) => (
              <option key={t.value} value={t.value}>{t.label}</option>
            ))}
          </select>
          <button className="changeset-run" onClick={startExport} disabled={busy}>
            Export
          </button>
        </div>
        {exportType === 'TEMPORAL' && (
          <div className="changeset-row">
            <label>
              From <input id="export-from" type="datetime-local" value={from} onChange={(e) => setFrom(e.target.value)} />
            </label>
            <label>
              To <input id="export-to" type="datetime-local" value={to} onChange={(e) => setTo(e.target.value)} />
            </label>
          </div>
        )}
        {exportType === 'MEMBERSHIP' && (
          <textarea
            id="export-tags"
            className="changeset-tags"
            placeholder="Tag pattern UUIDs, one per line"
            value={tagIds}
            onChange={(e) => setTagIds(e.target.value)}
          />
        )}
        <p className="reasoner-hint">
          {exportType === 'TEMPORAL'
            ? 'Every concept and semantic changed in the range, with its full history. The server reads the whole store to find them, so this takes minutes on a large dataset.'
            : exportType === 'FULL'
              ? 'The whole store. Large on a full dataset.'
              : 'Everything tagged by these membership patterns.'}
        </p>
      </section>

      {(job || error) && (
        <section className="changeset-job">
          {job && (
            <div className="changeset-job-head">
              <strong>{job.label}</strong>
              {!job.submitted && <span className="reasoner-hint"> — reconnected, asked for at {formatTime(job.queuedAt)}</span>}
            </div>
          )}

          {busy && ahead.length > 0 && !started && (
            <p className="reasoner-progress-label">
              Waiting behind: {ahead.map((a) => `${kindLabel(a.kind)} (${a.state.toLowerCase()})`).join(', ')}
            </p>
          )}

          {busy && (started || ahead.length === 0) && (
            <div className="reasoner-progress">
              <div className={`reasoner-progress-bar ${determinate ? '' : 'indeterminate'}`}>
                <div className="changeset-progress-fill" style={{ width: determinate ? `${percent}%` : '30%' }} />
              </div>
              <p className="reasoner-progress-label">
                {progress?.message || (started ? 'Working…' : 'Starting…')}
                {determinate && ` — ${percent}%`}
              </p>
            </div>
          )}

          {busy && job && job.kind !== 'IMPORT' && (
            <button className="reasoner-cancel" onClick={() => cancel(job.jobId)} disabled={cancelling}>
              {cancelling ? 'Cancelling…' : 'Cancel'}
            </button>
          )}

          {result && result.success && (
            <div className="reasoner-results">
              <table>
                <tbody>
                  <tr><th>Concepts</th><td>{result.conceptsCount?.toLocaleString()}</td></tr>
                  <tr><th>Semantics</th><td>{result.semanticsCount?.toLocaleString()}</td></tr>
                  <tr><th>Patterns</th><td>{result.patternsCount?.toLocaleString()}</td></tr>
                  <tr><th>Stamps</th><td>{result.stampsCount?.toLocaleString()}</td></tr>
                  <tr><th>Duration</th><td>{result.durationMs != null ? `${(result.durationMs / 1000).toFixed(1)}s` : '—'}</td></tr>
                </tbody>
              </table>
              {result.downloadUrl && (
                <p>
                  <a className="changeset-download" href={absoluteDownloadUrl(result.downloadUrl)}>
                    Download {result.fileName}
                  </a>
                  {result.fileSizeBytes != null && <span className="reasoner-hint"> ({formatBytes(result.fileSizeBytes)}, kept for an hour)</span>}
                </p>
              )}
            </div>
          )}
          {result && result.cancelled && <p className="reasoner-hint">Cancelled. No file was kept.</p>}
          {result && !result.success && !result.cancelled && <p className="reasoner-error">{result.errorMessage}</p>}
          {error && <p className="reasoner-error">{error}</p>}
        </section>
      )}

      <section className="changeset-section">
        <h3>Recent jobs</h3>
        {jobs.data && jobs.data.length === 0 && <p className="reasoner-hint">None in the last hour.</p>}
        {jobs.data && jobs.data.length > 0 && (
          <table className="changeset-jobs">
            <tbody>
              {[...jobs.data].reverse().map((j) => (
                <tr key={j.id}>
                  <td>{formatTime(j.queuedAt)}</td>
                  <td>{j.label}</td>
                  <td className={`job-state job-${j.state.toLowerCase()}`}>{j.state.toLowerCase()}</td>
                  <td className="changeset-job-actions">
                    {j.kind === 'EXPORT' && j.state === 'SUCCEEDED' && (
                      <a href={absoluteDownloadUrl(`/api/ike/admin/export/${j.id}/file`)}>Download</a>
                    )}
                    {j.kind !== 'REASONER' && j.id !== job?.jobId && (
                      <button className="link-button" onClick={() => rejoin(j)}>
                        {j.state === 'QUEUED' || j.state === 'RUNNING' ? 'Watch' : 'Details'}
                      </button>
                    )}
                    {j.kind !== 'IMPORT' && (j.state === 'QUEUED' || j.state === 'RUNNING') && (
                      <button className="link-button" onClick={() => cancel(j.id)}>Cancel</button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </div>
  );
}

