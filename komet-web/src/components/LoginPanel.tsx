import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { listAuthors, login } from '../api/tinkarApi';

/**
 * Signs in as one of the knowledge base's authors, as Komet's author screen does, with the
 * password the service holds for that author (set with tinkar-service's scripts/set-password.sh).
 * Everything the app then writes is stamped with that author.
 */
export function LoginPanel() {
  const [authorId, setAuthorId] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const authors = useQuery({ queryKey: ['auth-authors'], queryFn: listAuthors });

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await login(authorId, password);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Sign-in failed');
      setPassword('');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="login-panel">
      <h2>Sign in</h2>
      <p className="reasoner-hint">
        Sign in as an author. What you change is stamped with that author.
      </p>
      <form onSubmit={submit} className="login-form">
        <label htmlFor="login-author">Author</label>
        <select
          id="login-author"
          value={authorId}
          onChange={(e) => setAuthorId(e.target.value)}
          disabled={authors.isLoading || busy}
          required
        >
          <option value="" disabled>
            {authors.isLoading ? 'Loading authors...' : 'Select an author'}
          </option>
          {authors.data?.map((a) => (
            <option key={a.authorId} value={a.authorId}>
              {a.name}
            </option>
          ))}
        </select>

        <label htmlFor="login-password">Password</label>
        <input
          id="login-password"
          type="password"
          autoComplete="current-password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          disabled={busy}
          required
        />

        {authors.isError && (
          <div className="error-message">
            Could not load the authors: {authors.error instanceof Error ? authors.error.message : 'request failed'}
          </div>
        )}
        {error && <div className="error-message">{error}</div>}

        <button type="submit" className="changeset-run" disabled={!authorId || !password || busy}>
          {busy ? 'Signing in...' : 'Sign in'}
        </button>
      </form>
    </div>
  );
}
