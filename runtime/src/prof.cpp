#include "ps2/prof.hpp"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <utility>
#include <vector>

#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <unordered_map>
#endif
#if defined(__linux__)
#include <dlfcn.h>
#include <map>
#include <pthread.h>
#include <signal.h>
#include <sys/syscall.h>
#include <sys/time.h>
#include <time.h>
#include <ucontext.h>
#include <unistd.h>
#endif

namespace ps2::prof {

bool g_on = [] {
    const char *v = std::getenv("PS2_PROF");
    return v && *v && std::strcmp(v, "0") != 0;
}();
thread_local std::atomic<std::uint8_t> *t_slot = nullptr;

namespace {

constexpr int kMaxSlots = 16;

const char *const kNames[kLabels] = {
    "other", "EE-code", "events", "kernel", "idle", "IOP-sync", "SIF-pump", "DMA", "IPU", "QUEUE-FULL", "VU-drain", "LIMITER",
    "vu-work", "vu-wait", "render-work", "render-wait", "post-work", "post-wait", "tiles-wait", "PRESENT-WAIT",
    "w:encoder", "w:begin-pass", "w:set-pipeline", "w:set-group", "w:dispatch", "w:end-pass", "w:render-pass", "w:finish", "w:submit",
    "w:write-buf", "w:poll", "w:ACQUIRE", "w:PRESENT", "w:readback", "w:copy-map", "w:wait-post",
    "r:gs-cmds", "r:shape", "r:build", "r:schedule", "r:prepare", "r:encode", "r:gpu-prof", "v:vif", "v:program", "v:xgkick"};

struct Slot {
    std::atomic<std::uint8_t> label{0};
    char name[24] = {};
    int tid = 0;
#if defined(__linux__)
    clockid_t clock = 0;
    bool has_clock = false;
#endif
    std::uint32_t tally[kLabels] = {};
    std::uint32_t samples = 0, stat_samples = 0, running = 0;
    std::uint32_t core[64] = {};
    double cpu_last_ms = 0.0;
    std::vector<std::pair<std::string, std::uint32_t>> sleeps;  // what the kernel says a sleeping thread waits in (wchan and system call)
#if defined(_WIN32)
    HANDLE handle = nullptr;                         // PS2_PROF_PC: the thread, for reading its instruction pointer
    std::unordered_map<std::uint64_t, std::uint32_t> pcs;  // instruction pointer (offset in the exe) -> samples while not in a wait label
    std::unordered_map<std::uint64_t, std::uint32_t> callers;  // the word at the stack pointer (the return address in a leaf function such as memcpy)
#endif
};

Slot g_slots[kMaxSlots];
std::atomic<int> g_count{0};

std::atomic<std::int64_t> g_last_ns{0};
std::atomic<std::uint64_t> g_frames{0};
std::atomic<std::uint32_t> g_delta_us[1024];
std::atomic<std::uint64_t> g_delta_n{0};
std::mutex g_note_mutex;
std::string g_note;
struct Counter {
    const char *name;
    const std::atomic<std::uint64_t> *value;
    double scale;
    std::uint64_t last;
};
Counter g_counters[16];
std::atomic<int> g_counter_count{0};

#if defined(__linux__)
double thread_cpu_ms(const Slot &s) {
    if (!s.has_clock) return 0.0;
    timespec t{};
    if (clock_gettime(s.clock, &t) != 0) return 0.0;
    return static_cast<double>(t.tv_sec) * 1000.0 + static_cast<double>(t.tv_nsec) / 1e6;
}

std::string slurp(const char *path, std::size_t max) {
    std::FILE *f = std::fopen(path, "r");
    if (!f) return {};
    char buf[160];
    const std::size_t n = std::fread(buf, 1, std::min(max, sizeof buf - 1), f);
    std::fclose(f);
    buf[n] = 0;
    std::string s(buf);
    while (!s.empty() && (s.back() == '\n' || s.back() == ' ')) s.pop_back();
    return s;
}

// a thread that is not running: the kernel function it sleeps in (wchan) and the system call it is in (the first number of 'syscall')
void note_sleep(Slot &s) {
    char path[64];
    std::snprintf(path, sizeof path, "/proc/self/task/%d/wchan", s.tid);
    std::string key = slurp(path, 60);
    std::snprintf(path, sizeof path, "/proc/self/task/%d/syscall", s.tid);
    const std::string sys = slurp(path, 24);
    if (!sys.empty()) key += " sys=" + sys.substr(0, sys.find(' '));
    if (key.empty()) key = "?";
    for (auto &e : s.sleeps)
        if (e.first == key) {
            ++e.second;
            return;
        }
    if (s.sleeps.size() < 24) s.sleeps.emplace_back(key, 1u);
}

// /proc/self/task/<tid>/stat: field 3 is the state (R running, S sleeping, D disk wait), field 39 the processor it last ran on
void read_stat(Slot &s) {
    char path[64];
    std::snprintf(path, sizeof path, "/proc/self/task/%d/stat", s.tid);
    std::FILE *f = std::fopen(path, "r");
    if (!f) return;
    char buf[640];
    const std::size_t n = std::fread(buf, 1, sizeof buf - 1, f);
    std::fclose(f);
    buf[n] = 0;
    const char *p = std::strrchr(buf, ')');
    if (!p || p[1] != ' ') return;
    p += 2;
    const char state = *p;
    if (state != 'R') note_sleep(s);
    int idx = 0;
    while (*p && idx < 36) {
        if (*p == ' ') ++idx;
        ++p;
    }
    const int cpu = std::atoi(p);
    ++s.stat_samples;
    if (state == 'R') ++s.running;
    if (cpu >= 0 && cpu < 64) ++s.core[cpu];
}

int read_freqs(int *mhz, int max) {
    int got = 0;
    for (int c = 0; c < max; ++c) {
        char path[96];
        std::snprintf(path, sizeof path, "/sys/devices/system/cpu/cpu%d/cpufreq/scaling_cur_freq", c);
        std::FILE *f = std::fopen(path, "r");
        if (!f) {
            mhz[c] = -1;
            continue;
        }
        long khz = 0;
        if (std::fscanf(f, "%ld", &khz) == 1) {
            mhz[c] = static_cast<int>(khz / 1000);
            ++got;
        } else {
            mhz[c] = -1;
        }
        std::fclose(f);
    }
    return got;
}
#endif

void print_second(double elapsed_s, std::uint64_t &last_delta_n, bool &freq_tried, bool &freq_ok) {
    const int n = std::min(g_count.load(), kMaxSlots);
    for (int i = 0; i < n; ++i) {
        Slot &s = g_slots[i];
        if (!s.samples) continue;
        double cpu_pct = 0.0;
#if defined(__linux__)
        const double now_ms = thread_cpu_ms(s);
        cpu_pct = (now_ms - s.cpu_last_ms) / (elapsed_s * 1000.0) * 100.0;
        s.cpu_last_ms = now_ms;
#endif
        const double run_pct = s.stat_samples ? 100.0 * s.running / s.stat_samples : 0.0;
        int order[kLabels];
        for (int k = 0; k < kLabels; ++k) order[k] = k;
        std::sort(order, order + kLabels, [&](int a, int b) { return s.tally[a] > s.tally[b]; });
        std::string labels;
        for (int k = 0; k < 6; ++k) {
            const double pct = 100.0 * s.tally[order[k]] / s.samples;
            if (pct < 1.5) break;
            char one[48];
            std::snprintf(one, sizeof one, "%s%s %.0f%%", labels.empty() ? "" : ", ", kNames[order[k]], pct);
            labels += one;
        }
        int corder[64];
        for (int k = 0; k < 64; ++k) corder[k] = k;
        std::sort(corder, corder + 64, [&](int a, int b) { return s.core[a] > s.core[b]; });
        std::string cores;
        for (int k = 0; k < 3; ++k) {
            if (!s.stat_samples || !s.core[corder[k]]) break;
            char one[24];
            std::snprintf(one, sizeof one, "%s%d:%.0f%%", cores.empty() ? "" : " ", corder[k], 100.0 * s.core[corder[k]] / s.stat_samples);
            cores += one;
        }
        std::fprintf(stderr, "[prof] %-13s cpu %3.0f%% running %3.0f%% | %s | cores %s\n", s.name, cpu_pct, run_pct, labels.c_str(), cores.c_str());
        if (!s.sleeps.empty() && s.stat_samples) {
            std::sort(s.sleeps.begin(), s.sleeps.end(), [](const auto &a, const auto &b) { return a.second > b.second; });
            std::string sl;
            for (std::size_t k = 0; k < s.sleeps.size() && k < 3; ++k) {
                const double pct = 100.0 * s.sleeps[k].second / s.stat_samples;
                if (pct < 3.0) break;
                char one[120];
                std::snprintf(one, sizeof one, "%s%s %.0f%%", sl.empty() ? "" : ", ", s.sleeps[k].first.c_str(), pct);
                sl += one;
            }
            if (!sl.empty() && s.stat_samples) std::fprintf(stderr, "[prof] %-13s   not running, in: %s\n", "", sl.c_str());
        }
        s.sleeps.clear();
        std::memset(s.tally, 0, sizeof s.tally);
        std::memset(s.core, 0, sizeof s.core);
        s.samples = s.stat_samples = s.running = 0;
    }
    // the pictures of this second
    const std::uint64_t total = g_delta_n.load(std::memory_order_acquire);
    std::vector<std::uint32_t> d;
    for (std::uint64_t k = std::max<std::uint64_t>(last_delta_n, total > 1024 ? total - 1024 : 0); k < total; ++k)
        d.push_back(g_delta_us[k % 1024].load(std::memory_order_relaxed));
    last_delta_n = total;
    if (!d.empty()) {
        std::sort(d.begin(), d.end());
        double sum = 0;
        int over22 = 0, over34 = 0, over50 = 0;
        for (std::uint32_t v : d) {
            sum += v;
            over22 += v > 22000;
            over34 += v > 34000;
            over50 += v > 50000;
        }
        std::fprintf(stderr, "[prof] frames %zu: ms min %.1f avg %.1f p50 %.1f p95 %.1f max %.1f | over 22 ms: %d, over 34 ms: %d, over 50 ms: %d (50 Hz needs 20.0)\n",
                     d.size(), d.front() / 1000.0, sum / static_cast<double>(d.size()) / 1000.0, d[d.size() / 2] / 1000.0, d[d.size() * 95 / 100] / 1000.0,
                     d.back() / 1000.0, over22, over34, over50);
    } else {
        std::fprintf(stderr, "[prof] frames 0 in this second\n");
    }
    {
        const int cn = std::min(g_counter_count.load(), 16);
        std::string line;
        for (int i = 0; i < cn; ++i) {
            Counter &c = g_counters[i];
            const std::uint64_t v = c.value->load(std::memory_order_relaxed);
            char one[64];
            std::snprintf(one, sizeof one, "%s%s %.1f", line.empty() ? "" : ", ", c.name, static_cast<double>(v - c.last) * c.scale / elapsed_s);
            line += one;
            c.last = v;
        }
        if (!line.empty()) std::fprintf(stderr, "[prof] per second: %s\n", line.c_str());
    }
    {
        std::lock_guard<std::mutex> lock(g_note_mutex);
        if (!g_note.empty()) std::fprintf(stderr, "[prof] device: %s\n", g_note.c_str());
    }
#if defined(__linux__)
    if (!freq_tried || freq_ok) {
        int mhz[16];
        const int got = read_freqs(mhz, 16);
        if (!freq_tried) {
            freq_tried = true;
            freq_ok = got > 0;
            if (!freq_ok) std::fprintf(stderr, "[prof] the processor frequencies cannot be read by this program\n");
        }
        if (freq_ok) {
            std::string line;
            for (int c = 0; c < 16; ++c) {
                if (mhz[c] < 0) continue;
                char one[24];
                std::snprintf(one, sizeof one, "%scpu%d %d", line.empty() ? "" : ", ", c, mhz[c]);
                line += one;
            }
            std::fprintf(stderr, "[prof] MHz now: %s\n", line.c_str());
        }
    }
#else
    (void)freq_tried;
    (void)freq_ok;
#endif
}

#if defined(_WIN32)
// PS2_PROF_PC=<file> (Windows): every millisecond the instruction pointer of each profiled thread that is working (not in a wait label) is
// read; every 5 s the file gets "thread offset samples" lines (offset from the exe's base, for llvm-symbolizer --adjust-vma or addr2line).
const std::string g_pc_file = [] {
    const char *v = std::getenv("PS2_PROF_PC");
    return std::string(v ? v : "");
}();
const std::uintptr_t g_base = reinterpret_cast<std::uintptr_t>(GetModuleHandleW(nullptr));

bool waiting_label(int l) {
    return l == kVuWait || l == kRenderWait || l == kPostWait || l == kTilesWait || l == kPresentWait || l == kLimiter || l == kQueueFull || l == kVuDrain || l == kIdle;
}

void sample_pc(Slot &s) {
    if (!s.handle || waiting_label(s.label.load(std::memory_order_relaxed))) return;
    if (SuspendThread(s.handle) == static_cast<DWORD>(-1)) return;
    CONTEXT c{};
    c.ContextFlags = CONTEXT_CONTROL;
    const bool ok = GetThreadContext(s.handle, &c) != 0;
    ResumeThread(s.handle);
    if (!ok) return;
    ++s.pcs[static_cast<std::uint64_t>(c.Rip)];
    ++s.callers[*reinterpret_cast<const std::uint64_t *>(c.Rsp)];
}

void write_pcs(int n) {
    std::FILE *f = std::fopen(g_pc_file.c_str(), "w");
    if (!f) return;
    for (int i = 0; i < n; ++i)
        for (const auto &e : g_slots[i].pcs) {
            // inside the exe: the offset from its base; elsewhere: the module's file name and the offset in it
            HMODULE m = nullptr;
            char mod[MAX_PATH] = "?";
            std::uintptr_t base = 0;
            if (GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT, reinterpret_cast<LPCSTR>(e.first), &m) && m) {
                base = reinterpret_cast<std::uintptr_t>(m);
                if (base == g_base) {
                    std::strcpy(mod, "exe");
                } else {
                    char full[MAX_PATH];
                    if (GetModuleFileNameA(m, full, MAX_PATH)) {
                        const char *slash = std::strrchr(full, '\\');
                        std::snprintf(mod, sizeof mod, "%s", slash ? slash + 1 : full);
                    }
                }
            }
            std::fprintf(f, "%s %s %llx %u\n", g_slots[i].name, mod, static_cast<unsigned long long>(e.first - base), e.second);
        }
    for (int i = 0; i < n; ++i)
        for (const auto &e : g_slots[i].callers)
            if (e.first >= g_base && e.first < g_base + 0x10000000u) std::fprintf(f, "%s stack %llx %u\n", g_slots[i].name, static_cast<unsigned long long>(e.first - g_base), e.second);
    std::fclose(f);
}
#endif

void sampler() {
    using Clock = std::chrono::steady_clock;
    std::fprintf(stderr, "[prof] PS2_PROF is on: once a second, per thread: CPU of the thread's own clock, how much of the time it was running (not sleeping), "
                         "which labels it was in, which processors it ran on; then the picture times and the processor frequencies\n");
    auto next = Clock::now(), last_print = next;
    std::uint64_t tick = 0, last_delta_n = 0;
    bool freq_tried = false, freq_ok = false;
    for (;;) {
        next += std::chrono::milliseconds(1);
        std::this_thread::sleep_until(next);
        const auto now = Clock::now();
        if (now > next + std::chrono::milliseconds(20)) next = now;  // fell far behind: do not catch up with a burst
        const int n = std::min(g_count.load(), kMaxSlots);
        ++tick;
        for (int i = 0; i < n; ++i) {
            Slot &s = g_slots[i];
            ++s.tally[std::min<int>(s.label.load(std::memory_order_relaxed), kLabels - 1)];
            ++s.samples;
#if defined(__linux__)
            if (tick % 4 == 0) read_stat(s);
#endif
#if defined(_WIN32)
            if (!g_pc_file.empty()) sample_pc(s);
#endif
        }
#if defined(_WIN32)
        if (!g_pc_file.empty() && tick % 5000 == 0) write_pcs(n);
#endif
        if (now - last_print >= std::chrono::seconds(1)) {
            print_second(std::chrono::duration<double>(now - last_print).count(), last_delta_n, freq_tried, freq_ok);
            last_print = now;
        }
    }
}

}  // namespace

namespace {
thread_local bool t_seen = false;
std::atomic<int> g_tids[16];
std::atomic<int> g_tid_count{0};
}  // namespace

#if defined(__linux__) && (defined(__aarch64__) || defined(__x86_64__))
// PS2_PC_SAMPLE=<seconds>: where the processor is, by instruction address (a phone has no perf): a CPU-time timer signals the running threads
// every millisecond, the handler notes the interrupted instruction's address and thread, and every <seconds> a thread prints, per thread, the dearest
// addresses as module + offset (the live-compiled vector programs are libraries of the cache: pull one and objdump it at the offset).
// "[pcs] <thread> <module> +<offset> <samples> <symbol>"; PS2_PC_SAMPLE_TOP=<n> lines per thread (default 80).
namespace {
struct PcSample {
    std::uintptr_t pc;
    int tid;
};
constexpr std::size_t kPcMax = 1u << 21;
PcSample *g_pc_buf = nullptr;
std::atomic<std::size_t> g_pc_n{0};
std::mutex g_pc_names_mutex;
std::map<int, std::string> g_pc_names;  // thread id -> its name (register_thread)

void pc_handler(int, siginfo_t *, void *context) {
    const auto *uc = static_cast<const ucontext_t *>(context);
#if defined(__aarch64__)
    const std::uintptr_t pc = static_cast<std::uintptr_t>(uc->uc_mcontext.pc);
#else
    const std::uintptr_t pc = static_cast<std::uintptr_t>(uc->uc_mcontext.gregs[REG_RIP]);
#endif
    const std::size_t i = g_pc_n.fetch_add(1, std::memory_order_relaxed);
    if (i < kPcMax) g_pc_buf[i] = {pc, static_cast<int>(syscall(SYS_gettid))};
}

void pc_report(unsigned seconds, unsigned top) {
    for (;;) {
        std::this_thread::sleep_for(std::chrono::seconds(seconds));
        const std::size_t n = std::min(g_pc_n.exchange(0), kPcMax);
        std::vector<PcSample> copy(g_pc_buf, g_pc_buf + n);
        std::map<int, std::map<std::uintptr_t, std::uint32_t>> by_thread;
        for (const PcSample &s : copy) ++by_thread[s.tid][s.pc];
        for (const auto &[tid, pcs] : by_thread) {
            std::uint32_t total = 0;
            for (const auto &e : pcs) total += e.second;
            std::string name_text = "?";
            {
                std::lock_guard<std::mutex> lock(g_pc_names_mutex);
                if (const auto it = g_pc_names.find(tid); it != g_pc_names.end()) name_text = it->second;
            }
            for (char &c : name_text)
                if (c == ' ') c = '_';
            const char *name = name_text.c_str();
            std::fprintf(stderr, "[pcs] thread %s (tid %d): %u samples in %u s\n", name, tid, total, seconds);
            std::vector<std::pair<std::uint32_t, std::uintptr_t>> order;
            for (const auto &e : pcs) order.push_back({e.second, e.first});
            std::sort(order.rbegin(), order.rend());
            for (std::size_t k = 0; k < order.size() && k < top; ++k) {
                Dl_info info{};
                const bool ok = dladdr(reinterpret_cast<void *>(order[k].second), &info) && info.dli_fname;
                const char *file = ok ? std::strrchr(info.dli_fname, '/') : nullptr;
                std::fprintf(stderr, "[pcs] %s %s +%llx %u %s\n", name, ok ? (file ? file + 1 : info.dli_fname) : "?",
                             static_cast<unsigned long long>(ok ? order[k].second - reinterpret_cast<std::uintptr_t>(info.dli_fbase) : order[k].second), order[k].first,
                             ok && info.dli_sname ? info.dli_sname : "");
            }
        }
    }
}

const bool g_pc_sample = [] {
    const char *v = std::getenv("PS2_PC_SAMPLE");
    const int seconds = v ? std::atoi(v) : 0;
    if (seconds <= 0) return false;
    const char *t = std::getenv("PS2_PC_SAMPLE_TOP");
    const unsigned top = t && std::atoi(t) > 0 ? static_cast<unsigned>(std::atoi(t)) : 80u;
    g_pc_buf = new PcSample[kPcMax];
    struct sigaction sa {};
    sa.sa_sigaction = pc_handler;
    sa.sa_flags = SA_SIGINFO | SA_RESTART;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGPROF, &sa, nullptr);
    std::thread(pc_report, static_cast<unsigned>(seconds), top).detach();
    return true;
}();
}  // namespace

// The calling thread's own CPU-time timer (a process-wide one signals whichever thread the kernel picks, and starves the busy ones).
void pc_thread_timer(const char *name) {
    if (!g_pc_sample) return;
    {
        std::lock_guard<std::mutex> lock(g_pc_names_mutex);
        g_pc_names[static_cast<int>(syscall(SYS_gettid))] = name ? name : "?";
    }
    clockid_t cid;
    if (pthread_getcpuclockid(pthread_self(), &cid) != 0) return;
    sigevent sev{};
    sev.sigev_notify = SIGEV_THREAD_ID;
    sev.sigev_signo = SIGPROF;
    sev._sigev_un._tid = static_cast<int>(syscall(SYS_gettid));
    timer_t timer;
    if (timer_create(cid, &sev, &timer) != 0) return;
    itimerspec spec{};
    spec.it_interval.tv_nsec = 1000000;
    spec.it_value.tv_nsec = 1000000;
    timer_settime(timer, 0, &spec, nullptr);
}
#endif

int thread_ids(int *out, int max) {
    const int n = std::min(std::min(g_tid_count.load(), 16), max);
    for (int i = 0; i < n; ++i) out[i] = g_tids[i].load();
    return n;
}

void register_thread(const char *name) {
#if defined(__linux__)
    if (!t_seen) {
        t_seen = true;
#if defined(__aarch64__) || defined(__x86_64__)
        pc_thread_timer(name);
#endif
        // the heavy threads only: the log copy, the watchdog and the sound feeder sleep nearly all the time
        const bool heavy = name && std::strstr(name, "log") == nullptr && std::strstr(name, "watchdog") == nullptr && std::strstr(name, "sound") == nullptr;
        if (heavy) {
            const int i = g_tid_count.fetch_add(1);
            if (i < 16) g_tids[i].store(static_cast<int>(syscall(SYS_gettid)));
            if (std::getenv("PS2_THREAD_LOG")) {
                static const auto start = std::chrono::steady_clock::now();
                std::fprintf(stderr, "[threads] #%d \"%s\" tid %ld at +%.1f s\n", i, name ? name : "?", static_cast<long>(syscall(SYS_gettid)),
                             std::chrono::duration<double>(std::chrono::steady_clock::now() - start).count());
            }
            else g_tid_count.store(16);
        }
    }
#endif
    if (!g_on || t_slot) return;
    const int idx = g_count.fetch_add(1);
    if (idx >= kMaxSlots) {
        g_count.store(kMaxSlots);
        return;
    }
    Slot &s = g_slots[idx];
    std::snprintf(s.name, sizeof s.name, "%s", name && std::strncmp(name, "ps2 ", 4) == 0 ? name + 4 : (name ? name : "?"));
#if defined(__linux__)
    s.tid = static_cast<int>(syscall(SYS_gettid));
    s.has_clock = pthread_getcpuclockid(pthread_self(), &s.clock) == 0;
    s.cpu_last_ms = thread_cpu_ms(s);
#endif
#if defined(_WIN32)
    if (!g_pc_file.empty())
        s.handle = OpenThread(THREAD_SUSPEND_RESUME | THREAD_GET_CONTEXT | THREAD_QUERY_INFORMATION, FALSE, GetCurrentThreadId());
#endif
    t_slot = &s.label;
    static std::atomic<bool> started{false};
    if (!started.exchange(true)) std::thread(sampler).detach();
}

void add_counter(const char *name, const std::atomic<std::uint64_t> *value, double scale) {
    if (!g_on) return;
    const int i = g_counter_count.fetch_add(1);
    if (i >= 16) {
        g_counter_count.store(16);
        return;
    }
    g_counters[i] = Counter{name, value, scale, value->load(std::memory_order_relaxed)};
}

void set_note(const char *text) {
    if (!g_on) return;
    std::lock_guard<std::mutex> lock(g_note_mutex);
    g_note = text ? text : "";
}

void frame_mark() {
    if (!g_on) return;
    const std::int64_t now = std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
    const std::int64_t last = g_last_ns.exchange(now);
    g_frames.fetch_add(1);
    if (!last) return;
    const std::uint64_t k = g_delta_n.load(std::memory_order_relaxed);
    g_delta_us[k % 1024].store(static_cast<std::uint32_t>((now - last) / 1000), std::memory_order_relaxed);
    g_delta_n.store(k + 1, std::memory_order_release);
}

}  // namespace ps2::prof
