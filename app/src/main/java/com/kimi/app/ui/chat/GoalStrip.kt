package com.kimi.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kimi.app.data.wire.WireGoalSnapshot

/** Goal 进度条（event.goal.updated / GET goal 数据） */
@Composable
fun GoalStrip(goal: WireGoalSnapshot) {
    val budget = goal.budget
    val progress = when {
        budget.turnBudget != null && budget.turnBudget > 0 ->
            (goal.turnsUsed.toFloat() / budget.turnBudget).coerceIn(0f, 1f)

        budget.tokenBudget != null && budget.tokenBudget > 0 ->
            (goal.tokensUsed.toFloat() / budget.tokenBudget).coerceIn(0f, 1f)

        else -> null
    }
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Flag,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    goal.objective,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    when (goal.status) {
                        "active" -> "进行中"
                        "paused" -> "已暂停"
                        "blocked" -> "受阻"
                        "complete" -> "已完成"
                        else -> goal.status
                    } + " · ${goal.turnsUsed} 回合",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            if (progress != null) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                )
            }
        }
    }
}
