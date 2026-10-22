package com.cheatprogram.media.player;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Map;

/** No network request is needed to turn a first-party login URL into a QR image. */
public final class MusicQr {
    private MusicQr(){ }
    public static byte[] png(String value)throws Exception{
        if(value==null||value.length()>4096)throw new IllegalArgumentException("Invalid QR input");
        var matrix=new QRCodeWriter().encode(value,BarcodeFormat.QR_CODE,320,320,
                Map.of(EncodeHintType.CHARACTER_SET,"UTF-8",EncodeHintType.MARGIN,4,EncodeHintType.ERROR_CORRECTION,ErrorCorrectionLevel.M));
        BufferedImage image=new BufferedImage(matrix.getWidth(),matrix.getHeight(),BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<matrix.getHeight();y++)for(int x=0;x<matrix.getWidth();x++)image.setRGB(x,y,matrix.get(x,y)?0xff000000:0xffffffff);
        ByteArrayOutputStream output=new ByteArrayOutputStream();ImageIO.write(image,"png",output);return output.toByteArray();
    }
}
