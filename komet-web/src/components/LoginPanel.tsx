import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { listAuthors, login } from '../api/tinkarApi';
import kometLogo from '../assets/komet-logo.png';
import { PoweredByIke } from './PoweredByIke';

/**
 * Signs in as one of the knowledge base's authors, as Komet's author screen does, with the
 * password the service holds for that author (set with tinkar-service's scripts/set-password.sh).
 * Everything the app then writes is stamped with that author. Laid out like Komet desktop's
 * sign-in screen.
 */
export function LoginPanel() {
  const [authorId, setAuthorId] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
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
    <div className="login-screen">
      <div className="login-panel">
        <img className="login-logo" src={kometLogo} alt="Komet" />
        <h1 className="login-title">Welcome to Komet</h1>
        <p className="login-subtitle">
          A collaborative authoring environment for Integrated Knowledge Management
        </p>

        <form onSubmit={submit} className="login-form">
          <select
            id="login-author"
            aria-label="Author"
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

          <div className="login-password">
            <input
              id="login-password"
              aria-label="Password"
              placeholder="Password"
              type={showPassword ? 'text' : 'password'}
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              disabled={busy}
              required
            />
            <button
              type="button"
              className="login-show"
              onClick={() => setShowPassword(!showPassword)}
              aria-pressed={showPassword}
            >
              {showPassword ? 'Hide' : 'Show'}
            </button>
          </div>

          {authors.isError && (
            <div className="error-message">
              Could not load the authors: {authors.error instanceof Error ? authors.error.message : 'request failed'}
            </div>
          )}
          {error && <div className="error-message">{error}</div>}

          <button type="submit" className="login-submit" disabled={!authorId || !password || busy}>
            {busy ? 'SIGNING IN...' : 'SIGN IN'}
          </button>
        </form>

        <PoweredByIke className="login-powered-by" />
      </div>
    </div>
  );
}
