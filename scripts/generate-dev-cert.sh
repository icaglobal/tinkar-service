#!/usr/bin/env bash
#
# Generate a self-signed TLS certificate for local gRPC development.
#
# Idempotent: if a certificate already exists and is not close to expiry, this
# script reports and exits 0 without touching it. Pass --force to regenerate.
#
# The certificate is its own trust anchor (self-signed), so the SAME cert file
# is both the server's certificate chain and the client's trust material:
#
#   server (tinkar-service)   GRPC_TLS_CERT_CHAIN=certs/server-cert.pem
#                             GRPC_TLS_PRIVATE_KEY=certs/server-key.pem
#   client (Komet)            -Dkomet.grpc.tls.ca=<abs path>/certs/server-cert.pem
#
# NOT FOR PRODUCTION. Use a CA-issued certificate there; the client trusts the
# JDK default store when no CA override is given, so switching is config-only.
#
set -euo pipefail

CERT_DIR="${CERT_DIR:-certs}"
CERT_FILE="${CERT_DIR}/server-cert.pem"
KEY_FILE="${CERT_DIR}/server-key.pem"
DAYS="${CERT_DAYS:-365}"
# Renew when fewer than this many seconds of validity remain (default 30 days).
RENEW_WITHIN="${CERT_RENEW_WITHIN:-2592000}"
# Hostnames/IPs the certificate is valid for. Java ignores CN entirely and
# matches only SANs, so every name a client may dial must appear here.
SAN="${CERT_SAN:-DNS:localhost,IP:127.0.0.1,IP:::1}"
CN="${CERT_CN:-localhost}"

FORCE=0
[ "${1:-}" = "--force" ] && FORCE=1

command -v openssl >/dev/null 2>&1 || {
    echo "ERROR: openssl not found on PATH." >&2
    exit 1
}

if [ "$FORCE" -eq 0 ] && [ -f "$CERT_FILE" ] && [ -f "$KEY_FILE" ]; then
    # -checkend exits non-zero when the cert expires within the given window.
    if openssl x509 -in "$CERT_FILE" -noout -checkend "$RENEW_WITHIN" >/dev/null 2>&1; then
        expiry=$(openssl x509 -in "$CERT_FILE" -noout -enddate 2>/dev/null | cut -d= -f2)
        echo "Certificate already present and valid — leaving it alone."
        echo "  cert:    $CERT_FILE"
        echo "  key:     $KEY_FILE"
        echo "  expires: $expiry"
        echo "  (re-run with --force to regenerate)"
        exit 0
    fi
    echo "Certificate exists but expires within the renewal window — regenerating."
fi

mkdir -p "$CERT_DIR"

# -nodes leaves the key unencrypted, which is what a server reading it at
# startup needs; modern OpenSSL emits PKCS#8, the format net.devh expects.
# -addext carries the SAN: a CN-only certificate is rejected outright by Java's
# hostname verification, producing an opaque handshake failure.
openssl req -x509 \
    -newkey rsa:2048 \
    -sha256 \
    -days "$DAYS" \
    -nodes \
    -keyout "$KEY_FILE" \
    -out "$CERT_FILE" \
    -subj "/CN=${CN}" \
    -addext "subjectAltName=${SAN}" \
    >/dev/null 2>&1

chmod 600 "$KEY_FILE"
chmod 644 "$CERT_FILE"

echo "Generated self-signed certificate."
echo "  cert:    $CERT_FILE"
echo "  key:     $KEY_FILE  (mode 600)"
echo "  subject: CN=${CN}"
echo "  SAN:     ${SAN}"
echo "  expires: $(openssl x509 -in "$CERT_FILE" -noout -enddate | cut -d= -f2)"
echo
echo "Server:  export GRPC_TLS_ENABLED=true"
echo "         export GRPC_TLS_CERT_CHAIN=$(pwd)/${CERT_FILE}"
echo "         export GRPC_TLS_PRIVATE_KEY=$(pwd)/${KEY_FILE}"
echo "Client:  -Dkomet.grpc.tls=true -Dkomet.grpc.tls.ca=$(pwd)/${CERT_FILE}"
