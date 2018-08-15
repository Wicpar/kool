package kool_


import org.lwjgl.PointerBuffer
import org.lwjgl.system.MemoryUtil
import java.nio.*

/**
 * Created by elect on 05/03/17.
 */

fun floatBufferBig(capacity: Int): FloatBuffer = MemoryUtil.memCallocFloat(capacity)

fun doubleBufferBig(capacity: Int): DoubleBuffer = MemoryUtil.memCallocDouble(capacity)

fun bufferBig(capacity: Int): ByteBuffer = MemoryUtil.memCalloc(capacity)
fun shortBufferBig(capacity: Int): ShortBuffer = MemoryUtil.memCallocShort(capacity)
fun intBufferBig(capacity: Int): IntBuffer = MemoryUtil.memCallocInt(capacity)
fun longBufferBig(capacity: Int): LongBuffer = MemoryUtil.memCallocLong(capacity)

fun charBufferBig(capacity: Int): CharBuffer = TODO()

fun pointerBufferBig(capacity: Int): PointerBuffer = MemoryUtil.memCallocPointer(capacity)
fun pointerBufferBig(capacity: IntBuffer): PointerBuffer = MemoryUtil.memCallocPointer(capacity[0])
fun pointerBufferBig(capacity: IntArray): PointerBuffer = MemoryUtil.memCallocPointer(capacity[0])


fun Buffer.free() = MemoryUtil.memFree(this)

inline val Buffer.adr: Ptr
    get() = when (this) {
        is ByteBuffer -> MemoryUtil.memAddress(this)
        is ShortBuffer -> MemoryUtil.memAddress(this)
        is IntBuffer -> MemoryUtil.memAddress(this)
        is LongBuffer -> MemoryUtil.memAddress(this)
        is FloatBuffer -> MemoryUtil.memAddress(this)
        is DoubleBuffer -> MemoryUtil.memAddress(this)
        is CharBuffer -> MemoryUtil.memAddress(this)
        else -> throw Error("unsupported buffer type")
    }

inline var Buffer.pos: Int
    get() = position()
    set(value) {
        position(value)
    }

inline val Buffer.cap: Int
    get() = capacity()

inline val Buffer.rem: Int
    get() = remaining()

inline val Buffer.remSize: Int
    get() = rem * when(this) {
        is ByteBuffer -> 1
        is ShortBuffer -> 2
        is IntBuffer -> 4
        is LongBuffer -> 8
        is FloatBuffer -> 4
        is DoubleBuffer -> 8
//        is CharBuffer -> java.lang.Integer.BYTES
        else -> throw Error("unsupported buffer type")
    }


inline val PointerBuffer.adr: Ptr
    get() = MemoryUtil.memAddress(this)

inline var PointerBuffer.pos: Int
    get() = position()
    set(value) {
        position(value)
    }

inline val PointerBuffer.cap: Int
    get() = capacity()

inline val PointerBuffer.rem: Int
    get() = remaining()

inline val PointerBuffer.remSize: Int
    get() = remaining() * org.lwjgl.system.Pointer.POINTER_SIZE

typealias Ptr = Long // TODO -> inline class
typealias Adr = Long // TODO -> inline class


class A

fun main(args: Array<String>) {

}