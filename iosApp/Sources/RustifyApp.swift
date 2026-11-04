// iosApp/Sources/RustifyApp.swift
//
// The iOS shell: SwiftUI over IosController (shared/src/iosMain/.../ios/IosController.kt), which is
// the same QueuePlayer the desktop runs, over the same core through its C ABI. Audio plays through
// the core's engine (cpal → CoreAudio); this file only sets up the audio session iOS requires.

import AVFoundation
import RustifyShared
import SwiftUI

@main
struct RustifyApp: App {
    @StateObject private var model = PlayerModel()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(model)
                .preferredColorScheme(.dark)
        }
    }
}

/// Bridges IosController's callbacks to SwiftUI's observation.
@MainActor
final class PlayerModel: ObservableObject {
    let controller = IosController(secrets: KeychainStore())
    @Published var ui: PlayerUi?
    @Published var results: [FullTrack] = []
    @Published var user: SpotifyUser?
    @Published var searching = false
    @Published var searchError: String?
    private var observation: Kotlinx_coroutines_coreJob?

    init() {
        configureAudioSession()
        let fm = FileManager.default
        let data = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let cache = fm.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        try? fm.createDirectory(at: data, withIntermediateDirectories: true)
        try? fm.createDirectory(at: cache, withIntermediateDirectories: true)
        controller.start(dataDir: data.path, cacheDir: cache.path)
        observation = controller.observe { [weak self] ui in
            self?.ui = ui
        }
        controller.restoreSpotify { [weak self] user in
            self?.user = user
        }
    }

    /// Playback category: plays with the silent switch on and keeps going in the background
    /// (with UIBackgroundModes = audio in Info.plist).
    private func configureAudioSession() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .default)
        try? session.setActive(true)
    }

    func search(_ query: String) {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return }
        searching = true
        searchError = nil
        controller.search(query: q, onResult: { [weak self] tracks in
            self?.results = tracks
            self?.searching = false
        }, onError: { [weak self] message in
            self?.searchError = message
            self?.searching = false
        })
    }

    func play(at index: Int) {
        controller.playAll(tracks: results, start: Int32(index))
    }

    func signIn(_ cookie: String) {
        controller.signInSpotify(spDc: cookie, onUser: { [weak self] user in
            self?.user = user
        }, onError: { [weak self] message in
            self?.searchError = message
        })
    }

    func loadLiked() {
        searching = true
        controller.likedSongs(onResult: { [weak self] tracks in
            self?.results = tracks
            self?.searching = false
        }, onError: { [weak self] message in
            self?.searchError = message
            self?.searching = false
        })
    }
}

struct ContentView: View {
    @EnvironmentObject var model: PlayerModel
    @State private var query = ""
    @State private var showSignIn = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                List {
                    if model.searching {
                        ProgressView()
                    } else if let error = model.searchError {
                        Text(error).foregroundStyle(.secondary)
                    }
                    ForEach(Array(model.results.enumerated()), id: \.offset) { index, track in
                        Button {
                            model.play(at: index)
                        } label: {
                            VStack(alignment: .leading) {
                                Text(track.name).lineLimit(1)
                                Text(track.artists.map { $0.name }.joined(separator: ", "))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                            }
                        }
                    }
                }
                .listStyle(.plain)
                NowPlayingBar()
            }
            .navigationTitle("Rustify")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    if model.user != nil {
                        Button("Liked") { model.loadLiked() }
                    } else {
                        Button("Spotify") { showSignIn = true }
                    }
                }
            }
            .sheet(isPresented: $showSignIn) {
                SignInView { cookie in
                    model.signIn(cookie)
                    showSignIn = false
                }
            }
            .searchable(text: $query, prompt: "Search YouTube Music")
            .onSubmit(of: .search) { model.search(query) }
        }
    }
}

struct NowPlayingBar: View {
    @EnvironmentObject var model: PlayerModel

    var body: some View {
        let ui = model.ui
        VStack(spacing: 6) {
            HStack {
                VStack(alignment: .leading) {
                    Text(ui?.current?.name ?? "Not playing").lineLimit(1)
                    Text(ui?.error ?? (ui?.current?.artists.map { $0.name }.joined(separator: ", ") ?? ""))
                        .font(.caption)
                        .foregroundStyle(ui?.error != nil ? .red : .secondary)
                        .lineLimit(1)
                }
                Spacer()
                Button { model.controller.previous() } label: { Image(systemName: "backward.fill") }
                Button { model.controller.togglePause() } label: {
                    Image(systemName: (ui?.playing ?? false) ? "pause.fill" : "play.fill")
                }
                .font(.title2)
                Button { model.controller.next() } label: { Image(systemName: "forward.fill") }
            }
            if let ui, let duration = ui.durationMs?.int64Value, duration > 0 {
                ProgressView(value: Double(ui.positionMs), total: Double(duration))
            }
        }
        .padding()
        .background(.thinMaterial)
    }
}

/// The sp_dc cookie from a signed-in open.spotify.com session, as the phone asks for it.
struct SignInView: View {
    let onSubmit: (String) -> Void
    @State private var cookie = ""

    var body: some View {
        NavigationStack {
            Form {
                Section(footer: Text("Open open.spotify.com signed in, copy the value of the sp_dc cookie and paste it here. It stays on this phone.")) {
                    SecureField("sp_dc", text: $cookie)
                }
                Button("Sign in") { onSubmit(cookie) }
                    .disabled(cookie.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            .navigationTitle("Spotify")
        }
    }
}
