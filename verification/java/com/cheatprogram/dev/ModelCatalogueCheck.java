package com.cheatprogram.dev;
import com.cheatprogram.ai.AiPlayerModels;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
public final class ModelCatalogueCheck {
    private static com.sun.net.httpserver.HttpServer server;
    private static final UUID id=UUID.randomUUID();
    private static final AtomicInteger pages=new AtomicInteger();
    private static long started;
    private static String endpoint;
    public static void start()throws Exception{
        server=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r->{var t=new Thread(r,"model-catalogue-fixture");t.setDaemon(true);return t;}));
        server.createContext("/models",e->{
            if(!"Bearer local-fixture-key".equals(e.getRequestHeaders().getFirst("Authorization"))){e.sendResponseHeaders(401,-1);e.close();return;}
            pages.incrementAndGet();
            String body=e.getRequestURI().getQuery()==null?"{\"data\":[{\"id\":\"local-model-a\"}],\"next\":\"?page=2\"}":"{\"data\":[{\"id\":\"local-model-b\"}]}";
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();
        });
        server.start();endpoint="http://127.0.0.1:"+server.getAddress().getPort();started=System.nanoTime();AiPlayerModels.refresh(id,endpoint,"local-fixture-key");
    }
    public static boolean finish(){
        var state=AiPlayerModels.state(id);
        if(state.loading()){if(System.nanoTime()-started>10_000_000_000L)throw new AssertionError("Catalogue timeout");return false;}
        try{
            if(state.models().size()!=2||pages.get()!=2||!state.models().getFirst().usable())throw new AssertionError("Catalogue pagination/selection failed: "+state.status());
            AiPlayerModels.clear(id);
            if(!AiPlayerModels.state(id).models().isEmpty())throw new AssertionError("Stale models retained after invalidation");
            System.out.println("MODEL_CATALOGUE_PASS refresh-auth pagination selectable-models invalidation");return true;
        }finally{AiPlayerModels.clear(id);server.stop(0);}
    }
    public static void openPicker(Object screen,String field)throws Exception{
        var f=screen.getClass().getDeclaredField(field);f.setAccessible(true);Object picker=f.get(screen);
        var open=picker.getClass().getDeclaredField("open");open.setAccessible(true);open.setBoolean(picker,true);
    }
}
