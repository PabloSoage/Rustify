// iosApp/Sources/AudioSession.swift
//
// The audio session iOS asks of a music player: the playback category (plays with the silent switch
// on and in the background, with UIBackgroundModes = audio in project.yml), active when playing,
// and the two things the system does to it from outside — interruptions (a call, an alarm, Siri)
// and route changes (headphones unplugged). The core's engine plays through CoreAudio and knows
// nothing of either; this tells the player.

import AVFoundation
import RustifyShared

/// Used from the main queue only: every observer is registered on it, and IosController's player
/// must be called from it.
final class AudioSession {
    private let controller: IosController
    private var observers: [NSObjectProtocol] = []
    /// Whether the interruption in progress stopped music that was playing; only then does its end
    /// bring the music back.
    private var resumeAfterInterruption = false

    init(controller: IosController) {
        self.controller = controller
        configure()
        let center = NotificationCenter.default
        let session = AVAudioSession.sharedInstance()
        // queue: .main — the system may post these on a thread of its own.
        observers = [
            center.addObserver(forName: AVAudioSession.interruptionNotification, object: session, queue: .main) {
                [weak self] note in self?.interrupted(note)
            },
            center.addObserver(forName: AVAudioSession.routeChangeNotification, object: session, queue: .main) {
                [weak self] note in self?.routeChanged(note)
            },
            // The media server restarted: every session setting is gone and has to be set again.
            center.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification, object: session, queue: .main) {
                [weak self] _ in self?.configure()
            },
        ]
    }

    deinit {
        for observer in observers { NotificationCenter.default.removeObserver(observer) }
    }

    private func configure() {
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
        } catch {
            print("W/AudioSession: category: \(error)")
        }
    }

    /// Before anything starts to play: an inactive session is what silences a background app.
    /// Doing it again while active is harmless.
    func activate() {
        do {
            try AVAudioSession.sharedInstance().setActive(true)
        } catch {
            print("W/AudioSession: activate: \(error)")
        }
    }

    /// Play, with the session active first — for every "play" that is not the core's own.
    func play() {
        activate()
        controller.play()
    }

    private func interrupted(_ note: Notification) {
        guard let info = note.userInfo,
              let rawType = info[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: rawType)
        else { return }
        switch type {
        case .began:
            // The system has already silenced the output; this makes the player agree.
            resumeAfterInterruption = controller.isPlaying
            controller.pause()
        case .ended:
            let rawOptions = info[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
            let shouldResume = AVAudioSession.InterruptionOptions(rawValue: rawOptions).contains(.shouldResume)
            if resumeAfterInterruption && shouldResume { play() }
            resumeAfterInterruption = false
        @unknown default:
            break
        }
    }

    /// The output that was playing went away — headphones unplugged, a Bluetooth headset off or out
    /// of range. Pause, as Android does when audio becomes noisy, rather than carry on out loud.
    private func routeChanged(_ note: Notification) {
        guard let raw = note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
              AVAudioSession.RouteChangeReason(rawValue: raw) == .oldDeviceUnavailable
        else { return }
        controller.pause()
    }
}
