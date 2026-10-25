package com.cheatprogram.render;

import com.cheatprogram.CheatProgram;
import com.google.gson.JsonParser;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.renderer.ShaderInstance;


import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The client's own core shaders. Fabric Loader on its own does not expose mod assets to the
 * game's resource manager, so the programs are read straight from the mod jar and compiled with
 * the vanilla shader classes. A program that fails to compile is remembered and the renderer
 * falls back to flat drawing.
 */
public final class Shaders {
	public static final Program RRECT = new Program("rrect", DefaultVertexFormat.POSITION_TEX_COLOR);
	public static final Program GLASS = new Program("glass", DefaultVertexFormat.POSITION_TEX_COLOR);
    public static final Program ALBUM_GLASS = new Program("album_glass", DefaultVertexFormat.POSITION_TEX_COLOR);
	public static final Program LIQUID_TOGGLE = new Program("liquid_toggle", DefaultVertexFormat.POSITION_TEX_COLOR);
	public static final Program BLUR_DOWN = new Program("blur_down", DefaultVertexFormat.POSITION);
	public static final Program BLUR_UP = new Program("blur_up", DefaultVertexFormat.POSITION);
	public static final Program BACKDROP = new Program("backdrop", DefaultVertexFormat.POSITION);
	public static final Program WORLD_COLOR = new Program("world_color", DefaultVertexFormat.POSITION);

	private Shaders() {
	}

	public static final class Program {
		final String name;
		final VertexFormat format;
		ShaderInstance compiled;
		boolean failed;

		Program(String name, VertexFormat format) {
			this.name = name;
			this.format = format;
		}
	}

	/** @return the compiled program, or null when it is unavailable */
	public static ShaderInstance get(Program program) {
		if (program.failed) return null;
		if (program.compiled == null) {
			try {
				program.compiled = compile(program);
			} catch (Exception e) {
				program.failed = true;
				CheatProgram.LOGGER.error("Cheat Program shader {} is unavailable, falling back to flat rendering", program.name, e);
				return null;
			}
		}
		return program.compiled;
	}

    private static ShaderInstance compile(Program program)throws Exception {
        // Legacy ShaderInstance prefixes a bare program name before constructing a resource ID.
        // Route unique local aliases to the mod resources without relying on Fabric API.
        var bundled=new net.minecraft.server.packs.PathPackResources("Cheat Program bundled shaders",net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("cheatprogram").orElseThrow().getRootPaths().getFirst(),true);
        net.minecraft.server.packs.resources.ResourceProvider provider=location->{
            String requested=location.getPath(),prefix="shaders/core/cheatprogram_";
            String path=requested.startsWith(prefix)?"/assets/cheatprogram/shaders/core/"+requested.substring(prefix.length()):"/assets/"+location.getNamespace()+"/"+requested;
            if(Shaders.class.getResource(path)==null)return java.util.Optional.empty();
            return java.util.Optional.of(new net.minecraft.server.packs.resources.Resource(bundled,()->{
                InputStream input=Shaders.class.getResourceAsStream(path);
                if(!path.endsWith(".json"))return input;
                try(input){
                    var json=JsonParser.parseString(new String(input.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
                    for(String type:new String[]{"vertex","fragment"})if(json.has(type))json.addProperty(type,"cheatprogram_"+json.get(type).getAsString().replace("cheatprogram:core/",""));
                    var attributes=new com.google.gson.JsonArray();for(String attribute:program.format.getElementAttributeNames())attributes.add(attribute);json.add("attributes",attributes);if(program.format==DefaultVertexFormat.POSITION_TEX_COLOR){var blend=new com.google.gson.JsonObject();blend.addProperty("func","add");blend.addProperty("srcrgb","srcalpha");blend.addProperty("dstrgb","1-srcalpha");blend.addProperty("srcalpha","one");blend.addProperty("dstalpha","1-srcalpha");json.add("blend",blend);}
                    return new java.io.ByteArrayInputStream(json.toString().getBytes(StandardCharsets.UTF_8));
                }
            }));
        };
        return new ShaderInstance(provider,"cheatprogram_"+program.name,program.format);
    }

	private static String read(String path) throws IOException {
		try (InputStream in = Shaders.class.getResourceAsStream("/assets/" + CheatProgram.MOD_ID + "/" + path)) {
			if (in == null) throw new FileNotFoundException(path);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
