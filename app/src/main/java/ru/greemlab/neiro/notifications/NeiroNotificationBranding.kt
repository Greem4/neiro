package ru.greemlab.neiro.notifications

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.drawable.IconCompat
import ru.greemlab.neiro.R

/**
 * Фирменное оформление push-уведомлений: цветной логотип Neiro в шапке (API 24+).
 */
object NeiroNotificationBranding {

    private const val HEADER_ICON_DP = 28

    @Volatile
    private var logoBitmapCache: Bitmap? = null

    fun apply(builder: NotificationCompat.Builder, context: Context): NotificationCompat.Builder {
        val appContext = context.applicationContext
        return builder.setSmallIcon(IconCompat.createWithBitmap(logoBitmap(appContext)))
    }

    fun channelLightColor(context: Context): Int =
        ContextCompat.getColor(context.applicationContext, R.color.notification_accent)

    private fun logoBitmap(context: Context): Bitmap {
        logoBitmapCache?.let { return it }
        val density = context.resources.displayMetrics.density
        val sizePx = (HEADER_ICON_DP * density).toInt().coerceAtLeast(1)
        return vectorToBitmap(context, R.drawable.ic_notification_logo, sizePx).also {
            logoBitmapCache = it
        }
    }

    private fun vectorToBitmap(context: Context, @DrawableRes resId: Int, sizePx: Int): Bitmap {
        val drawable = requireNotNull(ContextCompat.getDrawable(context, resId)) {
            "Drawable $resId not found"
        }.mutate()
        DrawableCompat.setTintList(drawable, null)

        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        return bitmap
    }
}
