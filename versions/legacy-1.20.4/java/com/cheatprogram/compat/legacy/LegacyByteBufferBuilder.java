package com.cheatprogram.compat.legacy;
/** The old renderer owns its allocation directly rather than a separate memory arena. */
public final class LegacyByteBufferBuilder extends com.mojang.blaze3d.vertex.BufferBuilder implements AutoCloseable {
    final int capacity;
    public LegacyByteBufferBuilder(int capacity){super(capacity);this.capacity=capacity;}
    @Override public void close(){release();}
}
