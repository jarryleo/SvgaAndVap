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
package com.tencent.qgame.animplayer.mix

import android.graphics.Bitmap
import com.tencent.qgame.animplayer.PointRect

/**
 * 资源描述
 */
class Resource(src: Src) {
    var id = ""
    var type = Src.SrcType.UNKNOWN
    var loadType = Src.LoadType.UNKNOWN
    var tag = ""
    var bitmap: Bitmap? = null
    var curPoint: PointRect? = null  // src在当前帧的位置信息(仅渲染/触摸阶段填充, fetch阶段为null)

    // src区域尺寸(mp4配置json), 作为动态图解码的目标尺寸兜底:
    // 避免fetch阶段拿不到curPoint时按原图尺寸全量解码, 超大图导致内存暴涨/OOM
    var w = 0
    var h = 0

    init {
        id = src.srcId
        type = src.srcType
        loadType = src.loadType
        tag = src.srcTag
        bitmap = src.bitmap
        w = src.w
        h = src.h
    }
}