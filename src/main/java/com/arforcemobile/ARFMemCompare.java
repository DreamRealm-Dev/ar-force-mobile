package com.arforcemobile;

import dev.engine_room.flywheel.lib.memory.MemoryBlock;

import java.nio.ByteBuffer;

/**
 * MemoryBlock 内容比对（UBO 去重用）。absolute get 不受 position/limit 影响。
 * 块大小 ~256B×5，每帧比较成本可忽略。
 */
public final class ARFMemCompare {

    private ARFMemCompare() {
    }

    public static boolean equal(MemoryBlock a, MemoryBlock b) {
        long size = a.size();
        if (size != b.size()) {
            return false;
        }
        try {
            ByteBuffer ba = a.asBuffer();
            ByteBuffer bb = b.asBuffer();
            long i = 0;
            long n8 = size & ~7L;
            for (; i < n8; i += 8) {
                if (ba.getLong((int) i) != bb.getLong((int) i)) {
                    return false;
                }
            }
            for (; i < size; i++) {
                if (ba.get((int) i) != bb.get((int) i)) {
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
