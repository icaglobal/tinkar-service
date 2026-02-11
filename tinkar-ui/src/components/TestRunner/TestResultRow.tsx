import { useState } from 'react';
import type { TestResult } from '../../api/types';

interface TestResultRowProps {
  result: TestResult;
}

const STATUS_LABELS: Record<string, string> = {
  pass: 'P',
  fail: 'F',
  skip: 'S',
  running: '',
  pending: '',
};

export function TestResultRow({ result }: TestResultRowProps) {
  const [expanded, setExpanded] = useState(false);
  const hasData = result.responseData !== undefined;

  return (
    <div className="test-result-row">
      <div
        className="test-result-header"
        onClick={() => hasData && setExpanded(!expanded)}
        style={{ cursor: hasData ? 'pointer' : 'default' }}
      >
        <span className={`test-status-badge ${result.status}`}>
          {STATUS_LABELS[result.status]}
        </span>
        <span className="test-result-name">{result.name}</span>
        {result.detail && (
          <span className="test-result-detail">{result.detail}</span>
        )}
        {result.durationMs !== undefined && (
          <span className="test-result-duration">{result.durationMs}ms</span>
        )}
        {hasData && (
          <button
            className="test-result-expand"
            onClick={(e) => { e.stopPropagation(); setExpanded(!expanded); }}
          >
            {expanded ? 'Hide' : 'JSON'}
          </button>
        )}
      </div>
      {expanded && hasData && (
        <div className="test-response-data">
          <pre>{JSON.stringify(result.responseData, null, 2)}</pre>
        </div>
      )}
    </div>
  );
}
