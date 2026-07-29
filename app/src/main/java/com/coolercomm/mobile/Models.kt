package com.coolercomm.mobile

/** 입출력 채널 1개 (비트위치 + 이름) */
data class IoBit(val bit: Int, val name: String)

/**
 * 냉각기 모델 정의 (Windows 프로그램의 model.csv 와 동일 개념).
 * 상태블록을 한 번 읽어(statusBase~) 필드별로 해석한다.
 */
class CoolerModel(
    val name: String,
    val statusBase: Int, val statusCount: Int,
    val mainReg: Int, val mainScale: Double,
    val subReg: Int, val subScale: Double,
    val setEchoReg: Int, val setScale: Double,   // -1 = 숨김
    val setWriteReg: Int, val setWriteScale: Double,
    val runStateReg: Int, val runStateBit: Int,
    val alarmMode: String,                 // "bits" | "history"
    val alarmRegs: IntArray,
    val alarmNames: Map<Int, String>,      // history: 코드→이름 / bits: 비트→이름
    val runStopType: String,               // "coil" | "reg"
    val runStopAddr: Int,
    val ctrlModeReg: Int,                   // -1 = 없음
    val alarmResetType: String,            // "coil" | "reg"  (경보리셋)
    val alarmResetAddr: Int,
    val timeSyncBase: Int,                 // -1 = 시각동기 미지원. base~base+5=년월일시분초, base+6=커밋
    val inputRegs: IntArray,               // 입력플래그 합성 레지스터(상위→하위). DAEHO:[500,501] DH35:[9]
    val inputBits: List<IoBit>,
    val outputRegs: IntArray,              // 출력플래그 합성 레지스터. DAEHO:[502,503] DH35:[5]
    val outputBits: List<IoBit>
) {
    fun get(block: IntArray, absReg: Int): Int {
        val i = absReg - statusBase
        return if (i in block.indices) block[i] else 0
    }
    fun tempStr(block: IntArray, reg: Int, scale: Double): String {
        if (reg < 0) return "--.-"
        val v = get(block, reg).toShort().toInt() * scale     // 부호있는 16비트
        val t = kotlin.math.truncate(v * 10.0) / 10.0          // HMI처럼 소수1자리 버림
        return String.format("%.1f", t)
    }
    /** 현재 알람 요약 문자열 */
    fun alarmSummary(block: IntArray): String {
        val active = ArrayList<String>()
        if (alarmMode == "history") {
            for (reg in alarmRegs) {
                val code = get(block, reg)
                if (code != 0) active.add(alarmNames[code] ?: "코드 $code")
            }
        } else { // bits
            for (reg in alarmRegs) {
                val bits = get(block, reg)
                for (b in 0..15) if ((bits shr b) and 1 != 0)
                    active.add(alarmNames[b] ?: "비트 $b")
            }
        }
        return if (active.isEmpty()) "정상" else active.joinToString(", ")
    }
    /** 여러 레지스터를 상위→하위로 합성해 32비트 플래그로 만든다 */
    private fun ioValue(block: IntArray, regs: IntArray): Long {
        var v = 0L
        for (r in regs) v = (v shl 16) or (get(block, r).toLong() and 0xFFFF)
        return v
    }
    /** 입출력 상태: (이름, ON여부) 목록 */
    fun ioStates(block: IntArray, regs: IntArray, bits: List<IoBit>): List<Pair<String, Boolean>> {
        val v = ioValue(block, regs)
        return bits.map { it.name to (((v shr it.bit) and 1L) != 0L) }
    }
    val hasTimeSync: Boolean get() = timeSyncBase >= 0
}

val CTRL_MODE = arrayOf("일반제어", "일반정밀", "정밀제어", "펌프다운")

private val DAEHO_ALARMS = mapOf(
    1 to "순환펌프 과부하", 2 to "순환예비펌프 과부하", 3 to "순환펌프 유량이상",
    4 to "공정펌프 과부하", 5 to "공정예비펌프 과부하", 6 to "공정펌프 유량이상",
    7 to "콤프1 과부하", 8 to "콤프1 인터널", 9 to "콤프2 과부하", 10 to "콤프2 인터널",
    11 to "제어팬1 과부하", 12 to "제어팬2 과부하", 13 to "상시팬 과부하",
    14 to "고압이상1", 15 to "저압이상1", 16 to "고압이상2", 17 to "저압이상2",
    18 to "유압이상1", 19 to "유압이상2", 20 to "메인 온도센서 이상", 21 to "서브 온도센서 이상",
    22 to "고온 경보", 23 to "저온 경보", 24 to "동파 경보", 25 to "역상 경보",
    26 to "과냉 경보", 27 to "저수위 경보", 28 to "히터1 경보", 29 to "히터2/비상정지", 30 to "콤프 정지"
)

private val DH35_ALARMS = mapOf(
    0 to "NTC개방", 1 to "NTC단락", 2 to "과열", 3 to "역상", 4 to "오토튜닝중",
    5 to "고압", 6 to "결상",
    8 to "펌프EOCR", 9 to "콤프EOCR", 10 to "팬EOCR", 11 to "히터과열",
    12 to "과냉", 13 to "유수", 14 to "저수위", 15 to "저압락아웃"
)

// DAEHO ONECYCLE 입력(입력플래그=(500<<16)|501) — inputs.csv 와 동일 비트
private val DAEHO_IN = listOf(
    IoBit(3, "콤프 OCR1"), IoBit(4, "콤프 INT1"), IoBit(5, "휀 OCR1"),
    IoBit(6, "고압1"), IoBit(7, "저압1"), IoBit(8, "유압1"),
    IoBit(10, "고수위"), IoBit(11, "저수위"), IoBit(12, "공정펌프 OCR"),
    IoBit(14, "공정펌프 플로우"), IoBit(20, "과냉TC"), IoBit(21, "원격"),
    IoBit(22, "팬스위치1"), IoBit(24, "히터1"), IoBit(25, "비상정지"),
    IoBit(26, "예비1"), IoBit(27, "예비2"), IoBit(28, "예비3"),
    IoBit(29, "센서A"), IoBit(30, "센서B"), IoBit(31, "역상")
)
// DAEHO ONECYCLE 출력(출력플래그=(502<<16)|503) — outputs.csv 와 동일 비트
private val DAEHO_OUT = listOf(
    IoBit(0, "운전(RUN)"), IoBit(1, "알람"), IoBit(4, "공정펌프"),
    IoBit(6, "급수솔"), IoBit(7, "냉각솔1"), IoBit(8, "가열솔1"),
    IoBit(9, "콤프1"), IoBit(13, "휀1"), IoBit(19, "히터1")
)

// DH 3.5 입력(reg9) / 출력(reg5)
private val DH35_IN = listOf(
    IoBit(0, "저수위"), IoBit(1, "고수위"), IoBit(2, "공정펌프 EOCR"),
    IoBit(3, "콤프1 EOCR"), IoBit(4, "휀 EOCR"), IoBit(5, "히터 과열"),
    IoBit(6, "휀제어 스위치"), IoBit(7, "과냉 TC"), IoBit(8, "공정플로우"), IoBit(9, "원격리모트")
)
private val DH35_OUT = listOf(
    IoBit(0, "펌프"), IoBit(1, "콤프"), IoBit(2, "팬"), IoBit(3, "냉각 솔밸브"),
    IoBit(4, "가열 솔밸브"), IoBit(5, "급수 솔밸브"), IoBit(6, "히터"), IoBit(7, "알람")
)

val MODELS = listOf(
    CoolerModel(
        name = "DH ONECYCLE",
        statusBase = 500, statusCount = 20,
        mainReg = 504, mainScale = 0.1,
        subReg = 505, subScale = 0.1,
        setEchoReg = 514, setScale = 0.1,
        setWriteReg = 0, setWriteScale = 0.1,
        runStateReg = 503, runStateBit = 0,
        alarmMode = "history", alarmRegs = intArrayOf(515, 516, 517, 518, 519),
        alarmNames = DAEHO_ALARMS,
        runStopType = "reg", runStopAddr = 550,      // 펌웨어 명령 레지스터 550 (TCP)
        ctrlModeReg = -1,
        alarmResetType = "reg", alarmResetAddr = 551, // 경보리셋 551
        timeSyncBase = 520,                           // RTC 520~526 (FC06 단일+커밋)
        inputRegs = intArrayOf(500, 501), inputBits = DAEHO_IN,
        outputRegs = intArrayOf(502, 503), outputBits = DAEHO_OUT
    ),
    CoolerModel(
        name = "DH 3.5",
        statusBase = 0, statusCount = 34,
        mainReg = 0, mainScale = 0.01,
        subReg = 1, subScale = 0.01,
        setEchoReg = 20, setScale = 0.01,
        setWriteReg = 20, setWriteScale = 0.01,
        runStateReg = 10, runStateBit = 0,
        alarmMode = "bits", alarmRegs = intArrayOf(12),
        alarmNames = DH35_ALARMS,
        runStopType = "reg", runStopAddr = 21,
        ctrlModeReg = 22,
        alarmResetType = "reg", alarmResetAddr = 34,
        timeSyncBase = 120,                           // RTC 120~126
        inputRegs = intArrayOf(9), inputBits = DH35_IN,
        outputRegs = intArrayOf(5), outputBits = DH35_OUT
    )
)
