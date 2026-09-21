package com.samrat.cardboardhands

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import zone.ien.hig.CupertinoText
import zone.ien.hig.theme.CupertinoTheme

class AboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PhoneXRTheme { About() } }
    }

    @Composable
    private fun About() {
        HigPage(title = "О приложении", onBack = ::finish) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppIcon()
                CupertinoText("PhoneXR", style = CupertinoTheme.typography.title1)
                CupertinoText(
                    "Версия ${BuildConfig.VERSION_NAME}",
                    color = CupertinoTheme.colorScheme.secondaryLabel
                )
                CupertinoText(
                    "VR на обычном телефоне: OpenXR через Monado, трекинг рук камерой, Joy‑Con вместо контроллеров " +
                        "и переходник для игр Gear VR.",
                    style = CupertinoTheme.typography.subhead,
                    textAlign = TextAlign.Center,
                    color = CupertinoTheme.colorScheme.secondaryLabel
                )
            }
            HigSection(
                title = "Благодарности",
                footer = "Комната кинотеатра: «minecraft vr Living Room» от Piethekiddev (Sketchfab), лицензия CC BY 4.0."
            ) {
                HigLink("Модель комнаты на Sketchfab") {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://sketchfab.com/3d-models/minecraft-vr-living-room-decef3993905402b8237708ae5af0704")
                        )
                    )
                }
            }
            HigSection(title = "Команда", footer = "Made with ❤️") {
                Person("Разработчик", "@Beketov_samrat")
                Person("Тестировщик", "@livebradar")
                Person("Дизайнер", "@Freddytech87")
            }
        }
    }

    /** A person from the team: the role and their Telegram, which opens on a tap. */
    @Composable
    private fun HigScope.Person(role: String, telegram: String) {
        HigLink(role, value = telegram) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/${telegram.removePrefix("@")}")))
        }
    }

    /**
     * The launcher icon is an adaptive icon (XML), which Compose painterResource cannot load —
     * that threw as soon as this screen opened. The system Drawable draws it on any Android version.
     */
    @Composable
    private fun AppIcon() {
        val icon = remember { packageManager.getApplicationIcon(packageName) }
        Canvas(
            modifier = Modifier
                .size(112.dp)
                .clip(RoundedCornerShape(26.dp))
        ) {
            drawIntoCanvas { canvas ->
                icon.setBounds(0, 0, size.width.toInt(), size.height.toInt())
                icon.draw(canvas.nativeCanvas)
            }
        }
    }
}
