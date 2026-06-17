import { useMemo, useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { SearchBox } from './components/SearchBox';
import { ResultsTable } from './components/ResultsTable';
import { SortedResultsTable } from './components/SortedResultsTable';
import { SemanticsView } from './components/SemanticsView';
import { TestRunner } from './components/TestRunner/TestRunner';
import { CoordinatesPanel } from './components/CoordinatesPanel';
import { conceptSearchWithSort, getDescendants, getSemantics, removeDescendant, createAndAddDescendant } from './api/tinkarApi';
import type { SearchSortOption } from './api/types';
import './App.css';

type ViewMode = 'search' | 'descendants' | 'semantics' | 'test-runner' | 'coordinates';

const SORT_OPTIONS: { value: SearchSortOption; label: string }[] = [
  { value: 'TOP_COMPONENT', label: 'Top Component (by score)' },
  { value: 'TOP_COMPONENT_ALPHA', label: 'Top Component (alphabetical)' },
  { value: 'SEMANTIC', label: 'Matched Semantic (by score)' },
  { value: 'SEMANTIC_ALPHA', label: 'Matched Semantic (alphabetical)' },
];

function App() {
  const [searchQuery, setSearchQuery] = useState<string>('');
  const [sortBy, setSortBy] = useState<SearchSortOption>('TOP_COMPONENT');
  const [showInactive, setShowInactive] = useState<boolean>(false);
  const [viewMode, setViewMode] = useState<ViewMode>('search');
  const [selectedConceptId, setSelectedConceptId] = useState<string | null>(null);
  const [selectedConceptName, setSelectedConceptName] = useState<string>('');
  const [newDescendantName, setNewDescendantName] = useState<string>('');

  const queryClient = useQueryClient();

  const {
    data: searchData,
    isLoading: isSearchLoading,
    isError: isSearchError,
    error: searchError,
  } = useQuery({
    queryKey: ['conceptSearchWithSort', searchQuery, sortBy],
    queryFn: () => conceptSearchWithSort(searchQuery, 200, sortBy),
    enabled: !!searchQuery,
  });

  const {
    data: descendantsData,
    isLoading: isDescendantsLoading,
    isError: isDescendantsError,
    error: descendantsError,
  } = useQuery({
    queryKey: ['descendants', selectedConceptId],
    queryFn: () => getDescendants(selectedConceptId!),
    enabled: viewMode === 'descendants' && !!selectedConceptId,
  });

  const {
    data: semanticsData,
    isLoading: isSemanticsLoading,
    isError: isSemanticsError,
    error: semanticsError,
  } = useQuery({
    queryKey: ['semantics', selectedConceptId],
    queryFn: () => getSemantics(selectedConceptId!),
    enabled: viewMode === 'semantics' && !!selectedConceptId,
  });

  const deleteMutation = useMutation({
    mutationFn: ({ parentId, descendantId }: { parentId: string; descendantId: string }) =>
      removeDescendant(parentId, descendantId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['descendants', selectedConceptId] });
    },
  });

  const addMutation = useMutation({
    mutationFn: ({ parentId, conceptName }: { parentId: string; conceptName: string }) =>
      createAndAddDescendant(parentId, conceptName),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['descendants', selectedConceptId] });
      setNewDescendantName('');
    },
  });

  const handleSearch = (query: string) => {
    setSearchQuery(query);
    setViewMode('search');
    setSelectedConceptId(null);
    setSelectedConceptName('');
  };

  const handleViewSemantics = (conceptId: string, conceptName?: string) => {
    setSelectedConceptName(conceptName || conceptId);
    setSelectedConceptId(conceptId);
    setViewMode('semantics');
  };

  const handleViewDescendants = (conceptId: string, conceptName?: string) => {
    setSelectedConceptName(conceptName || conceptId);
    setSelectedConceptId(conceptId);
    setViewMode('descendants');
  };

  const handleBack = () => {
    setViewMode('search');
    setSelectedConceptId(null);
    setSelectedConceptName('');
  };

  const handleDelete = (descendantId: string) => {
    if (selectedConceptId && confirm('Are you sure you want to remove this descendant?')) {
      deleteMutation.mutate({ parentId: selectedConceptId, descendantId });
    }
  };

  const handleAddDescendant = (e: React.FormEvent) => {
    e.preventDefault();
    const trimmedName = newDescendantName.trim();
    if (selectedConceptId && trimmedName) {
      addMutation.mutate({ parentId: selectedConceptId, conceptName: trimmedName });
    }
  };

  const sortedDescendants = useMemo(() => {
    if (!descendantsData?.results) return [];
    return [...descendantsData.results].sort((a, b) =>
      a.descriptions.fullyQualifiedName.localeCompare(b.descriptions.fullyQualifiedName)
    );
  }, [descendantsData?.results]);

  const isLoading = isSearchLoading || isDescendantsLoading || isSemanticsLoading;
  const isError = viewMode === 'descendants' ? isDescendantsError : viewMode === 'semantics' ? isSemanticsError : isSearchError;
  const error = viewMode === 'descendants' ? descendantsError : viewMode === 'semantics' ? semanticsError : searchError;

  return (
    <div className="app">
      <header className="app-header">
        <div className="header-row">
          <h1>Tinkar Search</h1>
          <div className="header-buttons">
            <button
              className={`coordinates-button ${viewMode === 'coordinates' ? 'active' : ''}`}
              onClick={() => setViewMode('coordinates')}
            >
              Coordinates
            </button>
            <button
              className={`test-runner-button ${viewMode === 'test-runner' ? 'active' : ''}`}
              onClick={() => setViewMode('test-runner')}
            >
              Run DeX Tests
            </button>
          </div>
        </div>
      </header>

      <main className="app-main">
        {viewMode !== 'test-runner' && viewMode !== 'coordinates' && (
          <SearchBox onSearch={handleSearch} isLoading={isLoading} />
        )}

        {viewMode === 'search' && (
          <div className="sort-controls">
            <label htmlFor="sort-select">Sort by:</label>
            <select
              id="sort-select"
              value={sortBy}
              onChange={(e) => setSortBy(e.target.value as SearchSortOption)}
              className="sort-select"
            >
              {SORT_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
            <button
              type="button"
              className={`inactive-toggle ${showInactive ? 'active' : ''}`}
              onClick={() => setShowInactive(!showInactive)}
            >
              {showInactive ? 'Hide Inactive' : 'Show Inactive'}
            </button>
          </div>
        )}

        {isError && (
          <div className="error-message">
            Error: {error instanceof Error ? error.message : 'An error occurred'}
          </div>
        )}

        {viewMode === 'descendants' && selectedConceptId && (
          <>
            <button className="back-button" onClick={handleBack}>
              &larr; Back to search results
            </button>
            {descendantsData && !descendantsData.success && (
              <div className="error-message">
                Error: {descendantsData.errorMessage || 'Failed to load descendants'}
              </div>
            )}
            {deleteMutation.isError && (
              <div className="error-message">
                Error: {deleteMutation.error instanceof Error ? deleteMutation.error.message : 'Failed to delete descendant'}
              </div>
            )}
            {addMutation.isError && (
              <div className="error-message">
                Error: {addMutation.error instanceof Error ? addMutation.error.message : 'Failed to add descendant'}
              </div>
            )}
            {addMutation.isSuccess && (
              <div className="success-message">
                New concept created and added as descendant successfully!
              </div>
            )}

            <form className="add-descendant-form" onSubmit={handleAddDescendant}>
              <label htmlFor="descendant-name">Create New Descendant Concept:</label>
              <div className="add-descendant-input-group">
                <input
                  id="descendant-name"
                  type="text"
                  placeholder="Enter concept name (e.g., 'Acute bronchitis')"
                  value={newDescendantName}
                  onChange={(e) => setNewDescendantName(e.target.value)}
                  disabled={addMutation.isPending}
                  className="descendant-input"
                />
                <button
                  type="submit"
                  disabled={!newDescendantName.trim() || addMutation.isPending}
                  className="add-button"
                >
                  {addMutation.isPending ? 'Creating...' : 'Create'}
                </button>
              </div>
            </form>

            {descendantsData && descendantsData.success && (
              <ResultsTable
                results={sortedDescendants}
                totalCount={descendantsData.totalCount}
                title={`Descendants of: ${selectedConceptName}`}
                onDelete={handleDelete}
                isDeleting={deleteMutation.isPending}
              />
            )}
            {isDescendantsLoading && <p className="loading">Loading descendants...</p>}
          </>
        )}

        {viewMode === 'semantics' && selectedConceptId && (
          <>
            {semanticsData && !semanticsData.success && (
              <div className="error-message">
                Error: {semanticsData.errorMessage || 'Failed to load semantics'}
              </div>
            )}
            {semanticsData && semanticsData.success && (
              <SemanticsView
                data={semanticsData}
                conceptName={selectedConceptName}
                conceptId={selectedConceptId!}
                onBack={handleBack}
              />
            )}
            {isSemanticsLoading && <p className="loading">Loading semantics...</p>}
          </>
        )}

        {viewMode === 'search' && (
          <>
            {searchData && !searchData.success && (
              <div className="error-message">
                Error: {searchData.errorMessage || 'Search failed'}
              </div>
            )}

            {searchData && searchData.success && (
              <SortedResultsTable
                searchData={searchData}
                onViewSemantics={handleViewSemantics}
                onViewDescendants={handleViewDescendants}
                showInactive={showInactive}
              />
            )}

            {!searchQuery && !isLoading && (
              <p className="instructions">Enter a search term to find Tinkar concepts</p>
            )}
          </>
        )}

        {viewMode === 'test-runner' && (
          <TestRunner onBack={handleBack} />
        )}

        {viewMode === 'coordinates' && (
          <CoordinatesPanel onBack={handleBack} />
        )}
      </main>
    </div>
  );
}

export default App;
