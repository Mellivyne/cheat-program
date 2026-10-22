package com.cheatprogram.compat.gl;

import com.mojang.blaze3d.buffers.GpuBufferSlice;

/** Retains the actual native fog uniform; the sentinel binds our immutable no-fog UBO. */
public record FogParameters(GpuBufferSlice buffer) {
    public static final FogParameters NO_FOG=new FogParameters(null);
}
