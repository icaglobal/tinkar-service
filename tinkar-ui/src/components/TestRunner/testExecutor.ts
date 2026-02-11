import type { TestResult, TestRunSummary } from '../../api/types';
import type { TestGroupDefinition, TestContext } from './testDefinitions';

export type TestProgressCallback = (
  groupId: string,
  testId: string,
  result: TestResult,
) => void;

function createTestContext(): TestContext {
  const data: Record<string, string> = {};
  return {
    data,
    set: (key: string, value: string) => { data[key] = value; },
    get: (key: string) => data[key],
  };
}

export async function runAllTests(
  groups: TestGroupDefinition[],
  onProgress: TestProgressCallback,
  onComplete: (summary: TestRunSummary) => void,
  abortSignal?: AbortSignal,
): Promise<void> {
  const ctx = createTestContext();
  const summary: TestRunSummary = { pass: 0, fail: 0, skip: 0, total: 0, isRunning: true };

  for (const group of groups) {
    for (const test of group.tests) {
      if (abortSignal?.aborted) {
        summary.isRunning = false;
        onComplete(summary);
        return;
      }

      // Signal running state
      onProgress(group.id, test.id, {
        id: test.id,
        name: test.name,
        status: 'running',
      });

      const startTime = performance.now();
      try {
        const outcome = await test.run(ctx);
        const duration = Math.round(performance.now() - startTime);
        summary[outcome.status]++;
        summary.total++;
        onProgress(group.id, test.id, {
          id: test.id,
          name: test.name,
          status: outcome.status,
          detail: outcome.detail,
          responseData: outcome.responseData,
          durationMs: duration,
        });
      } catch (err) {
        const duration = Math.round(performance.now() - startTime);
        summary.fail++;
        summary.total++;
        onProgress(group.id, test.id, {
          id: test.id,
          name: test.name,
          status: 'fail',
          detail: err instanceof Error ? err.message : String(err),
          durationMs: duration,
        });
      }
    }
  }

  summary.isRunning = false;
  onComplete(summary);
}
