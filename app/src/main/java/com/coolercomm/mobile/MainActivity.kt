package com.coolercomm.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { CoolerScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoolerScreen() {
    val scope = rememberCoroutineScope()

    var modelIdx by remember { mutableStateOf(0) }         // 0=DH ONECYCLE
    val model = MODELS[modelIdx]
    var modelMenu by remember { mutableStateOf(false) }

    var host by remember { mutableStateOf("192.168.219.15") }
    var port by remember { mutableStateOf("502") }
    var unit by remember { mutableStateOf("1") }

    var connected by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("연결 안됨") }
    var mainT by remember { mutableStateOf("--.-") }
    var subT by remember { mutableStateOf("--.-") }
    var setT by remember { mutableStateOf("--.-") }
    var running by remember { mutableStateOf(false) }
    var alarm by remember { mutableStateOf("-") }
    var ctrlMode by remember { mutableStateOf("-") }
    var setInput by remember { mutableStateOf("20.0") }
    var inputs by remember { mutableStateOf<List<Pair<String, Boolean>>>(emptyList()) }
    var outputs by remember { mutableStateOf<List<Pair<String, Boolean>>>(emptyList()) }

    var modbus by remember { mutableStateOf<ModbusTcp?>(null) }
    var pollJob by remember { mutableStateOf<Job?>(null) }

    fun disconnect() {
        pollJob?.cancel(); pollJob = null
        val m = modbus; modbus = null
        scope.launch(Dispatchers.IO) { m?.close() }
        connected = false; status = "연결 종료"
        mainT = "--.-"; subT = "--.-"; setT = "--.-"; alarm = "-"; ctrlMode = "-"
        inputs = emptyList(); outputs = emptyList()
    }

    fun connect() {
        val md = model
        val m = ModbusTcp(host.trim(), port.trim().toIntOrNull() ?: 502, unit.trim().toIntOrNull() ?: 1)
        status = "연결 중..."
        scope.launch {
            try {
                withContext(Dispatchers.IO) { m.connect() }
                modbus = m; connected = true; status = "연결됨 — 폴링"
                pollJob = scope.launch {
                    while (isActive) {
                        try {
                            val r = withContext(Dispatchers.IO) { m.readHolding(md.statusBase, md.statusCount) }
                            mainT = md.tempStr(r, md.mainReg, md.mainScale)
                            subT = md.tempStr(r, md.subReg, md.subScale)
                            setT = md.tempStr(r, md.setEchoReg, md.setScale)
                            running = ((md.get(r, md.runStateReg) shr md.runStateBit) and 1) != 0
                            alarm = md.alarmSummary(r)
                            ctrlMode = if (md.ctrlModeReg >= 0) {
                                val cm = md.get(r, md.ctrlModeReg); if (cm in 0..3) CTRL_MODE[cm] else cm.toString()
                            } else ""
                            inputs = md.ioStates(r, md.inputRegs, md.inputBits)
                            outputs = md.ioStates(r, md.outputRegs, md.outputBits)
                            status = "수신 OK"
                        } catch (e: Exception) {
                            status = "통신오류: ${e.message}"
                        }
                        delay(700)
                    }
                }
            } catch (e: Exception) {
                status = "연결 실패: ${e.message}"; connected = false
            }
        }
    }

    fun sendRunStop(run: Boolean) {
        val m = modbus ?: return
        val md = model
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (md.runStopType == "coil") m.writeCoil(md.runStopAddr, run)
                    else m.writeSingle(md.runStopAddr, if (run) 1 else 0)
                }
                status = (if (run) "운전" else "정지") + " 전송됨"
            } catch (e: Exception) { status = (if (run) "운전" else "정지") + " 실패: ${e.message}" }
        }
    }

    fun sendAlarmReset() {
        val m = modbus ?: return
        val md = model
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (md.alarmResetType == "coil") m.writeCoil(md.alarmResetAddr, true)
                    else m.writeSingle(md.alarmResetAddr, 1)
                }
                status = "경보 리셋 전송됨"
            } catch (e: Exception) { status = "경보 리셋 실패: ${e.message}" }
        }
    }

    fun sendSetpoint() {
        val m = modbus ?: return
        val md = model
        val v = setInput.trim().toDoubleOrNull()
        if (v == null) { status = "숫자를 입력하세요"; return }
        val raw = (v / md.setWriteScale).roundToInt()
        scope.launch {
            try {
                withContext(Dispatchers.IO) { m.writeSingle(md.setWriteReg, raw) }
                status = "설정온도 전송됨 ($v℃)"
            } catch (e: Exception) { status = "설정온도 실패: ${e.message}" }
        }
    }

    fun sendTimeSync() {
        val m = modbus ?: return
        val md = model
        if (!md.hasTimeSync) { status = "이 모델은 시각동기 미지원"; return }
        val c = java.util.Calendar.getInstance()
        val yr = c.get(java.util.Calendar.YEAR)
        val mo = c.get(java.util.Calendar.MONTH) + 1
        val da = c.get(java.util.Calendar.DAY_OF_MONTH)
        val hh = c.get(java.util.Calendar.HOUR_OF_DAY)
        val mi = c.get(java.util.Calendar.MINUTE)
        val ss = c.get(java.util.Calendar.SECOND)
        val base = md.timeSyncBase
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    m.writeSingle(base + 0, yr); m.writeSingle(base + 1, mo); m.writeSingle(base + 2, da)
                    m.writeSingle(base + 3, hh); m.writeSingle(base + 4, mi); m.writeSingle(base + 5, ss)
                    m.writeSingle(base + 6, 1)   // 커밋
                }
                status = "시각동기 완료 %04d-%02d-%02d %02d:%02d:%02d".format(yr, mo, da, hh, mi, ss)
            } catch (e: Exception) { status = "시각동기 실패: ${e.message}" }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("냉각기 제어", fontSize = 22.sp, fontWeight = FontWeight.Bold)

        // 모델 선택 (연결 중엔 잠금)
        ExposedDropdownMenuBox(expanded = modelMenu, onExpandedChange = { if (!connected) modelMenu = it }) {
            OutlinedTextField(
                value = model.name, onValueChange = {}, readOnly = true,
                label = { Text("모델") }, enabled = !connected,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenu) },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                MODELS.forEachIndexed { i, mdl ->
                    DropdownMenuItem(text = { Text(mdl.name) }, onClick = { modelIdx = i; modelMenu = false })
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(host, { host = it }, label = { Text("IP") }, singleLine = true, enabled = !connected, modifier = Modifier.weight(2.2f))
            OutlinedTextField(port, { port = it }, label = { Text("포트") }, singleLine = true, enabled = !connected, modifier = Modifier.weight(1f))
            OutlinedTextField(unit, { unit = it }, label = { Text("국번") }, singleLine = true, enabled = !connected, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button({ if (connected) disconnect() else connect() }) {
                Text(if (connected) "연결 끊기" else "연결")
            }
            Text(status, color = if (connected) Color(0xFF2E7D32) else Color.Gray)
        }

        HorizontalDivider()

        TempCard("메인 온도", mainT, Color(0xFF1565C0))
        TempCard("서브 온도", subT, Color(0xFF0277BD))
        TempCard("설정 온도", setT, Color(0xFFE65100))

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (running) "● 운전중" else "■ 정지",
                color = if (running) Color(0xFF2E7D32) else Color(0xFFC62828),
                fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (ctrlMode.isNotEmpty()) Text("제어: $ctrlMode", fontSize = 16.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("알람: $alarm", color = if (alarm == "정상") Color.Gray else Color(0xFFC62828),
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            OutlinedButton({ sendAlarmReset() }, enabled = connected) { Text("경보 리셋") }
        }

        HorizontalDivider()

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button({ sendRunStop(true) }, enabled = connected,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                modifier = Modifier.weight(1f)) { Text("운전", fontSize = 18.sp) }
            Button({ sendRunStop(false) }, enabled = connected,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                modifier = Modifier.weight(1f)) { Text("정지", fontSize = 18.sp) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(setInput, { setInput = it }, label = { Text("설정온도 ℃") }, singleLine = true, modifier = Modifier.weight(2f))
            Button({ sendSetpoint() }, enabled = connected, modifier = Modifier.weight(1f)) { Text("전송") }
        }

        if (model.hasTimeSync) {
            Button({ sendTimeSync() }, enabled = connected, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F))) { Text("PC(폰) 시각 동기화") }
        }

        // ── 입출력 표시 ──
        if (inputs.isNotEmpty() || outputs.isNotEmpty()) {
            HorizontalDivider()
            IoSection("입력", inputs, Color(0xFF1565C0))
            Spacer(Modifier.height(4.dp))
            IoSection("출력", outputs, Color(0xFF2E7D32))
        }

        Text("※ 운전/정지·설정온도 쓰기는 컨트롤러가 원격/컴퓨터 제어 모드일 때만 반영됩니다.",
            fontSize = 12.sp, color = Color.Gray)
    }
}

@Composable
fun IoSection(title: String, items: List<Pair<String, Boolean>>, onColor: Color) {
    Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color(0xFF444444))
    Spacer(Modifier.height(6.dp))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.chunked(2).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowItems.forEach { (name, on) ->
                    IoChip(name, on, onColor, Modifier.weight(1f))
                }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun IoChip(name: String, on: Boolean, onColor: Color, modifier: Modifier = Modifier) {
    val bg = if (on) onColor else Color(0xFFECEFF1)
    val fg = if (on) Color.White else Color(0xFF90A4AE)
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(if (on) Color(0xFFFFF176) else Color(0xFFB0BEC5)))
        Text(name, color = fg, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1, textAlign = TextAlign.Start, modifier = Modifier.weight(1f))
    }
}

@Composable
fun TempCard(title: String, value: String, color: Color) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), fontSize = 16.sp)
            Text(value, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = color)
            Text("  ℃", fontSize = 18.sp, color = color)
        }
    }
}
