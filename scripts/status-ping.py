#!/usr/bin/env python3
"""Open a game connection and send a status request.

The handshake and status request are enough to activate a server-side
Connection, so this exercises sculk's channel hook without a real client.
Usage: status-ping.py PORT [HOST]
"""
import socket
import struct
import sys


def varint(n: int) -> bytes:
    out = b""
    while True:
        b = n & 0x7F
        n >>= 7
        out += bytes([b | (0x80 if n else 0)])
        if not n:
            return out


def packet(pid: int, data: bytes) -> bytes:
    body = varint(pid) + data
    return varint(len(body)) + body


port = int(sys.argv[1])
host = sys.argv[2] if len(sys.argv) > 2 else "127.0.0.1"
# Protocol version is irrelevant for a status request; servers answer anyway.
handshake = varint(767) + varint(len(host)) + host.encode() + struct.pack(">H", port) + varint(1)
with socket.create_connection((host, port), timeout=10) as s:
    s.sendall(packet(0, handshake) + packet(0, b""))
    print(f"status reply: {len(s.recv(65536))} bytes")
