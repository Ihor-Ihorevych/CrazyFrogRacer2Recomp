#!/usr/bin/env python3
"""Hardening seeds for eerecomp: lists every stack-frame prologue (addiu/daddiu sp,sp,-N) in the ELF's
code range that is not an entry point of the translation in <generated dir>. Such functions are
reachable only through function pointers; adding them as entries means an indirect call to them
cannot end in 'missing code'.
usage: prolog_entries.py <elf> <generated dir> <code end hex> > extra_entries.txt"""
import glob, re, struct, sys
elf_path, gen, code_end = sys.argv[1], sys.argv[2], int(sys.argv[3], 16)
data = open(elf_path, 'rb').read()
# single LOAD segment at 0x400000, file offset 0x80 (CFR2); generalize via program header 0
off, vaddr, filesz = struct.unpack_from('<III', data, 0x34 + 4)[0], struct.unpack_from('<I', data, 0x34 + 8)[0], struct.unpack_from('<I', data, 0x34 + 16)[0]
code = data[off:off + filesz]
ent = set()
units = sorted(glob.glob(gen + '/unit_*.cpp'))
for f in units:
    base = vaddr + int(re.search(r'unit_([0-9A-F]+)\.cpp', f).group(1), 16) * 0x4000
    m = re.search(r'entries_[0-9A-F]+\[4096\] = \{(.*?)\};', open(f).read(), re.S)
    for i, x in enumerate(int(v) for v in re.findall(r'\d+', m.group(1))):
        if x: ent.add(base + i * 4)
W = struct.unpack('<%dI' % (len(code) // 4), code[:len(code) // 4 * 4])
for i, w in enumerate(W):
    a = vaddr + i * 4
    if a >= code_end: break
    if (w >> 16) in (0x27bd, 0x67bd) and (w & 0x8000) and a not in ent:
        print('%x' % a)
