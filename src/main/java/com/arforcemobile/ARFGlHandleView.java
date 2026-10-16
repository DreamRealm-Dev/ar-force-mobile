package com.arforcemobile;

/**
 * Flywheel GlObject 的鸭子接口：暴露 GL handle 给其他 arforcemobile mixin 使用。
 * 由 GlObjectHandleMixin 合并进 GlObject，子类（GlBuffer/TextureBuffer）自动继承。
 */
public interface ARFGlHandleView {

    int arf$handle();
}
