import sys, zlib, struct
def conv(src, dst, scale=2):
    d = open(src,'rb').read()
    parts = d.split(None, 4)
    w, h = int(parts[1]), int(parts[2])
    pix = d[len(d) - w*h*3:]
    # downscale by integer factor
    f = scale
    rows = []
    for y in range(0, h - h % f, f):
        row = bytearray()
        r = pix[y*w*3:(y+1)*w*3]
        for x in range(0, w - w % f, f):
            row += r[x*3:x*3+3]
        rows.append(b'\0' + bytes(row))
    W, H = w//f, h//f
    raw = b''.join(rows)
    def chunk(t, c): 
        x = struct.pack('>I', len(c)) + t + c
        return x + struct.pack('>I', zlib.crc32(t+c) & 0xffffffff)
    png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', W, H, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(raw, 6)) + chunk(b'IEND', b'')
    open(dst, 'wb').write(png)
if __name__ == '__main__':
    conv(sys.argv[1], sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 2)
