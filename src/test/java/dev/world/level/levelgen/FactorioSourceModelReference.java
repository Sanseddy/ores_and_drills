package dev.world.level.levelgen;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** Source-model comparison, not screenshots of Factorio: the engine basis noise is a substitute.
 * Cone/peak, octave scales and default amplitude follow the official Lua and noise-expression docs.
 * One isolated spot; regional selection, distance scaling and overlaps are outside this comparison.
 */
public final class FactorioSourceModelReference {
    public static void main(String[] args) throws Exception {
        Path output=Path.of(args[0]);Files.createDirectories(output);
        BufferedImage image=new BufferedImage(1700,1740,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics();g.setColor(new Color(20,26,36));g.fillRect(0,0,1700,1740);
        g.setFont(new Font(Font.MONOSPACED,Font.PLAIN,11));g.setColor(Color.WHITE);
        g.drawString("SOURCE MODEL: isolated cone + (N8+N24+1.5N64-1/3)/8; gradient substitute; NOT Factorio engine output",15,22);
        Set<String> small=new HashSet<>(),large=new HashSet<>();
        for(int seed=0;seed<100;seed++) {
            int tx=seed%10*170,ty=40+seed/10*170;
            for(int px=0;px<128;px++)for(int pz=0;pz<128;pz++) {
                double field=field(seed,(px-63.5)*.5,(pz-63.5)*.5);
                if(field>0){float w=(float)Math.min(1,field);g.setColor(new Color(.25f+.65f*w,.22f+.5f*w,.14f));g.fillRect(tx+21+px,ty+22+pz,1,1);}
            }
            g.setColor(Color.WHITE);g.drawString("model seed="+seed,tx+6,ty+15);
            for(int diameter:new int[]{5,7}) {
                Set<String> mask=new HashSet<>();
                for(int x=-diameter/2;x<=diameter/2;x++)for(int z=-diameter/2;z<=diameter/2;z++) {
                    double shiftX=(CompactDepositGeometry.unit(seed,21)-.5)*.7;
                    double shiftZ=(CompactDepositGeometry.unit(seed,22)-.5)*.7;
                    if(field(seed,(x-shiftX)*48/diameter,(z-shiftZ)*48/diameter)>0)mask.add(x+":"+z);
                }
                (diameter==5?small:large).add(CompactDepositReview.signature(mask,0));
            }
        }
        g.dispose();ImageIO.write(image,"png",output.resolve("source-model-xz-100.png").toFile());
        Files.writeString(output.resolve("reference.txt"),"Documented Factorio cone and default octave/amplitude equations, evaluated with a gradient-noise substitute.\n"
                +"Not actual Factorio engine output; no Factorio executable was used.\n"
                +"100 source-model spots, point-sampled at maximum diameter: unique 5-block masks="+small.size()+"; 7-block masks="+large.size()+".\n");
    }
    private static double field(long seed,double x,double z) {
        double cone=Math.max(-.75,1-Math.hypot(x,z)/24);
        return cone+(CompactDepositGeometry.basis(seed+31,x,z,8)+CompactDepositGeometry.basis(seed+31,x,z,24)
                +1.5*CompactDepositGeometry.basis(seed+31,x,z,64)-1.0/3)/8;
    }
}
