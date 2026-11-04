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
    }

    func testWalkthrough() {
        app.launch()
        home()
        search()
        link()
        localMusic()
    }

    private func home() {
        step("home", shows: "Connect Spotify", timeout: 60)
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
    }

    /// The file simulator-check.sh left in the app's Documents, which local music holds by default.
    private func localMusic() {
        guard tap("Library") else { return }
        record("library")
        guard tap("Local Music") else { return }
        step("local-music", shows: "Walkthrough Ñandú", timeout: 30)
        guard tap("Walkthrough Ñandú") else { return }
        sleep(8)
        record("local-playing")
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
