package com.vibespace.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.vibespace.app.R
import com.vibespace.app.data.api.Post
import com.vibespace.app.ui.common.TimeFormat
import com.vibespace.app.ui.common.ViewCountFormat
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import com.vibespace.app.ui.common.relativeTime
import com.vibespace.app.ui.theme.VibeColors

@Composable
fun Avatar(url: String?, name: String, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val ring = Modifier.size(size).clip(CircleShape).border(1.5.dp, VibeColors.brandGradient, CircleShape)
    if (url != null) {
        AsyncImage(
            model = url,
            contentDescription = stringResource(R.string.avatar),
            contentScale = ContentScale.Crop,
            modifier = modifier.then(ring).background(VibeColors.SurfaceHigh),
        )
    } else {
        Box(modifier.then(ring).background(VibeColors.SurfaceHigh), contentAlignment = Alignment.Center) {
            Text(
                name.trim().firstOrNull()?.uppercase() ?: "V",
                color = VibeColors.NeonCyan,
                fontWeight = FontWeight.Bold,
                style = if (size >= 64.dp) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PostCard(
    post: Post,
    onOpen: () -> Unit,
    onAuthor: () -> Unit,
    onClap: () -> Unit,
    onSave: () -> Unit,
    onComments: () -> Unit,
    onReport: () -> Unit,
    onHashtag: (String) -> Unit,
    modifier: Modifier = Modifier,
    onDelete: (() -> Unit)? = null,
    expanded: Boolean = false,
    /** Fresher count from the view tracker, if any; otherwise the post's own. */
    viewCount: Long = post.viewCount,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(shape)
            .background(VibeColors.Surface)
            .border(1.dp, VibeColors.Outline.copy(alpha = 0.6f), shape)
            .clickable(enabled = !expanded, onClick = onOpen)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clickable(onClick = onAuthor), verticalAlignment = Alignment.CenterVertically) {
                Avatar(post.author.avatarUrl, post.author.displayName)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(post.author.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "@${post.author.username} · ${relativeTime(post.createdAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (onDelete != null && post.viewer?.isAuthor == true) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { menu = false; onDelete() })
                    } else {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_report)) },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_flag), null) },
                            onClick = { menu = false; onReport() },
                        )
                    }
                }
            }
        }
        if (post.caption.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                post.caption,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = if (expanded) Int.MAX_VALUE else 8,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (post.hashtags.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                post.hashtags.forEach { tag ->
                    Text("#$tag", color = VibeColors.NeonCyan, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clickable { onHashtag(tag) })
                }
            }
        }
        post.media.firstOrNull()?.url?.let { url ->
            Spacer(Modifier.height(10.dp))
            val m = post.media.first()
            val ratio = if (m.width != null && m.height != null && m.height > 0) (m.width.toFloat() / m.height).coerceIn(0.6f, 1.9f) else 1f
            AsyncImage(
                model = url,
                contentDescription = stringResource(R.string.post_image),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(ratio).heightIn(max = 520.dp).clip(RoundedCornerShape(14.dp)).background(VibeColors.SurfaceHigh),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val clapped = post.viewer?.hasClapped == true
            ActionChip(
                icon = R.drawable.ic_clap,
                label = TimeFormat.compact(post.counts.vibeClaps),
                active = clapped,
                activeColor = VibeColors.NeonPurple,
                contentDescription = stringResource(R.string.action_clap),
                onClick = onClap,
            )
            ActionChip(
                icon = R.drawable.ic_vibetalk,
                label = TimeFormat.compact(post.counts.vibeTalk),
                active = false,
                activeColor = VibeColors.NeonCyan,
                contentDescription = stringResource(R.string.vibetalk),
                onClick = onComments,
            )
            ViewCountLabel(maxOf(viewCount, post.viewCount))
            Spacer(Modifier.weight(1f))
            val saved = post.viewer?.hasSaved == true
            ActionChip(
                icon = if (saved) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border,
                label = if (post.counts.saves > 0) TimeFormat.compact(post.counts.saves) else "",
                active = saved,
                activeColor = VibeColors.NeonCyan,
                contentDescription = stringResource(if (saved) R.string.action_saved else R.string.action_save),
                onClick = onSave,
            )
        }
    }
}

@Composable
private fun ActionChip(icon: Int, label: String, active: Boolean, activeColor: Color, contentDescription: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(
            painterResource(icon),
            contentDescription = contentDescription,
            tint = if (active) activeColor else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        if (label.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Text(label, color = if (active) activeColor else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Eye icon + "1.2K" / "3.4 लाख": how many people saw the post. Not clickable. */
@Composable
fun ViewCountLabel(count: Long, modifier: Modifier = Modifier) {
    val text = ViewCountFormat.format(
        count,
        ViewCountFormat.systemFor(stringResource(R.string.views_number_system)),
        lakh = stringResource(R.string.views_lakh),
        crore = stringResource(R.string.views_crore),
    )
    val description = pluralStringResource(R.plurals.views_count, count.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), text)
    Row(
        modifier.padding(horizontal = 12.dp, vertical = 8.dp).clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_eye), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
    }
}
