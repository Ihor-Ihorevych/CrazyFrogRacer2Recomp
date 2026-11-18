// The hardware renderer's drawing program (hwr.cpp, docs/hw-renderer-design.md 3.5). The corners come in clip space (hwr.cpp places the GS
// pixel x,y at the centre of the card's pixel: + 0.5) with w = 1, so colour, texture coordinates, Q and fog are interpolated linearly in
// screen space as the GS does. The fragment stage does what the GS does to one pixel before the blender (gs.wgsl, plot): texture read,
// texture function, fog, alpha test. Depth test, blending and the write masks are the card's own (pipeline state chosen by hwr.cpp).
//
// Specialised by override constants so that no pipeline carries code it does not use:
//   TEX   0 no texture, 1 a decoded texture through the sampler (bilinear / trilinear by the card, LOD from Q), 2 texel loads with the GS's
//         wrap modes (region clamp / repeat) and, for a target used as a texture, the GS's view of its bytes (CT24 / CT16 with TEXA)
//   ATEST 0 no alpha test, 1 drop the pixels that fail it, 2 drop the pixels that pass it (the second draw of a test whose failure still writes)
//DUAL_ENABLE

struct D {
    a: vec4<u32>,  // flags, modes (tfx 0-1, atst 2-4, wms 8-9, wmt 10-11, source view 12-13), aref, fog colour
    b: vec4<u32>,  // tw | th << 8 (log2), minu | maxu << 16, minv | maxv << 16, ta0 | ta1 << 8
    c: vec4<u32>,  // mipmap word (gpu.cpp make_mip word 0), levels - 1, FIX, a target source's card texels per GS texel across (f32 bits, 0 = 1)
    d: vec4<u32>,  // the same down (f32 bits)
}

@group(0) @binding(0) var<storage, read> draws: array<D>;
@group(1) @binding(0) var tex: texture_2d<f32>;
@group(1) @binding(1) var smp: sampler;
@group(1) @binding(2) var smp_clamp: sampler;   // bilinear, clamp to edge
@group(1) @binding(3) var smp_repeat: sampler;  // bilinear, repeat

override TEX: u32 = 0u;
override ATEST: u32 = 0u;

const F_TME: u32 = 1u;
const F_FGE: u32 = 2u;
const F_FST: u32 = 4u;
const F_TCC: u32 = 8u;
const F_BILINEAR: u32 = 16u;
const F_AEM: u32 = 32u;
const F_FBA: u32 = 64u;
const F_CT16OUT: u32 = 128u;
const F_MIP: u32 = 256u;
const F_ALPHA_FACTOR: u32 = 512u;
const F_PREMUL_FLOOR: u32 = 1024u;  // the blend is Cs * FIX (+ Cd): the shader gives (Cs * FIX) >> 7 as the GS does, the card adds it
const F_PREMUL_CEIL: u32 = 2048u;   // Cd - Cs * FIX: the GS's (-Cs * FIX) >> 7 rounds away from zero
// 16-bit target: the colour written is cut to 5 bits per channel (floor), or for Cd - x rounded up, so that the target holds what the GS
// stores (exact where the card only adds or takes the value away; blends with other factors keep 8 bits and are cut where read)
const F_Q16_FLOOR: u32 = 4096u;
const F_Q16_CEIL: u32 = 8192u;

struct VIn {
    @location(0) pos: vec2<f32>,
    @location(1) z: f32,
    @location(2) col: vec4<f32>,
    @location(3) stq: vec3<f32>,
    @location(4) fog: f32,
    @location(5) di: u32,
}

// The draw's parameters reach the fragment stage as flat values read once per corner (vs), not from the storage buffer at every pixel:
// tile GPUs (Mali) are slow at per-pixel buffer reads.
struct VOut {
    @builtin(position) pos: vec4<f32>,
    @location(0) col: vec4<f32>,
    @location(1) stq: vec3<f32>,
    @location(2) fog: f32,
    @location(3) @interpolate(flat) da: vec4<u32>,
    @location(4) @interpolate(flat) db: vec4<u32>,
    @location(5) @interpolate(flat) dc: vec4<u32>,
    @location(6) @interpolate(flat) dd: u32,
}

@vertex
fn vs(v: VIn) -> VOut {
    var o: VOut;
    o.pos = vec4<f32>(v.pos, v.z, 1.0);
    o.col = v.col;
    o.stq = v.stq;
    o.fog = v.fog;
    let d = draws[v.di];
    o.da = d.a;
    o.db = d.b;
    o.dc = d.c;
    o.dd = d.d.x;
    return o;
}

fn wrap_coord(v: i32, size: i32, m: u32, mn: u32, mx: u32) -> i32 {
    switch m {
        case 0u {
            return v & (size - 1);
        }
        case 1u {
            return clamp(v, 0, size - 1);
        }
        case 2u {
            return clamp(v, i32(mn), i32(mx));
        }
        default {
            return i32((u32(v) & mn) | mx);
        }
    }
}

// one texel as the GS would read it (0..255 per channel): the stored RGBA, or a target's bytes seen as CT24 (alpha from TEXA) or CT16
// (5 bits per channel and the A bit, expanded with TEXA)
fn fetch(d: D, x: i32, y: i32) -> vec4<f32> {
    let dim = vec2<i32>(textureDimensions(tex));
    let c = floor(textureLoad(tex, clamp(vec2<i32>(x, y), vec2<i32>(0, 0), dim - vec2<i32>(1, 1)), 0) * 255.0 + vec4<f32>(0.5));
    let view = (d.a.y >> 12u) & 3u;
    let ta0 = f32(d.b.w & 0xFFu);
    let ta1 = f32((d.b.w >> 8u) & 0xFFu);
    let aem = (d.a.x & F_AEM) != 0u;
    if (view == 1u) {
        var a = ta0;
        if (aem && c.x == 0.0 && c.y == 0.0 && c.z == 0.0) {
            a = 0.0;
        }
        return vec4<f32>(c.xyz, a);
    }
    if (view == 2u) {
        let rgb = floor(c.xyz / 8.0) * 8.0;
        var a = ta0;
        if (c.w >= 128.0) {
            a = ta1;
        } else if (aem && rgb.x == 0.0 && rgb.y == 0.0 && rgb.z == 0.0) {
            a = 0.0;
        }
        return vec4<f32>(rgb, a);
    }
    return c;
}

// the card texel of a source drawn at f texels per GS texel under GS texture position g: the GS texel is wrapped as the GS does, the place
// inside it kept
fn scaled_tap(g: f32, f: f32, size: i32, m: u32, mn: u32, mx: u32) -> i32 {
    let t = floor(g);
    let w = wrap_coord(i32(t), size, m, mn, mx);
    return i32(floor((f32(w) + (g - t)) * f));
}

fn sample_loads(d: D, u: f32, v: f32) -> vec4<f32> {
    let tw = d.b.x & 0xFFu;
    let th = (d.b.x >> 8u) & 0xFFu;
    let sw = i32(1u << tw);
    let sh = i32(1u << th);
    let mu = (d.a.y >> 8u) & 3u;
    let mv = (d.a.y >> 10u) & 3u;
    let minu = d.b.y & 0xFFFFu;
    let maxu = d.b.y >> 16u;
    let minv = d.b.z & 0xFFFFu;
    let maxv = d.b.z >> 16u;
    if (d.c.w != 0u) {  // a target drawn at another size than the GS's (PS2_HWR_SCALE): read its own texels (point: the one under the pixel;
                        // bilinear: between its texels)
        let fx = bitcast<f32>(d.c.w);
        let fy = bitcast<f32>(d.d.x);
        // Fast path (the display copy, the glow and blur passes): the target's own colours (no 24/16-bit view), wrapped by clamp or repeat
        // over the whole texture, filtered by the card in one read instead of four texel loads through the GS's wrap. The card's filter
        // weights have fewer bits than the GS's: a colour may differ by 1.
        let view = (d.a.y >> 12u) & 3u;
        let whole_u = mu == 1u || (mu == 2u && minu == 0u && maxu >= u32(sw) - 1u);
        let whole_v = mv == 1u || (mv == 2u && minv == 0u && maxv >= u32(sh) - 1u);
        let dim = vec2<f32>(textureDimensions(tex));
        let repeat_whole = mu == 0u && mv == 0u && abs(dim.x - round(f32(sw) * fx)) < 1.0 && abs(dim.y - round(f32(sh) * fy)) < 1.0;
        if (view == 0u && (d.a.x & F_BILINEAR) != 0u && ((whole_u && whole_v) || repeat_whole)) {
            let at = vec2<f32>(u * fx, v * fy) / dim;
            var c: vec4<f32>;
            if (mu == 0u) {
                c = textureSampleLevel(tex, smp_repeat, at, 0.0);
            } else {
                c = textureSampleLevel(tex, smp_clamp, at, 0.0);
            }
            return floor(c * 255.0 + vec4<f32>(0.5));
        }
        if ((d.a.x & F_BILINEAR) == 0u) {
            return fetch(d, scaled_tap(u, fx, sw, mu, minu, maxu), scaled_tap(v, fy, sh, mv, minv, maxv));
        }
        let hu = u * fx - 0.5;
        let hv = v * fy - 0.5;
        let hx = floor(hu);
        let hy = floor(hv);
        let xa = scaled_tap((hx + 0.5) / fx, fx, sw, mu, minu, maxu);
        let xb = scaled_tap((hx + 1.5) / fx, fx, sw, mu, minu, maxu);
        let ya = scaled_tap((hy + 0.5) / fy, fy, sh, mv, minv, maxv);
        let yb = scaled_tap((hy + 1.5) / fy, fy, sh, mv, minv, maxv);
        let top = mix(fetch(d, xa, ya), fetch(d, xb, ya), hu - hx);
        let bottom = mix(fetch(d, xa, yb), fetch(d, xb, yb), hu - hx);
        return floor(clamp(mix(top, bottom, hv - hy) + vec4<f32>(0.5), vec4<f32>(0.0), vec4<f32>(255.0)));
    }
    if ((d.a.x & F_BILINEAR) == 0u) {
        return fetch(d, wrap_coord(i32(floor(u)), sw, mu, minu, maxu), wrap_coord(i32(floor(v)), sh, mv, minv, maxv));
    }
    let uu = u - 0.5;
    let vf = v - 0.5;
    let x0 = i32(floor(uu));
    let y0 = i32(floor(vf));
    let xa = wrap_coord(x0, sw, mu, minu, maxu);
    let xb = wrap_coord(x0 + 1, sw, mu, minu, maxu);
    let ya = wrap_coord(y0, sh, mv, minv, maxv);
    let yb = wrap_coord(y0 + 1, sh, mv, minv, maxv);
    let fx = uu - f32(x0);
    let fy = vf - f32(y0);
    let top = mix(fetch(d, xa, ya), fetch(d, xb, ya), fx);
    let bottom = mix(fetch(d, xa, yb), fetch(d, xb, yb), fx);
    return floor(clamp(mix(top, bottom, fy) + vec4<f32>(0.5), vec4<f32>(0.0), vec4<f32>(255.0)));
}

fn alpha_ok(a: u32, atst: u32, aref: u32) -> bool {
    switch atst {
        case 0u {
            return false;
        }
        case 1u {
            return true;
        }
        case 2u {
            return a < aref;
        }
        case 3u {
            return a <= aref;
        }
        case 4u {
            return a == aref;
        }
        case 5u {
            return a >= aref;
        }
        case 6u {
            return a > aref;
        }
        default {
            return a != aref;
        }
    }
}

struct Shaded {
    rgb: vec3<f32>,   // 0..255
    alpha: f32,       // the alpha stored (0..255)
    factor: f32,      // As as the card's blend factor (0x80 = 1.0)
}

fn shade(in: VOut) -> Shaded {
    let d = D(in.da, in.db, in.dc, vec4<u32>(in.dd, 0u, 0u, 0u));
    let flags = d.a.x;
    let modes = d.a.y;
    let cf = floor(in.col * 255.0 + vec4<f32>(0.002));
    var r = i32(cf.x);
    var g = i32(cf.y);
    var bl = i32(cf.z);
    var a = i32(cf.w);
    if (TEX != 0u && (flags & F_TME) != 0u) {
        let tw = d.b.x & 0xFFu;
        let th = (d.b.x >> 8u) & 0xFFu;
        let sw = f32(1u << tw);
        let sh = f32(1u << th);
        var u: f32;
        var v: f32;
        var q = 1.0;
        if ((flags & F_FST) != 0u) {
            u = in.stq.x;
            v = in.stq.y;
        } else {
            q = in.stq.z;
            if (q == 0.0) {
                q = 1.0;
            }
            u = in.stq.x / q * sw;
            v = in.stq.y / q * sh;
        }
        var t: vec4<f32>;
        if (TEX == 1u) {
            var lod = 0.0;
            if ((flags & F_MIP) != 0u) {
                let m = d.c.x;
                let lk = f32(bitcast<i32>(((m >> 16u) & 0xFFFu) << 20u) >> 20u) / 16.0;
                lod = lk;
                if ((m & 1u) == 0u) {
                    lod = log2(1.0 / abs(q)) * f32(1u << ((m >> 8u) & 3u)) + lk;
                }
                lod = min(lod, f32(d.c.y));
            }
            t = floor(textureSampleLevel(tex, smp, vec2<f32>(u / sw, v / sh), lod) * 255.0 + vec4<f32>(0.5));
        } else {
            t = sample_loads(d, u, v);
        }
        let tr = i32(t.x);
        let tg = i32(t.y);
        let tb = i32(t.z);
        let ta = i32(t.w);
        let tcc = (flags & F_TCC) != 0u;
        switch (modes & 3u) {
            case 0u {
                r = clamp((tr * r) >> 7u, 0, 255);
                g = clamp((tg * g) >> 7u, 0, 255);
                bl = clamp((tb * bl) >> 7u, 0, 255);
                if (tcc) {
                    a = clamp((ta * a) >> 7u, 0, 255);
                }
            }
            case 1u {
                r = tr;
                g = tg;
                bl = tb;
                if (tcc) {
                    a = ta;
                }
            }
            case 2u {
                r = clamp(((tr * r) >> 7u) + a, 0, 255);
                g = clamp(((tg * g) >> 7u) + a, 0, 255);
                bl = clamp(((tb * bl) >> 7u) + a, 0, 255);
                if (tcc) {
                    a = clamp(ta + a, 0, 255);
                }
            }
            default {
                r = clamp(((tr * r) >> 7u) + a, 0, 255);
                g = clamp(((tg * g) >> 7u) + a, 0, 255);
                bl = clamp(((tb * bl) >> 7u) + a, 0, 255);
                if (tcc) {
                    a = ta;
                }
            }
        }
    }
    if ((flags & F_FGE) != 0u) {
        let f = i32(floor(in.fog + 0.002));
        let fc = d.a.w;
        r = (f * r + (255 - f) * i32(fc & 0xFFu)) >> 8u;
        g = (f * g + (255 - f) * i32((fc >> 8u) & 0xFFu)) >> 8u;
        bl = (f * bl + (255 - f) * i32((fc >> 16u) & 0xFFu)) >> 8u;
    }
    if (ATEST != 0u) {
        let ok = alpha_ok(u32(a & 255), (modes >> 2u) & 7u, d.a.z);
        if (ATEST == 1u && !ok) {
            discard;
        }
        if (ATEST == 2u && ok) {
            discard;
        }
    }
    var s: Shaded;
    var c = vec3<u32>(u32(r & 255), u32(g & 255), u32(bl & 255));
    if ((flags & F_PREMUL_FLOOR) != 0u) {
        c = min((c * d.c.z) >> vec3<u32>(7u), vec3<u32>(255u));
    } else if ((flags & F_PREMUL_CEIL) != 0u) {
        c = min((c * d.c.z + vec3<u32>(127u)) >> vec3<u32>(7u), vec3<u32>(255u));
    }
    if ((flags & F_Q16_FLOOR) != 0u) {
        c = c & vec3<u32>(0xF8u);
    } else if ((flags & F_Q16_CEIL) != 0u) {
        c = min((c + vec3<u32>(7u)) & vec3<u32>(0x1F8u), vec3<u32>(255u));
    }
    s.rgb = vec3<f32>(c);
    var ao = a & 255;
    if ((flags & F_FBA) != 0u) {
        ao = ao | 0x80;
    }
    s.alpha = f32(ao);
    if ((flags & F_CT16OUT) != 0u) {  // a 16-bit target keeps the A bit; the colour's low bits are cut where it is read or written back
        s.alpha = select(0.0, 255.0, (ao & 0x80) != 0);
    }
    s.factor = f32(min(a & 255, 128)) / 128.0;
    return s;
}

@fragment
fn fs(in: VOut) -> @location(0) vec4<f32> {
    let s = shade(in);
    var oa = s.alpha / 255.0;
    if ((in.da.x & F_ALPHA_FACTOR) != 0u) {
        oa = s.factor;
    }
    return vec4<f32>(s.rgb / 255.0, oa);
}

//DUAL_BEGIN
// Dual-source blending: output 0 is the colour and the raw alpha, output 1 the blend factor As/128 (the pipeline blends colour with Src1 /
// OneMinusSrc1 or Src1 / One; alpha is written as it is).
struct DualOut {
    @location(0) @blend_src(0) c: vec4<f32>,
    @location(0) @blend_src(1) f: vec4<f32>,
}

@fragment
fn fs_dual(in: VOut) -> DualOut {
    let s = shade(in);
    var o: DualOut;
    o.c = vec4<f32>(s.rgb / 255.0, s.alpha / 255.0);
    o.f = vec4<f32>(s.factor, s.factor, s.factor, 1.0);
    return o;
}
//DUAL_END
