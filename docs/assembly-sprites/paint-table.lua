-- Repaints the table atlas from UVs packed in Blockbench. Run from the repository root.
local uv=json.decode(io.open('docs/assembly-sprites/table-uv.json','r'):read('*a'))
local args={root=app.params['root'] or '.',uv=uv}

local root=args.root
local image=Image(args.uv.size,args.uv.size,ColorMode.RGB)
local palette={
 dark='#202431',steel='#404651',light='#697786',edge='#a0b1ba',
 copper='#aa613b',copperLight='#e3a35a',orange='#e99939',yellow='#ffd272',brown='#733e32',
 paper='#c8c8a2',ink='#4c777d',teal='#6cb2ae'
}
local col={}
for k,h in pairs(palette)do col[k]=app.pixelColor.rgba(tonumber(h:sub(2,3),16),tonumber(h:sub(4,5),16),tonumber(h:sub(6,7),16),255)end
for _,f in ipairs(args.uv.faces)do
 local x,y,w,h=f.x,f.y,f.w,f.h
 local function p(a,b,c)if a>=0 and a<w and b>=0 and b<h then image:drawPixel(x+a,y+b,col[c])end end
 local function r(a,b,c,d,color)for yy=b,b+d-1 do for xx=a,a+c-1 do p(xx,yy,color)end end end
 local base=f.side=='down'and 'dark'or 'steel'
 if f.name:find('copper')then base='copper'end
 if f.name:find('iron')then base='light'end
 r(0,0,w,h,base)
 if h>1 then r(0,0,w,1,base=='copper'and 'copperLight'or 'light')end
 if w>2 and h>2 then r(0,1,1,h-1,'light');r(1,h-1,w-1,1,'dark')end
 if w>=5 and h>=4 then
  p(1,1,'dark');p(w-2,1,'dark')
  if h>5 then p(1,h-2,'dark');p(w-2,h-2,'dark')end
 end
 if f.name:find('safety_front')and (f.side=='north'or f.side=='south')then
  for yy=0,h-1 do for xx=0,w-1 do local gx=xx+f.from[1];p(xx,yy,(gx+yy)%8<4 and 'yellow'or 'brown')end end
 end
 if f.name:find('steel_worktop')and f.side=='up'then
  for yy=0,h-1 do for xx=0,w-1 do
   local gx=xx+f.from[1]
   if yy==0 or yy==15 or gx==0 or gx==31 then p(xx,yy,'orange')
   elseif yy==1 or yy==14 or gx==1 or gx==30 then p(xx,yy,'dark')
   elseif gx==15 or gx==16 then p(xx,yy,'dark')
   elseif (gx==18 or gx==28)and(yy==3 or yy==11)then p(xx,yy,'teal')
   elseif (gx==19 or gx==27)and(yy==3 or yy==11)then p(xx,yy,'teal')
   end
   if gx>=20 and gx<=26 and yy>=7 and yy<=11 then p(xx,yy,'paper')end
   if ((gx==21 or gx==25)and yy>=8 and yy<=10)or(yy==8 and gx>=21 and gx<=25)then p(xx,yy,'ink')end
  end end
  for _,gx in ipairs({2,13,18,29})do
   local localX=gx-f.from[1];p(localX,2,'dark');p(localX,13,'dark')
  end
 end
 if f.name:find('cabinet')and (f.side=='east'or f.side=='west')and w>=6 and h>=6 then
  for yy=2,h-2,2 do r(2,yy,w-4,1,'dark')end
 end
 if f.name:find('end_panel')and(f.side=='east'or f.side=='west')and w>=8 then
  for xx=2,w-3,3 do r(xx,2,1,h-3,'dark')end
 end
 if f.name:find('drawer')and not f.name:find('handle')and w>=5 and h>=3 then
  r(1,0,w-2,1,'edge');p(w-2,h-2,'dark')
 end
 -- Clamp jaws and shaft have sharp metallic edges, not smoothed gradients.
 if f.name:find('jaw')and f.side=='up'then r(0,0,w,h,'light');r(0,0,w,1,'edge')end
 if f.name:find('screw')and f.side=='up'then
  for xx=1,w-1,2 do p(xx,0,'dark')end
 end
 -- One pixel of duplicated edge color around every island.
 for xx=0,w-1 do image:drawPixel(x+xx,y-1,image:getPixel(x+xx,y));image:drawPixel(x+xx,y+h,image:getPixel(x+xx,y+h-1))end
 for yy=0,h-1 do image:drawPixel(x-1,y+yy,image:getPixel(x,y+yy));image:drawPixel(x+w,y+yy,image:getPixel(x+w-1,y+yy))end
end
local spr=Sprite(args.uv.size,args.uv.size,ColorMode.RGB)
spr.layers[1].name='UV-painted industrial surfaces'
spr:newCel(spr.layers[1],1,image,Point(0,0))
spr:saveAs(root..'/docs/assembly-sprites/drill_assembly_table.aseprite')
image:saveAs(root..'/src/main/resources/assets/ores_and_drills/textures/assembly/drill_assembly_table.png')
return {atlas=args.uv.size,paintedFaces=#args.uv.faces,file='drill_assembly_table.png'}

