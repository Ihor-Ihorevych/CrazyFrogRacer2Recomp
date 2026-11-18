import subprocess, sys, random, os, json, time, concurrent.futures as cf
E=os.getcwd().replace(chr(92),'/')
R='C:/Users/Ihor/CFR2Recomp/ps2-native-recomp'
EXE=os.environ.get('PS2EXE', R+'/build/native-eeall/ps2run.exe')
SEG=8000

def presses(r, start, end, policy):
    out=[]; f=start
    while f<end:
        k=r.random()
        if policy=='mix':
            if k<0.40: b,n='cross',r.randint(5,200)
            elif k<0.55: b,n=r.choice(['left','right']),r.randint(5,60)
            elif k<0.65: b,n=r.choice(['up','down']),3
            elif k<0.72: b,n='start',3
            elif k<0.80: b,n=r.choice(['circle','triangle','square']),r.randint(2,20)
            elif k<0.86: b,n=r.choice(['l1','r1','l2','r2']),r.randint(2,30)
            elif k<0.90: b,n=r.choice(['cross+left','cross+right']),r.randint(20,120)
            elif k<0.93: b,n='select',3
            else: b,n='cross',4
            gap=r.randint(1,40)
        elif policy=='confirm':   # menu-heavy: confirm and move, rarely back
            if k<0.55: b='cross'
            elif k<0.8: b=r.choice(['up','down','left','right'])
            elif k<0.9: b='start'
            elif k<0.95: b='circle'
            else: b=r.choice(['triangle','square','l1','r1'])
            n=r.randint(2,6); gap=r.randint(12,70)
        out.append((f,b,n)); f+=gap
    return ' '.join(f'--press {a}:{b}:{c}' for a,b,c in out).split()

def run_seg(tag, instate, start, end, seed, policy, extra, ee_live='0'):
    r=random.Random(seed)
    out=f'st/{tag}.state'
    cmd=[EXE, R+'/games/cfr2/disc/SLES_545.49','--disc',R+'/games/cfr2/disc.iso','--memcard',f'{E}/mc_{tag}.ps2','--misses',f'{E}/misses_{tag}.txt',
         '--frames',str(end+2),'--frames-dir',f'{E}/shots/{tag}','--frame-every','1500','--save-state',f'{end}:{out}']
    if instate: cmd+=['--load-state',instate]
    cmd+=presses(r,start+10,end,policy)+extra
    env=dict(os.environ, PS2_EE_LIVE=ee_live, PS2_VU_LIVE='0', PS2_DUMP=f'{E}/ram_{tag}.bin')
    os.makedirs(f'{E}/shots/{tag}',exist_ok=True)
    with open(f'{E}/logs/{tag}.log','w') as lf:
        p=subprocess.run(cmd,cwd=E,env=env,stdout=lf,stderr=subprocess.STDOUT)
    log=open(f'{E}/logs/{tag}.log',errors='replace').read()
    halted=[l for l in log.splitlines() if l.startswith('[ee] halted')]
    return p.returncode, (halted[0] if halted else '?')

def job(name, instate, start, total, seed, policy):
    f=start; seg=0; st=instate
    res=[]
    while f<start+total:
        end=min(f+SEG,start+total)
        tag=f'{name}_s{seg}'
        rc,h=run_seg(tag, st, f, end, seed*1000+seg, policy, [])
        res.append((tag,rc,h))
        if 'frame limit' not in h:
            print(name,'STOPPED',tag,h,flush=True); return res
        st=f'st/{tag}.state'; f=end; seg+=1
    print(name,'done',total,'frames',flush=True)
    return res

if __name__=='__main__':
    # drive.py jobs.json  -> list of [name, instate|null, startframe, total, seed, policy]
    jobs=json.load(open(sys.argv[1]))
    with cf.ThreadPoolExecutor(2) as ex:
        for _ in ex.map(lambda j: job(*j), jobs): pass
