package com.arforcemobile.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 读取 BaseVertex DrawContext 的私有字段:
 *  - count: 本 context 的索引数量
 *  - baseVertex: 顶点缓冲偏移
 *  - elementBuffer: RenderSystem 共享顺序索引缓冲(所有 context 同 mode 共用)
 */
@Mixin(targets = "com.github.argon4w.acceleratedrendering.core.buffers.accelerated.draw.basevertex.BaseVertexDrawContextPool$DrawContext")
public interface BaseVertexContextAccessor {

    @Accessor("count")
    int ar$count();

    @Accessor("baseVertex")
    int ar$baseVertex();

    @Accessor("elementBuffer")
    RenderSystem.AutoStorageIndexBuffer ar$elementBuffer();
}
