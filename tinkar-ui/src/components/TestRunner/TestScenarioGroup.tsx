import { useState } from 'react';
import type { TestGroup } from '../../api/types';
import { TestResultRow } from './TestResultRow';

interface TestScenarioGroupProps {
  group: TestGroup;
  onRunGroup?: (groupId: string) => void;
  isRunning?: boolean;
  isGroupRunning?: boolean;
}

export function TestScenarioGroup({ group, onRunGroup, isRunning, isGroupRunning }: TestScenarioGroupProps) {
  const [expanded, setExpanded] = useState(true);

  const pass = group.tests.filter((t) => t.status === 'pass').length;
  const fail = group.tests.filter((t) => t.status === 'fail').length;
  const skip = group.tests.filter((t) => t.status === 'skip').length;

  return (
    <div className="test-scenario-group">
      <div
        className="scenario-group-header"
        onClick={() => setExpanded(!expanded)}
      >
        <span className="scenario-group-name">
          {expanded ? '\u25BC' : '\u25B6'} {group.name}
        </span>
        <div className="scenario-group-counts">
          {pass > 0 && <span className="summary-pass">{pass} pass</span>}
          {fail > 0 && <span className="summary-fail">{fail} fail</span>}
          {skip > 0 && <span className="summary-skip">{skip} skip</span>}
          <span className="summary-total">{group.tests.length} total</span>
          {onRunGroup && (
            <button
              className={`run-group-button${isGroupRunning ? ' running' : ''}`}
              disabled={isRunning}
              onClick={(e) => {
                e.stopPropagation();
                onRunGroup(group.id);
              }}
            >
              {isGroupRunning ? 'Running...' : 'Run'}
            </button>
          )}
        </div>
      </div>
      {expanded && (
        <div className="scenario-group-body">
          {group.description && (
            <div className="scenario-group-description">{group.description}</div>
          )}
          {group.tests.map((test) => (
            <TestResultRow key={test.id} result={test} />
          ))}
        </div>
      )}
    </div>
  );
}
