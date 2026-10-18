package com.dd3boh.xenowave.ui.dialog

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.dd3boh.xenowave.LocalPlayerConnection
import com.dd3boh.xenowave.R
import com.dd3boh.xenowave.constants.ListThumbnailSize
import com.dd3boh.xenowave.models.MultiQueueObject
import com.dd3boh.xenowave.ui.component.items.ListItem
import com.dd3boh.xenowave.ui.component.items.QueueListItem


@Composable
fun AddToQueueDialog(
    initialTextFieldValue: String? = null,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val playerConnection = LocalPlayerConnection.current
    var queues by remember {
        mutableStateOf(emptyList<MultiQueueObject>())
    }
    var showCreateQueueDialog by rememberSaveable {
        mutableStateOf(false)
    }


    LaunchedEffect(Unit) {
        queues = playerConnection?.service?.queueBoard?.getAllQueues()?.reversed() ?: emptyList()
    }

    ListDialog(
        onDismiss = onDismiss
    ) {
        item {
            ListItem(
                title = stringResource(R.string.create_queue),
                thumbnailContent = {
                    com.dd3boh.xenowave.ui.icons.XenoIcon(
                        icon = com.dd3boh.xenowave.ui.icons.XenoActionIcons.Add,
                        contentDescription = null,
                        tint = (MaterialTheme.colorScheme.onBackground),
                        modifier = Modifier.size(ListThumbnailSize)
                    )
                },
                modifier = Modifier.clickable {
                    showCreateQueueDialog = true
                }
            )
        }

        var index = queues.size
        // add queue
        items(queues) { queue ->
            QueueListItem(
                queue = queue,
                number = index--,
                modifier = Modifier.clickable {
                    onAdd(queue.title)
                    onDismiss()
                }
            )
        }
    }

    if (showCreateQueueDialog) {
        TextFieldDialog(
            icon = { com.dd3boh.xenowave.ui.icons.XenoIcon(icon = com.dd3boh.xenowave.ui.icons.XenoActionIcons.Add, contentDescription = null) },
            title = { Text(text = stringResource(R.string.create_queue)) },
            initialTextFieldValue = TextFieldValue(initialTextFieldValue ?: ""),
            onDismiss = { showCreateQueueDialog = false },
            onDone = { queuetName ->
                onAdd(queuetName)
                onDismiss()
            },
        )
    }
}

@Composable
fun EditQueueDialog(
    queue: MultiQueueObject,
    onDismiss: () -> Unit,
) {
    val playerConnection = LocalPlayerConnection.current

    TextFieldDialog(
        icon = { com.dd3boh.xenowave.ui.icons.XenoIcon(icon = com.dd3boh.xenowave.ui.icons.XenoActionIcons.Edit, contentDescription = null) },
        title = { Text(text = stringResource(R.string.edit_playlist)) },
        onDismiss = onDismiss,
        initialTextFieldValue = TextFieldValue(
            queue.title,
            TextRange(queue.title.length)
        ),
        onDone = { name ->
            onDismiss()
            playerConnection?.service?.queueBoard?.renameQueue(queue, name)
        }
    )
}
