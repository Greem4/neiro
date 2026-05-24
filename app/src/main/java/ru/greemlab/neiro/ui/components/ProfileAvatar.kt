package ru.greemlab.neiro.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent

/**
 * Аватар пользователя YClients в круге; при отсутствии URL или ошибке загрузки — [NeiroLogo].
 */
@Composable
fun ProfileAvatar(
    avatarUrl: String?,
    size: Dp = 64.dp,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    val url = avatarUrl?.trim()?.takeIf { it.isNotBlank() }
    if (url == null) {
        NeiroLogo(size = size, modifier = modifier)
        return
    }

    SubcomposeAsyncImage(
        model = url,
        contentDescription = contentDescription,
        modifier = modifier
            .size(size)
            .clip(CircleShape),
        contentScale = ContentScale.Crop,
    ) {
        when (painter.state) {
            is AsyncImagePainter.State.Success -> SubcomposeAsyncImageContent()
            else -> NeiroLogo(size = size)
        }
    }
}
