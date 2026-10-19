import http.server
import socketserver
import sys
import uuid
import os

JAVA_SECRET = f"VAULT_SECRET_JAVA_{uuid.uuid4()}_LEAK"
NATIVEPATH_SECRET = f"VAULT_SECRET_NATIVEPATH_{uuid.uuid4()}_ZEROIZED"

os.makedirs("target", exist_ok=True)
with open("target/tokens.txt", "w") as f:
    f.write(f"{JAVA_SECRET}\n{NATIVEPATH_SECRET}\n")

class MockHandler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/secret/java":
            payload = JAVA_SECRET.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
        elif self.path == "/secret/nativepath":
            payload = NATIVEPATH_SECRET.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, format, *args):
        pass

if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 49999
    with socketserver.TCPServer(("127.0.0.1", port), MockHandler) as httpd:
        print(f"READY {port}", flush=True)
        httpd.serve_forever()
