package com.cheatprogram.capture;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** One in-flight decode and one replaceable latest frame; never a video backlog. */
final class LatestFrameDecoder<T> implements AutoCloseable {
    private final Executor executor;
    private final Consumer<T> decode;
    private T latest;
    private boolean running,closed;
    LatestFrameDecoder(Executor executor,Consumer<T> decode){this.executor=executor;this.decode=decode;}
    void offer(T frame){
        synchronized(this){if(closed)return;latest=frame;if(running)return;running=true;}
        try{executor.execute(this::drain);}catch(RuntimeException failure){synchronized(this){running=false;latest=null;}throw failure;}
    }
    private void drain(){
        while(true){T frame;synchronized(this){frame=latest;latest=null;if(closed||frame==null){running=false;return;}}
            // A rejected/obsolete frame must not strand the next complete frame.
            try{decode.accept(frame);}catch(RuntimeException ignored){ }
        }
    }
    synchronized void clear(){latest=null;}
    @Override public synchronized void close(){closed=true;latest=null;}
}
