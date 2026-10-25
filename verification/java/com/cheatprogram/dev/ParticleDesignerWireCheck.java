package com.cheatprogram.dev;

import com.cheatprogram.ai.AiImage;
import com.cheatprogram.ai.AiParticleDesigner;
import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/** Local mock provider only: checks that image-aware HTTP responses become a real portable composition. */
public final class ParticleDesignerWireCheck {
    public static void run()throws Exception{
        JsonObject plan=JsonParser.parseString("{\"rounds\":[{\"name\":\"Mock fixture\",\"duration\":120,\"emitters\":[{\"particle\":\"minecraft:end_rod\",\"y\":1,\"path\":{\"kind\":\"helix\",\"radius\":1.2}}]}]}").getAsJsonObject();
        AtomicReference<Throwable> serverFailure=new AtomicReference<>();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",request->{
            try{
                if(!request.getRequestMethod().equals("POST"))throw new AssertionError("Wrong method");
                JsonObject body=JsonParser.parseString(new String(request.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
                if(!body.get("model").getAsString().equals("local-fixture-model"))throw new AssertionError("Wrong model");
                JsonArray content=body.getAsJsonArray("messages").get(0).getAsJsonObject().getAsJsonArray("content");
                if(content.size()!=2||!content.get(1).getAsJsonObject().getAsJsonObject("image_url").get("url").getAsString().startsWith("data:image/png;base64,"))throw new AssertionError("Image missing from request");
                JsonObject message=new JsonObject();message.addProperty("role","assistant");message.addProperty("content",plan.toString());
                JsonObject choice=new JsonObject();choice.add("message",message);choice.addProperty("finish_reason","stop");JsonArray choices=new JsonArray();choices.add(choice);JsonObject response=new JsonObject();response.add("choices",choices);
                byte[] bytes=response.toString().getBytes(StandardCharsets.UTF_8);request.getResponseHeaders().set("Content-Type","application/json");request.sendResponseHeaders(200,bytes.length);request.getResponseBody().write(bytes);
            }catch(Throwable error){serverFailure.set(error);request.sendResponseHeaders(500,-1);}finally{request.close();}
        });
        java.nio.file.Path file=java.nio.file.Files.createTempFile("particle-designer-wire-",".png");
        try{
            java.awt.image.BufferedImage pixel=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB);javax.imageio.ImageIO.write(pixel,"png",file.toFile());pixel.flush();
            AiImage image=AiImage.read(file);Class<?> protocol=Class.forName("com.cheatprogram.ai.AiProtocol");
            var message=protocol.getDeclaredMethod("message",String.class,String.class,java.util.List.class);message.setAccessible(true);
            JsonArray history=new JsonArray();history.add((JsonObject)message.invoke(null,"user","Design an orbit from this reference",java.util.List.of(image)));
            var exchange=protocol.getDeclaredMethod("exchange",HttpClient.class,URI.class,String.class,String.class,JsonArray.class,JsonArray.class,java.util.function.BooleanSupplier.class);exchange.setAccessible(true);
            server.start();URI endpoint=URI.create("http://"+server.getAddress().getAddress().getHostAddress()+":"+server.getAddress().getPort()+"/chat/completions");
            Object result=exchange.invoke(null,HttpClient.newHttpClient(),endpoint,"local-fixture-model","fixture-only",history,new JsonArray(),(java.util.function.BooleanSupplier)()->true);
            if(serverFailure.get()!=null)throw new AssertionError("Mock provider validation failed",serverFailure.get());
            var reply=result.getClass().getDeclaredMethod("reply");reply.setAccessible(true);JsonObject raw=(JsonObject)reply.invoke(result);
            var designed=AiParticleDesigner.decode(JsonParser.parseString(raw.get("content").getAsString()).getAsJsonObject());
            if(!designed.code().startsWith("CPS1.")||!designed.prepared().particles().rounds.getFirst().emitters.getFirst().path.kind.equals("helix"))throw new AssertionError("Wire result did not become editable particles");
            String data=designed.prepared().decoded().data().toString();if(data.contains("fixture-only")||data.contains("data:image")||data.contains(file.toString()))throw new AssertionError("Private request information leaked");
            System.out.println("PARTICLE_DESIGNER_WIRE_CHECK_PASS local-http image-request real-particle-plan portable-code no-credentials-or-images");
        }finally{server.stop(0);java.nio.file.Files.deleteIfExists(file);}
    }
}
