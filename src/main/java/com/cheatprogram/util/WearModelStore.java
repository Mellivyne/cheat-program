package com.cheatprogram.util;
import com.cheatprogram.core.Config;import java.io.*;import java.nio.file.*;import java.security.*;import java.util.*;import java.util.concurrent.*;
/** Content-addressed, local-only canonical model library. Imports never retain source paths. */
public final class WearModelStore {
 public static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"CheatProgram model assets");t.setDaemon(true);return t;});
 private static volatile WearModelData.Model model;private static volatile String requested="",loaded="",status="ui.modelWear.empty";private static long generation;
 private WearModelStore(){}public static Path directory(){return Config.directory().resolve("modelwear");}public static String status(){return status;}
 public static synchronized WearModelData.Model model(String id){if(id==null||id.isBlank())return null;if(!id.matches("[a-f0-9]{64}")){status="ui.modelWear.invalid";return null;}if(!id.equals(requested)){requested=id;long token=++generation;status="ui.modelWear.loading";IO.execute(()->{try{WearModelData.Model parsed=WearModelData.decode(bytes(id));synchronized(WearModelStore.class){if(token==generation){model=parsed;loaded=id;status="ui.modelWear.ready";}}}catch(Exception bad){synchronized(WearModelStore.class){if(token==generation)status="ui.modelWear.invalid";}}});}return id.equals(loaded)?model:null;}
 public static byte[] bytes(String id)throws IOException{if(id==null||!id.matches("[a-f0-9]{64}"))throw WearModelData.bad("empty");Path file=directory().resolve(id+".cpmodel");if(Files.isSymbolicLink(directory())||Files.isSymbolicLink(file)||!Files.isRegularFile(file)||Files.size(file)>WearModelData.MAX_BYTES)throw WearModelData.bad("invalid");byte[] bytes=Files.readAllBytes(file);if(!digest(bytes).equals(id))throw WearModelData.bad("invalid");return bytes;}
 public static String install(byte[] bytes)throws IOException{
  WearModelData.Model checked=WearModelData.decode(bytes);byte[] canonical=WearModelData.encode(checked);String id=digest(canonical);
  synchronized(Config.persistenceLock()){if(!Config.persistenceAllowed())throw WearModelData.bad("saveFailed");Path dir=directory();if(Files.isSymbolicLink(dir))throw WearModelData.bad("referenceBlocked");Files.createDirectories(dir);Path target=dir.resolve(id+".cpmodel");if(Files.isSymbolicLink(target))throw WearModelData.bad("referenceBlocked");if(!Files.isRegularFile(target)||Files.size(target)!=canonical.length||!Arrays.equals(Files.readAllBytes(target),canonical)){Path temp=Files.createTempFile(dir,"model-",".pending");try{Files.write(temp,canonical);Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}}}
  synchronized(WearModelStore.class){requested=loaded=id;model=checked;status="ui.modelWear.ready";generation++;}return id;
 }
 private static String digest(byte[] b){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}

