// iosApp/Sources/NowPlaying.swift
//
// The lock screen and Control Center: what is playing (MPNowPlayingInfoCenter) and the buttons that
// act on it (MPRemoteCommandCenter), the same buttons headphones and CarPlay send. Driven by the
// PlayerUi that IosController.observe hands the app; the buttons go to the same IosController.

import MediaPlayer
import RustifyShared
import UIKit

/// Used from the main queue only, like IosController.
final class NowPlaying {
    private let controller: IosController
    private let audio: AudioSession
    private var targets: [(MPRemoteCommand, Any)] = []

    /// What the lock screen shows now, to publish only when that would change. It moves the elapsed
    /// time on by itself at the rate it was given, so a position that keeps pace is not news.
    private struct Shown {
        let key: String
        let rate: Double
        let durationMs: Int64
        let positionMs: Int64
        let at: Date
    }

    private var shown: Shown?
    private var latest: PlayerUi?
    private var artworkKey: String?
    private var artwork: MPMediaItemArtwork?

    init(controller: IosController, audio: AudioSession) {
        self.controller = controller
        self.audio = audio
        let center = MPRemoteCommandCenter.shared()
        handle(center.playCommand) { $0.audio.play() }
        handle(center.pauseCommand) { $0.controller.pause() }
        handle(center.togglePlayPauseCommand) {
            if $0.controller.isPlaying { $0.controller.pause() } else { $0.audio.play() }
        }
        handle(center.nextTrackCommand) { $0.controller.next() }
        handle(center.previousTrackCommand) { $0.controller.previous() }
        let seek = center.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let self, let event = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            let ms = Int64(event.positionTime * 1000)
            NowPlaying.onMain { self.controller.seek(positionMs: ms) }
            return .success
        }
        targets.append((center.changePlaybackPositionCommand, seek))
    }

    deinit {
        for (command, target) in targets { command.removeTarget(target) }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }

    private func handle(_ command: MPRemoteCommand, _ action: @escaping (NowPlaying) -> Void) {
        let target = command.addTarget { [weak self] _ in
            guard let self else { return .commandFailed }
            NowPlaying.onMain { action(self) }
            return .success
        }
        targets.append((command, target))
    }

    /// The player is only ever called from the main queue; the command center does not promise it.
    private static func onMain(_ work: @escaping () -> Void) {
        if Thread.isMainThread { work() } else { DispatchQueue.main.async(execute: work) }
    }

    /// Every PlayerUi the controller observes.
    func update(_ ui: PlayerUi?) {
        latest = ui
        guard let ui, let track = ui.current else {
            if shown != nil {
                MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
                shown = nil
                artworkKey = nil
                artwork = nil
            }
            return
        }
        // By place in the queue as well as id: the same song twice in a row is two entries.
        let key = "\(ui.index):\(track.id ?? track.name)"
        if key != artworkKey {
            artworkKey = key
            artwork = nil
            loadArtwork(track, key: key)
        }
        let rate: Double = ui.playing && !ui.loading ? 1 : 0
        let durationMs = ui.durationMs?.int64Value ?? Int64(track.durationMs)
        let now = Date()
        if let shown, shown.key == key, shown.rate == rate, shown.durationMs == durationMs {
            let expected = shown.positionMs + Int64(now.timeIntervalSince(shown.at) * 1000 * rate)
            // A seek, or the engine running behind or ahead of the lock screen's clock.
            if abs(expected - ui.positionMs) < 1500 { return }
        }
        shown = Shown(key: key, rate: rate, durationMs: durationMs, positionMs: ui.positionMs, at: now)

        var info: [String: Any] = [
            MPMediaItemPropertyTitle: track.name,
            MPMediaItemPropertyArtist: track.artists.map { $0.name }.joined(separator: ", "),
            MPNowPlayingInfoPropertyElapsedPlaybackTime: Double(ui.positionMs) / 1000,
            MPNowPlayingInfoPropertyPlaybackRate: rate,
            MPNowPlayingInfoPropertyDefaultPlaybackRate: 1.0,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
        ]
        if let album = track.album?.name, !album.isEmpty { info[MPMediaItemPropertyAlbumTitle] = album }
        if durationMs > 0 { info[MPMediaItemPropertyPlaybackDuration] = Double(durationMs) / 1000 }
        if let artwork { info[MPMediaItemPropertyArtwork] = artwork }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    /// The cover from the track's own URL (Spotify's album art, or YouTube's thumbnail); shown when
    /// it arrives, if the track is still the one playing.
    private func loadArtwork(_ track: FullTrack, key: String) {
        // Spotify lists the largest first; a YouTube Music track has the one.
        guard let address = track.album?.images.first?.url, let url = URL(string: address) else { return }
        URLSession.shared.dataTask(with: url) { [weak self] data, _, _ in
            guard let data, let image = UIImage(data: data) else { return }
            DispatchQueue.main.async {
                guard let self, self.artworkKey == key else { return }
                self.artwork = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
                // Published again in full, from the latest state: the elapsed time is "now".
                self.shown = nil
                self.update(self.latest)
            }
        }.resume()
    }
}
