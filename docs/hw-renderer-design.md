# Hardware renderer for Crazy Frog Racer 2: analysis and design

Status: M1 and M2 implemented on PC (2026-11-18, `runtime/src/gs/gpu/hwr.cpp`; progress, measurements and what is left in `docs/hw-renderer-progress.md`; deviations from this design in section 6). Scope: one game, Crazy Frog Racer 2 (EU), with full speed (50 fps PAL) in races on a
Mali-G615 phone (Dimensity 8300, wgpu on Vulkan) as the goal. The exact compute renderer ("the painter", gpu.cpp + gs.wgsl) stays and is
the reference. The new renderer does not have to match it bit for bit, but the game must look right.

All numbers below were measured on the PC build (`build/native-check`, copied to `C:\Users\Ihor\CFR2Recomp\hwr`) with the race save state
(`race_state_tmp.state`, frame 3400, `--press 3420:cross:700` plus left/right pulses) and the coverage states in `eecov/st` (main menu,
cup race `cup0`, minigames `pinball` and `dance`). Scripts and raw logs are in `C:\Users\Ihor\CFR2Recomp\hwr` (`scripts/tr.py` turns
`PS2_TRACE_DRAWS` into one line per run, `scripts/tgt.py` counts target switches, `scripts/seq.py` lists uploads and TEX0 writes from
`PS2_TRACE_GSSEQ`, `scripts/blk.py` compares VRAM dumps, `scripts/framecmp.py` is the picture metric below).

---

## 1. What the game draws

### 1.1 Per-frame numbers (`PS2_GPU_STAT=1`; race = 500 frames 3400-3900 while driving)

| | race (state 3400) | cup race (`cup0`) | pinball | dance | main menu | title |
|---|---|---|---|---|---|---|
| shapes / frame (mean, p95) | 9 908, 12 628 | 17 785, 20 640 | 5 635 | 4 321 | 506 | 176 |
| covered pixels / frame (before Z) | 2.27 M | 2.42 M | 1.30 M | 1.06 M | 1.74 M | ~0.8 M |
| draws (pipeline + texture changes) | 398 | ~600 | ~350 | ~300 | ~10 | ~6 |
| target switches in one traced frame | 295 | 394 | 342 | 292 | 3 | - |
| host uploads / frame (bytes) | 243 (1.94 MB) | 328 | 2 | 2 | 2 | 0 (FMV: 768, 786 KB) |
| palette (CLUT) loads / frame | 122 | 170 | 35 | 12 | 11 | ~1 |
| distinct textures / frame | 55 (3.5 MB) | 77 (3.8 MB) | 21 | 12 | 10 | 2 |
| downloads to the EE (TRXDIR 1) | 0 | 0 | 0 | 0 | 0 | 0 |
| local copies (TRXDIR 2) | 0 | 0 | 0 | 0 | 0 | 0 |

Primitives (race): triangle strips 97.7 % of shapes but only 30 % of pixels; sprites 1.6 % of shapes and 61 % of pixels (clears, copies,
glow, HUD panels); fans 0.7 %. No points or lines. Average strip run is tiny (16-25 triangles, boxes of 5-30 pixels).

Exact renderer cost for comparison: PC replay of race frame 330 = 2.53 ms per frame (raster 1.98, upload+CLUT 0.39, decode 0.14,
snapshot 0.13). On the phone the same work is ~40-50 ms (`PS2_GPU_SKIP=raster` 14 -> 47 fps).

### 1.2 Feature use (race; all other scenes use a subset)

* **Frame buffers**: CT32 (83 % of shapes, 97 % of pixels) and CT16 (17 % of shapes, 3 % of pixels). No CT24 target, no Z-format target.
* **Frame masks**: only whole channels. `0x00000000` and `0xFF000000` (alpha kept: the opaque 3D pass keeps the alpha it was cleared to).
  No partial masks.
* **Depth**: Z16S at zbp 0x1400 (82 % of shapes; GEQUAL + write for 3D, ALWAYS + write for clears). The other "depth" use is Z32 at zbp 0
  with ALWAYS and no write = depth off. No Z24/Z32 depth test at all.
* **Blending** (shapes / pixels): off 91.6 % / 62.8 %; `(Cs-Cd)*As+Cd` 6.7 % / 13.6 %; `(Cs-0)*FIX+Cd` (additive) 0.3 % / 15 %;
  `(Cs-Cd)*FIX+Cd` 5.8 % px; `(Cd-Cs)*FIX+0`, `(0-Cs)*FIX+Cd` (subtractive shadow), `(Cs-0)*FIX+0`, `(Cs-0)*As+Cd` small.
  **Never** a destination-alpha factor (C = Ad), colour clamp always on (no wrap-around), PABE never.
* **Alpha test**: off 91 %; `GREATER 0x7F, fail RGB_ONLY` 6.7 % of shapes (transparent 3D and HUD); `NEVER` with FB_ONLY / ZB_ONLY /
  RGB_ONLY (used as colour-only, depth-only and RGB-only clears); `NOTEQUAL 0, KEEP` (glow extraction, reads destination alpha of the
  rendered picture as a mask).
* **DATE (destination alpha test)**: never. **FBA**: never. **Dithering**: off. **Scan mask**: off. **Fog**: on for most 3D (FGE).
* **Textures**: T8 (8-bit palette, CT32 CLUT, CSM1) 92 % of textured shapes, CT32 8 %, CT24 1 shape (glow composite, full screen),
  CT16 (shadow textures, TEXA `ta0 80 aem 1`). No 4-bit textures in the measured scenes. All filtering bilinear; half of the textured runs
  are **trilinear mipmapped** (`TEX1 lcm 0 mxl 3 mmin 5 K=-11.5`, MTBA levels uploaded with the base), LOD per pixel from Q.
* **Clamp**: REPEAT for models, CLAMP for copies, REGION_CLAMP for the glow ladder.

### 1.3 Memory layout and the frame, step by step (race frame 3700; identical structure in every 3D scene)

```
0x0000  F0     512x256 CT32   display buffer (DISPFB1 fbp 0, fbw 8, DBY 1; SMODE2 INT=1 FFMD=1; DISPLAY1 DW 2560 DH 510; PMODE 0xFFA1: circuit 1 only)
0x0800  F800   512x384 CT32   main colour target ("fbp 2048")
0x1400  Z1400  512x384 Z16S   main depth
0x1A00..0x3A00                texture ring: each frame the model textures are re-uploaded at moving addresses
0x3A00  64x64 CT16 fbw 1      shadow buffers (0x3A00, 0x3A20 ... one per car in cup races; "fbp 14848"), 0x3FA0 = blurred shadow
0x3A00  256x384 CT32 fbw 4    glow buffer, aliasing the shadow pages later in the frame
```

1. Copy F800 (last frame) to F0 with 16 vertical sprite strips: 512x384 -> 512x256 vertical squeeze, then a second 50 % blended pass with
   a 0.625-line offset (deflicker). The display shows F0. (These sprites also write Z=0 into Z1400 rows 0-255, dead because of step 2.)
2. Clear F800 (sprite, `NEVER/FB_ONLY`), clear 64x64 CT16 shadow buffer, clear Z1400 (sprite, `NEVER/ZB_ONLY`).
3. Opaque 3D into F800/Z1400 (alpha masked), **interleaved run by run with the same mesh drawn flat into the 64x64 CT16 shadow buffer**
   (145 runs, ~2 500 shapes, 2 targets alternate ~290 times per frame).
4. More opaque 3D (alpha written: the glow mask), then alpha-blended 3D (`GREATER 0x7F / RGB_ONLY`).
5. Shadow: 4 additive sprites blur 0x3A00 into 0x3FA0 (CT16), then a subtractive fan `(0-Cs)*FIX+Cd` samples 0x3FA0 onto the ground in
   F800. In cup races this repeats per car, always through the same 0x3FA0.
6. Glow: F800 is sampled (`NOTEQUAL 0` on its alpha) into the 256x192 glow buffer at 0x3A00 (CT32, now aliasing the shadow pages),
   darkened, blurred down a ladder of smaller rectangles of the **same** buffer (reads one rectangle, writes another: same texture in the
   same pass), and added back to F800 as CT24 (`(Cs-0)*FIX+Cd`, full screen).
7. Depth clear again, HUD (alpha-blended strips with GEQUAL depth).

So: **4 colour targets** (F0, F800, CT16 shadow 64x64 per car, CT32 glow), **1 real depth buffer**, no readback, no local copy, no format
tricks beyond CT32-as-CT24 and the CT16/CT32 aliasing of 0x3A00. Menus are the same pipeline with ~500 shapes; minigames are races
without the cup HUD.

### 1.4 Textures: streamed every frame, pre-swizzled, repeating by content

* Each model texture is uploaded right before use as a chain: base level, 3-4 mip levels, then its 16x16 CT32 CLUT just below the base
  (`UP 0x2036 64x64 CT32`, `UP 0x2136 ...`, `UP 0x2032 16x16 CT32`, `TEX0 tbp 2036 tbw 4 psm T8 cbp 2032 cld 1`).
* **T8 data is uploaded as CT32** (half width), i.e. pre-swizzled: the bytes only become texels through GS memory addressing. A
  "use the upload bytes as a linear texture" shortcut does not work; a decode through the GS swizzle (gs.wgsl `decode`) is needed.
* Address-keyed caching fails: only 2.9 % of texture bytes are unchanged at the same address since the last use (hwstat), and the
  existing decode cache (keyed with the address) finds 15-25 %.
* **Content repeats**: of the 8 192 blocks of the ring (0x1A00-0x3A00) at frame 3701, 100 % have identical 256-byte content somewhere in
  the ring of frame 3700 (16 % at the same address); after 10 frames 99.3 %, after 60 frames (new part of the track) 86 %.
  A content-keyed texture cache will hit nearly always.
* 71 % of all texel bytes read per frame come from pictures the game drew itself (F800 copy, glow chain, shadows).

### 1.5 What the existing card path (`PS2_GPU_HW=1`) does with this game

It draws **nothing**: with the wide routing on the race, `card 0 (0 shapes), painter 101738 batches, painter keeps: depth format
(268481 setups)`. `classify()` in hwdraw.cpp only takes Z24 (`s[6] != 0x31` -> "depth format") and every 3D shape here uses Z16S. That,
not the Mali, is why it gave no gain on the phone. Even with Z16S allowed it would be slow here: it keeps graphics memory as the truth,
writes pictures back and reloads them around every painter batch, interleaves compute and render passes, and would follow the 290 target
switches per frame one by one.

Reusable from it: the vertex layout and GS-to-clip mapping, the fragment code for texture function / fog / alpha test, the blend mapping
table (`blend_of`), dual-source blending setup, the pipeline key recording and background pipeline builder, the load/store sync shaders
(`hwdraw_sync.wgsl`, for save states and the rare fallback), and the memory accounting wrappers. From gpu.cpp: upload/CLUT queueing,
`decode` in gs.wgsl, `bytes_id` content hashing, page/block identity bookkeeping, the display/present plumbing.

---

## 2. Hard cases for this game, and how PCSX2's hardware renderer handles each

1. **Target ping-pong (F800 <-> 64x64 CT16 shadow buffer, ~290 switches per frame; one shared blur buffer per car).**
   PCSX2 renders in stream order and simply switches render targets; on desktop GPUs that is cheap. On a tile-based GPU each switch is a
   render pass end + start with load/store of the whole 512x384 tile set, which would cost more than the drawing. Here: a **frame graph
   with reordering and renaming** (section 3.3). The shadow draws do not depend on F800 and F800 does not read the shadow buffer until
   step 5, so all shadow work can be drawn before F800's pass; 0x3FA0 is fully overwritten per car, so each version gets its own texture
   (an atlas tile), and the main pass is never broken.
2. **Streaming textures at moving addresses, pre-swizzled T8 in CT32 uploads, mip chains, one CLUT per texture.**
   PCSX2: source cache keyed by TEX0/TEXA/CLUT, invalidated per page on upload; with "texture preloading = full (hash cache)" it hashes
   texture contents so re-uploads at new addresses hit. Palette textures are either expanded with the CLUT (GPU palette conversion off) or
   kept as R8 index textures with a palette texture and filtered in the shader. Here: **content-keyed cache of RGBA8 textures with full mip
   chains**, key independent of the address (section 3.4), decoded through gs.wgsl `decode` on a miss only; CLUT applied at decode so the
   card's own bilinear/trilinear filtering is correct.
3. **Render-to-texture, including sampling the target being drawn (glow ladder), CT32 sampled as CT24, CT16 targets sampled with TEXA,
   CT16/CT32 aliasing of the same pages.**
   PCSX2: targets are looked up before memory when a texture's block range overlaps a target; format reinterpretation through convert
   shaders; "texture is frame buffer" handled with a copy of the region or framebuffer fetch / texture barrier; overlapping targets of another
   format are invalidated on draw. Here: target lookup by block range, CT24/CT16 rules in the fragment shader, a small region copy
   (`copyTextureToTexture`) before a draw that samples its own target, and page-ownership invalidation (a target that loses pages to a
   newer target or an upload is dropped or trimmed).
4. **Destination alpha as a mask + blend factor As/128 + alpha test that drops only depth.**
   The game keeps F800's alpha 0 for opaque scenery (FBMSK 0xFF000000) and writes alpha only for glowing objects; the glow pass samples F800
   with `NOTEQUAL 0`. Blends use As with 0x80 = 1.0 while the card's factor is a/255, and the alpha written must stay the raw As.
   `GREATER 0x7F / RGB_ONLY` means "colour always, depth and alpha only where alpha > 0x7F".
   PCSX2: stores raw alpha in RGBA8, uses dual-source blending (second output = As*255/128 as the factor) or software blending; splits
   alpha-test-with-partial-fail into two draws. Here: the same. Dual source where the adapter has it (wgpu feature `DUAL_SOURCE_BLENDING`,
   already used by hwdraw on PC; to be checked on the Mali), otherwise two draws (colour with factor alpha, then alpha only).
5. **Per-pixel mip LOD from Q (`lcm 0`, K negative) and GS sampling conventions.**
   PCSX2 "full" mipmapping computes LOD from Q in the shader (`log2(1/q) << L + K`) and samples explicit levels; "basic" lets the GPU
   pick. Here: interpolate S, T, Q linearly in screen space (clip w = 1, as hwdraw already does) and use `textureSampleLevel` with the GS
   formula; also the half-pixel rules for sprites/copies (the F800->F0 copy and the glow ladder are sprite copies where a half-texel error
   blurs or shifts the picture).

Not needed for this game (measured never used): DATE, blending with Ad, colour wrap-around, partial frame masks, channel shuffles, 4-bit
textures in scenes measured, local copies, downloads, framebuffer fetch.

---

## 3. Design

### 3.1 Where it hooks in

* New module `runtime/src/gs/gpu/hwr.cpp` (+ `hwr.wgsl`, `hwr.hpp`), namespace `ps2::gs::gpu::hwr`. Switch `PS2_RENDERER=exact|hw`
  (read once at `gpu::init`; default `exact` until M5, then `hw` on Android for this game). With `exact` no hwr code runs and no hwr
  shader is compiled.
* gpu.cpp keeps the device, queue, surface, VRAM buffer, upload/CLUT queueing and decode. In `hw` mode `gpu::draw()` hands each shape
  (State + vertices) to `hwr::shape()` instead of the tile batcher; `image_*`, `load_clut`, `texflush` go to both (gpu.cpp records the
  compute work, hwr records the event in its frame log); `display/present` ask hwr for the display target first.
* Frame unit: everything between two presents (the game draws exactly one picture per vblank). hwr records the frame, builds the graph,
  and encodes one command buffer at present time:
  `[prologue compute pass: uploads, CLUT loads, decodes of cache misses, in stream order] [render passes] [present blit]`.
  This is correct because render passes never read VRAM directly: they read cached textures (decoded in the prologue at their point of the
  stream) or targets.
* Rare unsupported state (none measured): the frame is cut at that point, involved targets are written back to VRAM (`hw_store` /
  `hw_store_z` of hwdraw_sync.wgsl), the painter draws the batch, targets touching its write range are reloaded (`hw_load`) or dropped.
  Counter `fallback shapes/frame` printed with `PS2_HWR_LOG=1`; target 0 in all scenes.
* Save state / `download()` / `read_state()`: write all dirty targets back to VRAM first (same store shaders; at scale > 1 a 1:1
  down-sample, top-left sample), then the existing path. `write_state()` / state load: drop all targets and the texture cache.

### 3.2 Data structures

```
struct Target {                      // a picture the card holds
  u32 bp, bw, psm;                   // GS identity (FRAME fbp*32 or ZBUF zbp*32), psm CT32/CT24/CT16 or Z16S/Z32
  WGPUTexture tex; u32 w, h, scale;  // RGBA8Unorm for all colour formats, Depth32Float for depth; size = max seen (rounded to 64)
  PageSet pages;                     // 8 KB pages it owns (format-aware page size); newest writer wins
  Rect valid;                        // rows/cols that hold real content
  u32 version;                       // bumped by renaming (a full overwrite while an older version is still to be read)
};
struct TexEntry {                    // a decoded texture
  u64 key;                           // address-free content key (3.4)
  WGPUTexture tex; WGPUBindGroup group; u32 levels; u64 last_frame; u32 bytes;
};
struct Event { kind: Draw|Upload|ClutLoad|TexFlush|Display; ... }      // the frame log, in stream order
struct Draw { target, depth, pipeline key, tex source (cache entry | target+rect | none), param index, first vertex, count, scissor, blend const };
```

* Colour targets are RGBA8Unorm holding the GS bytes directly (alpha raw, 0x80 = 128/255). CT16 targets are RGBA8 too; the shader
  quantises to 5 bits per channel and writes alpha 255 or 0 (the A bit) so that sampling it back as CT16 with TEXA works. CT24 = CT32 with
  alpha ignored on sampling (TEXA rules).
* Depth: Depth32Float, value z/65536 for Z16S (exact), store op `discard` when the graph shows the next use is a full clear (true in every
  frame measured: the HUD clear and next frame's clear), so on the Mali it never leaves the tile memory.
* A colour target and its depth must be the same size in WebGPU. Rule: targets with fbw >= 4 are allocated `fbw*64` wide and 512 high
  (x scale); F0 (512x256) and F800 (512x384) both pair with Z1400 at 512x512. Small targets (64x64 shadows) have no depth.
* Texture cache: map key -> TexEntry, LRU by bytes, budget 64 MB on the phone (race working set ~1500 textures over 500 frames, 48 MB at
  declared size; RGBA8 expansion of T8 x4 means mips + RGBA ~ 1.33 x 4 x the T8 size; real working set per lap is smaller).
* Vertex and parameter buffers: one persistent vertex buffer, one index buffer (u16/u32), one storage buffer of per-draw parameters, each
  written with **one** `wgpuQueueWriteBuffer` per frame and grown geometrically (the cause of hwdraw's out-of-memory was many small
  staging writes per frame).

### 3.3 The frame graph (reorder, rename, merge)

At present time hwr has the whole frame's events. It builds nodes per (target version, contiguous run of draws), with edges:

* read-after-write: a draw samples pages of a target (or a texture decoded from pages a target wrote) -> after the writer's draws;
* write-after-read: a draw overwrites pages another node still has to read -> either rename (if the overwrite is a full clear of the
  target rect, allocate a new version: e.g. each car's 0x3FA0 blur) or order after the reader;
* write-after-write on overlapping pages (aliasing 0x3A00 CT16 shadow vs CT32 glow): order, and the later target takes the pages.

Then: topological order that keeps each target's draws in their original relative order, prefers to keep the largest target (F800) in one
pass, and batches the small ones. Result for the race frame:

| pass | target | content |
|---|---|---|
| 1 | shadow atlas (all 64x64 CT16 versions as tiles of one 512x128 texture) | clears + flat meshes of all cars |
| 2 | blur atlas | the 4-tap blurs into each car's 0x3FA0 version |
| 3 | F0 | copy of last frame's F800 (2 draws) |
| 4 | F800 + Z1400 | clear (load op), all 3D, shadows applied (sampling pass 2 tiles), blended 3D |
| 5-8 | glow buffer (+ small region copies before self-sampling draws) | extract, darken, blur ladder |
| 9 | F800 (load colour, clear depth) | glow composite, HUD |
| - | present | blit F0 to the surface |

About 9-10 render passes instead of ~300. Consecutive draws with identical state merge: the 140 F800 runs between shadow runs collapse to
a handful of draws (`draws A` 319 per frame today; expected < 150 after merging).

Clear detection: a sprite covering the whole valid rect with `NEVER/FB_ONLY` (colour) or `NEVER/ZB_ONLY` (depth), untextured, constant
colour, as the first draw of a pass, becomes the pass's `loadOp = clear` (no load of the old tile contents).

### 3.4 Texture key without the address

gpu.cpp already hashes every upload (`bytes_id`) and keeps per-block identities, but mixes in the absolute address (`rec[0]`, block
number). hwr keeps its own table `block -> (upload id, block offset inside the upload)` where `upload id = hash(bytes, dpsm, dbw, rrw, rrh)`
without dbp. A texture key is

`mix( for each block of every mip level in order: (upload id, offset), tpsm, tw, th, mxl, mtba levels' (tbw), cpsm, csm, csa,
       palette content id (same scheme for the CLUT blocks), TEXA+AEM if tpsm is CT16/CT24 )`.

If any block of the texture is owned by a target, it is a render-to-texture case: the source is that target (plus rect and format view).
If any block was written by a draw that was not followed (painter fallback) or by an upload not covering whole blocks, the key falls back
to "address + memory version" (always decodes; correct, just slower).

On a miss: decode each level with gs.wgsl `decode` (palette applied, RGBA out) into the decoded buffer inside the prologue compute pass,
then `copyBufferToTexture` into the new texture's levels. The phone cost of a miss is a few dispatches; at the measured ~95-99 % content
hit rate that is a few textures per frame. TEXA/AEM-dependent formats (CT16/CT24) are expanded at sampling time from the stored RGBA8 so
the TEXA register is not part of their key.

### 3.5 Shaders and pipelines (one WGSL file, specialised by overrides)

Vertex: GS x,y (12.4 fixed minus XYOFFSET, times scale) to clip space with the GS pixel-centre rule (+0.5 as in hwdraw.wgsl, plus the
half-texel fix for sprites with linear filtering), z as z/65536, w = 1 so S,T,Q, colour and fog interpolate linearly in screen space like
the GS. Strips/fans/sprites are expanded to triangle lists on the CPU (sprite = 2 triangles, flat colour from the last vertex).

Fragment (per draw parameters from a storage buffer indexed by `instance_index`, passed with `firstInstance`):
texture coordinates (STQ divide or FST UV / size), REGION_CLAMP by clamping coordinates, LOD = `log2(1/q)*2^L + K/16` (lcm 0) or K
(lcm 1) clamped to [0, mxl], `textureSampleLevel` (trilinear sampler for mmin 5, bilinear for 1), CT24/CT16 TEXA expansion when the
source is a target, TFX (modulate/decal/highlight) and TCC, fog `C*F + FOG*(255-F)`, alpha test, output rules for CT16 targets.

Override constants make the variants so that no pipeline carries code it does not use (on Mali a `discard` anywhere disables early depth /
forward pixel kill for that pipeline):

* `ATEST` (none | GREATER | NOTEQUAL | ... ) and `PASS` (single | pass-1 "passed pixels" | pass-2 "failed pixels"),
* `TEX` (none | cache texture | target as CT32 | as CT24 | as CT16), `MIP` (off | from Q), `FOG`,
* `DUAL` (second output = factor) and `CT16OUT`.

Render state per pipeline: blend (table below), colour write mask from FBMSK (whole channels only, measured), depth compare/write, target
formats. Measured state combinations: about 30-40 pipelines in all scenes. They are built at start from a recorded key list
(`record_pipeline_key` / `load_pipeline_keys` of hwdraw.cpp, file in the app's data dir) and otherwise in the background; a draw whose
pipeline is not ready yet waits once (the first frame of a scene), never falls back.

Blend mapping (GS `(A-B)*C>>7 + D`, factor c = C/128, clamp on):

| GS | WebGPU colour blend | notes |
|---|---|---|
| off / `(Cs-Cs)*x+Cs` | none | |
| `(Cs-Cd)*As+Cd` | src = src1.a (dual: As/128), dst = 1-src1.a, add | alpha channel: src One, dst Zero (raw As written) unless masked |
| `(Cs-0)*FIX+Cd` | src = constant, dst = One, add | constant = FIX/128 via `setBlendConstant` |
| `(Cs-Cd)*FIX+Cd` | src = constant, dst = 1-constant | |
| `(Cd-Cs)*FIX+0` | src = constant, dst = constant, reverse subtract | FIX = 0x80 here: Cd - Cs |
| `(0-Cs)*FIX+Cd` | src = constant, dst = One, reverse subtract | shadow darkening |
| `(Cs-0)*FIX+0` | src = constant, dst = Zero | |
| `(Cs-0)*As+Cd` | src = src1.a, dst = One | |

Without dual-source blending: As-blends whose alpha channel is masked (FBMSK 0xFF000000, the common case) output `vec4(c, As/128)` and
use `src-alpha`; As-blends that also write alpha are drawn twice (colour pass with alpha masked, then alpha-only pass, as hwdraw's
`ALPHA_ONLY`). GS truncation vs card rounding, and As > 0x80 saturating at 1.0, are accepted differences.

Alpha test with partial fail (`GREATER 0x7F, RGB_ONLY` and `FB_ONLY`): two draws, as PCSX2: pass 1 = pixels that pass (full write),
pass 2 = pixels that fail (colour only, no depth, no alpha). `NEVER` tests are pure write masks (no shader test). `NOTEQUAL 0, KEEP` is a
plain discard.

### 3.6 Display and present

DISPFB1 is F0 (512x256, fbw 8). hwr finds the target owning those pages and the present pass samples it directly (a texture variant of
blit.wgsl's fragment, same picture-style options, interlace: FFMD=1 field mode with DBY 1 -> the 256-line buffer is shown full height,
which is what the exact path's weave converges to for a still picture). No readback, no VRAM round trip. Optional later switch
`PS2_HWR_PRESENT=main`: present F800 (512x384, before the game's own 384->256 squeeze and deflicker) for a sharper picture.

Upscaling: every target x s (1, 2, 3), positions and scissors x s, sampled targets use normalised coordinates of the real size, the 64x64
shadow atlas can stay at 1x (it is blurred anyway). Cost on the phone at 2x: ~9 M pixels drawn per frame, well inside a Mali-G615's fill
rate; the exact path at 3x was far too heavy.

### 3.7 WebGPU / wgpu constraints on the Mali, and what the design does about them

* No framebuffer fetch / texture barrier in WebGPU: the game needs none (no Ad blend, DATE, wrap, partial mask). Self-sampling (glow
  ladder) uses a region copy before the draw (small: 128x96 .. 256x192).
* A texture cannot be sampled and rendered in the same pass: the graph puts producers in earlier passes; same-target sampling uses a copy.
* Tile-based GPU: every pass costs a tile load and store. Few passes (graph), `loadOp clear` instead of clear sprites, `storeOp discard`
  for depth and for transient atlases after their last read, no compute between render passes (all compute in the prologue).
* `discard` in a shader disables early depth / FPK: only alpha-test variants contain it.
* Many small `queue.writeBuffer` calls create staging allocations: one write per buffer per frame.
* Dual-source blending is an optional feature (`DUAL_SOURCE_BLENDING` in wgpu, Vulkan `dualSrcBlend`): query at start, fall back as above.
* Depth32Float and RGBA8Unorm are renderable and sampleable everywhere; no storage textures needed (the store/load sync shaders use
  `rgba8unorm` storage, core WebGPU, only for save states and fallbacks).
* Max 8 colour attachments / 4 bind groups / 16 samplers: one group for per-frame buffers, one for the sampled texture (cached per
  texture/target view), samplers fixed (4 combinations).
* Pipeline creation is slow on Android drivers (tens of ms): recorded key list + warm-up at boot, background creation.

### 3.8 What stays on the compute path

Uploads and CLUT loads into VRAM (prologue pass), decodes of cache misses (prologue pass), save-state write-back, and the never-measured
fallback states. Later (M5) uploads can become lazy: only applied to VRAM when a miss decode, a fallback or a save state needs that
memory, which removes ~240 small dispatches per frame in races.

---

## 4. Milestones

Picture check used by all milestones (`hwr/scripts/framecmp.py`, to be moved to `scripts/`): the game state does not depend on the
renderer (no readbacks), so the same save state and `--press` list give the same game frames. Run the exact and the hw renderer with
`--frames-dir` and `--frame-every 25` and compare frame by frame:

* mean absolute difference per channel, luma PSNR, share of pixels whose largest channel difference is > 16 and > 48, share of 16x16
  blocks with mean difference > 24 ("missing or wrong object");
* **frame passes** when PSNR >= 30 dB, pixels > 48 <= 1.0 %, bad blocks <= 0.5 %; **scene passes** when 95 % of its frames pass and no
  frame has bad blocks > 2 %;
* plus visual inspection of `prefix_side.png` / `prefix_diff.png` (x4) for every failing frame and 3 frames per scene.

Calibration: identical frames give 99 dB / 0 %; two consecutive race frames (3700 vs 3701) give PSNR 22.5 dB, 8.1 % > 48, 8.5 % bad
blocks (FAIL), so a one-frame motion or a missing object fails clearly while filtering/rounding differences pass.

Scenes: race (race_state_tmp, 3400-4100 with the hot_exact.sh press lists), cup race (`eecov/st/cup0.state` 5000+), pinball (4600+),
dance (4400+), main menu (3400+), title (`st/title.state`), boot FMV (cold boot to frame 2600).

**M1 - race main geometry, in order, no graph (PC).** hwr module, switch, frame log, triangle-list expansion, one RGBA8 + Depth32Float
target per (fbp, fbw) with page ownership, content-keyed texture cache with mips (decode via gs.wgsl), the fragment shader (texture
function, fog, LOD from Q, alpha test incl. two-pass), blends of the table, clear detection, targets as textures (F0 copy, shadow, glow;
region copy for self-sampling), present from the F0 target. Passes in stream order (~300 per frame: fine on PC, it is a correctness step).
Check: race scene passes; `PS2_HWR_LOG` shows 0 fallback shapes, texture cache hit rate > 90 % after 10 frames; PC frame time in race
reported next to the exact path's 2.5 ms. Expected: correct pictures, PC GPU time similar or lower; phone not yet the goal.

**M2 - frame graph: reorder, rename, merge, atlases (PC, then a first phone run).** Dependency graph, renaming of fully overwritten
targets, shadow atlas, draw merging, storeOp discard for depth, single command buffer per frame. Check: same pictures as M1 (framecmp
between M1 and M2 output: PSNR >= 45 dB), passes per frame <= 12 in race and cup race, draws per frame < 200. First phone measurement
(`PS2_SPEED_LOG=1`, `PS2_PROF=1`): expected GPU time per race frame 5-10 ms (from ~40-50 ms).

**M3 - blending and alpha corner cases.** Dual-source on/off paths (force off with a switch to test the fallback), As > 0x80, CT16 target
output and TEXA sampling, glow-mask alpha (check the glow appears only on glowing objects: crop compare around them), half-texel rules
of the F800->F0 copy and the glow ladder, REGION_CLAMP. Check: race and cup race pass with bad blocks < 0.2 %; dedicated crops of glow,
shadows and HUD inspected side by side.

**M4 - menus, title, minigames, FMV, upscaling.** Title, main menu, cup select, pinball, dance, FMV (uploaded CT32 frames: cache misses
every frame, must stay cheap), save/load state while in hw mode, renderer switch at a frame boundary. 2x and 3x scale (compare by
down-sampling the hw frame to 1x before framecmp). Check: all scenes pass; save state made in hw mode loads in exact mode and gives the
exact mode's next frames.

**M5 - Android build and tuning.** Build `build/android-gpu` with the module, `PS2_RENDERER=hw` in files/env.txt, pipeline key file shipped
in the app data, measure race and cup race with `PS2_SPEED_LOG`/`PS2_PROF` over 3 minutes (thermal). Tuning in order of expected gain:
lazy VRAM uploads, `PS2_HWR_PRESENT=main`, vertex expansion on a worker thread, texture cache budget, 16-bit index buffers.
Target: 50 fps in the race and cup race at 1x (and at 2x if the GPU has headroom), window-thread time < 8 ms per frame.

Expected phone performance after M2-M5 (estimate, not measured): GPU 4-8 ms per race frame at 1x (10-20 k small triangles, ~2.5 M
pixels, ~10 passes, a few decode misses), window thread 4-8 ms (vertex expansion 10-20 k vertices, ~150 draw calls through wgpu, upload
hashing that the exact path already does). The limit then moves to the CPU emulation threads (EE, VU1), which the profiler already covers.

---

## 5. Risks and effort

| risk | effect | mitigation |
|---|---|---|
| GS rasterisation / sampling conventions differ from the card (edge rules, half texel, LOD) | seams, blurry or shifted copies (F800->F0, glow ladder), mip choice differs | the framecmp crops; rules taken from gs.wgsl and hwdraw.wgsl; tolerance accepts small differences |
| content key wrongly matches (hash collision or a texture partly overwritten) | wrong texture for a frame | 64-bit keys built from per-upload hashes plus format/size; partial uploads fall back to address+version keys; debug switch that decodes every time and compares |
| frame-graph reorder breaks a dependency the trace did not show (another scene) | wrong picture in that scene | dependencies from page sets, not from game knowledge; `PS2_HWR_ORDER=stream` switch draws in stream order for comparison |
| dual-source blending missing on the Mali driver | 2 draws for some As-blends | fallback path exists; As-blends with alpha written are few (< 1 % of pixels) |
| pipeline creation stalls on Android | hitches on first sight of a scene | recorded key list, warm-up at boot, background builder |
| window thread becomes the bottleneck (wgpu per-draw overhead on a phone CPU) | fps capped by CPU | draw merging (M2), one buffer write per frame, persistent bind groups, vertex work on a worker |
| scenes not measured (other tracks, cutscenes, other minigames, 2-player split screen) use other features | fallbacks / wrong pictures there | fallback to the painter per batch with a log line; run the eecov drives with `PS2_GPU_STAT=1` before M4 to list every feature used |
| memory on the phone | OOM | cache budget, target sizes fixed per fbw, one persistent buffer per kind |

Effort (one experienced developer, or the same number of focused agent sessions): M1 6-8 days, M2 4-6 days, M3 3-4 days, M4 4-5 days,
M5 4-7 days. Total about 4-6 weeks. M1 and M2 carry most of the risk; after M2 the phone result shows whether 50 fps is reachable.

---

## 6. Deviations of the implementation (M1) from this design, and why

* **Module place**: `runtime/src/gs/gpu/hwr.cpp/.hpp`, `hwr.wgsl` (drawing program) and `hwr_sync.wgsl` (load/store compute programs,
  appended to gs.wgsl). As 3.1 says; the `exact` path runs no hwr code (only `hwr::g_on` tests) and is byte-identical.
* **Ordering in M1**: hwr's work is placed in gpu.cpp's own command stream (the `hw::live_encode` protocol: an op records the number of
  compute commands queued before it and is encoded between them at submit). Uploads, palette loads and decodes stay compute commands of
  gpu.cpp; there is no separate prologue pass yet (that is M2 with the frame graph).
* **Decode**: gs.wgsl `decode` into gpu.cpp's decoded buffer (it gets `CopySrc` in hw mode only) at the draw's position in the stream,
  then `copyBufferToTexture` per mip level. The decode row pitch is padded to 64 texels (the setup's tw is raised to >= 6) because a
  buffer-to-texture copy needs 256-byte rows.
* **Present**: instead of sampling the target in the present pass, the target that owns the displayed rectangle is written back to graphics
  memory (`hwr_store`, one 512x256 compute dispatch) right before gpu.cpp's display kernel. Display, field/deflicker handling, frame dumps
  (`--frames-dir`) and the window present are then unchanged and identical to the exact path, which framecmp needs. Direct present from the
  target is left for M5 (it saves one small dispatch per frame).
* **Sampling**: decoded textures with REPEAT/CLAMP use the card's sampler (bilinear/trilinear, LOD from Q in the shader through
  `textureSampleLevel`); REGION_CLAMP/REGION_REPEAT and targets used as textures use texel loads with the GS wrap rules (`TEX=2`),
  which also gives the CT24/CT16+TEXA view of a target. Mipmaps with a region mode read level 0 (counted as an approximation; not in races).
* **Blending**: `(Cs-0)*FIX+Cd`, `(Cs-0)*FIX+0` and `(0-Cs)*FIX+Cd` are not done with a constant blend factor: the shader outputs the GS's
  truncated product `(Cs*FIX)>>7` (rounded up for the subtractive form) and the card only adds/subtracts it (factors One). The card's
  rounding of `Cs*FIX/128` left dim non-zero pixels in the glow buffer, which the CT24+AEM glow composite turned into alpha 0x80 and wider
  glow (PSNR 39 -> 44 dB). 16-bit targets: the colour written is cut to 5 bits where the card only adds or subtracts (exact GS storage).
* **Two-draw alpha test**: a merged draw takes a shape only if it shares no pixel with the shapes already in it (separating axis test on
  the triangles, at most 96 triangles per draw); otherwise passing-then-failing pixels of overlapping translucent triangles came out in
  the wrong order (pink instead of red parts of the translucent robot, HUD glyph edges). Costs ~190 more draws per race frame.
* **Clear detection**: as 3.3, but the pass's attachment may be larger than the cleared rectangle (targets are allocated fbw*64 x 512);
  rows below are outside the target's pages and never read.
* **Targets**: every colour target is fbw*64 x 512 (RGBA8), depth targets are per (zbp, size) (Depth32Float, value z/(zmax+1)). A pass
  also attaches the colour target's last depth target when sizes match, so draws without depth do not break it. A new colour target is
  loaded from memory when its first page is not stale; depth targets start cleared (the game clears depth every frame).
* **Fallback**: unsupported shapes are counted per reason and not drawn (no painter fallback yet); 0 such shapes in race and cup race.

### M2 (frame graph)

* **Where the reorder happens**: not a separate prologue pass recorded by hwr; hwr's passes and copies simply do not take a place in
  gpu.cpp's command stream (only memory loads and stores do), so `schedule()` puts them after all compute commands of the submit. The
  frame's uploads, palette loads and decodes are then one stretch of compute work before all render passes, which is the prologue of 3.1.
* **Clear detection** works on runs of strips (the game clears with 16 sprites of 32 pixels), and a run counts as a full clear when it
  covers every page the target holds (not "the valid rectangle"); the run's draws are taken back. Opaque textured runs over the whole
  target (the display copy) rename without being taken back.
* **Renaming** is per target surface (pool of textures of equal size), not page-level versions; the graph's resources are surfaces and
  cached textures, so a texture decoded from memory never creates an edge to a pass.
* **No shadow atlas**: renaming already lets all shadow and blur passes run before the main pass; the race needs one shadow and one blur
  pass, the cup race four of each (64x512 each). An atlas would need texel offsets in the shader's target reads (or copies back).
* **Self copies** are kept (WebGPU cannot sample an attachment of the same pass) but made only when the rectangle a draw reads was drawn into
  since the last copy: 3 per frame. Renaming cannot replace them: the ladder blends into the rest of the same picture.
* **Draw order inside a pass** is the stream order; draws are not sorted by pipeline (only neighbours with equal state merge).
* **Depth**: a node whose depth no one uses afterwards and whose draws do not test it drops the attachment; storeOp discard for surfaces
  that no target holds after their last use in the submit. The depth that is current at the end of the frame is stored (the next frame is
  not known yet).
* **Uploads** (gpu.cpp, hw mode only): pending uploads and palette loads are grouped by the exact 256-byte blocks they touch
  (`emit_pre_hwr`, `hwr::pre_blocks`), palette commands at most 16 loads each; 124 + 42 dispatches per race frame became 5.5 + 9.8.
