"""Engine loop builder + mix check.

  python tools/engine_audio.py build [name...]   sources in tools/reference/audio -> pack .ogg
  python tools/engine_audio.py check <vehicle>   simulate the vehicle's `display.engine` loops over an rpm sweep

Needs numpy, scipy, soundfile, ffmpeg.
"""
import argparse
import json
import os
import subprocess
import sys
import tempfile

import numpy as np
import scipy.signal as sg
import soundfile as sf

SR = 44100
TICK = SR // 20
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'tools', 'reference', 'audio')
PACK = os.path.join(ROOT, 'src', 'main', 'resources', 'combinedassault_packs', 'combinedassault')
SOUNDS = os.path.join(PACK, 'assets', 'combinedassault', 'sounds')
VEHICLES = os.path.join(PACK, 'combinedassault', 'combinedassault', 'vehicles')

# psola: (source, t0, t1, f0 lo, f0 hi, tracked harmonic, fires/rev, target rpm, seconds)
# xfade: (source, t0, t1, loop start, min len, max len, crossfade), seconds
LOOPS = {
    'vehicle/humvee/humvee_engine_idle': ('psola', 'humvee_idle.flac', 0.03, 1.29, 38, 47, 1, 4, 700, 4.0),
    'vehicle/humvee/humvee_engine_mid': ('psola', 'humvee_move.flac', 10.8, 13.2, 76, 86, 2, 4, 1400, 6.0),
    'vehicle/humvee/humvee_engine_high': ('psola', 'humvee_move.flac', 17.3, 20.3, 84, 96, 2, 4, 2600, 6.0),
    'vehicle/m1a2/m1a2_turbine_idle': ('xfade', 'mcheli_abrams.ogg', 0.2, 3.3, 0.6, 2.0, 2.7, 0.35),
    'vehicle/m1a2/m1a2_turbine_run': ('xfade', 'mcheli_tank_gte.ogg', 0.3, 13.4, 1.0, 9.0, 12.0, 0.6),
}
LOOP_RMS_DB = -16
BAND = sg.butter(4, [20, 15000], 'bandpass', fs=SR, output='sos')


def load_mono(path, t0=None, t1=None):
    x, sr = sf.read(path, always_2d=True)
    x = x.mean(1)
    if sr != SR:
        x = sg.resample_poly(x, SR, sr)
    return x[0 if t0 is None else int(t0 * SR):len(x) if t1 is None else int(t1 * SR)]


def f0_track(x, lo, hi, harm, hop=0.05, win=0.5):
    n, h = int(win * SR), int(hop * SR)
    ts, fs = [], []
    for i in range(0, len(x) - n, h):
        S = np.abs(np.fft.rfft(x[i:i + n] * np.hanning(n), 8 * n))
        f = np.fft.rfftfreq(8 * n, 1 / SR)
        m = (f > lo * harm) & (f < hi * harm)
        ts.append(i + n / 2)
        fs.append(f[m][np.argmax(S[m])] / harm)
    return np.interp(np.arange(len(x)), ts, sg.medfilt(np.array(fs), 9))


def pitch_marks(x, f0):
    """One mark per firing pulse, phase-locked to the previous grain by correlation."""
    lp = sg.sosfiltfilt(sg.butter(4, 4 * np.median(f0) / (SR / 2), output='sos'), x)
    p0 = int(SR / f0[0])
    marks = [int(np.argmax(lp[p0:2 * p0])) + p0]
    while True:
        period = SR / f0[marks[-1]]
        c, r, g = int(marks[-1] + period), int(0.2 * period), int(period / 2)
        if c + r + g >= len(x):
            break
        ref = x[marks[-1] - g:marks[-1] + g]
        marks.append(max(range(c - r, c + r + 1), key=lambda k: np.dot(ref, x[k - g:k + g])))
    return np.array(marks[2:-2])


def psola(x, f0, target_hz, seconds):
    """TD-PSOLA at constant pulse rate onto a circular buffer: formants kept, loop seamless by construction.
    Grain order palindromic over the source => no content step at the wrap."""
    marks = pitch_marks(x, f0)
    length = int(round(seconds * SR))
    pulses = int(round(length * target_hz / SR))
    spacing = length / pulses
    out = np.zeros(length)
    for i in range(pulses):
        m = marks[int(round((1 - abs(2 * i / pulses - 1)) * (len(marks) - 1)))]
        half = int(round(SR / f0[m]))
        idx = (np.arange(-half, half) + int(round(i * spacing))) % length
        np.add.at(out, idx, x[m - half:m + half] * np.hanning(2 * half))
    return out


def xfade(x, a, min_len, max_len, fade):
    """Loop [a, b): b by waveform match of x[b-fade:b] to x[a-fade:a]; tail crossfaded into the pre-roll of a."""
    ref = x[a - fade:a]

    def corr(b):
        w = x[b - fade:b]
        return np.dot(ref, w) / np.sqrt(np.dot(ref, ref) * np.dot(w, w))

    b0 = max(range(a + min_len, min(a + max_len, len(x)), fade // 8), key=corr)
    b = max(range(b0 - fade // 8, min(b0 + fade // 8, len(x) + 1)), key=corr)
    k = max(corr(b), 0)
    t = np.linspace(0, 1, fade, endpoint=False)
    y = x[a:b].copy()
    # correlated => linear, uncorrelated => equal power
    y[-fade:] = x[b - fade:b] * (k * (1 - t) + (1 - k) * np.cos(np.pi / 2 * t)) \
        + ref * (k * t + (1 - k) * np.sin(np.pi / 2 * t))
    return y


def build(names):
    for name in names or LOOPS:
        kind, src, *p = LOOPS[name]
        if kind == 'psola':
            t0, t1, lo, hi, harm, per_rev, rpm, seconds = p
            x = load_mono(os.path.join(SRC, src), t0, t1)
            y = psola(x, f0_track(x, lo, hi, harm), rpm * per_rev / 60, seconds)
        else:
            t0, t1, a, lo, hi, fade = p
            x = load_mono(os.path.join(SRC, src), t0, t1)
            y = xfade(x, int(a * SR), int(lo * SR), int(hi * SR), int(fade * SR))
        # circular filter: middle of three copies => no edge transient at the wrap
        y = sg.sosfiltfilt(BAND, np.tile(y, 3))[len(y):2 * len(y)]
        y *= 10 ** (LOOP_RMS_DB / 20) / np.sqrt(np.mean(y ** 2))
        peak = np.max(np.abs(y))
        if peak >= 1:
            sys.exit(f'{name}: clips ({peak:.2f})')
        out = os.path.join(SOUNDS, name + '.ogg')
        os.makedirs(os.path.dirname(out), exist_ok=True)
        with tempfile.NamedTemporaryFile(suffix='.wav') as tmp:
            sf.write(tmp.name, y.astype(np.float32), SR, subtype='FLOAT')
            subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', tmp.name, '-c:a', 'libvorbis', '-q:a', '6', out], check=True)
        print(f'{name}: {len(y) / SR:.3f} s, peak {20 * np.log10(peak):.1f} dBFS')


def curve(c, x):
    if isinstance(c, (int, float)):
        return float(c)
    return float(np.interp(x, [p[0] for p in c], [p[1] for p in c]))


def render(loops, inputs, fade_default=0.1):
    """In-game model: curves per tick, gain slewed by `fade`/tick, pitch = playback rate clamped [0.5, 2]."""
    out = np.zeros(len(inputs) * TICK)
    sounds = json.load(open(os.path.join(PACK, 'assets', 'combinedassault', 'sounds.json')))
    for loop in loops:
        event = loop['sound']['event'].split(':')[1]
        x = load_mono(os.path.join(SOUNDS, sounds[event]['sounds'][0].split(':')[1] + '.ogg'))
        key = loop.get('input', 'rpm')
        pos, g, fade = 0.0, 0.0, loop.get('fade', fade_default)
        for t, inp in enumerate(inputs):
            v = inp[key]
            g1 = g + np.clip(curve(loop.get('volume', 1), v) * curve(loop.get('gate', 1), inp['power']) - g, -fade, fade)
            p = float(np.clip(curve(loop.get('pitch', 1), v), 0.5, 2.0))
            idx = pos + p * np.arange(TICK)
            i0 = np.floor(idx).astype(int)
            fr = idx - i0
            seg = x[i0 % len(x)] * (1 - fr) + x[(i0 + 1) % len(x)] * fr
            out[t * TICK:(t + 1) * TICK] += seg * np.linspace(g, g1, TICK, endpoint=False)
            pos, g = (pos + p * TICK) % len(x), g1
    return out


def check(vehicle, idle, out_wav, fires_per_rev):
    """Sweep idle -> full -> idle; per 50 ms: loudness, and (piston) whether the firing comb follows rpm."""
    d = json.load(open(os.path.join(VEHICLES, vehicle + '.json')))
    t = np.arange(0, 22, 0.05)
    rpm = np.interp(t, [0, 3, 11, 13, 19, 22], [idle, idle, 1, 1, idle, idle])
    inputs = [dict(rpm=r, power=1.0, speed=max(0.0, (r - idle) / (1 - idle)), throttle=float(r > idle)) for r in rpm]
    y = render(d['display']['engine']['loops'], inputs)
    max_rpm = d['attributes'].get('transmission', {}).get('max_rpm')
    n, h = int(0.2 * SR), int(0.05 * SR)
    rms, err = [], []
    for i in range(0, len(y) - n, h):
        rms.append(20 * np.log10(np.sqrt(np.mean(y[i:i + n] ** 2)) + 1e-9))
        if fires_per_rev and max_rpm:
            f_exp = np.interp((i + n / 2) / SR, t, rpm) * max_rpm * fires_per_rev / 60
            S = np.abs(np.fft.rfft(y[i:i + n] * np.hanning(n), 8 * n))
            f = np.fft.rfftfreq(8 * n, 1 / SR)
            cands = f_exp * np.exp(np.linspace(np.log(0.6), np.log(1.6), 241))
            best = cands[int(np.argmax([sum(np.interp(k * c, f, S) for k in range(1, 5)) for c in cands]))]
            err.append(1200 * np.log2(best / f_exp))
    rms = np.array(rms)
    print(f'{vehicle}: loudness idle {rms[40]:.1f} dB, full {rms[230]:.1f} dB, max 50 ms step {np.max(np.abs(np.diff(rms))):.1f} dB')
    if err:
        err = np.abs(np.array(err))
        print(f'  firing comb within 60 cents of rpm: {np.mean(err < 60) * 100:.1f}%, median {np.median(err):.0f} cents')
    if out_wav:
        sf.write(out_wav, (y / np.max(np.abs(y)) * 0.89).astype(np.float32), SR, subtype='FLOAT')


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest='cmd', required=True)
    b = sub.add_parser('build')
    b.add_argument('names', nargs='*')
    c = sub.add_parser('check')
    c.add_argument('vehicle')
    c.add_argument('--idle', type=float, required=True, help='rpm input at idle (0..1)')
    c.add_argument('--fires-per-rev', type=float, default=0, help='piston: firing pulses per crank rev')
    c.add_argument('--wav', help='write the simulated mix')
    a = ap.parse_args()
    if a.cmd == 'build':
        build(a.names)
    else:
        check(a.vehicle, a.idle, a.wav, a.fires_per_rev)


if __name__ == '__main__':
    main()
