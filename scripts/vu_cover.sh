#!/usr/bin/env bash
# Ahead-of-time VU1/VU0 program coverage for a game (written for Crazy Frog Racer 2, works for any game whose VU1 microcode is
# one image that the game uploads once).
#
#   scripts/vu_cover.sh <game> [static|merge|build|check]...      (default: static merge build)
#
#   static  writes the entry points the image's structure names into games/<game>/vu1_all as synthetic recordings
#   merge   copies the recordings of runs (games/<game>/vu1, any extra folders in $VU_EXTRA) into games/<game>/vu1_all
#   build   vurecomp games/<game>/vu1_all -> generated/<game>_vu_all (a copy of generated/<game> with the vu1_*.cpp replaced)
#   check   runs the compiled set on a save state ($STATE) with PS2_VU_COUNT=1 and prints the interpreted entries
#
# Why a folder of 16 KiB files is enough: for CFR2 all recorded images are byte-identical (md5 equal): the game uploads ONE 16 KiB
# microcode image (it is the ELF's data at file offset 0xD3900 and stays resident), and every "program" is an entry address into
# it. vurecomp recomputes the key from (image, entry) (program_key_moved over the code reachable from the entry), the key in a file
# name is a label only, so any image copy named <anything 16 hex>_<entry>.bin makes that entry compiled.
# The entries of such an image are found from its structure (all 15 programs that play recorded are in the list, plus one that is
# the same code as another):
#   - address 0 and every pair after a run of all-zero pairs (the padding between programs)   -> MSCAL targets
#   - every XGKICK pair + 0x10 and every pair with the E bit + 0x10                            -> MSCNT resume points
# Register-indirect jumps (JR/JALR) are followed by vurecomp itself ("N of them jump targets") and by the runtime when recording.
#
# Dynamic check/recording of what the static list may have missed (new files are written only for entries that are not compiled in):
#   PS2_VU_RECORD=<dir> PS2_VU0_RECORD=<dir0> PS2_VU_COUNT=1 build/native-vuall/ps2run.exe games/<g>/disc/<ELF> --disc games/<g>/disc.iso \
#       --game-dir games/<g> [--load-state file] --frames N --press frame:button ...
# then re-run this script with VU_EXTRA="<dir> ..." and `merge build`.
set -euo pipefail
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd); ROOT=$(cd "$here/.." && pwd); cd "$ROOT"
game=${1:?usage: vu_cover.sh <game> [steps]}; shift
steps=("$@"); [ ${#steps[@]} -gt 0 ] || steps=(static merge build)
py=${PYTHON:-python}
all=games/$game/vu1_all; all0=games/$game/vu0_all; gen=generated/$game; out=generated/${game}_vu_all
for step in "${steps[@]}"; do
  case $step in
    merge)
      mkdir -p "$all" "$all0"
      cp -n games/$game/vu1/*.bin "$all"/ 2> /dev/null || true
      cp -n games/$game/vu0/*.bin "$all0"/ 2> /dev/null || true
      for d in ${VU_EXTRA:-}; do cp -n "$d"/*.bin "$all"/ 2> /dev/null || true; done
      for d in ${VU0_EXTRA:-}; do cp -n "$d"/*.bin "$all0"/ 2> /dev/null || true; done ;;
    static)
      mkdir -p "$all"
      img=$(ls "$all"/*.bin games/$game/vu1/*.bin 2> /dev/null | head -1)
      [ -n "$img" ] || { echo "no recorded image to start from: record one run first (PS2_VU_RECORD)"; exit 1; }
      "$py" -I - "$img" "$all" << 'EOF'
import struct, sys
img = open(sys.argv[1], 'rb').read(); out = sys.argv[2]
P = [struct.unpack_from('<II', img, pc) for pc in range(0, 16384, 8)]
cand = {0}
for i, p in enumerate(P):
    if p == (0, 0) and i + 1 < len(P) and P[i + 1] != (0, 0): cand.add((i + 1) * 8)
    if (p[0] & 0xFFE007FF) == 0x4A0007FF or p[0] == 0x4A0F7800 or (p[1] & 0x40000000): cand.add(i * 8 + 16)
for e in sorted(cand):
    if e < 16384: open('%s/AAAAAAAAAAAAAAAA_%04X.bin' % (out, e), 'wb').write(img)
print(len(cand), 'static entries:', ' '.join('%X' % e for e in sorted(cand)))
EOF
      ;;
    build)
      tools=build/tools
      rm -rf "$out"; cp -r "$gen" "$out"; rm -f "$out"/vu1_*.cpp "$out"/vu0_*.cpp
      "$tools/vurecomp" "$all" "$out" --vu0-dir "$all0"
      echo "now: cmake -S . -B build/native-vuall -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_COMPILER=<clang++> -DPS2_GENERATED=$ROOT/$out && ninja -C build/native-vuall ps2run" ;;
    check)
      PS2_VU_COUNT=1 build/native-vuall/ps2run games/$game/disc/*.[A-Z][A-Z][A-Z]* --disc games/$game/disc.iso --game-dir games/$game \
        ${STATE:+--load-state "$STATE"} --frames "${FRAMES:-3600}" 2>&1 | grep -a '\[vu1\] compiled\|\[vu\] compiled' | tail -5 ;;
    *) echo "unknown step $step"; exit 1 ;;
  esac
done
