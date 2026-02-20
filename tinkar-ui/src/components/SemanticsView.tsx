import { useState } from 'react';
import type { ConceptSemanticsResponse, SemanticInfo } from '../api/types';

interface SemanticsViewProps {
  data: ConceptSemanticsResponse;
  conceptName: string;
  onBack: () => void;
}

function SemanticCard({ semantic }: { semantic: SemanticInfo }) {
  const [showStamp, setShowStamp] = useState(false);

  return (
    <div className="semantic-card">
      <div className="semantic-card-header">
        <h4 className="semantic-pattern-name">{semantic.patternName}</h4>
        <span className="semantic-id">{semantic.semanticId}</span>
      </div>

      <div className="semantic-fields">
        {semantic.fields.map((field, index) => (
          <div key={index} className="semantic-field">
            <span className="field-name">{field.fieldName}:</span>
            <span className="field-value" title={`Type: ${field.fieldType}`}>
              {field.value || <em className="empty-value">(empty)</em>}
            </span>
          </div>
        ))}
      </div>

      <button
        className="stamp-toggle"
        onClick={() => setShowStamp(!showStamp)}
      >
        {showStamp ? '▼' : '▶'} Stamp Info
      </button>

      {showStamp && (
        <div className="semantic-stamp">
          <div className="stamp-row">
            <span className="stamp-label">Status:</span>
            <span className="stamp-value">{semantic.stamp.status}</span>
          </div>
          <div className="stamp-row">
            <span className="stamp-label">Time:</span>
            <span className="stamp-value">{semantic.stamp.time}</span>
          </div>
          <div className="stamp-row">
            <span className="stamp-label">Author:</span>
            <span className="stamp-value">{semantic.stamp.author}</span>
          </div>
          <div className="stamp-row">
            <span className="stamp-label">Module:</span>
            <span className="stamp-value">{semantic.stamp.module}</span>
          </div>
          <div className="stamp-row">
            <span className="stamp-label">Path:</span>
            <span className="stamp-value">{semantic.stamp.path}</span>
          </div>
        </div>
      )}
    </div>
  );
}

export function SemanticsView({ data, conceptName, onBack }: SemanticsViewProps) {
  // Group semantics by pattern name for better organization
  const groupedSemantics = data.semantics.reduce((acc, semantic) => {
    const key = semantic.patternName;
    if (!acc[key]) {
      acc[key] = [];
    }
    acc[key].push(semantic);
    return acc;
  }, {} as Record<string, SemanticInfo[]>);

  const patternNames = Object.keys(groupedSemantics).sort();

  return (
    <div className="semantics-view">
      <button className="back-button" onClick={onBack}>
        &larr; Back to search results
      </button>

      <div className="semantics-header">
        <h2 className="results-title">Semantics for: {conceptName}</h2>
        <p className="concept-id-display">ID: {data.conceptId}</p>
        <p className="semantics-count">
          {data.semantics.length} semantic{data.semantics.length !== 1 ? 's' : ''} found
          {patternNames.length > 1 && ` across ${patternNames.length} patterns`}
        </p>
      </div>

      {data.semantics.length === 0 ? (
        <p className="no-results">No semantics found for this concept</p>
      ) : (
        <div className="semantics-groups">
          {patternNames.map((patternName) => (
            <div key={patternName} className="semantics-pattern-group">
              <h3 className="pattern-group-header">
                {patternName}
                <span className="pattern-count">
                  ({groupedSemantics[patternName].length})
                </span>
              </h3>
              <div className="semantics-list">
                {groupedSemantics[patternName].map((semantic) => (
                  <SemanticCard key={semantic.semanticId} semantic={semantic} />
                ))}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
