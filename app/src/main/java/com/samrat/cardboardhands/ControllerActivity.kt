package com.samrat.cardboardhands

import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * This phone as a pointer for the headset: it says where it points, and its buttons press what it
 * points at. Meant for a second phone lying next to the one in the headset.
 */
class ControllerActivity : ComponentActivity() {
    private var sender: PhoneController.Sender? = null
    private var trigger by mutableStateOf(false)
    private var sent by mutableStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { PhoneXRTheme { Screen() } }
    }

    override fun onResume() {
        super.onResume()
        sender = PhoneController.Sender(this).also { it.start() }
        ticker()
    }

    override fun onPause() {
        super.onPause()
        sender?.stop()
        sender = null
    }

    /** The screen shows that packets really leave the phone, once a second. */
    private fun ticker() {
        window.decorView.postDelayed({
            sender?.let {
                sent = it.sent
                ticker()
            }
        }, 1000)
    }

    private fun press(pressed: Boolean, bit: Int) {
        val current = sender ?: return
        current.buttons = if (pressed) current.buttons or bit else current.buttons and bit.inv()
        if (pressed) buzz()
    }

    private fun buzz() = runCatching {
        getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(12, 120))
    }

    @Composable
    private fun Screen() {
        HigPage(title = "Контроллер", onBack = ::finish, subtitle = "Наведите телефон на экран в шлеме, как пультом.") {
            HigSection(
                title = "Связь",
                footer = "Оба телефона должны быть в одной сети Wi‑Fi. Шлем сам ловит этот телефон — " +
                    "ничего вводить не нужно."
            ) {
                HigRow("Отправлено пакетов", if (sent > 0) "$sent" else "…", detailColor = if (sent > 0) HigColors.good else HigColors.secondary)
            }

            HigSection(
                title = "Кнопки",
                footer = "«Навести на центр» совмещает направление телефона с центром экрана: " +
                    "нажмите её, держа телефон на экран. Курок — нажатие в том месте, куда смотрит телефон."
            ) {
                HigLink("Навести на центр") {
                    press(true, PhoneController.RECENTER)
                    window.decorView.postDelayed({ press(false, PhoneController.RECENTER) }, 200)
                }
                HigLink("Назад") {
                    press(true, PhoneController.BACK)
                    window.decorView.postDelayed({ press(false, PhoneController.BACK) }, 200)
                }
            }

            // The trigger is held, not tapped, so a drag across the screen works.
            Box(
                Modifier
                    .padding(20.dp)
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(if (trigger) HigColors.accent else HigColors.accent.copy(alpha = .25f))
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            trigger = true
                            press(true, PhoneController.TRIGGER)
                            tryAwaitRelease()
                            trigger = false
                            press(false, PhoneController.TRIGGER)
                        })
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    HigText(if (trigger) "Нажато" else "Курок", color = if (trigger) Color.White else HigColors.label)
                    HigText("держите, чтобы вести", color = if (trigger) Color.White else HigColors.secondary)
                }
            }
        }
    }
}
