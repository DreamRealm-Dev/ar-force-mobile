package com.arforcemobile.mixin;

import dev.engine_room.flywheel.backend.gl.buffer.GlBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * S5 绑定侧的 instancer 内部状态访问（javap 实证字段）：
 * vbo:Ldev/engine_room/flywheel/backend/gl/buffer/GlBuffer;（private）
 * instanceStride:I（private final）
 */
@Mixin(targets = "dev.engine_room.flywheel.backend.engine.instancing.InstancedInstancer")
public interface InstancedInstancerAccessor {

    @Accessor(value = "vbo", remap = false)
    GlBuffer arforcemobile$vbo();

    @Accessor(value = "instanceStride", remap = false)
    int arforcemobile$instanceStride();
}
