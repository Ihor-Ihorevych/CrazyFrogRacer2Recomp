# Processor work after the hardware renderer (2026-11-18)

Goal: move what still runs emulated or on a slow fallback to native code, bit-identical to before.

## Checks used for every change

- Race state (`race_state_tmp.state`, frame 3400) to frame 4400, a picture every 25 frames and the whole machine state saved at the end
  (`--save-state 4399:end.state`): 40 pictures and a 46 MB state, byte-identical to the build before the change.
- The recorded inputs `games/cfr2/states/run_2026-11-17_18-57-32_f003400.inputs` replayed from the same state to frame 6700 (real
  driving, 3300 frames): 33 pictures and the end state byte-identical.
- The same race also with a PC build made with `-DPS2_VU_NO_DAZ` (the vector-unit helpers the phone uses: no MXCSR tricks): identical.
- `scripts/test_vu_codegen.py` (random VU programs, compiled vs interpreter, generic/SSE4.1/AVX2): passes. It needed
  `runtime/src/prof.cpp` in its link line (the profiler counters are referenced by vu_micro.cpp).
- Code paths only an ARM build has were tested on the phone itself with one headless test binary each (no game).

## Changes

| What | Where | Phone effect | Check |
|---|---|---|---|
| VIF UNPACK with clang vector types (was: SSE4.1 on PC, scalar loop on ARM) | runtime/src/vif.cpp (`PS2_VIF_VECTOR`, default on with clang; `-DPS2_VIF_VECTOR=0` gives the old paths) | NEON unpack | 288 000 random cases of all 96 format/mode forms vs the scalar loop, PC and phone |
| OPMULA / OPMSUB as one vector multiply / multiply-subtract of rotated lanes (was: three scalar `mul`/`msub` with branches) | recomp/vurecomp_gen.cpp, `vyzxw`/`vzxyw` in vu_ops.hpp | hottest VU1 program on ARM: 9254 -> 7222 instructions, 984 -> 405 branches | race, test_vu_codegen |
| MAC flags without movemask (`vmac`: compares + one OR across lanes) for `FlagPipeV::mac_of` and `flags(v4u)` (was: the scalar flags() on ARM) | runtime/include/ps2/vu_ops.hpp | every MAC read of a VU1 program | 20 M random/special values vs scalar, PC and phone |
| VU FTOI with FCVTZS on ARM (was: vftoi_generic) | vu_ops.hpp | | all 2^32 inputs x 4 fractions vs vftoi_generic, on the phone |
| VU0 macro ITOF on four lanes (`vu::itof4`; was: per lane through a double division) | runtime/include/ps2/vu.hpp, recomp/codegen.cpp; the 44 calls in generated/cfr2*/unit_*.cpp edited to itof4 | | all 2^32 inputs x 4 fractions vs itof, PC and phone |

VU1 code is generated from `games/cfr2/vu1` into `generated/cfr2` (PC) and from `games/cfr2/vu1_all` into `generated/cfr2_ee_all`
(Android build `build/android-gpu`): `vurecomp <vu1 dir> <generated dir> --vu0-dir games/cfr2/vu0`.

PC sampler (race, 1000 frames, samples in compiled VU1 programs): 1708 -> 1573 (x86 DAZ path), 2216 -> 1954 (no-DAZ path, the
phone's kind of code).

## Looked at and not done (numbers from the PC sampler, race)

- **IOP to native code**: the IOP runs ~39 000 instructions per frame (~2 M/s; LIBSD, STREAM, SDRDRV), about 0.2 ms per frame on the
  PC, an estimated ~2 % of one phone core. A recompiler for it would save about 1.5 % of a core; not worth a new translator yet.
- **EE FPU fast paths**: with the helpers made non-inline for measuring, fp::ee::* and fp::key are ~13 % of the emulation thread, most
  of it call overhead (inlined they are a few instructions, with predictable branches). A float-unit version of mul/add would not be
  clearly faster; not done.
- **VU1 code lookup after microcode uploads**: the race uploads no microcode (VIF MPG only 8 times, at boot), so the lookup path
  costs nothing there; a per-line stamp was written, verified and taken out again.
- **memcpy** (~10 % of the VU1 and emulation threads on PC, `rep movsb`): the packet copies into the GS queue between threads
  (`gs::queue::Ring::post`) and DMA copies. Data movement of the threading design, not emulation.
- **ARM flush-to-zero instead of the eager vfix**: FPCR.FZ decides tininess before rounding, vfix after; a product that rounds up to
  the smallest normal number would differ. Not bit-exact, not done.

## Phone

`android-spike/CrazyFrogRacer2-full.apk` (disc inside) built and installed 2026-11-18; the build before is kept in
`C:/Users/Ihor/CFR2Recomp/nat/apk_before/`. No runtime switch is needed for these changes (all bit-identical); the old forms come
back with the old generated files (`nat/gen0*`, `nat/units0`) or `-DPS2_VIF_VECTOR=0`.
