package com.cheatprogram.dev;

import com.cheatprogram.ai.AiBuildingDesigner;
import com.cheatprogram.util.BuildingInspection;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

final class BuildingDesignerCheck {
    static com.cheatprogram.util.BuildingFiles.Blueprint run()throws Exception{
        var plan=JsonParser.parseString("""
            {"name":"Inspection fixture","size":[4,3,5],"palette":[{"Name":"minecraft:air"},{"Name":"minecraft:stone_bricks"},{"Name":"minecraft:oak_planks"}],"fills":[
             {"from":[0,0,0],"to":[3,0,4],"block":1},
             {"from":[0,1,0],"to":[3,2,4],"block":2},
             {"from":[1,1,1],"to":[2,2,3],"block":0}]}
            """).getAsJsonObject();
        var b=AiBuildingDesigner.decode(plan);var analysis=new BuildingInspection(b);
        if(b.count()!=60||analysis.materials(-1).stream().mapToLong(BuildingInspection.Material::count).sum()!=48)throw new AssertionError("Material totals include air or lose fills");
        if(analysis.materials(0).size()!=1||analysis.materials(0).getFirst().count()!=20||analysis.materials(1).getFirst().count()!=14)throw new AssertionError("Wrong floor totals");
        if(analysis.slice(1).height()!=1||analysis.slice(1).count()!=20||new BuildingInspection(analysis.slice(1)).materials(-1).getFirst().count()!=14)throw new AssertionError("Layer indexing mismatch");
        var invalid=plan.deepCopy();invalid.getAsJsonArray("fills").get(0).getAsJsonObject().getAsJsonArray("to").set(0,new com.google.gson.JsonPrimitive(100));
        try{AiBuildingDesigner.decode(invalid);throw new AssertionError("Out-of-bounds fill accepted");}catch(IllegalArgumentException expected){}
        invalid=plan.deepCopy();invalid.getAsJsonArray("size").set(0,new com.google.gson.JsonPrimitive(4.5));
        try{AiBuildingDesigner.decode(invalid);throw new AssertionError("Fractional size accepted");}catch(IllegalArgumentException expected){}
        invalid=plan.deepCopy();invalid.getAsJsonArray("palette").get(1).getAsJsonObject().addProperty("Name","minecraft:unknown_fixture_block");
        try{AiBuildingDesigner.decode(invalid);throw new AssertionError("Unknown block accepted");}catch(IllegalArgumentException expected){}
        invalid=plan.deepCopy();invalid.getAsJsonArray("palette").get(1).getAsJsonObject().add("Properties",JsonParser.parseString("{\"facing\":\"invalid\"}"));
        try{AiBuildingDesigner.decode(invalid);throw new AssertionError("Unknown block property accepted");}catch(IllegalArgumentException expected){}
        var mc=Minecraft.getInstance();var position=mc.player.position();
        try(var preview=new com.cheatprogram.render.BuildingModelPreview()){
            preview.set(b);preview.tour(true);float z=preview.eyeZ;preview.travel(1,0,1,.1f);
            if(preview.eyeZ>=z||!mc.player.position().equals(position))throw new AssertionError("Tour failed or moved real player");
            if(preview.eyeY<=2.0f)throw new AssertionError("Tour could not fly");
            preview.yaw=90;float x=preview.eyeX;z=preview.eyeZ;preview.travel(1,0,0,.1f);
            if(preview.eyeX<=x||Math.abs(preview.eyeZ-z)>.0001)throw new AssertionError("Tour movement disagrees with view direction");
        }
        System.out.println("BUILDING_CHECK_PASS material-totals floor-indexing fill-order unknown-block invalid-plan tour-camera-isolation");
        return b;
    }
}
