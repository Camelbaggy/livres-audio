package io.github.camelbaggy.livresaudio.core.playback

import androidx.media3.common.Player

/** Pure mapping of a Media3 `Player.STATE_*` to the keeper's state. */
fun keeperStateOf(playbackState: Int): KeeperPlayerState = when (playbackState) {
    Player.STATE_BUFFERING -> KeeperPlayerState.BUFFERING
    Player.STATE_READY -> KeeperPlayerState.READY
    Player.STATE_ENDED -> KeeperPlayerState.ENDED
    else -> KeeperPlayerState.IDLE
}

/** Only a deliberate move (user seek, chapter skip) counts as a [DiscontinuityKind.SEEK]. */
fun discontinuityKindOf(reason: Int): DiscontinuityKind =
    if (reason == Player.DISCONTINUITY_REASON_SEEK) DiscontinuityKind.SEEK else DiscontinuityKind.OTHER

/** What the keeper reads from the player. Call on the player's thread only. */
fun snapshotOf(player: Player): PlayerSnapshot = PlayerSnapshot(
    mediaId = player.currentMediaItem?.mediaId,
    mediaItemIndex = player.currentMediaItemIndex,
    itemCount = player.mediaItemCount,
    positionMs = player.currentPosition,
    state = keeperStateOf(player.playbackState),
    isPlaying = player.isPlaying
)
