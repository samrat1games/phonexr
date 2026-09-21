package com.samrat.cardboardhands

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import zone.ien.hig.theme.CupertinoColors
import zone.ien.hig.theme.CupertinoTheme
import zone.ien.hig.theme.systemGreen
import zone.ien.hig.theme.systemRed
import kotlin.math.min
import kotlin.math.roundToInt

/** Checks the Joy-Con motion sensors: whether Android exposes them and whether data really arrives. */
class GyroTestActivity : ComponentActivity() {
    private var tracker: JoyConTracker? = null
    private var left by mutableStateOf(JoyConTracker.Pose())
    private var right by mutableStateOf(JoyConTracker.Pose())
    private var leftMotion by mutableStateOf(JoyConTracker.Motion())
    private var rightMotion by mutableStateOf(JoyConTracker.Motion())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PhoneXRTheme { Screen() } }
    }

    override fun onStart() {
        super.onStart()
        tracker = JoyConTracker(this)
    }

    override fun onStop() {
        super.onStop()
        tracker?.close()
        tracker = null
    }

    @Composable
    private fun Screen() {
        LaunchedEffect(Unit) {
            while (true) {
                withFrameNanos { }
                tracker?.let {
                    left = it.pose(left = true)
                    right = it.pose(left = false)
                    leftMotion = it.motion(left = true)
                    rightMotion = it.motion(left = false)
                }
            }
        }
        HigPage(
            title = "Гироскоп Joy‑Con",
            onBack = ::finish,
            subtitle = "Поверните Joy‑Con — куб повторит поворот, а скорость вращения покажет, что данные идут."
        ) {
            if (Build.VERSION.SDK_INT < 31) {
                HigSection(footer = "Датчики Joy‑Con Android отдаёт приложениям только с Android 12.") {
                    HigRow("Нужен Android 12 или новее", detailColor = HigColors.bad)
                }
                return@HigPage
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Cube(left, leftMotion, Modifier.weight(1f))
                Cube(right, rightMotion, Modifier.weight(1f))
            }
            JoyConSection("Левый Joy‑Con", leftMotion)
            JoyConSection("Правый Joy‑Con", rightMotion)
            HigButton("Выровнять", filled = false) { tracker?.recenter() }
            HigSection(
                footer = "Держите оба Joy‑Con прямо и нажмите «Выровнять». Если датчики не найдены, ядро телефона " +
                    "не отдаёт гироскоп Joy‑Con (так бывает, например, на Samsung с ядром 5.10) — " +
                    "тогда поворот руки берётся только с камеры."
            ) {}
        }
    }

    @Composable
    private fun JoyConSection(title: String, motion: JoyConTracker.Motion) {
        val verdict = verdict(motion)
        HigSection(title = title) {
            HigRow("Состояние", verdict.first, detailColor = verdict.second)
            HigRow("Датчики", motion.sensors.ifEmpty { listOf("нет") }.joinToString(", "))
            HigRow("Частота", "${motion.rateHz.roundToInt()} Гц · событий ${motion.events}")
            HigRow("Скорость вращения", "${motion.degreesPerSecond.roundToInt()}°/с")
        }
    }

    @Composable
    private fun verdict(motion: JoyConTracker.Motion): Pair<String, Color> = when {
        !motion.connected -> "Не подключён — подключите по Bluetooth" to HigColors.secondary
        motion.sensors.none { it == "гироскоп" || it == "ориентация" } ->
            "Подключён, но гироскопа нет" to HigColors.bad
        motion.rateHz < 1f -> "Гироскоп есть, но данные не приходят" to HigColors.bad
        else -> "Гироскоп работает" to HigColors.good
    }

    @Composable
    private fun Cube(pose: JoyConTracker.Pose, motion: JoyConTracker.Motion, modifier: Modifier) {
        val working = motion.rateHz >= 1f
        val color = if (working) HigColors.accent else CupertinoTheme.colorScheme.tertiaryLabel
        val accent = if (working) HigColors.good else CupertinoTheme.colorScheme.quaternaryLabel
        Canvas(modifier = modifier.aspectRatio(1f)) {
            val scale = min(size.width, size.height) * .22f
            val center = Offset(size.width / 2f, size.height / 2f)
            val projected = corners.map { corner ->
                val (x, y, z) = rotate(pose, corner[0], corner[1], corner[2] * .55f)
                // Simple perspective: points further away are drawn closer to the centre.
                val depth = 4.5f / (4.5f - z)
                Offset(center.x + x * scale * depth, center.y - y * scale * depth)
            }
            edges.forEach { edge ->
                drawLine(
                    color = if (edge in front) accent else color,
                    start = projected[edge.first],
                    end = projected[edge.second],
                    strokeWidth = if (edge in front) 6f else 4f
                )
            }
            drawCircle(color.copy(alpha = .15f), radius = scale * 2.2f, center = center, style = Stroke(2f))
        }
    }
}

private val corners = listOf(
    floatArrayOf(-1f, -1f, -1f), floatArrayOf(1f, -1f, -1f), floatArrayOf(1f, 1f, -1f), floatArrayOf(-1f, 1f, -1f),
    floatArrayOf(-1f, -1f, 1f), floatArrayOf(1f, -1f, 1f), floatArrayOf(1f, 1f, 1f), floatArrayOf(-1f, 1f, 1f)
)
private val edges = listOf(0 to 1, 1 to 2, 2 to 3, 3 to 0, 4 to 5, 5 to 6, 6 to 7, 7 to 4, 0 to 4, 1 to 5, 2 to 6, 3 to 7)
/** The front face is drawn in the accent colour so the direction the Joy-Con points is visible. */
private val front = setOf(4 to 5, 5 to 6, 6 to 7, 7 to 4)

/** Rotates a point by the Joy-Con quaternion. */
private fun rotate(pose: JoyConTracker.Pose, px: Float, py: Float, pz: Float): Triple<Float, Float, Float> {
    val (qx, qy, qz, qw) = listOf(pose.x, pose.y, pose.z, pose.w)
    val tx = 2f * (qy * pz - qz * py)
    val ty = 2f * (qz * px - qx * pz)
    val tz = 2f * (qx * py - qy * px)
    return Triple(
        px + qw * tx + (qy * tz - qz * ty),
        py + qw * ty + (qz * tx - qx * tz),
        pz + qw * tz + (qx * ty - qy * tx)
    )
}
