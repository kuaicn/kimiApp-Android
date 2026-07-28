package com.kimi.app.ui.common

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * 服务器图片（/api/v1/files/{id}）。
 * 认证由应用级 ImageLoader 的共享 OkHttp（带 Bearer 拦截器）处理，调用方无需关心 token。
 */
@Composable
fun AuthImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    AsyncImage(
        model = url,
        imageLoader = com.kimi.app.ui.LocalKimiImageLoader.current
            ?: coil3.SingletonImageLoader.get(androidx.compose.ui.platform.LocalContext.current),
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .widthIn(max = 260.dp)
            .heightIn(max = 320.dp)
            .clip(RoundedCornerShape(12.dp)),
    )
}
