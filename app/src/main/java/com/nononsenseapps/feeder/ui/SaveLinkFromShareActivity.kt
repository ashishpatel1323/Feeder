package com.nononsenseapps.feeder.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.background.runOnceSyncChainSendRead
import com.nononsenseapps.feeder.base.DIAwareComponentActivity
import com.nononsenseapps.feeder.sharedlinks.SaveResult
import com.nononsenseapps.feeder.sharedlinks.SharedLinks
import com.nononsenseapps.feeder.sharedlinks.siteOf
import com.nononsenseapps.feeder.sync2.Sync2Client
import com.nononsenseapps.feeder.ui.compose.utils.withAllProviders
import com.nononsenseapps.feeder.util.DEEP_LINK_BASE_URI
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.kodein.di.instance

/**
 * The share sheet's "Save to Feeder": saves any shared web page or YouTube video to Saved and
 * shows a small sheet with Read now, Find feed and Undo. It closes by itself after a few seconds.
 */
class SaveLinkFromShareActivity : DIAwareComponentActivity() {
    private val sync2Client: Sync2Client by instance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shared = (intent?.getStringExtra(Intent.EXTRA_TEXT) ?: intent?.dataString)?.trim()
        val subject = intent?.getStringExtra(Intent.EXTRA_SUBJECT)

        setContent {
            withAllProviders {
                var result by remember { mutableStateOf<SaveResult?>(null) }
                var touched by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                LaunchedEffect(Unit) {
                    result = SharedLinks(di).save(listOfNotNull(shared, subject).firstOrNull { it.contains("http") || it.contains('.') } ?: shared)
                    runOnceSyncChainSendRead(di)
                }
                LaunchedEffect(result, touched) {
                    if (result != null && !touched) {
                        delay(if (result is SaveResult.Saved) 4000 else 2500)
                        finish()
                    }
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { finish() },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Surface(
                        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
                        tonalElevation = 3.dp,
                        shadowElevation = 12.dp,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                // Taps on the sheet keep it open
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { touched = true },
                    ) {
                        SheetContent(
                            result = result,
                            onReadNow = { id ->
                                startActivity(
                                    Intent(Intent.ACTION_VIEW, "$DEEP_LINK_BASE_URI/article/$id".toUri(), this@SaveLinkFromShareActivity, MainActivity::class.java)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                                finish()
                            },
                            onFindFeed = { link ->
                                startActivity(Intent(this@SaveLinkFromShareActivity, AddFeedFromShareActivity::class.java).putExtra(Intent.EXTRA_TEXT, link))
                                finish()
                            },
                            onUndo = { id ->
                                scope.launch {
                                    sync2Client.hideItems(listOf(id))
                                    runOnceSyncChainSendRead(di)
                                    finish()
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetContent(
    result: SaveResult?,
    onReadNow: (Long) -> Unit,
    onFindFeed: (String) -> Unit,
    onUndo: (Long) -> Unit,
) {
    Column(
        Modifier
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 20.dp),
    ) {
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .size(width = 36.dp, height = 4.dp)
                .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.height(16.dp))
        when (result) {
            null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.saving_link), style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(12.dp))
            }

            is SaveResult.NotALink -> {
                Text(stringResource(R.string.no_link_shared), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
            }

            is SaveResult.Saved -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(if (result.existed) R.string.already_in_saved else R.string.added_to_saved),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(siteOf(result.link), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    result.info.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.shared_link_full_article),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                // Every action is a visible button
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { onReadNow(result.itemId) }, modifier = Modifier.weight(1f).height(56.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.read_now), maxLines = 1, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    OutlinedButton(onClick = { onFindFeed(result.link) }, modifier = Modifier.weight(1f).height(56.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.RssFeed, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.find_feed), maxLines = 1, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    if (!result.existed) {
                        OutlinedButton(onClick = { onUndo(result.itemId) }, modifier = Modifier.weight(1f).height(56.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text(stringResource(R.string.undo), maxLines = 1, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}
