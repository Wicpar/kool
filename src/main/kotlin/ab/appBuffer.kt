package ab

import glm_.*
import glm_.buffer.bufferBig
import org.lwjgl.PointerBuffer
import org.lwjgl.system.MemoryUtil
import org.lwjgl.system.Pointer
import java.nio.*
import java.util.concurrent.atomic.AtomicLong

object appBuffer {

    var SIZE = 2 shl 16  // 65536

    var buffer = bufferBig(SIZE)
    var address = MemoryUtil.memAddress(buffer)

    val ptr = AtomicLong(address)

    inline val intBuffer: IntBuffer
        get() {
            val size = Int.BYTES.L
            return MemoryUtil.memIntBuffer(ptr.getAndAdd(size), 1)
        }

    inline val longBuffer: LongBuffer
        get() {
            val size = Long.BYTES.L
            return MemoryUtil.memLongBuffer(ptr.getAndAdd(size), 1)
        }
    inline val doubleBuffer: DoubleBuffer
        get() {
            val size = Double.BYTES.L
            return MemoryUtil.memDoubleBuffer(ptr.getAndAdd(size), 1)
        }
    inline val pointerBuffer: PointerBuffer
        get() {
            val size = Pointer.POINTER_SIZE
            return MemoryUtil.memPointerBuffer(ptr.advance(size), 1)
        }

    inline infix fun pointerBuffer(capacity: Int): PointerBuffer {
        val size = Pointer.POINTER_SIZE * capacity
        return MemoryUtil.memPointerBuffer(ptr.advance(size), capacity)
    }

    inline infix fun pointerBufferOf(pointer: Pointer) = pointerBuffer(1).apply { put(0, pointer) }
    inline infix fun pointerBufferOf(long: Long) = pointerBuffer(1).apply { put(0, long) }
    inline fun pointerBufferOf(long0: Long, long1: Long) = pointerBuffer(1).apply {
        put(0, long0).put(1, long1)
    }
    inline fun pointerBufferOf(long0: Long, long1: Long, long2: Long) = pointerBuffer(1).apply {
        put(0, long0).put(1, long1).put(2, long2)
    }
    inline fun pointerBufferOf(long0: Long, long1: Long, long2: Long, long3: Long) = pointerBuffer(1).apply {
        put(0, long0).put(1, long1).put(2, long2).put(3, long3)
    }
    inline fun pointerBufferOf(long0: Long, long1: Long, long2: Long, long3: Long, long4: Long) = pointerBuffer(1).apply {
        put(0, long0).put(1, long1).put(2, long2).put(3, long3).put(4, long4)
    }

    inline val byte get() = ptr.advance(Byte.BYTES)
    inline val short get() = ptr.advance(Short.BYTES)
    inline val int get() = ptr.advance(Int.BYTES)
    inline val long get() = ptr.advance(Long.BYTES)
    inline val float get() = ptr.advance(Float.BYTES)
    inline val double get() = ptr.advance(Double.BYTES)
    inline val pointer get() = ptr.advance(Pointer.POINTER_SIZE)

    inline fun byteArray(size: Int) = ptr.advance(Byte.BYTES * size)
    inline fun intArray(size: Int) = ptr.advance(Int.BYTES * size)
    inline fun longArray(size: Int) = ptr.advance(Long.BYTES * size)
    inline fun pointerArray(size: Int) = ptr.advance(Pointer.POINTER_SIZE * size)

    inline fun floats(float: Float): Long {
        val res = ptr.advance(Float.BYTES)
        MemoryUtil.memPutFloat(res, float)
        return res
    }

    inline infix fun floatBufferOf(float: Float): FloatBuffer {
        val res = floatBuffer(1)
        res[0] = float
        return res
    }

    inline fun floatBufferOf(float0: Float, float1: Float): FloatBuffer {
        val res = floatBuffer(2)
        res[0] = float0
        res[1] = float1
        return res
    }

    inline fun floatBufferOf(float0: Float, float1: Float, float2: Float): FloatBuffer {
        val res = floatBuffer(3)
        res[0] = float0
        res[1] = float1
        res[2] = float2
        return res
    }

    inline fun floatBufferOf(float0: Float, float1: Float, float2: Float, float3: Float): FloatBuffer {
        val res = floatBuffer(4)
        res[0] = float0
        res[1] = float1
        res[2] = float2
        res[3] = float3
        return res
    }

    inline fun floatBufferOf(vararg floats: Float): FloatBuffer {
        val res = floatBuffer(floats.size)
        for (i in floats.indices)
            res[i] = floats[i]
        return res
    }

    infix inline fun longBufferOf(long: Long): LongBuffer {
        val res = longBuffer(1)
        res[0] = long
        return res
    }

    inline fun longBufferOf(long0: Long, long1: Long): LongBuffer {
        val res = longBuffer(2)
        res[0] = long0
        res[1] = long1
        return res
    }

    inline fun longBufferOf(longs: Collection<Long>): LongBuffer {
        val res = longBuffer(longs.size)
        for (i in longs.indices)
            res[i] = longs.elementAt(i)
        return res
    }

    infix inline fun intBufferOf(int: Int): IntBuffer {
        val res = intBuffer(1)
        res[0] = int
        return res
    }

    inline fun intBufferOf(int0: Int, int1: Int): IntBuffer {
        val res = intBuffer(2)
        res[0] = int0
        res[1] = int1
        return res
    }

    inline fun intBufferOf(int0: Int, int1: Int, int2: Int): IntBuffer {
        val res = intBuffer(3)
        res[0] = int0
        res[1] = int1
        res[2] = int2
        return res
    }

    inline fun intBufferOf(int0: Int, int1: Int, int2: Int, int3: Int): IntBuffer {
        val res = intBuffer(4)
        res[0] = int0
        res[1] = int1
        res[2] = int2
        res[3] = int3
        return res
    }

    inline fun intBufferOf(vararg ints: Int): IntBuffer {
        val res = intBuffer(ints.size)
        for (i in ints.indices)
            res[i] = ints[i]
        return res
    }

    inline fun shortBufferOf(vararg ints: Int): ShortBuffer {
        val res = shortBuffer(ints.size)
        for (i in ints.indices)
            res[i] = ints[i]
        return res
    }

    inline fun buffer(size: Int): ByteBuffer = MemoryUtil.memByteBuffer(ptr.advance(Byte.BYTES * size), size)

    inline fun bufferOfAscii(string: String, nullTerminated: Boolean = true): ByteBuffer {
        val bytes = buffer(string.length + if(nullTerminated) 1 else 0)
        for(i in string.indices)
            bytes[i] = string[i].b
        if(nullTerminated)
            bytes[string.length] = 0
        return bytes
    }

    inline fun bufferOfUtf8(string: String, nullTerminated: Boolean = true): ByteBuffer {
        val bytes = buffer(MemoryUtil.memLengthUTF8(string, nullTerminated))
        MemoryUtil.memUTF8(string, nullTerminated, bytes)
        return bytes
    }

    inline fun intBuffer(size: Int): IntBuffer = MemoryUtil.memIntBuffer(ptr.advance(Int.BYTES * size), size)
    inline fun intBuffer(size: Int, block: (Int) -> Int): IntBuffer {
        val res = intBuffer(size)
        for (i in 0 until res.remaining())
            res[i] = block(i)
        return res
    }
    inline fun shortBuffer(size: Int): ShortBuffer = MemoryUtil.memShortBuffer(ptr.advance(Short.BYTES * size), size)
    inline fun shortBuffer(size: Int, block: (Int) -> Int): ShortBuffer {
        val res = shortBuffer(size)
        for (i in 0 until res.remaining())
            res[i] = block(i)
        return res
    }

    inline fun floatBuffer(size: Int): FloatBuffer = MemoryUtil.memFloatBuffer(ptr.advance(Float.BYTES * size), size)
    inline fun longBuffer(size: Int): LongBuffer = MemoryUtil.memLongBuffer(ptr.advance(Long.BYTES * size), size)

    fun reset() {
        ptr.set(address)
        MemoryUtil.memSet(address, 0, SIZE.L)
    }

    fun next() = MemoryUtil.memGetByte(ptr.get())
    fun printNext() = println("@${ptr.get() - address}: ${next()}")
    val remaining get() = SIZE - consumed
    val consumed get() = ptr.get() - address

    val VERSION = "0.2"
}

inline fun AtomicLong.advance(int: Int) = getAndAdd(int.L)
