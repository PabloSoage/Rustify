// iosApp/Sources/RustifyApp.swift
//
// The iOS shell around the shared interface: the phone's app, the same RustifyApp Android and the
// desktop draw, in a UIViewController from RustifyKit (sharedUi/src/iosMain/.../MainViewController.kt).
// What stays here is what is plain Swift: the audio session iOS requires (AudioSession.swift), the lock
// screen (NowPlaying.swift) and the Keychain (KeychainStore.swift), all over IosController.

import RustifyKit
import SwiftUI

@main
struct RustifyApp: App {
    @StateObject private var model = PlayerModel()

    var body: some Scene {
        WindowGroup {
            SharedInterface(controller: model.controller)
                .ignoresSafeArea(.all)
                .preferredColorScheme(.dark)
                .onOpenURL { IosLinks.shared.offer(link: $0.absoluteString) }
        }
    }
}

/// The Compose interface, as a SwiftUI view.
struct SharedInterface: UIViewControllerRepresentable {
    let controller: IosController

    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(controller: controller)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

/// Starts the core and keeps the session and the lock screen following the player.
@MainActor
final class PlayerModel: ObservableObject {
    let controller = IosController(secrets: KeychainStore())
    private var observation: Kotlinx_coroutines_coreJob?
    private var audio: AudioSession?
    private var nowPlaying: NowPlaying?
    private var wasActive = false

    init() {
        let audio = AudioSession(controller: controller)
        self.audio = audio
        nowPlaying = NowPlaying(controller: controller, audio: audio)
        let fm = FileManager.default
        let data = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let cache = fm.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        try? fm.createDirectory(at: data, withIntermediateDirectories: true)
        try? fm.createDirectory(at: cache, withIntermediateDirectories: true)
        controller.start(dataDir: data.path, cacheDir: cache.path)
        observation = controller.observe { [weak self] ui in
            guard let self else { return }
            // Whatever started it (a song tapped, the core moving on, a restored queue), playing needs
            // the session, before the engine opens its output.
            let active = ui.playing || ui.loading
            if active && !self.wasActive { self.audio?.activate() }
            self.wasActive = active
            self.nowPlaying?.update(ui)
        }
    }
}
