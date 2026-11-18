#!/usr/bin/env bash
# EE (R5900) ahead-of-time coverage procedure for a game that has to run with no live compiler (Android).
# Documented for cfr2; nothing here edits generated/cfr2, games/cfr2/entries.txt, runtime/ or host/.
#
#   scripts/ee_cover.sh translate     entries_ee_all.txt -> generated/cfr2_ee_all (copy VU files from generated/cfr2_vu_all first run)
#   scripts/ee_cover.sh build         configure (first time) + build build/native-eeall/ps2run
#   scripts/ee_cover.sh hardening     append stack-frame prologues that no entry covers to entries_ee_all.txt
#   scripts/ee_cover.sh sweep JOBS    run scripts/ee_cover/drive.py on a jobs json (2 runners, live compile off)
#   scripts/ee_cover.sh absorb FILES  add the addresses of misses_*.txt files to entries_ee_all.txt (the port.sh step-4 rule)
#
# Loop: sweep -> if any '[ee] halted: missing code' / misses_*.txt appears: absorb, translate, build, sweep again.
# jobs json: [[name, start_state|null, start_frame, total_frames, seed, policy('mix'|'confirm')], ...]; states are made with
#   ps2run ... --save-state <frame>:<path> (a path relative to the work dir; a /c/... path does not survive the colon).
# Cold boot reaches the title at ~3000 frames; start (frame ~3320) opens the menu (One player / Two players / Records /
# Options / Video box / Load data). One player: Championship, Single race, Time trials, Chase, Mini games (Dance Frog,
# Froggy Pinball). Races: hold cross, left/right, start = pause menu. Only the Football cup is open on a fresh card.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
. "$ROOT/../env.sh" 2>/dev/null || true
game=games/cfr2; gen=generated/cfr2_ee_all; bld=build/native-eeall; elf=$game/disc/SLES_545.49
CODE_END=4d77a0   # first data word of the ELF's single RWX segment (found by diffing a RAM dump against the file)
work=${EE_COVER_WORK:-$ROOT/../eecov}   # run.sh/drive.py work dir: st/ (states), shots/, logs/, misses_*.txt
case "${1:-}" in
  translate)
    [ -d "$gen" ] || { mkdir -p "$gen"; cp -r generated/cfr2_vu_all/. "$gen/"; }
    knobs=(); [ ! -f $game/aspect.txt ] || knobs=(--aspect-knobs $game/aspect.txt)
    build/tools/eerecomp.exe "$elf" --entries $game/entries_ee_all.txt "${knobs[@]}" --out "$gen" | tail -1 ;;
  build)
    if [ ! -f "$bld/build.ninja" ]; then
      cxx=$(grep '^CMAKE_CXX_COMPILER:STRING' build/native-vuall/CMakeCache.txt | cut -d= -f2)
      cmake -S . -B "$bld" -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_COMPILER="$cxx" -DPS2_GENERATED="$ROOT/$gen" \
        -DPS2_RUNTIME_LIB= -DPS2_WGPU_DIR="$ROOT/third_party/wgpu-native" -DSDL2_DIR="$ROOT/third_party/sdl2/lib/cmake/SDL2"
    fi
    ninja -C "$bld" ps2run ;;
  hardening)
    python scripts/ee_cover/prolog_entries.py "$elf" "$gen" $CODE_END >> $game/entries_ee_all.txt
    sort -u $game/entries_ee_all.txt -o $game/entries_ee_all.txt ;;
  sweep)
    mkdir -p "$work/st" "$work/shots" "$work/logs"
    cd "$work" && python "$ROOT/scripts/ee_cover/drive.py" "$2" ;;   # PS2EXE=<exe> overrides build/native-eeall/ps2run.exe
  absorb)
    shift
    cat "$@" | python -I -c "
import sys
known={l.strip() for l in open('$game/entries_ee_all.txt')}
new=sorted({l.strip() for l in sys.stdin if l.strip()}-known)
open('$game/entries_ee_all.txt','a').write(''.join(t+'\n' for t in new)); print(len(new),'new entries')" ;;
  *) sed -n 2,22p "$0"; exit 2 ;;
esac
