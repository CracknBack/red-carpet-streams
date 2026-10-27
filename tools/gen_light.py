import os
TV=os.path.join(os.path.dirname(os.path.abspath(__file__)),'..','tv-app')
import re
p=os.path.join(TV,'index.html'); s=open(p,encoding='utf-8').read()
S0,S1='/*LIGHT-START*/','/*LIGHT-END*/'
if S0 in s:
    a=s.index(S0); b=s.index(S1)+len(S1); s=s[:a]+s[b:]
k0=s.index('</style>'); s=s[:k0].rstrip('\n')+'\n'+s[k0:]      # no blank lines pile up in front of the block on every run
i=s.index('<style>')+7; j=s.index('</style>'); css=s[i:j]
# --- parse the top-level rules (skip @media / @keyframes / @supports blocks)
rules=[]; depth=0; start=0; sel=''; k=0
while k<len(css):
    ch=css[k]
    if ch=='/' and css[k:k+2]=='/*':
        e=css.index('*/',k)+2; css=css[:k]+css[e:]; continue
    if ch=='{':
        if depth==0: sel=css[start:k].strip(); bstart=k+1
        depth+=1
    elif ch=='}':
        depth-=1
        if depth==0:
            if sel and not sel.startswith('@'): rules.append((sel,css[bstart:k]))
            start=k+1
    k+=1
COL=re.compile(r'#[0-9a-fA-F]{8}\b|#[0-9a-fA-F]{6}\b|#[0-9a-fA-F]{3,4}\b|rgba?\([^)]*\)')
def parse(c):
    if c.startswith('#'):
        h=c[1:]
        if len(h) in (3,4): h=''.join(x*2 for x in h)
        return int(h[0:2],16),int(h[2:4],16),int(h[4:6],16),(int(h[6:8],16)/255 if len(h)==8 else 1.0)
    n=re.findall(r'[\d.]+',c[c.index('(')+1:c.rindex(')')]); return float(n[0]),float(n[1]),float(n[2]),(float(n[3]) if len(n)>3 else 1.0)
def fmt(r,g,b,a): return ('rgba(%d,%d,%d,%.2f)'%(round(r),round(g),round(b),a)) if a<1 else '#%02X%02X%02X'%(round(r),round(g),round(b))
def mp(prop,c):
    try: r,g,b,a=parse(c)
    except Exception: return None
    mx,mn=max(r,g,b),min(r,g,b); ch=mx-mn; L=(0.2126*r+0.7152*g+0.0722*b)/255
    if prop=='color':
        if L>0.62 and ch<60: return fmt(58,29,24,a)
        if L>0.45 and ch>=40 and r>=g>=b: return fmt(138,90,28,a)
        if g>r and g>b and L>0.5: return fmt(46,125,79,a)
        if r>g and r>b and L>0.5 and g<170: return fmt(179,38,30,a)
        if 0.3<L<=0.62 and ch<50: return fmt(123,102,80,a)
        return None
    if prop.startswith('background'):
        if mx<110 and ch<45: return fmt(252,248,238,a)
        return None
    if prop.startswith('border') or prop=='outline':
        if mx<120 and ch<60: return fmt(217,199,163,a)
        return None
    if prop=='box-shadow':
        if mx<30 and ch<10: return fmt(90,60,30,a*0.55)
        if mx<60 and ch<30: return fmt(252,248,238,a)
        return None
    return None
SKIP=re.compile(r'player|osd|spin|#pv\b|\.pv|gpv-box|gpv-msg|buffer|hs-|hspot|\.hb-game|hb-pill|hb-title|hb-sub|hb-count')
out=[]
for sel,body in rules:
    if SKIP.search(sel): continue
    decls=[d for d in re.split(r';(?![^(]*\))',body) if ':' in d]
    props={d.split(':',1)[0].strip():d.split(':',1)[1].strip() for d in decls}
    saturated_bg=any(pr.startswith('background') and any(parse(c)[3]>0.5 and (max(parse(c)[:3])-min(parse(c)[:3]))>90 for c in COL.findall(v)) for pr,v in props.items())
    new=[]
    for pr,v in props.items():
        if pr=='color' and saturated_bg: continue
        if not (pr=='color' or pr.startswith('background') or pr.startswith('border') or pr=='outline' or pr=='box-shadow'): continue
        if 'url(' in v and pr.startswith('background') and sel.strip() in ('#home','#main','#series','#login','#main.sport,#game','#game','#main.sport'): continue
        nv=COL.sub(lambda m: (mp(pr,m.group(0)) or m.group(0)),v)
        if nv!=v: new.append('%s:%s'%(pr,nv))
    if new:
        selo=','.join('html.light '+x.strip() for x in sel.split(','))
        out.append('%s{%s}'%(selo,';'.join(new)))
HAND = """
html.light{--bg:#F6EEDC;--panel:#FFFDF7;--raise:#F2E7CF;--line:#D9C7A3;--text:#3A1D18;--muted:#7B6650;--amber:#9A5B16;--amber-soft:rgba(154,91,22,.14);--focus-bg:linear-gradient(90deg,rgba(168,28,44,.18),rgba(168,28,44,.04));--red2:#B02030;--red-deep:#8E1522;color-scheme:light}
html.light body{background:#F6EEDC;color:#3A1D18}
html.light #home{background:linear-gradient(165deg,rgba(252,248,236,.12),rgba(244,232,207,.18) 55%,rgba(233,214,174,.30)),url(home-bg-light.jpg) center/cover no-repeat,#F6EEDC}
html.light #main,html.light #series,html.light #login{background:linear-gradient(165deg,rgba(252,248,236,.38),rgba(244,232,207,.46) 55%,rgba(233,214,174,.58)),url(home-bg-light.jpg) center/cover no-repeat,#F6EEDC}
html.light #main.sport,html.light #game{background:linear-gradient(165deg,rgba(252,248,236,.30),rgba(244,232,207,.40) 55%,rgba(233,214,174,.52)),url(sport-bg-light.jpg) center/cover no-repeat,#F6EEDC}
html.light #rail{background:linear-gradient(180deg,rgba(250,236,226,.94),rgba(240,218,200,.94));border-right-color:#D9B98F}
/* game tiles are gold; the selected one turns light, with a crimson edge and a gold glow */
html.light .ftile{background:linear-gradient(160deg,#F5DAA2 0%,#E5B870 55%,#D09B58 100%);border-color:#B8873F;box-shadow:inset 0 0 0 .4rem rgba(255,243,210,.5),inset 0 0 0 .52rem rgba(130,18,34,.88),0 .6rem 1.6rem rgba(110,60,30,.25)}
html.light .ftile .ft-comp{color:#5A2A1E} html.light .ftile .ft-names{color:#2A0F14} html.light .ftile .ft-time{color:#6E0F1E}
html.light .ftile .ft-right,html.light .ftile .ft-n,html.light .ftile .ft-day{color:#5A2A1E}
html.light .ftile .ft-pill.on{border-color:#6E0F1E;color:#6E0F1E} html.light .ftile .ft-pill.rep{border-color:#5A3A24;color:#5A3A24}
html.light .ftile.focus{background:linear-gradient(180deg,#FFFEFA,#F7EEDA);border-color:#8E1522;box-shadow:inset 0 0 0 .4rem #FFFEFA,inset 0 0 0 .55rem rgba(142,21,34,.97),0 0 4.5rem rgba(228,175,85,.8),0 1.2rem 2.6rem rgba(110,50,55,.32)}
html.light .ftile.focus .ft-comp{color:#7B6650} html.light .ftile.focus .ft-names{color:#2A0F14} html.light .ftile.focus .ft-time{color:#8E1522}
html.light .ftile.focus .ft-right,html.light .ftile.focus .ft-n,html.light .ftile.focus .ft-day{color:#7B4A2A}
html.light .ftile.focus .ft-pill.on{border-color:#9A5B16;color:#9A5B16} html.light .ftile.focus .ft-pill.rep{border-color:#7B6650;color:#7B6650}
html.light .ftile .cd{color:#5A0F1E;font-weight:600}
html.light .ftile .ft-time.ft-played{color:#5A3A2A} html.light .ftile .cd-on{color:#6E0F1E} html.light .ftile.focus .cd{color:#8E1522} html.light .ftile.focus .cd-on{color:#8E1522}
html.light .ctile{background:linear-gradient(160deg,#F5DAA2 0%,#E5B870 55%,#D09B58 100%);border-color:#B8873F;box-shadow:inset 0 0 0 .4rem rgba(255,243,210,.5),inset 0 0 0 .52rem rgba(130,18,34,.88),0 .6rem 1.6rem rgba(110,60,30,.25)}
html.light .ctile .ct-name{color:#2A0F14} html.light .ctile .ct-sub{color:#5A2A1E} html.light .ctile .ct-ok{color:#1F5E39} html.light .ctile .ct-lk,html.light .ctile .ct-app,html.light .ctile .ct-src,html.light .ctile .ct-lang{color:#5A2A1E}
html.light .ctile .cd{color:#5A0F1E}
html.light .ctile .bd,html.light .ftile .bd{color:#5A2A1E;border-color:#8A5A1C;background:rgba(255,243,210,.35)} html.light .ctile.focus .bd{color:#7B4A2A;border-color:#B8873F;background:none}
html.light .ctile.focus{background:linear-gradient(180deg,#FFFEFA,#F7EEDA);border-color:#8E1522;box-shadow:inset 0 0 0 .4rem #FFFEFA,inset 0 0 0 .55rem rgba(142,21,34,.97),0 0 4.5rem rgba(228,175,85,.8),0 1.2rem 2.6rem rgba(110,50,55,.32)}
html.light .ctile.focus .ct-name{color:#2A0F14} html.light .ctile.focus .ct-sub{color:#7B4A2A} html.light .ctile.focus .ct-ok{color:#2E7D4F} html.light .ctile.focus .cd{color:#8E1522}
/* the side menu is a deep wine column, so it reads as a different thing from the tabs */
html.light #rail{background:linear-gradient(180deg,#6E1727 0%,#541120 55%,#420D19 100%);border-right:.2rem solid #D9A957}
html.light #rail .ri .ic img{filter:brightness(1.55) saturate(1.2) contrast(1.05)}
/* the side menu's words were dark brown on dark wine; make them readable */
html.light #rail .ri{color:#EBD3A6} html.light #rail .ri.active{color:#FFF6E2;border-left-color:#F0C46E}
html.light #rail .ri.focus{color:#FFF6E2;background:linear-gradient(90deg,rgba(255,255,255,.22),rgba(255,255,255,.05))}
html.light #rail .brand{color:#F0CB88}
/* the search box stands out: gold edge and glow, brighter still when it is the thing selected */
html.light #qbar{background:linear-gradient(180deg,#FFFFFF,#FBF2DC);border:.28rem solid #C9974A;box-shadow:0 0 0 .35rem rgba(201,151,74,.25),0 .8rem 2rem rgba(110,60,30,.22)}
html.light #qbar.hot{border-color:#8E1522;box-shadow:0 0 0 .45rem rgba(142,21,34,.28),0 0 3.2rem rgba(228,175,85,.8)}
html.light #q::placeholder{color:#8A6540;opacity:1;font-weight:600} html.light #qbar .qic{color:#8E1522}
html.light .chip.dragover{box-shadow:inset .5rem 0 0 #8E1522}
html.light .chip.lift{border-color:#8E1522;box-shadow:inset 0 -.38rem 0 #8E1522,0 1.3rem 2.2rem rgba(90,40,30,.35),0 0 2.8rem rgba(228,175,85,.9)}
html.light #rail .ri .ic img{filter:none}
html.light #rail-logo{filter:brightness(1.6) saturate(1.15) drop-shadow(0 0 .5rem rgba(255,230,190,.55))}
html.light #clock{color:#F0CB88}
/* the tabs are separate buttons; the open one is filled gold with a crimson base */
html.light #chips{border-bottom:none;gap:.7rem}
html.light .chip{background:linear-gradient(180deg,#FFFEFA,#F4EAD3);border:.18rem solid #CDB17E;border-radius:1.2rem;color:#5A3A24;box-shadow:0 .3rem .8rem rgba(110,60,30,.15);padding:.7rem 1.2rem}
html.light .chip .tabi{margin-right:.6rem}
html.light .chip.on{background:linear-gradient(160deg,#F5DAA2,#E0AE62);border-color:#8E1522;color:#2A0F14;box-shadow:inset 0 -.38rem 0 #8E1522,0 .4rem 1rem rgba(110,60,30,.25)}
html.light .row.current{background:linear-gradient(90deg,rgba(168,28,44,.22),rgba(168,28,44,.06));border-left-color:#9A5B16}
html.light .hlogo{filter:drop-shadow(0 .6rem 1.4rem rgba(120,50,60,.28))}
html.light #h-time{color:#4A2118} html.light #h-date{color:#7A3B2E} html.light #h-sub,html.light #h-ver{color:#8A6A52}
html.light .hslot .hg img,html.light .ri .ic img,html.light .tabi,html.light .ft-comp img,html.light .hb-lg,html.light .hb-chip img{filter:saturate(1.3) brightness(.76) contrast(1.08)}
html.light .hb-game .hb-title{color:#fff} html.light .hb-game .hb-sub,html.light .hb-game .hb-count{color:#F3D7A6}
html.light .hb-find{color:#A8352F} html.light .hb-cl{color:#5A2A1E}
html.light .hslot{background:linear-gradient(180deg,#FFFEFA,#F8F0DD);border-color:#B98B55;box-shadow:inset 0 0 0 .35rem #FFFEFA,inset 0 0 0 .45rem rgba(150,24,40,.6),0 1rem 2.4rem rgba(110,50,55,.2)}
html.light .hslot.s0.focus{box-shadow:inset 0 0 0 .5rem #FFFEFA,inset 0 0 0 .62rem rgba(170,24,40,.9),0 0 5.5rem rgba(205,155,70,.65),0 1.6rem 3.2rem rgba(110,50,55,.28)}
html.light .hs-glow.l,html.light .hs-glow.r{background:radial-gradient(ellipse at 50% 40%,rgba(255,252,240,.85),rgba(255,252,240,.3) 38%,rgba(255,252,240,0) 68%)}
html.light .hs-pool{background:radial-gradient(ellipse at 50% 50%,rgba(255,252,240,.8),rgba(255,252,240,.3) 45%,rgba(255,252,240,0) 70%)}
@supports (background:conic-gradient(red,blue)){html.light .hs-beam.l{background:conic-gradient(from 104deg at 0% 0%,rgba(255,252,240,0) 0deg,rgba(255,252,240,.7) 9.5deg,rgba(255,252,240,0) 20deg,rgba(255,252,240,0) 360deg)}html.light .hs-beam.r{background:conic-gradient(from 233deg at 100% 0%,rgba(255,252,240,0) 0deg,rgba(255,252,240,.7) 9.5deg,rgba(255,252,240,0) 20deg,rgba(255,252,240,0) 360deg)}}
"""
block=S0+'\n'+'\n'.join(out)+'\n'+HAND+S1
s=s[:j]+block+'\n'+s[j:]
open(p,'w',encoding='utf-8').write(s)
open(os.path.join(TV,'desktop.html'),'w',encoding='utf-8').write(s.replace('<head>','<head>\n<script>window.RCS_DESKTOP = true;</script>',1))
open(os.path.join(TV,'quick.html'),'w',encoding='utf-8').write(s.replace('<head>','<head>\n<script>window.RCS_DESKTOP = true; window.RCS_EXT = true;</script>',1))
print('rules:',len(rules),'| light rules generated:',len(out),'| block size KB:',len(block)//1024)
