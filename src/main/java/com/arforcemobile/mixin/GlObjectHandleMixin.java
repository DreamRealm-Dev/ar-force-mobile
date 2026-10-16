package com.arforcemobile.mixin;

import com.arforcemobile.ARFGlHandleView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * 把 GlObject 的私有 handle 暴露成鸭子接口，供 GlBuffer 上传钩子读取。
 */
@Mixin(targets = "dev.engine_room.flywheel.backend.gl.GlObject")
public abstract class GlObjectHandleMixin implements ARFGlHandleView {

    @Shadow(remap = false)
    private int handle;

    @Override
    @Unique
    public int arf$handle() {
        return handle;
    }
}
