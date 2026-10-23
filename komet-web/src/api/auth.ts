/**
 * The signed-in session: the token from POST /api/auth/login, and who it names.
 *
 * Kept in sessionStorage, so a page reload stays signed in but a new tab or a closed browser
 * signs in again. Every API call sends the token; a call the service answers with 401 ends the
 * session, and the app shows the sign-in screen again.
 */

export type SignedInAuthor = { authorId: string; name: string };

export type Session = {
  token: string;
  expiresAt: number;
  author: SignedInAuthor;
};

const KEY = 'tinkar-session';

type Listener = (session: Session | null) => void;
const listeners = new Set<Listener>();

let current: Session | null = load();

function load(): Session | null {
  try {
    const raw = sessionStorage.getItem(KEY);
    if (!raw) return null;
    const session = JSON.parse(raw) as Session;
    return session.expiresAt > Date.now() ? session : null;
  } catch {
    return null;
  }
}

export function getSession(): Session | null {
  if (current && current.expiresAt <= Date.now()) {
    clearSession();
  }
  return current;
}

export function setSession(session: Session): void {
  current = session;
  try {
    sessionStorage.setItem(KEY, JSON.stringify(session));
  } catch {
    // Storage unavailable (a private window, say): the session lasts until the page reloads.
  }
  listeners.forEach((l) => l(current));
}

export function clearSession(): void {
  if (current === null) return;
  current = null;
  try {
    sessionStorage.removeItem(KEY);
  } catch {
    // Nothing stored to remove.
  }
  listeners.forEach((l) => l(null));
}

/** Calls `listener` whenever the session starts or ends; returns the call that stops it. */
export function onSessionChange(listener: Listener): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}
