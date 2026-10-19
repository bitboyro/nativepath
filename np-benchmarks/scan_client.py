"""Sends planted secrets to the ScanServerApp from a separate process, in ways that include the
failure paths. The JVM under test holds the canaries only XOR-masked; this process unmasks them, so
any plaintext later found in the server's memory came in over the network.

Usage: scan_client.py <base-url> <ca-cert.pem> <mode> <masked-token-hex> <masked-password-hex> <mask> <count>

Modes:
  secure   POST /secure, secret Authorization header and JSON password
  control  POST /control, the usual Spring way, token in X-Control-Token
  error    POST /error: the handler fails with the secrets in flight (a 500 is the expected answer)
  abort    headers and the start of the body, then the connection is dropped before the body is complete
  timeout  headers and the start of the body, then silence until the server gives up on the body
"""
import json, socket, ssl, sys, time, urllib.error, urllib.request
from urllib.parse import urlparse

base, ca, mode, token_hex, pw_hex, mask, count = sys.argv[1:8]
mask, count = int(mask), int(count)
token = bytes(b ^ mask for b in bytes.fromhex(token_hex)).decode()
password = bytes(b ^ mask for b in bytes.fromhex(pw_hex)).decode()
ctx = ssl.create_default_context(cafile=ca)
u = urlparse(base)
host, port = u.hostname, u.port or 443


def post(path, headers):
    body = json.dumps({"user": "alice", "password": password}).encode()
    req = urllib.request.Request(base + path, data=body, method="POST",
                                 headers={**headers, "Content-Type": "application/json", "Connection": "close"})
    try:
        with urllib.request.urlopen(req, context=ctx, timeout=30) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def partial(path, linger):
    """Headers and the first part of a body that is announced much longer, then drop (or wait)."""
    sock = socket.create_connection((host, port), timeout=30)
    tls = ctx.wrap_socket(sock, server_hostname=host)
    start = json.dumps({"user": "alice", "password": password}).encode()
    head = (f"POST {path} HTTP/1.1\r\nHost: {host}:{port}\r\nAuthorization: Bearer {token}\r\n"
            f"Content-Type: application/json\r\nContent-Length: {len(start) + 50000}\r\n\r\n").encode()
    tls.sendall(head + start)
    if linger:
        tls.settimeout(linger)
        try:
            while tls.recv(4096):
                pass
        except (socket.timeout, ssl.SSLError, OSError):
            pass
    try:
        tls.close()
    except OSError:
        pass
    return 0, "dropped"


for _ in range(count):
    if mode == "secure":
        status, out = post("/secure", {"Authorization": "Bearer " + token})
    elif mode == "control":
        status, out = post("/control", {"X-Control-Token": token})
    elif mode == "error":
        status, out = post("/error", {"Authorization": "Bearer " + token})
    elif mode == "abort":
        status, out = partial("/secure", 0)
    elif mode == "timeout":
        status, out = partial("/secure", 6)
    else:
        sys.exit("unknown mode " + mode)
    print(status, out, flush=True)
