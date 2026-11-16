-- Aseprite 1.3+: aseprite -b --script-param root=. --script docs/assembly-sprites/paint-components.lua
-- Front-facing mechanical parts in the cold-steel / orange-copper palette supplied by the user.
local root=app.params['root'] or '.'
local palette={
 o='#191f2c',b='#29313e',d='#3c4c61',s='#667d93',l='#a3b6c5',h='#e2e8db',
 c='#75432f',r='#ac6037',a='#dc9647',y='#f4cc78',
 g='#294f3b',n='#4d8e48',v='#92c365',
 t='#2f626e',u='#67b5b3',i='#b9e4d6',R='#b94440',H='#ec8b65'
}
local color={}
for k,h in pairs(palette)do color[k]=app.pixelColor.rgba(tonumber(h:sub(2,3),16),tonumber(h:sub(4,5),16),tonumber(h:sub(6,7),16),255)end
local im
local function pixel(x,y,k)if x>=0 and x<16 and y>=0 and y<16 then im:drawPixel(x,y,color[k])end end
local function rect(x1,y1,x2,y2,k)for y=y1,y2 do for x=x1,x2 do pixel(x,y,k)end end end
local function line(x1,y1,x2,y2,k)
 local dx,dy=math.abs(x2-x1),-math.abs(y2-y1)
 local sx,sy=x1<x2 and 1 or -1,y1<y2 and 1 or -1
 local err=dx+dy
 while true do pixel(x1,y1,k);if x1==x2 and y1==y2 then break end
  local e=2*err;if e>=dy then err=err+dy;x1=x1+sx end
  if e<=dx then err=err+dx;y1=y1+sy end
 end
end
local function stamp(x,y,rows)
 for yy,row in ipairs(rows)do for xx=1,#row do local c=row:sub(xx,xx);if c~='.'then pixel(x+xx-1,y+yy-1,c)end end end
end
local function frame()
 -- Two uprights, two crossmembers, feet and four fastening points.
 rect(3,2,12,3,'s');rect(3,12,12,13,'d')
 rect(2,3,3,12,'s');rect(12,3,13,12,'d')
 rect(2,13,5,13,'s');rect(10,13,13,13,'d')
 line(3,2,11,2,'h');line(2,4,2,11,'l');line(4,12,10,12,'s')
 for _,p in ipairs({{3,3},{12,3},{3,12},{12,12}})do pixel(p[1],p[2],'b')end
 pixel(12,2,'l');pixel(3,11,'h');pixel(11,12,'l')
end
local function drive()
 -- Perpendicular input/output shafts, a reduction housing, and an exposed steel cog.
 rect(9,2,10,4,'s');rect(9,2,10,2,'h')
 rect(5,4,12,11,'d');rect(5,4,12,4,'l');rect(5,5,5,10,'s')
 rect(2,7,5,8,'s');rect(2,7,4,7,'h')
 rect(5,12,6,13,'s');rect(11,12,12,13,'d')
 stamp(10,5,{'.hl','lsd','.dd'})
 stamp(6,7,{'.lhl.','llsll','lsbsl','lldll','.lll.'})
 pixel(5,5,'b');pixel(12,11,'s')
end
local function motor()
 -- A broad motor body with two end caps, visible copper windings, a terminal box and mounting feet.
 rect(5,4,11,11,'d');rect(5,4,11,5,'s')
 rect(4,5,5,10,'s');rect(12,6,13,9,'d')
 line(4,5,4,9,'l');rect(5,6,5,10,'d')
 rect(2,7,4,8,'l');pixel(2,7,'h')
 rect(6,6,10,9,'c')
 for x=6,10,2 do rect(x,6,x,9,'a');pixel(x,6,'y')end
 rect(5,10,11,11,'s');rect(6,11,11,11,'d')
 rect(7,2,10,3,'d');line(7,2,9,2,'l');pixel(9,3,'R')
 rect(4,12,6,13,'s');rect(10,12,12,13,'d')
 line(5,4,10,4,'h');line(12,5,12,9,'s')
end
local function head(reinforced)
 -- A steel coupling shank, a broad mounting collar and a tapered helical cutting head.
 rect(7,2,8,4,'s');line(7,2,7,4,'h')
 rect(3,5,12,6,'d');rect(3,5,11,5,'h');rect(4,6,11,6,'s')
 for _,row in ipairs({{7,4,11},{8,4,11},{9,5,10},{10,5,10},{11,6,9},{12,6,9},{13,7,8}})do
  rect(row[2],row[1],row[3],row[1],'d');pixel(row[2],row[1],'s')
 end
 rect(7,7,8,12,'s')
 line(4,7,10,9,'h');line(5,8,9,10,'b')
 line(5,10,8,12,'l');pixel(7,13,'h');pixel(8,12,'d')
 if reinforced then
  rect(4,7,5,8,'u');pixel(4,7,'i');pixel(5,8,'t')
  rect(10,7,11,8,'t');pixel(10,7,'u')
  rect(7,12,8,13,'u');pixel(7,12,'i');pixel(8,13,'t')
 end
end
local function manifold()
 -- One horizontal trunk, steel end flanges, two downward branches and a red handwheel.
 rect(3,8,12,10,'r');line(4,8,11,8,'y');line(4,10,11,10,'c')
 rect(2,7,3,11,'s');line(2,7,2,10,'h');pixel(3,11,'d')
 rect(12,7,13,11,'d');line(12,7,12,10,'l')
 rect(4,11,5,13,'r');line(4,11,4,12,'a');rect(4,13,5,13,'d')
 rect(10,11,11,13,'c');line(10,11,10,12,'a');rect(10,13,11,13,'d')
 rect(7,5,8,8,'s');line(7,5,7,7,'l')
 stamp(5,2,{'.RRRR.','RH..RR','.RRRR.'})
 pixel(6,2,'H');pixel(9,4,'c')
end
local function control()
 -- A PCB, chip, edge connector, quartz resonator, redstone indicator and connected gold traces.
 rect(3,3,11,12,'n');rect(12,4,12,11,'g');rect(4,12,11,12,'g')
 line(3,3,10,3,'v');line(3,4,3,10,'v')
 line(5,4,10,4,'a');line(11,5,11,10,'a');line(4,9,4,11,'y');line(4,11,10,11,'a')
 rect(6,6,9,9,'b');rect(6,6,8,6,'s');pixel(6,7,'d')
 for y=6,9,2 do pixel(5,y,'y');pixel(10,y,'a')end
 for x=6,9,2 do pixel(x,5,'y');pixel(x,10,'a')end
 rect(4,5,4,7,'l');pixel(4,5,'h')
 rect(10,4,11,5,'R');pixel(10,4,'H')
 for x=5,11,2 do pixel(x,13,'y')end
end
local names={'drill_frame','mechanical_drive','electric_motor','drill_head','reinforced_drill_head','fluid_manifold','precision_control'}
local draw={drill_frame=frame,mechanical_drive=drive,electric_motor=motor,
 drill_head=function()head(false)end,reinforced_drill_head=function()head(true)end,
 fluid_manifold=manifold,precision_control=control}
local sheet=Image(126,20,ColorMode.RGB)
local report={}
for index,name in ipairs(names)do
 im=Image(16,16,ColorMode.RGB);draw[name]()
 local silhouette=Image(16,16,ColorMode.RGB)
 for it in im:pixels()do
  if app.pixelColor.rgbaA(it())>0 then
   for _,d in ipairs({{0,0},{1,0},{-1,0},{0,1},{0,-1}})do
    local x,y=it.x+d[1],it.y+d[2]
    if x>=0 and x<16 and y>=0 and y<16 then silhouette:drawPixel(x,y,color.o)end
   end
  end
 end
 local s=Sprite(16,16,ColorMode.RGB);s.layers[1].name='Functional silhouette'
 s:newCel(s.layers[1],1,silhouette,Point(0,0))
 local layer=s:newLayer();layer.name='Materials and working parts';s:newCel(layer,1,im,Point(0,0))
 local flat=Image(s);flat:saveAs(root..'/src/main/resources/assets/ores_and_drills/textures/item/'..name..'.png')
 s:saveAs(root..'/docs/assembly-sprites/'..name..'.aseprite')
 sheet:drawImage(flat,Point((index-1)*18+1,2))
 local colors={};local pixels=0
 for it in flat:pixels()do if app.pixelColor.rgbaA(it())>0 then colors[it()]=true;pixels=pixels+1 end end
 local count=0;for _ in pairs(colors)do count=count+1 end
 table.insert(report,{name=name,colors=count,opaquePixels=pixels})
 s:close()
end
local contact=Sprite(126,20,ColorMode.RGB);contact.layers[1].name='Functional assembly components'
contact:newCel(contact.layers[1],1,sheet,Point(0,0));contact:saveAs(root..'/docs/assembly-sprites/components.aseprite')
sheet:saveAs(root..'/docs/assembly-sprites/components.png')

-- Inspect on the same neutral gray as Minecraft's inventory slots.
local inventory=Sprite(126,20,ColorMode.RGB);inventory.layers[1].name='Vanilla inventory background'
local bg=Image(126,20,ColorMode.RGB)
local function fill(x,y,w,h,c)for yy=y,y+h-1 do for xx=x,x+w-1 do bg:drawPixel(xx,yy,c)end end end
local rgb=app.pixelColor.rgba
fill(0,0,126,20,rgb(198,198,198,255))
for i=0,6 do local x=i*18
 fill(x,1,18,18,rgb(139,139,139,255));fill(x,1,17,1,rgb(55,55,55,255))
 fill(x,1,1,17,rgb(55,55,55,255));fill(x+1,18,17,1,rgb(255,255,255,255))
 fill(x+17,2,1,17,rgb(255,255,255,255))
end
inventory:newCel(inventory.layers[1],1,bg,Point(0,0))
local items=inventory:newLayer();items.name='Outlined components';inventory:newCel(items,1,sheet,Point(0,0))
inventory:saveAs(root..'/docs/assembly-sprites/components-inventory.aseprite')

