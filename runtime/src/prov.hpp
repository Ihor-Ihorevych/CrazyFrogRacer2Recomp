#pragma once

// Provenance of the work the console does (docs/debugging-native.md): which code of the GAME asked for it. A game routine that is worth
// rewriting as native code (a draw sequence that makes the hardware renderer write a target back to memory a hundred times a frame, a
// vector program that builds the packets, a loop that burns the emulation thread) is found by asking who started the work.
//
//   PS2_PROV=<frames>       print the table every <frames> frames (and at the end); 0 or empty: off
//   PS2_PROV_TOP=<n>        rows of the table (default 20)
//   PS2_PROV_FILE=<path>    the table also goes to this file (appended), one block per report
//
// Every start of a DMA transfer (VIF1 and GIF paths: the draws; SPR, IPU and the others are counted too) notes the EE's pc, its return
// address, the thread and the return addresses found on the stack ("who called whom"), and every run of a VU1 program notes its entry and
// its key. The work the graphics side then does (shapes drawn, shapes it could not draw and why, textures decoded, target write-backs,
// pixels written back) is counted against the context that was current when the shape arrived. With the drawing on its own thread
// (a window run) the context is the one of the moment the shape is processed, which can be a kick or two late; run headless
// (one thread) for exact numbers. A "function" is estimated as the code back to the last `addiu sp, sp, -N`.

#include <cstdint>

namespace ps2::prov {

extern bool g_on;
extern bool g_writers;   // PS2_WRITERS=1: who writes the memory the DMA chains read (the display lists and vertex data)

enum Counter : int {
    kShapes, kDrawn, kSkipped, kDropped,        // shapes seen, drawn by the card, skipped (empty), not drawn for a reason
    kStores, kStorePixels,                      // targets written back to memory because a draw reads them as another format
    kDecodes,                                   // textures decoded
    kPixels,                                    // pixels the drawn shapes cover (bounding boxes)
    kCounters
};

void init();                                    // reads PS2_PROV*; called once at the start
void ee_kick(int channel);                      // a DMA channel is started by the EE (dmac.cpp)
void vu_run(std::uint32_t entry, std::uint64_t key);   // a VU1 program starts (vu_micro.cpp)
void gif_path(int path);                        // the GIF is given a packet of this path (1: a VU1 kick, 2: VIF1 DIRECT, 3: the DMA channel): gif.cpp
void count(Counter c, std::uint64_t n = 1);
void drop(const char *reason);                  // a shape that was not drawn, and why (counted with kDropped)
void frame_end(std::uint64_t frame);            // every frame: prints when it is time
void report(const char *when);                  // prints the table now

// PS2_WRITERS=1 (with PS2_PROV): the EE functions that write the memory the DMA channels read their packets from. Every guest store takes
// the slow path while it is on (a debugging run, about 3x slower). The pages a kick reads are marked (mark_dma), a store into a marked page is
// counted against the function it comes from and the one that called it (the pc of the generated code is the last call or dispatch point,
// the return address names the routine). The table says how many bytes per frame each function writes: the display-list builders.
void mark_dma(std::uint32_t address, std::uint32_t bytes);
void note_store(std::uint32_t address, std::uint32_t bytes);

}  // namespace ps2::prov
