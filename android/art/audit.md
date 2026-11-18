# Crazy Frog Racer 2 - touch control audit

## Method

* Runtime pad log (`PS2_PAD_LOG=1`, `runtime/src/iop/sio2.cpp`) on the PC build: which pad mode the game negotiates.
* Behavioural test on `build/native-cfr2/ps2run.exe`: scripted a path from boot into a race (start/cross presses), saved a state at frame 3400
  (race under way), then re-ran the same 300 frames from that state with one extra `--press` per button (`--load-state`, `--press N:button:frames`,
  `--frame-at`) and compared the captured frames byte for byte against an un-pressed baseline (runs are deterministic: baseline == baseline).
  Done (a) in the race with the car idle, (b) in the race while holding Cross with a 30-frame hold and with repeated 6-frame taps of each button,
  (c) inside the pause menu. "DIFF" = the game reacted to that button in that context. "same" = no visible reaction **in that context only**.
* A web search gave no manual text for CFR2 (only the first game's FAQ), so there is no documented mapping; everything below is observed.
* The pause menu shows the hint "Confirm (X)" (frame 3200 of the start-spam run), which confirms the Cross glyph is the confirm button.

## Pad mode

Boot sequence (frames 2-69): digital -> config -> `0x44 set mode 01 03` (analog, **locked**) -> `0x4F pressure mode FF FF 03` ->
**analog with pressure (id 0x79), locked**, and it stays there for the whole run (a single negotiation, never changed in the race).
That is the stock libpad `padSetMainMode(DUALSHOCK, LOCK)` + `padSetButtonInfo` init sequence, so the game *requests* the pressure bytes, but
nothing observed proves it uses them (my tests cannot set per-button pressure; the host sends a constant full-scale value). The game *does* read the
left stick axes (stick-left changes the race frame, and in the pause menu stick-down produces the identical frame as D-pad-down).
Because the pad is locked in analog mode, the user's "Analog" toggle does nothing on a real pad either; the touch overlay should simply always
report analog mode data with the D-pad bits available as well.

## Verdicts

| Control | Verdict | Evidence | Default visibility | Settings-screen candidate |
|---|---|---|---|---|
| Cross | USED | Race: holding it changes the frames (accelerate). Menus: "Confirm (X)", moves through the pause menu. | Visible, big (GAS) | No |
| Square | USED | Race: DIFF both idle and while driving (brake/reverse). | Visible (BRK) | No |
| Circle | UNSURE | No visible effect in the race (idle, driving, taps) and none in the pause menu. Probably "use item/weapon" or "back" in sub-menus; untested because no pick-up occurred and no deeper menu was walked. | Visible, medium | Yes: first candidate to hide if the user wants a minimal layout |
| Triangle | UNSURE | Same as Circle: no effect in any context tested. Possibly look-back/horn/item. | Visible, medium | Yes |
| L1 | UNSURE | No effect in the race windows tested (idle, driving, taps). | Visible, small | Yes |
| R1 | USED | Race: DIFF after repeated taps while driving (hop/drift-style action; exact action not identified). | Visible | No |
| L2 | UNUSED (in tested contexts) | No effect in race (idle, driving, taps) or pause menu. | Hide by default | Yes |
| R2 | UNUSED (in tested contexts) | Same as L2. | Hide by default | Yes |
| Start | USED | Opens the Pause menu in a race. | Visible | No |
| Select | UNUSED (in tested contexts) | No effect in race, driving or pause menu. | Hide by default (or keep tiny) | Yes |
| Left stick | USED | Steering: stick-left changes race frames; pause menu navigation. Pad runs analog (locked) so the stick is genuinely read. | Visible (floating stick) | No |
| D-pad | USED | D-pad left steers in the race, up/down navigate the pause menu (up/down alone have no effect while driving). Menus need it, so keep the STICK/D-PAD toggle, or better: send the stick as analog and also emit D-pad bits for menu use. | Hidden as separate widget; reachable by the stick toggle | Yes (the toggle) |
| Right stick | UNUSED | The host's `--axis rx/ry` exists but there is no on-screen right stick; a racer with a chase camera has no reason for it. Not tested in-game (no CLI to hold rx/ry in my runs). | Not drawn | n/a |
| L3 / R3 | UNUSED | `l3`/`r3` presses: no effect in race idle or driving. | Not drawn | n/a |
| Pressure (L2/R2 analog etc.) | UNUSED/UNSURE | Pressure mode is enabled by libpad init, but no evidence it is read; analog throttle on Cross/R2 is not implemented in the overlay anyway. | n/a | n/a |

Caveats: "UNUSED" means no effect in the contexts listed above (a short stretch of one race and the pause menu), not a proof for every screen. Item
use probably only reacts when an item is held, and Circle/Triangle may be "back" in menus deeper than the pause menu, so keep Circle and Triangle until
someone plays a full race. The first-game FAQ (GameFAQs) is the place to cross-check if a mapping is needed.

## Recommendation

* Default on-screen set: GAS (Cross), BRK (Square), R1, Circle, Triangle, Start, floating stick (+ D-pad toggle).
* Default hidden, enabled from Settings: L1 (UNSURE, keep available), L2, R2, Select.
* Circle/Triangle can be hidden once a full-race test shows they are unused; until then keep them.

## Icon set (`art/svg/<name>.svg` -> `res/drawable/ic_<name>.xml`, 96x96, white, tintable)

| Icon (file) | Belongs to |
|---|---|
| ic_cross | Cross button (GAS / Confirm) |
| ic_square | Square button (BRK) |
| ic_circle | Circle button |
| ic_triangle | Triangle button |
| ic_l1, ic_l2, ic_r1, ic_r2 | Shoulder buttons L1, L2, R1, R2 |
| ic_start | Start button (pause) |
| ic_select | Select button |
| ic_dpad | D-pad / the STICK/D-PAD toggle in D-pad state |
| ic_stick_base | Floating stick base ring (also the toggle in stick state) |
| ic_stick_thumb | Floating stick thumb |
| ic_pause | Pause/menu button (overlay menu, not a PS2 button) |
| ic_settings | Settings gear |
| ic_eye, ic_eye_off | Show / hide overlay (visibility) |
| ic_gamepad | Controller / input settings entry |
| ic_touch | Touch-control settings entry (touch hand) |
| ic_fullscreen | Fullscreen / stretch display mode |
| ic_aspect | Aspect-ratio display mode |
| ic_resolution | Resolution / render-scale setting |
| ic_accelerate | Alternative glyph for the Cross/GAS button (pedal) |
| ic_brake | Alternative glyph for the Square/BRK button |

Regenerate drawables: `python android-spike/art/svg2vd.py` (reads `art/svg/`, writes `res/drawable/`); verified with
`aapt2 compile --dir android-spike/res -o out.zip` (build-tools 35.0.0). Preview: open `art/preview.html`.
