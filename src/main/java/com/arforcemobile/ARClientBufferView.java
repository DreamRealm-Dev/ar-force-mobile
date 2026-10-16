package com.arforcemobile;

/**
 * 客户端内存回退缓冲的鸭子接口。
 *
 * MappedBufferClientModeMixin 会把本接口合并进 AR 的 MappedBuffer 类，
 * 上传钩子（MutableBuffer 层 / AcceleratedBufferSource 层）用 instanceof 探测。
 * 方法名带 arf$ 前缀避免与目标类/其他 mod 混入的方法冲突。
 */
public interface ARClientBufferView {

    /** 本缓冲是否已进入客户端内存模式（GL 映射不可用或被强制）。 */
    boolean arf$isClientMode();

    /** 客户端内存指针（nmemAlloc 分配）。 */
    long arf$getClientPtr();

    /** 本帧已写入长度（AR 的 MappedBuffer.position）。 */
    long arf$getPosition();

    /** 已经上传到 GL 的长度（增量上传游标）。 */
    long arf$getUploaded();

    void arf$setUploaded(long value);

    /** 客户端内存大小。 */
    long arf$getClientSize();

    /** GL 缓冲的存储是否已确保存在（MG 缺 EXT_buffer_storage 时 glBufferStorage 会静默 no-op）。 */
    boolean arf$isStorageEnsured();

    void arf$setStorageEnsured(boolean value);

    /** 本缓冲是否有未上传的新数据（脏标记，避免上传器全量扫描注册表）。 */
    boolean arf$isDirty();

    void arf$setDirty(boolean value);

    /** 释放客户端内存并退出客户端模式（幂等，供 delete 钩子调用）。 */
    void arf$releaseClient();
}
