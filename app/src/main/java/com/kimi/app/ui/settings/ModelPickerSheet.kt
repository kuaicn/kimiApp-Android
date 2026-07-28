package com.kimi.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kimi.app.data.wire.WireModel
import com.kimi.app.ui.AppViewModel

/** 模型选择器：按 provider 分组，收藏置顶，点击切换当前会话模型 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    viewModel: AppViewModel,
    currentModel: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val favorites by viewModel.favoriteModels.collectAsState()

    // provider 分组，收藏置顶（model 字段本身即完整模型 id，如 kimi-code/k3）
    val grouped = state.models
        .sortedWith(
            compareByDescending<WireModel> { it.model in favorites }
                .thenBy { it.provider }
                .thenBy { it.model },
        )

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            Text("选择模型", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            LazyColumn {
                var lastProvider: String? = null
                for (model in grouped) {
                    val key = model.model
                    if (model.provider != lastProvider) {
                        lastProvider = model.provider
                        item(key = "p_${model.provider}") {
                            Text(
                                model.provider,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                            )
                        }
                    }
                    item(key = key) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(key); onDismiss() }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    model.display_name ?: model.model,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val meta = buildString {
                                    append(model.model)
                                    if (model.max_context_size > 0) {
                                        append(" · ${model.max_context_size / 1024}k 上下文")
                                    }
                                }
                                Text(
                                    meta,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { viewModel.toggleFavoriteModel(key) }) {
                                Icon(
                                    if (key in favorites) Icons.Filled.Star else Icons.Outlined.Star,
                                    contentDescription = "收藏",
                                    tint = if (key in favorites) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            if (key == currentModel) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "当前",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        HorizontalDivider(thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}
