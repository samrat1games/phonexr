package com.samrat.cardboardhands

import android.content.Intent
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

class SettingsActivity : ComponentActivity() {
    private var state by mutableStateOf(Settings.State())
    private var live by mutableStateOf(JoyConBridge.Snapshot())
    /** Key code waiting for an action, or "learning" while the user presses a button. */
    private var pickedKey by mutableStateOf<Int?>(null)
    private var learning by mutableStateOf(false)
    private var receiver: android.content.BroadcastReceiver? = null
    /** Re-read on resume: the user comes back from accessibility settings. */
    private var interceptEnabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = Settings.load(this)
        setContent { PhoneXRTheme { Screen() } }
    }

    override fun onResume() {
        super.onResume()
        // The camera Joy-Con screen may have changed settings.
        state = Settings.load(this)
        interceptEnabled = JoyConInputService.isEnabled(this)
    }

    override fun onStart() {
        super.onStart()
        receiver = JoyConBridge.listen(this) { snapshot ->
            live = snapshot
            val learned = snapshot.learnedKey
            if (learning && learned != null) {
                learning = false
                pickedKey = learned
                JoyConBridge.watch(this, watching = true, learning = false)
            }
        }
        JoyConBridge.watch(this, watching = true, learning = false)
    }

    override fun onStop() {
        super.onStop()
        JoyConBridge.watch(this, watching = false, learning = false)
        receiver?.let { unregisterReceiver(it) }
        receiver = null
    }

    private fun update(next: Settings.State) {
        state = next
        Settings.save(this, next)
    }

    @Composable
    private fun Screen() {
        HigPage(title = "Управление", onBack = ::finish) {
            HigSection(
                title = "Отслеживание",
                footer = "3DoF работает на любом телефоне. 6DoF добавляет перемещение в комнате через ARCore."
            ) {
                HigChoice("3DoF", "Поворот головы без перемещения", !state.sixDof) {
                    update(state.copy(sixDof = false))
                }
                if (BuildConfig.LITE) HigRow("6DoF", "Только в PhoneXR Full", detailColor = HigColors.secondary)
                else HigChoice("6DoF", "Поворот и перемещение через ARCore", state.sixDof) {
                    update(state.copy(sixDof = true))
                }
            }

            HigSection(title = "Руки") {
                HigChoice(
                    "Жесты нажимают",
                    "Щипок и кулак работают как кнопки контроллера",
                    state.handMode == Settings.HandMode.CONTROLLERS
                ) { update(state.copy(handMode = Settings.HandMode.CONTROLLERS)) }
                HigChoice(
                    "Только руки",
                    "Игра получает руки без нажатий",
                    state.handMode == Settings.HandMode.HANDS
                ) { update(state.copy(handMode = Settings.HandMode.HANDS)) }
            }

            HigSection(
                title = "Joy‑Con",
                footer = if (interceptEnabled) "Нажмите кнопку на Joy‑Con — она подсветится на схеме. " +
                    "Нажмите на кнопку на схеме, чтобы назначить ей действие."
                else "Без перехвата кнопки Joy‑Con уходят игре как геймпад, а не как контроллеры VR. " +
                    "Включите «PhoneXR Joy‑Con» в «Специальных возможностях»."
            ) {
                HigRow(
                    "Перехват кнопок",
                    if (interceptEnabled) "Включён" else "Выключен",
                    detailColor = if (interceptEnabled) HigColors.good else HigColors.bad
                )
                if (!interceptEnabled) {
                    HigLink("Включить перехват") { startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) }
                }
            }
            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                JoyConDiagram(state, live.left, live.right) { pickedKey = it }
            }

            HigSection(
                title = "Поворот и положение Joy‑Con",
                footer = "Если гироскоп Joy‑Con недоступен, камера может сама находить Joy‑Con по цвету."
            ) {
                val gyro = gyroStatus()
                HigRow("Гироскоп Joy‑Con", gyro.first, detailColor = gyro.second)
                HigLink("Проверить гироскоп") {
                    startActivity(Intent(this@SettingsActivity, GyroTestActivity::class.java))
                }
                HigLink("Joy‑Con через камеру", value = if (state.cameraJoyCons) "Вкл." else "Выкл.") {
                    startActivity(Intent(this@SettingsActivity, JoyConCameraActivity::class.java))
                }
            }

            if (!BuildConfig.LITE) HigSection(
                title = "Метки на Joy‑Con",
                footer = "Самый точный режим: камера видит напечатанные метки ArUco и даёт положение и полный поворот. " +
                    "Распечатайте markers/joycon_markers_A4.pdf в масштабе 100%. ID 0–3 — левый Joy‑Con, 4–7 — правый: " +
                    "слева, середина (сторона с кнопками), справа, сверху. Ровно держите Joy‑Con кнопками к себе, " +
                    "верхом вверх — это «вперёд». После включения остановите и снова запустите трекинг."
            ) {
                HigSwitchRow("Отслеживать по меткам", state.markerJoyCons) { update(state.copy(markerJoyCons = it)) }
            }

            HigSection(
                title = "Контроллеры",
                footer = "PhoneXR принимает любые геймпады: Joy‑Con, DualShock, Xbox и безымянные. " +
                    "Один геймпад работает за две руки: левый стик и кнопки X/Y/L — левая рука, правый стик и A/B/R — правая."
            ) {
                val found = JoyConButtons.names()
                if (found.isEmpty()) HigRow("Ничего не подключено", "Подключите геймпад по Bluetooth", detailColor = HigColors.secondary)
                else found.forEach { name -> HigRow(name, "Подключён", detailColor = HigColors.good) }
            }

            HigSection(
                title = "Сейчас",
                footer = if (!JoyConInputService.sticksSupported) "Стик читается только на Android 14 и новее." else null
            ) {
                HigRow("Левый стик", stick(live.left))
                HigRow("Правый стик", stick(live.right))
                HigRow("Левая рука", pressed(live.left.buttons))
                HigRow("Правая рука", pressed(live.right.buttons))
            }

            HigSection {
                HigLink("Назначить нажатием кнопки") {
                    learning = true
                    JoyConBridge.watch(this@SettingsActivity, watching = true, learning = true)
                }
                HigLink("Сбросить раскладку") { update(state.copy(bindings = Settings.defaults().bindings)) }
            }
        }

        if (learning) LearningDialog()
        pickedKey?.let { ActionDialog(it) }
    }

    /** Joy-Con rotation needs a gyroscope, and not every Android kernel exposes one. */
    @Composable
    private fun gyroStatus(): Pair<String, androidx.compose.ui.graphics.Color> {
        val secondary = HigColors.secondary
        if (android.os.Build.VERSION.SDK_INT < 31) return "Нужен Android 12 или новее" to HigColors.bad
        val devices = android.view.InputDevice.getDeviceIds().toList()
            .mapNotNull { id -> android.view.InputDevice.getDevice(id) }
            .filter { device -> JoyConButtons.isJoyCon(device) }
        if (devices.isEmpty()) return "Joy‑Con не найдены — подключите их по Bluetooth" to secondary
        val withGyro = devices.count { device ->
            device.sensorManager.getSensorList(android.hardware.Sensor.TYPE_ALL).any { sensor ->
                sensor.type == android.hardware.Sensor.TYPE_GYROSCOPE ||
                    sensor.type == android.hardware.Sensor.TYPE_GAME_ROTATION_VECTOR ||
                    sensor.type == android.hardware.Sensor.TYPE_ROTATION_VECTOR
            }
        }
        return if (withGyro > 0) "Есть у $withGyro из ${devices.size}: поворот руки работает без камеры" to HigColors.good
        else "Недоступны — поворот руки берётся только с камеры" to HigColors.bad
    }

    private fun stick(live: JoyConButtons.Live) =
        "вперёд ${(live.stickY * 100).roundToInt()}%, вбок ${(live.stickX * 100).roundToInt()}%"

    private fun pressed(mask: Int) =
        Settings.Action.entries.filter { it.bit != 0 && mask and it.bit != 0 }
            .joinToString(", ") { it.title }
            .ifEmpty { "ничего не нажато" }

    @Composable
    private fun LearningDialog() {
        val stop = {
            learning = false
            JoyConBridge.watch(this, watching = true, learning = false)
        }
        HigAlert(
            title = "Нажмите кнопку на Joy‑Con",
            message = "PhoneXR ждёт нажатия. Дальше выберите, что эта кнопка делает в VR.",
            actions = listOf(HigAction(tr("Отмена"), HigActionStyle.CANCEL, stop)),
            onDismiss = stop
        )
    }

    @Composable
    private fun ActionDialog(keyCode: Int) {
        val actions = Settings.Action.entries.map { action ->
            HigAction(action.title + if (state.bindings[keyCode] == action) " ✓" else "") {
                update(state.copy(bindings = state.bindings + (keyCode to action)))
                pickedKey = null
            }
        } + HigAction(tr("Отмена"), HigActionStyle.CANCEL) { pickedKey = null }
        HigAlert(
            title = "Кнопка ${Settings.keyName(keyCode)}",
            message = "Что она делает в VR:",
            actions = actions,
            onDismiss = { pickedKey = null }
        )
    }
}
