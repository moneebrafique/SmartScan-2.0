package com.paypeico.chat.ui

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paypeico.chat.data.ChatRepo
import com.paypeico.chat.data.Config
import com.paypeico.chat.data.Me
import com.paypeico.chat.data.Msg
import com.paypeico.chat.data.Participant
import com.paypeico.chat.data.TimeFmt
import com.paypeico.chat.data.typeLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the conversation list shows: date separators and messages. */
private sealed class ChatRow {
    abstract val key: Any

    data class Day(val label: String, val day: String) : ChatRow() {
        override val key: Any get() = "day_$day"
    }

    data class Message(val m: Msg, val mine: Boolean, val grouped: Boolean) : ChatRow() {
        override val key: Any get() = m.id
    }
}

private fun buildRows(messages: List<Msg>, isMine: (Msg) -> Boolean): List<ChatRow> {
    val rows = ArrayList<ChatRow>(messages.size + 8)
    var lastDay: String? = null
    var prev: Msg? = null
    for (m in messages) {
        val day = TimeFmt.dayKey(m.time)
        val newDay = day != lastDay
        if (newDay) {
            rows.add(ChatRow.Day(TimeFmt.dayLabel(m.time), day))
            lastDay = day
        }
        val grouped = !newDay && prev != null &&
            prev.senderId == m.senderId && prev.senderType == m.senderType
        rows.add(ChatRow.Message(m, isMine(m), grouped))
        prev = m
    }
    return rows
}

@Composable
fun ChatScreen(me: Me, other: Participant, onBack: () -> Unit) {
    val messages = remember(other) { mutableStateListOf<Msg>() }
    var lastId by remember(other) { mutableStateOf(0L) }
    var loaded by remember(other) { mutableStateOf(false) }
    var error by remember(other) { mutableStateOf<String?>(null) }
    var scrollTick by remember(other) { mutableStateOf(0) }
    var input by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val fetchLock = remember { Mutex() }
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current

    fun isMine(m: Msg) = m.senderId == me.id && m.senderType == me.type

    // Fetch only messages newer than the last one we have.
    // NOTE: this never scrolls itself. Scrolling waits for the list to be on
    // screen, so it is done in a separate effect (below) to avoid getting stuck.
    suspend fun refresh() {
        fetchLock.withLock {
            val fresh = ChatRepo.messages(me, other, lastId).filter { it.id > lastId }
            if (fresh.isNotEmpty()) {
                val wasAtBottom = !listState.canScrollForward
                val firstLoad = messages.isEmpty()
                messages.addAll(fresh)
                lastId = fresh.last().id
                if (firstLoad || wasAtBottom || isMine(fresh.last())) {
                    scrollTick++
                }
                if (fresh.any { !isMine(it) }) {
                    ChatRepo.markRead(me, other)
                }
            }
        }
    }

    LaunchedEffect(other) {
        while (true) {
            try {
                refresh()
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Connection problem, retrying…"
            }
            loaded = true
            delay(Config.MESSAGE_POLL_MS)
        }
    }

    val rows = remember(messages.size) { buildRows(messages.toList(), ::isMine) }

    // Jump to the newest message once the list is actually on screen.
    LaunchedEffect(scrollTick) {
        if (scrollTick > 0 && rows.isNotEmpty()) {
            listState.scrollToItem(rows.lastIndex)
        }
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || sending) return
        sending = true
        scope.launch {
            var sent = false
            try {
                ChatRepo.send(me, other, text)
                sent = true
                input = ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(ctx, "Message not sent. Check your connection and try again.", Toast.LENGTH_LONG).show()
            } finally {
                sending = false
            }
            if (sent) {
                try {
                    refresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The regular refresh will pick it up.
                }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                        Avatar(other.name, other.id, size = 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                other.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                typeLabel(other.type),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    TextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message") },
                        maxLines = 5,
                        shape = RoundedCornerShape(26.dp),
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(
                        onClick = { send() },
                        enabled = input.isNotBlank() && !sending,
                        shape = CircleShape,
                        modifier = Modifier
                            .padding(bottom = 2.dp)
                            .size(52.dp),
                    ) {
                        if (sending) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                        }
                    }
                }
            }
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize(),
        ) {
            val err = error
            if (err != null) ErrorBar(err)

            when {
                !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                rows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Avatar(other.name, other.id, size = 72.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "No messages yet",
                            modifier = Modifier.padding(top = 12.dp),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Say hello to ${other.name} 👋",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    items(rows, key = { it.key }) { row ->
                        when (row) {
                            is ChatRow.Day -> DaySeparator(row.label)
                            is ChatRow.Message -> Bubble(
                                m = row.m,
                                mine = row.mine,
                                grouped = row.grouped,
                                onCopy = {
                                    clipboard.setText(AnnotatedString(row.m.text))
                                    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DaySeparator(label: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(m: Msg, mine: Boolean, grouped: Boolean, onCopy: () -> Unit) {
    val bg = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val fg = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val big = 18.dp
    val small = 6.dp
    val shape = if (mine) {
        RoundedCornerShape(topStart = big, topEnd = if (grouped) small else big, bottomStart = big, bottomEnd = small)
    } else {
        RoundedCornerShape(topStart = if (grouped) small else big, topEnd = big, bottomStart = small, bottomEnd = big)
    }
    val maxWidth = (LocalConfiguration.current.screenWidthDp * 0.78f).dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (grouped) 2.dp else 8.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = shape,
            color = bg,
            shadowElevation = if (mine) 0.dp else 1.dp,
            modifier = Modifier.widthIn(max = maxWidth),
        ) {
            Column(
                modifier = Modifier
                    .combinedClickable(onClick = {}, onLongClick = onCopy)
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
            ) {
                Text(m.text, color = fg, style = MaterialTheme.typography.bodyLarge)
                Text(
                    TimeFmt.clock(m.time),
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = fg.copy(alpha = 0.65f),
                )
            }
        }
    }
}
