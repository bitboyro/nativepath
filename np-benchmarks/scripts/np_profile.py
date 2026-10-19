#!/usr/bin/env python3
"""Turns `perf report --no-children --sort sym --stdio` text into JSON: the top symbols and the
share of samples per category. Usage: np_profile.py <perf-report.txt> <pid> <seconds>"""
import json
import re
import sys

# First match wins; order matters (kernel network before the generic kernel bucket).
CATEGORIES = [
    ("syscalls", r"entry_SYSCALL|do_syscall|__x64_sys|__arm64_sys|syscall_|^sys_|_copy_(to|from)_user|epoll|futex|\bread\b|\bwrite\b|\bsend\b|\brecv\b"),
    ("kernel network", r"\btcp_|\bip_|__ip_|\bnf_|__netif|net_rx|net_tx|sock_|sk_|skb|inet_|dev_queue|napi|__dev_|loopback|\btcp\b|__tcp"),
    ("tls and crypto", r"aes|chacha|poly1305|gcm|sha(1|2|256|384|512)|ring::|ring_core|rustls|ecp_|p256|x25519|bn_|curve25519|ghash|EVP_|ssl_|SSL_|ssl3|BoringSSL|openssl"),
    ("http parsing", r"httparse|hyper::proto|h1::|parse_headers|decode|encode|netty.*(Http|Codec)|Http11|HttpServerCodec|coyote|CoyoteAdapter"),
    ("async runtime", r"tokio|mio::|park|waker|schedule|Notify|oneshot|mpsc|block_on|poll_|futures"),
    ("allocation", r"malloc|\bfree\b|realloc|calloc|alloc::|dealloc|jemalloc|zeroiz|memset|wipe|Cleaner|Arena|G1|ZGC|gc_|GC"),
    ("jvm runtime", r"libjvm|Interpreter|JavaThread|VM_|Runtime1|nmethod|StubRoutines|SharedRuntime|CompileBroker|JIT|jdk\.internal|java/|Ljava|Lio/netty|Lorg/|Lro/bitboy"),
    ("memory copy", r"memcpy|memmove|__memcpy|copy_user"),
]
LINE = re.compile(r"^\s*([0-9.]+)%\s+(?:\[[.kgu]\]\s+)?(.*\S)\s*$")


def main():
    path, pid, seconds = sys.argv[1], sys.argv[2], sys.argv[3]
    syms = []
    for line in open(path, errors="replace"):
        m = LINE.match(line)
        if m:
            syms.append((float(m.group(1)), m.group(2)))
    total = sum(p for p, _ in syms) or 1.0
    cats, other = {}, 0.0
    for pct, sym in syms:
        for name, rx in CATEGORIES:
            if re.search(rx, sym):
                cats[name] = cats.get(name, 0.0) + pct
                break
        else:
            other += pct
    out = {
        "pid": int(pid), "seconds": int(seconds), "samples_pct_total": round(total, 1),
        "categories": [{"name": k, "pct": round(100 * v / total, 1)} for k, v in sorted(cats.items(), key=lambda kv: -kv[1])]
        + ([{"name": "other", "pct": round(100 * other / total, 1)}] if other else []),
        "top": [{"sym": s[:120], "pct": round(100 * p / total, 1)} for p, s in sorted(syms, key=lambda x: -x[0])[:25]],
    }
    print(json.dumps(out))


main()
