import XCTest

/// Opens the app in the Simulator and keeps a picture of each screen it reaches. CI exports the pictures from the
/// test result, and they are reviewed like the Android emulator shots.
final class BoringTravelTour: XCTestCase {
    override func setUp() {
        continueAfterFailure = true
    }

    func testTour() {
        let app = XCUIApplication()
        // "-tour": the app asks nothing of its own (location, notifications); none of the steps below taps a button
        // that would ask (IosApp.kt).
        app.launchArguments += ["-tour"]
        app.launch()

        // Once the fonts are read: Welcome on a fresh install (shown once), else Trips. Continue (a build without Google's
        // client, as CI's) or "Use without an account" (a build with it, since round 23) goes on to Trips; the tour never
        // taps "Continue with Google" or Sign in with Apple, which would open a sign-in.
        let idea = app.staticTexts.matching(NSPredicate(format: "label CONTAINS[c] %@", "Kyoto & Osaka")).firstMatch
        let welcome = app.descendants(matching: .any).matching(
            NSPredicate(format: "label ==[c] %@ OR label ==[c] %@", "Continue", "Use without an account")
        ).firstMatch
        var waited = 0
        while waited < 60 && !welcome.exists && !idea.exists {
            _ = welcome.waitForExistence(timeout: 1)
            waited += 1
        }
        if welcome.exists {
            snap("00-welcome")
            if welcome.isHittable { welcome.tap() }
            // Since parity round 1 the screens fade: Trips' trip ideas are there under Welcome while it fades out, so
            // the picture of Trips waits for Welcome's button to go (Mac run of 19 Nov 2026: "01-trips" showed Welcome).
            gone("Use without an account", in: app, wait: 10)
            gone("Continue", in: app, wait: 10)
        }

        // Trips, with the trip ideas.
        let loaded = idea.waitForExistence(timeout: 30)
        snap("01-trips")
        XCTAssertTrue(loaded, "Trips did not show its trip ideas")
        guard loaded else { return }
        // Sideways, as Android's screens turn (they stretch to the width, clear of the Dynamic Island's side).
        landscape("01b-trips-landscape")

        // The bar first, because it needs nothing else: Me, the Calendar tab, back to Trips. Me has a "Calendar" of its
        // own (Plan view), so the Calendar tab is tapped from Trips.
        if tap("Me", in: app), shows("Your space", in: app) { snap("02-me") }
        _ = tap("Trips", in: app)
        if tap("Calendar", in: app), shows("Month", in: app) { snap("03-calendar") }
        _ = tap("Trips", in: app)
        // Round 24: the Community tab, signed out (what the community is over its sign-in card; the tour never taps Sign
        // in) or, in a build without the community's Firebase ids, "Not in this build". Neither asks anything. It waits
        // for words at the top of the page (the Simulator doesn't report Compose's controls below the fold); the tab has
        // no page of its own on top, so Trips is tapped straight after.
        if tap("Community", in: app),
            shows("Trips, clips and photo albums", in: app, wait: 10) || shows("Not in this build", in: app, wait: 5) {
            snap("03b-community")
        }
        _ = tap("Trips", in: app)
        guard idea.waitForExistence(timeout: 15) else { return }

        // One trip idea.
        idea.tap()
        _ = app.staticTexts.matching(NSPredicate(format: "label CONTAINS[c] %@", "Day 1")).firstMatch.waitForExistence(timeout: 10)
        snap("04-trip-idea")

        // Its plan becomes a trip: the first day, then the trip's page, a new plan item and the trip's Journal. From
        // here on a step that doesn't show only ends the tour early; the pictures taken so far say where.
        guard tap("Use this program", in: app) else { return }
        guard shows("Make the trip", in: app) else { return }
        // "Make the trip" waits for a day: the 28th of the month the calendar opens on ("Saturday, November 28, 2026").
        // The calendar keeps the months beside this one off screen with the same labels, so the one that can be tapped
        // is looked for among all of them (the second run, 18 Nov 2026, found August's first and stopped).
        var picked = false
        let days = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS ' 28, '"))
        _ = days.firstMatch.waitForExistence(timeout: 6)
        for index in 0..<days.count {
            let day = days.element(boundBy: index)
            if day.isHittable {
                day.tap()
                picked = true
                break
            }
        }
        // Not found: what the calendar calls its days goes into the log, to write the right label here.
        if !picked {
            print("TOUR-SCREEN-BEGIN")
            print(app.debugDescription)
            print("TOUR-SCREEN-END")
        }
        snap("05-first-day")
        guard tap("Make the trip", in: app) else { return }
        guard shows("items", in: app) else { return }
        snap("06-trip")
        landscape("06a-trip-landscape")
        // The plan opens collapsed, one line per item; expanded, every day in full with the travel between places.
        if tap("Expand", in: app), shows("Collapse", in: app) {
            snap("06b-plan-expanded")
            _ = tap("Collapse", in: app)
        }
        guard tap("Add", in: app) else { return }
        guard shows("Title", in: app) else { return }
        snap("07-plan-item")

        // The sheet closes (nothing was typed, so nothing is added); the trip's own bar opens its Journal.
        guard tap("Done", in: app) else { return }
        gone("Add to your plan", in: app)
        guard tap("Journal", in: app) else { return }
        guard shows("Nothing written for this trip", in: app) else { return }
        snap("08-trip-journal")

        // The trip's map (MapLibre): its Travelled · Planned pill and the leg sheet are the app's own, so they show
        // whether or not the tiles load. The trip isn't on yet: Travelled has nothing; Planned has the plan's places.
        if tap("Map", in: app), shows("Travelled", in: app) {
            // The map's style loads cold the first time ("Loading map"): the picture waits for it, up to 30 s, and is
            // taken anyway if it never goes (Mac run of 19 Nov 2026: both pictures showed "Loading map").
            gone("Loading map", in: app, wait: 30)
            snap("08b-trip-map")
            if tap("Planned", in: app) {
                gone("Loading map", in: app, wait: 10)
                snap("08c-trip-map-planned")
            }
        }

        // Back on Trips: the new trip as a ticket with its countdown.
        guard tap("Back", in: app), shows("Trip ideas", in: app) else { return }
        snap("09-trips")

        // The Map tab opens on the globe (as on Android); Places I've been from its chip.
        if tap("Map", in: app), shows("Places I've been", in: app) {
            snap("10-globe")
            if tap("Places I've been", in: app), shows("Search countries", in: app) {
                snap("10b-places-been")
                _ = tap("Back", in: app)
            }
        }

        // Me › Backup & sync: Google Drive's card on top (since round 23; without Google's client it only says so, and
        // nothing on the page is tapped), Last backup under it, the backup file's buttons below the fold. It waits for
        // "Last backup", on screen when the page opens: the Simulator doesn't report Compose's controls below the fold, so
        // "Save a file" was never found and the Back below never tapped (Mac run of 19 Nov 2026), and since each tab
        // keeps its pages, Me then reopened on Backup & sync and Route tracking's step had nothing to tap. Back is
        // tapped whatever the page showed, so Me is on its first page again.
        // Me's rows sit below the Account card and the theme pickers, off screen (Mac run of 19 Nov 2026, round 24: 02-me
        // ended at Voice notes), so the page is swiped to the row first ([scrollTap]).
        if tap("Me", in: app), scrollTap("Backup & sync", in: app) {
            if shows("Last backup", in: app) { snap("11-backup") }
            _ = tap("Back", in: app)
        }

        // The trip's ⋮ › Share: its pages made by WebKit and previewed, then back.
        _ = tap("Trips", in: app)
        if tap("Kyoto & Osaka", in: app), tap("Trip options", in: app), tap("Share", in: app), shows("Preview", in: app) {
            _ = shows("Tap a page to see it bigger.", in: app, wait: 60)
            snap("12-share")
            // A page seen bigger, with the plan in it: WebKit's own print, to compare with Android's pages.
            if tap("Include the plan", in: app) {
                Thread.sleep(forTimeInterval: 8)
            }
            let page = app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH[c] %@", "Page 2 of")).firstMatch
            if page.waitForExistence(timeout: 30), page.isHittable {
                page.tap()
                Thread.sleep(forTimeInterval: 3)
                snap("12b-share-page")
                _ = tap("Close", in: app)
                // The same page on ruled paper: its lines are a repeating gradient, which WebKit's print must draw as
                // lines (the markers' bands, another gradient, came out black on 19 Nov 2026).
                if tap("Ruled", in: app) {
                    Thread.sleep(forTimeInterval: 8)
                    if page.waitForExistence(timeout: 30), page.isHittable {
                        page.tap()
                        Thread.sleep(forTimeInterval: 3)
                        snap("12c-share-ruled")
                        _ = tap("Close", in: app)
                    }
                }
            }
            _ = tap("Back", in: app)
            // Android 1.22.0: the planner opens from the Plan's Chat, beside Add (the ✦ left the trip's top bar). Back from
            // Share returns to the tab the trip was on, Plan (a trip opens on Plan); the bar's Plan is tapped first all the
            // same, because Chat floats only over the Plan, and the plan's "N days · M items" line (at the top of the
            // page) says it is there. The planner's empty chat: nothing is sent, so no model and no network. The picture
            // waits for the trip's ⋮ to go (the screens fade).
            _ = tap("Plan", in: app)
            if shows("items", in: app, wait: 10), tap("Chat", in: app), shows("Change this trip", in: app) {
                gone("Trip options", in: app, wait: 5)
                snap("13-planner")
                _ = tap("Back", in: app)
            }
            // The trip's ⋮ › Add from a link: Copy from a link's address before anything is pasted (nothing is read).
            if tap("Trip options", in: app), tap("Add from a link", in: app), shows("Only public pages are read", in: app) {
                snap("14-copy-link")
                _ = tap("Back", in: app)
            }
            // Round 25: the trip's ⋮ › Post to community opens the composer; in a build without the community's Firebase
            // ids (CI's) it says "Not in this build", else the sign-in card over What to share (the tour never signs
            // in). Both are at the top of the page. Nothing is posted and nothing is asked; the picture waits for the
            // trip's ⋮ to go (the screens fade), then Back returns to the trip.
            if tap("Trip options", in: app), tap("Post to community", in: app),
               shows("Not in this build", in: app, wait: 10) || shows("What to share", in: app, wait: 5) {
                gone("Trip options", in: app, wait: 5)
                snap("14b-post-to-community")
                _ = tap("Back", in: app)
            }
        }

        // Round 10: Me › Route tracking before anything is switched on (Off: no steps, nothing asked), then the trip
        // map's Route tracking sheet for a trip that hasn't started ("Starts on …"; its levels are not tapped).
        _ = tap("Back", in: app)
        _ = tap("Trips", in: app)
        // Me's row reads "Route tracking" over its level, so its label may be both: the first that starts so.
        if tap("Me", in: app), scrollTap("Route tracking", prefix: true, in: app), shows("For every trip", in: app) {
            snap("15-route-tracking")
            _ = tap("Back", in: app)
        }
        _ = tap("Trips", in: app)
        if tap("Kyoto & Osaka", in: app), tap("Map", in: app), shows("Travelled", in: app),
           tapStarting("Route tracking", in: app), shows("For this trip", in: app) {
            snap("16-trip-route-tracking")
        }

        // Round 18: the trip video. The app starts again (the route tracking sheet is still open), the tour's trip opens
        // and its ⋮ › Make a clip: the dark studio with the trip's name, the size and Export, and the preview paused on its
        // first frame, the globe from space (the globe is bundled: it needs no network; the street map's pictures that
        // come later aren't waited for).
        app.terminate()
        app.launch()
        if tap("Kyoto & Osaka", in: app, wait: 30), tap("Trip options", in: app), tap("Make a clip", in: app),
           shows("Clip preview", in: app, wait: 60) {
            snap("17-trip-video")
            // Round 19: the editor under the preview (the tracks, the tools) and its Text tool's panel for the whole
            // clip. Nothing here asks the phone for anything (no recording, no picker) or reaches the network.
            if shows("Map scenes", in: app, wait: 30), tap("Text", in: app), shows("Place names", in: app) {
                snap("18-trip-video-text")
                _ = tap("Done", in: app)
            }
            // Round 20: Export's sheet. Export starts the video at once (as on Android); making it asks the phone for
            // nothing (the app's own files, no Photos, no microphone), so the tour waits for it, generously, and keeps
            // a picture of the sheet that follows: proof that AVAssetWriter wrote the MP4. No assertion: a slow
            // Simulator only ends the tour early.
            if tap("Export", in: app), shows("Export", in: app) {
                snap("19-trip-video-export")
                if shows("Your video is ready", in: app, wait: 600) {
                    snap("20-trip-video-made")
                } else {
                    snap("20-trip-video-not-made")
                    print("TOUR-SCREEN-BEGIN")
                    print(app.debugDescription)
                    print("TOUR-SCREEN-END")
                }
            }
        }

        // Round 21: the book. The app starts again, the tour's trip opens and its ⋮ › Make a book lays the book out
        // (Core Text measuring its words) and keeps it: the pages' overview, then the cover opened large and the page
        // after it, both WebKit's own pages (the highlighter's band behind the dates, the flags, the stamp), and the PDF
        // made through WebKit's print at the book's own size: Send to Drive makes it and then only says Drive isn't
        // connected (no system sheet). No assertion: a slow Simulator only ends the tour early.
        app.terminate()
        app.launch()
        if tap("Kyoto & Osaka", in: app, wait: 30), tap("Trip options", in: app), tap("Make a book", in: app),
           shows("Tap a page to change it.", in: app, wait: 120) {
            snap("21-book-pages")
            if tap("1 · Cover", in: app, wait: 15) || tapStarting("Page 1,", in: app, wait: 5) {
                Thread.sleep(forTimeInterval: 6)
                snap("22-book-cover")
                if tap("Next page", in: app) {
                    Thread.sleep(forTimeInterval: 6)
                    snap("23-book-page-2")
                }
                if tap("PDF", in: app), shows("Send to Drive", in: app, wait: 15), tap("Send to Drive", in: app) {
                    if shows("Google Drive isn't connected", in: app, wait: 300) {
                        snap("24-book-pdf-made")
                    } else {
                        snap("24-book-pdf-not-made")
                    }
                }
            }
        }

        // Round 22: the page editor. The app starts again and the tour's trip opens its book (kept from the visit
        // before); the title page opens in the editor (WebKit's page under the editing layer, "On this page" with its
        // rows, the page's tools), its title is tapped (the coral outline, the corners and the turn handle, the words'
        // tools), and All pages goes back. Nothing here asks the phone for anything. No assertion.
        app.terminate()
        app.launch()
        if tap("Kyoto & Osaka", in: app, wait: 30), tap("Trip options", in: app), tap("Make a book", in: app),
           shows("Tap a page to change it.", in: app, wait: 120) {
            if tap("2 · Title", in: app, wait: 15) || tapStarting("Page 2,", in: app, wait: 5) {
                if shows("On this page", in: app, wait: 30) {
                    Thread.sleep(forTimeInterval: 6)
                    snap("25-book-editor")
                }
                if tap("Title", in: app, wait: 15) {
                    Thread.sleep(forTimeInterval: 3)
                    snap("26-book-editor-selected")
                }
                if tap("All pages", in: app, wait: 10) {
                    _ = shows("Tap a page to change it.", in: app, wait: 15)
                    snap("27-book-pages-again")
                }
            }
        }
    }

    /// Whether something whose label contains `text` shows within a few seconds.
    private func shows(_ text: String, in app: XCUIApplication, wait: TimeInterval = 15) -> Bool {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS[c] %@", text)).firstMatch.waitForExistence(timeout: wait)
    }

    /// Taps the first thing on screen labelled `text`; false when it never shows or can't be tapped.
    private func tap(_ text: String, in app: XCUIApplication, wait: TimeInterval = 15) -> Bool {
        let all = app.descendants(matching: .any).matching(NSPredicate(format: "label ==[c] %@", text))
        guard all.firstMatch.waitForExistence(timeout: wait) else { return false }
        // Several things can carry the same label (a title and a tab, a row off screen): the first that can be tapped.
        for index in 0..<all.count {
            let found = all.element(boundBy: index)
            if found.isHittable {
                found.tap()
                return true
            }
        }
        return false
    }

    /// Taps the first thing on screen whose label starts with `text` (a row whose label holds its value too).
    private func tapStarting(_ text: String, in app: XCUIApplication, wait: TimeInterval = 15) -> Bool {
        let all = app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH[c] %@", text))
        guard all.firstMatch.waitForExistence(timeout: wait) else { return false }
        for index in 0..<all.count {
            let found = all.element(boundBy: index)
            if found.isHittable {
                found.tap()
                return true
            }
        }
        return false
    }

    /// Taps the row labelled `text` (or starting with it, [prefix]) on a page longer than the screen: XCUITest doesn't
    /// report Compose's controls off screen, so the page is swiped up until the row can be tapped (about six times),
    /// then down as far (the tab keeps its scroll between visits, so the page may be past it already); false when it never
    /// shows.
    private func scrollTap(_ text: String, prefix: Bool = false, in app: XCUIApplication) -> Bool {
        let format = prefix ? "label BEGINSWITH[c] %@" : "label ==[c] %@"
        func hittable() -> XCUIElement? {
            let all = app.descendants(matching: .any).matching(NSPredicate(format: format, text))
            for index in 0..<all.count {
                let found = all.element(boundBy: index)
                if found.exists && found.isHittable { return found }
            }
            return nil
        }
        // The page as it is first: the row may be on screen already.
        _ = app.descendants(matching: .any).matching(NSPredicate(format: format, text)).firstMatch.waitForExistence(timeout: 3)
        if let row = hittable() {
            row.tap()
            return true
        }
        // Up about six times (to the page's end), then down as far again and more (back past where it started).
        for (up, times) in [(true, 6), (false, 12)] {
            for _ in 0..<times {
                if up { app.swipeUp() } else { app.swipeDown() }
                Thread.sleep(forTimeInterval: 0.6)
                if let row = hittable() {
                    row.tap()
                    return true
                }
            }
        }
        return false
    }

    /// Waits a few seconds at most for whatever is labelled with `text` to go (a sheet closing).
    private func gone(_ text: String, in app: XCUIApplication, wait: TimeInterval = 5) {
        let element = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS[c] %@", text)).firstMatch
        let end = Date().addingTimeInterval(wait)
        while element.exists && Date() < end {
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        }
    }

    /// Turns the Simulator sideways, keeps a picture, and turns it upright again (the app turns as Android's does).
    private func landscape(_ name: String) {
        XCUIDevice.shared.orientation = .landscapeLeft
        snap(name)
        XCUIDevice.shared.orientation = .portrait
        Thread.sleep(forTimeInterval: 1)
    }

    private func snap(_ name: String) {
        // The Simulator on GitHub's Macs draws slowly: without a pause a picture can show the screen before, or a map
        // before its tiles (Street Gym's tour showed one screen six times in a row on 18 Nov 2026).
        Thread.sleep(forTimeInterval: 1.5)
        let picture = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        picture.name = name
        picture.lifetime = .keepAlways
        add(picture)
    }
}
