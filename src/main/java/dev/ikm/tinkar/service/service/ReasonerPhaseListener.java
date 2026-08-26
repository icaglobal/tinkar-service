/*
 * Copyright © 2015 Integrated Knowledge Management (support@ikm.dev)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.ikm.tinkar.service.service;

/**
 * Notified as each phase of the reasoner pipeline completes.
 *
 * <p>The phase numbering deliberately mirrors Komet's local
 * {@code RunReasonerTaskBase}, which reports four steps through a
 * {@code TrackingCallable}. Keeping the count and the wording aligned means a
 * caller can drive the same progress UI whether the classification ran locally
 * or on this service.
 */
@FunctionalInterface
public interface ReasonerPhaseListener {

    /**
     * The phases of the pipeline, in order.
     *
     * <p>Step number and wording are kept together so the two cannot drift apart, and the
     * wording matches Komet's local {@code RunReasonerTaskBase} verbatim — a caller showing
     * progress should not be able to tell from the text whether the run was local or remote.
     *
     * <p>Declaration order defines the step numbers and {@link #TOTAL_STEPS}, so adding or
     * removing a phase here needs no other edit.
     */
    enum Phase {
        LOAD_DATA("Loading data into reasoner"),
        COMPUTE_INFERENCES("Computing inferences"),
        BUILD_NECESSARY_NORMAL_FORM("Building necessary normal form"),
        PROCESS_RESULTS("Processing results");

        private final String message;

        Phase(String message) {
            this.message = message;
        }

        /** 1-based position in the pipeline. */
        public int step() {
            return ordinal() + 1;
        }

        /** What this phase did, in Komet's wording. */
        public String message() {
            return message;
        }
    }

    /**
     * Number of phases in the pipeline, matching Komet's {@code maxWork}. Derived from
     * {@link Phase} rather than written out, so it cannot fall out of step with it.
     */
    int TOTAL_STEPS = Phase.values().length;

    /** No-op listener, for callers that do not want progress. */
    ReasonerPhaseListener NONE = (step, total, message) -> { };

    /**
     * @param step       1-based phase that just completed
     * @param totalSteps always {@link #TOTAL_STEPS}; passed so the caller need
     *                   not hard-code it
     * @param message    what the phase did, in the same words Komet uses
     */
    void onPhaseComplete(int step, int totalSteps, String message);

    /**
     * Reports a completed phase, taking its step number and wording from the enum. Preferred
     * over the three-argument form at call sites, which cannot then pair a step with the wrong
     * message.
     */
    default void onPhaseComplete(Phase phase) {
        onPhaseComplete(phase.step(), TOTAL_STEPS, phase.message());
    }
}
