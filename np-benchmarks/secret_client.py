"""Sends secret-bearing HTTPS requests to the server under test from a separate process.

The JVM under test holds the canary values only XOR-masked; this process unmasks them, sends them
over TLS (Authorization header + a JSON password field) and exits, so any plaintext later found in
the server's memory must have come in over the network.

Usage: secret_client.py <url> <ca-cert.pem> <masked-token-hex> <masked-password-hex> <mask> <count>
"""
import json, ssl, sys, urllib.request

url, ca, token_hex, pw_hex, mask, count = sys.argv[1:7]
mask, count = int(mask), int(count)
token = bytes(b ^ mask for b in bytes.fromhex(token_hex)).decode()
password = bytes(b ^ mask for b in bytes.fromhex(pw_hex)).decode()
ctx = ssl.create_default_context(cafile=ca)

for _ in range(count):
    body = json.dumps({"user": "alice", "password": password}).encode()
    req = urllib.request.Request(url, data=body, method="POST", headers={
        "Authorization": "Bearer " + token, "Content-Type": "application/json", "Connection": "close"})
    with urllib.request.urlopen(req, context=ctx) as r:
        print(r.status, r.read().decode())
