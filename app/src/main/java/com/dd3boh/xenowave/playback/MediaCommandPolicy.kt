package com.dd3boh.xenowave.playback

import androidx.media3.session.SessionCommand
import com.dd3boh.xenowave.constants.MediaSessionConstants

/**
 * Single policy for custom media commands and the local binder.
 *
 * Only the app's own controller is offered commands that [onCustomCommand]
 * will execute. External controllers keep the normal Player/library commands
 * but are never told about actions that would be refused.
 */
object MediaCommandPolicy {
    val customCommands: List<SessionCommand> = listOf(
        MediaSessionConstants.CommandToggleLibrary,
        MediaSessionConstants.CommandToggleLike,
        MediaSessionConstants.CommandToggleStartRadio,
        MediaSessionConstants.CommandToggleShuffle,
        MediaSessionConstants.CommandToggleRepeatMode,
    )

    private val customActions: Set<String> = customCommands
        .map { it.customAction }
        .toSet()

    fun commandsFor(isOwnController: Boolean): List<SessionCommand> =
        if (isOwnController) customCommands else emptyList()

    fun canExecute(isOwnController: Boolean, action: String): Boolean =
        isOwnController && (action in customActions || action == MusicService.COMMAND_GET_BINDER)

    fun canExposeBinder(isOwnController: Boolean): Boolean = isOwnController
}
