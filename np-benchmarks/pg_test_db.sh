#!/bin/sh
# Starts a throwaway PostgreSQL 17 (SCRAM-SHA-256 authentication, TLS on, plaintext still allowed)
# for the np-db tests and writes the files they read: password.txt and card.txt (the canary values,
# which exist only here, in those files and in the container; the Java scan tests hold them
# XOR-masked) and ca.pem (a throwaway CA that signed the server certificate for localhost and
# 127.0.0.1).
#
# Usage: np-benchmarks/pg_test_db.sh [dir]        (default dir: target/pg, port: $NP_PG_PORT or 55432)
#        np-benchmarks/pg_test_db.sh --prepare dir  only write the files (the Docker setup starts PostgreSQL itself)
#        np-benchmarks/pg_test_db.sh stop
set -eu
NAME=np-pg
if [ "${1:-}" = "stop" ]; then
    docker rm -f "$NAME" >/dev/null 2>&1 || true
    exit 0
fi
PREPARE=0
if [ "${1:-}" = "--prepare" ]; then PREPARE=1; shift; fi
DIR="${1:-target/pg}"
PORT="${NP_PG_PORT:-55432}"
mkdir -p "$DIR"
DIR="$(cd "$DIR" && pwd)"
printf '%s' 'NP-PG-CANARY-PASSWORD-3e9a71c45d' > "$DIR/password.txt"
printf '%s' 'NP-PG-CANARY-CARD-5f2b8d0c7e14' > "$DIR/card.txt"
chmod 644 "$DIR/password.txt" "$DIR/card.txt"

# Throwaway CA and server certificate (ECDSA P-256, valid 30 days).
CERTS="$DIR/certs"
rm -rf "$CERTS"
mkdir -p "$CERTS"
printf '[req]\ndistinguished_name=dn\nx509_extensions=v3_ca\nprompt=no\n[dn]\nCN=np test CA\n[v3_ca]\nbasicConstraints=critical,CA:TRUE\nkeyUsage=critical,keyCertSign,cRLSign\nsubjectKeyIdentifier=hash\n' > "$CERTS/ca.cnf"
printf 'subjectAltName=DNS:localhost,DNS:postgres,IP:127.0.0.1\nbasicConstraints=CA:FALSE\nkeyUsage=critical,digitalSignature\nextendedKeyUsage=serverAuth\n' > "$CERTS/server.ext"
openssl ecparam -name prime256v1 -genkey -noout -out "$CERTS/ca.key"
openssl req -x509 -new -key "$CERTS/ca.key" -sha256 -days 30 -config "$CERTS/ca.cnf" -out "$DIR/ca.pem"
openssl ecparam -name prime256v1 -genkey -noout -out "$CERTS/server.key"
openssl req -new -key "$CERTS/server.key" -subj "/CN=localhost" -out "$CERTS/server.csr"
openssl x509 -req -in "$CERTS/server.csr" -CA "$DIR/ca.pem" -CAkey "$CERTS/ca.key" -CAcreateserial \
    -days 30 -sha256 -extfile "$CERTS/server.ext" -out "$CERTS/server.crt" 2>/dev/null
chmod 644 "$CERTS/server.key" "$CERTS/server.crt" "$DIR/ca.pem"

if [ "$PREPARE" = 1 ]; then echo PREPARED; exit 0; fi

docker rm -f "$NAME" >/dev/null 2>&1 || true
# PostgreSQL refuses a key file it does not own with mode 0600, so copy the certificate files in
# and fix ownership before handing over to the image's entrypoint.
docker run -d --name "$NAME" -p "127.0.0.1:$PORT:5432" \
    -e POSTGRES_USER=np -e POSTGRES_DB=np -e POSTGRES_PASSWORD_FILE=/run/np-password \
    -v "$DIR/password.txt:/run/np-password:ro" \
    -v "$CERTS:/run/np-certs:ro" \
    --entrypoint sh postgres:17-alpine -c '
        cp /run/np-certs/server.crt /run/np-certs/server.key /var/lib/postgresql/ &&
        chown postgres /var/lib/postgresql/server.crt /var/lib/postgresql/server.key &&
        chmod 600 /var/lib/postgresql/server.key &&
        exec docker-entrypoint.sh postgres -c ssl=on \
            -c ssl_cert_file=/var/lib/postgresql/server.crt -c ssl_key_file=/var/lib/postgresql/server.key' >/dev/null

i=0
until docker exec "$NAME" pg_isready -U np -d np -h 127.0.0.1 >/dev/null 2>&1; do
    i=$((i + 1))
    if [ "$i" -gt 60 ]; then
        echo "postgres did not start" >&2
        docker logs "$NAME" >&2
        exit 1
    fi
    sleep 0.5
done
echo READY
