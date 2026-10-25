package com.cheatprogram.dev;
import com.cheatprogram.render.*;
import com.cheatprogram.media.player.*;
import com.cheatprogram.media.player.MusicModels.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import com.mojang.blaze3d.platform.NativeImage;
import org.joml.Vector3f;
import org.joml.Matrix4f;
import java.util.*;
public final class AlbumSpaceCheck extends Screen {
    private final AlbumSpaceModel scene=new AlbumSpaceModel();
    private final MusicCoverCarouselRenderer renderer=new MusicCoverCarouselRenderer();
    private final MusicArtworkCache cache=new MusicArtworkCache();
    private final List<Track> tracks=new ArrayList<>();
    private static final List<ResourceLocation> FIXTURE_TEXTURES=new ArrayList<>();
    public AlbumSpaceCheck(String kind){super(Component.literal(kind));scene.reset(kind,45,100);
        try{
            var f=MusicArtworkCache.class.getDeclaredField("PUBLIC");f.setAccessible(true);var entries=(Map<String,Object>)f.get(null);
            Class<?> type=Class.forName("com.cheatprogram.media.player.MusicArtworkCache$Entry");var ctor=type.getDeclaredConstructor();ctor.setAccessible(true);var texture=type.getDeclaredField("texture");texture.setAccessible(true);
            int[] palettes={0xff3978d2,0xffe15f77,0xff38a678,0xffdb9a32,0xff8a64c9,0xff39a9b8,0xffcc5c31};
            for(int i=0;i<100;i++){String url="https://p1.music.126.net/local-layout-fixture-"+i+".png";Object entry=ctor.newInstance();
                ResourceLocation id;
                if(i<FIXTURE_TEXTURES.size())id=FIXTURE_TEXTURES.get(i);
                else{NativeImage pixels=new NativeImage(64,64,false);int base=palettes[i%palettes.length];
                    for(int py=0;py<64;py++)for(int px=0;px<64;px++){boolean accent=((px/8+py/8+i)%3)==0;int color=accent?0xfff1d8a5:base;pixels.setPixel(px,py,color);}
                    id=ResourceLocation.fromNamespaceAndPath("cheatprogram","test/albums/fixture_"+i);Minecraft.getInstance().getTextureManager().register(id,new DynamicTexture(pixels));FIXTURE_TEXTURES.add(id);}
                texture.set(entry,id);entries.put("netease:https://p1.music.126.net/local-layout-fixture-"+i+".png?param=512y512",entry);tracks.add(new Track(Provider.NETEASE,"fixture-"+i,"Album "+i,"Fixture","Fixture",url,1000));}
            run(kind);
        }catch(Exception e){throw new RuntimeException(e);}
    }
    private void run(String kind){
        if(scene.cards().size()>85)throw new AssertionError("Unbounded frame work");
        var projection=new Matrix4f().perspective((float)Math.toRadians(40),900f/420,.1f,40);
        int hits=0;for(var card:scene.cards()){
            var point=new Vector3f();new Matrix4f(projection).mul(card.matrix()).transformProject(point);
            float x=(point.x+1)*450,y=(1-point.y)*210;
            if(point.z> -1&&point.z<1&&x>=0&&y>=0&&x<900&&y<420&&renderer.hitSpace(scene,900,420,x,y)>=0)hits++;
        }
        if(hits<3)throw new AssertionError("Scene has no pickable visible covers: "+kind);
        scene.begin(10,10);scene.drag(180,80);if(scene.release(180,80))throw new AssertionError("Drag played a song");
        scene.reset(kind,45,100);scene.select(46);for(int i=0;i<120;i++)scene.update(.016f);
        if(scene.selected()!=46)throw new AssertionError("Selection lost");
        scene.reset(kind,45,100);System.out.println("ALBUM_SPACE_PASS "+kind+" hits="+hits+" bounded picking drag selection");
    }
    @Override public void render(GuiGraphics g,int x,int y,float partial){
        scene.update(.016f);R2D.begin(g);try{renderer.drawSpace(g,50,100,R2D.width()-100,R2D.height()-180,tracks,cache,scene,Float.NaN,Float.NaN,.016f);if(renderer.failed())throw new AssertionError("3D scene rendering failed");}finally{R2D.end();}
    }
    public static void cleanup(){for(ResourceLocation id:FIXTURE_TEXTURES)Minecraft.getInstance().getTextureManager().release(id);FIXTURE_TEXTURES.clear();}
    @Override public void removed(){renderer.close();cache.close();}
}
