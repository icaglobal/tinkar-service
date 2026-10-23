import type { TestRunSummary } from '../../api/types';

interface TestSummaryBarProps {
  summary: TestRunSummary;
}

export function TestSummaryBar({ summary }: TestSummaryBarProps) {
  return (
    <div className="test-summary-bar">
      <span className="summary-pass">PASS: {summary.pass}</span>
      <span className="summary-fail">FAIL: {summary.fail}</span>
      <span className="summary-skip">SKIP: {summary.skip}</span>
      <span className="summary-total">TOTAL: {summary.total}</span>
      {summary.isRunning && <span className="summary-running">Running...</span>}
    </div>
  );
}
