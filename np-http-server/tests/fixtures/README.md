Self-signed certificate and key for tests only (CN=localhost, SAN 127.0.0.1 and localhost,
valid ten years). Not secret; never use outside tests. Regenerate with:

    openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 3650 \
      -subj "/CN=localhost" -addext "subjectAltName=IP:127.0.0.1,DNS:localhost" \
      -addext "basicConstraints=critical,CA:FALSE" -addext "extendedKeyUsage=serverAuth" \
      -keyout test-key.pem -out test-cert.pem
