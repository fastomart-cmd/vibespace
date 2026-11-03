from PIL import Image, ImageDraw, ImageFont
W,H=1080,2240
img=Image.new("RGB",(W,H),(8,4,18))
d=ImageDraw.Draw(img)
# gradient bg
for y in range(H):
    t=y/H
    d.line([(0,y),(W,y)],fill=(int(10+20*t),int(4+2*t),int(22+38*t)))
B="/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"; R="/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
f=lambda p,s:ImageFont.truetype(p,s)
PUR=(176,92,255); CY=(0,229,255); WH=(240,236,255); GR=(170,160,200)
d.text((W//2,90),"VibeSpace",font=f(B,84),fill=PUR,anchor="mm")
d.text((W//2,165),"Create. Share. Earn.",font=f(R,36),fill=CY,anchor="mm")
d.text((W//2,225),"ROADMAP",font=f(B,40),fill=WH,anchor="mm")
steps=[
("1","Backend Phase 1","Login, profile, VibeMates, VibeStars,\nposts, VibeClaps, VibeTalk, feed","NOW"),
("2","Android App Phase 1","Kotlin + Compose, dark theme,\nHindi/English, guest viewing",""),
("3","Free Setup & Deploy","Supabase + Cloudflare R2 + Oracle,\nfree URL, APK via GitHub",""),
("4","Testing","APK on your phone + friends,\nfix bugs",""),
("5","Phase 2: Videos","VibeShorts + VibeLong,\nupload, processing, real views",""),
("6","Phase 3","VibeChat, VibePulse, VibeSpread,\nVibeShield, full admin panel",""),
("7","Launch","Domain, Play Store ($25),\nprivacy policy & terms",""),
("8","Phase 4: Earn","VibeMarket ads, VibeEarn,\nUPI payouts (KYC + legal)",""),
]
top=300; gap=228; x0=150
d.line([(x0,top+40),(x0,top+40+gap*(len(steps)-1))],fill=(90,50,160),width=6)
for i,(n,t,s,tag) in enumerate(steps):
    y=top+i*gap
    col=CY if tag else PUR
    d.rounded_rectangle([230,y,1010,y+190],radius=28,fill=(28,14,52),outline=col,width=4 if tag else 2)
    d.ellipse([x0-40,y+5,x0+40,y+85],fill=col)
    d.text((x0,y+45),n,font=f(B,40),fill=(10,4,20),anchor="mm")
    d.text((265,y+30),t,font=f(B,40),fill=WH)
    d.multiline_text((265,y+90),s,font=f(R,29),fill=GR,spacing=10)
    if tag:
        d.rounded_rectangle([860,y+28,990,y+76],radius=20,fill=CY)
        d.text((925,y+52),tag,font=f(B,26),fill=(10,4,20),anchor="mm")
d.text((W//2,H-55),"Made in India",font=f(R,30),fill=GR,anchor="mm")
img.save("vibespace_roadmap.png")
