package com.cheatprogram.util;

import com.google.gson.*;
import com.google.gson.stream.*;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Static triangle import only. No scripting, network, archive extraction, rig or animation execution. */
public final class WearModelData {
 public static final int MAX_BYTES=10*1024*1024,MAX_TRIANGLES=40000,MAX_TEXTURES=16,MAX_PARTS=128;
 public record Part(int texture,float[] vertices) { }
 public record Model(List<Part> parts,List<byte[]> textures,int triangles) { }
 @FunctionalInterface public interface Resources {byte[] read(String relative)throws IOException;}
 private static final int MAGIC=0x43504d31,STRIDE=9;
 private WearModelData(){}
 public static Model importFile(Path selected)throws IOException{
  Path file=selected.toRealPath();if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>MAX_BYTES)throw bad("tooLarge");
  Path base=file.getParent();long[] total={0};Resources resolver=relative->{
   if(!safeReference(relative))throw bad("referenceBlocked");Path requested=base.resolve(relative.replace('/',File.separatorChar)).normalize();
   if(!requested.startsWith(base)||Files.isSymbolicLink(requested))throw bad("referenceBlocked");Path real=requested.toRealPath();
   if(!real.startsWith(base)||!Files.isRegularFile(real)||Files.size(real)>MAX_BYTES)throw bad("referenceBlocked");byte[] bytes=Files.readAllBytes(real);total[0]+=bytes.length;if(total[0]>MAX_BYTES*2L)throw bad("tooLarge");return bytes;
  };
  byte[] bytes=Files.readAllBytes(file);String name=file.getFileName().toString().toLowerCase(Locale.ROOT);
  return parse(name,bytes,resolver);
 }
 public static boolean safeReference(String value){return value!=null&&!value.isBlank()&&value.length()<256&&!value.contains(":")&&!value.contains("\\")&&!value.startsWith("/")&&!value.contains("%")&&Arrays.stream(value.split("/",-1)).noneMatch(p->p.isEmpty()||p.equals(".")||p.equals(".."));}
 public static Model parse(String name,byte[] bytes,Resources resources)throws IOException{
  if(bytes==null||bytes.length>MAX_BYTES)throw bad("tooLarge");try{
   if(name.endsWith(".obj"))return obj(new String(bytes,StandardCharsets.UTF_8),resources);
   if(name.endsWith(".gltf")||name.endsWith(".glb"))return new Gltf(bytes,name.endsWith(".glb"),resources).load();
   if(name.endsWith(".cpmodel"))return decode(bytes);throw bad("unsupported");
  }catch(IOException error){throw error;}catch(RuntimeException error){throw bad("invalid");}
 }
 public static byte[] encode(Model model)throws IOException{
  validate(model);ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(DataOutputStream out=new DataOutputStream(bytes)){
   out.writeInt(MAGIC);out.writeInt(model.textures.size());for(byte[] png:model.textures){out.writeInt(png.length);out.write(png);}
   out.writeInt(model.parts.size());for(Part part:model.parts){out.writeInt(part.texture);out.writeInt(part.vertices.length);for(float v:part.vertices)out.writeFloat(v);}
  }if(bytes.size()>MAX_BYTES)throw bad("tooLarge");return bytes.toByteArray();
 }
 public static Model decode(byte[] bytes)throws IOException{
  if(bytes==null||bytes.length>MAX_BYTES)throw bad("tooLarge");try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes))){
   if(in.readInt()!=MAGIC)throw bad("invalid");int count=bounded(in.readInt(),0,MAX_TEXTURES);List<byte[]> textures=new ArrayList<>();
   for(int i=0;i<count;i++){int n=bounded(in.readInt(),1,MAX_BYTES);byte[] image=in.readNBytes(n);if(image.length!=n)throw bad("invalid");addImage(textures,image);}
   count=bounded(in.readInt(),1,MAX_PARTS);List<Part> parts=new ArrayList<>();int triangles=0;
   for(int i=0;i<count;i++){int texture=in.readInt(),n=bounded(in.readInt(),27,MAX_TRIANGLES*27);if(n%27!=0||(triangles+=n/27)>MAX_TRIANGLES)throw bad("tooLarge");float[] data=new float[n];for(int at=0;at<n;at++)data[at]=in.readFloat();parts.add(new Part(texture,data));}
   if(in.read()!=-1)throw bad("invalid");Model model=new Model(List.copyOf(parts),List.copyOf(textures),triangles);validate(model);return model;
  }catch(EOFException malformed){throw bad("invalid");}
 }
 private static void validate(Model model)throws IOException{
  if(model==null||model.parts.isEmpty()||model.parts.size()>MAX_PARTS||model.textures.size()>MAX_TEXTURES)throw bad("invalid");int total=0;
  for(Part p:model.parts){if(p.texture< -1||p.texture>=model.textures.size()||p.vertices.length%27!=0||(total+=p.vertices.length/27)>MAX_TRIANGLES)throw bad("tooLarge");
   for(int i=0;i<p.vertices.length;i++){float v=p.vertices[i];if(!Float.isFinite(v)||Math.abs(v)>(i%9<3?100000: i%9<5?10000:1))throw bad("invalid");}
  }if(total<1||total!=model.triangles)throw bad("invalid");
 }
 private static Model finish(List<Part> parts,List<byte[]> textures)throws IOException{
  Model raw=new Model(List.copyOf(parts),List.copyOf(textures),parts.stream().mapToInt(p->p.vertices.length/27).sum());validate(raw);
  // Fit new imports to human scale once. Shared canonical assets keep this exact geometry thereafter.
  float minX=Float.POSITIVE_INFINITY,minY=minX,minZ=minX,maxX=-minX,maxY=-minX,maxZ=-minX;
  for(Part p:parts)for(int i=0;i<p.vertices.length;i+=9){minX=Math.min(minX,p.vertices[i]);maxX=Math.max(maxX,p.vertices[i]);minY=Math.min(minY,p.vertices[i+1]);maxY=Math.max(maxY,p.vertices[i+1]);minZ=Math.min(minZ,p.vertices[i+2]);maxZ=Math.max(maxZ,p.vertices[i+2]);}
  float longest=Math.max(maxY-minY,Math.max(maxX-minX,maxZ-minZ));if(longest<.000001f)throw bad("invalid");float factor=1.8f/longest;
  for(Part p:parts)for(int i=0;i<p.vertices.length;i+=9){p.vertices[i]=(p.vertices[i]-(minX+maxX)*.5f)*factor;p.vertices[i+1]=(p.vertices[i+1]-minY)*factor;p.vertices[i+2]=(p.vertices[i+2]-(minZ+maxZ)*.5f)*factor;}
  return raw;
 }
 private record Material(int texture,float[] color){}
 private static Model obj(String source,Resources resources)throws IOException{
  List<float[]> positions=new ArrayList<>(),uvs=new ArrayList<>();Map<String,Material> materials=new HashMap<>();List<byte[]> textures=new ArrayList<>();Map<String,List<Float>> groups=new LinkedHashMap<>();String selected="";int triangleCount=0;
  for(String raw:source.split("\\R")){String line=raw.strip();if(line.isEmpty()||line.startsWith("#"))continue;String[] fields=line.split("\\s+");if(fields.length>2048)throw bad("tooLarge");
   switch(fields[0]){
    case "v"->{if(fields.length<4||positions.size()>=MAX_TRIANGLES*3)throw bad("tooLarge");positions.add(new float[]{number(fields[1]),number(fields[2]),number(fields[3])});}
    case "vt"->{if(fields.length<3||uvs.size()>=MAX_TRIANGLES*3)throw bad("tooLarge");uvs.add(new float[]{number(fields[1]),1-number(fields[2])});}
    case "mtllib"->{if(fields.length!=2)throw bad("unsupported");String mtl=new String(readResource(resources,fields[1]),StandardCharsets.UTF_8);String current="";float[] color={1,1,1,1};int texture=-1;
     for(String row:mtl.split("\\R")){String[] v=row.strip().split("\\s+");if(v.length<2)continue;if(v[0].equals("newmtl")){if(!current.isEmpty())materials.put(current,new Material(texture,color));current=v[1];color=new float[]{1,1,1,1};texture=-1;}
      else if(v[0].equals("Kd")&&v.length>=4){color[0]=unit(number(v[1]));color[1]=unit(number(v[2]));color[2]=unit(number(v[3]));}
      else if(v[0].equals("d"))color[3]=unit(number(v[1]));else if(v[0].equals("map_Kd")){if(v.length!=2)throw bad("unsupported");String prefix=fields[1].contains("/")?fields[1].substring(0,fields[1].lastIndexOf('/')+1):"";texture=addImage(textures,readResource(resources,prefix+v[1]));}}
     if(!current.isEmpty())materials.put(current,new Material(texture,color));if(materials.size()>MAX_PARTS)throw bad("tooLarge");}
    case "usemtl"->{selected=fields.length>1?fields[1]:"";if(groups.size()>MAX_PARTS)throw bad("tooLarge");}
    case "f"->{if(fields.length<4)throw bad("invalid");List<Float> out=groups.computeIfAbsent(selected,k->new ArrayList<>());Material m=materials.getOrDefault(selected,new Material(-1,new float[]{1,1,1,1}));for(int tri=2;tri<fields.length-1;tri++){if(++triangleCount>MAX_TRIANGLES)throw bad("tooLarge");for(int index:new int[]{1,tri,tri+1}){
     String[] vertex=fields[index].split("/",-1);float[] p=positions.get(objIndex(vertex[0],positions.size())),uv=vertex.length>1&&!vertex[1].isEmpty()?uvs.get(objIndex(vertex[1],uvs.size())):new float[2];append(out,p,uv,m.color);if(out.size()>MAX_TRIANGLES*27)throw bad("tooLarge");}}}
    default->{}
   }
  }
  List<Part> parts=new ArrayList<>();for(var g:groups.entrySet())parts.add(new Part(materials.getOrDefault(g.getKey(),new Material(-1,new float[4])).texture,floats(g.getValue())));return finish(parts,textures);
 }
 private static int objIndex(String value,int count)throws IOException{int i=Integer.parseInt(value);i=i<0?count+i:i-1;if(i<0||i>=count)throw bad("invalid");return i;}
 private static final class Gltf {
  final JsonObject root;final Resources resources;final List<byte[]> buffers=new ArrayList<>(),textures=new ArrayList<>();final List<Part> parts=new ArrayList<>();final Map<Integer,Integer> images=new HashMap<>();int vertices;
  Gltf(byte[] bytes,boolean glb,Resources resources)throws IOException{
   this.resources=resources;byte[] json=bytes,bin=null;if(glb){ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);if(b.remaining()<20||b.getInt()!=0x46546c67||b.getInt()!=2||b.getInt()!=bytes.length)throw bad("invalid");json=null;
    while(b.remaining()>0){if(b.remaining()<8)throw bad("invalid");int n=b.getInt(),type=b.getInt();if(n<0||n>b.remaining())throw bad("invalid");byte[] chunk=new byte[n];b.get(chunk);if(type==0x4e4f534a&&json==null)json=chunk;else if(type==0x004e4942&&bin==null)bin=chunk;else throw bad("unsupported");}if(json==null)throw bad("invalid");}
   root=json(json);if(!text(object(root,"asset"),"version").equals("2.0")||!array(root,"extensionsRequired").isEmpty())throw bad("unsupported");
   for(JsonElement item:array(root,"buffers")){if(buffers.size()>=16)throw bad("tooLarge");JsonObject row=item.getAsJsonObject();byte[] data=row.has("uri")?resource(text(row,"uri")):bin;if(data==null||data.length<integer(row,"byteLength",0))throw bad("invalid");buffers.add(data);}
  }
  Model load()throws IOException{
   JsonArray nodes=array(root,"nodes"),scenes=array(root,"scenes");if(nodes.size()>1024||scenes.isEmpty())throw bad("unsupported");JsonObject scene=scenes.get(bounded(integer(root,"scene",0),0,scenes.size()-1)).getAsJsonObject();
   for(JsonElement n:array(scene,"nodes"))node(n.getAsInt(),new Matrix4f(),new HashSet<>(),0);return finish(parts,textures);
  }
  void node(int index,Matrix4f parent,Set<Integer> chain,int depth)throws IOException{
   if(depth>32||!chain.add(index))throw bad("invalid");JsonObject node=get("nodes",index);if(node.has("skin"))throw bad("unsupported");Matrix4f matrix=new Matrix4f(parent);
   if(node.has("matrix")){JsonArray a=array(node,"matrix");if(a.size()!=16)throw bad("invalid");float[] m=new float[16];for(int i=0;i<16;i++)m[i]=finite(a.get(i).getAsFloat());matrix.mul(new Matrix4f().set(m));}
   else{float[] t=vector(node,"translation",new float[]{0,0,0}),r=vector(node,"rotation",new float[]{0,0,0,1}),s=vector(node,"scale",new float[]{1,1,1});Quaternionf q=new Quaternionf(r[0],r[1],r[2],r[3]);if(q.lengthSquared()<.00001)throw bad("invalid");matrix.translate(t[0],t[1],t[2]).rotate(q.normalize()).scale(s[0],s[1],s[2]);}
   if(node.has("mesh"))for(JsonElement p:array(get("meshes",node.get("mesh").getAsInt()),"primitives"))primitive(p.getAsJsonObject(),matrix);
   for(JsonElement child:array(node,"children"))node(child.getAsInt(),matrix,chain,depth+1);chain.remove(index);
  }
  void primitive(JsonObject p,Matrix4f matrix)throws IOException{
   if(integer(p,"mode",4)!=4||p.has("targets")||p.has("extensions")||parts.size()>=MAX_PARTS)throw bad("unsupported");JsonObject attrs=object(p,"attributes");if(!attrs.has("POSITION"))throw bad("invalid");Accessor position=new Accessor(attrs.get("POSITION").getAsInt(),3);Accessor uv=attrs.has("TEXCOORD_0")?new Accessor(attrs.get("TEXCOORD_0").getAsInt(),2):null;Accessor colors=attrs.has("COLOR_0")?new Accessor(attrs.get("COLOR_0").getAsInt(),0):null;
   Accessor indices=p.has("indices")?new Accessor(p.get("indices").getAsInt(),1):null;if(position.type!=5126||indices!=null&&(indices.type==5126||indices.normalized))throw bad("unsupported");int count=indices==null?position.count:indices.count;if(count%3!=0||(vertices+=count)>MAX_TRIANGLES*3)throw bad("tooLarge");
   Material material=new Material(-1,new float[]{1,1,1,1});if(p.has("material")){JsonObject m=get("materials",p.get("material").getAsInt()),pbr=object(m,"pbrMetallicRoughness");float[] c=vector(pbr,"baseColorFactor",new float[]{1,1,1,1});for(float v:c)if(v<0||v>1)throw bad("invalid");int image=-1;if(pbr.has("baseColorTexture")){JsonObject t=object(pbr,"baseColorTexture");if(integer(t,"texCoord",0)!=0||t.has("extensions"))throw bad("unsupported");int imageId=get("textures",integer(t,"index",-1)).get("source").getAsInt();if(!images.containsKey(imageId)){JsonObject img=get("images",imageId);images.put(imageId,addImage(textures,img.has("uri")?resource(text(img,"uri")):view(integer(img,"bufferView",-1))));}image=images.get(imageId);}material=new Material(image,c);}
   float[] out=new float[count*9];for(int i=0;i<count;i++){int index=indices==null?i:(int)indices.value(i,0);if(index<0||index>=position.count)throw bad("invalid");Vector3f point=new Vector3f(position.value(index,0),position.value(index,1),position.value(index,2));matrix.transformPosition(point);int at=i*9;out[at]=point.x;out[at+1]=point.y;out[at+2]=point.z;out[at+3]=uv==null?0:uv.value(index,0);out[at+4]=uv==null?0:uv.value(index,1);for(int c=0;c<4;c++)out[at+5+c]=material.color[c]*(colors==null||c>=colors.components?1:colors.value(index,c));}
   parts.add(new Part(material.texture,out));
  }
  class Accessor {
   final ByteBuffer data;final int offset,stride,count,components,type,size;final boolean normalized;
   Accessor(int index,int expected)throws IOException{
    JsonObject a=get("accessors",index);if(a.has("sparse"))throw bad("unsupported");count=bounded(integer(a,"count",-1),1,MAX_TRIANGLES*3);components=switch(text(a,"type")){case "SCALAR"->1;case "VEC2"->2;case "VEC3"->3;case "VEC4"->4;default->throw bad("unsupported");};if(expected>0&&components!=expected||expected==0&&components<3)throw bad("invalid");
    type=integer(a,"componentType",0);size=switch(type){case 5121->1;case 5123->2;case 5125,5126->4;default->throw bad("unsupported");};normalized=a.has("normalized")&&a.get("normalized").getAsBoolean();JsonObject v=get("bufferViews",integer(a,"bufferView",-1));byte[] bytes=buffers.get(bounded(integer(v,"buffer",-1),0,buffers.size()-1));int start=integer(v,"byteOffset",0),length=integer(v,"byteLength",-1),inner=integer(a,"byteOffset",0);stride=integer(v,"byteStride",size*components);if(start<0||length<0||inner<0||stride<size*components||stride>256||(long)start+length>bytes.length||(long)inner+(count-1L)*stride+size*components>length)throw bad("invalid");offset=start+inner;data=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
   }
   float value(int index,int component)throws IOException{if(index<0||index>=count||component<0||component>=components)throw bad("invalid");int at=offset+index*stride+component*size;double value=switch(type){case 5121->Byte.toUnsignedInt(data.get(at));case 5123->Short.toUnsignedInt(data.getShort(at));case 5125->Integer.toUnsignedLong(data.getInt(at));default->data.getFloat(at);};if(normalized&&type!=5126)value/=type==5121?255:type==5123?65535:4294967295d;return finite((float)value);}
  }
  byte[] resource(String uri)throws IOException{if(uri.startsWith("data:")){int comma=uri.indexOf(',');if(comma<0||!uri.substring(0,comma).endsWith(";base64")||uri.length()>MAX_BYTES*2)throw bad("referenceBlocked");try{byte[] value=Base64.getDecoder().decode(uri.substring(comma+1));if(value.length>MAX_BYTES)throw bad("tooLarge");return value;}catch(IllegalArgumentException error){throw bad("invalid");}}if(!safeReference(uri))throw bad("referenceBlocked");return resources.read(uri);}
  byte[] view(int index)throws IOException{JsonObject view=get("bufferViews",index);byte[] source=buffers.get(bounded(integer(view,"buffer",-1),0,buffers.size()-1));int at=integer(view,"byteOffset",0),n=integer(view,"byteLength",-1);if(at<0||n<0||(long)at+n>source.length)throw bad("invalid");return Arrays.copyOfRange(source,at,at+n);}
  JsonObject get(String key,int index)throws IOException{JsonArray array=array(root,key);if(index<0||index>=array.size()||!array.get(index).isJsonObject())throw bad("invalid");return array.get(index).getAsJsonObject();}
 }
 public static JsonObject json(byte[] bytes)throws IOException{
  if(bytes.length>16*1024*1024)throw bad("tooLarge");String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();try(JsonReader reader=new JsonReader(new StringReader(text))){reader.setLenient(false);JsonElement value=readJson(reader,0,new int[1]);if(reader.peek()!=JsonToken.END_DOCUMENT||!value.isJsonObject())throw bad("invalid");return value.getAsJsonObject();}catch(RuntimeException error){throw bad("invalid");}
 }
 private static JsonElement readJson(JsonReader r,int depth,int[] nodes)throws IOException{if(depth>40||++nodes[0]>200000)throw bad("tooLarge");return switch(r.peek()){
  case BEGIN_OBJECT->{r.beginObject();JsonObject o=new JsonObject();while(r.hasNext()){String k=r.nextName();if(k.length()>256||o.has(k))throw bad("invalid");o.add(k,readJson(r,depth+1,nodes));}r.endObject();yield o;}
  case BEGIN_ARRAY->{r.beginArray();JsonArray a=new JsonArray();while(r.hasNext())a.add(readJson(r,depth+1,nodes));r.endArray();yield a;}
  case STRING->{String s=r.nextString();if(s.length()>MAX_BYTES*2)throw bad("tooLarge");yield new JsonPrimitive(s);}
  case NUMBER->{String n=r.nextString();double d=Double.parseDouble(n);if(!Double.isFinite(d))throw bad("invalid");yield new JsonPrimitive(d);}
  case BOOLEAN->new JsonPrimitive(r.nextBoolean());case NULL->{r.nextNull();yield JsonNull.INSTANCE;}default->throw bad("invalid");};}
 private static int addImage(List<byte[]> textures,byte[] source)throws IOException{if(textures.size()>=MAX_TEXTURES)throw bad("tooLarge");byte[] image=png(source);long pixels=0;for(byte[] p:textures)pixels+=(long)ByteBuffer.wrap(p).getInt(16)*ByteBuffer.wrap(p).getInt(20);pixels+=(long)ByteBuffer.wrap(image).getInt(16)*ByteBuffer.wrap(image).getInt(20);if(pixels>16L*1024*1024)throw bad("tooLarge");textures.add(image);return textures.size()-1;}
 private static byte[] png(byte[] source)throws IOException{
  if(source.length>4*1024*1024)throw bad("tooLarge");try(ImageInputStream stream=ImageIO.createImageInputStream(new ByteArrayInputStream(source))){var readers=ImageIO.getImageReaders(stream);if(!readers.hasNext())throw bad("unsupported");var reader=readers.next();try{reader.setInput(stream,true,true);int w=reader.getWidth(0),h=reader.getHeight(0);if(w<1||h<1||w>2048||h>2048||(long)w*h>4194304)throw bad("tooLarge");BufferedImage image=reader.read(0);ByteArrayOutputStream out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);if(out.size()>4*1024*1024)throw bad("tooLarge");return out.toByteArray();}finally{reader.dispose();}}
 }
 private static float[] vector(JsonObject o,String key,float[] fallback)throws IOException{if(!o.has(key))return fallback;JsonArray a=array(o,key);if(a.size()!=fallback.length)throw bad("invalid");float[] v=new float[a.size()];for(int i=0;i<v.length;i++)v[i]=finite(a.get(i).getAsFloat());return v;}
 private static byte[] readResource(Resources resources,String path)throws IOException{if(!safeReference(path))throw bad("referenceBlocked");byte[] value=resources.read(path);if(value==null||value.length>MAX_BYTES)throw bad("tooLarge");return value;}
 private static void append(List<Float> out,float[] p,float[] uv,float[] color){for(float v:p)out.add(v);for(float v:uv)out.add(v);for(float v:color)out.add(v);}
 private static float[] floats(List<Float> input){float[] out=new float[input.size()];for(int i=0;i<out.length;i++)out[i]=input.get(i);return out;}
 private static float number(String s)throws IOException{return finite(Float.parseFloat(s));}private static float finite(float v)throws IOException{if(!Float.isFinite(v)||Math.abs(v)>100000)throw bad("invalid");return v;}private static float unit(float v){return Math.max(0,Math.min(1,v));}
 private static int bounded(int v,int min,int max)throws IOException{if(v<min||v>max)throw bad("invalid");return v;}
 private static JsonObject object(JsonObject o,String key){return o.has(key)&&o.get(key).isJsonObject()?o.getAsJsonObject(key):new JsonObject();}private static JsonArray array(JsonObject o,String key){return o.has(key)&&o.get(key).isJsonArray()?o.getAsJsonArray(key):new JsonArray();}private static String text(JsonObject o,String key){return o.has(key)&&o.get(key).isJsonPrimitive()?o.get(key).getAsString():"";}private static int integer(JsonObject o,String key,int fallback){try{return o.has(key)?o.get(key).getAsInt():fallback;}catch(RuntimeException e){return fallback;}}
 public static IOException bad(String reason){return new IOException("ui.modelWear."+reason);}
}


