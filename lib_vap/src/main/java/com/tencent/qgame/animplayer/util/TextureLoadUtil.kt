/*
 * Tencent is pleased to support the open source community by making vap available.
 *
 * Copyright (C) 2020 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * Licensed under the MIT License (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/MIT
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tencent.qgame.animplayer.util

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils

object TextureLoadUtil {
    private const val TAG = "TextureUtil"

    // glGetIntegerv查询失败(上下文异常)时的保守纹理单边上限
    private const val FALLBACK_MAX_TEXTURE_SIZE = 2048

    fun loadTexture(bitmap: Bitmap?): Int {
        val textureObjectIds = IntArray(1)
        GLES20.glGenTextures(1, textureObjectIds, 0)

        if (textureObjectIds[0] == 0) {
            return 0
        }

        if (bitmap == null) {
            GLES20.glDeleteTextures(1, textureObjectIds, 0)
            return 0
        }

        if (bitmap.isRecycled) {
            ALog.e(TAG, "bitmap isRecycled")
            return 0
        }

        // 上传前统一转换成驱动可安全处理的位图, 否则会在glTexImage2D内native崩溃:
        // 1. 超过GL_MAX_TEXTURE_SIZE的位图, 部分GPU驱动(如Mali)直接崩溃而非返回错误;
        // 2. 奇数宽RGB_565位图, 行stride=width*2不满足GL默认GL_UNPACK_ALIGNMENT=4的对齐
        //    (GLUtils不设置该值), 部分驱动按对齐stride整块memcpy像素会越过缓冲区末尾(SEGV_ACCERR)。
        val uploadBitmap = createUploadBitmap(bitmap, queryMaxTextureSize())
        if (uploadBitmap == null) {
            ALog.e(TAG, "create upload bitmap fail, drop texture ${bitmap.width}x${bitmap.height}")
            GLES20.glDeleteTextures(1, textureObjectIds, 0)
            return 0
        }

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureObjectIds[0])
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR_MIPMAP_LINEAR
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        // 行对齐放宽到1字节, 保证任意位图的行stride都合法; 上传后恢复GL默认值, 不影响同上下文的其它上传
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, uploadBitmap, 0)
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        // 临时副本仅用于本次上传, 立即回收; 原bitmap归属调用方, 不在此处处理
        if (uploadBitmap !== bitmap) {
            uploadBitmap.recycle()
        }

        return textureObjectIds[0]
    }

    private fun queryMaxTextureSize(): Int {
        val max = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, max, 0)
        return if (max[0] > 0) max[0] else FALLBACK_MAX_TEXTURE_SIZE
    }

    /**
     * 转换为可安全上传的位图, 无需转换时返回原bitmap同一实例
     */
    private fun createUploadBitmap(bitmap: Bitmap, limit: Int): Bitmap? {
        return try {
            var result = bitmap
            if (result.width > limit || result.height > limit) {
                val scale = limit.toFloat() / maxOf(result.width, result.height)
                result = Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceIn(1, limit),
                    (bitmap.height * scale).toInt().coerceIn(1, limit),
                    true
                )
                ALog.e(
                    TAG,
                    "bitmap ${bitmap.width}x${bitmap.height} exceed texture limit=$limit, scaled to ${result.width}x${result.height}"
                )
            }
            if (result.config == Bitmap.Config.RGB_565 && result.width % 2 != 0) {
                // 补1像素宽成偶数宽, 行stride恢复4字节对齐(687->688级别的差异视觉不可感知)
                val padded =
                    Bitmap.createScaledBitmap(result, result.width + 1, result.height, false)
                if (result !== bitmap) {
                    result.recycle()
                }
                result = padded
                ALog.e(
                    TAG,
                    "odd width RGB_565 bitmap ${bitmap.width}x${bitmap.height}, padded to ${result.width}x${result.height}"
                )
            }
            result
        } catch (e: OutOfMemoryError) {
            ALog.e(TAG, "create upload bitmap OOM", e)
            null
        } catch (e: Exception) {
            ALog.e(TAG, "create upload bitmap failed", e)
            null
        }
    }


    fun releaseTexture(textureId: Int) {
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        }
    }
}