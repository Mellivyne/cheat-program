package com.cheatprogram.render;

import com.cheatprogram.CheatProgram;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.serialization.JsonOps;
import com.cheatprogram.compat.gl.CompiledShaderProgram;

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
		CompiledShaderProgram compiled;
		boolean failed;

		Program(String name, VertexFormat format) {
			this.name = name;
			this.format = format;
		}
	}

	/** @return the compiled program, or null when it is unavailable */
	public static CompiledShaderProgram get(Program program) {
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

	private static CompiledShaderProgram compile(Program program) throws Exception {
		return CompiledShaderProgram.fromMod(program.name, program.format);
	}

	private static String read(String path) throws IOException {
		try (InputStream in = Shaders.class.getResourceAsStream("/assets/" + CheatProgram.MOD_ID + "/" + path)) {
			if (in == null) throw new FileNotFoundException(path);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
