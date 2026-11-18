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

## Second round: the parts that were still emulated

Checks as above, plus a power-on run of 3000 frames with no input (intro movies through the IPU: 81 M qwords decoded, title),
pictures every 100 frames and the end state, all byte-identical to the build before.

| What | Where | Check |
|---|---|---|
| EE FPU `div`: the float unit's division when both operands and the quotient are ordinary numbers (rounded once to nearest, which is divide()'s answer); everything else the old way | runtime/include/ps2/fpu.hpp (`fp::ee::div`) | a billion random and edge pairs vs the old code |
| EE `cvt.w.s` / `cvt.s.w` with the float unit's conversions (cut towards zero) | fpu.hpp (`fp::cvt_w_s`, `fp::cvt_s_w`) | all 2^32 inputs each |
| IPU (movies) on ARM: IDCT and colour conversion with clang vector types, the AVX2 versions' arithmetic in the same order (ARM had the plain loops) | runtime/src/ipu/idct.cpp, ipu.cpp (`-DPS2_IPU_PORTABLE` uses them on x86 too) | 3 M IDCT blocks and 2 M macroblocks (RGB32 and RGB16) vs the plain code; the power-on run with `-DPS2_IPU_PORTABLE -DPS2_VU_NO_DAZ` |
| SPU2 voice loop: the routing bits read once per voice instead of once per sample (the compiler reloaded them after every store into the mix) | runtime/src/iop/spu2.cpp | race, replay, power-on |

Speed of the float helpers on the PC (one long dependent chain, random ordinary numbers): div 9.8 -> 4.6 ns, cvt.s.w 0.35 -> 0.10 ns,
cvt.w.s 0.35 -> 0.15 ns. A float-unit `mul` (6.5 vs 4.3 ns) and `sqrt` (4.9 vs 4.4 ns) were slower and `add`/`sub` the same
(9.7-10.2 vs 10.7 ns), so those stay integer code (fast versions in C:/Users/Ihor/CFR2Recomp/nat/fp/fast.hpp, all exact).

**IOP translated ahead of time: tried, measured, removed.** Every block the interpreter forms (6211 blocks recorded over race,
replay and power-on) was translated into a C++ function doing the same operations, stopping before any instruction the block code
would give up; all three runs stayed byte-identical. But the IOP's time did not change (PS2_IOP_LOAD: 0.24-0.27 ms per frame either
way). Counting showed why: the IOP runs ~6.5 k blocks of ~6 instructions per frame and spends ~38 ns per block in the interpreter's
bookkeeping (block lookup, successor cache, entry), which a per-block function keeps; instruction dispatch was never the cost.
(Almost every block entry also re-checks its words, as the epoch goes up after every hardware access, but that is ~36 k words per
frame, a few microseconds.) Only a whole recompiler with direct jumps between blocks could win, at most ~0.2 ms per PC frame.

## Phone

`android-spike/CrazyFrogRacer2-full.apk` (disc inside) built and installed 2026-11-18; the build before is kept in
`C:/Users/Ihor/CFR2Recomp/nat/apk_before/`. No runtime switch is needed for these changes (all bit-identical); the old forms come
back with the old generated files (`nat/gen0*`, `nat/units0`) or `-DPS2_VIF_VECTOR=0`.
