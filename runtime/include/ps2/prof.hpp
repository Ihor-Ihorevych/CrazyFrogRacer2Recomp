#pragma once
// PS2_PROF=1: a sampling profiler for "why is it slow", built in. Every runtime thread registers itself (crash::enter_thread) and the
// code marks what the thread is doing with a label (Scope around a wait or a subsystem). A sampler thread looks at every thread's label
// once a millisecond and, from /proc, whether the thread is running or sleeping and on which processor, and once a second prints
//   [prof] one line per thread: CPU % of the thread's own clock, running share, the labels that took time, the processors it was on
//   [prof] one line for the frames: how long each picture took (min, median, 95th percentile, max, how many were slow)
//   [prof] one line with the current frequency of each processor (when the system lets the program read it)
// Off unless PS2_PROF is set to something other than 0; then a label costs one thread-local store.
#include <atomic>
#include <cstdint>

namespace ps2::prof {

enum Label : std::uint8_t {
    kOther,       // anything not marked (for the emulation thread: the game's own code)
    kEe,          // emulation thread: the game's code (set at the top of the main loop)
    kEvents,      // emulation thread: timed events (run_due_events)
    kKernel,      // emulation thread: the kernel's service of the game's calls
    kIdle,        // emulation thread: every game thread waits, the machine skips ahead
    kIopSync,     // emulation thread: the IOP (second processor) catching up
    kSifPump,     // emulation thread: the EE-IOP packets
    kDma,         // emulation thread: DMA start or resume
    kIpu,         // emulation thread: the MPEG decoder's registers
    kQueueFull,   // a thread waits for room in a queue to the next thread
    kVuDrain,     // emulation thread waits for the vector unit thread to finish what it was given
    kLimiter,     // emulation thread: the speed limiter (waits for the clock or the sound device)
    kVuWork,      // vector unit thread: working
    kVuWait,      // vector unit thread: no work
    kRenderWork,  // drawing thread: working (encoding and submitting)
    kRenderWait,  // drawing thread: no work
    kPostWork,    // submit thread: working
    kPostWait,    // submit thread: no work
    kTilesWait,   // tile helper thread: waiting for shapes
    kPresentWait, // emulation thread: too many pictures are queued for the screen, it waits for the drawing thread to show some
    kWgpuBase,    // 16 labels from here: the wgpu calls, in the order of wt::Kind in gs/gpu/gpu.cpp
    // drawing thread, inside render-work (the hardware renderer, hwr.cpp): where its processor time goes
    kRGs = kWgpuBase + 16,  // r:gs-cmds    the GS command stream played back (GIF packets, register writes, vertex kicks) outside the parts below
    kRShape,                // r:shape      a shape turned into card vertices and bookkeeping (hwr::shape, without its state set-up)
    kRBuild,                // r:build      a shape's state worked out (build_state: targets, texture, pipeline key)
    kRSchedule,             // r:schedule   the frame's passes and copies ordered (schedule / build_graph)
    kRPrepare,              // r:prepare    vertex and parameter data written to the card
    kREncode,               // r:encode     render passes, draws and copies recorded (the w: labels inside it are wgpu's own calls)
    kRProfile,              // r:gpu-prof   PS2_GPU_PROFILE's own reading of the timestamps
    // vector unit thread, inside vu-work: VIF packets (unpack, MSCAL), the programs, and the packets they send on
    kVVif,                  // v:vif        VIF1 packets parsed and played (UNPACK into VU memory, MSCAL starts) outside the labels below
    kVProg,                 // v:program    a compiled program's body
    kVKick,                 // v:xgkick     a program's packet handed on (GIF parsing, the queue to the drawing thread)
    kLabels
};

extern bool g_on;
extern thread_local std::atomic<std::uint8_t> *t_slot;

inline std::uint8_t enter(int label) {
    if (!g_on || !t_slot) return 0;
    return t_slot->exchange(static_cast<std::uint8_t>(label), std::memory_order_relaxed);
}
inline void leave(std::uint8_t previous) {
    if (g_on && t_slot) t_slot->store(previous, std::memory_order_relaxed);
}
inline void set(int label) {
    if (g_on && t_slot) t_slot->store(static_cast<std::uint8_t>(label), std::memory_order_relaxed);
}

struct Scope {
    std::uint8_t previous;
    explicit Scope(int label) : previous(enter(label)) {}
    ~Scope() { leave(previous); }
    Scope(const Scope &) = delete;
    Scope &operator=(const Scope &) = delete;
};

void register_thread(const char *name);  // first call starts the sampler
int thread_ids(int *out, int max);       // kernel thread ids of the heavy runtime threads (for the Android performance hint session)
void frame_mark();                       // the emulation thread, once per picture
// A counter another part of the runtime keeps (calls, nanoseconds): printed every second as the change per second times `scale`
// (scale 1e-6 turns nanoseconds into milliseconds per second).
void add_counter(const char *name, const std::atomic<std::uint64_t> *value, double scale);
void set_note(const char *text);         // a line the host wants in every second's output (the phone's thermal state)

}  // namespace ps2::prof
