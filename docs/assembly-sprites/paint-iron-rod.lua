-- Aseprite: outlined steel rod in the Create-style component palette at native 16px resolution.
local root=app.params['root'] or '.'
local spr=Sprite(16,16,ColorMode.RGB)
spr.layers[1].name='Iron rod'
local img=Image(16,16,ColorMode.RGB)
-- Same steel ramp as components_create_style_v2: warm highlights, blue-violet shadows.
local colors={O='1e2236',D='353d5a',S='58698f',M='8499b8',L='bfd0e2',W='f5faf2'}
for k,v in pairs(colors) do colors[k]=app.pixelColor.rgba(tonumber(v:sub(1,2),16),tonumber(v:sub(3,4),16),tonumber(v:sub(5,6),16),255) end
-- Each column x=3..12 crosses the shaft as highlight/core/shadow lanes. Columns 5 and 10 are
-- machined grooves; the top end is a lit cut face, the bottom end faces away from the light.
local hi  ={'M','W','M','W','W','W','W','M','W','L'}
local core={'S','L','D','M','L','M','M','D','M','L'}
local sh  ={'D','S','D','S','S','S','S','D','S','M'}
for i=0,9 do
 local x,y=3+i,12-i
 for offset=-2,2 do img:drawPixel(x,y+offset,colors.O) end
 img:drawPixel(x,y-1,colors[hi[i+1]])
 img:drawPixel(x,y,colors[core[i+1]])
 img:drawPixel(x,y+1,colors[sh[i+1]])
end
spr:newCel(spr.layers[1],1,img,Point(0,0))
spr:saveAs(root..'/docs/assembly-sprites/iron_rod.aseprite')
spr:saveCopyAs(root..'/src/main/resources/assets/ores_and_drills/textures/item/iron_rod.png')
