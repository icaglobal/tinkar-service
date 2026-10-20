import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  listStampCoordinates,
  listNavigationCoordinates,
  listLanguageCoordinates,
  saveStampCoordinate,
  saveNavigationCoordinate,
} from '../api/tinkarApi';

interface CoordinatesPanelProps {
  onBack: () => void;
}

export function CoordinatesPanel({ onBack }: CoordinatesPanelProps) {
  const queryClient = useQueryClient();
  const [saveMessage, setSaveMessage] = useState<{ text: string; isError: boolean } | null>(null);

  const { data: stampCoords, isLoading: isStampLoading, isError: isStampError } = useQuery({
    queryKey: ['stampCoordinates'],
    queryFn: listStampCoordinates,
  });

  const { data: navCoords, isLoading: isNavLoading, isError: isNavError } = useQuery({
    queryKey: ['navigationCoordinates'],
    queryFn: listNavigationCoordinates,
  });

  const { data: langCoords, isLoading: isLangLoading, isError: isLangError } = useQuery({
    queryKey: ['languageCoordinates'],
    queryFn: listLanguageCoordinates,
  });

  const saveDefaultsMutation = useMutation({
    mutationFn: async () => {
      await Promise.all([
        saveStampCoordinate({ allowedStates: 'ACTIVE' }),
        saveStampCoordinate({ allowedStates: 'INACTIVE' }),
        saveNavigationCoordinate({ premiseType: 'INFERRED' }),
        saveNavigationCoordinate({ premiseType: 'STATED' }),
      ]);
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['stampCoordinates'] });
      queryClient.invalidateQueries({ queryKey: ['navigationCoordinates'] });
      setSaveMessage({ text: 'Default coordinates saved successfully!', isError: false });
      setTimeout(() => setSaveMessage(null), 4000);
    },
    onError: (error) => {
      setSaveMessage({
        text: `Error: ${error instanceof Error ? error.message : 'Failed to save coordinates'}`,
        isError: true,
      });
    },
  });

  return (
    <div className="coordinates-panel">
      <div className="coordinates-header">
        <button className="back-button" onClick={onBack}>&larr; Back</button>
        <div className="coordinates-title-row">
          <h2>Saved Coordinates</h2>
          <button
            className="save-defaults-button"
            onClick={() => saveDefaultsMutation.mutate()}
            disabled={saveDefaultsMutation.isPending}
          >
            {saveDefaultsMutation.isPending ? 'Saving...' : 'Save Default Coordinates'}
          </button>
        </div>
        <p className="coordinates-subtitle">
          Saved coordinate configurations used for knowledge graph queries.
          Default coordinates include STAMP (ACTIVE, INACTIVE) and Navigation (INFERRED, STATED).
        </p>
        {saveMessage && (
          <div className={saveMessage.isError ? 'error-message' : 'success-message'}>
            {saveMessage.text}
          </div>
        )}
      </div>

      <div className="coord-section">
        <h3 className="coord-section-title">Stamp Coordinates</h3>
        {isStampLoading && <p className="loading">Loading...</p>}
        {isStampError && <div className="error-message">Failed to load stamp coordinates</div>}
        {stampCoords && stampCoords.length === 0 && (
          <p className="no-results">No saved stamp coordinates.</p>
        )}
        {stampCoords && stampCoords.length > 0 && (
          <div className="coord-list">
            {stampCoords.map((coord) => (
              <div key={coord.id} className="coord-card">
                <div className="coord-card-header">
                  <code className="coord-id">{coord.id}</code>
                  <span className="coord-created-at">{new Date(coord.createdAt).toLocaleString()}</span>
                </div>
                <dl className="coord-settings">
                  {coord.settings.allowedStates && (
                    <>
                      <dt>allowedStates</dt>
                      <dd>{coord.settings.allowedStates}</dd>
                    </>
                  )}
                  {coord.settings.positionTime != null && (
                    <>
                      <dt>positionTime</dt>
                      <dd>{coord.settings.positionTime}</dd>
                    </>
                  )}
                  {coord.settings.positionPathId && (
                    <>
                      <dt>positionPathId</dt>
                      <dd><code>{coord.settings.positionPathId}</code></dd>
                    </>
                  )}
                  {coord.settings.moduleIds && coord.settings.moduleIds.length > 0 && (
                    <>
                      <dt>moduleIds</dt>
                      <dd>{coord.settings.moduleIds.join(', ')}</dd>
                    </>
                  )}
                  {coord.settings.excludedModuleIds && coord.settings.excludedModuleIds.length > 0 && (
                    <>
                      <dt>excludedModuleIds</dt>
                      <dd>{coord.settings.excludedModuleIds.join(', ')}</dd>
                    </>
                  )}
                  {coord.settings.modulePriorityIds && coord.settings.modulePriorityIds.length > 0 && (
                    <>
                      <dt>modulePriorityIds</dt>
                      <dd>{coord.settings.modulePriorityIds.join(', ')}</dd>
                    </>
                  )}
                </dl>
              </div>
            ))}
          </div>
        )}
      </div>

      <div className="coord-section">
        <h3 className="coord-section-title">Navigation Coordinates</h3>
        {isNavLoading && <p className="loading">Loading...</p>}
        {isNavError && <div className="error-message">Failed to load navigation coordinates</div>}
        {navCoords && navCoords.length === 0 && (
          <p className="no-results">No saved navigation coordinates.</p>
        )}
        {navCoords && navCoords.length > 0 && (
          <div className="coord-list">
            {navCoords.map((coord) => (
              <div key={coord.id} className="coord-card">
                <div className="coord-card-header">
                  <code className="coord-id">{coord.id}</code>
                  <span className="coord-created-at">{new Date(coord.createdAt).toLocaleString()}</span>
                </div>
                <dl className="coord-settings">
                  {coord.settings.premiseType && (
                    <>
                      <dt>premiseType</dt>
                      <dd>{coord.settings.premiseType}</dd>
                    </>
                  )}
                </dl>
              </div>
            ))}
          </div>
        )}
      </div>

      <div className="coord-section">
        <h3 className="coord-section-title">Language Coordinates</h3>
        {isLangLoading && <p className="loading">Loading...</p>}
        {isLangError && <div className="error-message">Failed to load language coordinates</div>}
        {langCoords && langCoords.length === 0 && (
          <p className="no-results">No saved language coordinates.</p>
        )}
        {langCoords && langCoords.length > 0 && (
          <div className="coord-list">
            {langCoords.map((coord) => (
              <div key={coord.id} className="coord-card">
                <div className="coord-card-header">
                  <code className="coord-id">{coord.id}</code>
                  <span className="coord-created-at">{new Date(coord.createdAt).toLocaleString()}</span>
                </div>
                <dl className="coord-settings">
                  {coord.settings.languagePreset && (
                    <>
                      <dt>languagePreset</dt>
                      <dd>{coord.settings.languagePreset}</dd>
                    </>
                  )}
                </dl>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
