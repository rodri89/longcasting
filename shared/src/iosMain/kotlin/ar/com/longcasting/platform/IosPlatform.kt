package ar.com.longcasting.platform

import ar.com.longcasting.Feedback
import ar.com.longcasting.FileExporter
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AudioToolbox.AudioServicesPlaySystemSound
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.writeToFile
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UINotificationFeedbackGenerator
import platform.UIKit.UINotificationFeedbackType
import platform.UIKit.UIWindowScene

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosFileExporter : FileExporter {

    override suspend fun share(fileName: String, mimeType: String, content: String): Boolean {
        // En Kotlin/Native un String **no** es un NSString: castearlo compila con warning y
        // revienta en runtime. Hay que construir el NSString explicitamente.
        val directory = NSTemporaryDirectory()
        val path = if (directory.endsWith("/")) "$directory$fileName" else "$directory/$fileName"

        val written = NSString.create(string = content).writeToFile(
            path = path,
            atomically = true,
            encoding = NSUTF8StringEncoding,
            error = null,
        )
        if (!written) return false

        val root = (UIApplication.sharedApplication.connectedScenes.firstOrNull() as? UIWindowScene)
            ?.windows
            ?.filterIsInstance<platform.UIKit.UIWindow>()
            ?.firstOrNull { it.isKeyWindow() }
            ?.rootViewController
            ?: return false

        val controller = UIActivityViewController(
            activityItems = listOf(NSURL.fileURLWithPath(path)),
            applicationActivities = null,
        )
        root.presentViewController(controller, animated = true, completion = null)
        return true
    }
}

class IosFeedback : Feedback {

    override fun success() {
        UIImpactFeedbackGenerator(style = UIImpactFeedbackStyle.UIImpactFeedbackStyleHeavy).impactOccurred()
        // 1057 es el tono corto de confirmacion del sistema.
        AudioServicesPlaySystemSound(1057u)
    }

    override fun warning() {
        UINotificationFeedbackGenerator().notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeWarning)
        AudioServicesPlaySystemSound(1053u)
    }
}
