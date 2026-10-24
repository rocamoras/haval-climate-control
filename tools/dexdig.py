# -*- coding: utf-8 -*-
"""Parser minimo de DEX: acha a classe que declara um campo/metodo e lista o que ela
referencia (strings, campos, metodos). Serve para responder "que propriedade essa tela
escreve?" sem apktool/jadx.

Uso: dexdig.py <classes.dex> <identificador>
"""
import struct, sys, collections

# Tamanho em code units (u2) de cada opcode Dalvik. Indice = opcode.
LEN = [0] * 256
for op in range(256):
    LEN[op] = 1
for op in list(range(0x1a, 0x1b)) + [0x00]:
    pass
_SPEC = {
    1: list(range(0x00, 0x01)) + [0x0e] + list(range(0x01, 0x0e)) + list(range(0x0f, 0x12)),
}
# Tabela oficial (formato -> tamanho). Preenchida por faixas.
def _set(rng, n):
    for o in rng:
        LEN[o] = n
_set(range(0x00, 0x100), 2)          # default 2 (a maioria)
_set([0x00], 1)                      # nop
_set(range(0x01, 0x0e), 1)           # move/return/... 12x/11x
_set([0x0e], 1)                      # return-void
_set([0x02, 0x03], 2)
_set([0x05, 0x06], 2)
_set([0x08, 0x09], 2)
_set([0x1a], 2)                      # const-string
_set([0x1b], 3)                      # const-string/jumbo
_set([0x17, 0x19], 3)                # const-wide/32, const-wide/high16 -> 3/2
_set([0x18], 5)                      # const-wide
_set([0x26, 0x2b, 0x2c], 3)          # fill-array-data, packed/sparse-switch
_set([0x00], 1)
_set(range(0x24, 0x26), 3)           # filled-new-array
_set(range(0x6e, 0x73), 3)           # invoke-kind
_set(range(0x74, 0x79), 3)           # invoke-kind/range
_set([0x14, 0x15], 3)                # const, const/high16 -> 3/2
_set([0x15], 2)
_set([0x03, 0x07], 1)
_set([0x0a, 0x0b, 0x0c, 0x0d], 1)
_set([0x12], 1)                      # const/4
_set([0x1f, 0x22], 2)
_set([0x21, 0x27, 0x28], 1)
_set([0x28], 1)                      # goto
_set([0x29], 2)                      # goto/16
_set([0x2a], 3)                      # goto/32
_set(range(0x7b, 0x90), 1)           # unop
_set(range(0x90, 0xaf), 2)           # binop
_set(range(0xb0, 0xd0), 1)           # binop/2addr
_set(range(0xd0, 0xe3), 2)           # binop/lit
_set([0xfa, 0xfb], 4)                # invoke-polymorphic
_set([0xfc, 0xfd], 3)
_set([0xfe, 0xff], 2)


def uleb(b, o):
    r = 0; s = 0
    while True:
        x = b[o]; o += 1
        r |= (x & 0x7f) << s
        if not (x & 0x80):
            return r, o
        s += 7


class Dex:
    def __init__(self, path):
        self.b = b = open(path, "rb").read()
        (self.str_n, self.str_o, self.typ_n, self.typ_o, self.pro_n, self.pro_o,
         self.fld_n, self.fld_o, self.mth_n, self.mth_o, self.cls_n,
         self.cls_o) = struct.unpack_from("<12I", b, 56)
        self.strings = []
        for i in range(self.str_n):
            off = struct.unpack_from("<I", b, self.str_o + 4 * i)[0]
            _, off = uleb(b, off)
            end = b.index(b"\x00", off)
            self.strings.append(b[off:end].decode("utf-8", "replace"))
        self.types = [self.strings[struct.unpack_from("<I", b, self.typ_o + 4 * i)[0]]
                      for i in range(self.typ_n)]
        self.fields = []
        for i in range(self.fld_n):
            c, t, n = struct.unpack_from("<HHI", b, self.fld_o + 8 * i)
            self.fields.append((self.types[c], self.types[t], self.strings[n]))
        self.methods = []
        for i in range(self.mth_n):
            c, p, n = struct.unpack_from("<HHI", b, self.mth_o + 8 * i)
            self.methods.append((self.types[c], self.strings[n]))

    def classes(self):
        for i in range(self.cls_n):
            o = self.cls_o + 32 * i
            ci, _, _, _, _, _, data_off, _ = struct.unpack_from("<8I", self.b, o)
            yield self.types[ci], data_off

    def class_body(self, data_off):
        """Devolve (campos, metodos_com_code_off)."""
        b = self.b
        if not data_off:
            return [], []
        o = data_off
        sf, o = uleb(b, o); inf, o = uleb(b, o); dm, o = uleb(b, o); vm, o = uleb(b, o)
        flds, mths = [], []
        idx = 0
        for _ in range(sf + inf):
            if _ == sf:
                idx = 0
            d, o = uleb(b, o); _a, o = uleb(b, o)
            idx += d
            flds.append(self.fields[idx][2])
        for grp in (dm, vm):
            idx = 0
            for _ in range(grp):
                d, o = uleb(b, o); _a, o = uleb(b, o); co, o = uleb(b, o)
                idx += d
                mths.append((self.methods[idx][1], co))
        return flds, mths

    def refs(self, code_off):
        """Strings, campos e metodos citados por um metodo."""
        if not code_off:
            return [], [], []
        b = self.b
        insns_size = struct.unpack_from("<I", b, code_off + 12)[0]
        base = code_off + 16
        S, F, M = [], [], []
        i = 0
        while i < insns_size:
            unit = struct.unpack_from("<H", b, base + 2 * i)[0]
            op = unit & 0xFF
            n = LEN[op] or 1
            try:
                if op == 0x1a:
                    S.append(self.strings[struct.unpack_from("<H", b, base + 2 * (i + 1))[0]])
                elif op == 0x1b:
                    S.append(self.strings[struct.unpack_from("<I", b, base + 2 * (i + 1))[0]])
                elif 0x52 <= op <= 0x6d:
                    F.append(self.fields[struct.unpack_from("<H", b, base + 2 * (i + 1))[0]][2])
                elif 0x6e <= op <= 0x78:
                    M.append(self.methods[struct.unpack_from("<H", b, base + 2 * (i + 1))[0]][1])
            except (IndexError, struct.error):
                pass
            i += n
        return S, F, M


if __name__ == "__main__":
    d = Dex(sys.argv[1])
    needle = sys.argv[2]
    hits = 0
    for cname, doff in d.classes():
        flds, mths = d.class_body(doff)
        names = set(flds) | {m for m, _ in mths}
        if needle not in names:
            continue
        hits += 1
        print("=" * 70)
        print("CLASSE", cname)
        print("campos:", ", ".join(sorted(set(flds))[:40]))
        print()
        allS = collections.Counter()
        for mname, co in mths:
            S, F, M = d.refs(co)
            interesting = [s for s in S if s.startswith("car.") or "limit" in s.lower()
                           or "batt" in s.lower()]
            if interesting or needle.lower() in mname.lower():
                print("  metodo %s" % mname)
                if interesting:
                    print("     strings:", sorted(set(interesting)))
                fl = [f for f in F if "ow" in f or "imit" in f]
                if fl:
                    print("     campos :", sorted(set(fl))[:12])
            allS.update(s for s in S if s.startswith("car."))
        print()
        print("  todas as chaves car.* desta classe:", sorted(allS))
        if hits >= 3:
            break
