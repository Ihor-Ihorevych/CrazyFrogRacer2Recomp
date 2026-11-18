import sys, zlib, struct, glob
def load(p, f):
    d = open(p,'rb').read(); parts = d.split(None,4); w,h = int(parts[1]), int(parts[2]); pix = d[len(d)-w*h*3:]
    rows=[]
    for y in range(0,h-h%f,f):
        r=pix[y*w*3:(y+1)*w*3]; rows.append(b''.join(r[x*3:x*3+3] for x in range(0,w-w%f,f)))
    return w//f, h//f, rows
def main(out, files, cols=5, f=4):
    tiles=[load(p,f) for p in files]
    tw,th=tiles[0][0],tiles[0][1]
    nr=(len(tiles)+cols-1)//cols
    W=tw*cols; H=th*nr
    rows=[bytearray(b'\0'*(W*3)) for _ in range(H)]
    for i,(w,h,r) in enumerate(tiles):
        cx,cy=(i%cols)*tw,(i//cols)*th
        for y in range(min(h,th)):
            rows[cy+y][cx*3:(cx+w)*3]=r[y][:w*3]
    raw=b''.join(b'\0'+bytes(r) for r in rows)
    def chunk(t,c):
        x=struct.pack('>I',len(c))+t+c; return x+struct.pack('>I',zlib.crc32(t+c)&0xffffffff)
    open(out,'wb').write(b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',W,H,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(raw,6))+chunk(b'IEND',b''))
if __name__=='__main__':
    # montage.py out.png cols factor files...
    main(sys.argv[1], sys.argv[4:], int(sys.argv[2]), int(sys.argv[3]))
