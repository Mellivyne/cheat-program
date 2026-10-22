package com.cheatprogram.capture;

import com.mojang.blaze3d.platform.NativeImage;
import com.cheatprogram.compat.gl.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import com.cheatprogram.compat.gl.RenderSystem;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.cheatprogram.core.Config;

/** Window capture and explicitly enabled input are isolated in a bounded, local native helper. */
public final class AppCaptureEngine {
    public record Window(long handle,int pid,String process,String title,boolean minimized,String sourceKind,String sourceId) {
        public Window(long handle,int pid,String process,String title,boolean minimized){this(handle,pid,process,title,minimized,"window","");}
        public Window { if(!"window".equals(sourceKind)&&!"monitor".equals(sourceKind))throw new IllegalArgumentException("Unknown capture source kind");sourceId=sourceId==null?"":sourceId; }
        public boolean desktop(){return sourceKind.equals("monitor");}
        public String sourceKey(){return sourceKind+":"+handle+":"+pid+":"+sourceId;}
        public String label(){return title+" · "+process;}
    }
    public record Texture(Identifier id,int width,int height,int clientWidth,int clientHeight) { }
    private record Frame(NativeImage pixels,int clientWidth,int clientHeight,long number,long viewHandle) { }
    private record RawFrame(byte[] pixels,int width,int height,int clientWidth,int clientHeight,long number,long viewHandle,long revision,long viewEpoch) { }
    public static final int MAX_EDGE=8192,MAX_PIXELS=33554432,MAX_BYTES=MAX_PIXELS*4;
    private static final int MAGIC=0x33534341;
    private static final Minecraft MC=Minecraft.getInstance();
    private static final ExecutorService IO=Executors.newCachedThreadPool(job->{Thread t=new Thread(job,"CheatProgram-window-capture");t.setDaemon(true);return t;});
    private static final ScheduledExecutorService WATCH=Executors.newSingleThreadScheduledExecutor(job->{Thread t=new Thread(job,"CheatProgram-capture-watchdog");t.setDaemon(true);return t;});
    private static final long OWNER=ProcessHandle.current().pid();
    private static final AtomicLong TEXTURE_SERIAL=new AtomicLong();
    private static Path helper;
    private final AtomicReference<Frame> pending=new AtomicReference<>();
    private volatile Session session;
    private volatile String status="ui.appcast.idle",inputStatus="ui.appcast.inputIdle";
    private volatile long receivedFrames,lastFrameNumber,lastCaptureMillis,changedAt,rateStarted;
    private volatile int lastHash,backend,effectiveFps;private volatile double actualFps,displayFps;
    private int rateFrames;private boolean hashSeen;
    private DynamicTexture texture;private Texture image;private NativeImage uploading;private int uploadedRows;private long uploadingView;
    private long nextUpload,uploadedFrames,displayedFrame,displayRateStarted,lastDisplayNanos;private int displayRateFrames,uploadBudgetMiB=32;private boolean sharp,appliedSharp,textureReady,nativeFrameRate=true;
    private double wheelRemainderX,wheelRemainderY;
    public static boolean supported(){return System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("windows");}
    public String statusKey(){return status;}
    public String inputStatusKey(){return inputStatus;}
    public boolean running(){Session s=session;return s!=null&&!s.cancelled;}
    public long receivedFrames(){return receivedFrames;}
    public long frameNumber(){return lastFrameNumber;}
    public long uploadedFrames(){return uploadedFrames;}
    public long displayedFrame(){return displayedFrame;}
    public double actualFps(){return lastCaptureMillis==0||System.currentTimeMillis()-lastCaptureMillis>1500?0:actualFps;}
    public double displayFps(){return System.nanoTime()-lastDisplayNanos>1_500_000_000L?0:displayFps;}
    public int effectiveFps(){return effectiveFps;}
    public String backendKey(){return backend==2?"mode.appcast_wgc":backend==1?"mode.appcast_gdi":"mode.appcast_auto";}
    public long unchangedMillis(){return hashSeen?Math.max(0,System.currentTimeMillis()-changedAt):0;}
    public long lastCaptureMillis(){return lastCaptureMillis;}
    /** Metadata only; input callbacks must never trigger a texture upload. */
    public int cachedClientWidth(){Texture current=image;return current==null?1:current.clientWidth;}
    public int cachedClientHeight(){Texture current=image;return current==null?1:current.clientHeight;}
    public Window currentWindow(){Session owner=session;return owner==null?null:owner.activeWindow;}
    public boolean rootProcessAlive(){Session owner=session;return owner!=null&&!owner.window.desktop()&&owner.rootStart.isPresent()
            &&ProcessHandle.of(owner.window.pid()).filter(ProcessHandle::isAlive).flatMap(process->process.info().startInstant()).equals(owner.rootStart);}
    public void sharp(boolean enabled){sharp=enabled;}
    public void uploadBudget(int mib){uploadBudgetMiB=Math.max(4,Math.min(128,mib));}
    public void nativeFrameRate(boolean enabled){nativeFrameRate=enabled;}
    public static CompletableFuture<List<Window>> windows(){return CompletableFuture.supplyAsync(()->{
        if(!supported()||!Config.persistenceAllowed())throw new CompletionException(new IOException("Window capture unavailable"));Process process=null;
        try{
            process=launch("list",Long.toString(OWNER));Process active=process;IO.execute(()->discard(active.getErrorStream()));
            ScheduledFuture<?> timeout=WATCH.schedule(active::destroyForcibly,12,TimeUnit.SECONDS);List<Window> found=new ArrayList<>();
            try(BufferedReader reader=process.inputReader(StandardCharsets.UTF_8)){String line;while((line=reader.readLine())!=null){String[] fields=line.split("\t",-1);if(fields.length!=5&&fields.length!=7)continue;
                try{found.add(new Window(Long.parseLong(fields[0]),Integer.parseInt(fields[1]),decode(fields[2]),decode(fields[3]),fields[4].equals("1"),fields.length==7?fields[5]:"window",fields.length==7?decode(fields[6]):""));}catch(RuntimeException ignored){}}}
            finally{timeout.cancel(false);}
            if(!process.waitFor(2,TimeUnit.SECONDS)||process.exitValue()!=0)throw new IOException("Window enumeration failed");
            found.sort(Comparator.comparing(Window::title,String.CASE_INSENSITIVE_ORDER));return List.copyOf(found);
        }catch(Exception e){throw new CompletionException(e);}finally{if(process!=null&&process.isAlive())process.destroyForcibly();}
    },IO);}
    private static String decode(String value){return new String(Base64.getDecoder().decode(value),StandardCharsets.UTF_8);}
    private static synchronized Path helper()throws IOException{
        synchronized(Config.persistenceLock()){
        if(!Config.persistenceAllowed())throw new IOException("Restart required after initialization");
        if(helper!=null&&Files.isRegularFile(helper))return helper;
        Path directory=FabricLoader.getInstance().getConfigDir().resolve("cheatprogram/runtime");Files.createDirectories(directory);
        byte[] bytes;
        try(InputStream resource=AppCaptureEngine.class.getResourceAsStream("/assets/cheatprogram/capture/window-capture.exe")){
            if(resource==null)throw new IOException("Missing window capture helper");bytes=resource.readAllBytes();}
        String hash;try{hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0,16);}catch(NoSuchAlgorithmException impossible){throw new IOException(impossible);}
        Path target=directory.resolve("window-capture-v12-"+hash+".exe");
        if(!Files.isRegularFile(target)||!Arrays.equals(Files.readAllBytes(target),bytes))Files.write(target,bytes);
        helper=target;return target;}
    }
    private static Process launch(String...args)throws IOException{List<String> command=new ArrayList<>();command.add(helper().toString());command.addAll(List.of(args));return new ProcessBuilder(command).start();}
    public synchronized void start(Window window,int maxEdge,int fps,boolean nativeSize,int maxPixels,boolean allowInput,String requestedBackend){
        stop();if(!supported()||!Config.persistenceAllowed()){status="ui.appcast.unsupported";return;}
        Session next=new Session(window,allowInput&&!window.desktop());session=next;status="ui.appcast.starting";inputStatus=window.desktop()?"ui.appcast.desktopViewOnly":next.allowInput?"ui.appcast.inputReady":"ui.appcast.inputDisabled";
        receivedFrames=lastFrameNumber=lastCaptureMillis=rateStarted=0;rateFrames=0;actualFps=0;hashSeen=false;backend=0;
        next.monitor=WATCH.scheduleAtFixedRate(()->{if(session==next&&!next.cancelled&&System.nanoTime()-next.lastPacket>15_000_000_000L){status="ui.appcast.timeout";next.lastPacket=System.nanoTime();Process hung=next.process;if(hung!=null)closeHelper(hung);}},2,1,TimeUnit.SECONDS);
        IO.execute(()->capture(next,Math.max(320,Math.min(MAX_EDGE,maxEdge)),Math.max(0,Math.min(120,fps)),nativeSize,Math.max(1048576,Math.min(MAX_PIXELS,maxPixels)),requestedBackend));
    }
    /** Resume one existing source session without discarding its last complete texture. */
    public synchronized void requestReconnect(){
        Session owner=session;if(owner==null||owner.cancelled||!Config.persistenceAllowed())return;
        owner.reconnectRevision++;owner.decoder.clear();owner.lastPacket=System.nanoTime();
        wheelRemainderX=wheelRemainderY=0;owner.move.set(null);owner.commands.clear();owner.commands.offer("R");
        status="ui.appcast.waitingFrame";inputStatus=owner.window.desktop()?"ui.appcast.desktopViewOnly":"ui.appcast.inputReleased";
        // This reference belongs to this attempt; a later attempt/session must never
        // be terminated by a delayed cleanup of the previous helper.
        Process previous=owner.process;if(previous!=null&&previous.isAlive())closeHelper(previous);
    }
    private void capture(Session owner,int edge,int fps,boolean nativeSize,int maxPixels,String requestedBackend){
        try{while(session==owner&&!owner.cancelled&&Config.persistenceAllowed()){
            synchronized(this){if(session!=owner||owner.cancelled)return;owner.lastPacket=System.nanoTime();owner.viewEpoch++;owner.decoder.clear();owner.viewReady=false;Frame obsolete=pending.getAndSet(null);if(obsolete!=null)obsolete.pixels.close();}
            captureAttempt(owner,edge,fps,nativeSize,maxPixels,requestedBackend);
            if(session!=owner||owner.cancelled||status.equals("ui.appcast.closed")||!Config.persistenceAllowed())break;
            Thread.sleep(1000);
        }}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}finally{owner.cancel();}
    }
    private void captureAttempt(Session owner,int edge,int fps,boolean nativeSize,int maxPixels,String requestedBackend){
        Process attempt=null;long revision=owner.reconnectRevision;
        try{
            if(owner.cancelled)return;String backendArg=requestedBackend.equals("appcast_gdi")?"gdi":requestedBackend.equals("appcast_wgc")?"wgc":"auto";
            Window target=owner.activeWindow;
            Process p=launch("capture",Long.toString(target.handle()),Integer.toString(target.pid()),Integer.toString(edge),Integer.toString(fps),Long.toString(OWNER),nativeSize?"1":"0",Integer.toString(maxPixels),owner.allowInput?"1":"0",backendArg,owner.window.sourceKind(),Base64.getEncoder().encodeToString(owner.window.sourceId().getBytes(StandardCharsets.UTF_8)),Long.toString(owner.window.handle()),Integer.toString(owner.window.pid()));
            attempt=p;owner.process=p;if(owner.cancelled||session!=owner||owner.reconnectRevision!=revision){p.destroyForcibly();return;}IO.execute(()->diagnostics(owner,p.getErrorStream()));IO.execute(()->writeCommands(owner,p));
            try(DataInputStream input=new DataInputStream(new BufferedInputStream(p.getInputStream(),256*1024))){while(!owner.cancelled&&owner.reconnectRevision==revision){
                int magic=readInt(input),code=readInt(input),w=readInt(input),h=readInt(input),cw=readInt(input),ch=readInt(input),length=readInt(input),source=readInt(input),hash=readInt(input);
                long number=readLong(input),time=readLong(input);owner.lastPacket=System.nanoTime();
                if(owner.cancelled||session!=owner||owner.reconnectRevision!=revision)break;
                if(magic!=MAGIC||w<0||h<0||w>MAX_EDGE||h>MAX_EDGE||cw<0||ch<0||cw>MAX_EDGE||ch>MAX_EDGE||length<0||length>MAX_BYTES||(long)w*h>MAX_PIXELS)throw new IOException("Invalid capture frame");
                if(code==20){
                    if(owner.window.desktop()||length<1||length>16384||number==0)throw new IOException("Invalid followed window");
                    byte[] metadata=input.readNBytes(length);if(metadata.length!=length)throw new EOFException();
                    String[] fields=new String(metadata,StandardCharsets.UTF_8).split("\t",-1);
                    if(fields.length!=4)throw new IOException("Invalid followed window identity");
                    int pid=Integer.parseInt(fields[0]);if(pid<=4||pid==OWNER)throw new IOException("Unsafe followed window");
                    synchronized(this){if(session!=owner||owner.cancelled||owner.reconnectRevision!=revision)break;owner.viewEpoch++;owner.decoder.clear();owner.activeWindow=new Window(number,pid,decode(fields[1]),decode(fields[2]),false);
                        owner.viewReady=false;Frame obsolete=pending.getAndSet(null);if(obsolete!=null)obsolete.pixels.close();}
                    long gameWindow=Long.parseLong(fields[3]);Window followed=owner.activeWindow;
                    if(gameWindow!=0)MC.execute(()->{if(session==owner&&!owner.cancelled&&owner.activeWindow.handle()==followed.handle())AppInputFocus.restoreAfterProjectedView(followed.handle(),gameWindow);});
                    owner.move.set(null);owner.commands.clear();wheelRemainderX=wheelRemainderY=0;
                    status="ui.appcast.waitingFrame";inputStatus="ui.appcast.inputReady";continue;
                }
                if(code>=100){if(length!=0)throw new IOException("Invalid input acknowledgement");if(session==owner)inputStatus=owner.window.desktop()?"ui.appcast.desktopViewOnly":switch(code){case 100->"ui.appcast.inputQueued";case 101->"ui.appcast.inputDenied";case 102->"ui.appcast.inputRejected";case 103->"ui.appcast.inputReleased";case 105->"ui.appcast.scrollUnsupported";case 106->"ui.appcast.focusProtected";default->"ui.appcast.inputFailed";};continue;}
                if(!updateStatus(owner,code,source))break;
                if(length==0){if(code==3)break;continue;}
                if((code!=1&&code!=8)||w<2||h<2||cw<2||ch<2||length!=(long)w*h*4)throw new IOException("Invalid capture dimensions");
                byte[] pixels=new byte[length];input.readFully(pixels);
                if(owner.cancelled||session!=owner||owner.reconnectRevision!=revision)break;
                recordFrame(owner,number,time,hash,w,h,fps);
                owner.decoder.offer(new RawFrame(pixels,w,h,cw,ch,number,owner.activeWindow.handle(),revision,owner.viewEpoch));
            }}
            if(session==owner&&!owner.cancelled&&owner.reconnectRevision==revision&&!status.equals("ui.appcast.closed"))status="ui.appcast.failed";
        }catch(Throwable e){if(session==owner&&!owner.cancelled&&owner.reconnectRevision==revision)status="ui.appcast.failed";}finally{if(owner.process==attempt)owner.process=null;if(attempt!=null&&attempt.isAlive())closeHelper(attempt);}
    }
    private boolean current(Session owner,RawFrame raw){return session==owner&&!owner.cancelled&&owner.reconnectRevision==raw.revision&&owner.viewEpoch==raw.viewEpoch&&owner.activeWindow.handle()==raw.viewHandle;}
    /** Conversion never blocks the pipe reader. There is one latest raw frame,
     * not a backlog of frames decoded long after the source has moved on. */
    private void decodeFrame(Session owner,RawFrame raw){
        if(!current(owner,raw))return;byte[] pixels=raw.pixels;NativeImage frame=new NativeImage(raw.width,raw.height,false);boolean handed=false;
        try{for(int offset=0;offset<pixels.length;offset+=4){byte blue=pixels[offset];pixels[offset]=pixels[offset+2];pixels[offset+2]=blue;pixels[offset+3]=(byte)255;}
            if(!current(owner,raw))return;
            org.lwjgl.system.MemoryUtil.memByteBuffer(((com.cheatprogram.mixin.NativeImagePixelsAccessor)(Object)frame).cheatprogram$pixels(),pixels.length).put(pixels);
            synchronized(this){if(current(owner,raw))handed=offer(owner,new Frame(frame,raw.clientWidth,raw.clientHeight,raw.number,raw.viewHandle));}
        }finally{if(!handed)frame.close();}
    }
    /** EOF releases native keys and restores this helper's source window styles. */
    private static void closeHelper(Process process){
        try{process.getOutputStream().close();}catch(IOException ignored){ }
        WATCH.schedule(()->{if(process.isAlive())process.destroyForcibly();},1000,TimeUnit.MILLISECONDS);
    }
    private void recordFrame(Session owner,long number,long time,int hash,int w,int h,int requestedFps){
        if(session!=owner||owner.cancelled)return;long now=System.nanoTime();receivedFrames++;lastFrameNumber=number;lastCaptureMillis=time;
        effectiveFps=requestedFps;
        if(!hashSeen||lastHash!=hash){lastHash=hash;changedAt=System.currentTimeMillis();hashSeen=true;}
        if(rateStarted==0)rateStarted=now;rateFrames++;if(now-rateStarted>=1_000_000_000L){actualFps=rateFrames*1_000_000_000.0/(now-rateStarted);rateFrames=0;rateStarted=now;}
    }
    private static int readInt(DataInputStream input)throws IOException{return Integer.reverseBytes(input.readInt());}
    private static long readLong(DataInputStream input)throws IOException{return Long.reverseBytes(input.readLong());}
    private boolean updateStatus(Session owner,int code,int source){
        if(session!=owner||owner.cancelled)return false;backend=source;
        status=switch(code){case 1->"ui.appcast.live";case 2->"ui.appcast.minimized";case 3->"ui.appcast.closed";case 4->"ui.appcast.cloaked";case 5->"ui.appcast.protected";case 6->"ui.appcast.dimensions";case 8->"ui.appcast.black";case 12->"ui.appcast.waitingFrame";case 13->"ui.appcast.wgcUnavailable";case 14->"ui.appcast.sourceStalled";default->"ui.appcast.failed";};return true;
    }
    private synchronized boolean offer(Session owner,Frame frame){if(session!=owner||owner.cancelled)return false;Frame old=pending.getAndSet(frame);if(old!=null)old.pixels.close();return true;}
    private static void discard(InputStream stream){try(stream){byte[] bytes=new byte[2048];while(stream.read(bytes)!=-1){}}catch(IOException ignored){}}
    private void diagnostics(Session owner,InputStream stream){try(BufferedReader reader=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8))){String line;while((line=reader.readLine())!=null){long now=System.nanoTime();if(session==owner&&!owner.cancelled&&line.matches("WGC: [A-Za-z0-9]+ \\(0x[0-9A-F]{8}\\)( stage=[a-z-]{1,40})?")&&now-owner.lastDiagnostic>30_000_000_000L){owner.lastDiagnostic=now;com.cheatprogram.CheatProgram.LOGGER.warn("Application live capture is reconnecting: {}",line);}}}catch(IOException ignored){}}
    private void writeCommands(Session owner,Process process){
        Thread writerThread=Thread.currentThread();owner.writer=writerThread;
        try(BufferedWriter writer=new BufferedWriter(new OutputStreamWriter(process.getOutputStream(),StandardCharsets.US_ASCII))){
            while(!owner.cancelled&&session==owner&&owner.process==process&&process.isAlive()){String command=owner.commands.poll();if(command==null)command=owner.move.getAndSet(null);
                if(owner.process!=process||session!=owner)break;
                if(owner.cancelled||"Q".equals(command)){writer.write("R\nQ\n");writer.flush();break;}
                if(command!=null){writer.write(command);writer.write('\n');writer.flush();}
                else java.util.concurrent.locks.LockSupport.parkNanos(100_000_000L);}
        }catch(Exception ignored){}finally{
            if(owner.writer==writerThread)owner.writer=null;
            // Let R/Q release posted keys, but never block an input writer or look
            // up owner.process after a replacement helper has already started.
            if(process.isAlive())WATCH.schedule(()->{if(process.isAlive())process.destroyForcibly();},700,TimeUnit.MILLISECONDS);
        }
    }
    private boolean command(String command,boolean move){Session owner=session;if(owner==null||owner.cancelled||!owner.allowInput||!owner.viewReady)return false;
        if(move){owner.move.set(command);owner.wakeWriter();return true;}
        // Preserve the last drag position before a button/key/wheel boundary.
        // Dropping it could turn a short press-drag-release into a plain click.
        String precedingMove=owner.move.getAndSet(null);
        if(precedingMove!=null&&!owner.commands.offer(precedingMove)){inputStatus="ui.appcast.inputBusy";releaseInput();return false;}
        if(owner.commands.offer(command)){owner.wakeWriter();return true;}inputStatus="ui.appcast.inputBusy";releaseInput();return false;}
    private int[] clientPoint(double u,double v){Texture current=image;if(current==null)return null;return new int[]{Math.max(0,Math.min(current.clientWidth-1,(int)Math.floor(u*current.clientWidth))),Math.max(0,Math.min(current.clientHeight-1,(int)Math.floor(v*current.clientHeight)))};}
    public boolean move(double u,double v){int[] p=clientPoint(u,v);return p!=null&&command("M\t"+p[0]+"\t"+p[1],true);}
    /** Drag samples are ordered with button boundaries; hover alone may be coalesced. */
    public boolean drag(double u,double v){int[] p=clientPoint(u,v);return p!=null&&command("M\t"+p[0]+"\t"+p[1],false);}
    public boolean button(int button,boolean down,double u,double v){if(button<0||button>4)return false;int[] p=clientPoint(u,v);return p!=null&&command("B\t"+button+"\t"+(down?1:0)+"\t"+p[0]+"\t"+p[1],false);}
    public boolean scroll(double horizontal,double vertical,double u,double v){int[] p=clientPoint(u,v);if(p==null||!Double.isFinite(horizontal)||!Double.isFinite(vertical))return false;wheelRemainderX+=Math.max(-100,Math.min(100,horizontal))*120;wheelRemainderY+=Math.max(-100,Math.min(100,vertical))*120;int sx=(int)wheelRemainderX,sy=(int)wheelRemainderY;wheelRemainderX-=sx;wheelRemainderY-=sy;if(sx==0&&sy==0)return true;return command("W\t"+sx+"\t"+sy+"\t"+p[0]+"\t"+p[1],false);}
    public boolean key(int glfwKey,boolean down,boolean repeat){int vk=AppInputKeys.virtualKey(glfwKey);return vk!=0&&command("K\t"+vk+"\t"+(down?1:0)+"\t"+(repeat?1:0),false);}
    public boolean character(char value){return value>=32&&value!=127&&command("C\t"+(int)value,false);}
    /** Committed text/paste uses Unicode, independently of the keyboard layout of the target. */
    public boolean text(String value){if(value==null||value.isEmpty())return false;String limited=value.substring(0,Math.min(8192,value.length())).replace("\r\n","\n");return command("T\t"+Base64.getEncoder().encodeToString(limited.getBytes(StandardCharsets.UTF_8)),false);}
    public void releaseInput(){wheelRemainderX=wheelRemainderY=0;Session owner=session;if(owner!=null&&!owner.cancelled){owner.move.set(null);owner.commands.clear();owner.commands.offer("R");owner.wakeWriter();}inputStatus=owner!=null&&owner.window.desktop()?"ui.appcast.desktopViewOnly":"ui.appcast.inputReleased";}
    /** Selecting another independent projection ends this source's unconsumed follow intent. */
    public void cancelWindowFollow(){releaseInput();Session owner=session;if(owner!=null&&!owner.cancelled){owner.commands.offer("F");owner.wakeWriter();}}
    private final class Session{
        final Window window;final Optional<Instant> rootStart;volatile Window activeWindow;final boolean allowInput;final ArrayBlockingQueue<String> commands=new ArrayBlockingQueue<>(256);final AtomicReference<String> move=new AtomicReference<>();
        volatile Process process;volatile Thread writer;volatile boolean cancelled,viewReady;volatile long lastPacket=System.nanoTime(),lastDiagnostic,reconnectRevision,viewEpoch;volatile ScheduledFuture<?> monitor;final AtomicBoolean closing=new AtomicBoolean();
        final LatestFrameDecoder<RawFrame> decoder=new LatestFrameDecoder<>(IO,raw->decodeFrame(this,raw));
        Session(Window window,boolean allowInput){this.window=window;this.rootStart=window.pid()>0?ProcessHandle.of(window.pid()).flatMap(process->process.info().startInstant()):Optional.empty();this.activeWindow=window;this.allowInput=allowInput;}
        void wakeWriter(){Thread current=writer;if(current!=null)java.util.concurrent.locks.LockSupport.unpark(current);}
        void cancel(){if(!closing.compareAndSet(false,true))return;cancelled=true;decoder.close();commands.clear();commands.offer("Q");wakeWriter();ScheduledFuture<?> task=monitor;if(task!=null)task.cancel(false);Process p=process;if(p!=null)WATCH.schedule(()->{if(p.isAlive())p.destroyForcibly();},1000,TimeUnit.MILLISECONDS);}
    }
    /** Render thread only. Lossless native frames are uploaded in bounded strips, without full-frame CPU copies. */
    public Texture image(){
        if(uploading==null&&pending.get()==null)return visibleImage();
        RenderSystem.assertOnRenderThread();
        int active=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0);
        int bound=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D),unpack=GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        int alignment=GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT),rowLength=GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH),skipRows=GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS),skipPixels=GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS);
        try{GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER,0);return uploadImage();}
        finally{GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT,alignment);GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH,rowLength);GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS,skipRows);GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS,skipPixels);GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER,unpack);GlStateManager._bindTexture(bound);RenderSystem.activeTexture(active);}
    }
    private Texture uploadImage(){
        long now=System.nanoTime();if(!nativeFrameRate&&now<nextUpload)return visibleImage();nextUpload=now+8_000_000L;
        if(uploading==null){Frame next=pending.getAndSet(null);if(next!=null){NativeImage frame=next.pixels;int limit=GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
            if(frame.getWidth()>limit||frame.getHeight()>limit){frame.close();status="ui.appcast.gpuLimit";return visibleImage();}
            if(texture==null||image==null||image.width!=frame.getWidth()||image.height!=frame.getHeight()){
                releaseTexture();Identifier id=Identifier.fromNamespaceAndPath("cheatprogram","dynamic/appcast/"+TEXTURE_SERIAL.incrementAndGet());
                texture=new DynamicTexture(() -> "Cheat Program texture", frame.getWidth(),frame.getHeight(),false);MC.getTextureManager().register(id,texture);
                image=new Texture(id,frame.getWidth(),frame.getHeight(),next.clientWidth,next.clientHeight);
            }else image=new Texture(image.id,image.width,image.height,next.clientWidth,next.clientHeight);
            texture.setPixels(frame);uploading=frame;uploadingView=next.viewHandle;uploadedRows=0;displayedFrame=next.number;
        }}
        if(texture!=null){if(appliedSharp!=sharp||uploading!=null&&uploadedRows==0){com.cheatprogram.compat.gl.RenderSystem.setTextureFilter(texture, !sharp, false);appliedSharp=sharp;}
            if(uploading!=null){int rows=nativeFrameRate?uploading.getHeight()-uploadedRows:Math.min(uploading.getHeight()-uploadedRows,Math.max(1,(uploadBudgetMiB*1024*1024)/(uploading.getWidth()*4)));
                com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder()
                    .writeToTexture(texture.getTexture(),uploading,0,0,0,uploadedRows,uploading.getWidth(),rows,0,uploadedRows);
                uploadedRows+=rows;
                if(uploadedRows>=uploading.getHeight()){uploading=null;textureReady=true;Session owner=session;if(owner!=null&&owner.activeWindow.handle()==uploadingView)owner.viewReady=true;uploadedFrames++;lastDisplayNanos=now;displayRateFrames++;if(displayRateStarted==0)displayRateStarted=now;if(now-displayRateStarted>=1_000_000_000L){displayFps=displayRateFrames*1_000_000_000.0/(now-displayRateStarted);displayRateFrames=0;displayRateStarted=now;}}}}
        return visibleImage();
    }
    private Texture visibleImage(){return textureReady&&image!=null&&(status.equals("ui.appcast.live")||status.equals("ui.appcast.black")||status.equals("ui.appcast.waitingFrame")||status.equals("ui.appcast.sourceStalled")||status.equals("ui.appcast.wgcUnavailable")||status.equals("ui.appcast.timeout")||status.equals("ui.appcast.failed"))?image:null;}
    public synchronized void stop(){Session old=session;session=null;if(old!=null)old.cancel();Frame frame=pending.getAndSet(null);if(frame!=null)frame.pixels.close();releaseTexture();status="ui.appcast.idle";inputStatus="ui.appcast.inputIdle";}
    private void releaseTexture(){uploading=null;uploadedRows=0;textureReady=false;if(image!=null)MC.getTextureManager().release(image.id());else if(texture!=null)texture.close();texture=null;image=null;}
}
