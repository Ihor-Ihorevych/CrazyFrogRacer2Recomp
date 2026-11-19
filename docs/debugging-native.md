# Finding the game code worth rewriting natively

Goal: replace routines of the game (EE functions, VU1 programs) with host code that does the same job without emulating the console. Start where
the time and the trouble are. These switches say who asks for the work. They are for debugging runs: use a headless run from a quick save
(`--load-state`, `--frames N`) so that everything runs on one thread and the numbers are exact.

## 1. Who makes the work: `PS2_PROV`

```sh
PS2_PROV=100 PS2_PROV_TOP=16 PS2_RENDERER=hw ps2run --disc game.iso --game-dir games/hp2 --memcard card.ps2 --load-state X.state --frames 26900
```

A table every 100 frames and at the end (`PS2_PROV_FILE=<path>` also appends it to a file). One row per context, sorted by weight:

* **path 1** shapes come from a VU1 program: the row names its entry and key (the file name in `games/<game>/vu1/` and the function in
  `generated/<game>/vu1_*.cpp`, which already is that program as C++).
* **path 2 / 3** shapes come from the EE through VIF1 DIRECT or the GIF DMA channel: the row names the EE code that started the transfer (pc, return
  address, the call chain found on the stack, each as `fn 0xSTART+0xOFFSET`, the function start estimated from its `addiu sp, sp, -N`).
* per frame: shapes seen, drawn, not drawn (and why: DATE, blend form ...), **write-backs** of render targets to graphics memory (and their
  pixels), texture decodes.

HP2, your running-left-and-right scene: the VU1 program at entry 0x0140 makes about 33 000 shapes a frame (the scene's geometry); the program at
entry 0x0198 makes 90 shapes and 29 write-backs and 29 decodes a frame (a render-to-texture routine that costs the card more than the whole scene).

## 2. Who writes the packets: `PS2_WRITERS=1` (with `PS2_PROV`)

Every DMA kick marks the memory its chain reads (the tags and every block). Every guest store (slow, about 3x) into such a page is counted against
the function that made it and the one that called it. The table ends with "writers of the memory the DMA chains read": KiB and stores per frame per
function. In HP2 the display lists are small (a few KiB a frame, from about a dozen functions around 0x1C0000-0x1F8000): the EE does not build the
geometry, the VU1 programs do.

## 3. Reading and replaying one routine

* A VU1 program: `PS2_VU1_KICKLOG`, `PS2_VU1_TRACE`, `PS2_VU1_DUMP`, `PS2_VU1_WATCH`, `PS2_VIF_TRACE` (docs/debugging-vu1.md) give every instruction
  with its inputs, the data memory it was given and who wrote each word. The generated C++ of the program is the starting point of a native version.
* A render-to-texture round trip: `PS2_HWR_LOG=2` lists the target/texture format pairs a game reinterprets, `PS2_HWR_LOG=3` the blend forms and DATE
  shapes that are not drawn.
* An EE function: `PS2_WATCH=<hex address>,<hex length>` prints every store into a range with pc and return address (`PS2_WATCH_REGS=1` adds
  a0-a3, s0-s7, sp); `PS2_TRACE_DMA`, `PS2_TRACE_IPU`, `PS2_TRACE_DRAWS=<frame>` show what a function started.

## 4. How a native replacement is checked

Run the same state before and after with `--frames-dir` and `--frame-at`, and compare the pictures (`scripts/hwdiff.py`) and the end state
(`--save-state`, `scripts/replay.py`). A replacement that changes nothing the game can see is correct.

## 5. What the phone measurements say to rewrite (HP2, Mali-G615, the heavy courtyard scene, 2026-11-19)

Where the time is, from `PS2_PROF=1`, `PS2_GPU_SWEEP=1` (card time with one part of the work left out at a time) and `PS2_PROV`:

| Part | Cost | Verdict |
|---|---|---|
| EE code (the game's own routines) | 18-38 % of one core | **not worth rewriting**: it is not the limit |
| VU1 programs (compiled on the phone since the NEON header fix) | 25-30 % of a core, ~850 runs/s | not the limit; the generated C++ is already native |
| IOP, IPU (movies), SPU2 | 2-9 % | no |
| Card, all work | ~47 ms/frame (sweep, serialised) of which render passes ~16, draws ~10, compute (decodes, palettes, uploads) ~8, the final picture pass ~9 | **the limit** |

So the native work that pays is in the hardware renderer, ordered by expected gain:

1. **The final picture pass** at the phone's 2712x1215: ~9 ms. Done: the game surface now gets a fixed smaller size (the 50/75/100 % buttons of the PS2 tab, `Ps2Activity.applyScale`).
2. **Render-to-texture round trips** (VU1 program entry 0x0198 and the frame/target read back as 8-bit or 16-bit): 30-40 write-backs and 60 texture decodes a frame, each with a pass break. Replace "store the target to memory, decode a texture from it" by one compute step that reinterprets the target's texels straight into the texture (no memory round trip, no pass break where the target is not drawn into again).
3. **DATE** (destination alpha test, ~430 shapes a frame still not drawn, the likely cause of the stretched band at the bottom) and the exact destination-alpha blends (now an approximation): one shader path that reads the destination alpha from a copy of the target. It fixes the picture and the approximation together.
4. **Texture decode batching**: each decode is a compute dispatch and a copy per mip level (~760 dispatches and ~200 copies a frame on the PC). One dispatch for all the textures of a frame, one copy. (Limiting the levels to 2, `PS2_HWR_LEVELS=2`, measured slower on the phone: not that.)
5. **Draw merging** for the scene's geometry (VU1 entry 0x0140 and the other mesh programs, ~25 000 shapes, 215 draws a frame): already merged by state; check the pipeline switches (85/frame) and group switches (130/frame) for what can be sorted away.

The EE and VU1 routines named by `PS2_PROV` (EE 0x1F57B8, 0x1E8F88; VU1 0x0140, 0x0198, 0x16D8) stay as they are until the card side is out of the way.

### Update 2026-11-19 (PAL, 50 % scale, the heavy courtyard scene, phone thermally throttled)

* The game draws every second field (a drawn frame, then an empty one): at PAL it needs 25 drawn frames a second, so "25 of 50" shown by the app's FPS counter is the game's full speed.
* `PS2_GPU_SWEEP=1` (card time per field, mean over drawn and empty fields): all 27-28 ms; without draws in the passes 23.7; without render passes 21.4; without the
  picture in the present pass 19.9 (the present costs ~7 ms a field: the default look 5, "sharp + smooth edges", reads ~40 pixels per screen pixel when the picture is
  under 60 % of the screen's width; `PS2_LOOK=2` (soft) is one filtered read); without compute 23.2. A drawn frame needs ~56 ms of the card against the 40 ms PAL budget.
* Per drawn frame (`PS2_HWR_LOG=1`): 67 render passes, 51 MB attachment loads + 51 MB stores, 44 target write-backs, 17 self copies (15 MB), 2207 DATE shapes dropped.
* `PS2_HWR_NOSTORE=1` (no target -> memory -> texture round trip) was 59 fps against 40 in NTSC: the round trip is the next thing to replace (plan phase 2).
* The smeared band at the bottom was the game's own overscan fill (it draws 448 of its 512 PAL lines and stretches the last line over the rest): `PS2_DISPLAY_ROWS=448` shows 448.
* The phone reaches thermal status 3 (severe: skin 51 C, CPU/GPU 87 C) after a long run of tests: let it cool before comparing fps.

### Sweep with the three transfer parts (2026-11-19, PAL, 50 %, soft look, decode batching, phone cooler)

Card time per field (mean over drawn and empty fields): all 15.0 ms. Left out: passes without draws 10.9, no render passes 9.6, no compute 11.1,
**no target<->memory transfers (stores/loads) 11.8, no texture level copies 11.9, no target self copies 14.1**, no picture in the present 14.2.
So of the ~15 ms: render passes 5.4, compute 4.0 (decodes, palettes, uploads), the stores/loads ~3.2, the buffer->texture level copies ~3.1, self copies ~0.9.
The sweep draws a wrong picture on purpose (artifacts are expected while it runs: `PS2_GPU_SWEEP` must not be left in env.txt).
Next: (1) decode a level straight into the cache texture (storage texture write) so there is no decoded-buffer round trip and no level copy (~3 ms);
(2) the direct target -> texture path for the CT32/CT16 reinterpretations (~3 ms and fewer pass breaks).

### Done: targets read in another format straight from the target (2026-11-19)

`PS2_HWR_LOG=4` lists every texture decoded in a frame. In HP2 nearly all of them were targets written back to memory and decoded again:
~100-200 a frame of a 128x160 CT32 target read as T8H (a palette conversion of its alpha mask, drawn into itself), 4 a frame of the 704x512
CT16S target read as T8 (a full-screen colour grade through a palette: 1024x1024 and 512x1024 textures, 3 M texels), and ~40 small T8/T4 reads
of the frame. They are now read by the draw itself (hwr.wgsl `fetch`):

* **view 3** (T8H of a CT32 target with the same base and width): the target's alpha byte through the palette slot of the draw's setup
  (gpu.cpp keeps 2048 palette slots that stay valid through a submit; the draw shader now binds that buffer). `PS2_HWR_T8H=0` off, `=mark` paints them magenta.
* **view 4** (any other layout: T8/T4/CT16/CT16S/CT32/CT24 of a CT32/CT16/CT16S target, no mipmaps, not a draw into the same target):
  the texel's GS address from gs.wgsl's page tables, the target pixel that holds it from inverse tables made once on the processor
  (`g_addr`), then its bits as the GS keeps them, through the palette (slot + CSA) or TEXA. `PS2_HWR_DIRECT=0` off, `=mark` paints them cyan.
* The draw record's last row is now passed whole to the fragment stage (it carried only its first word).

Checked pixel-identical against the old path on four states (with and without dual-source blending); the mark switches show that view 4
covers a visible full-screen effect. Per drawn frame in the courtyard: target write-backs 220 -> 8, decoded texels 7.6 M -> 0.9 M.
Still on the old path: the mipmapped T8/T4 reads of the frame (8 write-backs a frame).

### Done: one copy for the alpha-only self reads (2026-11-19, the heads scene: the Burrow yard with Ron, Fred and George)

The ~200 T8H draws a frame read the alpha of the 128x160 target they draw into (its colour, alpha masked). A draw that reads its own
target reads a copy made before it, and any write made the copy old: a copy and a render-pass break for each of them (phone sweep: the self
copies 8.8 ms and the passes ~13 ms of a 21.8 ms field). Targets now also keep the rectangles whose alpha was written since the copy
(`dirty_a`); a draw that reads only alpha (view 3) needs a new copy only when those overlap. Heads scene per drawn frame: render passes
226 -> 58, self copies 195 -> 27, pixel-identical (`PS2_HWR_ALPHA_SNAP=0` turns it off). Phone, 50 % scale, PAL: 25-26 fps -> 49-51 fps.
The phone also records the renderer's pipelines now (`PS2_HWR_PIPELINES` in the game folder): made at the start, not during play.

### Investigated and left as they are (2026-11-19 night)

* **Flipendo (square) halves the frame rate**: while a spell is cast the game renders small mip chains (T8/T4, 128x128 down to 32x32, LOD
  bias K = -7.3) into the top-left corner of its 704x512 frame (address 0, `tbw 2`) and textures geometry with them right away, up to 28 times a
  frame. Each is a real read of what was just drawn into the frame: the target is written back (the pages of the corner) and the frame's render
  pass is broken (~34 more breaks a drawn frame; 8 stay after the spell). Tried: view 4 with level 0 only (`PS2_HWR_DIRECT_MIP=1`, ~17000
  pixels of far geometry wrong) and view 4 for a draw into the same target (`PS2_HWR_DIRECT_SELF=1`, a copy of the whole frame per read:
  210 copies, slower). Both off. What would help: give the corner draws a surface of their own (the frame's pass then goes on), or a mip-aware
  view 4 (the mip bases in a second draw record) with a copy of only the pages read.
* **The CT16S effect buffer** (704x512 at 2F20) is shifted up 2 rows with 1408 strips of 64x2 a frame (every other row pair) and needs a copy of
  itself per column (11 a frame): the read rectangle of a bilinear read is widened by a texel and reaches the column before. Exact strips now merge
  in the dirty list (`PS2_HWR_DIRTY_MERGE`), but these strips never touch, so it stays.
* **The 128x160 mask** at 3B80 is drawn in CT32 and then in CT16 for every character: two format classes, so a new target each time (16 a
  frame). Small passes; not worth more now.
* Debug switches added: `PS2_HWR_LOG=4` (every texture decoded, with `[hwr-miss-mip]` its mip word), `PS2_HWR_LOG=5` (where view 3/4 draws
  write), `PS2_HWR_PAGE_LOG=<page>` (who takes a page of graphics memory and when it goes back), `PS2_HWR_DUMP=<hwr frame>` (the schedule; the
  hwr frame number counts from the start of the run, drawn frames are the odd ones after a quick-save load in HP2).

### Done: Flipendo's write-backs (2026-11-19, later that night)

`PS2_HWR_PAGE_LOG=0` (now it also prints every shape that writes the page) showed what the spell really does: its sparkles are 3D
triangles drawn near the top-left of the frame and textured from address 0 (T4/T8, `tbw 2`), the frame's own first page: each batch read what
the batch before drew there. A read of the target a draw draws into, through memory, now writes back a page at most once a frame
(`store_for_memory(..., once_per_frame)`; counted as "own target read as of earlier in the frame"; `PS2_HWR_STALE_SELF=0` off).
Spell frame: write-backs 42 -> 3, render passes 92 -> 53. Pixel-identical in five frames before/during/after the spell and in four other
states (whose passes also drop: the garden 57 -> 43). This replaces the "Investigated and left" Flipendo entry above.

### Done without the phone (2026-11-19, night): copies, vertex order; tried: a state cache

* **Partial self copies** (`self_copy`, `PS2_HWR_PARTIAL_COPY=0` off): a copy of a target that exists already is brought up to date only
  over the rectangle the draw reads, stretched over the rows of the written rectangles it meets (so they can be cut back to what lies beside
  it); the dirty list merges rectangles of the same columns before it falls back to one bounding box (`add_dirty`). The CT16S effect
  buffer's column shift now copies 66x512 per column instead of 704x512. Heads scene per drawn frame: passes 53 -> 43, attachment loads
  39 -> 25 MB, self-copy traffic 16 -> 2 MB; the garden 43 -> 33 passes; the spell frame 92 (start of the night) -> 43. Pixel-identical
  in four states and five spell frames. (A first version copied only the rect read and made 1084 copies a frame: the rows matter.)
* **No vertex reorder copy** (`PS2_HWR_VORDER=1` restores it): the vertices go to the card as recorded, a draw call merges only
  neighbours that are adjacent there. Same draw calls (572), schedule 0.8-2.0 -> 0.15-0.3 ms a frame on the PC, pixel-identical.
* PC card time (`PS2_FRAME_GPU_MS=1`, heads scene with a spell), all of the night's paths off -> on: 9.5-13.9 -> 5.1-8.7 ms a frame.
* Tried and removed: a cache of shape states by setup content (3600 rebuilds a frame, `states built` in the `[hwr]` line). The hits were
  the cheap rebuilds (2.1 ms of build_state stayed) and the lookup cost 3 ms more. The renderer's CPU time (~6 ms a drawn frame on the PC)
  is spread over 68000 shapes' vertices and page bookkeeping; the phone's main thread was ~44 % busy, not the limit.

### Done: masked VIF UNPACK in vector code (2026-11-19, night)

Profiling on Windows without admin rights: a build with CodeView debug info and frame pointers next to the normal one
(`cmake -S . -B build/prof-hp2 -G Ninja -DCMAKE_BUILD_TYPE=Release "-DCMAKE_CXX_FLAGS=-mavx2 -ffp-contract=off -gcodeview
-fno-omit-frame-pointer" "-DCMAKE_EXE_LINKER_FLAGS=-Wl,-pdb=" -DPS2_GENERATED=$PWD/generated/hp2`, `ninja -C build/prof-hp2 ps2run`,
two minutes with the generated objects cached) and hp3-engine's `devtools/sampler` (`SAMPLER_DELAY_MS=6000 sampler.exe
build/prof-hp2/ps2run.exe build/prof-hp2/ps2run.pdb <ps2run arguments>`). `PS2_VU_TIME=excl` now times each VU1 program without the
ones it chains into.

The heads scene's top item was `vif::unpack`'s generic per-lane loop (~10 % of a headless run): Harry Potter 2 sends its vertex data
with masked UNPACKs (the write mask), which always took it. `unpack_masked` (vif.cpp) does the same lane rules four lanes at a time
(per mask row: element with STMOD, row register, column register, or memory left alone), for every format but V4-5 and without filling
(CL >= WL). `PS2_VIF_MASK_CHECK=1` runs both ways on every masked UNPACK and compares memory and row registers: 2.75 million compared in
three scenes, none differ. Now ~2.5 % of the run. `PS2_VIF_MASKED=0` turns it off.

Next by the profile: the renderer's vertex emission (`hwr::shape` + Vtx stores ~8 %), the VU helpers of the compiled programs (vfix,
vclamp_result, clip_value: inlined code of the programs, ~10 % together), GIF parsing (`gs::kick`, `packed_one`, ~5 %).

### Tried: mipmapped textures through view 4 (2026-11-19) - not exact, off by default

Phone profile (`PS2_GPU_PROFILE=1`, new: every render pass, compute pass and copy timed on the card by name; `PS2_PROF=1` now also splits the
drawing thread into `r:gs-cmds`, `r:shape`, `r:build`, `r:schedule`, `r:prepare`, `r:encode`): a Flipendo cast takes the card from ~10 to ~13 ms
a frame, mostly the decode compute pass (3-5 ms, `decode load_clut upload`) and ~100 level copies. The sparkles read mip chains (T8/T4 128x128
down to 16x16, `K = -7.9`) whose level 0 is the frame's corner and whose lower levels lie in memory the game UPLOADS AGAIN between draws.

Built: `sample_v4_mip` / `v4_level` in hwr.wgsl (the LOD and the sampler's choice of level worked out in the shader, each level read through
the target by its own base and width, or straight from graphics memory: binding 3 `vmem`), `add_mip_record` (the levels in the record after
the draw's), per-level page checks in shape(), a page-wise frozen copy for view-4 reads of the target being drawn (`self_copy frame_stale`).
Result on the heads scene with a spell (5 frames): levels inside the target and non-mip self reads are pixel-identical to the old path, but
change nothing (same passes, decodes and copies). The memory-level read (the sparkles' own case: 19223 draws a run) differs by ~5600 pixels:
`PS2_HWR_LOG=1` shows 300-370 uploads a frame into pages such a draw reads, so reading memory when the render pass runs sees the last upload,
not the one before the draw. Exact would need a snapshot of the level pages per draw (a copy op each, as many as the level copies it replaces).
Switches, all opt-in: `PS2_HWR_DIRECT_MIPMAP=1`, `PS2_HWR_DIRECT_SELF=1|nomip|mip`, `PS2_HWR_DIRECT_MEMLEVELS=1` (not exact). The scheduler
already puts the level copies together between render passes; much of the "idle before level" time in the profile is the profiler's own
bracketing passes. Also found: HP2's targets are made up to 576 rows (kMaxRows), and a view-4 draw is set up the old way when its texels leave the
source target's pages (`PS2_HWR_DIRECT_PAGES=0` off, `PS2_HWR_LOG=6` lists them).

### Where the vector unit thread's time goes (2026-11-19, phone, heads scene standing)

New measuring aids: `PS2_VU_TIME=excl PS2_VU_TIME_EVERY=10` prints the dearest programs every 10 s (ms per second of play, calls per second,
microseconds per call; the report at exit never comes on a phone), and `PS2_PROF=1` splits the thread into `v:vif` (VIF packets: UNPACK, MSCAL),
`v:program` (compiled program bodies) and `v:xgkick` (packets handed on). PC, full speed: v:program ~75 % of the thread's work, v:vif ~20 %,
v:xgkick ~8 %.
Phone: ~1.5 million program starts a second at 14.8 fps (timing on), i.e. ~100,000 a frame: the vertex loop (one start per vertex, ~35
instruction pairs, chained with `vu::interpret(1, jpc)`). Nine programs hold 80 % of the time, none more than 15 %; five tiny ones run 140-290 thousand
times a second at 0.1-0.2 us. The time is the body of the loop, not a heavy kernel. On ARM `PS2_VU_DAZ` is 0 (the "denormals are zero" shortcut
is x86 SSE4.1 only), so every result goes through vfix (5 NEON instructions: bic, smin, fcmge, and, bsl; compiled with the NDK) and every program
start loads and cleans 15 or so registers (`vshadow_eager`). A DAZ-like path on ARM would use FPCR.FZ, which flushes results too and so differs
from vfix for products just below the smallest normal number (the reason x86 keeps FTZ off): not bit-exact, so not done. Left for later: skip the
clean-up of registers that the previous program of the chain wrote (already clean), and keep the chain's registers in the next program.

### Why the spell's textures cannot be cheaper without changing the picture (2026-11-19)

* The sparkles' lower mip levels are rewritten by the game for every sparkle: `PS2_HWR_LOG=1` with the memory-level read on counts ~4000 blocks a frame
  that a draw reads from memory and that get other content (not the same bytes again) before the pass runs. Reading memory when the pass runs is
  therefore wrong; only a copy of the pages per draw (or the decode the old path does) is exact.
* `PS2_HWR_LEVELS=n` (decode at most n levels of a texture; a quality trade, not exact), heads scene with a spell, PC card work per frame:
  16 (all): 7.84 ms; 2: 6.85 ms, ~4000 pixels of a 640x512 picture differ; 1: 6.47 ms, ~25,000 pixels differ (far surfaces sharper and shimmering).
* A jump of a vertex program to its own entry goes round inside the function (generator `again:`, vurecomp 18; `PS2_VU_NO_LOOPBACK=1` at
  generation leaves it out): full machine state after 205 frames byte-identical to the old build, ~4 % less program time on the PC, no change
  in the phone's 45 fps standing (the vertex thread is not what holds it below 50). `PS2_VU_CHAIN=1` prints which program hands over to which.
