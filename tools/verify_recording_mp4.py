"""Validate finalized hybrid MP4 sample sizes against every fragment, per track."""
import struct
import sys
from pathlib import Path


def boxes(data, begin=0, end=None):
    end = len(data) if end is None else end
    while begin + 8 <= end:
        size, kind = struct.unpack_from(">I4s", data, begin)
        header = 8
        if size == 1:
            size = struct.unpack_from(">Q", data, begin + 8)[0]
            header = 16
        if size == 0:
            size = end - begin
        if size < header or begin + size > end:
            raise ValueError(f"Invalid {kind!r} box at {begin}")
        yield kind, begin + header, begin + size
        begin += size


def child(data, parent, name):
    return next(b for b in boxes(data, parent[1], parent[2]) if b[0] == name)


def inspect(path):
    data = Path(path).read_bytes()
    roots = list(boxes(data))
    moov = [b for b in roots if b[0] == b"moov"][-1]
    # Hybrid finalization wraps the original moov/moof/mdat sequence in a
    # single outer mdat, then appends the finalized moov for ordinary players.
    fragments = list(roots)
    if not any(b[0] == b"moof" for b in fragments):
        for media in roots:
            if media[0] == b"mdat" and data[media[1] + 4:media[1] + 8] == b"moov":
                fragments.extend(boxes(data, media[1], media[2]))
    finalized = {}
    for track in boxes(data, moov[1], moov[2]):
        if track[0] != b"trak":
            continue
        tkhd = child(data, track, b"tkhd")
        track_id = struct.unpack_from(">I", data, tkhd[1] + (20 if data[tkhd[1]] == 1 else 12))[0]
        stbl = child(data, child(data, child(data, track, b"mdia"), b"minf"), b"stbl")
        stsz = child(data, stbl, b"stsz")
        size, count = struct.unpack_from(">II", data, stsz[1] + 4)
        finalized[track_id] = [size] * count if size else list(struct.unpack_from(f">{count}I", data, stsz[1] + 12))

    fragmented = {}
    for moof in fragments:
        if moof[0] != b"moof":
            continue
        for traf in boxes(data, moof[1], moof[2]):
            if traf[0] != b"traf":
                continue
            tfhd = child(data, traf, b"tfhd")
            flags, track_id = struct.unpack_from(">II", data, tfhd[1])
            flags &= 0xFFFFFF
            cursor = tfhd[1] + 8
            for flag, length in ((1, 8), (2, 4), (8, 4)):
                if flags & flag:
                    cursor += length
            default_size = struct.unpack_from(">I", data, cursor)[0] if flags & 16 else 0
            samples = fragmented.setdefault(track_id, [])
            for trun in boxes(data, traf[1], traf[2]):
                if trun[0] != b"trun":
                    continue
                trun_flags, count = struct.unpack_from(">II", data, trun[1])
                trun_flags &= 0xFFFFFF
                cursor = trun[1] + 8
                if trun_flags & 1:
                    cursor += 4
                if trun_flags & 4:
                    cursor += 4
                for _ in range(count):
                    sample_size = default_size
                    for flag in (0x100, 0x200, 0x400, 0x800):
                        if trun_flags & flag:
                            value = struct.unpack_from(">I", data, cursor)[0]
                            cursor += 4
                            if flag == 0x200:
                                sample_size = value
                    samples.append(sample_size)
    if not finalized or set(finalized) != set(fragmented):
        raise ValueError("Missing finalized/fragmented tracks")
    for track_id, sizes in finalized.items():
        fragment_sizes = fragmented[track_id]
        mismatches = sum(a != b for a, b in zip(sizes, fragment_sizes)) + abs(len(sizes) - len(fragment_sizes))
        print(f"Track {track_id}: stsz={len(sizes)} trun={len(fragment_sizes)} mismatches={mismatches}")
        if not sizes or mismatches:
            raise ValueError(f"Track {track_id} finalized sample sizes do not match fragments")


if __name__ == "__main__":
    inspect(sys.argv[1])
