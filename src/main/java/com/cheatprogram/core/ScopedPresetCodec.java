package com.cheatprogram.core;
import com.cheatprogram.util.WearModelData;import com.google.gson.*;import java.io.*;import java.nio.charset.StandardCharsets;import java.security.*;import java.util.*;import java.util.zip.*;
/** Independent offline feature codes. They cannot contain a global profile or an arbitrary file tree. */
public final class ScopedPresetCodec {
 public static final int MAX_CODE=12*1024*1024,MAX_PACKED=8*1024*1024,MAX_PLAIN=16*1024*1024;
 public enum Scope {MODEL("modelwear"),WEAPON("weaponanimations"),PARTICLES("particleeffects"),BUILDING("buildingblueprints");public final String module;Scope(String id){module=id;}}
 public record Decoded(Scope scope,JsonObject data,Map<String,byte[]> assets){}
 private ScopedPresetCodec(){}
 public static String encode(Scope scope,JsonObject data,Map<String,byte[]> assets)throws IOException{
  validate(scope,data,assets);JsonObject root=new JsonObject();root.addProperty("version",1);root.addProperty("scope",scope.module);root.add("data",data.deepCopy());JsonObject files=new JsonObject();assets.forEach((name,bytes)->files.addProperty(name,Base64.getEncoder().encodeToString(bytes)));root.add("assets",files);
  byte[] plain=root.toString().getBytes(StandardCharsets.UTF_8);if(plain.length>MAX_PLAIN)throw bad("tooLarge");ByteArrayOutputStream out=new ByteArrayOutputStream();try(GZIPOutputStream zip=new GZIPOutputStream(out)){zip.write(plain);}byte[] bytes=out.toByteArray();if(bytes.length>MAX_PACKED)throw bad("tooLarge");String code="CPS1."+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)+"."+digest(bytes);if(code.length()>MAX_CODE)throw bad("tooLarge");return code;
 }
 public static Decoded decode(Scope expected,String source)throws IOException{
  if(source==null||source.length()>MAX_CODE)throw bad("tooLarge");String[] parts=source.strip().split("\\.",-1);if(parts.length!=3||!parts[0].equals("CPS1"))throw bad("invalid");if(!parts[1].matches("[A-Za-z0-9_-]+")||!parts[2].matches("[a-f0-9]{64}"))throw bad("invalid");
  try{byte[] compressed=Base64.getUrlDecoder().decode(parts[1]);if(compressed.length>MAX_PACKED)throw bad("tooLarge");if(!MessageDigest.isEqual(parts[2].getBytes(StandardCharsets.US_ASCII),digest(compressed).getBytes(StandardCharsets.US_ASCII)))throw bad("checksum");ByteArrayOutputStream plain=new ByteArrayOutputStream();try(GZIPInputStream zip=new GZIPInputStream(new ByteArrayInputStream(compressed))){byte[] chunk=new byte[8192];for(int n;(n=zip.read(chunk))!=-1;){if(plain.size()+n>MAX_PLAIN)throw bad("tooLarge");plain.write(chunk,0,n);}}
   JsonObject root=WearModelData.json(plain.toByteArray());only(root,Set.of("version","scope","data","assets"));if(!root.has("version")||root.get("version").getAsDouble()!=1)throw bad("invalid");if(!root.has("scope")||!expected.module.equals(root.get("scope").getAsString()))throw bad("wrongScope");JsonObject data=root.getAsJsonObject("data"),encoded=root.getAsJsonObject("assets");if(data==null||encoded==null||encoded.size()>1)throw bad("invalid");Map<String,byte[]> assets=new LinkedHashMap<>();for(var e:encoded.entrySet()){if(!e.getValue().isJsonPrimitive()||!e.getValue().getAsJsonPrimitive().isString())throw bad("invalid");byte[] bytes=Base64.getDecoder().decode(e.getValue().getAsString());if(bytes.length>WearModelData.MAX_BYTES)throw bad("tooLarge");assets.put(e.getKey(),bytes);}validate(expected,data,assets);return new Decoded(expected,data,Map.copyOf(assets));
  }catch(IOException failure){if(failure.getMessage()!=null&&failure.getMessage().startsWith("ui.scopedShare."))throw failure;throw bad("invalid");}catch(RuntimeException bad){throw bad("invalid");}
 }
 private static void validate(Scope scope,JsonObject data,Map<String,byte[]> assets)throws IOException{
  if(scope==null||data==null||assets==null)throw bad("invalid");Set<String> allowed=switch(scope){case MODEL,WEAPON->Set.of("settings");case PARTICLES->Set.of("settings","project");case BUILDING->Set.of("settings","building");};only(data,allowed);
  if(!data.has("settings")||!data.get("settings").isJsonObject()||data.getAsJsonObject("settings").size()>128)throw bad("invalid");
  if(scope==Scope.MODEL){if(assets.size()!=1||!assets.containsKey("model.cpmodel"))throw bad("assetMissing");WearModelData.decode(assets.get("model.cpmodel"));}
  else if(!assets.isEmpty())throw bad("wrongScope");
  for(String name:assets.keySet())if(!WearModelData.safeReference(name)||name.contains("/"))throw bad("invalid");
 }
 public static void only(JsonObject object,Set<String> allowed)throws IOException{if(object==null)throw bad("invalid");for(String key:object.keySet())if(!allowed.contains(key))throw bad("wrongScope");}
 private static String digest(byte[] b){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
 public static IOException bad(String reason){return new IOException("ui.scopedShare."+reason);}
}
