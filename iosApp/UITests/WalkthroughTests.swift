// iosApp/UITests/WalkthroughTests.swift
//
// Walks the app in the simulator as a person would, no account signed in: what launching it and looking
// (tools/ios/simulator-check.sh) cannot tell. Every step leaves a screenshot and what the system's
// accessibility tree held, in the folder CHECK_DIR names, since a failure here is read from CI.

import XCTest

final class WalkthroughTests: XCTestCase {
    private let app = XCUIApplication()

    override func setUp() {
        continueAfterFailure = true
        addUIInterruptionMonitor(withDescription: "System Dialogs") { alert in
            if alert.buttons["Allow While Using App"].exists {
                alert.buttons["Allow While Using App"].tap()
                return true
            }
            if alert.buttons["OK"].exists {
                alert.buttons["OK"].tap()
                return true
            }
            if alert.buttons["Allow"].exists {
                alert.buttons["Allow"].tap()
                return true
            }
            return false
        }
    }

    func testWalkthrough() {
        app.launch()
        home()
        travel()
        dj()
        search()
        link()
        localMusic()
        settings()
        ownServer()
    }

    private func home() {
        step("home", shows: "Connect Spotify", timeout: 60)
    }

    /// 1. Travel: map, Core Location, destination search and OSRM route ETA card.
    private func travel() {
        guard tap("Open menu") else { return }
        guard tap("Travel playlist") else { return }
        acceptPermissionIfPresent()
        step("travel-map", shows: "Search destination or place", timeout: 20)

        guard tap("Search destination or place") else { return }
        app.typeText("Santiago de Compostela\n")

        let suggestion = element("Santiago de Compostela")
        XCTContext.runActivity(named: "Travel Destination and Route") { activity in
            if suggestion.waitForExistence(timeout: 15) {
                suggestion.tap()
                let eta = element("ETA")
                if eta.waitForExistence(timeout: 20) {
                    record("travel-route")
                } else {
                    activity.add(XCTAttachment(string: "OSRM routing did not return an ETA card within 20s (possible runner network limit)"))
                    record("travel-route-timeout")
                }
            } else {
                activity.add(XCTAttachment(string: "Nominatim/Photon autocomplete returned no suggestions for Santiago de Compostela (possible runner network limit)"))
                record("travel-search-timeout")
            }
        }
        guard back() else { return }
    }

    /// 3. DJ: verify the DJ screen opens and the microphone button is present.
    private func dj() {
        guard tap("Open menu") else { return }
        guard tap("DJ") else { return }
        acceptPermissionIfPresent()
        step("dj", shows: "Talk to the DJ", timeout: 20)
        guard back() else { return }
    }

    private func search() {
        guard tap("Search") else { return }
        step("search", shows: "What do you want to listen to?")
        guard tap("What do you want to listen to?") else { return }
        app.typeText("Around the World Daft Punk\n")
        step("search-results", shows: "Around the World", timeout: 30)
        guard tap("Around the World") else { return }
        // What it plays is read in app.log and on these: a runner's address may be refused the audio.
        sleep(20)
        record("search-playing")
    }

    /// iOS asks before a link opens an app, even the one in front. Nothing else here can answer it.
    private func link() {
        guard #available(iOS 16.4, *) else { return }
        XCUIDevice.shared.system.open(URL(string: "rustify://track/4uLU6hMCjMI75M1A2tKUQC")!)
        let open = XCUIApplication(bundleIdentifier: "com.apple.springboard").buttons["Open"]
        if open.waitForExistence(timeout: 10) { open.tap() }
        sleep(15)
        record("link")
        // iOS has no back button of its own: a screen with no arrow of its own, an error's too, is a trap.
        _ = back()
        // In the simulator a tap sometimes goes unanswered (seen here and on Search's result).
        if !element("Library").waitForExistence(timeout: 5) { _ = back() }
        record("link-back")
    }

    /// The file simulator-check.sh left in the app's Documents, which local music holds by default.
    private func localMusic() {
        guard tap("Library") else { return }
        record("library")
        guard tap("Local Music") else { return }
        step("local-music", shows: "Walkthrough Ñandú", timeout: 30)
        guard tap("Walkthrough Ñandú") else { return }
        // The mini player's button says what pressing it would do: Pause is the engine playing.
        step("local-playing", shows: "Pause", timeout: 20)
        sleep(8)
        record("local-after-8s")
        guard tap("Pause") else { return }
        gone("local-paused", "Pause")
        // Resume playing so the engine's tap continues to stream audio buffers for Audio Lab
        let play = app.buttons.matching(NSPredicate(format: "label == %@", "Play")).firstMatch
        if play.waitForExistence(timeout: 5) {
            play.tap()
            step("local-resumed", shows: "Pause", timeout: 10)
        }
    }

    private func settings() {
        guard tap("Home"), tap("Open menu"), tap("Settings") else { return }
        step("settings", shows: "Advanced")
        guard tap("General") else { return }
        step("settings-general", shows: "Cache and Storage")
        // The engine has no audio cache here, so nothing offers to clear one; the images are real.
        XCTAssertTrue(element("Clear images").exists, "the images can be cleared")
        XCTAssertFalse(element("Clear audio").exists, "no audio cache to clear on iOS")
        guard back(), tap("Advanced") else { return }
        step("settings-advanced", shows: "Diagnostics")
        audioLab()
        guard tap("View logs") else { return }
        // The button is gone with the screen it was on; what the viewer holds is read on the screenshot.
        gone("logs", "View logs")
    }

    /// 2. Audio Lab: with local track playing, verify it does not show "play something".
    private func audioLab() {
        guard tap("Audio lab") else { return }
        sleep(4)
        XCTAssertFalse(element("Play something to see it here.").exists, "Audio lab should receive audio from the running engine")
        record("audio-lab")
        guard back() else { return }
    }

    /// Sets up the server own-server.py is, as a person would, then plays from it and downloads from it.
    /// That the bytes came from there is for simulator-check.sh to read, in the server's log.
    private func ownServer() {
        guard back(), back(), tap("Audio & backends") else { return }
        guard fill("Server address", "http://127.0.0.1:8765"), fill("User", "walkthrough") else { return }
        guard scrollTo(element("Save and test")) else { return }
        element("Save and test").tap()
        step("own-server", shows: "Connected: walkthrough 1", timeout: 20)

        guard back(), back(), tap("Home"), tap("Search") else { return }
        if !element("Around the World").waitForExistence(timeout: 5) {
            guard tap("What do you want to listen to?") else { return }
            app.typeText("Around the World Daft Punk\n")
        }
        // Coming back to Search, the row is there before the screen takes touches: a tap then does nothing.
        sleep(3)
        let row = app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@", "Around the World")).firstMatch
        for i in 0..<4 where !element("Pause").waitForExistence(timeout: 5) {
            if i > 0 { record("own-server-tap-again") }
            row.tap()
        }
        step("own-server-playing", shows: "Pause", timeout: 30)
        // Long enough for the engine to want its second range.
        sleep(25)
        record("own-server-after-25s")
        guard tap("Pause") else { return }
        gone("own-server-paused", "Pause")
        sleep(2)

        // The menu first, tapped again when the simulator drops the tap (seen in CI: the results
        // scrolled under scrollTo with no menu open). Then further down it than the screen reaches.
        guard tap("More options") else { return }
        if !element("Add to queue").waitForExistence(timeout: 5) {
            record("menu-tap-again")
            guard tap("More options") else { return }
        }
        guard scrollTo(element("Download track")) else { return }
        element("Download track").tap()
        step("own-server-downloaded", shows: "Track downloaded", timeout: 60)
    }

    /// Dismisses any system permission alerts (Location, Microphone, Speech) via SpringBoard.
    private func acceptPermissionIfPresent() {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let allowWhileUsing = springboard.buttons["Allow While Using App"]
        if allowWhileUsing.waitForExistence(timeout: 5) {
            allowWhileUsing.tap()
            return
        }
        let allow = springboard.buttons["Allow"]
        if allow.waitForExistence(timeout: 2) {
            allow.tap()
            return
        }
        let ok = springboard.buttons["OK"]
        if ok.waitForExistence(timeout: 2) {
            ok.tap()
        }
    }

    /// The screen's arrow. By its whole label: the log viewer's lines say "playback" and "AudioBackend".
    private func back() -> Bool {
        let arrow = app.buttons.matching(NSPredicate(format: "label == %@", "Back")).firstMatch
        guard arrow.waitForExistence(timeout: 20) else {
            XCTFail("no arrow back")
            record("missing-back")
            return false
        }
        arrow.tap()
        return true
    }

    /// The text field labelled exactly [label], further down a screen of many.
    private func fill(_ label: String, _ text: String) -> Bool {
        let field = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@ OR label BEGINSWITH %@", label, label + " ")).firstMatch
        guard scrollTo(field) else { return false }
        // A tap the simulator drops leaves no keyboard, and typing then fails the whole run (seen in
        // CI on "Server address"): tap again until the keyboard is up.
        field.tap()
        for _ in 0..<2 where !app.keyboards.firstMatch.waitForExistence(timeout: 3) {
            field.tap()
        }
        guard app.keyboards.count > 0 else {
            XCTFail("no keyboard for \(label)")
            record("no-keyboard-\(label)")
            return false
        }
        app.typeText(text + "\n")
        sleep(1)
        record("filled-\(label)")
        return true
    }

    private func scrollTo(_ target: XCUIElement) -> Bool {
        _ = target.waitForExistence(timeout: 5)
        for _ in 0..<40 where !(target.exists && target.isHittable) {
            app.swipeUp()
        }
        guard target.exists && target.isHittable else {
            XCTFail("could not reach \(target)")
            record("unreachable")
            return false
        }
        return true
    }

    private func element(_ text: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS[c] %@", text)).firstMatch
    }

    private func tap(_ text: String) -> Bool {
        let target = element(text)
        guard target.waitForExistence(timeout: 20) else {
            XCTFail("nothing reads \"\(text)\"")
            record("missing-\(text)")
            return false
        }
        target.tap()
        return true
    }

    private func step(_ name: String, shows text: String, timeout: TimeInterval = 20) {
        let found = element(text).waitForExistence(timeout: timeout)
        record(name)
        XCTAssertTrue(found, "\(name): nothing reads \"\(text)\"")
    }

    /// For what the matching cannot tell by presence: "Play" is in "Playlists", "Logs" in "View logs".
    private func gone(_ name: String, _ text: String, timeout: TimeInterval = 10) {
        let left = expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: element(text))
        let result = XCTWaiter.wait(for: [left], timeout: timeout)
        record(name)
        XCTAssertEqual(result, .completed, "\(name): \"\(text)\" is still there")
    }

    private var count = 0

    private func record(_ name: String) {
        count += 1
        let screenshot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        guard let dir = ProcessInfo.processInfo.environment["CHECK_DIR"] else { return }
        let base = URL(fileURLWithPath: dir).appendingPathComponent(String(format: "walk-%02d-%@", count, name))
        try? screenshot.pngRepresentation.write(to: base.appendingPathExtension("png"))
        try? app.debugDescription.write(to: base.appendingPathExtension("txt"), atomically: true, encoding: .utf8)
    }
}
