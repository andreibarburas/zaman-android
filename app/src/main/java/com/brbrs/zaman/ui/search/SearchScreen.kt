package com.brbrs.zaman.ui.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.brbrs.zaman.data.local.EventEntity
import com.brbrs.zaman.ui.theme.GlassCard
import com.brbrs.zaman.ui.theme.SourceLogo
import com.brbrs.zaman.ui.theme.effectiveSourceBadge
import com.brbrs.zaman.ui.theme.ZamanType
import com.brbrs.zaman.ui.theme.inkMuted
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenEvent: (String) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsState()
    val results by viewModel.results.collectAsState()
    val focusRequester = remember { FocusRequester() }

    Scaffold { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = viewModel::setQuery,
                    placeholder = { Text("Search events…") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setQuery("") }) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear")
                            }
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f).focusRequester(focusRequester),
                )
            }

            LaunchedEffect(Unit) { focusRequester.requestFocus() }

            when {
                query.isBlank() -> HintState()
                results.isEmpty() -> EmptyResultsState(query)
                else -> LazyColumn(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(results, key = { it.href }) { event ->
                        ResultRow(event, onClick = if (event.sourceApp == null) ({ onOpenEvent(event.href) }) else null)
                    }
                }
            }
        }
    }
}

@Composable
private fun HintState() {
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        Text(
            "Search across every enabled calendar, including Koto tasks and Tavla cards.",
            style = ZamanType.bodyMedium,
            color = inkMuted(),
        )
    }
}

@Composable
private fun EmptyResultsState(query: String) {
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        Text("No events match \"$query\"", style = ZamanType.bodyMedium, color = inkMuted())
    }
}

@Composable
private fun ResultRow(event: EventEntity, onClick: (() -> Unit)?) {
    val dateLabel = DateTimeFormatter.ofPattern("MMM d, HH:mm").format(
        Instant.ofEpochMilli(event.startEpochMillis).atZone(ZoneId.systemDefault())
    )
    val cardModifier = if (onClick != null) Modifier.fillMaxWidth().clickable(onClick = onClick) else Modifier.fillMaxWidth()

    GlassCard(modifier = cardModifier) {
        Column(Modifier.padding(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                event.effectiveSourceBadge()?.let {
                    SourceLogo(it)
                    Spacer(Modifier.width(6.dp))
                }
                Text(event.summary, style = ZamanType.titleMedium, modifier = Modifier.weight(1f, fill = false))
            }
            Text(dateLabel, style = ZamanType.bodySmall, color = inkMuted(), modifier = Modifier.padding(top = 2.dp))
        }
    }
}
