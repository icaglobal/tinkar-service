import { useCallback, useRef, useState } from 'react';
import type { TestGroup, TestResult, TestRunSummary } from '../../api/types';
import { allTestGroups } from './testDefinitions';
import { runAllTests } from './testExecutor';
import { TestScenarioGroup } from './TestScenarioGroup';
import { TestSummaryBar } from './TestSummaryBar';

interface TestRunnerProps {
  onBack: () => void;
}

function buildInitialGroups(): TestGroup[] {
  return allTestGroups.map((g) => ({
    id: g.id,
    name: g.name,
    description: g.description,
    tests: g.tests.map((t) => ({
      id: t.id,
      name: t.name,
      status: 'pending' as const,
    })),
  }));
}

const INITIAL_SUMMARY: TestRunSummary = {
  pass: 0,
  fail: 0,
  skip: 0,
  total: 0,
  isRunning: false,
};

export function TestRunner({ onBack }: TestRunnerProps) {
  const [groups, setGroups] = useState<TestGroup[]>(buildInitialGroups);
  const [summary, setSummary] = useState<TestRunSummary>(INITIAL_SUMMARY);
  const [isRunning, setIsRunning] = useState(false);
  const abortRef = useRef<AbortController | null>(null);

  const handleProgress = useCallback(
    (groupId: string, testId: string, result: TestResult) => {
      setGroups((prev) =>
        prev.map((g) =>
          g.id === groupId
            ? { ...g, tests: g.tests.map((t) => (t.id === testId ? result : t)) }
            : g,
        ),
      );
      // Update running summary counts from the result
      const s = result.status;
      if (s === 'pass' || s === 'fail' || s === 'skip') {
        setSummary((prev) => ({
          ...prev,
          pass: prev.pass + (s === 'pass' ? 1 : 0),
          fail: prev.fail + (s === 'fail' ? 1 : 0),
          skip: prev.skip + (s === 'skip' ? 1 : 0),
          total: prev.total + 1,
        }));
      }
    },
    [],
  );

  const handleRun = useCallback(async () => {
    // Reset state
    setGroups(buildInitialGroups());
    setSummary({ ...INITIAL_SUMMARY, isRunning: true });
    setIsRunning(true);

    const controller = new AbortController();
    abortRef.current = controller;

    await runAllTests(
      allTestGroups,
      handleProgress,
      (finalSummary) => {
        setSummary(finalSummary);
        setIsRunning(false);
      },
      controller.signal,
    );
  }, [handleProgress]);

  const handleCancel = useCallback(() => {
    abortRef.current?.abort();
    setIsRunning(false);
    setSummary((prev) => ({ ...prev, isRunning: false }));
  }, []);

  return (
    <div className="test-runner">
      <button className="back-button" onClick={onBack}>
        &larr; Back to search
      </button>

      <div className="test-runner-header">
        <h2>DeX Scenario Test Runner</h2>
        <p className="test-runner-subtitle">
          Tests the REST API against the three scenarios from the DeX into Elsa document
        </p>
      </div>

      <TestSummaryBar summary={summary} />

      <div className="test-runner-controls">
        {!isRunning ? (
          <button className="run-tests-button" onClick={handleRun}>
            Run Tests
          </button>
        ) : (
          <button className="cancel-tests-button" onClick={handleCancel}>
            Cancel
          </button>
        )}
      </div>

      <div className="test-runner-groups">
        {groups.map((group) => (
          <TestScenarioGroup key={group.id} group={group} />
        ))}
      </div>
    </div>
  );
}
