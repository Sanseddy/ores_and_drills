-- Aseprite 1.3+: aseprite -b --script-param root=. --script docs/assembly-sprites/paint-interface.lua
-- Vanilla container palette; coordinates match AssemblyMenu and AssemblyScreen.
local root = app.params['root'] or '.'
local spr = Sprite(256, 256, ColorMode.RGB)
spr.layers[1].name = 'Container frame'
local img = Image(256, 256, ColorMode.RGB)
local function rgba(hex)
  return app.pixelColor.rgba(tonumber(hex:sub(1,2),16), tonumber(hex:sub(3,4),16), tonumber(hex:sub(5,6),16),255)
end
local black, white, panel, well, shade = rgba('000000'), rgba('ffffff'), rgba('c6c6c6'), rgba('8b8b8b'), rgba('373737')
local function rect(x,y,w,h,c)
  for py=y,y+h-1 do for px=x,x+w-1 do img:drawPixel(px,py,c) end end
end
local w,h=196,215
rect(2,0,w-4,h,black);rect(0,2,w,h-4,black);rect(1,1,w-2,h-2,black)
rect(2,1,w-4,h-2,panel);rect(1,2,w-2,h-4,panel)
rect(2,1,w-5,2,white);rect(1,2,2,h-5,white)
rect(w-3,3,2,h-5,rgba('555555'));rect(3,h-3,w-5,2,rgba('555555'))
img:drawPixel(3,3,white);img:drawPixel(w-4,h-4,rgba('555555'))
spr:newCel(spr.layers[1],1,img,Point(0,0))
local function layer(name)
  local l=spr:newLayer();l.name=name;img=Image(256,256,ColorMode.RGB);return l
end
local slots=layer('Recessed slots')
local function slot(x,y,size)
  rect(x,y,size,size,well)
  rect(x,y,size-1,1,shade);rect(x,y,1,size-1,shade)
  rect(x+1,y+size-1,size-1,1,white);rect(x+size-1,y+1,1,size-1,white)
end
for i=0,5 do slot(47+i%3*24,43+math.floor(i/3)*28,18) end
slot(146,53,26)
for row=0,2 do for col=0,8 do slot(16+col*18,133+row*18,18) end end
for col=0,8 do slot(16+col*18,191,18) end
spr:newCel(slots,1,img,Point(0,0))
local indicators=layer('Progress and arrow')
-- The arrow's white overlay is stored in the unused right side of the atlas.
local function arrow(x,y,c)
  rect(x,y+5,15,7,c)
  for row=0,16 do
    local width=9-math.abs(8-row)
    rect(x+14,y+row,width,1,c)
  end
end
arrow(117,57,well);arrow(204,0,white)
rect(47,102,66,7,well)
rect(47,102,65,1,shade);rect(47,102,1,6,shade)
rect(48,108,65,1,white);rect(112,103,1,6,white)
-- Keep the complete 64px fill inside the atlas.
rect(0,236,64,5,white)
spr:newCel(indicators,1,img,Point(0,0))
local selector=layer('Recipe selector')
slot(232,24,24)
slot(232,48,24)
rect(232,48,24,1,white);rect(232,48,1,24,white)
rect(232,71,24,1,white);rect(255,48,1,24,white)
rect(233,49,22,1,shade);rect(233,49,1,22,shade)
rect(234,50,20,20,rgba('a0a0a0'))
slot(232,72,24);rect(233,73,22,22,rgba('aaaaaa'))
spr:newCel(selector,1,img,Point(0,0))
spr:saveAs(root..'/docs/assembly-sprites/drill_assembly_interface.aseprite')
spr:saveCopyAs(root..'/src/main/resources/assets/ores_and_drills/textures/gui/drill_assembly.png')
print('Exported vanilla-style assembly interface, 256x256 atlas / 196x215 panel')
