package com.k4music.android.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class PieceReaderTest {
    /** A 10-byte file served in pieces of [pieceSize]; a read past the piece ends it, as a bounded request does. */
    private class FakeSource(private val size: Int, private val pieceSize: Long) {
        var position = 0L
        var pieceEnd = 0L
        var opens = 0

        fun open(at: Long): Long {
            opens++
            position = at
            val length = if (pieceSize < 0) -1L else minOf(pieceSize, size - at)
            pieceEnd = if (pieceSize < 0) size.toLong() else at + length
            return length
        }

        fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= pieceEnd) return -1
            val read = minOf(length.toLong(), pieceEnd - position).toInt()
            position += read
            return read
        }
    }

    private fun drain(reader: PieceReader): Int {
        val buffer = ByteArray(3)
        var total = 0
        while (true) {
            val read = reader.read(buffer, 0, buffer.size)
            if (read == -1) return total
            total += read
        }
    }

    private fun reader(source: FakeSource, total: Long) = PieceReader(
        totalLength = { total },
        readPiece = source::read,
        openPiece = source::open
    ).apply { start(0L, source.open(0L)) }

    @Test
    fun `the end of a piece is not the end of the file`() {
        val source = FakeSource(10, 4)
        assertEquals(10, drain(reader(source, total = 10)))
        assertEquals(3, source.opens) // 4 + 4 + 2 bytes, and no request past the known end
    }

    @Test
    fun `a read asked for part of the file stops at the end it asked for`() {
        val source = FakeSource(10, 3)
        assertEquals(6, drain(reader(source, total = 6))) // the reader is told the end of the request as its total
        assertEquals(2, source.opens)
    }

    @Test
    fun `a read closed while idle carries on where it stopped`() {
        val source = FakeSource(10, 4)
        val reader = reader(source, total = 10)
        val buffer = ByteArray(3)
        assertEquals(3, reader.read(buffer, 0, buffer.size))
        assertEquals(true, reader.resume())
        assertEquals(3, source.position) // not a byte lost or read twice
        assertEquals(7, drain(reader))
    }

    @Test
    fun `an unknown total ends where the source says it ends`() {
        val source = FakeSource(10, 4)
        assertEquals(10, drain(reader(source, total = -1)))
        assertEquals(4, source.opens) // one more to find out: it answers with nothing left
    }

    @Test
    fun `a piece of unknown size is the rest of the file`() {
        val source = FakeSource(10, -1)
        assertEquals(10, drain(reader(source, total = -1)))
        assertEquals(1, source.opens)
    }
}
