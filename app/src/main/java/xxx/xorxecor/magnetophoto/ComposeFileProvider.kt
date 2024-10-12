// File: ComposeFileProvider.kt
package xxx.xorxecor.magnetophoto

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

object ComposeFileProvider {
    fun getImageUri(context: Context): Uri {
        val imagesFolder = File(context.cacheDir, "images")
        if (!imagesFolder.exists()) {
            imagesFolder.mkdirs()
        }
        val imageFile = File(imagesFolder, "captured_image_${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            imageFile
        )
    }

    fun saveBitmapToUri(context: Context, bitmap: Bitmap): Uri {
        val imagesFolder = File(context.cacheDir, "images")
        if (!imagesFolder.exists()) {
            imagesFolder.mkdirs()
        }
        val imageFile = File(imagesFolder, "colorized_image_${System.currentTimeMillis()}.jpg")
        imageFile.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            imageFile
        )
    }
}