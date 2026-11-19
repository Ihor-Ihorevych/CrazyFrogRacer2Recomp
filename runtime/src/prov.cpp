#include "prov.hpp"

#include "ps2/memory.hpp"
#include "ps2/os.hpp"
#include "kernel.hpp"
#include "ps2/system.hpp"

#include <algorithm>
#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace ps2::prov {

bool g_on = false;
bool g_writers = false;

namespace {

constexpr int kChain = 6;
constexpr std::uint32_t kCodeLow = 0x00100000u;

struct EeCtx {
    std::uint32_t pc = 0, ra = 0, thread = 0;
    std::uint8_t channel = 0;
    std::uint32_t chain[kChain] = {};
};

struct VuCtx {
    std::uint32_t entry = 0xFFFFFFFFu;
    std::uint64_t key = 0;
};

struct Row {
    EeCtx ee;
    VuCtx vu;
    std::uint64_t c[kCounters] = {};
    std::uint64_t kicks = 0;
    int path = 0;
    bool path_set = false;
    std::map<std::string, std::uint64_t> reasons;
};

std::mutex g_mutex;
int g_path = 0;
EeCtx g_ee;
VuCtx g_vu;
std::uint64_t g_interval = 0, g_last_report = 0, g_frames = 0, g_frames_at_start = 0;
std::size_t g_top = 20;
std::string g_file;
std::unordered_map<std::uint64_t, Row> g_rows;
std::uint64_t g_total[kCounters] = {};

struct Writer {
    std::uint32_t fn = 0, caller_fn = 0, pc = 0, ra = 0;
    std::uint64_t stores = 0, bytes = 0;
};
std::unordered_map<std::uint64_t, Writer> g_writer_rows;
std::vector<std::uint8_t> g_src_pages(8192, 0);   // 4 KiB pages of main memory that a DMA chain read from

std::uint64_t mix(std::uint64_t h, std::uint64_t v) {
    h ^= v + 0x9E3779B97F4A7C15ull + (h << 6) + (h >> 2);
    return h;
}

// What a shape is told apart by: from a VU1 kick (path 1) the program that built it, else the EE code that started the transfer.
std::uint64_t key_of(const EeCtx &e, const VuCtx &v, int path) {
    std::uint64_t h = mix(0x9A0Bull, static_cast<std::uint64_t>(path));
    if (path == 1) {
        h = mix(h, v.entry);
        return mix(h, v.key);
    }
    h = mix(h, e.pc);
    h = mix(h, e.ra);
    h = mix(h, e.channel);
    for (std::uint32_t c : e.chain) h = mix(h, c);
    return h;
}

Row &row() {
    const std::uint64_t k = key_of(g_ee, g_vu, g_path);
    Row &r = g_rows[k];
    if (r.kicks == 0 && r.c[kShapes] == 0 && !r.path_set) {
        r.path_set = true;
        r.path = g_path;
        r.ee = g_ee;
        r.vu = g_vu;
    }
    return r;
}

std::uint32_t word_at(std::uint32_t a) {
    if (a >= kRamSize || (a & 3u)) return 0;
    std::uint32_t w;
    std::memcpy(&w, g_ram + a, 4);
    return w;
}

// A word that is a return address: it is code, and the instruction two before it (the call and its delay slot) is jal or jalr.
bool is_return_address(std::uint32_t v) {
    if (v < kCodeLow + 8 || v >= kRamSize || (v & 3u)) return false;
    const std::uint32_t call = word_at(v - 8);
    return (call >> 26) == 3u || ((call >> 26) == 0u && (call & 0x3Fu) == 9u);
}

// The start of the function around a: back to the last `addiu sp, sp, -N` (at most 16 KiB), else 0.
std::uint32_t function_start(std::uint32_t a) {
    if (a < kCodeLow || a >= kRamSize) return 0;
    for (std::uint32_t p = a & ~3u, n = 0; p >= kCodeLow && n < 4096; p -= 4, ++n) {
        const std::uint32_t w = word_at(p);
        if ((w >> 16) == 0x27BDu && (w & 0x8000u)) return p;
        // `jr ra` + its delay slot just before a: a is the start of the next function (when nothing was found yet)
        if (w == 0x03E00008u && p + 8 <= a && n > 0) return p + 8;
    }
    return 0;
}

std::string where(std::uint32_t a) {
    char buf[64];
    const std::uint32_t f = function_start(a);
    if (f) std::snprintf(buf, sizeof buf, "0x%06X(fn 0x%06X+0x%X)", a, f, a - f);
    else std::snprintf(buf, sizeof buf, "0x%06X", a);
    return buf;
}

std::uint32_t fn_of(std::uint32_t a);

void out(const std::string &text) {
    std::fputs(text.c_str(), stderr);
    if (!g_file.empty())
        if (std::FILE *f = os::open(g_file, "ab")) {
            std::fputs(text.c_str(), f);
            std::fclose(f);
        }
}

std::uint32_t fn_of(std::uint32_t a) { return function_start(a); }

}  // namespace

void mark_dma(std::uint32_t address, std::uint32_t bytes) {
    if (!g_writers || !bytes) return;
    const std::uint32_t first = (address & (kRamSize - 1)) >> 12, last = std::min<std::uint32_t>((((address & (kRamSize - 1)) + bytes - 1) >> 12), 8191u);
    for (std::uint32_t p = first; p <= last; ++p) g_src_pages[p] = 1;
}

void note_store(std::uint32_t address, std::uint32_t bytes) {
    if (address >= kRamSize || !g_src_pages[address >> 12]) return;
    const std::uint32_t pc = g_cpu.pc, ra = g_cpu.r[31].w[0];
    const std::uint32_t fn = fn_of(pc ? pc : ra), caller = fn_of(ra);
    const std::uint64_t k = (static_cast<std::uint64_t>(fn) << 32) | caller;
    std::lock_guard<std::mutex> lock(g_mutex);
    Writer &w = g_writer_rows[k];
    if (!w.stores) w.fn = fn, w.caller_fn = caller, w.pc = pc, w.ra = ra;
    ++w.stores;
    w.bytes += bytes;
}

void init() {
    static bool done = false;
    if (done) return;
    done = true;
    const std::string v = os::env("PS2_PROV");
    if (v.empty() || v == "0") return;
    g_interval = std::strtoull(v.c_str(), nullptr, 10);
    if (const std::string t = os::env("PS2_PROV_TOP"); !t.empty()) g_top = std::max<std::size_t>(1, std::strtoull(t.c_str(), nullptr, 10));
    g_file = os::env("PS2_PROV_FILE");
    g_on = true;
    g_writers = os::env("PS2_WRITERS") == "1";
    std::atexit([] { report("end of run"); });
    std::fprintf(stderr, "[prov] on: a table every %llu frames, top %zu rows%s\n", static_cast<unsigned long long>(g_interval), g_top, g_file.empty() ? "" : " (also in the file)");
}

void ee_kick(int channel) {
    if (!g_on) return;
    EeCtx e;
    e.pc = g_cpu.pc;
    e.ra = g_cpu.r[31].w[0];
    e.thread = static_cast<std::uint32_t>(kernel::current_thread());
    e.channel = static_cast<std::uint8_t>(channel);
    // the return addresses on the stack: the calls that led here
    const std::uint32_t sp = g_cpu.r[29].w[0];
    int found = 0;
    if (!(sp & 3u) && sp < kRamSize)
        for (std::uint32_t at = sp, n = 0; at + 4 <= kRamSize && n < 192 && found < kChain; at += 4, ++n) {
            const std::uint32_t v = word_at(at);
            if (is_return_address(v) && v != e.ra) e.chain[found++] = v;
        }
    std::lock_guard<std::mutex> lock(g_mutex);
    g_ee = e;
    // a kick is counted against the context before it is the next one: path 3 draws (the DMA's own packets) follow the channel's kick
    const int saved = g_path;
    g_path = 3;
    ++row().kicks;
    g_path = saved;
}

void gif_path(int path) {
    if (!g_on) return;
    g_path = path & 3;
}

void vu_run(std::uint32_t entry, std::uint64_t key) {
    if (!g_on) return;
    std::lock_guard<std::mutex> lock(g_mutex);
    g_vu.entry = entry;
    g_vu.key = key;
}

void count(Counter c, std::uint64_t n) {
    if (!g_on) return;
    std::lock_guard<std::mutex> lock(g_mutex);
    row().c[c] += n;
    g_total[c] += n;
}

void drop(const char *reason) {
    if (!g_on) return;
    std::lock_guard<std::mutex> lock(g_mutex);
    Row &r = row();
    ++r.c[kDropped];
    ++g_total[kDropped];
    ++r.reasons[reason];
}

void report(const char *when) {
    if (!g_on) return;
    std::lock_guard<std::mutex> lock(g_mutex);
    std::vector<const Row *> rows;
    for (const auto &[k, r] : g_rows) rows.push_back(&r);
    // the rows that cost the card the most: its work, then the write-backs weigh more than a shape
    const auto weight = [](const Row *r) { return r->c[kShapes] + 200 * r->c[kStores] + 20 * r->c[kDecodes] + 50 * r->c[kDropped]; };
    std::sort(rows.begin(), rows.end(), [&](const Row *a, const Row *b) { return weight(a) > weight(b); });
    const std::uint64_t frames = std::max<std::uint64_t>(1, g_frames - g_frames_at_start);
    char head[256];
    std::snprintf(head, sizeof head, "[prov] %s: frame %llu, %llu frames counted, %zu contexts; per frame: shapes %.0f drawn %.0f dropped %.0f write-backs %.1f (%.2f Mpixel) decodes %.1f\n", when,
                  static_cast<unsigned long long>(g_frames), static_cast<unsigned long long>(frames), g_rows.size(), static_cast<double>(g_total[kShapes]) / frames,
                  static_cast<double>(g_total[kDrawn]) / frames, static_cast<double>(g_total[kDropped]) / frames, static_cast<double>(g_total[kStores]) / frames,
                  static_cast<double>(g_total[kStorePixels]) / frames / 1e6, static_cast<double>(g_total[kDecodes]) / frames);
    std::string text = head;
    std::size_t shown = 0;
    for (const Row *r : rows) {
        if (shown++ >= g_top) break;
        char line[512];
        std::snprintf(line, sizeof line, "[prov] #%zu path %d per frame: shapes %.1f drawn %.1f dropped %.1f write-backs %.2f (%.3f Mpx) decodes %.2f | kicks %.1f ch%u thread %u | EE pc %s ra %s", shown, r->path,
                      static_cast<double>(r->c[kShapes]) / frames, static_cast<double>(r->c[kDrawn]) / frames, static_cast<double>(r->c[kDropped]) / frames,
                      static_cast<double>(r->c[kStores]) / frames, static_cast<double>(r->c[kStorePixels]) / frames / 1e6, static_cast<double>(r->c[kDecodes]) / frames,
                      static_cast<double>(r->kicks) / frames, r->ee.channel, r->ee.thread, where(r->ee.pc).c_str(), where(r->ee.ra).c_str());
        text += line;
        text += "\n[prov]     callers:";
        for (std::uint32_t c : r->ee.chain)
            if (c) text += " " + where(c);
        char vu[96];
        if (r->vu.entry != 0xFFFFFFFFu) std::snprintf(vu, sizeof vu, "\n[prov]     VU1 program entry 0x%04X key %016llX", r->vu.entry, static_cast<unsigned long long>(r->vu.key));
        else std::snprintf(vu, sizeof vu, "\n[prov]     no VU1 program yet");
        text += vu;
        for (const auto &[why, n] : r->reasons) {
            char w[160];
            std::snprintf(w, sizeof w, "\n[prov]     not drawn: %s x%.1f per frame", why.c_str(), static_cast<double>(n) / frames);
            text += w;
        }
        text += "\n";
    }
    if (g_writers) {
        std::vector<const Writer *> ws;
        for (const auto &[k, w] : g_writer_rows) ws.push_back(&w);
        std::sort(ws.begin(), ws.end(), [](const Writer *a, const Writer *b) { return a->bytes > b->bytes; });
        text += "[prov] writers of the memory the DMA chains read (display lists, vertex data), bytes per frame:\n";
        std::size_t n = 0;
        for (const Writer *w : ws) {
            if (n++ >= g_top) break;
            char line[320];
            std::snprintf(line, sizeof line, "[prov]   #%zu %.1f KiB/frame %.0f stores/frame | function %s called from %s | last pc 0x%06X ra 0x%06X\n", n, static_cast<double>(w->bytes) / frames / 1024.0,
                          static_cast<double>(w->stores) / frames, where(w->fn).c_str(), where(w->caller_fn).c_str(), w->pc, w->ra);
            text += line;
        }
    }
    out(text);
}

void frame_end(std::uint64_t frame) {
    if (!g_on) return;
    g_frames = frame;
    if (!g_frames_at_start) g_frames_at_start = frame;
    if (g_interval && frame >= g_last_report + g_interval) {
        report("interval");
        g_last_report = frame;
    }
}

}  // namespace ps2::prov
