#!/usr/bin/env python3
"""FNV-1a 64 of some bytes, the fingerprint the load generator checks bodies against.

  np_fnv.py                    reads stdin;           prints "<length> <hash>"
  np_fnv.py --text hello       the bytes of the text
  np_fnv.py --repeat A 65536   the text repeated that many times
"""
import sys


def fnv(b):
    h = 0xCBF29CE484222325
    for x in b:
        h = ((h ^ x) * 0x100000001B3) & 0xFFFFFFFFFFFFFFFF
    return h


if len(sys.argv) >= 3 and sys.argv[1] == "--text":
    data = sys.argv[2].encode()
elif len(sys.argv) >= 4 and sys.argv[1] == "--repeat":
    data = sys.argv[2].encode() * int(sys.argv[3])
else:
    data = sys.stdin.buffer.read()
print(len(data), format(fnv(data), "016x"))
