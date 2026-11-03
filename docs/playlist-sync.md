# Playlist synchronization

Connect a YouTube Music account, enable playlist synchronization and select **read/write** mode in account sync settings. Create a synced playlist, or use **Send to YouTube Music** on an existing local playlist. Linked playlists queue create, rename, add, remove, reorder and delete operations in the same database transaction as the local edit.

Pending edits survive app restarts. The app retries while it is running and when synchronization resumes. The playlist shows pending changes or a failed synchronization; the sync button reports success only after the complete remote playlist has been read back. Read-only mode, disabled synchronization, no network or a different connected account pauses outgoing edits. The queue stores an account identity hash, never credentials.

Duplicate songs are separate occurrences, identified by YouTube's `setVideoId`. Removing one occurrence preserves the others. Edits made on another device are merged: remote additions survive a local removal, remote ordering is retained unless the local order changed, and a local rename takes precedence when both names changed. Local audio files remain on the device and are not uploaded.

When an add or create request might have reached the server but its response was lost, the app reads the remote playlist or library to recover the result. If the result cannot be identified uniquely, automatic resending pauses. Check YouTube Music before explicitly choosing **Resend request**, since resending an already completed request can create a duplicate. Confirmed additions are recorded before the next request so a later retry does not send them again.

Deleting a linked editable playlist queues deletion in YouTube Music as well. Removing a download only removes the download/cache. Playlists belonging to other people are imported for reading and are not edited. Converting a linked playlist to local removes its queued synchronization.

Validation includes reconciliation permutations, duplicate handling, transactional queue persistence, restart recovery, migration from database version 20, simulated network failures, partial progress, changes during synchronization and account isolation. Real account verification should use a disposable playlist before distributing this feature broadly; tests do not modify a real YouTube Music account.
