#!/usr/bin/env python3
"""A Subsonic server with one song, whichever is asked for, for the walkthrough (simulator-check.sh).

YouTube turns the CI's runners away, so this is what lets the simulator play and download over the
network: the app's own source chain, the core's Subsonic client, the engine's HttpSource and the Darwin
HTTP engine all run for real against it. Each request is a line in the log the script reads afterwards.

    own-server.py <port> <log file>
"""
import http.server
import json
import math
import struct
import sys
import urllib.parse

RATE = 44100
SECONDS = 120


def tone():
    frames = b"".join(struct.pack("<h", int(8000 * math.sin(i * 0.0626))) for i in range(RATE * 2))
    data = frames * (SECONDS // 2)
    head = b"RIFF" + struct.pack("<I", 36 + len(data)) + b"WAVEfmt " + struct.pack("<IHHIIHH", 16, 1, 1, RATE, RATE * 2, 2, 16)
    return head + b"data" + struct.pack("<I", len(data)) + data


BODY = tone()
port, log = int(sys.argv[1]), open(sys.argv[2], "w", buffering=1)


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def json(self, inner):
        body = json.dumps({"subsonic-response": {"status": "ok", "version": "1.16.1", "type": "walkthrough", "serverVersion": "1", **inner}}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        url = urllib.parse.urlparse(self.path)
        query = urllib.parse.parse_qs(url.query)
        if url.path == "/rest/ping.view":
            log.write("PING\n")
            self.json({})
        elif url.path == "/rest/search3.view":
            title = query.get("query", [""])[0]
            log.write(f"SEARCH {title}\n")
            # No duration, so the core's matcher takes it whatever the catalogue says the song lasts.
            song = {"id": "tone", "title": title, "artist": "Daft Punk", "duration": 0, "suffix": "wav", "bitRate": 705}
            self.json({"searchResult3": {"song": [song]}})
        elif url.path == "/rest/stream.view":
            self.stream()
        else:
            log.write(f"UNKNOWN {url.path}\n")
            self.send_error(404)

    def stream(self):
        start, end = 0, len(BODY) - 1
        wanted = self.headers.get("Range")
        if wanted:
            first, _, last = wanted.removeprefix("bytes=").partition("-")
            start = int(first or 0)
            end = min(int(last), end) if last else end
        log.write(f"STREAM {self.headers.get('User-Agent', '')!r} from {start}\n")
        self.send_response(206 if wanted else 200)
        self.send_header("Content-Type", "audio/wav")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end - start + 1))
        if wanted:
            self.send_header("Content-Range", f"bytes {start}-{end}/{len(BODY)}")
        self.end_headers()
        try:
            self.wfile.write(BODY[start : end + 1])
        except (BrokenPipeError, ConnectionResetError):
            self.close_connection = True

    def log_message(self, *_):
        pass


http.server.ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
