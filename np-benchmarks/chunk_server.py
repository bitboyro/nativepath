import socket, sys, threading
port, mb, frame = int(sys.argv[1]), int(sys.argv[2]), int(sys.argv[3])
block = bytes((i * 31 + 7) & 0xFF for i in range(65536))
total = mb * 1024 * 1024
payload_frame = (block * (frame // 65536 + 1))[:frame] if frame >= 65536 else None
def serve(c):
    try:
        c.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        f = c.makefile("rb")
        while True:
            line = f.readline()
            if not line: return
            while f.readline() not in (b"\r\n", b""): pass
            c.sendall(b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n")
            sent = 0
            hdr = b"%x\r\n" % frame
            while sent < total:
                # frames are slices of the repeating 64 KB pattern so the CRC is the same for any frame size
                off = sent % 65536
                n = min(frame, total - sent)
                data = (block[off:] + block + block)[:n] if n <= 131072 else None
                if data is None:
                    parts = []; need = n; o = off
                    while need:
                        take = min(need, 65536 - o); parts.append(block[o:o+take]); need -= take; o = 0
                    data = b"".join(parts)
                c.sendall(b"%x\r\n" % n + data + b"\r\n")
                sent += n
            c.sendall(b"0\r\n\r\n")
    except OSError:
        pass
    finally:
        c.close()
s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
s.bind(("127.0.0.1", port)); s.listen(8)
print("ready", flush=True)
while True:
    c, _ = s.accept(); threading.Thread(target=serve, args=(c,), daemon=True).start()
