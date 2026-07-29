package com.coolercomm.mobile

import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 아주 작은 Modbus TCP 클라이언트 (MBAP + PDU).
 * 지원: FC03 읽기(홀딩), FC06 단일쓰기, FC16 다중쓰기.
 * 냉각기 컨트롤러(W5500, 포트 502)와 동일 규격.
 */
class ModbusTcp(val host: String, val port: Int, val unit: Int) {
    private var socket: Socket? = null
    private var tid = 0

    val isOpen: Boolean
        get() = socket?.let { it.isConnected && !it.isClosed } ?: false

    fun connect(timeoutMs: Int = 2000) {
        close()
        val s = Socket()
        s.connect(InetSocketAddress(host, port), timeoutMs)
        s.tcpNoDelay = true
        s.soTimeout = timeoutMs
        socket = s
    }

    fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    @Synchronized
    private fun transact(pdu: ByteArray): ByteArray {
        val s = socket ?: throw Exception("연결 안됨")
        tid = (tid + 1) and 0xFFFF
        val len = pdu.size + 1
        val frame = ByteArray(7 + pdu.size)
        frame[0] = (tid shr 8).toByte()
        frame[1] = (tid and 0xFF).toByte()
        frame[2] = 0
        frame[3] = 0
        frame[4] = (len shr 8).toByte()
        frame[5] = (len and 0xFF).toByte()
        frame[6] = unit.toByte()
        System.arraycopy(pdu, 0, frame, 7, pdu.size)

        val out = s.getOutputStream()
        out.write(frame)
        out.flush()

        val ins = s.getInputStream()
        val head = readN(ins, 6)
        val rlen = ((head[4].toInt() and 0xFF) shl 8) or (head[5].toInt() and 0xFF)
        if (rlen < 2 || rlen > 260) throw Exception("응답 길이 이상 $rlen")
        val body = readN(ins, rlen) // [unit, func, ...]
        val func = body[1].toInt() and 0xFF
        if (func and 0x80 != 0) {
            val exc = if (body.size >= 3) body[2].toInt() and 0xFF else 0
            throw Exception(describeExc(exc))
        }
        return body
    }

    private fun readN(ins: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var r = 0
        while (r < n) {
            val k = ins.read(b, r, n - r)
            if (k <= 0) throw Exception("연결이 끊어졌습니다")
            r += k
        }
        return b
    }

    /** FC03: start 부터 count 워드 읽기 */
    fun readHolding(start: Int, count: Int): IntArray {
        val pdu = byteArrayOf(
            0x03,
            (start shr 8).toByte(), (start and 0xFF).toByte(),
            (count shr 8).toByte(), (count and 0xFF).toByte()
        )
        val body = transact(pdu)
        val res = IntArray(count)
        for (i in 0 until count) {
            res[i] = ((body[3 + i * 2].toInt() and 0xFF) shl 8) or (body[4 + i * 2].toInt() and 0xFF)
        }
        return res
    }

    /** FC05: 단일 코일 쓰기 (on=true → 0xFF00, false → 0x0000) */
    fun writeCoil(addr: Int, on: Boolean) {
        val v = if (on) 0xFF00 else 0x0000
        val pdu = byteArrayOf(
            0x05,
            (addr shr 8).toByte(), (addr and 0xFF).toByte(),
            (v shr 8).toByte(), (v and 0xFF).toByte()
        )
        transact(pdu)
    }

    /** FC06: 단일 레지스터 쓰기 (value 는 0~65535, 음수는 호출측에서 & 0xFFFF) */
    fun writeSingle(addr: Int, value: Int) {
        val v = value and 0xFFFF
        val pdu = byteArrayOf(
            0x06,
            (addr shr 8).toByte(), (addr and 0xFF).toByte(),
            (v shr 8).toByte(), (v and 0xFF).toByte()
        )
        transact(pdu)
    }

    private fun describeExc(code: Int): String = when (code) {
        1 -> "Modbus 예외: 잘못된 기능코드"
        2 -> "Modbus 예외: 잘못된 주소"
        3 -> "Modbus 예외: 잘못된 값"
        4 -> "Modbus 예외: 슬레이브 처리 실패"
        else -> "Modbus 예외코드 0x%02X".format(code)
    }
}
