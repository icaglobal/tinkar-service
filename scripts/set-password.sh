#!/usr/bin/env bash
#
# Set, or remove, an author's password for signing in to tinkar-service.
#
#   scripts/set-password.sh <author-uuid>            prompts for the password, twice
#   scripts/set-password.sh --remove <author-uuid>   the author can no longer sign in
#
# The author is named by UUID: GET /api/auth/authors lists the authors and their UUIDs.
# The password is stored as a bcrypt hash, one "<author-uuid>=<hash>" line per author, in
# TINKAR_CREDENTIALS_FILE (default: data/credentials.properties, the service's default too).
# The service reads the file at every sign-in, so no restart is needed.
#
# Needs htpasswd: built in on macOS; apache2-utils on Debian and Ubuntu, httpd-tools on Fedora.
#
set -euo pipefail

FILE="${TINKAR_CREDENTIALS_FILE:-data/credentials.properties}"
COST=12

usage() {
    sed -n '3,6p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
}

remove=false
if [[ "${1:-}" == "--remove" ]]; then
    remove=true
    shift
fi
[[ $# -eq 1 ]] || usage
author="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')"
if [[ ! "$author" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]]; then
    echo "Not a UUID: $1 (GET /api/auth/authors lists the authors' UUIDs)" >&2
    exit 2
fi

mkdir -p "$(dirname "$FILE")"
touch "$FILE"
chmod 600 "$FILE"

# Every line but the author's: the line is rewritten, or left out to remove it.
others="$(grep -v "^${author}=" "$FILE" || true)"

if $remove; then
    printf '%s\n' "$others" | sed '/^$/d' > "$FILE"
    echo "Removed the password of $author from $FILE"
    exit 0
fi

command -v htpasswd > /dev/null || { echo "htpasswd is needed (apache2-utils / httpd-tools)" >&2; exit 1; }

read -r -s -p "Password for $author: " password; echo
read -r -s -p "Again: " again; echo
[[ "$password" == "$again" ]] || { echo "The passwords differ" >&2; exit 1; }
[[ ${#password} -ge 8 ]] || { echo "Use at least 8 characters" >&2; exit 1; }

# htpasswd -i reads the password from stdin, so it never appears in the process list.
hash="$(printf '%s' "$password" | htpasswd -niBC "$COST" "$author" | cut -d: -f2- | tr -d '\r\n')"

{ printf '%s\n' "$others" | sed '/^$/d'; printf '%s=%s\n' "$author" "$hash"; } > "$FILE"
echo "Set the password of $author in $FILE"
