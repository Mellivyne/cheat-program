package com.cheatprogram.ai;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/** Explicit image attachment held only in memory. Provider payloads contain no path or EXIF metadata. */
public final class AiImage {
    public static final int MAX_ATTACHMENTS=8;
    private static final long MAX_BYTES=20L*1024*1024,MAX_PIXELS=24_000_000;
    private final String dataUrl,label;
    private final int width,height;
    private final byte[] thumbnail;
    private AiImage(String dataUrl,String label,int width,int height,byte[] thumbnail){this.dataUrl=dataUrl;this.label=label;this.width=width;this.height=height;this.thumbnail=thumbnail;}
    public String dataUrl(){return dataUrl;}
    public String label(){return label;}
    public int width(){return width;}
    public int height(){return height;}
    public byte[] thumbnailPng(){return thumbnail.clone();}
    @Override public String toString(){return "AiImage["+width+"x"+height+", memory only]";}
    public static AiImage read(Path file)throws IOException{
        if(file==null||!Files.isRegularFile(file)||Files.size(file)<1||Files.size(file)>MAX_BYTES)throw new IOException("ui.ai.images.invalid");
        BufferedImage image;
        try(var input=ImageIO.createImageInputStream(file.toFile())){
            if(input==null)throw new IOException("ui.ai.images.invalid");
            var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IOException("ui.ai.images.invalid");
            ImageReader reader=readers.next();
            try{reader.setInput(input,true,true);int w=reader.getWidth(0),h=reader.getHeight(0);if(w<1||h<1||(long)w*h>MAX_PIXELS)throw new IOException("ui.ai.images.invalid");image=reader.read(0);}
            finally{reader.dispose();}
        }catch(IOException e){throw new IOException("ui.ai.images.invalid");}
        if(image==null)throw new IOException("ui.ai.images.invalid");
        try{
            ByteArrayOutputStream encoded=new ByteArrayOutputStream();
            if(!ImageIO.write(image,"png",encoded)||encoded.size()>MAX_BYTES)throw new IOException("ui.ai.images.invalid");
            int w=image.getWidth(),h=image.getHeight();float ratio=Math.min(1,192f/Math.max(w,h));
            BufferedImage preview=new BufferedImage(Math.max(1,Math.round(w*ratio)),Math.max(1,Math.round(h*ratio)),BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics=preview.createGraphics();try{graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);graphics.drawImage(image,0,0,preview.getWidth(),preview.getHeight(),null);}finally{graphics.dispose();}
            ByteArrayOutputStream thumb=new ByteArrayOutputStream();try{ImageIO.write(preview,"png",thumb);}finally{preview.flush();}
            return new AiImage("data:image/png;base64,"+Base64.getEncoder().encodeToString(encoded.toByteArray()),file.getFileName().toString(),w,h,thumb.toByteArray());
        }finally{image.flush();}
    }
}
