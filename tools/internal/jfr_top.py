#!/usr/bin/env python3
"""CPU samples from a JFR file: share per thread, top self frames, top inclusive frames under a package.
usage: tools/jfr_top.py <file.jfr> [package-prefix=com.wf.combinedassault] [thread-substring] [under-frame-substring]
under => also: callee frames (any package) inclusive below the first frame matching it."""
import collections
import json
import subprocess
import sys

path = sys.argv[1]
prefix = sys.argv[2] if len(sys.argv) > 2 else 'org.combinedassault'
only = sys.argv[3] if len(sys.argv) > 3 and sys.argv[3] else None
under = sys.argv[4] if len(sys.argv) > 4 else None
below = collections.Counter()
under_total = 0
raw = subprocess.run(['jfr', 'print', '--json', '--stack-depth', '64', '--events', 'jdk.ExecutionSample', path],
                     capture_output=True, text=True, check=True).stdout
events = json.loads(raw)['recording']['events']

threads = collections.Counter()
self_frames = collections.Counter()
inclusive = collections.Counter()
total = 0
for e in events:
    v = e['values']
    thread = (v.get('sampledThread') or {}).get('javaName') or '?'
    frames = [f"{f['method']['type']['name'].replace('/', '.')}.{f['method']['name']}" for f in v['stackTrace']['frames']]
    if not any(f.startswith(prefix) for f in frames):
        continue
    kind = 'server' if thread == 'Server thread' else 'worker' if 'hysics' in thread or 'combinedassault' in thread.lower() else thread
    threads[kind] += 1
    if only and only not in thread:
        continue
    total += 1
    self_frames[frames[0]] += 1
    for f in set(frames):
        if f.startswith(prefix):
            inclusive[f] += 1
    if under:
        hit = next((i for i in range(len(frames) - 1, -1, -1) if under in frames[i]), None)
        if hit is not None:
            under_total += 1
            for f in set(frames[:hit]):
                below[f] += 1

print(f'samples touching {prefix}: {sum(threads.values())}')
for t, n in threads.most_common(8):
    print(f'  {n:6d} {t}')
print(f'\n== self (thread filter {only!r}, {total} samples)')
for f, n in self_frames.most_common(25):
    print(f'  {100 * n / total:5.1f}% {f}')
print(f'\n== inclusive {prefix}*')
for f, n in inclusive.most_common(40):
    print(f'  {100 * n / total:5.1f}% {f}')
if under:
    print(f'\n== below {under!r} ({under_total} samples = {100 * under_total / max(1, total):.1f}%)')
    for f, n in below.most_common(40):
        print(f'  {100 * n / max(1, under_total):5.1f}% {f}')
