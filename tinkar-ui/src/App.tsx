import { useMemo, useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { SearchBox } from './components/SearchBox';
import { ResultsTable } from './components/ResultsTable';
import { conceptSearch, getDescendants, removeDescendant, createAndAddDescendant } from './api/tinkarApi';
import './App.css';

function App() {
  const [searchQuery, setSearchQuery] = useState<string>('');
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
    queryKey: ['conceptSearch', searchQuery],
    queryFn: () => conceptSearch(searchQuery, 200),
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
    enabled: !!selectedConceptId,
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
    setSelectedConceptId(null);
    setSelectedConceptName('');
  };

  const handleRowClick = (conceptId: string) => {
    const concept = searchData?.results.find(r => r.publicId[0] === conceptId);
    setSelectedConceptName(concept?.descriptions.fullyQualifiedName || conceptId);
    setSelectedConceptId(conceptId);
  };

  const handleBack = () => {
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

  const isLoading = isSearchLoading || isDescendantsLoading;
  const isError = selectedConceptId ? isDescendantsError : isSearchError;
  const error = selectedConceptId ? descendantsError : searchError;

  return (
    <div className="app">
      <header className="app-header">
        <h1>Tinkar Concept Search</h1>
      </header>

      <main className="app-main">
        <SearchBox onSearch={handleSearch} isLoading={isLoading} />

        {isError && (
          <div className="error-message">
            Error: {error instanceof Error ? error.message : 'An error occurred'}
          </div>
        )}

        {selectedConceptId ? (
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
        ) : (
          <>
            {searchData && !searchData.success && (
              <div className="error-message">
                Error: {searchData.errorMessage || 'Search failed'}
              </div>
            )}

            {searchData && searchData.success && (
              <ResultsTable
                results={searchData.results}
                totalCount={searchData.totalCount}
                onRowClick={handleRowClick}
              />
            )}

            {!searchQuery && !isLoading && (
              <p className="instructions">Enter a search term to find Tinkar concepts</p>
            )}
          </>
        )}
      </main>
    </div>
  );
}

export default App;
