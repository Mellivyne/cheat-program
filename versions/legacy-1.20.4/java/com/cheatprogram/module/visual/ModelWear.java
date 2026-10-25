package com.cheatprogram.module.visual;
import com.cheatprogram.core.Category;import com.cheatprogram.core.Module;import com.cheatprogram.core.ModuleManager;import com.cheatprogram.core.setting.*;import com.cheatprogram.render.*;import com.cheatprogram.util.*;import com.google.gson.JsonElement;import net.minecraft.client.Minecraft;import net.minecraft.util.Mth;import net.minecraft.world.phys.Vec3;import org.joml.Matrix4f;
/** Client-only static model attachment/replacement; no player/server state or camera aim is modified. */
public final class ModelWear extends Module {
 public final ModeSetting mode=add(new ModeSetting("mode","modelwear_attach","modelwear_attach","modelwear_replace"));
 public final NumberSetting offsetX=add(new NumberSetting("offsetX",0,-4,4,.05,"m")),offsetY=add(new NumberSetting("offsetY",0,-4,4,.05,"m")),offsetZ=add(new NumberSetting("offsetZ",0,-4,4,.05,"m"));
 public final NumberSetting size=add(new NumberSetting("size",1,.01,8,.01));
 public final NumberSetting yaw=add(new NumberSetting("yaw",0,-180,180,1,"°")),pitch=add(new NumberSetting("pitch",0,-180,180,1,"°")),roll=add(new NumberSetting("roll",0,-180,180,1,"°"));
 public final NumberSetting opacity=add(new NumberSetting("opacity",100,5,100,1,"%"));
 public final BoolSetting followBody=add(new BoolSetting("followBody",true)),firstPerson=add(new BoolSetting("firstPerson",false));
 private final TextSetting asset=add(new TextSetting("asset","",64){@Override public void load(JsonElement e){String v=e!=null&&e.isJsonPrimitive()?e.getAsString():"";set(v.matches("[a-f0-9]{64}")?v:"");}}).visibleWhen(()->false);
 public ModelWear(){super("modelwear",Category.VISUAL);}public String asset(){return asset.get();}public void asset(String id){asset.set(id!=null&&id.matches("[a-f0-9]{64}")?id:"");}
 public WearModelData.Model model(){return WearModelStore.model(asset());}
 public void move(double x,double y,double z){
  if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))return;
  double length=Math.sqrt(x*x+y*y+z*z),factor=length>4?4/length:1;double[] p={Math.round(x*factor*20)/20.0,Math.round(y*factor*20)/20.0,Math.round(z*factor*20)/20.0};
  // Sliders snap to 0.05; rounding must not move a boundary point back outside the sphere.
  while(p[0]*p[0]+p[1]*p[1]+p[2]*p[2]>16.00000001){int at=Math.abs(p[0])>=Math.abs(p[1])?0:1;if(Math.abs(p[2])>Math.abs(p[at]))at=2;p[at]=Math.copySign(Math.max(0,Math.abs(p[at])-.05),p[at]);}
  NumberSetting[] fields={offsetX,offsetY,offsetZ};for(int i=0;i<3;i++)if(Math.abs(fields[i].val()-p[i])>.000001)fields[i].set(p[i]);
 }
 public Vec3 offset(){Vec3 p=new Vec3(offsetX.val(),offsetY.val(),offsetZ.val());return p.lengthSqr()>16?p.normalize().scale(4):p;}
 public Matrix4f transform(float bodyYaw){Vec3 p=offset();return new Matrix4f().rotateY((float)Math.toRadians(-bodyYaw)).translate((float)p.x,(float)p.y,(float)p.z).rotateY((float)Math.toRadians(yaw.val())).rotateX((float)Math.toRadians(pitch.val())).rotateZ((float)Math.toRadians(roll.val())).scale(size.floatVal());}
 @Override public void onTick(){if(offsetX.val()*offsetX.val()+offsetY.val()*offsetY.val()+offsetZ.val()*offsetZ.val()>16.00000001)move(offsetX.val(),offsetY.val(),offsetZ.val());model();}
 @Override public void onRender3D(Render3D r){if(mc.player==null||!firstPerson.on()&&mc.options.getCameraType().isFirstPerson()&&!Freecam.isActive())return;WearModelData.Model mesh=model();if(mesh==null)return;Vec3 p=mc.player.getPosition(r.partialTick);float angle=followBody.on()?Mth.rotLerp(r.partialTick,mc.player.yBodyRotO,mc.player.yBodyRot):0;WearModelRenderer.world(r,mesh,p,this,angle);}
 public static boolean replaces(int entityId){ModelWear m=ModuleManager.get(ModelWear.class);Minecraft mc=Minecraft.getInstance();return m!=null&&m.isEnabled()&&m.mode.is("modelwear_replace")&&mc.player!=null&&entityId==mc.player.getId()&&!R2D.isActive()&&Math.abs(com.cheatprogram.compat.legacy.LegacyRenderSystem.getProjectionMatrix().m33())<.001f&&m.model()!=null&&!WearModelRenderer.failed(m.model());}
}
