package com.arforcemobile.mixin;

import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.draw.pools.IDrawContextPool.IDrawContext;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 读取 SimpleLayerContexts 的受保护字段 contexts。
 * 注意:Mixin 的 @Accessor 要求返回类型与字段类型描述符完全一致——
 * 字段是 fastutil 的 ReferenceArrayList,返回 java.util.List 会匹配失败(InvalidAccessorException)。
 */
@Mixin(targets = "com.github.argon4w.acceleratedrendering.core.buffers.accelerated.layers.storage.SimpleLayerContexts")
public interface SimpleLayerContextsAccessor {

    @Accessor("contexts")
    ReferenceArrayList<IDrawContext> ar$contexts();
}
