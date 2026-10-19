"""Test server holding the canary values; the client under test only knows them XOR-masked."""
import http.server, json, socketserver, subprocess, sys

RESPONSE_CANARY = "NATIVEPATH-CANARY-RESPONSE-7f3a91c2d4e85b60"
REQUEST_CANARY = "NATIVEPATH-CANARY-REQUEST-b1c0de42a9f37e58"

class H(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, *a): pass
    def do_GET(self):
        ok = self.headers.get("Authorization") == "Bearer " + REQUEST_CANARY
        if self.path == "/v1/secret/data/app":
            # HashiCorp Vault KV v2 read; the token is the request canary
            if self.headers.get("X-Vault-Token") != REQUEST_CANARY:
                self.send_response(403)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            body = json.dumps({"request_id": "scan", "lease_id": "", "renewable": False,
                               "data": {"data": {"user": "app", "password": RESPONSE_CANARY},
                                        "metadata": {"version": 1}}}).encode()
        elif self.path == "/token":
            body = json.dumps({"access_token": RESPONSE_CANARY, "token_type": "Bearer",
                               "expires_in": 3600, "auth_ok": ok}).encode()
        elif self.path == "/big":
            body = b"A" * 150000 + RESPONSE_CANARY.encode() + b"B" * 150000
        else:
            body = RESPONSE_CANARY.encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        n = int(self.headers.get("Content-Length", "0"))
        got = self.rfile.read(n).decode(errors="replace")
        body = json.dumps({"body_ok": got == REQUEST_CANARY}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Set-Cookie", "session=" + RESPONSE_CANARY)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

class S(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


if len(sys.argv) > 2:
    with open(sys.argv[2], "w") as f:
        f.write(REQUEST_CANARY + "\n")

if len(sys.argv) > 3:
    # macOS keychain item for the keychain scenario (the client deletes it afterwards)
    subprocess.run(["security", "add-generic-password", "-U", "-A", "-s", sys.argv[3], "-a", "scan",
                    "-w", REQUEST_CANARY], check=True, stdout=subprocess.DEVNULL)

srv = S(("127.0.0.1", int(sys.argv[1])), H)
print("READY", flush=True)
srv.serve_forever()
