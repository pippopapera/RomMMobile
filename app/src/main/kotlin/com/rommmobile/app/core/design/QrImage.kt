package com.rommmobile.app.core.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders [content] as a QR code. Used so the user can point their phone at the handheld and
 * land straight on the approval page, instead of typing an address and a code by hand.
 */
@Composable
fun QrImage(content: String, modifier: Modifier = Modifier, sizePx: Int = 480) {
    val bitmap: ImageBitmap? = remember(content, sizePx) { encodeQr(content, sizePx) }
    if (bitmap == null) return
    Image(
        painter = BitmapPainter(bitmap),
        contentDescription = null,
        modifier = modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).background(Color.White).padding(6.dp),
        contentScale = ContentScale.Fit,
    )
}


private fun encodeQr(content: String, size: Int): ImageBitmap? = runCatching {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 1,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
    val bmp = createBitmap(matrix.width, matrix.height)
    for (x in 0 until matrix.width) {
        for (y in 0 until matrix.height) {
            bmp[x, y] = if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
    }
    bmp.asImageBitmap()
}.getOrNull()
