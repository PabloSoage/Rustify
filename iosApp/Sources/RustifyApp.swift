// iosApp/Sources/RustifyApp.swift
//
// The iOS shell: SwiftUI over IosController (shared/src/iosMain/.../ios/IosController.kt), which is
// the same QueuePlayer the desktop runs, over the same core through its C ABI. Audio plays through
// the core's engine (cpal → CoreAudio); the audio session iOS requires is AudioSession.swift.

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
    private var audio: AudioSession?
    private var nowPlaying: NowPlaying?

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
            // Whatever started it (the core moving on, a restored queue), playing needs the session.
            if ui.playing && self.ui?.playing != true { self.audio?.activate() }
            self.ui = ui
            self.nowPlaying?.update(ui)
        }
        controller.restoreSpotify { [weak self] user in
            self?.user = user
        }
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
        audio?.activate()
        controller.playAll(tracks: results, start: Int32(index))
    }

    func togglePause() {
        if controller.isPlaying { controller.pause() } else { audio?.play() }
    }

    func signIn(_ cookie: String) {
        controller.signInSpotify(spDc: cookie, onUser: { [weak self] user in
            self?.user = user
        }, onError: { [weak self] message in
            self?.searchError = message
        })
    }

    /// Forgets the session, here and in the Keychain; the next sign-in asks for the cookie again.
    func signOut() {
        controller.signOutSpotify()
        user = nil
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
                ToolbarItem(placement: .navigationBarLeading) {
                    if let user = model.user {
                        Menu {
                            Text(user.name ?? user.id)
                            Button(role: .destructive) { model.signOut() } label: {
                                Label("Sign out of Spotify", systemImage: "rectangle.portrait.and.arrow.right")
                            }
                        } label: {
                            Image(systemName: "person.crop.circle")
                        }
                    }
                }
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
                Button { model.togglePause() } label: {
                    Image(systemName: (ui?.playing ?? false) ? "pause.fill" : "play.fill")
                }
                .font(.title2)
                Button { model.controller.next() } label: { Image(systemName: "forward.fill") }
            }
            if let ui, let duration = ui.durationMs?.int64Value, duration > 0 {
                SeekBar(positionMs: ui.positionMs, durationMs: duration) { model.controller.seek(positionMs: $0) }
            }
        }
        .padding()
        .background(.thinMaterial)
    }
}

/// Where the song is, and where to take it: drag and let go to seek.
struct SeekBar: View {
    let positionMs: Int64
    let durationMs: Int64
    let onSeek: (Int64) -> Void
    /// Where the thumb is while a finger holds it; the player's position does not move it then.
    @State private var dragging: Double?
    /// Where it was let go, shown until the player's position gets there, so the thumb does not jump
    /// back for the moment the seek takes.
    @State private var pending: (ms: Double, at: Date)?

    var body: some View {
        let total = Double(durationMs)
        let shown = min(dragging ?? pending?.ms ?? Double(positionMs), total)
        VStack(spacing: 2) {
            Slider(
                value: Binding(get: { shown }, set: { dragging = $0 }),
                in: 0...total,
                onEditingChanged: { editing in
                    guard !editing, let target = dragging else { return }
                    dragging = nil
                    pending = (target, Date())
                    onSeek(Int64(target))
                }
            )
            HStack {
                Text(Self.time(shown))
                Spacer()
                Text(Self.time(total))
            }
            .font(.caption2.monospacedDigit())
            .foregroundStyle(.secondary)
        }
        .onChange(of: positionMs) { position in
            // There, or given up on: a seek that did not happen must not hold the thumb for good.
            guard let p = pending else { return }
            if abs(Double(position) - p.ms) < 2000 || Date().timeIntervalSince(p.at) > 3 { pending = nil }
        }
    }

    private static func time(_ ms: Double) -> String {
        let seconds = Int(ms / 1000)
        return String(format: "%d:%02d", seconds / 60, seconds % 60)
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
