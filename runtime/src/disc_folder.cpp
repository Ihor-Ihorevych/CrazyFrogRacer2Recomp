#include "disc_folder.hpp"

#include "ps2/disc.hpp"
#include "ps2/os.hpp"

#include <algorithm>
#include <array>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

namespace ps2::disc {

namespace {

struct Span {
    std::uint64_t disk_offset;  // start byte offset on the virtual ISO (sector * 2048)
    std::uint64_t length;       // valid byte length of this span
    std::uint64_t file_offset;  // byte offset inside the target host file
    std::uint32_t file_id;      // index into VirtualDisc::files
};

struct VirtualDisc {
    static constexpr std::size_t kCacheSlots = 16;

    struct CacheSlot {
        std::uint32_t file_id = UINT32_MAX;
        std::uint64_t last_tick = 0;
        std::FILE *fp = nullptr;
    };

    static constexpr std::uint32_t kMemoryMeta = UINT32_MAX - 1;  // file id of the in-memory sectors (a plain folder's file system)

    std::vector<std::string> files;
    std::vector<std::uint8_t> meta;
    std::vector<Span> spans;
    std::uint64_t total_sectors = 0;
    std::uint32_t layer1_sector = 0;

    std::mutex lock;
    std::array<CacheSlot, kCacheSlots> cache{};
    std::uint64_t tick = 0;

    ~VirtualDisc() {
        for (CacheSlot &slot : cache) {
            if (slot.fp) {
                std::fclose(slot.fp);
                slot.fp = nullptr;
            }
        }
    }

    std::FILE *acquire_file(std::uint32_t file_id) {
        if (file_id >= files.size()) return nullptr;
        ++tick;
        for (CacheSlot &slot : cache) {
            if (slot.file_id == file_id && slot.fp) {
                slot.last_tick = tick;
                return slot.fp;
            }
        }
        std::size_t victim = 0;
        for (std::size_t i = 0; i < kCacheSlots; ++i) {
            if (!cache[i].fp) {
                victim = i;
                break;
            }
            if (cache[i].last_tick < cache[victim].last_tick) victim = i;
        }
        if (cache[victim].fp) {
            std::fclose(cache[victim].fp);
            cache[victim].fp = nullptr;
            cache[victim].file_id = UINT32_MAX;
        }
        std::FILE *fp = os::open(files[file_id], "rb");
        if (!fp) {
            std::fprintf(stderr, "[disc-folder] cannot open extracted file %s\n", files[file_id].c_str());
            return nullptr;
        }
        cache[victim].file_id = file_id;
        cache[victim].last_tick = tick;
        cache[victim].fp = fp;
        return fp;
    }

    std::size_t read_bytes(std::uint64_t offset, void *out, std::size_t bytes) {
        const std::uint64_t total_bytes = total_sectors * kSector;
        if (offset >= total_bytes || bytes == 0) return 0;
        const std::size_t clamped = static_cast<std::size_t>(std::min<std::uint64_t>(bytes, total_bytes - offset));
        std::uint8_t *dst = static_cast<std::uint8_t *>(out);
        std::memset(dst, 0, clamped);

        const std::uint64_t req_start = offset;
        const std::uint64_t req_end = offset + clamped;

        // Binary search for the first span whose end (disk_offset + length) > req_start.
        auto it = std::upper_bound(spans.begin(), spans.end(), req_start, [](std::uint64_t pos, const Span &s) {
            return pos < s.disk_offset + s.length;
        });

        std::lock_guard<std::mutex> guard(lock);
        for (; it != spans.end() && it->disk_offset < req_end; ++it) {
            const std::uint64_t ov_start = std::max(req_start, it->disk_offset);
            const std::uint64_t ov_end = std::min(req_end, it->disk_offset + it->length);
            if (ov_end <= ov_start) continue;

            const std::uint64_t file_pos = it->file_offset + (ov_start - it->disk_offset);
            const std::size_t chunk_len = static_cast<std::size_t>(ov_end - ov_start);
            std::uint8_t *chunk_dst = dst + (ov_start - req_start);

            if (it->file_id == kMemoryMeta) {  // the file system's own sectors of a plain folder, made in memory
                if (file_pos < meta.size()) std::memcpy(chunk_dst, meta.data() + file_pos, std::min<std::size_t>(chunk_len, meta.size() - file_pos));
                continue;
            }
            std::FILE *fp = acquire_file(it->file_id);
            if (!fp) continue;

#if defined(_WIN32)
            if (_fseeki64(fp, static_cast<long long>(file_pos), SEEK_SET) != 0) continue;
#else
            if (fseeko(fp, static_cast<off_t>(file_pos), SEEK_SET) != 0) continue;
#endif
            (void)std::fread(chunk_dst, 1, chunk_len, fp);
        }
        return clamped;
    }
};

}

namespace {

// ---- A plain folder: the files of a disc copied out as they are (SYSTEM.CNF, the boot program, the archives ...) ----
// The disc's file system is made here, in memory: ISO 9660 level 1 with one primary volume descriptor at sector 16, the directories
// next to it and the files one after the other, each starting on a sector. Only what the runtime reads is written (no path tables).
// A game that finds its files by name works; one that has sector numbers of the real disc written into its code does not: for it
// the image (an ISO, or a BIN with its CUE sheet) is the way.

struct Node {
    std::string iso;               // the name in the directory record: upper case, ";1" after a file's
    std::filesystem::path host;    // the file or folder itself
    bool directory = false;
    std::uint64_t size = 0;
    std::vector<Node> kids;
    std::uint32_t lba = 0, sectors = 0;
};

std::string upper(std::string s) {
    for (char &c : s) c = static_cast<char>(std::toupper(static_cast<unsigned char>(c)));
    return s;
}

std::size_t record_length(const Node &n) { return (33 + n.iso.size() + 1) & ~std::size_t{1}; }

bool scan(Node &dir, int depth, std::size_t &count) {
    if (depth > 16) return true;
    std::error_code ec;
    for (const auto &entry : std::filesystem::directory_iterator(dir.host, ec)) {
        const std::string name = os::utf8(entry.path().filename());
        if (name.empty() || name.size() > 200 || name[0] == '.') continue;
        Node n;
        n.host = entry.path();
        n.directory = entry.is_directory(ec);
        if (!n.directory && !entry.is_regular_file(ec)) continue;
        n.iso = upper(name) + (n.directory ? "" : ";1");
        if (n.directory) {
            if (!scan(n, depth + 1, count)) return false;
        } else {
            n.size = entry.file_size(ec);
            if (ec || n.size >= 0xFFFFFFFFull) continue;
            ++count;
        }
        dir.kids.push_back(std::move(n));
    }
    std::sort(dir.kids.begin(), dir.kids.end(), [](const Node &a, const Node &b) { return a.iso < b.iso; });
    return true;
}

// the sectors a directory's records take: a record never crosses a sector's end
std::uint32_t directory_sectors(const Node &d) {
    std::size_t used = 68;  // "." and ".."
    std::uint32_t sectors = 1;
    for (const Node &k : d.kids) {
        const std::size_t len = record_length(k);
        if (used + len > kSector) {
            ++sectors;
            used = 0;
        }
        used += len;
    }
    return sectors;
}

template <typename T> void put_both(std::vector<std::uint8_t> &v, std::size_t at, T value) {
    std::memcpy(v.data() + at, &value, sizeof value);
    for (std::size_t i = 0; i < sizeof value; ++i) v[at + sizeof value + i] = reinterpret_cast<const std::uint8_t *>(&value)[sizeof value - 1 - i];
}

std::size_t put_record(std::vector<std::uint8_t> &v, std::size_t at, std::uint32_t lba, std::uint32_t size, bool dir, const std::string &name) {
    const std::size_t len = (33 + name.size() + 1) & ~std::size_t{1};
    v[at] = static_cast<std::uint8_t>(len);
    put_both<std::uint32_t>(v, at + 2, lba);
    put_both<std::uint32_t>(v, at + 10, size);
    v[at + 18] = 126; v[at + 19] = 1; v[at + 20] = 1;  // 2026-01-01 (nothing reads it)
    v[at + 25] = dir ? 2 : 0;
    put_both<std::uint16_t>(v, at + 28, 1);
    v[at + 32] = static_cast<std::uint8_t>(name.size());
    std::memcpy(v.data() + at + 33, name.data(), name.size());
    return at + len;
}

bool open_plain_folder(const std::filesystem::path &base_dir, const std::string &path) {
    std::error_code ec;
    bool has_cnf = false;
    for (const auto &entry : std::filesystem::directory_iterator(base_dir, ec))
        if (upper(os::utf8(entry.path().filename())) == "SYSTEM.CNF") has_cnf = true;
    if (!has_cnf) return false;

    Node root;
    root.host = base_dir;
    root.directory = true;
    std::size_t files = 0;
    scan(root, 0, files);
    if (!files) return false;

    // directories first, breadth first, from sector 18 (16 is the volume descriptor, 17 the end of the descriptors)
    std::vector<Node *> dirs{&root};
    std::uint32_t cursor = 18;
    for (std::size_t i = 0; i < dirs.size(); ++i) {
        dirs[i]->sectors = directory_sectors(*dirs[i]);
        dirs[i]->lba = cursor;
        cursor += dirs[i]->sectors;
        for (Node &k : dirs[i]->kids)
            if (k.directory) dirs.push_back(&k);
    }
    const std::uint32_t files_start = cursor;
    std::vector<Node *> all_files;
    std::vector<Node *> walk{&root};
    for (std::size_t i = 0; i < walk.size(); ++i)
        for (Node &k : walk[i]->kids) {
            if (k.directory) {
                walk.push_back(&k);
                continue;
            }
            k.lba = cursor;
            k.sectors = static_cast<std::uint32_t>((k.size + kSector - 1) / kSector);
            cursor += k.sectors;
            all_files.push_back(&k);
        }
    const std::uint32_t total = cursor;

    auto vdisc = std::make_shared<VirtualDisc>();
    vdisc->meta.assign(static_cast<std::size_t>(files_start - 16) * kSector, 0);
    std::vector<std::uint8_t> &m = vdisc->meta;
    {   // the primary volume descriptor and the terminator
        std::uint8_t *pvd = m.data();
        std::memcpy(pvd, "\x01" "CD001\x01", 7);
        std::memset(pvd + 8, ' ', 64);
        std::memcpy(pvd + 40, "PS2DISC", 7);
        put_both<std::uint32_t>(m, 80, total);
        put_both<std::uint16_t>(m, 120, 1);
        put_both<std::uint16_t>(m, 124, 1);
        put_both<std::uint16_t>(m, 128, kSector);
        put_record(m, 156, root.lba, root.sectors * kSector, true, std::string(1, '\0'));
        std::memcpy(pvd + kSector, "\xFF" "CD001\x01", 7);
    }
    for (Node *d : dirs) {
        const std::size_t base = static_cast<std::size_t>(d->lba - 16) * kSector;
        std::size_t at = put_record(m, base, d->lba, d->sectors * kSector, true, std::string(1, '\0'));
        const Node *parent = nullptr;
        for (Node *p : dirs)
            for (const Node &k : p->kids)
                if (&k == d) parent = p;
        if (!parent) parent = d;
        at = put_record(m, at, parent->lba, parent->sectors * kSector, true, std::string(1, '\1'));
        std::size_t used = at - base;
        std::size_t sector_base = base;
        for (const Node &k : d->kids) {
            const std::size_t len = record_length(k);
            if (used + len > kSector) {
                sector_base += kSector;
                at = sector_base;
                used = 0;
            }
            at = put_record(m, at, k.lba, k.directory ? k.sectors * kSector : static_cast<std::uint32_t>(k.size), k.directory, k.iso);
            used += len;
        }
    }

    vdisc->total_sectors = total;
    vdisc->spans.push_back(Span{16ull * kSector, m.size(), 0, VirtualDisc::kMemoryMeta});
    for (Node *f : all_files) {
        if (!f->size) continue;
        const std::uint32_t id = static_cast<std::uint32_t>(vdisc->files.size());
        vdisc->files.push_back(os::utf8(f->host));
        vdisc->spans.push_back(Span{static_cast<std::uint64_t>(f->lba) * kSector, f->size, 0, id});
    }
    std::sort(vdisc->spans.begin(), vdisc->spans.end(), [](const Span &a, const Span &b) { return a.disk_offset < b.disk_offset; });
    std::fprintf(stderr, "[disc-folder] plain folder %s: %zu files, %u virtual sectors (the file system is made in memory)\n",
                 base_dir.string().c_str(), all_files.size(), total);
    return open(path, static_cast<std::uint64_t>(total) * kSector, [vdisc](std::uint64_t offset, void *out, std::size_t bytes) -> std::size_t {
        return vdisc->read_bytes(offset, out, bytes);
    });
}

}  // namespace

bool open_folder(const std::string &path) {
    std::error_code ec;
    const std::filesystem::path fs_path = os::path(path);
    std::filesystem::path base_dir;
    std::filesystem::path manifest_path;

    if (std::filesystem::is_directory(fs_path, ec)) {
        base_dir = fs_path;
        manifest_path = base_dir / "disc_manifest.tsv";
    } else if (fs_path.filename() == "disc_manifest.tsv" && std::filesystem::is_regular_file(fs_path, ec)) {
        manifest_path = fs_path;
        base_dir = fs_path.parent_path();
    } else {
        return false;
    }

    if (!std::filesystem::is_regular_file(manifest_path, ec)) return std::filesystem::is_directory(base_dir, ec) && open_plain_folder(base_dir, path);

    std::ifstream in(manifest_path);
    if (!in) return false;

    auto vdisc = std::make_shared<VirtualDisc>();
    // File ID 0 is always disc_meta.bin (system/PVD/directory metadata sectors).
    vdisc->files.push_back((base_dir / "disc_meta.bin").string());

    std::string line;
    std::size_t extracted_files = 0;
    while (std::getline(in, line)) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        if (line.empty() || line[0] == '#') continue;

        std::istringstream ss(line);
        std::string kind;
        if (!(ss >> kind)) continue;

        if (kind == "TOTAL_SECTORS") {
            std::string l1_tag;
            ss >> vdisc->total_sectors >> l1_tag >> vdisc->layer1_sector;
        } else if (kind == "META") {
            std::uint64_t start_sector = 0, sector_count = 0, meta_offset = 0;
            if (ss >> start_sector >> sector_count >> meta_offset) {
                vdisc->spans.push_back(Span{
                    start_sector * kSector,
                    sector_count * kSector,
                    meta_offset,
                    0u
                });
            }
        } else if (kind == "FILE") {
            std::uint32_t layer = 0;
            std::uint64_t start_sector = 0, size_bytes = 0;
            std::string iso_path, local_rel;
            if (ss >> layer >> start_sector >> size_bytes >> iso_path >> local_rel) {
                const std::uint32_t file_id = static_cast<std::uint32_t>(vdisc->files.size());
                vdisc->files.push_back((base_dir / local_rel).string());
                if (size_bytes > 0) {
                    vdisc->spans.push_back(Span{
                        start_sector * kSector,
                        size_bytes,
                        0u,
                        file_id
                    });
                }
                ++extracted_files;
            }
        }
    }

    if (vdisc->total_sectors == 0 || vdisc->spans.empty()) {
        std::fprintf(stderr, "[disc-folder] %s: invalid or empty disc_manifest.tsv\n", manifest_path.string().c_str());
        return false;
    }

    std::sort(vdisc->spans.begin(), vdisc->spans.end(), [](const Span &a, const Span &b) {
        if (a.disk_offset != b.disk_offset) return a.disk_offset < b.disk_offset;
        return a.length > b.length;
    });

    // Deduplicate any aliased ISO file extents that start at the exact same sector.
    std::vector<Span> unique_spans;
    unique_spans.reserve(vdisc->spans.size());
    for (const Span &s : vdisc->spans) {
        if (!unique_spans.empty()) {
            const Span &prev = unique_spans.back();
            if (s.disk_offset < prev.disk_offset + prev.length) continue;
        }
        unique_spans.push_back(s);
    }
    vdisc->spans = std::move(unique_spans);

    const std::uint64_t total_bytes = vdisc->total_sectors * kSector;
    std::fprintf(stderr, "[disc-folder] mounted extracted disc directory %s (%zu extracted files, %llu virtual sectors)\n",
                 base_dir.string().c_str(), extracted_files, static_cast<unsigned long long>(vdisc->total_sectors));

    return open(path, total_bytes, [vdisc](std::uint64_t offset, void *out, std::size_t bytes) -> std::size_t {
        return vdisc->read_bytes(offset, out, bytes);
    });
}

}
