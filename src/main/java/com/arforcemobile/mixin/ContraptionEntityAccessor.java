package com.arforcemobile.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 读取 AbstractEntityVisual 的 entity 字段。
 *
 * ContraptionVisualLightHysteresisMixin 不能直接 @Shadow 该字段：它声明在父类
 * AbstractEntityVisual 里，而 Mixin 的 @Shadow 只在目标类自身查找（真机实测
 * InvalidMixinException: @Shadow field entity was not located in the target class）。
 * 改用 accessor 注入到父类，滞回 mixin 里对 this 做 duck-cast 调用。
 */
@Mixin(targets = "dev.engine_room.flywheel.lib.visual.AbstractEntityVisual")
public interface ContraptionEntityAccessor {

    @Accessor(value = "entity", remap = false)
    Entity arf$entity();
}
