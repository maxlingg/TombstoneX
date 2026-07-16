package com.tombstonex.ui.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * 将任意 [Drawable] 转换为 Compose 可渲染的 [ImageBitmap]。
 *
 * 不引入 Coil 等第三方图片库，使用 Android 原生方式：
 * - 若是 [BitmapDrawable] 且内部 [BitmapDrawable.bitmap] 非空，直接复用；
 * - 否则用 [Canvas] 在目标尺寸的空白 [Bitmap] 上重新绘制。
 *
 * @param size 目标边长（px），用于统一应用图标尺寸
 */
fun Drawable.toImageBitmap(size: Int = 96): ImageBitmap {
    if (this is BitmapDrawable && bitmap != null) {
        return bitmap.asImageBitmap()
    }
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    setBounds(0, 0, size, size)
    draw(canvas)
    return bmp.asImageBitmap()
}
