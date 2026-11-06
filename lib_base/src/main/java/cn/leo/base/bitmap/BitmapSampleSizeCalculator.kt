package cn.leo.base.bitmap

import android.graphics.BitmapFactory

internal object BitmapSampleSizeCalculator {

    fun calculate(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        // Raw height and width of image
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1

        if (reqHeight <= 0 || reqWidth <= 0) return inSampleSize

        if (height > reqHeight || width > reqWidth) {
            // Calculate the largest inSampleSize value that is a power of 2 and keeps both
            // height and width larger than the requested height and width.
            while (height / inSampleSize >= reqHeight && width / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }
}
