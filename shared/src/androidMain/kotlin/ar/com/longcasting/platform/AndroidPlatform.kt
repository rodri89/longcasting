package ar.com.longcasting.platform

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.FileProvider
import ar.com.longcasting.Feedback
import ar.com.longcasting.FileExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AndroidFileExporter(private val context: Context) : FileExporter {

    override suspend fun share(fileName: String, mimeType: String, content: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val directory = File(context.cacheDir, "export").apply { mkdirs() }
                val file = File(directory, fileName)
                file.writeText(content)

                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(intent, "Exportar tiros")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
                true
            }.getOrDefault(false)
        }
}

/**
 * Vibracion y tono al cerrar una ocupacion. Vale la pena el detalle: cuando estas agachado
 * sobre la plomada con el telefono apoyado en el piso no vas a ver la pantalla.
 */
class AndroidFeedback(private val context: Context) : Feedback {

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager =
                context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    override fun success() {
        vibrate(longArrayOf(0, 60, 80, 60))
        tone(ToneGenerator.TONE_PROP_ACK)
    }

    override fun warning() {
        vibrate(longArrayOf(0, 250))
        tone(ToneGenerator.TONE_PROP_NACK)
    }

    private fun vibrate(pattern: LongArray) {
        val device = vibrator ?: return
        if (!device.hasVibrator()) return
        runCatching {
            device.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    }

    private fun tone(type: Int) {
        runCatching {
            val generator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
            generator.startTone(type, 300)
            // El generador retiene un recurso de audio; se libera despues del tono.
            android.os.Handler(context.mainLooper).postDelayed({ generator.release() }, 500)
        }
    }
}
