package com.cheatprogram.audio;

import com.cheatprogram.core.Config;
import com.sun.jna.*;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Optional local NVIDIA AFX denoiser. Never installs a driver, records a microphone, or sends audio away. */
public final class NvidiaAudioEffects implements AutoCloseable {
    private static final int RATE=48000;
    private Driver driver;
    private final Factory factory;
    private boolean attempted,closed;
    private byte[] pending=new byte[0];
    private volatile String status="ui.spatial.nvidia.idle";
    interface Driver extends AutoCloseable {int frames();void strength(float value)throws Exception;void run(float[] left,float[] right)throws Exception;void close();}
    interface Factory {Driver open()throws Exception;}
    public NvidiaAudioEffects(){this(NativeDriver::open);}
    NvidiaAudioEffects(Factory factory){this.factory=factory;}
    public String statusKey(){return status;}
    public static Path directory(){return Config.directory().resolve("spatial-audio/nvidia-sdk");}
    /** Called exclusively by the capture worker; null asks the caller to use its CPU denoiser. */
    public byte[] process(byte[] input,double strength){
        if(closed||input==null||input.length%4!=0)return null;
        if(!attempted){attempted=true;status="ui.spatial.nvidia.loading";try{driver=factory.open();if(driver==null)throw new IOException();}catch(Exception|LinkageError missing){status="ui.spatial.nvidia.unavailable";return null;}}
        if(driver==null)return null;
        try{
            int frames=driver.frames();if(frames<80||frames>1920)throw new IOException();
            int block=frames*4,total=pending.length+input.length;
            byte[] joined=Arrays.copyOf(pending,total);System.arraycopy(input,0,joined,pending.length,input.length);
            int complete=total-total%block;byte[] result=new byte[complete];
            driver.strength((float)Math.max(0,Math.min(1,Double.isFinite(strength)?strength:0)));
            float[] left=new float[frames],right=new float[frames];
            for(int start=0;start<complete;start+=block){
                for(int i=0;i<frames;i++){int p=start+i*4;left[i]=(short)((joined[p]&255)|(joined[p+1]<<8))/32768f;right[i]=(short)((joined[p+2]&255)|(joined[p+3]<<8))/32768f;}
                long began=System.nanoTime();driver.run(left,right);
                // Abandon a device that cannot keep up. Do not let a native queue grow without bound.
                if(System.nanoTime()-began>100_000_000L)throw new IOException();
                for(int i=0;i<frames;i++){int p=start+i*4;write(result,p,left[i]);write(result,p+2,right[i]);}
                status="ui.spatial.nvidia.active";
            }
            pending=Arrays.copyOfRange(joined,complete,total);return result;
        }catch(Exception|LinkageError failed){driver.close();driver=null;pending=new byte[0];status="ui.spatial.nvidia.failed";return null;}
    }
    private static void write(byte[] bytes,int at,float value)throws IOException{
        if(!Float.isFinite(value))throw new IOException();int v=Math.max(-32768,Math.min(32767,Math.round(value*32768)));bytes[at]=(byte)v;bytes[at+1]=(byte)(v>>8);
    }
    @Override public void close(){closed=true;if(driver!=null){driver.close();driver=null;}pending=new byte[0];}

    /** Implements the documented NvAFX 48 kHz planar-float API with independent left/right histories. */
    private static final class NativeDriver implements Driver {
        private NativeLibrary library;
        private Function create,destroy,setU32,setString,getU32,setFloat,setFloatList,load,run;
        private final Pointer[] handles=new Pointer[2];
        private Memory in,out,inPointers,outPointers;
        private int frameSize;
        private float intensity=Float.NaN;
        static Driver open()throws Exception{
            if(!System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("windows"))throw new IOException();
            List<Path> roots=new ArrayList<>();roots.add(directory());
            String configured=System.getenv("NVAFX_SDK_DIR");if(configured!=null&&!configured.isBlank())try{roots.add(Path.of(configured));}catch(InvalidPathException ignored){}
            String programs=System.getenv("ProgramFiles");if(programs!=null){
                roots.add(Path.of(programs,"NVIDIA Corporation","NVIDIA Audio Effects SDK"));
                roots.add(Path.of(programs,"NVIDIA Corporation","NVIDIA Broadcast SDKs","Audio Effects"));
            }
            for(Path root:roots){if(!Files.isDirectory(root))continue;
                try(var files=Files.find(root,5,(p,a)->a.isRegularFile())){
                    var list=files.limit(1024).toList();
                    Path dll=list.stream().filter(p->p.getFileName().toString().equalsIgnoreCase("NvAudioEffects.dll")).findFirst().orElse(null);
                    Path model=list.stream().filter(p->{String n=p.getFileName().toString().toLowerCase(Locale.ROOT);return n.contains("denoiser")&&!n.contains("dereverb")&&n.contains("48k")&&n.endsWith(".trtpkg");}).findFirst().orElse(null);
                    if(dll==null||model==null)continue;
                    NativeDriver engine=new NativeDriver();try{engine.initialize(dll,model);return engine;}catch(Exception|LinkageError unsupported){engine.close();}
                }catch(IOException ignored){}
            }
            throw new IOException("NVIDIA AFX runtime unavailable");
        }
        private void initialize(Path dll,Path model)throws Exception{
            library=NativeLibrary.getInstance(dll.toAbsolutePath().toString(),Map.of(Library.OPTION_STRING_ENCODING,"UTF-8"));
            create=library.getFunction("NvAFX_CreateEffect");destroy=library.getFunction("NvAFX_DestroyEffect");
            setU32=library.getFunction("NvAFX_SetU32");setString=library.getFunction("NvAFX_SetString");getU32=library.getFunction("NvAFX_GetU32");
            load=library.getFunction("NvAFX_Load");run=library.getFunction("NvAFX_Run");
            try{setFloat=library.getFunction("NvAFX_SetFloat");}catch(UnsatisfiedLinkError newer){setFloatList=library.getFunction("NvAFX_SetFloatList");}
            for(int channel=0;channel<2;channel++){
                PointerByReference handle=new PointerByReference();ok(create.invokeInt(new Object[]{"denoiser",handle}));handles[channel]=handle.getValue();if(handles[channel]==null)throw new IOException();
                ok(setU32.invokeInt(new Object[]{handles[channel],"input_sample_rate",RATE}));
                // Optional on SDK versions that select their default device internally.
                setU32.invokeInt(new Object[]{handles[channel],"use_default_gpu",1});
                ok(setString.invokeInt(new Object[]{handles[channel],"model_path",model.toAbsolutePath().toString()}));
                ok(load.invokeInt(new Object[]{handles[channel]}));
                int n=value(handles[channel],"num_samples_per_input_frame"),o=value(handles[channel],"num_samples_per_output_frame");
                if(n<80||n>1920||n!=o||value(handles[channel],"num_input_channels")!=1||value(handles[channel],"num_output_channels")!=1||value(handles[channel],"output_sample_rate")!=RATE)throw new IOException();
                if(channel==0)frameSize=n;else if(frameSize!=n)throw new IOException();
            }
            in=new Memory(frameSize*4L);out=new Memory(frameSize*4L);inPointers=new Memory(Native.POINTER_SIZE);outPointers=new Memory(Native.POINTER_SIZE);
            inPointers.setPointer(0,in);outPointers.setPointer(0,out);
        }
        private int value(Pointer handle,String key)throws IOException{IntByReference value=new IntByReference();ok(getU32.invokeInt(new Object[]{handle,key,value}));return value.getValue();}
        @Override public int frames(){return frameSize;}
        @Override public void strength(float value)throws IOException{
            if(value==intensity)return;
            for(Pointer handle:handles){int result=setFloat!=null?setFloat.invokeInt(new Object[]{handle,"intensity_ratio",value}):setFloatList.invokeInt(new Object[]{handle,"intensity_ratio",new float[]{value},1});ok(result);}intensity=value;
        }
        @Override public void run(float[] left,float[] right)throws IOException{
            float[][] channels={left,right};for(int c=0;c<2;c++){in.write(0,channels[c],0,frameSize);ok(run.invokeInt(new Object[]{handles[c],inPointers,outPointers,frameSize,1}));out.read(0,channels[c],0,frameSize);}
        }
        private static void ok(int status)throws IOException{if(status!=0)throw new IOException("NVIDIA AFX operation failed");}
        @Override public void close(){
            for(int i=0;i<handles.length;i++)if(handles[i]!=null){try{if(destroy!=null)destroy.invokeInt(new Object[]{handles[i]});}catch(RuntimeException|LinkageError ignored){}handles[i]=null;}
            for(Memory memory:new Memory[]{in,out,inPointers,outPointers})if(memory!=null)memory.close();in=out=inPointers=outPointers=null;
            if(library!=null){library.close();library=null;}
        }
    }
}
