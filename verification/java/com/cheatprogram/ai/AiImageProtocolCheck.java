package com.cheatprogram.ai;

import com.google.gson.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.concurrent.Executors;

/** Local fixture verifies actual multimodal payloads, cancellation and private attachment handling. */
public final class AiImageProtocolCheck {
    public static void run()throws Exception{
        var file=Files.createTempFile("ai-image-fixture-",".png");
        try{
            BufferedImage png=new BufferedImage(8,4,BufferedImage.TYPE_INT_ARGB);png.setRGB(2,1,0xff123456);javax.imageio.ImageIO.write(png,"png",file.toFile());png.flush();
            AiImage image=AiImage.read(file);
            if(image.width()!=8||image.height()!=4||!image.dataUrl().startsWith("data:image/png;base64,"))throw new AssertionError("Image was not encoded");
            if(image.toString().contains(file.toString()))throw new AssertionError("Attachment debug text exposed path");
            JsonArray history=new JsonArray();history.add(AiProtocol.message("system","fixture"));history.add(AiProtocol.message("user","Inspect this picture",List.of(image)));
            JsonObject chat=AiProtocol.request("vision-fixture",false,history,new JsonArray());
            var parts=chat.getAsJsonArray("messages").get(1).getAsJsonObject().getAsJsonArray("content");
            if(!parts.get(1).getAsJsonObject().getAsJsonObject("image_url").get("url").getAsString().equals(image.dataUrl()))throw new AssertionError("Chat request lost image bytes");
            JsonObject responses=AiProtocol.request("vision-fixture",true,history,new JsonArray());
            var imagePart=responses.getAsJsonArray("input").get(1).getAsJsonObject().getAsJsonArray("content").get(1).getAsJsonObject();
            if(!imagePart.get("type").getAsString().equals("input_image")||!imagePart.get("image_url").getAsString().equals(image.dataUrl()))throw new AssertionError("Responses request lost image bytes");
            if(chat.toString().contains(file.getFileName().toString())||responses.toString().contains(file.toString()))throw new AssertionError("Local path sent to provider");
            var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
            var executor=Executors.newCachedThreadPool(r->{Thread t=new Thread(r,"AI image protocol fixture");t.setDaemon(true);return t;});server.setExecutor(executor);
            java.util.concurrent.atomic.AtomicReference<JsonObject> received=new java.util.concurrent.atomic.AtomicReference<>();
            server.createContext("/chat/completions",exchange->{
                received.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject());
                byte[] reply="{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"fixture accepted\"}}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,reply.length);exchange.getResponseBody().write(reply);exchange.close();
            });
            server.createContext("/unsupported",exchange->{exchange.getRequestBody().readAllBytes();byte[] body="{}".getBytes();exchange.sendResponseHeaders(415,body.length);exchange.getResponseBody().write(body);exchange.close();});
            server.createContext("/slow",exchange->{try{Thread.sleep(2000);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}try{exchange.sendResponseHeaders(500,-1);}catch(Exception ignored){}finally{exchange.close();}});
            server.start();
            try{
                String base="http://127.0.0.1:"+server.getAddress().getPort();
                var result=AiProtocol.exchange(HttpClient.newHttpClient(),URI.create(base+"/chat/completions"),"vision-fixture","fixture-key",history,new JsonArray(),()->true);
                if(!result.reply().get("content").getAsString().equals("fixture accepted")||received.get()==null||!received.get().getAsJsonArray("messages").get(1).getAsJsonObject().get("content").isJsonArray())throw new AssertionError("HTTP image payload not received");
                try{AiProtocol.exchange(HttpClient.newHttpClient(),URI.create(base+"/unsupported"),"vision-fixture","fixture-key",history,new JsonArray(),()->true);throw new AssertionError("Vision rejection was hidden");}catch(AiProtocol.ProtocolException expected){if(!expected.getMessage().equals(com.cheatprogram.core.Lang.t("ui.ai.images.unsupported")))throw expected;}
                long started=System.nanoTime();
                try{AiProtocol.exchange(HttpClient.newHttpClient(),URI.create(base+"/slow"),"fixture","fixture-key",history,new JsonArray(),()->System.nanoTime()-started<150_000_000L);throw new AssertionError("Cancelled request completed");}catch(InterruptedException expected){if(System.nanoTime()-started>1_000_000_000L)throw new AssertionError("Image request cancellation blocked");}
            }finally{server.stop(0);executor.shutdownNow();}
            System.out.println("AI_IMAGE_CHECK_PASS png-read chat-bytes responses-bytes local-path-excluded http-payload vision-rejection cancellation");
        }finally{Files.deleteIfExists(file);}
    }
}
