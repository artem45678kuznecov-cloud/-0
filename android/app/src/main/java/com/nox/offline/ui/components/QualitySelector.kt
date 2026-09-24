package com.nox.offline.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nox.offline.downloader.Quality
import com.nox.offline.ui.glass.GlassStyles
import com.nox.offline.ui.glass.GlassSurface
import com.nox.offline.ui.theme.Nox
import com.nox.offline.ui.theme.nox

private fun Quality.title() = if (this == Quality.MAX) "MAX" else "${key}p"
private fun Quality.caption() = when (this) {
    Quality.Q360 -> "низкое"
    Quality.Q480 -> "среднее"
    Quality.Q720 -> "высокое"
    Quality.MAX -> "максимум"
}

/** Четыре плитки качества: 360p / 480p / 720p / MAX. */
@Composable
fun QualitySelector(selected: Quality, onSelect: (Quality) -> Unit, modifier: Modifier = Modifier) {
    val p = nox()
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (q in Quality.entries) {
            val on = q == selected
            GlassSurface(
                modifier = Modifier.weight(1f).heightIn(min = 70.dp)
                    .semantics { this.selected = on; role = Role.RadioButton },
                shape = RoundedCornerShape(20.dp),
                style = if (on) GlassStyles.Selected else GlassStyles.Chip,
                onClick = { onSelect(q) },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp)) {
                    Text(q.title(), color = Nox.TextPrimary, fontSize = 21.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1)
                    Text(q.caption(), color = if (on) p.accentLight else Nox.TextSecondary, fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
