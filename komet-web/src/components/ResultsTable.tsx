import type { SearchResult } from '../api/types';

interface ResultsTableProps {
  results: SearchResult[];
  totalCount: number;
  title?: string;
  onRowClick?: (conceptId: string) => void;
  onDelete?: (conceptId: string) => void;
  isDeleting?: boolean;
}

export function ResultsTable({ results, totalCount, title, onRowClick, onDelete, isDeleting }: ResultsTableProps) {
  if (results.length === 0) {
    return <p className="no-results">No results found</p>;
  }

  const handleDeleteClick = (e: React.MouseEvent, conceptId: string) => {
    e.stopPropagation();
    onDelete?.(conceptId);
  };

  return (
    <div className="results-container">
      {title && <h2 className="results-title">{title}</h2>}
      <p className="results-count">Found {totalCount} results</p>
      <table className="results-table">
        <thead>
          <tr>
            <th>Fully Qualified Name</th>
            <th>Concept ID</th>
            {onDelete && <th>Actions</th>}
          </tr>
        </thead>
        <tbody>
          {results.map((result) => (
            <tr
              key={result.publicId[0]}
              onClick={() => onRowClick?.(result.publicId[0])}
              className={onRowClick ? 'clickable' : ''}
            >
              <td>{result.descriptions.fullyQualifiedName}</td>
              <td className="concept-id">{result.publicId[0]}</td>
              {onDelete && (
                <td>
                  <button
                    className="delete-button"
                    onClick={(e) => handleDeleteClick(e, result.publicId[0])}
                    disabled={isDeleting}
                  >
                    {isDeleting ? 'Deleting...' : 'Delete'}
                  </button>
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
