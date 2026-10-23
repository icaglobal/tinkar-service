import { useState } from 'react';
import type { ConceptSearchWithSortResponse } from '../api/types';

interface SortedResultsTableProps {
  searchData: ConceptSearchWithSortResponse;
  onViewSemantics?: (conceptId: string, conceptName?: string) => void;
  onViewDescendants?: (conceptId: string, conceptName?: string) => void;
  showInactive?: boolean;
}

export function SortedResultsTable({ searchData, onViewSemantics, onViewDescendants, showInactive = false }: SortedResultsTableProps) {
  const [expandedConcepts, setExpandedConcepts] = useState<Set<string>>(new Set());

  const isGroupedMode = searchData.sortBy === 'TOP_COMPONENT' || searchData.sortBy === 'TOP_COMPONENT_ALPHA';

  const toggleExpanded = (conceptId: string) => {
    setExpandedConcepts((prev) => {
      const next = new Set(prev);
      if (next.has(conceptId)) {
        next.delete(conceptId);
      } else {
        next.add(conceptId);
      }
      return next;
    });
  };

  // Render highlighted text with bold tags converted to actual bold
  const renderHighlightedText = (text: string | null) => {
    if (!text) return null;
    // Replace <B> and </B> tags with actual bold styling
    const parts = text.split(/(<\/?[Bb]>)/);
    let isBold = false;
    return parts.map((part, index) => {
      if (part.toLowerCase() === '<b>') {
        isBold = true;
        return null;
      }
      if (part.toLowerCase() === '</b>') {
        isBold = false;
        return null;
      }
      return isBold ? <strong key={index}>{part}</strong> : part;
    });
  };

  if (isGroupedMode && searchData.groupedResults) {
    // Grouped mode (TOP_COMPONENT)
    const allResults = searchData.groupedResults;
    const results = showInactive ? allResults : allResults.filter((g) => g.active);
    const hiddenCount = allResults.length - results.length;

    if (results.length === 0) {
      return (
        <p className="no-results">
          No results found{hiddenCount > 0 ? ` (${hiddenCount} inactive concepts hidden)` : ''}
        </p>
      );
    }

    return (
      <div className="results-container">
        <p className="results-count">
          Found {results.length} concepts ({searchData.totalCount} total matches)
          {hiddenCount > 0 && <span className="hidden-count"> ({hiddenCount} inactive hidden)</span>}
        </p>
        <div className="grouped-results">
          {results.map((group) => {
            const conceptId = group.publicId[0];
            const isExpanded = expandedConcepts.has(conceptId);
            const hasMultipleMatches = group.matchingSemantics.length > 1;

            return (
              <div key={conceptId} className="concept-group">
                <div className="concept-header">
                  <div className="concept-info">
                    <span className={`concept-name ${!group.active ? 'inactive' : ''}`}>
                      {group.fullyQualifiedName}
                    </span>
                    <span className="concept-id-small">{conceptId}</span>
                    <span className="concept-score">Score: {group.topScore.toFixed(2)}</span>
                  </div>
                  <div className="concept-actions">
                    {hasMultipleMatches && (
                      <button
                        className="expand-button"
                        onClick={() => toggleExpanded(conceptId)}
                      >
                        {isExpanded ? '▼' : '▶'} {group.matchingSemantics.length} matches
                      </button>
                    )}
                    {onViewSemantics && (
                      <button
                        className="action-button semantics-button"
                        onClick={() => onViewSemantics(conceptId, group.fullyQualifiedName)}
                      >
                        Semantics
                      </button>
                    )}
                    {onViewDescendants && (
                      <button
                        className="action-button descendants-button"
                        onClick={() => onViewDescendants(conceptId, group.fullyQualifiedName)}
                      >
                        Descendants
                      </button>
                    )}
                  </div>
                </div>

                {/* Show first match always, others when expanded */}
                <div className="matching-semantics">
                  {group.matchingSemantics
                    .slice(0, isExpanded ? undefined : 1)
                    .map((semantic, idx) => (
                      <div key={idx} className="semantic-match">
                        <span className="match-text">
                          {renderHighlightedText(semantic.highlightedText)}
                        </span>
                        <span className="match-score">{semantic.score.toFixed(2)}</span>
                      </div>
                    ))}
                </div>
              </div>
            );
          })}
        </div>
      </div>
    );
  }

  // Flat mode (SEMANTIC)
  if (searchData.results) {
    const allResults = searchData.results;
    const results = showInactive ? allResults : allResults.filter((r) => r.active);
    const hiddenCount = allResults.length - results.length;

    if (results.length === 0) {
      return (
        <p className="no-results">
          No results found{hiddenCount > 0 ? ` (${hiddenCount} inactive results hidden)` : ''}
        </p>
      );
    }

    return (
      <div className="results-container">
        <p className="results-count">
          Found {results.length} results
          {hiddenCount > 0 && <span className="hidden-count"> ({hiddenCount} inactive hidden)</span>}
        </p>
        <table className="results-table">
          <thead>
            <tr>
              <th>Matched Text</th>
              <th>Concept Name</th>
              <th>Concept ID</th>
              <th>Score</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {results.map((result, index) => (
              <tr key={`${result.publicId[0]}-${index}`}>
                <td className="highlighted-text">
                  {renderHighlightedText(result.highlightedText)}
                </td>
                <td className={!result.active ? 'inactive' : ''}>
                  {result.fullyQualifiedName}
                </td>
                <td className="concept-id">{result.publicId[0]}</td>
                <td className="score-cell">{result.score.toFixed(2)}</td>
                <td className="actions-cell">
                  {onViewSemantics && (
                    <button
                      className="action-button semantics-button"
                      onClick={() => onViewSemantics(result.publicId[0], result.fullyQualifiedName)}
                    >
                      Semantics
                    </button>
                  )}
                  {onViewDescendants && (
                    <button
                      className="action-button descendants-button"
                      onClick={() => onViewDescendants(result.publicId[0], result.fullyQualifiedName)}
                    >
                      Descendants
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    );
  }

  return <p className="no-results">No results found</p>;
}
