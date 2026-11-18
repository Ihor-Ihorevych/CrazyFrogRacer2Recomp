# Hardware renderer: progress notes

Design: `docs/hw-renderer-design.md` (section 6 lists where the code deviates). Code: `runtime/src/gs/gpu/hwr.cpp`, `hwr.hpp`,
`hwr.wgsl` (drawing program), `hwr_sync.wgsl` (target <-> memory compute programs, appended to gs.wgsl); hooks in `gpu.cpp`
(each behind `hwr::g_on` / `hwr::requested()`), sources and shader embedding in `CMakeLists.txt`.

Switch: `PS2_RENDERER=hw` (default exact). Debug: `PS2_HWR_LOG=1` (one line per frame + summary at exit), `PS2_HWR_CACHE_MB=<n>`,
`PS2_HWR_SKIP_TBP=<hex>` (shapes with that texture base not drawn: bisecting), `PS2_FRAME_GPU_MS=1` (both renderers: card time per frame,
mean every 100 frames), `PS2_VRAM_DUMP=<frame>,<file>` (both renderers: 4 MB memory at the end of that frame; hw writes targets back first).

Test material: `C:\Users\Ihor\CFR2Recomp\hwr\m1` (`run.sh <exe> <outdir> [last] [every]` = race drive 3425-4400 every 25;
`runst.sh <exe> <state> <outdir> <last> <every>`; `cmpall.sh <ref> <test>` framecmp of every frame; `crop.py` zoomed ref|test|diff crops;
`vram32.py` / `adiff.py` / `pngcrop.py` look at CT32 pictures in memory dumps; `cover.py` / `runs.py` read `PS2_TRACE_DRAWS` logs).
Baseline exe of the tree before hwr: `hwr\exact_before.exe`; its frames: `m1\ref` (race), `m1\cup_ref` (cup race 5020-5300 every 20).

## M1 status (2026-11-18): done on PC

* Race drive (40 frames 3425-4400): 40/40 framecmp PASS, PSNR 46.9-50.5 dB, >48 diff <= 0.01 %, bad blocks 0 (`m1\cmp_hw4.txt`).
  Cup race (15 frames 5020-5300): 15/15 PASS, PSNR >= 48.7 dB (`m1\cmp_cup.txt`). Residual differences: isolated edge pixels of thin
  geometry (rasterisation rules), +-1 rounding of As blends.
* Fallback shapes: 0 in race and cup race. Texture cache hit rate 99.7 % (race), 99.8 % (cup); ~1.7 decodes per frame.
* Exact renderer: `PS2_RENDERER` unset and `=exact` frames byte-identical to `exact_before.exe` (race 40/40, cup 15/15).
* Android lib (`build/android-gpu`) builds.

Per race frame (PS2_HWR_LOG summary): ~11 000 shapes, 626 draws, 327 render passes, 569 pipeline switches, 412 bind-group switches,
29 self copies (glow ladder), 20 pipelines in all, processor time in hwr::shape ~1.3 ms. Cup race: 21 000 shapes, 1112 draws,
591 passes, 1014 pipeline switches.

Card time per frame (PS2_FRAME_GPU_MS, RTX 4050 laptop, frames 4000-4400, wall time submit -> done): Direct3D 12: exact 6.3-6.8 ms,
hw 8.2-9.0 ms; Vulkan: exact 5.1-5.9 ms, hw 11-12.6 ms. The hw path is dominated by the ~330 render passes per frame (load/store of whole
512x512 attachments per pass, barriers), which is what M2 removes.

## M2 status (2026-11-18): done on PC (phone not measured yet)

What M2 does (design 3.3; deviations in design section 6, "M2"):

* **Surfaces and renaming.** A target's picture lives in a surface (pooled RGBA8 / Depth32Float textures). The game clears with rows of
  16 untextured 32-pixel strips; a run of such strips that covers every page the target holds is taken back and becomes a *clear*: the
  target gets a new surface whose first render pass loads with `loadOp clear`. A run of opaque textured strips over the whole target (the
  F800 -> F0 display copy) gives a new surface too (no load). F800, Z1400 (twice: main and HUD), the shadow buffers and every car's 0x3FA0
  blur get a new surface per frame, so nothing waits for readers of the old picture.
* **Frame graph.** Every op of the submit (render-pass nodes, decoded-texture copies, self copies, memory loads/stores) gets read/write
  edges from the resources it touches. Loads/stores of graphics memory stay at their place among gpu.cpp's compute commands; everything else
  goes as late as it can (normally after all compute work of the frame = the "prologue"), ordered by a list schedule that keeps drawing into
  one colour surface while it can and, when it must switch, first takes a surface whose remaining nodes can all be drawn now (shadow, blur)
  before one that would break again (the main picture). Consecutive nodes on one surface are one render pass; their draws are re-packed into
  one vertex buffer in pass order and neighbours with equal state become one draw call.
* **Depth.** A node whose depth surface nothing uses afterwards and whose draws do not test depth drops its depth attachment (the glow
  composite then shares the HUD pass; the display copy's dead depth writes cost nothing). `storeOp discard` for a surface no target holds
  after its last use in the submit (the main pass's depth); depth that no draw reads and that is discarded is not loaded.
* **Self copies by region.** A draw that reads its own target reads a copy made before it, and the copy is made again only when something
  was drawn into the rectangle the draw reads since the last copy (texel rectangle from the FST coordinates, filter and wrap mode). The glow
  ladder needs 3 copies instead of 29, exactly as before (pictures bit-identical to M1).
* **Uploads in hw mode (gpu.cpp `emit_pre_hwr`).** The game uploads each texture with its palette and loads the palette right after; with
  page-wide ranges that made ~124 upload + ~42 palette dispatches per race frame, each a tiny dispatch with a barrier (about 2 ms of the
  PC frame). In hw mode the pending work is grouped with the exact blocks each op touches (`hwr::pre_blocks`): non-conflicting uploads go
  before the palette loads queued ahead of them. Now 5.5 upload and 9.8 palette commands per race frame. Palette commands are cut to 16
  loads (one workgroup): with more, gs.wgsl `load_clut`'s chunks in other workgroups gave different palettes in two runs of the same build
  (a race in that path; the exact renderer's runs of consecutive palette loads are short, but other games might reach it).
* **Positions.** hwr asks gpu.cpp for a stream position only for memory loads/stores (asking ends gpu.cpp's open run of uploads).
* **Pipelines.** The 20 pipelines of the race and the cup race are built in (`kBuiltinKeys`) and made at start (171 ms on the PC); new keys
  are appended to `PS2_HWR_PIPELINES=<file>` or `games/<name>/hw/hwr_pipelines.txt` (beside PS2_VU_RECORD) and made at the next start.
  Without dual-source blending the dual keys are converted to the source-alpha form.

New switches: `PS2_HWR_ORDER=stream` (M1's order, no renaming: for comparing), `PS2_HWR_DUMP=<frame>` (that frame's clear candidates and
schedule), `PS2_HWR_TIME=1` (card time of each render pass by timestamp queries, mean per 100 frames), `PS2_HWR_NOPASS=1|draws` (measuring
aid: no render passes / passes without draws), `PS2_HWR_PIPELINES=<file>`.

Checks (exe `hwr\m2\ps2run_m2.exe`; frames and logs in `C:\Users\Ihor\CFR2Recomp\hwr\m2`):

* Race drive 40 frames 3425-4400: 40/40 PASS vs exact (`m2\cmp_hw7.txt`, PSNR >= 46.9 dB), all 40 bit-identical to M1's frames.
  Cup race 15 frames 5020-5300: 15/15 PASS (`m2\cmp_cup7.txt`, PSNR >= 48.7 dB), bit-identical to M1. Fallback shapes 0. Texture cache hit
  rate 99.7 % / 99.8 %. `PS2_HWR_ORDER=stream` gives M1's frames too.
* Exact renderer (PS2_RENDERER unset): race 40/40 and cup 15/15 frames byte-identical to `hwr\exact_before.exe`'s.
* `build/native-check` and `build/android-gpu` build.

Per frame, steady race driving (frame 3700) / mean of the 1000-frame race run / cup race steady (mean of 300 frames):

| | M1 race | M2 race steady | M2 race mean | M2 cup steady (mean) |
|---|---|---|---|---|
| render passes | 327 | 9 | 10.4 | 17 (19.4) |
| draw calls | 626 | 240-300 | 316 | (569) |
| pipeline switches | 569 | 190-250 | 255 | (456) |
| self copies | 29 | 3 (1.1 MB) | 3 | 3 |
| attachment loads / stores | ~330 / ~330 whole targets | 5 (3.0 MB) / 10 (6.25 MB) | 5.8 / 10.8 | (8.5 / 22: 6.2 / 10.5 MB) |
| upload + palette commands | 124 + 42 | 5.5 + 9.8 | | (11.5 + 14.6) |
| hwr processor time | 1.3 ms | 1.27 ms (schedule 0.2 ms) | | 2.3 ms (0.4) |

Race frame schedule (all after the frame's compute work): F0 display copy (no load, 1 MB store) | shadow 64x512 (clear, 128 KB) | blur
0x3FA0 (clear, 128 KB) | F800 + Z: clears, all 3D, shadow, blended 3D (clear both, store colour 1 MB, depth discarded) | glow 256x512 x4
(load + store 512 KB each, 3 copies between) | F800: glow composite + HUD with its new depth (load colour, clear depth, store 1 MB + 1 MB).
The cup race adds 3 shadow buffers and 3 blur versions (8 small passes instead of 2); the main picture is one pass.

Card time per frame (PS2_FRAME_GPU_MS=1, RTX 4050 laptop, frames 4000-4400, means of 100 frames; logs `m2\time2_*.log`):

| | exact | M1 hw | M2 hw |
|---|---|---|---|
| Vulkan | 5.8-6.9 ms | 11-12.6 ms | **4.3-4.8 ms** |
| Direct3D 12 | 6.3-7.1 ms | 8.2-9.0 ms | **5.0-5.3 ms** |

PS2_HWR_TIME=1 (Vulkan): all render passes 1.8 ms per frame (main pass 0.6-0.7, glow composite + HUD 0.45-0.5, display copy 0.23, glow
0.3, shadow and blur 0.07). The rest of the hw frame is the shared compute work (uploads, palette loads, ~7 decodes).

Tile-based GPU (Mali): each render pass loads its attachments unless cleared and stores them unless discarded. Race frame: 9 passes,
3.0 MB loaded + 6.25 MB stored + 2.25 MB of self copies + 1.2 MB vertices + 0.5 MB display write-back ~ 13 MB per frame (~0.65 GB/s at
50 fps); M1's ~327 passes loaded and stored whole 512x512 targets each time (several hundred MB per frame).

## Known gaps / next steps

1. M2 left: shadow atlas (cup race: 8 small 64x512 passes; an atlas needs texel offsets in the shader's target reads, or copies back, which
   cost more bytes than they save), sorting draws by pipeline inside a pass (not done: the main pass mixes GEQUAL depth, blending and
   two-draw alpha tests, and pipeline switches cost little next to passes), depth discard at the end of the frame (the HUD depth, 1 MB, is
   stored because the next frame is not known yet), present straight from the target (kept: the write-back is one 512x256 dispatch and the
   display kernel's field/deflicker path stays exact).
2. First phone run with M2: `android-spike/CrazyFrogRacer2.apk` is built (2026-11-18) with PS2_RENDERER=hw as the app default (SpikeActivity's
   Os.setenv block; `PS2_RENDERER=exact` in files/env.txt switches back) but not installed (no phone on adb then):
   `MSYS_NO_PATHCONV=1 adb install -r C:/Users/Ihor/CFR2Recomp/android-spike/CrazyFrogRacer2.apk`. hw runs at PS2_SCALE 1 only (the app's scale
   setting above 1 falls back to exact). Useful there: PS2_HWR_LOG=1, PS2_HWR_TIME=1, PS2_SPEED_LOG=1, PS2_PROF=1.
3. Fallback to the painter for unsupported states is not implemented: such shapes are counted ("FALLBACK <reason>" in the log) and skipped.
   Needs: store involved targets, let gpu::draw's batch path draw, reload (colour: hwr_load; depth: a load draw).
4. Depth targets are not loaded from memory when created (start at 0). Save state in hw mode: `sync_all` writes colour and depth back
   (untested); state load drops all targets. Untested: save/load while in hw mode, menus, minigames, FMV, PS2_SCALE>1 (hw is refused
   at scale > 1, exact draws).
5. Dual-source blending is required for As blends whose alpha is written (no two-draw fallback yet, reason "no dual-source"); M3.
6. A texture inside a target at another base or width (reason "texture in a target (other layout)") is not supported; not seen in races.
7. Region clamp + mipmaps samples level 0; As > 0x80 saturates at 1.0; colour wrap (COLCLAMP=0) ignored. All counted as approximations.
8. Processor cost: per-shape work (triangle expansion, page ownership, SAT test for two-draw runs) ~1.3 ms per race frame on PC.

## Scale > 1 (2026-11-18)

hw now runs at PS2_SCALE 2-4 (before, scale > 1 fell back to the exact renderer: ~20x slower on the phone). Surfaces are made at
scale x size; hwr's bookkeeping (targets, rectangles, clears, self-copy regions) stays in GS pixels and only the card sizes are scaled
(scissor, self copies, sync dispatches). hwr_sync.wgsl runs one thread per sample (graphics memory keeps SCALE^2 samples per word,
k = sub-row * SCALE + sub-column; the sync module gets the SCALE override). A target read as a texture (TEX=2) reads its own samples:
draw word c.w = scale for target sources, `wrap_scaled` in hwr.wgsl wraps the GS texel and keeps the sample inside it.
Checks: 1x race 40/40 PASS vs exact (Vulkan). 2x race 13 frames vs exact 2x: PSNR 28.6-29.4 dB, >48 diff ~1.2 % (FAIL by framecmp's
thresholds): the same picture, differences soft along edges (the exact renderer's tent-filtered copies and sprite snapping at scale are
not reproduced; moving the card samples to x + j/scale made it worse). Card time 2x (Vulkan, RTX 4050): hw 6.3-7.1 ms, exact 6.0-6.4 ms;
the gain is on tile GPUs (memory traffic ~4x of 1x: ~50 MB per frame instead of hundreds of MB per pass set).

## Processor work, threaded run like the phone (2026-11-18)

New tool: `PS2_PROF_PC=<file>` with PS2_PROF=1 (Windows): prof.cpp samples each profiled thread's instruction pointer every 1 ms
(SuspendThread / GetThreadContext, only outside wait labels) and the word at its stack pointer (the caller of a leaf such as memcpy), and
writes "thread module offset count" / "thread stack offset count" every 5 s. Symbolize with `llvm-nm --numeric-sort` of the exe (image base
0x140000000) and the export table of system DLLs (`llvm-objdump -p`); helper `hwr\s2\sym.py`. Use PS2_THREADS=1 so that the headless run
has the phone's threads (emulation, vu1, drawing).

Found and fixed (race, 2x, hw, Vulkan):
* gif.cpp `release`: the PATH3 data held under MSKPATH3 was erased from the front after every released packet (the game lets streamed
  textures out one packet at a time): memmove of the whole remaining buffer each time, ~57 % of the vu1 thread's working samples. Now a
  read offset (`Held::head`), compacted when the released part is the larger one. vu1 thread busy 67-75 % -> ~41 %. Frames bit-identical.
* hwr `shape`: two steady_clock reads per shape for PS2_HWR_LOG's cpu time, also when the log is off (~8 % of the drawing thread). Now only
  with the log.
* hwr `texture_key`: every lookup hashed all blocks of the texture (and mip levels). Now memoized per setup words + palette id, valid while
  no page the texture covers changed (page generations bumped wherever a block identity or stale flag changes; whole-memory changes bump
  g_mem_all). resolve_texture 14.6 % -> 4.9 % of the drawing thread; hwr processor time per race frame 1.27 -> 0.96 ms. Race 1x frames
  bit-identical; cup 15/15 PASS.
Remaining drawing-thread costs: hwr::shape ~26 % (vertex building), gs write_packed_run ~14 %, uploads (hash + staging copy) ~16 %.
Emulation thread: EE code + IOP. vu1: compiled VU1 programs ~70 % of its work.

60 Hz: the disc is PAL (50 Hz; 50 fps is full speed). `--ntsc` runs the machine at 60 Hz, but the game keeps its 512-line PAL picture
and the NTSC display shows ~425 lines (the bottom HUD is cut), and whether the game's logic is per frame (then 20 % too fast) is not
settled. Not offered.
