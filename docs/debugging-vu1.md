# Debugging wrong geometry: VU1, VIF1 and the GIF

When a game draws triangle soup, the cause is almost always upstream of the renderer: check in this order.

1. **Is it the renderer?** Run the same saved state with `PS2_RENDERER=hw` and `PS2_RENDERER=exact` (`--load-state`, `--frame-at`,
   `--frames-dir`). Two different pictures with the same kind of damage mean the vertices are already wrong.
2. **Packets that do not end.** The log head says `[vu1] XGKICK packet without an end` and `[gif] path 1 writes a GS register that
   does not exist`: the GIF read vertex data as a tag. The report names the program (entry, key, interpreted/compiled), TOP/ITOP,
   the last kicks and VIF1's state.
3. **Which interpreter?** `PS2_VU_EXACT=1` runs VU1 programs in the slow bit-exact reference interpreter. Same picture: the two
   interpreters agree. `PS2_VU_INTERPRET=1` forces the interpreter over compiled programs; `PS2_VU_CHECK=1` runs a compiled program and
   the interpreter from the same state and prints `[vu-check]` for the first register or memory word that differs.
4. **The VIF.** `PS2_VIF_DEFER=0` starts a VU1 program at its MSCAL instead of after the UNPACKs behind it.

## Switches added for this (runtime/src/vu_micro.cpp, vif.cpp)

| Switch | What it prints |
|---|---|
| `PS2_VU1_KICKLOG=<n>` | one `[vu1-kick]` line for each of the first n XGKICKs: run number, program entry and key, address, TOP, ITOP, the first GIF tag, the length the kick measured, `CUT (no end)` |
| `PS2_VU1_DUMP=<dir>[,<n>]` | `vu1_runaway_<k>.txt` for the first n (default 3) packets that do not end: all vector and integer registers, Q P I R, flags, TOP/ITOP, the 16 KiB data memory (words and floats) and the 16 KiB micro code (`cXXXX hi lo`), written as the kick happens |
| `PS2_VU1_DUMP_RUN=<n>` (with `PS2_VU1_DUMP`) | the same dump after VU1 program run n |
| `PS2_VU1_TRACE=<run>[,<count>]` | `[vu1-t]` line for every instruction pair of runs run..run+count-1 (interpreted programs): pc, both words, the register the upper instruction wrote (as floats and words), changed integer registers, MAC/status/clip, Q. Run numbers are the ones `[vu1-kick]` prints |
| `PS2_VIF_TRACE=<first>,<count>[,<unit>]` | `[vif1-t]` line for each decoded VIF command (unit 1 by default): UNPACK format, flags, address with TOPS, count, CL/WL, mode, mask, row/col; MSCAL with TOPS/ITOPS/BASE/OFFSET; the first data words |

Typical session (HP2): play to the broken scene, press F5, then headless from the state:

```sh
PS2_VU1_KICKLOG=60 PS2_VU1_DUMP=out,2 ps2run ... --load-state games/hp2/states/quick_....state --frames N+3
PS2_VU1_TRACE=7,1 ps2run ... > trace.log          # run 7 is the first one with a stray kick in the kick log
```

An XGKICK is the lower instruction with `index` 0x6C: `lo & 0x7FF == 0x6FC`, the register is bits 15..11.
Older switches that help: `PS2_TRACE_DRAWS=<frame>[,<count>]`, `PS2_DRAW_RANGE`, `PS2_DRAW_SKIP`, `PS2_TRACE_PIXEL`, `PS2_DUMP_VU1AT`,
`PS2_TRACE_VU1RUN`, `PS2_TRACE_DMA`, `PS2_TRACE_IPU`.
