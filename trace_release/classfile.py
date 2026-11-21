"""Read JVM method descriptors and line tables."""
from __future__ import annotations
import hashlib
from pathlib import Path
import struct

class Reader:
    def __init__(self, data: bytes):
        self.data, self.pos = data, 0

    def take(self, n: int) -> bytes:
        if n < 0 or self.pos + n > len(self.data):
            raise ValueError("Truncated class file")
        out = self.data[self.pos:self.pos + n]
        self.pos += n
        return out

    def u1(self): return self.take(1)[0]
    def u2(self): return struct.unpack(">H", self.take(2))[0]
    def u4(self): return struct.unpack(">I", self.take(4))[0]


def read_class(path: str | Path) -> dict:
    path = Path(path)
    data = path.read_bytes()
    r = Reader(data)
    if r.u4() != 0xCAFEBABE:
        raise ValueError(f"Not a JVM class: {path}")
    minor, major, n = r.u2(), r.u2(), r.u2()
    cp = [None] * n
    i = 1
    while i < n:
        tag = r.u1()
        if tag == 1:
            # Class/member identifiers here are ASCII; modified-UTF8 NUL
            # handling also preserves ordinary text constants when present.
            raw = r.take(r.u2()).replace(b'\xc0\x80', b'\x00')
            cp[i] = (tag, raw.decode('utf-8', errors='surrogatepass'))
        elif tag in (7, 8, 16, 19, 20): cp[i] = (tag, r.u2())
        elif tag in (3, 4): cp[i] = (tag, r.take(4))
        elif tag in (5, 6): cp[i] = (tag, r.take(8)); i += 1
        elif tag in (9, 10, 11, 12, 17, 18): cp[i] = (tag, r.u2(), r.u2())
        elif tag == 15: cp[i] = (tag, r.u1(), r.u2())
        else: raise ValueError(f"Unknown constant-pool tag {tag} in {path}")
        i += 1
    def utf(index): return cp[index][1]
    def class_name(index): return utf(cp[index][1]).replace('/', '.') if index else None
    def attrs(reader):
        out = {}
        for _ in range(reader.u2()):
            name, length = utf(reader.u2()), reader.u4()
            out.setdefault(name, []).append(reader.take(length))
        return out
    access, this, parent = r.u2(), r.u2(), r.u2()
    for _ in range(r.u2()): r.u2()
    for _ in range(r.u2()):
        r.take(6); attrs(r)
    owner = class_name(this)
    methods = []
    for _ in range(r.u2()):
        flags, name, descriptor = r.u2(), utf(r.u2()), utf(r.u2())
        a = attrs(r)
        line_table = []
        for code in a.get('Code', []):
            cr = Reader(code)
            cr.u2(); cr.u2(); cr.take(cr.u4())
            cr.take(8 * cr.u2())
            sub = attrs(cr)
            for payload in sub.get('LineNumberTable', []):
                lr = Reader(payload)
                line_table.extend({'offset': lr.u2(), 'line': lr.u2()} for _ in range(lr.u2()))
        methods.append({'owner': owner, 'name': name, 'descriptor': descriptor,
                        'entity': f'{owner}#{name}{descriptor}', 'access_flags': flags,
                        'bridge': bool(flags & 0x0040), 'synthetic': bool(flags & 0x1000) or 'Synthetic' in a,
                        'static': bool(flags & 0x0008), 'abstract': bool(flags & 0x0400),
                        'native': bool(flags & 0x0100), 'has_code': 'Code' in a,
                        'line_table': line_table,
                        'signature': utf(Reader(a['Signature'][0]).u2()) if 'Signature' in a else None})
    a = attrs(r)
    source = utf(Reader(a['SourceFile'][0]).u2()) if 'SourceFile' in a else None
    inners = []
    for payload in a.get('InnerClasses', []):
        ir = Reader(payload)
        for _ in range(ir.u2()):
            inner, outer, simple, flags = ir.u2(), ir.u2(), ir.u2(), ir.u2()
            inners.append({'inner': class_name(inner), 'outer': class_name(outer),
                           'simple_name': utf(simple) if simple else None, 'access_flags': flags})
    if r.pos != len(data): raise ValueError(f"Unconsumed class bytes: {path}")
    return {'class_file': str(path.resolve()), 'class_sha256': hashlib.sha256(data).hexdigest(),
            'owner': owner, 'super': class_name(parent), 'class_flags': access,
            'version': [major, minor], 'source_file': source, 'inner_classes': inners,
            'methods': methods}
