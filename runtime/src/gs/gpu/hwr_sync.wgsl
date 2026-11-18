// ---- The hardware renderer's transfers between its targets and graphics memory (hwr.cpp). Appended to gs.wgsl, so it uses its memory
// layout code (read_sample, write_sample). Parameters: prm(0) bp, prm(1) bw, prm(2) psm, prm(3), prm(4) x, y of the rectangle, prm(5), prm(6)
// its size, prm(7) (depth only) the depth scale: zmax + 1 as f32 bits, prm(8), prm(9) the targets' scale across and down (card texels per
// GS pixel, f32 bits).
//
// Graphics memory keeps SCALE x SCALE samples per GS pixel (sample k = sub-row * SCALE + sub-column, at x + (column + 0.5) / SCALE); the
// targets are drawn at F, which need not be SCALE (PS2_HWR_SCALE: any size, e.g. 1.35). Stores run one thread per memory sample and read
// the target's texel under that sample; loads run one thread per target texel and read the memory sample under the texel's centre.

@group(1) @binding(0) var hwr_out: texture_storage_2d<rgba8unorm, write>;
@group(1) @binding(0) var hwr_in: texture_2d<f32>;
@group(1) @binding(0) var hwr_depth: texture_depth_2d;

fn hwr_16bit(psm: u32) -> bool {
    return psm == 0x02u || psm == 0x0Au || psm == 0x32u || psm == 0x3Au;
}

fn hwr_scale() -> vec2<f32> {
    return vec2<f32>(bitcast<f32>(prm(8u)), bitcast<f32>(prm(9u)));
}

// the target texel under memory sample (gid) of the rectangle
fn hwr_texel(gid: vec3<u32>, dim: vec2<u32>) -> vec2<i32> {
    let f = hwr_scale();
    let gx = f32(prm(3u)) + (f32(gid.x) + 0.5) / f32(SCALE);
    let gy = f32(prm(4u)) + (f32(gid.y) + 0.5) / f32(SCALE);
    return vec2<i32>(min(vec2<u32>(u32(gx * f.x), u32(gy * f.y)), dim - vec2<u32>(1u, 1u)));
}

// graphics memory -> a colour target (a 16-bit pixel becomes 5 bits per channel in 8 and alpha 255 or 0 from its A bit)
@compute @workgroup_size(8, 8)
fn hwr_load(@builtin(global_invocation_id) gid: vec3<u32>) {
    let f = hwr_scale();
    let dim = textureDimensions(hwr_out);
    let tx = u32(floor(f32(prm(3u)) * f.x)) + gid.x;
    let ty = u32(floor(f32(prm(4u)) * f.y)) + gid.y;
    if (tx >= dim.x || ty >= dim.y) {
        return;
    }
    let gx = (f32(tx) + 0.5) / f.x;
    let gy = (f32(ty) + 0.5) / f.y;
    let x = u32(gx);
    let y = u32(gy);
    if (x < prm(3u) || y < prm(4u) || x >= prm(3u) + prm(5u) || y >= prm(4u) + prm(6u)) {
        return;
    }
    let kx = min(u32((gx - f32(x)) * f32(SCALE)), SCALE - 1u);
    let ky = min(u32((gy - f32(y)) * f32(SCALE)), SCALE - 1u);
    let psm = prm(2u);
    var c = read_sample(psm, prm(0u), prm(1u), x, y, ky * SCALE + kx);
    if (hwr_16bit(psm)) {
        c = ((c & 0x1Fu) << 3u) | (((c >> 5u) & 0x1Fu) << 11u) | (((c >> 10u) & 0x1Fu) << 19u) | select(0u, 0xFF000000u, (c & 0x8000u) != 0u);
    }
    textureStore(hwr_out, vec2<i32>(i32(tx), i32(ty)), vec4<f32>(vec4<u32>(c & 0xFFu, (c >> 8u) & 0xFFu, (c >> 16u) & 0xFFu, c >> 24u)) / 255.0);
}

// a colour target -> graphics memory in its format (CT24 keeps the top byte of each word, CT16 keeps 5 bits per channel and the A bit)
@compute @workgroup_size(8, 8)
fn hwr_store(@builtin(global_invocation_id) gid: vec3<u32>) {
    if (gid.x >= prm(5u) * SCALE || gid.y >= prm(6u) * SCALE) {
        return;
    }
    let x = prm(3u) + gid.x / SCALE;
    let y = prm(4u) + gid.y / SCALE;
    let k = (gid.y % SCALE) * SCALE + gid.x % SCALE;
    let psm = prm(2u);
    let t = textureLoad(hwr_in, hwr_texel(gid, textureDimensions(hwr_in)), 0);
    let o = vec4<u32>(clamp(t * 255.0 + vec4<f32>(0.5), vec4<f32>(0.0), vec4<f32>(255.0)));
    var v = o.x | (o.y << 8u) | (o.z << 16u) | (o.w << 24u);
    if (hwr_16bit(psm)) {
        v = (o.x >> 3u) | ((o.y >> 3u) << 5u) | ((o.z >> 3u) << 10u) | select(0u, 0x8000u, o.w >= 128u);
    }
    write_sample(psm, prm(0u), prm(1u), x, y, k, v);
}

// a depth target -> graphics memory in its depth format
@compute @workgroup_size(8, 8)
fn hwr_store_z(@builtin(global_invocation_id) gid: vec3<u32>) {
    if (gid.x >= prm(5u) * SCALE || gid.y >= prm(6u) * SCALE) {
        return;
    }
    let x = prm(3u) + gid.x / SCALE;
    let y = prm(4u) + gid.y / SCALE;
    let k = (gid.y % SCALE) * SCALE + gid.x % SCALE;
    let d = textureLoad(hwr_depth, hwr_texel(gid, textureDimensions(hwr_depth)), 0);
    let scale = bitcast<f32>(prm(7u));
    write_sample(prm(2u), prm(0u), prm(1u), x, y, k, u32(clamp(floor(d * scale + 0.5), 0.0, scale - 1.0)));
}
