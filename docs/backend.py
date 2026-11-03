from PIL import Image, ImageDraw, ImageFont
W,H=1080,1920
img=Image.new("RGB",(W,H)); d=ImageDraw.Draw(img)
for y in range(H):
    t=y/H; d.line([(0,y),(W,y)],fill=(int(10+20*t),4,int(22+38*t)))
B="/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"; R="/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
f=lambda p,s:ImageFont.truetype(p,s)
PUR=(176,92,255); CY=(0,229,255); WH=(240,236,255); GR=(170,160,200); GN=(60,230,140); BX=(28,14,52)
d.text((W//2,80),"VibeSpace",font=f(B,72),fill=PUR,anchor="mm")
d.text((W//2,150),"Step 1: Backend Phase 1",font=f(B,42),fill=WH,anchor="mm")
d.rounded_rectangle([370,190,710,240],radius=24,fill=GN); d.text((540,215),"COMPLETE",font=f(B,28),fill=(5,20,10),anchor="mm")
def box(x1,y1,x2,y2,title,sub,col):
    d.rounded_rectangle([x1,y1,x2,y2],radius=26,fill=BX,outline=col,width=4)
    d.text(((x1+x2)//2,y1+45),title,font=f(B,36),fill=WH,anchor="mm")
    d.multiline_text(((x1+x2)//2,y1+82),sub,font=f(R,26),fill=GR,anchor="ma",align="center",spacing=8)
def arrow(x,y1,y2):
    d.line([(x,y1),(x,y2-18)],fill=CY,width=6); d.polygon([(x-16,y2-22),(x+16,y2-22),(x,y2)],fill=CY)
box(240,290,840,450,"Android App","Users: guests + creators\n(Step 2, building now)",PUR)
arrow(540,450,500)
box(140,500,940,710,"API Server","Node.js + TypeScript\n59 endpoints  |  /api/v1\nsecure login (JWT), rate limits",CY)
arrow(330,710,770); arrow(750,710,770)
box(60,770,520,980,"Database","PostgreSQL\n17 tables\n(Supabase)",PUR)
box(560,770,1020,980,"Media Storage","Photos & videos\nCloudflare R2",PUR)
d.text((W//2,1035),"What's built",font=f(B,40),fill=CY,anchor="mm")
feats=["Login / Signup","Password reset","Profile + avatar","VibeMates","VibeStars","Posts + hashtags","VibeClaps","VibeTalk + replies","Save & report","Following feed","Trending feed","Explore feed","Block & mute","Admin panel APIs","Guest viewing","Hindi + English"]
cx=[60,550]; y=1080
for i,t in enumerate(feats):
    x=cx[i%2]; yy=y+(i//2)*72
    d.rounded_rectangle([x,yy,x+470,yy+58],radius=29,fill=(40,20,72))
    d.text((x+30,yy+29),"\u2713",font=f(B,30),fill=GN,anchor="lm")
    d.text((x+70,yy+29),t,font=f(R,28),fill=WH,anchor="lm")
yb=y+8*72+30
d.rounded_rectangle([60,yb,1020,yb+130],radius=26,fill=BX,outline=GN,width=3)
d.text((W//2,yb+45),"66 / 66 tests passed",font=f(B,44),fill=GN,anchor="mm")
d.text((W//2,yb+98),"on a real PostgreSQL database",font=f(R,28),fill=GR,anchor="mm")
d.text((W//2,H-50),"Made in India",font=f(R,30),fill=GR,anchor="mm")
img.save("vibespace_backend_phase1.png")
