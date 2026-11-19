# BoringTravel for iPhone: full checklist

Everything the Android app (1.21.1) lets a user see or do, one line per thing a tester would check; a feature not on this list does not get built.
`[x]` is on iPhone today (in `ios-boringtravel`), `[ ]` is to build. Pointers are Android files under `android-boringtravel/app/src/main/java/com/justdataplease/boringtravel/` (engine files under `android-boringtravel/shared/src/commonMain/kotlin/com/justdataplease/boringtravel/`), with the tags **engine**, **iPhone API**, **network** and **owner**.
18 Nov 2026; the parity rounds of 19 Nov 2026 (the owner: "we need to be ios and android exactly the same") made the iPhone Android's wherever iOS allows it: what still differs, and why, is under "Where the iPhone still differs from Android" at the end.

## Counts

| Area | Features | On iPhone | To build | Waiting on the owner |
|---|---:|---:|---:|---:|
| 1. First launch and onboarding | 12 | 11 | 1 | 0 |
| 2. Tabs and navigation | 29 | 23 | 6 | 2 |
| 3. Trips home | 23 | 23 | 0 | 0 |
| 4. New trip, Edit trip, Delete trip | 25 | 25 | 0 | 0 |
| 5. Trip ideas | 9 | 9 | 0 | 0 |
| 6. A trip's page | 67 | 61 | 6 | 0 |
| 7. Plan items and the item sheet | 41 | 41 | 0 | 0 |
| 8. Places and place pages | 25 | 25 | 0 | 0 |
| 9. Maps and routes | 28 | 28 | 0 | 0 |
| 10. The journal | 31 | 29 | 2 | 0 |
| 11. The note editor, photos and videos | 72 | 68 | 4 | 0 |
| 12. Check-ins | 10 | 9 | 1 | 0 |
| 13. Dictation | 13 | 13 | 0 | 0 |
| 14. Calendar tab | 12 | 12 | 0 | 0 |
| 15. The phone's calendar | 24 | 23 | 1 | 0 |
| 16. Your world and the globe | 42 | 42 | 0 | 0 |
| 17. The trip video (clip) and its editor | 115 | 112 | 3 | 1 |
| 18. The book | 67 | 66 | 1 | 0 |
| 19. Share (print and export) | 28 | 28 | 0 | 0 |
| 20. The AI planner | 93 | 89 | 4 | 1 |
| 21. Copy from a link | 41 | 40 | 1 | 0 |
| 22. Community | 82 | 40 | 42 | 42 |
| 23. Travel together | 34 | 0 | 34 | 34 |
| 24. Route tracking | 28 | 28 | 0 | 0 |
| 25. Notifications | 6 | 6 | 0 | 0 |
| 26. Widgets and shortcuts | 11 | 10 | 1 | 1 |
| 27. Sharing into the app | 12 | 0 | 12 | 1 |
| 28. Me and every setting | 10 | 9 | 1 | 0 |
| 29. Your traveller & rides | 11 | 11 | 0 | 0 |
| 30. Themes and fonts | 15 | 13 | 2 | 0 |
| 31. Demo mode | 15 | 12 | 3 | 0 |
| 32. Accounts and sign-in | 10 | 8 | 2 | 2 |
| 33. Backups and restore | 31 | 30 | 1 | 0 |
| 34. Pro and purchases | 9 | 0 | 9 | 1 |
| 35. Everything else | 9 | 8 | 1 | 0 |
| **All** | **1090** | **952** | **138** | **85** |

"Waiting on the owner" counts the open lines tagged **owner**, plus every open line of Community and every line of Travel together and the open Drive lines of Backups (said under those headings). Most of them can be built and tested with a test double first.

A line is ticked only when everything it names is on iPhone: a line with one part that needs a later round (a country flag, a place page, the globe, a shared trip) stays open, and the round notes in "Build order" say which part.

## 1. First launch and onboarding

### Welcome
- [x] App opens on Welcome until onboarding is done, then on Trips (`ui/nav/AppNav.kt` `AppNav`)
- [x] Welcome page: brand mark, "BoringTravel", tagline, "Your journal lives on this phone…" footer, Privacy policy link (`ui/onboarding/WelcomeScreen.kt` `WelcomeScreen`; `ui/me/AboutApp.kt` `PrivacyLink`)
- [x] "Why Google" card: Drive is for backups, the app sees only the files it makes (`ui/onboarding/WelcomeScreen.kt` `WelcomeButtons`)
- [x] "Continue with Google" ("Connecting…"): account sheet, then Drive consent, in one tap; a no to Drive still signs in (`ui/onboarding/GoogleUi.kt` `rememberGoogleConnect`; **iPhone API**: AuthenticationServices' ASWebAuthenticationSession, Google's page asking for the account and Drive on one page; **network**; **owner**: Google iOS OAuth client; Apple 4.8: Sign in with Apple beside Google, since round 23)
- [ ] Build without sign-in: "Google sign-in isn't set up in this build…" and a plain "Continue" that connects Drive only (`ui/onboarding/WelcomeScreen.kt` `WelcomeButtons`; iPhone: the words and Continue are there, but Continue goes straight to Trips: without the iOS client an iPhone can't ask Google for Drive at all, where Android's Authorization API needs no client of its own)
- [x] Sign-in and Drive errors in red on Welcome; closing Google's sheet does nothing (`ui/onboarding/GoogleUi.kt` `GoogleConnect`; **owner**: Google iOS OAuth client)
- [x] "Use without an account" finishes onboarding; Drive can be connected later in Me › Backup & sync (`ui/onboarding/WelcomeScreen.kt` `finish`)
- [x] Connected: "Signed in with Google · Google Drive connected" row with a round initial, then "Looking for a backup" (`ui/onboarding/GoogleUi.kt` `SignedInRow`, `ProgressLine`; **network**; **owner**: Google iOS OAuth client)
- [x] "Found in your Google Drive": backup date, counts, Restore / Start fresh (`ui/onboarding/GoogleUi.kt` `BackupFoundCard`; **network**; **owner**: real Drive account for the last check)
- [x] Restore from Welcome: progress "… · 3 of 12", red error with "Try again", success opens Trips (`ui/onboarding/WelcomeScreen.kt` `restore`; **network**; **owner**: Google iOS OAuth client)
- [x] No backup, or Drive unreachable: straight to Trips; offline, the restore offer comes on a later open (`ui/onboarding/WelcomeScreen.kt`; `ui/me/RestoreOffer.kt`)
- [x] No location question during onboarding; route tracking stays Off (`ui/onboarding/WelcomeScreen.kt`)

## 2. Tabs and navigation

### Start and system bars
- [x] Fonts are read before the first screen, which then draws in the theme without a flash (`MainActivity.kt` `onCreate`)
- [x] Edge to edge: the page's paper runs under the status bar and the home indicator (`MainActivity.kt`)
- [x] Status-bar icons light on Trail, dark on the four light themes (`MainActivity.kt` `SystemBars`; iPhone: `ios/App.kt` sets compat's `SystemBars` per theme, `iosMain/…/MainViewController.kt` gives the window the light or dark style the status bar follows; **iPhone API**: the window's overrideUserInterfaceStyle, setNeedsStatusBarAppearanceUpdate)
- [x] Screens fade into each other (160 ms in, 120 ms out) (`ui/nav/AppNav.kt` NavHost; iPhone `ios/App.kt`: AnimatedContent with Android's tweens)

### Bottom bar outside a trip
- [x] Bar: Trips · Calendar · Map · Community · Me (`ui/nav/TripNav.kt` `HomeTab`; `ui/nav/AppNav.kt` `homeItems`)
- [x] Bar drawn per theme: Postcard Pop's coral pill, Trail's luggage tag, Sticker Book's highlighter, Sketchbook's pencil loop, Market's dot sticker (`ui/theme/NavBars.kt` `NavBars`, already compiled; `ui/nav/AppNav.kt` `NavItem`)
- [x] Labels that don't fit shrink to 60 % (never under 8 sp) (`ui/nav/AppNav.kt` `labelMinSize`)
- [x] The bar also shows on pages under a tab: Notes outside trips, a community post, profile or follow list; a place page keeps the bar it opened over (`ui/nav/TripNav.kt` `bar`, `Where`; iPhone `ios/App.kt` `where`)
- [x] No bar on Write, a note, the video and book editors, settings pages, New/Edit trip, Trip ideas, the planner, Copy from a link, Compose (`ui/nav/TripNav.kt` `Where.Elsewhere`)
- [x] Tapping the tab you're on does nothing; on a page under it, goes back to the tab; other tabs keep their pages and where they were (Android's popUpTo(start) { saveState } and restoreState; pages straight over Trips aren't brought back, as on Android) (`ui/nav/TripNav.kt` `homeTap`; `ui/nav/AppActions.kt` `homeTab`, `tab`; iPhone `ios/AppStack.kt`, `ios/App.kt` `showTab`)

### Bottom bar inside a trip
- [x] Bar: Plan · Journal · Map · Media; entering or leaving a trip crossfades the items while the bar stays (`ui/nav/TripNav.kt` `TripTab`; `ui/nav/AppNav.kt` `BtNavBar`)
- [x] Trip tabs switch in place with no back step; from a place page over the trip, a tab tap closes the page first (`ui/nav/TripNav.kt` `tripTap`)
- [ ] A trip opens on Plan unless a tab is asked for: Today widget and Write today → Journal, Map tab → Map, a shared link or the Calendar → Plan on that day (`ui/nav/TripNav.kt` `landingTab`; `ui/nav/AppActions.kt` `openPlanDay`)
- [x] One trip open at a time: opening it again brings it back, another trip replaces it (`ui/nav/AppActions.kt` `goToTrip`)
- [ ] A trip deleted or left elsewhere while open closes its screen (`ui/trip/TripScreen.kt` `TripScreen`)

### Back
- [x] Back button in the top bar of every pushed screen (`ui/components/Components.kt` `BtTopBar`, `BtBackButton`)
- [x] Swipe from the left edge does what Android's system Back does, with the rules below (Android system Back; iPhone: Compose Multiplatform's navigation events, which UIKit's screen-edge gesture sends, `ios/Back.kt`; the frame's Back in `ios/App.kt` and every Android BackHandler: New trip, Write, the book's page editor, the clip editor; the clip's full-screen preview closes as Android's dialog does; Compose's own dialogs, sheets and menus close first, as Android's)
- [x] Back from inside a trip leaves it in one step, to where it was opened from (Trips, Calendar, Map) (`ui/nav/AppActions.kt` `openTrip`)
- [x] New trip: Back from the form goes to the start choices; Back from the choices closes New trip (`ui/trips/EditTripScreen.kt` `BackHandler`)
- [x] Trip ideas: Back from a destination opened directly leaves; picked from the list, it returns to the list (`ui/programs/ProgramsScreen.kt` `BackHandler`)
- [x] A trip made from the New trip form or a trip idea takes their place in the history: Back goes to where New trip started (`ui/nav/AppActions.kt` `ideaUsed`; `ui/trips/EditTripScreen.kt` `save`)
- [x] "Write today" opens Write over the trip's Journal: Back shows the new note, Back again leaves the trip (`ui/nav/AppActions.kt` `writeInTrip`)
- [x] Camera from a widget or shortcut closed without a photo goes back where it came from (Android: the home screen; iPhone: the app's last screen) (`MainActivity.kt` `leave`)

### Links into the app
- [x] A community post link `https://justdataplease.com/apps/boringtravel/post/#<id>` opens the post (`widget/Deeplink.kt` `of`; `community/CommunityLinks.kt` `find`; **iPhone API**: universal links (Associated Domains); **owner**: apple-app-site-association on justdataplease.com; iPhone `ios/Widgets.kt` `Deeplink`, the entitlement in `iosApp/project.yml`, and `boringtravel://post/#<id>` for the website to fall back on)
- [ ] A join link `…/apps/boringtravel/join#…` or `boringtravel://join#…` opens the Join sheet; the data after `#` must reach the app (`MainActivity.kt` `receiveJoin`; **iPhone API**: universal links + URL scheme; **owner**: apple-app-site-association)
- [ ] Shares, join links and imports that arrive before Welcome is done wait and open after it; post links and widget actions before onboarding are dropped (`ui/nav/AppNav.kt` `shareReady`, `pendingAction`)
- [ ] Pending shares, joins and imports survive the app being stopped and reopened (`MainActivity.kt` `onSaveInstanceState`). The screens themselves do since the parity round: the stack, each tab's pages, the trip's tab and day and the note being written come back after iOS closed the app in the background (`ios/AppStack.kt` AppRestore, kept in SwiftUI's scene storage by `iosApp/BoringTravel/BoringTravelApp.swift`); shares, joins and imports wait for the share extension and universal links (**owner**)

### Messages
- [x] Snackbars in reverse colours (page colour on ink) in every theme (`ui/nav/AppNav.kt` `SnackbarHost`)
- [ ] Android's toasts ("Copied", restore results, "The photo couldn't be added.", "Added to the plan") shown as snackbars or banners (iPhone has no toast)

## 3. Trips home

### Header
- [x] Eyebrow with weekday and date ("Wed 18 Nov") over "Trips" (`ui/trips/TripsScreen.kt` `TripsScreen`)
- [x] "New trip" button with + in the header, hidden when there are no trips (`ui/trips/TripsScreen.kt` `TripsScreen`)

### Sections and rows
- [x] Sections: the trip happening now, then Next (soonest first), Someday (no dates), Past (latest first) (`ui/trips/TripsScreen.kt` `section`; **engine**: ui/trips/HomeStats.kt)
- [x] The trip happening now comes first, marked "Now · Day 14" (`ui/trips/TripsScreen.kt` `NowCard`)
- [x] Trip rows with the name, the route line and "Oct 2027 · 12 days" (or "Someday") (`ui/trips/TripsScreen.kt` `tripLine`; **engine**: util/Dates.kt `tripSummary`)
- [x] A tap on a row or card opens the trip on Plan (`ui/trips/TripsScreen.kt` `TicketRow`, `TripRow`, `NowCard`)
- [x] Each trip in its own colour, the same as on the Calendar tab (`ui/trips/TripsScreen.kt` `colorOf`; `tripcal/TripColors.kt`)
- [x] Up to 3 country flags per row, "+N" beyond, in travel order (`ui/world/WorldBits.kt` `TripFlags`; `world/WorldSource.kt`)
- [x] Ticket row: countdown stub, dashed perforation with notches, body with eyebrow, name, flags, DEMO tag, in the theme's card style (`ui/trips/TripTicket.kt` `TripTicket`, `TicketShape`)
- [x] Ticket eyebrow "3 Oct 2027 · 12 days" / "No dates yet"; footer "68 places planned" (Next, Someday) or "8 entries" (Past), else the route (`ui/trips/TripsScreen.kt` `TicketRow`; `ui/trips/TripCountdown.kt` `countLine`)
- [x] Stub colour: the trip's colour (Now, Next), pale (Past), chip with a calendar icon and "Add dates" (undated: the stub opens Edit trip) (`ui/trips/TripTicket.kt` `TicketStub`)
- [x] Countdown words: "N days to go", "Tomorrow", "N days left", "1 day left", "Last day", "Yesterday", "N days/weeks/months/years ago", "Add dates"; 3-digit numbers set smaller (`ui/trips/TripCountdown.kt` `TripCountdown.of`; `ui/trips/TripTicket.kt` `CountdownText`)
- [x] Pictures row: leaning framed thumbnail, name, flags, DEMO tag, "Oct 2027 · 12 days · 7 places", countdown (`ui/trips/TripsScreen.kt` `TripRow`, `tripLine`)
- [x] Trip picture: the cover photo, else its first photo, else a drawn scene; half of them mirrored (`ui/trips/TripCover.kt`; `ui/components/Pictures.kt` `sceneFor`)

### Now card
- [x] Tickets look: "Now · day 14 of 31", name, flags, DEMO tag, a progress bar of the trip's days, the dates; stub "N days left" / "Last day" (`ui/trips/TripsScreen.kt` `NowTicket`, `nowDates`; `ui/trips/TripTicket.kt` `TripProgress`)
- [x] Pictures look: framed cover leaning −1.5°, countdown sticker leaning 8°, "Now · day X of Y", flags, name, route (`ui/trips/TripsScreen.kt` `NowCard`; `ui/trips/TripTicket.kt` `CountdownSticker`)
- [x] "Write today": the trip's Journal, then Write over it (`ui/nav/AppActions.kt` `writeInTrip`)
- [x] Mic "Dictate": the same, with dictation started (`ui/nav/AppActions.kt` `writeInTrip`; **iPhone API**: Speech)
- [x] Camera "Take a photo": the camera, then a new note in this trip opened in Write (`ui/editor/TakePhoto.kt` `rememberQuickCamera`; **iPhone API**: UIImagePickerController; **owner**: camera check on a real iPhone)

### End of the list and empty states
- [x] "Trip ideas" row: "Ready day-by-day plans: Kyoto & Osaka, Lisbon & Porto, Argentina & Patagonia" (`ui/trips/TripsScreen.kt` `TripIdeasRow`)
- [x] No trips: "No trips yet", "Add your own, or start from a ready-made trip.", New trip, and a Trip ideas card listing each destination; a tap opens it (`ui/trips/TripsScreen.kt` `TripIdeasInvite`, `IdeaRow`)
- [x] Each destination's drawn picture in that card (`ui/trips/TripsScreen.kt` `IdeaRow`; `art/Scenes.kt`)
- [x] "Notes outside trips · N notes" card, only when there are some (`ui/trips/TripsScreen.kt` `LooseNotesCard`)

## 4. New trip, Edit trip, Delete trip

### Ways to start
- [x] New trip asks how to start: "Make it myself" in its own panel, then "Or" (`ui/trips/EditTripScreen.kt` `NewTripChoices`, `OrLine`)
- [x] "Start from a ready-made trip" opens Trip ideas (`ui/trips/EditTripScreen.kt` `NewTripChoices`)
- [x] "From a questionnaire": the planner with Plan a trip open (`ui/trips/EditTripScreen.kt`; `ui/nav/Routes.kt` `PlannerStart.QUESTIONS`; **network**)
- [x] "From pictures": the planner with the photo picker open (`ui/nav/Routes.kt` `PlannerStart.PICTURES`; **iPhone API**: PHPicker; **network**)
- [x] "From a site, blog or Google list": the planner with "Add a link" open (`ui/nav/Routes.kt` `PlannerStart.LINK`; **network**)
- [x] "Start from a community trip": the Community tab, where "Copy this trip" makes it yours (`ui/trips/EditTripScreen.kt` `NewTripChoices`; iPhone `ios/TripForm.kt`)

### The form
- [x] Top bar: back, "New trip" / "Edit trip", Save (form only), off until the name is filled in (`ui/trips/EditTripScreen.kt` `EditTripScreen`)
- [x] "Name" field ("Cyclades, slowly") and "Route" field ("Paros → Naxos → Amorgos") (`ui/trips/EditTripScreen.kt`)
- [x] "Dates" button: the range and "N days", or "No dates yet" and "Add" (`ui/trips/EditTripScreen.kt`; **engine**: util/Dates.kt)
- [x] Date range picker: Done (one day allowed), No dates yet, Cancel (`ui/trips/EditTripScreen.kt` `DateRangePicker`)
- [x] Notes under Dates: "Someday trips keep ideas until you pick dates." / "Planned days move with the new dates." (`ui/trips/EditTripScreen.kt`)
- [x] New dates move every planned day and the days' names by the same number of days (`ui/trip/PlanLogic.kt` `PlanDays.shift`; **engine**: engine/TripBook.kt `editTrip`, model/DayNames.kt)
- [x] Save on a new trip makes it and opens it on Plan; on Edit trip it saves and closes (`ui/trips/EditTripScreen.kt` `save`)
- [x] Calendar's "New trip from here" prefills the start day in the form (`ui/trips/EditTripScreen.kt` `startOn`)
- [x] Cover: empty frame (or the drawn picture) with "Add a cover photo", opening the photo picker, images only (`ui/trips/EditTripScreen.kt` `CoverField`; **iPhone API**: PHPicker)
- [x] New trip: the empty cover turns into a drawn scene once the name or route names a place kind (lake, beach, Kyoto…) (`art/Scenes.kt` `ScenePick.kindOf`)
- [x] Cover photo set: tap to change, "Change photo", "Remove" (`ui/trips/EditTripScreen.kt` `CoverField`)
- [x] "Or pick one from this trip": up to 24 of the trip's photos, no videos (`ui/trips/EditTripScreen.kt` `CoverField`)
- [x] "That photo couldn't be added. Try another one."; photos picked but not kept are deleted on Save (`ui/trips/EditTripScreen.kt` `picker`, `save`)
- [x] "Photos in notes": size Small / Medium / Large / Full and frame None / Polaroid / Tape / Rounded, with its note; default Full + Rounded; a new trip starts from the last choice; kept for 300 trips and in backups (`ui/trips/EditTripScreen.kt` `PhotosInNotes`; `data/settings/TripPhotos.kt`)
- [x] "Delete trip": full-width red outline button, on Edit trip only (`ui/components/BtConfirm.kt` `BtDeleteButton`)

### Delete trip
- [x] From Edit trip and the trip's ⋮ menu: "Delete this trip?" with Delete (red) and Cancel (`ui/trips/DeleteTripDialog.kt` `DeleteTripDialog`)
- [x] Deletes the plan and the journal; rows are marked deleted, so backups keep them deleted (`data/Deletions.kt` `deleteTrip`; **engine**: data/TripMarks.kt)
- [x] Also deletes the app's copies of its photos (unless another photo uses them), the recorded route, the book, saved videos and video edits, and its phone-calendar events (`data/TripRemoval.kt`; `data/Deletions.kt`; **iPhone API**: EventKit)
- [x] Dialog text in full: what goes, and that the phone's gallery and community posts stay; both buttons held while it runs (`ui/trips/DeleteTripDialog.kt` `DeleteTripText`)

## 5. Trip ideas

- [x] "Trip ideas" with "Pick a place, answer two questions, get a day-by-day plan."; each card: N days, name, blurb, best time; nothing fetched (`ui/programs/ProgramsScreen.kt` `DestinationList`; **engine**: programs/ProgramLibrary.kt)
- [x] Each destination's framed drawn picture (Kyoto & Osaka city, Lisbon & Porto coast, Argentina & Patagonia mountains) (`programs/ProgramScenes.kt`; `art/SceneArt.kt`)
- [x] "How do you like to travel?" Chill / Balanced / Energetic, with the picked one's line (**engine**: programs/Programs.kt `Pace`)
- [x] "What matters most?" Eat & drink / Sights & history / Nature & views (**engine**: programs/Programs.kt `Focus`)
- [x] Live preview: facts line, title, blurb, best time, every day with its items (icon, title, "07:30 · note"); a slower pace drops items, never a whole day (`ui/programs/ProgramPreview.kt`)
- [x] "Ideas" ("Not on a day. They go to Ideas in the plan.") and the "Sources: …" line (`ui/programs/ProgramPreview.kt` `ideas`, `sources`)
- [x] "Use this program": first-day picker titled with the days, "Pick the first day" then the range, "Make the trip" faded until a day is picked (`ui/programs/ProgramsScreen.kt` `StartDateDialog`)
- [x] The trip made: name, route, dates, items with times, notes, places, websites and travel between places; ideas in Ideas; nothing ticked; opens on Plan (**engine**: programs/ProgramTrips.kt, engine/TripBook.kt `startFromIdea`)
- [x] "Couldn't make the trip. Try again." (`ui/programs/ProgramsScreen.kt` `ProgramView`)

## 6. A trip's page

### Header
- [x] Back, "Day 14 of 31" while the trip is on, else "1–31 Mar 2027 · 31 days" or "Dates to be decided", the name in the display face (`ui/trip/TripScreen.kt` `TripHeader`, `dateLine`)
- [x] DEMO tag on example trips and up to 3 country flags (`ui/trip/TripScreen.kt` `TripHeader`)
- [ ] Shared trip badge "+N" or "Shared"; a tap opens Travel together (`ui/trip/TripScreen.kt` `SharedBadge`)
- [x] "Plan with AI" sparkle button opens the planner on this trip (`ui/trip/TripScreen.kt` `TripHeader`; **network**)

### ⋮ menu ("Trip options")
- [x] Edit trip, and last, in red, Delete trip (`ui/trip/TripScreen.kt` `TripHeader`)
- [x] Add from a link: Copy from a link for this trip (`ui/trip/TripScreen.kt` `TripHeader`)
- [ ] Post to community: the composer for this trip (`ui/trip/TripScreen.kt` `TripHeader`)
- [ ] Community post, only when this phone posted the trip: the post's update sheet (`ui/trip/TripScreen.kt`; `community/PostLinks.kt`)
- [x] Make a clip, Make a book, Share (`ui/trip/TripScreen.kt` `TripHeader`)
- [x] Calendar: the Calendar sheet (`ui/trip/TripScreen.kt` `TripHeader`)
- [ ] Travel together: the Together sheet (`ui/trip/TripScreen.kt` `TripHeader`)
- [ ] Leave trip (red) in place of Delete trip on a shared trip (`ui/trip/TripScreen.kt`; `ui/trips/DeleteTripDialog.kt`)

### Plan tab: top row and views
- [x] "12 days · 34 items" over the plan (`ui/trip/PlanViews.kt` `planSummary`)
- [x] Expand / Collapse (List only): one line per item, or every day in full with travel; kept app-wide, collapsed by default (`ui/trip/PlanViews.kt` `PlanExpandToggle`; `data/settings/Settings.kt` `planExpanded`)
- [x] List · Calendar switch, the same setting as Me › Plan view; List by default (`ui/trip/PlanViews.kt` `PlanViewSwitch`)
- [x] Switching collapsed and expanded keeps the same row on top; each view keeps its scroll (`ui/trip/PlanTab.kt` `PlanTab`)
- [x] The List opens on today while the trip is on, else at the top (`ui/trip/PlanViewLogic.kt` `PlanListLogic.focus`)
- [ ] The List opens on a day asked for (the Calendar tab, Add to plan) (`ui/nav/AppActions.kt` `openPlanDay`)

### Plan tab: List
- [x] Every day under its header in date order; runs of empty days fold into "Days 5–7 · nothing planned", a tap adds to the first (`ui/trip/PlanViewLogic.kt` `PlanListLogic.sections`; `ui/trip/PlanViews.kt` `EmptyDays`)
- [x] Today and named days never fold; empty they read "Nothing planned for today yet." / "Nothing planned for this day yet." (`ui/trip/PlanViews.kt` `PlanListRow`)
- [x] Items on days outside the dates still show, under "Outside the trip's dates. Move these items to a day or to Ideas." (`ui/trip/PlanLogic.kt` `PlanDays.days`; `ui/trip/PlanViews.kt` `DayHeader`)
- [x] "Ideas · 3" last with + ("Add an idea"), shown when there are ideas or no dates (`ui/trip/PlanViews.kt` `IdeasHeaderRow`)
- [x] Trip without dates: "Ideas", "This trip has no dates yet. Add them to plan day by day.", Add dates (Edit trip), "Places and notes for later." (`ui/trip/PlanTab.kt` `IdeasHeader`)
- [x] Within a day, timed items by time; an end on a later day is its own row (**engine**: model/PlanSpan.kt `dayOrder`, `byDay`)
- [x] Expanded day header: "Day 13", TODAY tag, "Tue 14 Mar", + (`ui/trip/PlanViews.kt` `DayHeader`)
- [x] The day's place in its title ("Day 13 · El Chaltén"): that day's stops, else the area of its planned places, else the last travel item's destination; a chevron when it opens a place page (`places/DayPlace.kt`; `ui/trip/PlanDayLabel.kt`; `places/PlaceNames.kt` `area`)
- [x] Expanded row: kind circle, title, CHECK-IN / ENDS tag, "19:00–22:00 · 3 h · note" or "20:30 → Sun 15 11:00 · 3 nights", the place when it differs from the title, the "#tags" line (`ui/trip/PlanTab.kt` `PlanRow`; **engine**: model/PlanSpan.kt, tags/ItemTags.kt)
- [x] End row: dashed circle, the end's name, CHECK-OUT or ENDS, the end time; a tap opens the item, it can't be ticked (`ui/trip/PlanTab.kt` `EndRow`, `EndBadge`)
- [x] Kind icons Place, Food, Stay, Getting there, Activity, Note; travel items show their vehicle, the leg's saved choice or guessed from "Fly to…" (**engine**: model/Kinds.kt, model/TravelDefault.kt, clip/TravelModes.kt)
- [x] The kind circle shows the link's picture, else the site's favicon, else the place's Wikipedia photo, else the icon (`ui/trip/PlanTab.kt` `KindBadge`; `ui/trip/LinkViews.kt` `LinkBadgeLayers`; **network**)
- [x] Collapsed row: time column (only when some item has a time), small badge, title with the place muted, CHECK-IN / ENDS, "until Sun 15 · 11:00 · 3 nights", tags, the arriving vehicle's tiny icon, ticked items muted (`ui/trip/PlanViews.kt` `PlanLineContent`)
- [x] Collapsed day header: small bold title, TODAY, the date, + ("Outside the dates" for days outside) (`ui/trip/PlanViews.kt` `CompactDayHeader`)

### Day titles and day names
- [x] A tap on a day's title opens that day's place page (its first sight, else the arrival, else a located item); a day with nothing to open asks for a name (`ui/trip/PlanViews.kt` `dayTitleTouch`; `ui/trip/TravelDefault.kt` `PlanDayAnchor`)
- [x] Press and hold a day's title to rename it, with a haptic tick (`ui/trip/PlanTab.kt`)
- [x] "Rename day 3": "Leave it empty for no name." (or "…to show the place: Kyoto."), Save / Cancel, 40 characters at most (`ui/components/BtConfirm.kt` `BtTextDialog`; **engine**: model/DayNames.kt)
- [x] A named day reads "Day 3 · Temples and tea"; the name stays with the trip and moves with its dates (**engine**: model/DayNames.kt)

### Ticking
- [x] A tap on the kind circle ticks or unticks the item: a check on its edge in the route colour (`ui/trip/PlanTab.kt` `PlanRow`; **engine**: engine/TripBook.kt `setDone`)
- [x] Opening the Plan ticks items whose place the recorded route passed within 200 m on their day; it never unticks (`places/VisitMatcher.kt`)

### Adding
- [x] Empty plan: a big "Add a place or a note" button (`ui/trip/PlanTab.kt` `AddButton`)
- [x] With items: a floating "Add", to today during the trip, else the first day (`ui/trip/PlanTab.kt` `PlanTab`)
- [x] + on each day's header ("Add to day 3") and on Ideas ("Add an idea") (`ui/trip/PlanViews.kt` `AddIcon`)
- [x] "Plan with AI" card ("Paste rough notes, add screenshots or links, or chat…"; PRO tag once Pro is on sale): opens the planner (`ui/trip/PlanTab.kt` `PlanWithAiCard`; **network**)
- [x] "Add from a link" card ("A Google Maps list, a blog post or a Google Doc…"): Copy from a link for this trip (`ui/trip/PlanTab.kt` `AddFromLinkCard`)

### Dragging and the row menu
- [x] Press and hold lifts a row (raised, shadow, haptic); drag it within its day, onto another day's header, a folded empty-days line or Ideas, with a light tick per slot (`ui/trip/PlanViews.kt` `LiftedRow`; `ui/trip/PlanDrop.kt`)
- [x] A drop on another day fits the time between its neighbours' (to 5 min, or an hour before or after) and keeps the length (`ui/trip/PlanDrop.kt` `drop`, `fit`)
- [x] After a drop: "Moved to Tue 14 · 11:00" or "Moved to Ideas" with Undo; nothing when only the order changed (`ui/trip/PlanDrop.kt` `notice`; `ui/trip/PlanTab.kt` `PlanNotice`)
- [x] Hold and let go without moving (long press in the Calendar view and on end rows): Copy name, Copy address, Share (`ui/trip/PlanItemActions.kt` `PlanItemMenu`; **iPhone API**: UIPasteboard, share sheet)

### Travel between places
- [x] Expanded List, between two located items: a dashed line and a chip "Car · ≈ 3 h" (or "Choose travel"), "from X" when the leg starts on another day, "not in km" when left out; a tap opens the chooser (`ui/trip/PlanTravelRows.kt`; `ui/trip/LegRow.kt` `LegRowModel`; `clip/RouteLegs.kt` `planned`; **engine**: clip/PlanRoute.kt)
- [x] Collapsed List: the leg as a tiny vehicle icon at the end of the row (`ui/trip/PlanViewLogic.kt` `PlanLine.of`)
- [x] Default travel: Car; Plane, Train, Ferry, Walk or Bike when the destination's name says so; Walk inside one place on one day under 5 km; Plane from 700 km (**engine**: model/TravelDefault.kt; `ui/trip/TravelDefault.kt` `RouteLeg.travel`)
- [x] Rough time "≈ 15 min", "≈ 1 h 30", "≈ 3 h" from the distance and the vehicle, never stored (`ui/trip/LegRow.kt` `LegEstimate`; **engine**: model/TravelTime.kt)
- [x] "Travel for this leg": "From → To", six paper-vehicle tiles in the theme's colours (Walk, Bike, Car, Train, Ferry, Plane); a tap saves (`ui/components/TravelModeChooser.kt`; **engine**: clip/TravelModes.kt)
- [x] "Count in km": "2,370 km in the trip's total" / "…left out of the trip's total"; every leg counts but flights by default (`ui/trip/TravelPicker.kt` `legKmLine`; **engine**: model/TravelDefault.kt `countsKm`)
- [x] "Leave unselected" puts the leg back to its default (`ui/components/TravelModeChooser.kt`)
- [x] Travel errors: "These places changed…", "This route leg changed…", "Couldn't save travel. Try again.", then "Keep saved choice" (`ui/trip/PlanTab.kt`)

### Plan tab: Calendar view
- [x] A month grid for every month with a trip day or a dated item; the week starts per the phone's region (`ui/trip/PlanViews.kt` `planCalendar`, `MonthBlock`; `ui/trip/PlanViewLogic.kt` `PlanCalendarLogic`)
- [x] Trip days shaded, today in the accent, up to 3 dots a day (then a count), muted when all done, the picked day ringed (`ui/trip/PlanViews.kt` `DayCell`)
- [x] A tap on a planned day shows its items under its month, with a compact header (+, rename, place page) (`ui/trip/PlanViews.kt` `dayPanel`)
- [x] "Ideas · N" chip under the last month shows the ideas (`ui/trip/PlanViews.kt` `planCalendar`)
- [x] Opens on today during the trip, else the first day; Add goes to the picked day or Ideas (`ui/trip/PlanLogic.kt` `PlanDays.initial`)

### Media tab
- [x] "Clips · N": thumbnail with a play mark and length, date, Share, Delete (red); hidden when there are none (`ui/trip/TripMedia.kt` `ClipsRow`, `ClipCard`; `data/SavedClips.kt`)
- [x] A tap plays a clip in the phone's player (`data/SavedClips.kt` `playIntent`; **iPhone API**: AVPlayer; iPhone: Android's own full-screen player in the app, once, with its controls, as there is no other app to hand the file to: see the differences at the end)
- [x] Share a clip; Delete asks "Delete this clip?… Copies you shared stay." (`data/SavedClips.kt` `shareIntent`; **iPhone API**: share sheet)
- [x] "Photos and videos · N" by day ("Day 3 · Tue 14 Mar"), 3 squares a row, videos with a play mark, plus photos in no note (`ui/trip/TripMedia.kt` `TripMediaTab`, `PhotoTile`)
- [x] A tap opens the photo's note (`ui/trip/TripMedia.kt` `PhotoTile`)
- [x] Press and hold deletes one of your own: "Delete this photo?… It goes from its note, the trip's cover and the book. Your phone's gallery isn't touched."; its layout reflows (`data/Deletions.kt` `deletePhoto`; **engine**: doc/PhotoAlbums.kt)
- [x] Empty: "Photos and videos you add to this trip's journal show here." with "Add media", else a floating "Add media"; both open Write with the picker (`ui/trip/TripMedia.kt`; **iPhone API**: PHPicker)

## 7. Plan items and the item sheet

### Frame and saving
- [x] Bottom sheet "Add to your plan" / "Edit plan item"; the title row stays while the rest scrolls; Done (tick) closes (`ui/trip/PlanItemSheet.kt`)
- [x] Share icon in the sheet for a saved item (`ui/trip/PlanItemSheet.kt`)
- [x] Saves by itself 0.7 s after the last edit, and on Done, a swipe down or leaving the screen (`ui/trip/PlanItemSheet.kt` `commit`)
- [x] A new item is made by the first save with a title; emptied again, it goes when the sheet closes (`ui/trip/PlanItemSheet.kt` `commit`)
- [x] An emptied title falls back to the place name's first part, then the old title (`ui/trip/PlanItemSheet.kt` `commit`)
- [x] A place or a link alone also makes the item; the title then falls back to the link's name, "Google Maps place" or the site's domain (`links/LinkPlaces.kt` `placeholderTitle`)
- [x] An item with a pin but no place name is named by reverse geocoding when opened (**iPhone API**: CLGeocoder; **network**)

### Kind, title, day, time, end
- [x] Kind chips: Place, Food, Stay, Getting there, Activity, Note (**engine**: model/Kinds.kt)
- [x] Title field, 3 lines, "Lunch in Halki" ("Buy ferry tickets" for a Note) (`ui/trip/PlanItemSheet.kt`)
- [x] Day chips: every plan day plus Ideas, scrolled to the chosen one; a new day puts the item in time order, else at the end (`ui/trip/PlanItemSheet.kt` `setDay`; `ui/trip/PlanLogic.kt` `PlanOrder`)
- [x] "Add a time": a clock titled "Time" with Done / Cancel; X removes the time; the time sets the item's place in the day (`ui/trip/PlanItemSheet.kt` `ClockDialog`, `setTime`)
- [x] The clock follows the phone's 12- or 24-hour setting (iPhone today: always 24-hour) (`ui/trip/PlanItemSheet.kt` `ClockDialog`)
- [x] "Ends" on a day: No end · Same day · the trip's later days (else the next 7; 60 days at most); a Stay reads "Sun 15 · 3 nights" and ends at 11:00 (**engine**: model/PlanSpan.kt `endingAt`, `MAX_DAYS`)
- [x] "Add an end time" ("Ends at": an hour after the start, else 11:00), "Remove end time" (several days only), the length "3 h" / "3 nights" (`ui/trip/PlanItemSheet.kt` `EndSection`)
- [x] +1 h, +2 h, +3 h from the start; past midnight ends the next day (`ui/trip/PlanItemSheet.kt` `EndSection`)
- [x] "Its own row at the end" (on by itself for a later day), then "Name at the end" (placeholder: the title, or "Check-out") (**engine**: model/PlanSpan.kt `endRow`, `endName`)
- [x] Note field, 2–6 lines, "Bus from the port, 40 min" (`ui/trip/PlanItemSheet.kt`)
- [x] Delete (saved items): red, asks "Delete this item?" / "…You can't undo this." (`ui/trip/PlanItemSheet.kt`)

### Place
- [x] "Place or Google Maps link" ("Search, or paste a Google Maps link"): searches as you type (2+ letters, 0.35 s pause); rows with kind icon, name, area; a pick fills the place, the title if empty and the kind unless one was chosen (`ui/trip/PlanItemSheet.kt` `PlaceSearchField`, `setPlace`; `places/PhotonPlaceSearch.kt`; **network**)
- [x] "Use “Halki” as the place": a typed name without a pin, works offline (`ui/trip/PlanItemSheet.kt` `setPlaceName`)
- [x] "Searching…", "Can't search right now. Check the connection.", "Nothing found. Try another spelling."; "Places © OpenStreetMap contributors" under results (`ui/trip/PlanItemSheet.kt` `PlaceSearchField`)
- [x] The search leans towards the day's first located item, else the trip's (`ui/trip/PlanItemSheet.kt` `PlaceField`)
- [x] A Google Maps link pasted in the field (alone or as the Maps app shares it) becomes the item's Maps link, not a search (`links/SharedLinks.kt` `inPlaceField`)
- [x] "Use my location" (empty field): asks on this tap only, "Finding your location…", named by reverse geocoding ("A point on the map" offline); "Couldn't find where you are…" / "No access to your location…" (`ui/trip/PlanItemSheet.kt` `UseMyLocation`; **iPhone API**: Core Location, CLGeocoder)
- [x] A chosen place: its address (or "A point on the map") and "Google Maps link"; long press copies; X removes place and link (`ui/trip/PlanItemSheet.kt` `PlaceField`)

### Website and link preview
- [x] "Website" ("A site, like example.com"): a bare domain gets https://, X removes it, "Use a web address, like example.com"; a Maps link typed here moves to the place (`links/LinkUrls.kt` `normalize`)
- [x] "Paste a link" on a new empty item reads the clipboard on that tap (a Maps link → place, others → Website); "No link to paste. Copy one first." (`ui/trip/PlanItemSheet.kt` `paste`; **iPhone API**: UIPasteboard, PasteButton)
- [x] Link preview on top: 16:9 picture, favicon, page name, domain; a tap opens it, long press copies; a compact row without a picture; "No app here can open this link." (`ui/trip/LinkViews.kt` `LinkPreviewCard`; **network**)
- [x] No link, or a site without a picture: the place's Wikipedia photo with its credit; a tap opens the article (`ui/trip/LinkViews.kt` `PlacePhotoCard`; **network**)
- [x] A link fills only what's empty: a site's title → title; a Maps link → pin, the place's name and, on a new item, Food or Stay by the name; a typed title always stays (`links/LinkFill.kt`; `links/LinkPlaces.kt`)
- [x] A short Maps link without a pin is resolved after the sheet closes and fills the place (`data/repo/Repositories.kt` `fillFromLink`; **network**)

### Getting there and tags
- [x] "Getting there" on an item reached from another place: "From Bariloche · ≈ 2 h", six tiles (Car, Train, Plane, Ferry, Bike, Walk) that save on tap, "Count in km" (`ui/trip/TravelPicker.kt` `GettingThere`; `ui/trip/PlanItemActions.kt` `gettingThereFrom`)
- [x] Tags: "#tag" chips (a tap takes one off), "Add a tag, like sushi or hiking" with up to 6 suggestions ("Food & Drink › Italian") and "Your own tag"; 8 tags of 30 characters at most (`ui/trip/ItemTagsField.kt`; **engine**: tags/ItemTags.kt, tags/TagTree.kt)
- [x] "Browse tags" / "Hide the list": category, subcategory, tag chips (`ui/trip/ItemTagsField.kt` `AddTag`; asset `tags/tags.json`)

### Calendar, Google Maps, journal, renew
- [x] Linked trip: "In Personal. Plan changes update it."; otherwise "Add to calendar" opens a new calendar event filled in, no permission; "No calendar app on this phone." (`calendar/CalendarIntents.kt` `insert`; **iPhone API**: EventKitUI EKEventEditViewController)
- [x] "…To keep all plan steps, sync calendar." opens the Calendar sheet (`ui/trip/PlanItemSheet.kt` `SyncCalendarLine`)
- [x] "Open in Google Maps": the Maps link, else the pin, else the name; hidden for a Note without a link; "No maps app or browser on this phone." (`links/MapsLinks.kt` `searchUrl`; **iPhone API**: UIApplication.open)
- [x] "Journal · N notes": notes named after the item or written within 100 m; "Nothing written about it yet."; a tap opens the note (`ui/trip/ItemNotes.kt` `ItemNotes.of`)
- [x] "Write": saves, closes and opens a new note placed "Alhambra (Granada)" at the item's pin, dated the item's day when that day is past (`ui/trip/ItemNotes.kt` `newEntry`; `ui/nav/AppActions.kt` `writeAbout`)
- [x] "Renew details" (items with a link): "Reading the links…", then "Details renewed." / "Nothing new." / "Couldn't read the links…"; a typed title stays (`links/LinkRenew.kt`; **network**)

### Sharing an item
- [x] Share sends text: name, date and time, end or nights, address, "Website:", "Map:", "Getting there:", "Note:", "#tags"; never the tick or the journal (`ui/trip/PlanItemText.kt`; `ui/trip/PlanItemActions.kt` `shareItem`; **iPhone API**: share sheet)

## 8. Places and place pages

### Search and naming
- [x] Photon search (OpenStreetMap, no key): 8 results, English/German/French names when the phone uses them, else local; duplicates within 1 km dropped (`places/PhotonPlaceSearch.kt`; `places/PhotonParser.kt`; **engine**: places/PlaceSearch.kt; **network**)
- [x] Kind guessed from the place: restaurant, café, bar → Food; hotel → Stay; station, airport → Getting there; park, zoo → Activity; sights → Place (`places/PhotonParser.kt` `kindOf`)
- [x] Area lines "Halki, Naxos, Greece" without admin words ("Naxos Regional Unit" → "Naxos") (`places/PlaceNames.kt` `compact`, `area`)
- [x] Reverse geocoding: the phone's geocoder (8 s), else Photon (`places/PhotonPlaceSearch.kt` `reverse`; **iPhone API**: CLGeocoder; **network**)

### Google Maps links and lists
- [x] Recognised links: google.<country>/maps, maps.google.*, maps.app.goo.gl, goo.gl/maps; pin from the place data, @camera or q=; name from /place/ or q= (`links/MapsLinks.kt` `parse`)
- [x] Short links followed one redirect at a time with the app's own User-Agent (`links/LinkFetcher.kt` `expandMaps`; **network**)
- [x] A Maps link with a name and no pin is searched by name, then looser forms, only near the trip (1,000 km) (`places/LooseQueries.kt`; `links/LinkPlaces.kt` `choose`; **engine**: places/NearTrip.kt; **network**)
- [x] A shared Google Maps list is read without sign-in, up to 500 places, each an idea with pin, address, note and Maps link; "The list is longer: these are its first N."; "This Google Maps list couldn't be read…" / "…is one place, not a list…" (`links/MapsLists.kt`; `links/LinkFetcher.kt` `mapsList`; **network**)

### Link previews and Wikipedia photos
- [x] Previews only for rows on screen, 3 at a time, kept on the phone (400), looked at again after 30 days / 7 days / 1 day / 15 min; a failed look keeps the old one (`links/LinkPreviews.kt`; `links/LinkPreview.kt` `LinkPolicy`; **network**)
- [x] A site's preview from og:image / twitter:image, og:title or the page title, og:site_name (first 512 KB); a link to an image is the picture; favicon from Google's favicon service (`links/LinkFetcher.kt`; `links/MetaTags.kt`; `links/LinkUrls.kt` `favicon`; **network**)
- [x] Google Maps pages never scraped: name and pin from the link, picture from Wikipedia near the pin (`links/LinkPreviews.kt` `resolveMaps`; **network**)
- [x] Which Wikipedia photo: an article named like the item nearby (1.5 km landmark, 5 km airport, 20 km town, 50 km area), else the nearest with a photo within 200 m; real photos only (`links/PlacePhotos.kt`; `links/WikiPhotos.kt`; **network**)
- [x] Only reusable licences with an author; "Photo: Jane Doe, CC BY-SA 4.0 · Wikipedia" on large pictures, a tap opens the file page (`links/WikiCredits.kt`; **network**)
- [x] Pictures fade in from a 40 MB disk cache; every request names the app in its User-Agent (`links/LinkPreviews.kt` `imageLoader`; `links/LinkFetcher.kt` `userAgent`)

### Place page
- [x] Opens from a day's title or the map (a stop or an item); "No place here" / "This item has no location in the plan yet." (`ui/place/PlaceScreen.kt`)
- [x] Hero per theme: Postcard's tilted postcard and postmark, Trail's full-bleed scene, Sticker Book's polaroid and washi tape, Sketchbook's hand-drawn map and sticky note, Market's torn poster and stamp (`ui/place/PlaceHero.kt`; `ui/place/PlaceHeroStyle.kt`)
- [x] Hero picture: your newest photo within 40 km, else a drawn picture (`ui/place/PlaceHero.kt` `PlacePicture`; `ui/place/PlaceModel.kt` `photosNear`)
- [x] Name from the stop, the area, the plan's arrival, the geocoder, else "This place"; long press copies (`ui/place/PlaceScreen.kt` `PlaceContent`; **iPhone API**: CLGeocoder)
- [x] Flag, country and the days (from the stop, an offline lookup within 60 km, or the geocoder) (`ui/place/PlaceHero.kt` `CountryLine`)
- [x] Back (DEMO tag on examples) and Share: name and country, "Days 13–16 of <trip>", up to 6 sights (`ui/place/PlaceScreen.kt` `TopButtons`; **iPhone API**: share sheet)
- [x] "How long · in your plan": 2 days · 3–4 days · 5+ days with the plan's marked, and "4 days in your plan · 13–16 Mar 2027 · 3 places" (`ui/place/PlaceScreen.kt` `HowLong`, `PlanBlock`)
- [x] Sections that open and close: Getting there (open, with vehicle tiles and Count in km), Where to stay ("No stay in the plan yet."), Notes (`ui/place/PlaceScreen.kt` `Sections`)
- [x] "In your plan": items within 40 km with tick circles and "Day 2 · 10:00 · note"; a tap opens the item sheet, long press copies (`ui/place/PlaceScreen.kt` `InPlan`; `ui/place/PlaceModel.kt` `PlaceGrouping`)
- [x] Floating "Add a place" (the place's last day), or "Add to plan" for a stop not in the plan ("Added to the plan") (`ui/place/PlaceScreen.kt` `PlaceContent`)
- [x] A stop's red Remove: "Remove this stop?… The recorded route stays." (`ui/place/PlaceScreen.kt`)

## 9. Maps and routes

### Map tab
- [x] "Map" with a Globe · Map switch; the last choice kept (Globe by default) and restored from backups (`ui/map/MapScreen.kt` `MapHome`)
- [x] Eyebrow "Since <year>" on Globe; on Map the current trip's name or "N trips · X km" (`ui/map/MapScreen.kt` `MapHome`)
- [x] During a trip, Map opens on today's trip; "All trips" / "Today's trip" switches (`ui/map/MapScreen.kt` `MapHome`)
- [x] All trips: every trip's route, stops and located notes; a route opens that trip's Map, a stop its place page, a note the note (`ui/map/MapScreen.kt` `AllTripsMap`; **iPhone API**: MapLibre iOS; **network**)
- [x] "Show everything" fits it all again; "No routes yet" / "Your trips' routes, stops and places show up here." (`ui/map/MapScreen.kt` `AllTripsMap`)

### Trip map
- [x] "Travelled · Planned" pill (`ui/map/TripMapBoard.kt` `MapSegmented`)
- [x] Travelled: the recorded route solid, gaps (flights, no signal) dashed, named stops, note pins, a ring at "now", the planned route dashed beneath; with no track, the stops joined in time order (`ui/map/TripMapBoard.kt`; `ui/map/MapData.kt`; `tracking/RouteShape.kt`)
- [x] Planned: the plan's places as flag pins and the dashed route in plan order (`clip/PlannedReplay.kt`; **engine**: clip/PlanRoute.kt)
- [x] Distance chip: "18.4 km today · 3 stops" during the trip, the whole trip's otherwise, "3 stops" with no points, a dot while recording (`ui/map/TripMapBoard.kt` `MapNote`)
- [x] Taps: a stop or plan pin opens its place page, a note pin the note (`ui/map/TripMapBoard.kt`)
- [x] "Show the whole trip"; switching Travelled / Planned refits (`ui/map/TripMapBoard.kt`)
- [x] "Map style": Street · Terrain · Earth, kept on this phone for every trip map, Street by default (`clip/BaseMap.kt` `MapStylePrefs`; `clip/MapStyles.kt`)
- [x] "Route tracking" button (dated trips not yet over): the trip's tracking sheet (`ui/map/TripMapBoard.kt`)
- [x] Opening a map turns the trip's points into stops and countries (`tracking/TrackProcessor.kt` `process`)

### Leg sheet
- [x] "Day 13 · Leg 9 of 21" and "El Calafate → El Chaltén" (towns; items when the trip stays in one place); the title opens the destination's place page (`ui/map/LegSheetModel.kt`; `ui/map/TripMapBoard.kt` `LegSheet`)
- [x] "About 3 hours by road" / "About 20 minutes on foot" from the leg's vehicle (`ui/map/LegSheetModel.kt` `note`; **engine**: model/TravelTime.kt)
- [x] "Previous leg" / "Next leg", opening on the leg reached by today (`ui/map/LegSheetModel.kt` `currentIndex`)
- [x] "Play the route clip" opens the trip video (`ui/map/TripMapBoard.kt` `LegSheet`)
- [x] "No planned route yet" / "Places in the plan with a location draw the route here." (`ui/map/TripMapBoard.kt`)
- [x] The map keeps clear of the sheet and refits when it grows, unless you moved it (`ui/map/BtMap.kt` `setPadding`)

### Every map
- [x] OpenFreeMap vector tiles, no key: positron on light themes, dark on Trail; land, water, park and label colours from the theme (`ui/map/BtMap.kt` `recolour`; `clip/MapStyles.kt` `StreetTiles`; **iPhone API**: MapLibre iOS or maplibre-compose; **network**)
- [x] Terrain: hill shading from Mapzen terrain tiles, darker on Trail (`clip/MapStyles.kt` `MapStyleJson.terrain`; **network**)
- [x] Earth: Sentinel-2 cloudless 2016 with white names and borders, zoom capped (`clip/MapStyles.kt` `MapStyleJson.earth`; **network**)
- [x] Terrain and Earth styles fetched once and cached on disk for offline (`clip/BaseMap.kt` `MapStyleLoader`; **network**)
- [x] Attribution "i" button; no logo, compass or tilt; Terrain and Earth credits with links (`ui/map/BtMap.kt`; the map turns with two fingers as Android's, and its taps and fits follow the turn, since the second parity round)
- [x] Pins: a stop is a location pin with a tick, a plan place a flag pin, a note an ink pin; taps reach 16 dp (`ui/map/BtMap.kt` `placePin`, `tap`)
- [x] Fit: zoom 15 at most; one point shows about 1.5 km (`ui/map/BtMap.kt` `fit`)
- [x] "Loading map"; first open offline: "Map unavailable · showing saved places" with Try again, drawn on plain land; "Map not available" when the map can't start (`ui/map/BtMap.kt` `MapLoadState`, `offlineStyle`)

## 10. The journal

### A trip's Journal
- [x] Notes newest first, by the note's time (`ui/journal/TripTabs.kt` `TripJournalTab`)
- [x] Row: the day large over the month in capitals, the title (else "Sat 19 Jun"), two lines of text, the place (`ui/journal/EntryRow.kt` `EntryRow`)
- [x] Row text: the first 160 or so characters, with "• ", "1. ", "[ ]" / "[x]", captions and places (`doc/DocRender.kt` `preview`)
- [x] Row meta line "Naxos · 27° · 3 photos, 1 video" (`ui/journal/JournalLogic.kt` `meta`, `counts`)
- [x] Row thumbnail in a leaning frame, alternating: the first layout drawn small, else the first photo (a video's first frame with a play mark), else a drawn picture of the place (`ui/journal/EntryRow.kt`; `ui/journal/DocAlbum.kt` `AlbumMini`)
- [x] A tap opens the note (`ui/journal/TripTabs.kt`)
- [x] Empty: "Nothing written for this trip" / "Write a line about today. It can be short." with Write (`ui/journal/TripTabs.kt`)
- [x] Write opens a new note in the trip (`ui/journal/TripTabs.kt`)

### Notes outside trips
- [x] "Notes outside trips" ("Written when no trip was on."), rows as in a trip's Journal, newest first; "No notes outside trips" / "Notes written when no trip is on show up here."; no Write button (`ui/journal/LooseNotesScreen.kt`; iPhone `ios/Journal.kt`, its own Write gone in the parity round)
- [ ] A note written when no trip is on today becomes a note outside trips (`ui/editor/WriteViewModel.kt` `load`)

### Reading a note
- [x] Top bar "Journal entry" with back, and "Edit" on your own notes (`ui/journal/EntryScreen.kt`)
- [x] Header "Sat 19 Jun 2027 · 13:10 · Halki, Naxos", in the note's own time zone (`ui/journal/EntryScreen.kt` `header`)
- [x] "· 27° sunny" in the header when the note has weather (`ui/journal/EntryScreen.kt` `header`)
- [x] Long press on the header copies the place (`ui/components/CopyText.kt`; **iPhone API**: UIPasteboard)
- [x] Title and text can be selected and copied (`ui/journal/EntryScreen.kt`)
- [x] Text in the note's own font; Caveat drawn larger (`ui/journal/DocView.kt` `docTextStyle`)
- [x] Another member's note has no Edit and no Delete (`ui/journal/EntryScreen.kt`)
- [ ] "By <name>" / "By a friend" on another member's note; its checklists can't be ticked (`ui/journal/EntryScreen.kt`)
- [x] Bold, italic, underline, strikethrough, sizes, colours and highlight; a colour follows its role in the current theme (`ui/journal/DocView.kt` `annotated`; `ui/journal/HtmlRuns.kt`; **engine**: doc/RichHtml.kt)
- [x] Links open in the browser (http, https, mailto, geo only) (`ui/journal/HtmlRuns.kt` `isSafeUrl`; **iPhone API**: UIApplication.open)
- [x] Headings in 3 sizes, quotes in italic with a bar, bullet and numbered lists, lines, a "New printed page" marker (`ui/journal/DocView.kt` `DocBlock`, `DocList`)
- [x] Checklists: ticking while reading saves at once (own notes); ticked items struck through (`ui/journal/EntryScreen.kt` `toggleCheck`)
- [x] Place chip: a tap opens the maps app at its pin, or searches the name (`ui/journal/DocView.kt` `PlaceChip`; **iPhone API**: maps URL)
- [x] A single photo or video at its width, position and angle, with its frame (None, Polaroid, Tape, Rounded) and caption; Polaroid writes the caption on the paper (`ui/journal/DocView.kt` `DocPhoto`; `doc/PhotoPlacement.kt`)
- [x] A picture keeps its own shape between 2× taller and 4× wider, else fitted (`ui/journal/DocView.kt` `rememberAspect`; **iPhone API**: ImageIO)
- [x] Layouts as in print: each photo cropped to its spot with its frame and tape (`ui/journal/DocAlbum.kt` `AlbumView`; **engine**: doc/PhotoAlbums.kt, album/AlbumLayouts.kt)
- [x] A tap on a video plays it full screen; one from a shared trip not here yet: "This video isn't on this phone yet. Try again in a moment." (`ui/journal/DocView.kt` `PlayVideo`)
- [x] Parts from a newer app version aren't shown, and are kept (`ui/journal/DocView.kt` Block.Unknown; **engine**: doc/EntryDoc.kt)
- [x] Delete (own notes), full width and red: "Delete this note?… Its photos and videos go too. You can't undo this." (`ui/journal/EntryScreen.kt`)
- [x] Deleting a note also deletes the app's copies of its photos and videos (`data/Deletions.kt` `deleteEntry`)

### Video player
- [x] Full screen on black with sound, plays at once; a tap pauses or plays; Play/Pause, seek bar, "0:12 / 0:45", Close; "This video can't be played." (`ui/community/MediaViewer.kt` `ClipViewer`; **iPhone API**: AVPlayer; iPhone: Android's controls in Compose over an AVPlayerLayer)

## 11. The note editor, photos and videos

### Screen
- [x] "Edit" on your own note opens it in Write (`ui/journal/EntryScreen.kt`)
- [x] Top bar: back, "SAT 19 JUN · NAXOS", round Done (`ui/editor/WriteScreen.kt` `WriteTopBar`)
- [x] The top line adds the temperature when known and opens Entry details ("Change date and place") (`ui/editor/WriteScreen.kt` `WriteTopBar`)
- [x] Title in the display face, "Title", 4 lines, Enter jumps to the text, capitals on themes with upper-case titles (`ui/editor/EditorBlocks.kt` `TitleField`)
- [x] Text with "Start writing"; a tap in the space under the text goes on writing (`ui/editor/EditorBlocks.kt` `RichArea`; `ui/editor/WriteScreen.kt`)
- [x] A new note opens with the cursor in the text (`ui/editor/WriteViewModel.kt` `load`)
- [x] The page stays above the keyboard (`ui/editor/WriteScreen.kt`)
- [ ] Media tab "Add media": a new note with the photo picker open (`ui/trip/TripMedia.kt`; `ui/editor/WriteScreen.kt` `attachPhotos`)

### Toolbar
- [x] Font row: Default, Albert Sans, Literata, Caveat, Plex Mono, Barlow, each in its face; sets the whole note's font (`ui/editor/EditorToolbar.kt` `FontChip`; `ui/theme/Fonts.kt` `journalFonts`)
- [x] Tool row: Aa · B · Lists · Take a photo · Choose photos and videos · Record a video · Add place · Mic, staying above the keyboard (`ui/editor/EditorToolbar.kt`)
- [x] B toggles bold, lit when the text at the cursor is bold; Aa and B only in a text or quote (`ui/editor/EditorToolbar.kt`; compose-rich-editor has an iPhone build)
- [x] Aa: Heading, Subheading, Normal text, Larger (1.3×), Smaller (0.85×), Italic, Underline, Strikethrough, Highlight (`ui/editor/EditorToolbar.kt` `TextSizeButton`)
- [x] Aa colours: Default colour plus the theme's route, danger, accent-ink and muted; the current one again removes it (`ui/editor/EditorToolbar.kt` `Swatch`)
- [x] Lists: Bullets, Numbers (on the paragraph), Checklist (inserts one); Enter on an empty item ends the list (`ui/editor/EditorToolbar.kt` `ListButton`; `ui/editor/WriteViewModel.kt` `newState`)
- [x] Inserts go at the cursor (splitting the text), else after the selected block, else at the end (`ui/editor/WriteViewModel.kt` `insertHere`)

### Blocks and the + menu
- [x] "+" between blocks and at the end ("Insert here"): Text, Photos and videos, Take a photo, Record a video, Checklist, Heading, Quote, Place, Line, Page break (`ui/editor/WriteScreen.kt` `InsertMenu`)
- [x] Quote: italic with a bar, "Quote", X removes (`ui/editor/EditorBlocks.kt` `QuoteItem`)
- [x] Checklist: a box per row, "Item", Enter adds a row, Backspace on an empty row removes it, X on the focused row, "Add item"; ticked rows struck through; empty rows dropped (`ui/editor/EditorBlocks.kt` `ChecklistItem`)
- [x] Line, with X "Remove line" (`ui/editor/EditorBlocks.kt` `DividerItem`)
- [ ] Page break "New printed page", X removes; it starts a new page in print (`ui/editor/EditorBlocks.kt` `PageBreakItem`; `doc/DocRender.kt` `printPages`)
- [x] A part from a newer version: "Part of this entry was made in a newer version of the app. It stays as it is." (`ui/editor/EditorBlocks.kt` `KeptItem`; **engine**: doc/EntryDoc.kt)

### Adding photos and videos
- [x] "Choose photos and videos": the system picker, both together, up to 20, no permission (`ui/editor/WriteScreen.kt` `picker`; **iPhone API**: PHPicker)
- [x] The picked order is kept; one makes a single block, several make layouts of the usual sizes, biggest first (5 → 4 + 1) (`ui/editor/WriteViewModel.kt` `importAll`, `PhotoPlacer`; **engine**: doc/PhotoAlbums.kt `pickGroups`)
- [x] Each photo is saved into the note as it comes in; "One photo couldn't be added." / "N photos couldn't be added." / "This video couldn't be added." (`ui/editor/WriteViewModel.kt` `importAll`)
- [x] "Adding photos…"; another photo tool, Back or Done meanwhile says "Finishing your photos. Please wait a moment." (`ui/editor/WriteViewModel.kt` `busyNotice`)
- [x] New photos take the trip's Photos in notes size and frame (Full + Rounded by default), always straight (`ui/editor/WriteViewModel.kt` `PhotoDefaults.forTrip`; `data/settings/TripPhotos.kt`)
- [x] Adding into a layout fills its empty spots and grows it up to 9; the rest start a new layout with its most common frame (`ui/editor/WriteViewModel.kt` `PhotoPlacer.put`; **engine**: doc/PhotoAlbums.kt `add`, `mainFrame`)
- [x] Photos kept as full-size copies in the app's own storage, in their own format (jpg, png, webp, heic) (`data/repo/Repositories.kt` `MediaRepository.import`; `data/repo/PhotoFiles.kt`; **iPhone API**: PHPicker's current representation, ImageIO to read and draw a HEIC)
- [x] Capture time with its offset, GPS point, size and rotation read on import (`data/repo/PhotoTime.kt`; **iPhone API**: ImageIO)
- [x] "Use photo time · 14:05" (or "Use video time") under the title of a new note when the first photo's time is over a minute off; a tap moves the note's day and time (`ui/editor/WriteViewModel.kt` `usePhotoTime`)
- [x] "Take a photo": the camera; the shot goes into the note, not the gallery; "No camera app on this phone." (`ui/editor/TakePhoto.kt` `rememberCameraShot`; **iPhone API**: UIImagePickerController, camera purpose string; **owner**: camera check on a real iPhone)
- [x] "Record a video": the camera with no length cut (30 minutes on iPhone); closing adds nothing (`ui/editor/TakeVideo.kt`; **iPhone API**: UIImagePickerController movie mode, microphone purpose string; **owner**: camera check on a real iPhone)
- [ ] A pick, shot or recording that returns while the note reloads goes in once it's loaded (`ui/editor/WriteScreen.kt` `pendingPhotos`, `pendingShot`)

### Videos
- [x] Every video written again as MP4: H.264, short side 720 px at most, about 3 Mbit/s (less for long ones, never under 1), AAC 128 kbit/s, HDR to normal colour (`data/repo/JournalVideos.kt` `prepare`, `transcode`; `data/repo/MediaTypes.kt` `VideoLimits`; **iPhone API**: AVAssetReader with a video composition (Rec. 709), AVAssetWriter)
- [x] "This video is too long. Videos can be up to 30 minutes." / "This video is too big to add." (over 256 MB) (`data/repo/MediaTypes.kt` `VideoLimits`)
- [x] Can't compress: the original goes in up to 240 MB, else "This video couldn't be prepared on this phone."; "This video couldn't be opened." (`data/repo/JournalVideos.kt` `copy`)
- [x] "Preparing the video… 40%" with X to stop (nothing left behind); the screen stays on (`ui/editor/WriteViewModel.kt` `cancelVideo`; **iPhone API**: isIdleTimerDisabled, beginBackgroundTask)
- [x] Leaving meanwhile asks "Stop adding the video?" / "It isn't in the note yet. Wait, or stop and leave." with Stop (red) / Wait (`ui/editor/WriteScreen.kt` `stopVideoAsk`)
- [ ] When the note goes to Drive (backups on, or a shared trip): "Videos take a while to upload." / "This video is N min long. It will take a while to upload." (`ui/journal/JournalLogic.kt` `videoAdded`; in since round 23 when backups are on; a shared trip's notes come with round 26)
- [x] A video's time and place from its own tags (MP4 date, ISO 6709 location) (`data/repo/MediaTypes.kt` `VideoTags`; **iPhone API**: AVFoundation metadata)
- [x] Notices in the status line close with X (`ui/editor/EditorToolbar.kt` `StatusLine`)

### Editing a photo
- [x] Tap selects it (ring, handles, controls card); tap again when done (`ui/editor/PhotoEditor.kt` `PhotoItem`)
- [x] Turn pill "⟳ −4°": drag around the photo, −15° to 15° in whole degrees (photos only) (`ui/editor/PhotoEditor.kt` `RotateHandle`)
- [x] "Move" handle: drag between blocks (a line marks the spot, the page scrolls at the edges) and sideways across the column (`ui/editor/PhotoEditor.kt` `MoveHandle`; `ui/editor/PhotoDragState.kt`; `doc/PhotoPlacement.kt`)
- [x] Resize handle: 30–100 % of the column (`ui/editor/PhotoEditor.kt` `ResizeHandle`)
- [x] Angle slider and a degrees chip that straightens (photos); Play (videos) (`ui/editor/PhotoEditor.kt` `PhotoControls`)
- [x] Size S / M / L / Full, Frame None / Polaroid / Tape / Rounded, Align left / centre / right (`ui/editor/PhotoEditor.kt` `PhotoControls`, `FrameChips`)
- [x] Caption, one line, up to 3 lines shown (`ui/editor/PhotoEditor.kt` `CaptionField`)
- [x] Move up, Move down (a paragraph at a time past text), Remove photo (red, no confirm) (`ui/editor/PhotoEditor.kt`; `ui/editor/WriteViewModel.kt` `move`)
- [x] A removed photo's file goes when nothing else in the note shows it (`ui/editor/WriteViewModel.kt` `deleteIfUnused`)

### Layouts
- [x] A layout shows its photos, empty spots as "+" and a "Layout" pill; a tap opens "Layout · 3 photos and 1 video" (`ui/editor/AlbumSheet.kt` `AlbumItem`)
- [x] Swap: tap one, then another (`ui/editor/AlbumSheet.kt`; **engine**: doc/PhotoAlbums.kt `swap`)
- [x] With one selected: Play (video), Earlier, Later, Remove (red, no confirm) (**engine**: doc/PhotoAlbums.kt `move`, `remove`)
- [x] Arrangements by size: 2 Side by side / Stacked / Big and small; 3 Big on top / Big below / Big beside / Stacked; 4 Grid / Big and three / Wide and tall; 6 Two across / Three across / Big and five; 9 Grid / Big and eight (`ui/editor/AlbumSheet.kt` `LayoutTile`; **engine**: album/AlbumLayouts.kt)
- [x] Frame chips: "Frame for all", or for the selected photo or video (`ui/editor/WriteViewModel.kt` `albumFrame`)
- [x] "Add photos or videos" into the layout (`ui/editor/AlbumSheet.kt`)
- [x] "Make them single photos" splits it in order (`ui/editor/WriteViewModel.kt` `albumToSingles`)
- [x] Move layout up / down; "Remove layout and its photos" asks first (`ui/editor/AlbumSheet.kt`)
- [x] After a removal the layout refits; with one left it becomes a single photo, with none it goes (**engine**: doc/PhotoAlbums.kt `reflow`)

### Entry details
- [x] "Entry details", When: date and time pickers (the phone's 12/24-hour setting); a new date keeps the time; the photo-time chip here too (`ui/editor/EntryDetailsSheet.kt`; `ui/editor/WriteViewModel.kt` `setDate`, `setTime`)
- [x] "No place" ("This note stays off the map") clears the place and stops automatic filling (`ui/editor/WriteViewModel.kt` `pickNoPlace`)
- [x] "Place, or search the map": after 3 letters, up to 5 "On the map" results near the note's pin or the last route point (`ui/editor/EntryDetailsSheet.kt` `FoundPlaces`; **network**)
- [x] "From this trip" chips: the day's items ("Alhambra (Granada)"), their towns, earlier notes' places, stops, then the rest of the plan; typing filters (`ui/editor/EntryDetailsSheet.kt` `KnownPlaces`; `places/TripPlaces.kt`)
- [x] "Use my location": asks on this tap, where the phone is, else the last route point, named by the geocoder; "Couldn't find where you are…", "Location isn't allowed…", "Couldn't name this place…" (`ui/editor/EntryDetailsSheet.kt` `UseMyLocation`; `ui/editor/Places.kt` `reverse`; **iPhone API**: Core Location, CLGeocoder)
- [x] A new note's place fills itself from the last route point under 12 h old, never asking (`ui/editor/WriteViewModel.kt` `fillPlace`)

### Place chips in the text
- [x] "Add place": a chip with the note's place, else the Place dialog (name, "From this trip", Save / Cancel) (`ui/editor/WriteScreen.kt` `onPlace`; `ui/editor/EntryDetailsSheet.kt` `PlaceDialog`)
- [x] A tap on a chip renames or Removes it; X beside it (`ui/editor/EditorBlocks.kt` `PlaceItem`)

### Saving
- [x] Saves by itself a moment after the last change and on leaving; no Save button (`ui/editor/WriteViewModel.kt` `autosave`, `finish`)
- [x] Back and Done both save and close (`ui/editor/WriteScreen.kt` `leave`)
- [x] A new note is kept once it has a title or text; left empty, it's dropped (`ui/editor/WriteViewModel.kt` `persist`)
- [x] Also saves when the app goes to the background (`ui/editor/WriteViewModel.kt` `flush`; iPhone `ios/Write.kt` on ON_STOP)
- [x] A note being written survives the app being stopped and reopens where it was (`ui/editor/WriteViewModel.kt` `KEY_ID`; iPhone: the note's id in the screens kept with the window, `ios/AppStack.kt`)

## 12. Check-ins

- [x] "Today's check-in" card on top of a trip's Journal: the trip is on, today's plan has places, you haven't written in it today; once per trip and day on this phone (`ui/journal/DailyJournalCheckIn.kt`; `checkin/JournalCheckIns.kt` `claim`)
- [x] Shown once counts as used; kept 60 days on the phone only, not in backups (`checkin/CheckInStore.kt`)
- [x] "At <place>?" with "From today's plan · <trip>", or "A recent location suggests you're nearby." (last route point at most 5 min old, 100 m accurate, within 300 m) (`checkin/DailyCheckIn.kt` `nearby`)
- [ ] "Yes, write" ("Opening…"): today's check-in note with the place and a place chip, or the same one again; one per member in a shared trip (`checkin/JournalCheckIns.kt` `confirm`; **engine**: doc/EntryDoc.kt)
- [x] A check-in note left unwritten stays, with its place chip (**engine**: doc/EntryDoc.kt `isEmpty`)
- [x] X "Skip today's check-in" hides it for today (`ui/journal/DailyJournalCheckIn.kt`)
- [x] "Another place": "Where are you today?…", today's places as radio rows, "Skip today" (`ui/journal/DailyJournalCheckIn.kt`)
- [x] "Use current location": asks on the tap, one fresh fix (about 12 s), "Finding your location…", "No reliable match nearby…", "Location is optional…" (`checkin/CheckInLocation.kt`; **iPhone API**: Core Location; **owner**: accuracy check on a real iPhone)
- [x] Errors: "A new day has started…", "This check-in was removed…", "This trip is no longer available.", "Today's plan changed…", "Couldn't open the note…" (`ui/journal/DailyJournalCheckIn.kt` `checkInRecoveryMessages`)
- [x] The card's day turns at midnight while the screen is open (`ui/journal/DailyJournalCheckIn.kt`)

## 13. Dictation

- [x] Mic at the end of the editor's toolbar, Stop in the route colour while listening (`ui/editor/EditorToolbar.kt`; **iPhone API**: Speech, AVAudioSession)
- [x] First use: "Dictation uses the microphone only while the mic button is on." Continue → the system prompts; Not now (`ui/editor/WriteScreen.kt` `micAsk`; iPhone: Speech and Microphone purpose strings)
- [x] Refused for good: "The microphone is off for this app. You can turn it on in Settings." with Open Settings / Cancel (`ui/editor/WriteScreen.kt` `micBlocked`)
- [x] Listening pill above the toolbar: level bars and "Listening · Greek"; a tap stops (`ui/editor/EditorToolbar.kt` `LevelBars`)
- [x] Words appear as you speak, corrected live and fixed at each pause, at the cursor of the text or quote you're in, else at the end; moving the cursor moves the next phrase (`speech/DictationText.kt` `DictationMerger`; `ui/editor/WriteViewModel.kt` `onDictated`)
- [x] Capitals at sentence, paragraph and bullet starts (Greek accents kept), spaces fixed, none before punctuation; punctuation from the recognizer where it gives it (`speech/DictationText.kt` `format`; **iPhone API**: addsPunctuation)
- [x] The language from Me › Voice notes (`speech/Dictation.kt`; `ui/me/VoiceSheet.kt`)
- [x] On-device recognition first; online for a language without an offline pack (`speech/Dictation.kt` `ensureRecognizer`; **network**; **owner**: Greek on a real iPhone)
- [x] Pauses don't stop it: it listens again after each, and stops after 3 minutes of silence ("Stopped after a long pause.") (`speech/Dictation.kt` `restart`)
- [x] Recovery: retries with growing waits, stops after 5 in a row ("Dictation stopped. Try again."); a busy mic stops after 3 ("The microphone is busy or not working.") (`speech/DictationRecovery.kt`)
- [x] Errors in red with X: "Allow the microphone to dictate.", "Dictation isn't available on this phone.", "Dictation couldn't start. Try again.", "<Language> isn't available…", "No internet, and offline dictation isn't ready for <Language>." (`speech/Dictation.kt`)
- [x] Stops when the app goes to the background or you leave; late words aren't added (`ui/editor/WriteScreen.kt`)
- [x] Opened by the Voice widget, the Voice note shortcut or the Now card's mic, dictation starts by itself once (`ui/editor/WriteScreen.kt` `autoDictateDone`)

## 14. Calendar tab

- [x] Header: weekday and date, "Calendar", DEMO tag in demo mode (`ui/tripcal/TripsCalendarScreen.kt`)
- [x] Empty: "No trips on the calendar yet." with New trip; demo: "No example trips on the calendar." with Exit demo (`ui/tripcal/TripsCalendarScreen.kt` `EmptyCalendar`)
- [x] Month · Year switch, opening on this month (`ui/tripcal/TripsCalendarScreen.kt`)
- [x] "November 2026" / "2026", a "Today" chip when away, arrows, and a swipe to page with a slide (`ui/tripcal/TripsCalendarScreen.kt`)
- [x] Month grid: weekday initials, week start per the phone's region, today a filled circle, neighbouring months' days muted (`ui/tripcal/MonthGrid.kt`; `tripcal/TripGrid.kt` `weeks`)
- [x] Each trip a coloured bar: that week's flags (2 on 3+ days, else 1), its name on longer bars, rounded ends; overlaps in up to 3 lanes, "+N" beyond (`ui/tripcal/MonthGrid.kt` `TripBar`; `tripcal/TripGrid.kt` `lanes`; `tripcal/TripSpan.kt`)
- [x] A tap on a day: one trip → its Plan on that day; several → a menu with colour dots; none → "New trip from here" (not in demo mode) (`ui/tripcal/MonthGrid.kt` `DayMenu`; `ui/nav/AppActions.kt` `newTripFrom`)
- [x] Year grid: 12 small months three across with trip bands, this month bold, today ringed; a month opens it (`ui/tripcal/YearGrid.kt`)
- [x] Trip colours: 6 per theme, fixed per trip, changed only when touching trips would share one; bar text in the readable ink (`tripcal/TripColors.kt`; `ui/tripcal/TripPalette.kt`)
- [x] Overlaps in red: "Kyoto and Lisbon overlap · 3–5 Apr 2027", up to 3, then "N more overlaps"; a hand-over day isn't one (`ui/tripcal/OverlapLines.kt`; `tripcal/TripAnalytics.kt`)
- [x] "No dates yet": colour dot, name, flags; a tap opens the trip, "Add dates" opens Edit trip (`ui/tripcal/TripsCalendarScreen.kt` `UndatedRow`)
- [x] A start with no end shows as one day; trips capped at 10 years (`tripcal/TripSpan.kt` `split`)

## 15. The phone's calendar

### Calendar sheet (trip ⋮ › Calendar)
- [x] Not linked: "The trip as an all-day event, and each planned item on its day." / "Stays up to date…" and "Add to calendar"; "Nothing to add yet…" (`ui/calendar/CalendarSheet.kt`)
- [x] Access asked only here, after one line, with Allow, "Share .ics file instead" and Back (`ui/calendar/CalendarSheet.kt` Step.Ask; **iPhone API**: EventKit full access)
- [x] Refused: "No calendar access." with Share .ics file, then Try again or Open settings (`ui/calendar/CalendarSheet.kt` Step.Denied)
- [x] "Choose a calendar": writable, visible calendars by account with colour dots; holidays, birthdays and week numbers left out; the linked or main one picked (`calendar/CalendarAccounts.kt`; **iPhone API**: EKSource, EKCalendar)
- [x] "Add N events" → "Added to Personal."; "No calendar on this phone takes new events…" (`calendar/CalendarSync.kt` `add`)
- [x] What goes in: the trip all day and free; each item at its time for its length (1 h default) or all day; a stay up to check-out; place, note, website, Maps link and a "BoringTravel link:" line; no ideas, no items that came from a calendar (`calendar/TripEvents.kt`)
- [x] Linked: colour, name, "Google · ana@gmail.com · updated 14 Mar, 10:05", "Change" → "Move to Work" → "Moved to Work." (`ui/calendar/CalendarSheet.kt` `LinkedRow`)
- [x] "Keep calendar updated" switch (`calendar/CalendarSync.kt` `setAutomatic`)
- [x] Status lines: behind, queued, updating, waiting to retry, paused (access, calendar gone, review) (`calendar/CalendarLinks.kt` `PendingCalendarUpdate.label`)
- [x] "Update calendar" → "Personal is up to date." / "Calendar updated: 2 new, 1 changed, 1 removed."; a calendar gone: "…Choose another." (`calendar/CalendarSync.kt` `update`)
- [x] Only the app's own events are touched; one changed elsewhere: "A linked event changed outside BoringTravel…" (`calendar/CalendarDiff.kt`; iPhone: the marker in the notes and `EKEvent.url`)
- [x] "Remove from calendar" (red): Keep / Remove → "Removed 12 events from Personal." (`calendar/CalendarSync.kt` `remove`)
- [x] "Share .ics file" ("…A copy: it doesn't update."): RFC 5545 with time zones, GEO and stable ids, named after the trip (`calendar/IcsWriter.kt`; **iPhone API**: share sheet)
- [x] "Times in Athens · GMT+3" with Change → "Time zone"; guessed from the last pick, the notes' zone, the planned countries, else the phone's (`calendar/TripZone.kt`; iPhone: each country's zones from IANA's zone.tab, bundled, under ICU's names: `ios/CountryZones.kt`)
- [x] Progress bar; "Couldn't add the events. Try again." (`ui/calendar/CalendarSheet.kt` `run`)
- [x] Calendar links and zones stay on this phone, out of backups and shared trips (`calendar/CalendarLinks.kt`)

### Plan tab prompt
- [x] "The calendar is behind the plan." with Update, then "Updating the calendar…", the result for 3 s, or an error; paused → Review opens the sheet (`ui/calendar/CalendarPrompt.kt`)
- [x] Reordering within a day or ticking isn't a change (`calendar/TripEvents.kt` `fingerprint`)

### Import from calendar
- [x] "Import from calendar" (dated trips): events in the trip's dates from every calendar but the app's own, holidays and birthdays; "No events in the trip's dates." (`calendar/CalendarImport.kt`; `calendar/CalendarSync.kt` `importCandidates`)
- [x] Rows: tick, colour, title, "Tue 14 · 10:00 · Getting there · Work"; "In the plan" greyed; obvious ones (flight, hotel, tour, ✈; English and Greek) ticked with a kind (`ui/calendar/CalendarSheet.kt` `ImportRow`; `calendar/CalendarImport.kt` `guessKind`)
- [x] "Add N items to the plan" → "Added 3 items…" / "Nothing new to add."; by time with their end, the note from the description (500 characters), the place from the location; never twice (`calendar/CalendarImport.kt` `toItem`, `fresh`)
- [x] An event from before the trip lands on day 1 untimed; a repeating event gives one item a day; the same event in two calendars shows once (`calendar/CalendarImport.kt` `candidates`)

### In the background
- [x] With Keep calendar updated on, every change to the trip or plan updates the calendar (Android: a job with retries and a 15-minute recovery; iPhone: on save and on return to the app); updates pause when access or the calendar is lost (`calendar/CalendarAutomation.kt`; **iPhone API**: EventKit, BGTaskScheduler)
- [ ] Deleting or leaving a trip removes its events quietly (`calendar/CalendarSync.kt` `removeDeleted`)

## 16. Your world and the globe

### Globe (Map tab › Globe)
- [x] The app's own globe of country outlines (Natural Earth 1:110m, offline) (`ui/world/GlobeView.kt`; `globe/GlobeRenderer.kt`; `globe/GlobeGeometry.kt`; asset `geo/countries.json`)
- [x] Visited countries filled in the highlighter colour; planned and want-to-go ones dashed (`globe/GlobeRenderer.kt` `setCountries`; `globe/GlobePalette.kt`)
- [x] Flag pins: round flag stickers (two letters when there is no flag emoji), most days win, pushed apart with leader lines, names where they fit, fading at the horizon (`globe/FlagBadges.kt`; `globe/PinLayout.kt`; `ui/world/WorldBits.kt` `worldPins`)
- [x] Drag to spin with fling, pinch 0.7–6×, double tap flies home, a tap on a moving globe stops it (`ui/world/GlobeView.kt` `GlobeState`; `globe/GlobeMath.kt`)
- [x] Idle spin (a turn in 72 s), stops on touch and resumes after 6 s; off with a sheet open or with Reduce Motion (`ui/world/GlobeView.kt` `step`; **iPhone API**: isReduceMotionEnabled)
- [x] Home view: the middle of your countries, else the Mediterranean (`ui/world/WorldBits.kt` `worldHome`)
- [x] Taps: a flag opens its country; land opens the country under the finger (small islands within 60 km); the sea does nothing (`ui/world/WorldMode.kt` `onGlobeTap`; `world/WorldSource.kt` `countryAt`; `geo/Countries.kt`)
- [x] Opening a country or continent flies there and lifts the globe above the sheet (`ui/world/WorldMode.kt` `openCountry`)
- [x] "Places I've been" chip; hints "No trips yet · Plan one", "Countries fill in as you travel", "Drag to spin · tap a flag" (`ui/world/WorldMode.kt`)
- [x] Colours, paper shadow, tilted stickers and label font from the theme (`globe/GlobePalette.kt`; `ui/world/WorldBits.kt` `FlagSticker`)
- [x] VoiceOver: "Globe. N countries · X% of the world: <names>." with one action per flag (`ui/world/WorldMode.kt` `globeDescription`; **iPhone API**: UIAccessibility custom actions)

### Your world sheet
- [x] Pull-up sheet: "7 countries · 4% of the world" (or "No countries yet") and countries · continents · days away; a tap expands it (`ui/world/WorldMode.kt`; `ui/world/WorldBits.kt` `countLine`, `WorldStatsStrip`)
- [x] Tiles "N countries" and "X% of the world", out of 195 (`ui/world/YourWorld.kt`; `geo/CountryInfo.kt` `worldPercent`)
- [x] Empty: "No countries yet" / "They fill in from your trips' stops, entries and photos. Add the places you've been before." (`ui/world/YourWorld.kt`)
- [x] "Continents": a bar and count each; a tap turns the globe there (`ui/world/YourWorld.kt` `ContinentBar`)
- [x] "Flags collected": 4 across in first-visit order, then the next trip's new countries dashed ("<name> · next") (`ui/world/YourWorld.kt` `FlagGrid`)
- [x] "Want to go": a row of dashed flags (`ui/world/YourWorld.kt`)
- [x] Totals: trips so far, days away, km once a route was recorded (`ui/world/WorldBits.kt` `TravelTotals`; **engine**: stats/TripStats.kt)
- [x] "Share" (the world card) and "Places I've been" buttons (`ui/world/YourWorld.kt`)

### Country sheet
- [x] Your best photo from there on top (`ui/world/WorldSheets.kt` `CountrySheet`; `world/WorldModel.kt`)
- [x] Flag (dashed if not visited), name in the phone's language, continent link, "first trip in 2019" / "added by you" / "planned" / "on your want-to-go list" (`ui/world/WorldSheets.kt`; `geo/CountryInfo.kt` `name`; **iPhone API**: Locale region names)
- [x] "Want to go" / "Remove from want to go" bookmark for countries not visited (`data/repo/CountryRepository.kt` `setWish`; **engine**: data/db/CountryMark.kt)
- [x] TRIPS · DAYS · PLACES for visited ones (`ui/world/WorldSheets.kt` `Fact`)
- [x] Up to 6 trips: "NOW · DAY 4", "MAR 2024", "NEXT · OCT 2027", "SOMEDAY"; a tap opens the trip (`ui/world/WorldSheets.kt` `TripLine`)
- [x] "Show on the map": Map › all trips framed on the country (`ui/world/WorldSheets.kt`)
- [x] "I've been here" adds it by hand (`data/repo/CountryRepository.kt` `markVisited`)
- [x] "Plan a trip here": a someday trip named after the country with one idea at its pin, opened (`ui/world/WorldActions.kt` `planTripIn`)
- [x] "Remove from my countries", no confirmation; a country from your trips is hidden instead (`data/repo/CountryRepository.kt` `remove`)

### Continent sheet
- [x] Name, "N countries" / "None yet", your countries most days first ("3 trips · 41 days · 12 entries" or "Added by you", a photo), then planned or wished ones dashed; "Tap a country on the globe to add it." (`ui/world/WorldSheets.kt` `ContinentSheet`)
- [x] Seven continents as travellers count them (Russia and Cyprus in Europe; Turkey and the Caucasus in Asia) (`geo/CountryInfo.kt` `continentOf`)

### Places I've been
- [x] Every country and territory in the phone's language, sorted for it (`ui/world/PlacesBeenScreen.kt`; `geo/CountryInfo.kt` `allCodes`; **iPhone API**: Locale, localizedCompare)
- [x] "Search countries": accents ignored, codes match; "No country by that name." (`ui/world/PlacesBeenScreen.kt` `fold`)
- [x] "Been · N" and "All countries", "Tap to add. Press and hold to remove." (`ui/world/PlacesBeenScreen.kt` `CountryLine`)
- [x] Hold under Been: "Remove <name>?" with its note, Remove (red) / Cancel (`ui/world/PlacesBeenScreen.kt`)

### Share your world
- [x] "Share" screen with a 9:16 card in the theme: its paper, the globe on your countries, "N countries", "X% of the world · K continents" (or "Next: <country>"), two rows of flags then "+N", the wordmark (`ui/world/ShareWorldScreen.kt`; `world/ShareCard.kt`)
- [x] "Share" hands the picture to the share sheet; "No app to share with." (`world/ShareCard.kt` `shareIntent`; **iPhone API**: share sheet)
- [x] "Save image" to the photo library: "Saved to Pictures/BoringTravel." / "Couldn't save the image." (`world/ShareCard.kt` `saveToPictures`; **iPhone API**: Photos add-only)

### How countries are counted
- [x] Visited: stops, located notes, located photos and videos, ticked plan items, countries added by hand; removed ones stay out (`world/WorldModel.kt` `build`)
- [x] Planned: located unticked items of trips not over; want to go: bookmarks; both only while not visited (`world/WorldModel.kt`)
- [x] Days per country: each day to that day's country, filled from the last known (**engine**: stats/TripStats.kt `daysAway`)
- [x] "% of the world" out of 195; territories count as countries but not in the % (`geo/CountryInfo.kt` `worldPercent`)
- [x] Updates live as trips, stops, notes, photos, items or marks change (`world/WorldSource.kt`)

## 17. The trip video (clip) and its editor

### Opening
- [ ] Opens from trip ⋮ › Make a clip, the trip map's "Play the route clip" and the composer's "Make a clip" (`ui/trip/TripScreen.kt`; `ui/map/TripMapBoard.kt`; `ui/community/ComposeScreen.kt` `ClipChoices`)
- [x] A dark full-screen studio with dark system bars, whatever the theme (`ui/studio/Studio.kt` `StudioScreen`, read in: its StudioSystemBars turns compat's `SystemBars` light and back; `ui/clip/ClipScreen.kt`)
- [x] "Trip video" / "Opening your trip" while loading (`ui/clip/ClipScreen.kt`)
- [x] "No route yet" / "Add locations to at least two planned places, record a journey, or save two stops…" with "Open trip plan" (`ui/clip/ClipScreen.kt`)
- [x] "Couldn't open this journey" / "Your trip is saved. Try opening the route again." with Try again (`ui/clip/ClipViewModel.kt` `retryLoad`)
- [x] Route sources offered when usable (2 points, 50 m): Trip plan (default), Recorded journey, Saved stops (`clip/PlannedReplay.kt` `ClipSources.available`; **engine**: clip/PlanRoute.kt)
- [x] Planned route: located items in plan order day by day; an item ending on another day is a stop again there; ideas last (`clip/PlannedReplay.kt` `build`; **engine**: model/PlanSpan.kt)
- [x] Only photos and videos on this phone are used; planned places get a country from the bundled outlines (`ui/clip/ClipViewModel.kt` `loadRoute`, `withCountries`)
- [x] The last choices are kept on this phone; first-time defaults 9:16, Auto length, 3D, Globe map, Latin Groove, On the beat, Postcard, Calm, Normal, photos full screen for 3 s, "Day · km" bottom left, tags on (`clip/ClipPrefs.kt`; `clip/ClipDesign.kt`; `clip/music/MusicPrefs.kt`)
- [x] Scene edits kept per trip and per route source; switching source loads that route's edits (`clip/ClipEditStore.kt`; `ui/clip/ClipViewModel.kt` `setSource`)
- [x] Colours, fonts, print border and paper vehicles follow the theme; Trail fetches the dark map (`clip/ClipRenderer.kt` `ClipStyle.of`)

### Top bar
- [x] Close (X), the trip's name, and the size menu: 9:16 "TikTok, Reels and Stories" (1080×1920), 4:5 "Feed post", 1:1 "Square post", 16:9 "Wide, for a screen" (`ui/clip/ClipScreen.kt` `ClipTopBar`; `clip/ClipPlan.kt` `ClipShape`)
- [x] Camera, texts, preview and export follow the size; 9:16 keeps texts out of the bands social apps cover (`clip/ClipDesign.kt` `ClipLayout`)
- [x] Export opens the Export sheet and starts when it can, "N%" while working (`ui/clip/ClipScreen.kt` `ClipTopBar`)

### Preview
- [x] "Preparing your map"; "Your map couldn't load" / "Connect to the internet and try again." with Retry map (`ui/clip/ClipStage.kt`; **network**)
- [x] The frame in the clip's shape; a tap plays or pauses; a full-screen button in the corner (`ui/clip/ClipStage.kt` `StageFrame`)
- [x] "Loading the map · N%" and "Preparing · N%"; play starts once it can play through (`ui/clip/ClipPreview.kt` `PreparingBadge`; `ui/clip/PreviewMath.kt` `canStart`)
- [x] Frames prepared ahead at 15 fps (5 on low memory) from the playhead, live drawing meanwhile; one scene's edit redraws only its frames (`ui/clip/PreviewCache.kt`; `clip/ClipScenes.kt` `dirtyFrames`; **iPhone API**: Skia)
- [x] Loops, holding the end card 0.9 s; pauses when the app leaves (`ui/clip/ClipPreview.kt`)
- [x] The chosen music plays in step with the playhead; voices and video sound only in the export (`ui/clip/MusicPreview.kt`; **iPhone API**: AVAudioPlayer)
- [x] Phone videos play in the preview from prepared small pictures (`clip/ClipVideoFrames.kt`; **iPhone API**: AVAssetImageGenerator)
- [x] "Making the video · N%" over the preview while exporting (`ui/clip/ClipPreview.kt` `MakingOverlay`)
- [x] With a place or photo chosen and the preview still, drag its name or card anywhere (dashed coral ring), one undo step; only Card photos move (`ui/clip/ClipStage.kt`; `clip/ClipEdits.kt` `SceneDrag`)
- [x] Full screen: black, fitted, Play/Pause, scrub bar, "0:12 / 1:05", a tap toggles, Close or Back, same place in the clip (`ui/clip/ClipPreview.kt` `FullScreenPreview`, `PlayControls`)

### Time row and tracks
- [x] "0:18 / 0:42", Play/Pause, Undo, Redo: 100 steps over scene edits and the clip's choices, one per field typed, not leg travel or Make default (`ui/clip/ClipScreen.kt` `TimeRow`; `clip/ClipEdits.kt` `EditHistory`)
- [x] The time row and tools hide while the keyboard is up (`ui/clip/ClipScreen.kt`)
- [x] Five tracks (scenes, photos, texts, music, voice) under a fixed playhead with a time ruler; drag sideways to scrub, pinch to zoom time (`ui/clip/ClipTracks.kt`; `ui/clip/TrackLayout.kt` `TrackScale`)
- [x] Scenes track: Title, places coloured by day ("Day 14" / "El Chaltén · close-up"), videos, day cards, End; close-ups marked yellow (`ui/clip/ClipTracks.kt` `SceneBlock`)
- [x] Photos track: thumbnails and how long each shows, left-out photos dimmed (`ui/clip/ClipTracks.kt` `PhotoBlock`)
- [x] Texts track: the title, each place name (dimmed when hidden), day card lines, the closing line (`ui/clip/ClipTracks.kt` `TextBlock`)
- [x] Music track: the track's name on a wave with beat dots, or "+ Music"; a tap opens Audio (`ui/clip/ClipTracks.kt` `MusicBar`)
- [x] Voice track: "Voice 0:05" blocks, "+ Record" at the place under the playhead, "● 0:04" growing while recording (`ui/clip/ClipTracks.kt` `VoiceBlock`, `RecordChip`)
- [x] A tap on a block chooses its scene: white border, playhead to its best frame, playback paused (`ui/clip/ClipScreen.kt` `sceneFrame`)

### Tools
- [x] Nothing chosen: Text, Audio, Media, Camera, Length, Route, Look, Stats for the whole clip; a chosen block puts "Scene · <name>" tools first (`ui/clip/ClipScreen.kt` `ClipToolbar`; `ui/clip/TrackLayout.kt` `ClipTool.forScene`)
- [x] "Skip stop" leaves a planned place out (2 must stay) with its nearby photos; "Let go" clears the choice; Back closes the panel, then lets go (`ui/clip/ClipViewModel.kt` `skipStops`; `clip/ClipPlan.kt` `skipping`)
- [x] Panels: title, "All scenes · This scene", yellow Done; "THIS SCENE · NAME" with "Reset scene" once changed (`ui/clip/ClipTools.kt` `ToolPanel`, `SceneLine`; `ui/studio/StudioControls.kt`)
- [x] Sliders show their value ("120 %", "2.5 s"), one undo step per drag; every control off while exporting (`ui/clip/ClipControls.kt` `ValueSlider`)
- [x] Thumb pad: a small frame with a 3×3 grid, drag or tap, "Top left" … "Bottom right", "Auto" for the automatic spot (`ui/clip/ClipControls.kt` `ThumbPad`)

### Text
- [x] Whole clip: "Place names", "Title at the start", "Stops' own titles" (planned routes), "Text size" 70–170 %, "Place names go" pad (`ui/clip/ClipSceneTools.kt` `TextAll`)
- [x] Font Theme / Sans / Serif / Hand / Tall; "Behind the text" None / Panel / Glass; Colour Light / Dark / Theme / Accent (`clip/ClipDesign.kt` `ClipFont`, `TextBack`, `TextColor`)
- [x] "Edit stops": the title, each stop and the end with their note or spot; a tap chooses one (`ui/clip/ClipTools.kt` `EachText`)
- [x] Day cards: a switch for a full-frame card opening each day, and each day Shown / Off; "This route has no dated days for cards." (`ui/clip/ClipSceneTools.kt` `DayCardAll`)
- [x] Tags: switch, "Tags come from the plan. Add them on a plan item.", colour Sun / Coral / Sea / Ink / White / Pink, size 60–200 % (`ui/clip/ClipTagTools.kt` `TagsAll`; `clip/ClipTags.kt`)
- [x] Title scene: "Show the title", "Title" (80), "Subtitle" (70), "Dates", size, position, its own font, panel and colour (`ui/clip/ClipSceneTools.kt` `OpeningText`, `OwnLook`)
- [x] Place scene: "Show the name", the small line above (24), "Name" (40), "Note" (70), "Exact spot" (60), size, position or drag, its own font, panel and colour (`ui/clip/ClipSceneTools.kt` `PlaceText`, `NoteAndSpot`)
- [x] Place tags: the plan's "#tag" chips, "Show the tags", colour, size, "Tags go" ("Auto: under the name.") (`ui/clip/ClipTagTools.kt` `SceneTags`)
- [x] Day card scene: "Card before Day N" ("Full frame for 1.5 s"), small line, "Title" (60), "Note", "Exact spot" (`ui/clip/ClipSceneTools.kt` `DayCardScene`)
- [x] Photo "Caption" (40); End "Closing line" (60, the totals as placeholder) (`ui/clip/ClipSceneTools.kt` `Caption`, `TextScene`)

### Audio
- [x] Music: None ("Silent video") and 8 bundled CC0 tracks (Latin Groove, Afro Groove, Que Chilero, Mirihi, Lost in Brazil, Offbeat, Caldera, Sun Village), each with play/stop, mood · length and a tick (`ui/clip/ClipSceneTools.kt` `MusicList`; `clip/music/MusicCatalog.kt`; assets `music/`; **iPhone API**: AVAudioPlayer)
- [x] Listening stops when the panel closes or the app leaves (`ui/clip/ClipSceneTools.kt` `rememberMusicPreview`)
- [x] "Starts at" waveform with a window as long as the clip: drag or tap, snaps to a bar; "Listen from here", "Best part" (default), "Song start" (`ui/clip/ClipSceneTools.kt` `MusicStart`; `clip/music/MusicShapes.kt`)
- [x] "On the beat": day changes, photo pauses, close-ups, videos and cards start on beats; the clip ends on a bar (`clip/ClipBeats.kt`; `clip/ClipTiming.kt` `onBar`)
- [x] Voice lines: "Record for <place>", one a place, up to 20 s, the music quieter under it; "Lines" with remove; "No voice lines yet." (`ui/clip/ClipSceneTools.kt` `VoiceAll`)
- [x] A place's "Voice line": Record / Record again / Remove; "Speak now. It stops by itself after 20 s." with Stop and Cancel (`ui/clip/ClipSceneTools.kt` `VoiceScene`; `clip/ClipVoice.kt`; **iPhone API**: AVAudioRecorder; **owner**: microphone check on a real iPhone)
- [x] The microphone asked on the first record; "Allow the microphone to record a voice line.", "The microphone couldn't start.", "Too short. Hold on a little longer." (`ui/clip/ClipViewModel.kt` `startVoice`, `stopVoice`)

### Media
- [x] Whole clip: "Photos" (the journey stops while each shows), "Choose media per place", "Photo pause" 1–8 s, "Still map before and after" 0–2 s (`ui/clip/ClipSceneTools.kt` `PhotosAll`, `MediaPerPlace`)
- [x] Layout Card / Full screen (default) / Album (up to 4 a page); Card adds size 50–150 %, "Photos go" and Frame Theme / None (`clip/ClipDesign.kt` `PhotoLayout`, `PhotoFrame`; **engine**: album/AlbumLayouts.kt)
- [x] At most one held photo per 4 s, spread out, chosen ones first; far photos join the nearest stop (`clip/ClipTiming.kt` `chooseHeld`)
- [x] A place's media: 4-across grid, photos in the clip framed yellow and numbered, tap in or out, hold and drag to reorder, no-room photos dimmed, with the room notes (`ui/clip/ClipPlacePhotos.kt` `PlaceMediaPanel`, `PickGrid`; `clip/ClipPicks.kt`)
- [x] The place's videos "Video 1 · 6 s" ("sound on"): a tap opens one, the bin removes it (`ui/clip/ClipSceneTools.kt` `VideoRow`)
- [x] "Add media to <place>": "From your phone" (videos, up to 8), plus every trip photo and journal video labelled "Here", a place, "No place" or "Not added", to add, move or take out (`ui/clip/ClipPlacePhotos.kt` `AddMediaSheet`, `AddTile`; `clip/PlaceMedia.kt`; **iPhone API**: PHPicker)
- [x] An added video is copied in (first 5 s, sound off, after its place); "Adding the video…"; "This video is too big. Pick a shorter one." and the other video errors (`clip/ClipMedia.kt` `importVideo`, `copyIn`)
- [x] Photo scene: "In the clip" with why not, "Pause" 0–8 s ("No pause"), size, position or drag, Tilt Auto / −8° / −4° / 0° / +4° / +8°, caption, Order Earlier / Later (`ui/clip/ClipSceneTools.kt` `OnePhoto`, `PhotoOrder`)
- [x] Video scene: Length 1–10 s, "Starts at", "Its own sound" (45 % under music, 90 % without), "Plays after <place>" with Earlier / Later place, Remove (`ui/clip/ClipSceneTools.kt` `VideoTrim`, `VideoScene`)

### Camera
- [x] Map: Street, Terrain, Earth ("Satellite pictures from 2016…"), Globe ("Starts from space over your trip…") (`ui/clip/ClipTools.kt` `MapAll`; `clip/MapStyles.kt`; **iPhone API**: MapLibre snapshots; **network**)
- [x] Camera Flat ("…straight above, no sky.") / 3D ("A tilted camera follows your route…"), and "Sky" in 3D (`ui/clip/ClipTools.kt` `MapAll`; `clip/ClipPlan.kt` `ClipView`)
- [x] "Camera moves" Calm / Follow; "How close" Whole route / Far / Normal / Close / Very close, steps the map can't reach dashed (`ui/clip/ClipTools.kt` `ZoomAll`, `ZoomSlider`; `clip/ClipZoom.kt`)
- [x] "Zoom out to" Auto / Space / Region / Whole route; "Stop close-up" Off / Close / Very close (8 at most, 40 % of the journey) (`clip/ClipZoom.kt` `ZoomOut`, `CloseUp`)
- [x] The zoom limit note, e.g. "Earth's pictures blur closer than about 2.6 km across…" (`clip/ClipZoom.kt` `ZoomLimits.note`)
- [x] A place: "How close here" (closer or wider than the rest) and "Stop close-up here" (`ui/clip/ClipTools.kt` `ZoomScene`)

### Length
- [x] "Length": Auto · 0:32 (20 s plus 1.3 s a day, 25–60 s, wide at least 45 s), 15 s, 30 s, 45 s, 60 s, Pace (`ui/clip/ClipTools.kt` `SpeedAll`; `clip/ClipTiming.kt` `ClipLength`)
- [x] Pace ×1, ×2, ×5, ×10, each showing its length ("0:42"): 8 s × √hours of travel at ×1, legs over 4 h counting less, 6–120 s (`clip/ClipTiming.kt` `seconds`; **engine**: model/TravelTime.kt)
- [x] A place's "Time here": Auto (1–3 s by the item's From–To, a quarter of the clip at most) or Set by hand 0–10 s (`ui/clip/ClipTools.kt` `StopStay`; `clip/StopStays.kt`; **engine**: model/PlanSpan.kt)

### Route
- [x] "Route from" Trip plan / Recorded journey / Saved stops, with its notes ("…N without a map location skipped.") (`ui/clip/ClipTools.kt` `RouteAll`)
- [x] "Left out": skipped stops with Put back (`ui/clip/ClipTools.kt` `SkippedStops`)
- [x] Recorded journey "Travel": Auto ("Guessed by speed: Car, Walk"), Walk, Bike, Car, Train, Ferry, Plane as paper 3D models (`clip/VehicleGuess.kt`; `clip/VehicleSprites.kt`)
- [x] Line Solid / Dashed, Width Thin / Medium / Bold, Colour Theme / White / Black / Accent (`clip/ClipDesign.kt` `LineStyle`, `LineWeight`, `LineColor`)
- [x] "Traveller & rides" for this video, People | Rides, "THIS VIDEO" / "FROM ME", "Use my default", "Make default"; a note when no leg is on foot or by that vehicle (`ui/clip/ClipTravellerTool.kt`; choices as in area 29)
- [x] A planned place's "How you got here" Walk / Bike / Car / Train / Ferry / Plane (again = the default) and "Count in km", saved on the trip and shown in the plan (`ui/clip/ClipTools.kt` `RouteScene`; **engine**: clip/TravelModes.kt)
- [x] A travel change made in the plan re-plans an open clip (`ui/clip/ClipViewModel.kt` `applySavedChoices`)

### Look and stats
- [x] Look Postcard / Film / Minimal / Bold, each setting font, panel, colour, line, size, bars, tone and grain; "Custom…" after a change (`ui/clip/ClipTools.kt` `LookAll`; `clip/ClipDesign.kt` `ClipLook`)
- [x] "Letterbox bars", "Grain", Tone Natural / Warm (`clip/ClipRenderer.kt` `drawFinish`, `toneFilter`)
- [x] "Reset every scene" ("…Your videos and voice lines stay.") (`clip/ClipEdits.kt` `resetScenes`)
- [x] Stats Day · km / Day / km / None, counting up ("Day 3 of 9 · 17 km"), size 60–250 %, position, font, panel, colour; hidden under titles and cards (`ui/clip/ClipSceneTools.kt` `StatsAll`; `clip/ClipText.kt` `statsLine`)

### What the video shows
- [x] Opening: the title and dates over the map as the camera dives from space or the region (`clip/ClipRenderer.kt` `drawOpening`)
- [x] The route drawn as it goes, what's ahead as soft dots, stops popping in (`clip/ClipRenderer.kt` `drawRoute`, `drawStops`; **iPhone API**: Skia)
- [x] A small 3D paper vehicle at the head in the theme's colours and this video's rides, turning with the route (`clip/PaperVehicle3D.kt`)
- [x] On foot, the traveller (or the couple) walks in step and stands at waits (`clip/WalkCycle.kt`; `clip/TravellerMesh.kt`)
- [x] Place names slide in under "DAY 4", then the note and spot; planned routes name the nearest place, recordings one place a day (`clip/ClipPlan.kt` `LabelRange`; `clip/ClipRenderer.kt` `drawLabel`)
- [x] Tag stickers under the name (`clip/ClipRenderer.kt` `drawTags`)
- [x] Photos as tilted bordered cards, full frame, or album pages, with captions (`clip/ClipRenderer.kt` `drawCard`, `drawFull`, `drawAlbumTile`)
- [x] Phone videos full frame after their place, cropped and turned right, with quick fades (`clip/ClipRenderer.kt` `drawVideo`; **iPhone API**: AVAssetReader)
- [x] Day cards: "DAY 14", the place, a rule, the date (`clip/ClipRenderer.kt` `drawDayCard`)
- [x] Close-ups: the camera goes in, holds about 1 s, pulls back (`clip/ClipPlan.kt` `StillView`)
- [x] Globe map: from space with the trip's countries and up to 12 flags, the street map below about 2,000 km, long legs on great circles (`clip/GlobeGround.kt`; `clip/ClipGlobe.kt`)
- [x] 3D: tilted ground with optional sky and haze; offline, the sea colour with a moving grid (`clip/ReplayProjection.kt`; `clip/ClipRenderer.kt` `drawSky`, `drawGrid`)
- [x] End card: the plane mark, title, dates, "10 days · 46 km" (or "N places · X km approx.", or your line), "Greece, Italy +2", small map credits (`clip/ClipRenderer.kt` `drawEnd`, `creditLines`; `clip/ClipPlan.kt` `EndCard`)
- [x] Map pictures from snapshots taken for the clip, up to 12 sharper ones plus close-ups (`clip/BaseMap.kt` `BaseMaps.fetch`; `clip/ClipPlan.kt` `layerSpecs`; **iPhone API**: MLNMapSnapshotter; **network**)

### Export
- [x] Export sheet "9:16 · 0:42 · Latin Groove", "Create video"; "The map is still coming in…" (`ui/clip/ClipScreen.kt` `ExportSheet`)
- [x] "Making the video" → "Adding the sound" → "Finishing" · N%, "You can close this; it carries on.", Cancel (`ui/clip/ClipScreen.kt`; `clip/ExportProgress.kt`; iPhone: on iOS 26 a continued-processing task with the system's own progress keeps it going away from the screen; before, the parts go on when the app is back)
- [x] MP4: H.264, 30 fps, about 16 Mbit/s at 1080×1920, smaller when the encoder can't (`clip/ClipExport.kt`; `clip/Mp4Encoder.kt`; **iPhone API**: AVAssetWriter; **owner**: speed and memory on a real iPhone)
- [x] Sound: AAC 128 kbit/s, the music from its start with fades, looped with a crossfade when short, video sound mixed in, ducking under voices (`clip/music/ClipMusic.kt`; `clip/music/MusicMix.kt`; **iPhone API**: AVAudioEngine)
- [x] Cancel or failure never leaves a broken file; the last good clip stays (`clip/ClipFiles.kt`)
- [x] Done: "Your video is ready" / "It's also kept in the trip's Media."; "Couldn't add the music, so this video is silent." (`ui/clip/ClipScreen.kt` `ExportSheet`)
- [x] Failed: "Couldn't make the video on this phone. Try another size or a faster speed." with Try again (`ui/clip/ClipScreen.kt`)
- [x] An edit after export goes back to "Create video"; leaving the editor stops a running export (`ui/clip/ClipViewModel.kt` `applyStep`, `makeJob`)

### Sharing the video
- [x] "Watch": the MP4 full screen with Play/Pause, scrub and time (`ui/clip/ClipPreview.kt` `FullScreenVideo`; **iPhone API**: AVPlayer; iPhone: Android's FullScreenVideo itself over an AVPlayerLayer, since the second parity round)
- [x] "Send video": the share sheet (`clip/ClipExport.kt` `shareIntent`; **iPhone API**: share sheet)
- [ ] TikTok (always), Instagram and WhatsApp (when installed) buttons; "<App> isn't on this phone…" (`clip/ClipTargets.kt`; **iPhone API**: app URL schemes, TikTok OpenSDK; **owner**: TikTok developer app)
- [x] "Save to phone" → "Saved to your gallery, in Movies › BoringTravel."; "Couldn't save it to the gallery. Try again." (`ui/clip/ClipViewModel.kt` `saveToPhone`; `export/Sharing.kt` `saveVideo`; **iPhone API**: Photos add-only)
- [x] "Save to Drive" → "Saved to Drive, in BoringTravel › Exports." with Open; "Google Drive isn't connected." with Connect Drive (`ui/clip/ClipScreen.kt` `DriveRow`; **network**; **owner**: Google iOS OAuth client)
- [x] "Send Drive link": "Anyone with the link can watch it; the video stays in your Drive." (`ui/clip/ClipViewModel.kt` `shareDriveLink`; **network**; **owner**: Google iOS OAuth client)
- [x] Every finished clip kept in the trip's Media with a thumbnail from 40 % in, in backups, deleted with its trip (`data/SavedClips.kt` `keep`)

### Pro and the mark
- [x] The plane mark on every clip's end card; off only for Pro, once Pro is on sale (`billing/ProBilling.kt` `markOff`; `ui/clip/ClipViewModel.kt` `noMark`)
- [ ] Once Pro is on sale: clips over a minute are Pro ("PRO" on speeds, "Clips over a minute are Pro…" with Go Pro), free clips move to a faster speed, photo room capped to a minute (`clip/ClipTiming.kt` `needsPro`, `freeSpeed`; `clip/ClipPicks.kt`)

## 18. The book

### Making it
- [x] Trip ⋮ › "Make a book", and "Want to customize more? Make a book" on Share (`ui/trip/TripScreen.kt`; `ui/export/ExportScreen.kt` `BookLine`)
- [x] "Laying out the pages"; "Couldn't make the book" with "This trip isn't here any more." / "Couldn't open the book. Try again." (`ui/book/BookScreen.kt`; `ui/book/BookViewModel.kt` `load`)
- [x] The first visit lays the book out and saves it as a draft; later visits reopen it with every edit (`book/BookExporter.kt` `latest`, `newDraft`; **engine**: data/db/BookDraft.kt)
- [x] A new book: US Letter where Letter is usual, else A5; the Share screen's style; Pages in fours on (`ui/book/BookViewModel.kt` `load`; `book/BookModel.kt`)
- [x] Auto layout: Cover, Title page, the route map (only with a travelled route), each day's opener and pages, Trip in numbers, Back cover (`book/BookLayout.kt` `auto`)
- [x] Cover photo: the trip's cover, else the first landscape photo, else any (`book/BookLayout.kt` `coverPhoto`)
- [x] A note: its first photo with its text on "Photo and text", text running on to Text pages, other photos in groups of 1–3, a layout as album pages (`book/BookLayout.kt` `dayPages`, `photoPages`)
- [x] A photo shaped like the page gets "Full-page photo", else "Photo and caption" (`book/BookLayout.kt` `fitsFullPage`)
- [x] A text-only note takes the day's nearest loose photo; text-only notes in a row share a page; loose photos close the day (`book/BookLayout.kt` `dayPages`)
- [x] Book text: title, "HH:mm · place", paragraphs, headings, quotes, lists, checklist items, places (`book/BookLayout.kt` `entryParas`; **engine**: doc/EntryDoc.kt, doc/RichHtml.kt)
- [x] Text flows page to page: one column on A5, two on larger; titles keep with their text; 2 lines each side of a split; pages added or dropped; the last page balanced (`book/BookLayout.kt` `reflow`; `book/TextFit.kt`; `book/PaintTextMeasure.kt`; **iPhone API**: Core Text measuring that matches WebKit)
- [x] Page numbers on alternating sides; running heads: the trip on left pages, "Day N · place" on right ones (`book/PageLayout.kt` `folio`, `runningHead`)
- [x] Cover per look: taped print with stickers and plane stamp, or full-bleed photo; Minimal's kicker, Postcard's postage stamp (`book/PageLayout.kt` `cover`)
- [x] Title page: stamp, title, subtitle, dates, flags, places (`book/PageLayout.kt` `titlePage`)
- [x] Day opener: day stamp, "Day N of M", weekday and date, place with flags, the day's route in pen (or "A → B → C"), up to 6 notes, "18.4 km · 3 stops · 27° sunny" (`book/PageLayout.kt` `dayOpener`; `book/BookFacts.kt`)
- [x] Map page "The route": flags, regions, the route with numbered day ends, scale bar, "123 km · 10 days" (`book/PageLayout.kt` `map`; `book/BookSvg.kt`)
- [x] Trip in numbers: Days, Kilometres, Countries, Entries, Photos, Stops, "Longest day", "Most photos" (`book/PageLayout.kt` `stats`; `book/BookFacts.kt`)
- [x] Notes page; back cover with the route, title, dates, stamp and "BoringTravel" (`book/PageLayout.kt` `notes`, `back`)
- [x] Drawn flags; a country with no drawing prints its two letters (`book/Flags.kt`)
- [x] Book edits never change the journal or the trip (`book/BookModel.kt`)

### Looks
- [x] Notebook (dotted or ruled paper, tilted taped prints, handwriting, red stamps, highlighter), Scrapbook (polaroids, captions on the border), Postcard (white, flat photos, serif, postage stamps), Minimal (white, clean sans) (`book/PageGeometry.kt` `BookLook`; `book/BookCss.kt`)
- [x] Theme picker: four mini pages drawn with the trip's own photos (`ui/export/ThemeThumbs.kt`)

### All pages
- [x] Dark studio: trip name, "Book · N pages · A5", Back, Book look, PDF (`ui/book/BookScreen.kt` `PagesOverview`)
- [x] "Tap a page to change it. Hold and drag to move it."; Undo / Redo; a thin progress bar (`ui/book/BookScreen.kt`)
- [x] "N notes pages added for binding." (`book/BookLayout.kt` `padding`)
- [x] Thumbnails "n · Cover / Title / Day N / Photos / Text / Map / Numbers / Notes / Back"; hidden notes' pages dimmed "Hidden" (`ui/book/BookScreen.kt` `PagesGrid`; `ui/book/PageViews.kt` `PageThumb`)
- [x] A tap opens a page; hold and drag moves it, others shift, the edges scroll, the text re-pours (`ui/book/BookScreen.kt` `PagesGrid`; `book/BookLayout.kt` `BookEdit.move`)
- [x] "Add a page": the 16 layouts; the page goes before the back cover and opens (`ui/book/BookSheets.kt` `TemplateGrid`)
- [x] "Photo pages": "Only photos not in the book", pick in order, "Next · N photos", an arrangement; pages before the back cover (`ui/book/BookSheets.kt` `AlbumPagesPicker`; `book/AlbumEdit.kt`)

### Page editor
- [x] "Page n of N" ("Hidden page"), "<Trip> · Book", All pages, previous / next page, PDF (`ui/book/PageEditor.kt`)
- [x] The page large, exactly as it prints, from the PDF's own HTML (`ui/book/PageViews.kt` `PagePreview`; `book/BookHtml.kt`; **iPhone API**: WKWebView)
- [x] Tap selects (coral outline, corner handles, turn handle), again opens its main tool, beside the page lets go; an empty photo spot opens "Choose photos" (`ui/book/PageCanvas.kt`; `ui/book/PageEditor.kt` `select`)
- [x] Drag to move, snapping to page edges, centre, margins and other elements with dashed guides; 6 mm stays on the page; the page follows live (`ui/book/PageCanvas.kt`; `book/FreeMath.kt` `snap`; `ui/book/PageViews.kt` `moveScript`; **iPhone API**: WKWebView evaluateJavaScript)
- [x] Corner resize (photos and stamps keep their shape, 4 mm at least); the turn handle snaps to 45°; "W × H mm" / "N°" while dragging (`book/FreeMath.kt` `resize`, `snapAngle`)
- [x] The first hand change makes a page "arranged by hand", looking the same (`book/FreeEdit.kt` `freeze`)
- [x] "+ Photos" and Undo / Redo on the stage (`ui/book/PageEditor.kt` `AddPhotosButton`)
- [x] Tool row: Page (Layout, Paper, Look, Page) and Add (Photo, Text, Sticker, Map); a selection shows its tools and Arrange; hidden while typing (`ui/book/PageEditor.kt` `EditorToolbar`)
- [x] Back closes the tool, then the selection, then goes to all pages (`ui/book/BookScreen.kt` `BackHandler`)
- [x] "ON THIS PAGE · TOP FIRST" with "+ Add": rows with a thumbnail, name and detail ("Photo · frame, −3°") (`ui/book/LayerList.kt`; `book/Layers.kt`)
- [x] A row selects on the page; its handle drags it forward or back; the eye hides it from page and PDF (page text can't hide) (`ui/book/LayerList.kt`; `book/FreeEdit.kt` `moveLayer`, `setHidden`)
- [x] Background row last (opens Paper, its eye hides it); "N photos not on the page: no room in this layout" with New page (`ui/book/LayerList.kt` `BackgroundRow`; `book/AlbumEdit.kt` `extrasToNewPages`)

### Page tools
- [x] Layout: arrangements by photo count 1, 2, 3, 4, 6, 9, and "More layouts", the 16 layouts drawn with this page's photos (`ui/book/ToolPanels.kt` `LayoutPanel`; **engine**: album/AlbumLayouts.kt)
- [x] The 16 layouts: Full-page photo, Full page with caption, Photo and caption, Two photos, Two side by side, Three photos, Three up, Grid of four, Photo and text, Photo and text column, Text, Day opener, Map, Trip in numbers, Notes page, Title page (`book/BookModel.kt` `Template.choosable`)
- [x] A new layout keeps the photos and text; extras: "N photos more than this layout has room for…" with "Put the rest on a new page" (`book/FreeEdit.kt` `applyTemplate`)
- [x] Paper: Theme's, Colour (9 plus the theme's), Photo to the edges with Dim 0–70 % (`ui/book/ToolPanels.kt` `PaperPanel`; `book/FreeEl.kt` `PageBg`)
- [x] Page: Duplicate page, Use this layout on other pages (Same layout / All / None, page ticks, "Use on N pages"), Add a page after, Add photo pages, Back to the layout, Hide / Show entry, Delete page (Keep / Delete) (`ui/book/ToolPanels.kt` `PagePanel`, `CopyLayoutPanel`; `book/BookLayout.kt` `hideEntry`)
- [x] Add: Text, Photo, Washi tape, Photo corners, Stamp, Flag, Day's route, Trip route, Box, Circle, Line (`ui/book/ToolPanels.kt` `AddPanel`)
- [x] Shortcuts: Text adds a box ready to type, Photo opens "Add a photo", Sticker opens "Add a sticker", Map adds the one route or asks (`ui/book/PageEditor.kt`)
- [x] Flag: added at once on a one-country trip, else the trip's countries or "Country code, like GR" (`ui/book/ToolPanels.kt`)
- [x] New things appear centred and selected; an empty new text box goes when closed (`ui/book/PageEditor.kt` `add`)
- [x] Photo: Replace / Choose (trip photos, Remove), Crop (drag, pinch to 5×, Reset; "This photo may print soft at this size." under 180 dpi), Frame White border / None, Caption (120, book only) (`ui/book/ToolPanels.kt` `ReplacePanel`, `CropPanel`, `FramePanel`, `CaptionPanel`; `book/CropMath.kt`)
- [x] Videos show as their first frame (`ui/book/PageViews.kt` `CroppedPhoto`)
- [x] Text: Edit (2,000, "For the book only…"), Page text per note (overflow moves on), Font (Titles, Text, or 14 faces; Bold, Italic, Capitals, Highlight), Colour, Size 6–72 pt with Tight / Normal / Wide / Double spacing, Align (`ui/book/ToolPanels.kt` `EditPanel`, `PageTextPanel`, `FontPanel`, `SizePanel`; `book/BookFonts.kt`)
- [x] Shapes and stickers: Box / Circle fill, outline and weight; Line colour and weight; washi colours; photo-corner colours; flag country, Sticker / Flat (`ui/book/ToolPanels.kt` `DecoPanel`)
- [x] Arrange: Rotate (−15°, −5°, 0°, +5°, +15°; nudge 0.5 mm), Forward, Back, Copy (5 mm down-right), Delete (`ui/book/ToolPanels.kt` `RotatePanel`; `book/FreeEdit.kt`)
- [x] "+ Photos": pick several in order (numbered, "IN BOOK", "Not in book" chip), "Add N photos" fills empty spots; "No room for N photos" offers a bigger layout, the page itself, or new pages (`ui/book/ToolPanels.kt` `AddPhotosPanel`; `book/AlbumEdit.kt` `fill`, `grow`)
- [x] "+ From phone" in every photo grid: the phone's picker, up to 30, joining the trip as loose photos (`ui/book/BookViewModel.kt` `addFromPhone`; **iPhone API**: PHPicker)
- [x] "This trip has no photos yet." with Add from phone; "Every photo of the trip is in the book." (`ui/book/BookSheets.kt` `NoPhotos`)

### Book look
- [x] Theme (four looks), Fonts (Theme's own, Notebook, Postcard, Minimal, Field book, Graph paper, Kraft, Night Route), Size A5 / 21 × 21 cm / A4 / US Letter (hand-placed things rescale), Paper Dotted / Ruled / Plain (`ui/book/BookSheets.kt` `LookContent`; `book/BookModel.kt` `BookFormat`, `BookPaper`; `book/BookFonts.kt`)
- [x] "Bleed and crop marks" (3 mm, the sheet 10 mm larger a side, 8 crop marks) and "Pages in fours" (`book/PageGeometry.kt` `Sheet`; `book/BookSvg.kt` `cropMarks`; `book/BookLayout.kt` `padded`)
- [x] "Start the layout again" with Cancel / Start again (Undo brings the edits back) (`ui/book/BookViewModel.kt` `resetLayout`)

### Undo, saving and the PDF
- [x] Undo / Redo, 30 steps over settings and pages; a panel visit is one step (`ui/book/BookViewModel.kt`; `book/FreeEdit.kt` `EditHistory`)
- [x] Saves 0.4 s after each change and on leaving; drafts go into backups (`ui/book/BookViewModel.kt` `scheduleSave`; **engine**: data/db/BookDraft.kt, backup/BackupFormat.kt)
- [x] PDF sheet "N pages · A5 · 148 × 210 mm": photos at about 300 dpi (4800 px at most) without metadata, "Getting photos ready · n of N", "Making the PDF", Cancel; made once until the book changes (`ui/book/BookScreen.kt` `MakeSheet`; `book/BookExporter.kt` `pdf`; **iPhone API**: WKWebView + UIPrintPageRenderer at a custom page size)
- [x] Save to phone ("<Trip> book.pdf", "Saved."), Share PDF, Print at the book's own size (`ui/book/BookScreen.kt`; `book/BookExporter.kt` `print`; **iPhone API**: document picker, share sheet, UIPrintInteractionController; **owner**: a real printer)
- [x] Send to Drive → "Saved to Drive › BoringTravel › Exports › <name>", then "Send Drive link" (`export/TripExporter.kt` `savePdfToDrive`; **network**; **owner**: Google iOS OAuth client)
- [ ] Errors: "Google Drive isn't connected. Connect it in Me › Backup & sync.", "The pages took too long…", "Too many large photos for one go…", "Couldn't make the PDF…", "The PDF came out empty…" (`ui/book/BookScreen.kt`; `book/BookExporter.kt`)

## 19. Share (print and export)

### Preview
- [x] Trip ⋮ › Share opens it (`ui/trip/TripScreen.kt`)
- [x] The real PDF for the options as a sideways strip of pages, "Preview · N pages", made again 0.7 s after a change (the old one stays meanwhile) (`ui/export/ExportScreen.kt` `Preview`; `ui/export/PdfPreview.kt` `PageStrip`; **iPhone API**: WKWebView, PDFKit)
- [x] "Getting photos ready · n of N", "Making the pages", Cancel → "Stopped." with "Make the pages"; a failure with Try again (`ui/export/ExportScreen.kt` `ProgressCard`)
- [x] "Tap a page to see it bigger.": a full-screen viewer, swipe, "3 / 12", × (`ui/export/PdfPreview.kt` `PageViewer`; **iPhone API**: PDFKit)

### Options
- [x] Style Notebook / Minimal (Postcard or Scrapbook saved earlier open as Notebook); remembered, and a new book's default (`ui/export/ExportScreen.kt`; **engine**: model/Kinds.kt `PrintTheme`)
- [x] "Pages": "Whole trip · 10 days" or "Days 2–5 · 4 days"; a sheet with Whole trip / Choose days and a two-thumb slider (`ui/export/DayRangeSheet.kt`)
- [x] Photos Small / Medium / Large; Paper A4 / Letter (Letter in the US and a few countries); "Notebook paper" Dotted / Ruled (`export/ExportOptions.kt`)
- [x] "Include the plan" (off): each day's plan, days with only plan items, and Ideas for the whole trip (`export/TripBook.kt`; `export/TripHtml.kt` `planList`)
- [x] Choices remembered and in backups; "Nothing leaves the phone until you save, print or share it." (`data/settings/Settings.kt` `printChoices`)

### Formats
- [x] Format PDF / Text / Google Docs / Word, then a sheet with the format's ways (`ui/export/ExportScreen.kt` `WaysSheet`)
- [x] PDF: Save to phone ("<Trip>.pdf", "Saved."), Print (the previewed PDF), Share (`ui/export/ExportScreen.kt` `savePdf`; `export/PdfMaker.kt` `printFile`; **iPhone API**: document picker, UIPrintInteractionController, share sheet; **owner**: a real printer)
- [x] File name: the trip, or "<title>, 19–21 Jun 2027" for chosen days; safe characters, 60 at most (`export/ExportFiles.kt` `fileName`)
- [x] Text: Save to phone (.txt), Copy (up to 100,000 characters, "Copied."), Share (`export/TripText.kt`; `export/Sharing.kt`)
- [x] Word: Save to phone / Share (.docx), with a photo progress bar (`export/TripDocx.kt`; needs a zip writer on iPhone)
- [x] Google Docs: connects Drive first when needed and carries on; uploads to Drive › BoringTravel › Exports as a Doc; "Saved to Google Docs" with Open and Share link ("Anyone with the link can open it…") (`ui/export/ExportScreen.kt` `startDocs`; `export/TripExporter.kt` `saveToDocs`, `shareLink`; **network**; **owner**: Google iOS OAuth client)
- [x] Photos for Docs scaled down by count (1600 / 1200 / 900 / 640 px) to stay under 50 MB (`export/ExportFiles.kt` `docsMaxPx`)
- [x] Notice cards, tap to close: "Google Drive isn't connected." with Connect, Drive and time-out errors, "Try fewer days or smaller photos.", "This trip isn't here any more.", "The PDF came out empty…" and the rest (`ui/export/ExportScreen.kt` `NoticeCard`; `export/TripExporter.kt`)

### What the pages hold
- [x] Cover: title, subtitle, dates under a highlighter, the cover photo whole (tape and plane mark on Notebook), "N days · N km · N stops · N photos", flags (`export/TripHtml.kt` `cover`, `tripFacts`)
- [x] Each day: red round day stamp, "Day N of M", the date in handwriting, flags · place · weather, each note's title, "HH:mm · place" and text in its own font (`export/TripHtml.kt` `notebookDay`, `entries`; `export/PrintCss.kt`)
- [x] Photos placed as in the note (width, turn, frame, caption), rows of whole photos, the day's loose photos after its notes (`export/TripHtml.kt` `placed`, `photos`; `export/PhotoRows.kt`)
- [x] A note's page breaks start a new page; videos print as their first frame (`doc/DocRender.kt` `printPages`; `export/PhotoPrep.kt`)
- [x] Plan list: time, box (ticked when done), title, "until…", note · place, kind (`export/TripHtml.kt` `planList`)
- [x] Minimal: plain white, no tape or highlighter; a footer with the trip and page number, except on the cover (`export/PrintCss.kt`)
- [x] Empty days left out; "Nothing written for these days yet." (`export/TripBook.kt`)
- [x] Photos re-encoded as plain JPEG without location, 1200 / 1600 / 2000 px (`export/PhotoPrep.kt` `encode`)
- [x] Docs: plainer, each day on a new page with "Moved 12.3 km · Stops 3 · Weather 27° sunny", photo rows and the plan as tables (`export/TripHtml.kt` `docsBody`)
- [x] Word: day pages with headings, bold, italic, underline, strike, lists, checklists, quotes, page breaks, photo rows, 20 mm margins (`export/TripDocx.kt`)
- [x] Text: name, dates, each day, plan lines, each note's title, time · place and words, captions, Ideas (`export/TripText.kt`; `doc/DocRender.kt` `plainText`)

## 20. The AI planner

### Screen and empty chat
- [x] "AI planner" with Back; "New trip" or "<Trip> · <dates>" under it (`ui/ai/PlannerScreen.kt` `tripLine`)
- [x] With an own key: "Using your <Provider> key · <model>"; a tap opens Me › AI (`ai/own/OwnKeyStore.kt` `usingLine`)
- [x] Empty chat: "Make the whole trip with AI" / "Change the trip with AI" and what to paste (`ui/ai/PlannerScreen.kt` `Conversation`)
- [x] "Start from" / "Add from" card: Pictures ("Screenshots, guide pages, maps or tickets. Up to 10.") and Link ("A Google Maps place or list, a web page, a blog or a Google Doc.") (`ui/ai/PlannerScreen.kt` `AttachChoices`)
- [x] "Plan a trip for me" / "Plan the days for me" on a new or empty trip opens the trip questions (`ui/ai/PlannerViewModel.kt` `openQuestions`)
- [x] The box's placeholder: "Paste your trip, or say where and when", "Paste your list, or say what to change", "Ask for a change", "Answer the questions above" (`ui/ai/PlannerScreen.kt`)
- [x] The list keeps the newest message, the progress line or the error in view (`ui/ai/PlannerScreen.kt`)
- [x] Typed text and attachments survive turning the phone; leaving drops the chat and unsaved changes, without asking (`ui/ai/PlannerViewModel.kt`)
- [x] A New trip way opens its first step once: the questions, the picker or "Add a link" (`ui/ai/PlannerScreen.kt` `started`)

### Trip questions
- [x] "Plan a trip": "Answer what matters to you. Skip the rest and the planner decides." (`ui/ai/TripQuestionsSheet.kt`)
- [x] Where (80) with "Suggest one"; When and how long ("3–7 May, or May") with chips 2 days to 2 weeks (`ui/ai/TripQuestionsSheet.kt`; `ai/tools/TravelBrief.kt` `DAY_CHOICES`)
- [x] Who: Solo, Couple, Family with children, Friends, and How many 2–6+; Pace: Easy, Balanced, Full (`ai/tools/TravelBrief.kt` `Who`, `Pace`)
- [x] Interests (several): Sights and history, Museums and art, Food, Nature and hikes, Beaches, Nightlife, Shopping, Off the usual path; Budget Lean / Middle / Generous; Getting around On foot and public transport / Rental car / Mixed (`ai/tools/TravelBrief.kt`)
- [x] "Starting from" and "Must-sees and things to avoid" (300) (`ui/ai/TripQuestionsSheet.kt`)
- [x] A selected chip tapped again clears; open questions go as "Decide for me: …" (`ai/tools/TravelBrief.kt` `message`)
- [x] Prefilled from a saved trip (route, dates, length) or from what the chat said (`ai/tools/TravelBrief.kt` `fromTrip`, `fromArgs`)
- [x] "Plan my trip" ("Planning") sends the answers as your message; errors such as the daily limit show in the sheet (`ui/ai/PlannerViewModel.kt` `sendAnswers`)
- [x] Offered once per chat; closed unanswered, "Answer the questions" opens it again (`ui/ai/PlannerScreen.kt` `Bubble`)
- [x] A trip from the answers: real sights by area (about 2 / 3 / 4–5 a day), light first and last days, stays and food by area, default times, dates after today; "Suggest one" says why (`ai/PlannerPrompt.kt` `system`)

### Writing a message
- [x] Up to 30,000 characters: "This itinerary is too long. Choose up to 30,000 characters, or import one part at a time." (`ui/ai/PlannerViewModel.kt` `updateInput`)
- [x] Send only with text or an attachment, and not while working, listening or loading (`ui/ai/PlannerScreen.kt` `canSend`)
- [x] "+" ("Attach pictures or a link"): Pictures (photo picker, 10 a message, "Up to 10 pictures in one message.") (`ui/ai/PlannerViewModel.kt` `addPictures`; **iPhone API**: PHPicker)
- [x] Link: "Add a link", prefilled from the clipboard on this tap, "That isn't a link. Paste one beginning https://", 5 a message (`ui/ai/PlannerScreen.kt` `askLink`; `ui/ai/PlannerViewModel.kt` `addLink`; **iPhone API**: UIPasteboard)
- [x] A strip above the box: picture thumbnails and link chips, each with ✕ (`ui/ai/PlannerScreen.kt` `AttachmentStrip`)
- [x] On Send the links and the pictures' text go under the words; the bubble shows the words (600 characters) and tags like "3 pictures" and each domain (`ui/ai/Attachments.kt` `message`; `ui/ai/PlannerScreen.kt` `SentTag`)
- [x] Mic ("Dictate message" / "Stop dictation"): "Listening · tap Stop, then check your words before sending.", in the Voice notes language; "Allow the microphone in app settings to dictate, or type your message." (`ui/ai/PlannerViewModel.kt` `startDictation`; **iPhone API**: Speech)
- [ ] A shared .txt file: "Reading the document" with Cancel, its text added to the box; errors for 45 s, not plain text, not UTF-8, no text (`ui/ai/PlannerViewModel.kt` `importDocument`; `ai/ItineraryDocuments.kt`)
- [ ] Shared text waits in the box unsent; a shared Docs link comes as a link, shared pictures attached (`ui/ai/PlannerViewModel.kt` `receiveShared`)

### While it works
- [x] A spinner line with the step: "Reading your message", "Reading N pictures", "Reading <domain>", "Finding N places", "Setting the trip", "Adding N items", "Changing N items", "Removing items", "Planning", "Checking the plan" (`ui/ai/PlannerScreen.kt` `Working`; `ai/tools/ToolLoop.kt`)
- [x] "Stop": "Planning stopped. Your message is kept.", with the message and attachments back in the box (`ui/ai/PlannerViewModel.kt` `cancelPlan`)
- [x] After a failure the message goes back into the box; pictures already read aren't read again (`ui/ai/PlannerViewModel.kt` `restore`)
- [x] 8 minutes at most: "The planner took too long. Try again, or send fewer days at a time." (`ui/ai/PlannerViewModel.kt` `REQUEST_TIMEOUT_MS`)
- [x] The box, +, mic and ✕ are off while it works (`ui/ai/PlannerScreen.kt`)

### Pictures and links
- [x] Pictures read into text: places with town and a note, days, dates, times, flights, trains, ferries, stays; never usernames, phone numbers, emails, booking codes, prices, card or passport numbers (`ai/PictureReading.kt`; **network**)
- [x] Pictures turned upright, scaled (1200 / 2400 px), JPEG on white; long screenshots cut into up to 3 pieces, 20 pieces a message; not kept (`ai/PicturePrep.kt`; **iPhone API**: ImageIO)
- [x] "No places or plans found in this picture. Try a sharper screenshot.", "Reading the pictures took too long…", "This picture couldn't be opened…", own-key picture errors (`ai/PictureReading.kt`)
- [x] In a saved trip, pictures only add places (on their day inside the trip, else ideas); in a new trip they make it (`ai/PlannerPrompt.kt`)
- [x] Up to 8 links a message read together within 15 s; Docs and Drive links left to the AI (`ai/tools/MessageLinks.kt`; **network**)
- [x] A Google Maps place link gives its name and pin (short links followed, a name with no pin searched near the trip) (`ai/tools/MessageLinks.kt` `readMaps`; **network**)
- [x] A place's own website: its name searched near the trip; found, it becomes that place with pin and kind (`ai/tools/MessageLinks.kt` `readSite`; **network**)
- [x] A Google Maps list: every place with name, pin, address, note and Maps link; 200 at most here ("The list holds N more: Copy from a link takes all of it.") (`ai/tools/MessageLinks.kt`; `links/MapsLists.kt`; **network**)
- [x] Only place links: added at once as ideas, no AI, not counted ("Added N places to your ideas, with their pins…", "<name> is already in your plan."); a list alone names a new trip after itself (`ai/PlannerProcess.kt` `byApp`; `ai/tools/ToolRunner.kt` `addMapsList`)

### What it can change
- [x] Make a whole trip: name, first and last day, route line, every item on its day in order (`ai/tools/ToolRunner.kt` `setTrip`, `addItems`)
- [x] Add items: kind, title, day or idea, times, end date, note, place, pin, how you get there, website (`ai/tools/ToolRunner.kt` `parseItem`; **engine**: model/Kinds.kt, model/PlanSpan.kt)
- [x] Change saved or pending items: day, times, kind, title, note (replace or add), place and pin, getting there, website (`ai/tools/ToolRunner.kt` `updateItems`; `ai/DraftEdits.kt`)
- [x] Remove items, only when asked or when a new list replaces the plan (`ai/tools/ToolRunner.kt` `removeItems`)
- [x] Move the dates (the plan moves, or keeps a pasted list's dates; 90 days at most), rename when asked, change the route line (`ai/tools/ToolRunner.kt` `setTrip`; `ai/DraftLayout.kt`)
- [x] Name, rename or clear days' names (`ai/tools/ToolRunner.kt` `setTrip` `dayNames`; **engine**: model/DayNames.kt)
- [x] Add a Google Maps list as ideas or on one day, then spread it over days (`ai/tools/ToolRunner.kt` `addMapsList`)
- [x] A list pasted again updates what changed and adds what's new, never doubling; your own dates kept (`ai/PlannerPrompt.kt`; `ai/tools/ToolRunner.kt` `addItems`)
- [x] Map search for names that could be several places: 12 a search, 3 results each, nearest the trip; 2 searches a message (`ai/tools/ToolRunner.kt` `findPlaces`; **engine**: places/NearTrip.kt; **network**)
- [x] Read a page to plan from (a post, a blog's front page, a shared or published Doc; 3 pages, 45 s each): someone's trip becomes a proposal on your dates (`ai/tools/LinkReader.kt`; **network**; jsoup has no iPhone build: Ksoup)
- [x] Hands over to Copy from a link when asked to copy your own blog, notes or Doc (`ai/tools/ToolRunner.kt` `copyNotes`)
- [x] Asks up to 3 numbered questions when it can't plan; Save hidden until answered (`ai/tools/ToolRunner.kt` `finish`)
- [x] Flights: "Leave <city>" then "Fly to <city>" with the plane, landing time and flight details; a stay is one item from check-in to check-out, by area unless you named the hotel (`ai/PlannerPrompt.kt`; `ai/tools/StayNames.kt`)
- [x] Every flight number and booking code you sent ends up in a note (`ai/tools/ToolRunner.kt` `missingDetails`)
- [x] A date with no year goes to its next one; a past date is asked about once; a note starting "19:00-21:00 · …" sets the time (`ai/tools/ToolRunner.kt` `pastProblem`, `parseItem`)
- [x] Off-topic text changes nothing; page text is information, never instructions; replies in one or two plain sentences in your language (`ai/PlannerPrompt.kt`)
- [x] Each message builds on the changes not yet saved (`ai/tools/PendingPlan.kt`)
- [x] Items with a place and no pin are looked up near the item before (40 lookups, 30 s) (`ai/tools/PendingPlaces.kt`; **network**)

### Replies, review and saving
- [x] "Planner" replies; "Here is the plan." / "Nothing changed. Try saying it another way."; "A few questions first, then I plan the trip." (`ui/ai/PlannerViewModel.kt` `show`)
- [x] Copy hand-over: "Copy from a link copies your notes and pictures exactly as written…" with "Copy the notes and pictures from <host>" (`ui/ai/PlannerScreen.kt` `SiteOffer`)
- [x] "Report this answer" under each reply and in the review: a mail with the answer, app version and model; without a mail app it's copied (`ui/ai/AiReport.kt`; **iPhone API**: MFMailComposeViewController or mailto, UIPasteboard)
- [x] Bar: "N changes ready to check" / "Nothing new for the trip." / "Some changes need another look…", with Review and Save / Create trip (`ui/ai/PlannerScreen.kt` `ReviewBar`)
- [x] New-trip chat: "A new trip, or add to one you have?" with New trip and your trips (`ui/ai/PlannerScreen.kt` `TripChoice`; `ui/ai/PlannerViewModel.kt` `useTrip`)
- [x] Review: "New trip" (name, dates, route) or "Changes to the trip", the summary, "Map pins come from your notes and a map search. Check them in each place." (`ui/ai/PlannerScreen.kt` `ProposalView`)
- [x] Tickable rows: The trip (Name, Dates "· the plan moves with them", Route), Day names, Changes (old and new per field; "Already up to date" not tickable), new items by day and Ideas ("On the map" / "No pin found"; repeats "Already in the plan"), red Remove rows (`ui/ai/PlannerScreen.kt` `TripChangeRows`, `UpdateRow`, `PreviewRow`, `RemovalRow`; `ai/DraftLayout.kt`)
- [x] An item dated outside the trip becomes an idea with "Day 3 · …" in its note (`ai/DraftLayout.kt` `idea`)
- [x] "N of M selected", "Create trip" / "Save selected" (off with nothing ticked or with problems), "Discard all" → "Changes discarded. Nothing was saved." (`ui/ai/PlannerScreen.kt` `ReviewSheet`; `ui/ai/PlannerViewModel.kt` `discard`)
- [x] Problems in red block saving; with no dates: "What are the trip's start and end dates, including the year?" (`ai/DraftEdits.kt`; `ai/DraftLayout.kt`)
- [x] Save writes the ticked changes at once, checked against the plan as it is now: items by time, a Maps link on items with no website, Getting there as the leg's travel, dates moving items and day names (`ai/DraftApplier.kt`; `ai/PlannerProcess.kt` `save`; **engine**: clip/TravelModes.kt, model/DayNames.kt)
- [x] After saving: "Trip created. Dates changed. Added 12 items on 5 days, 3 ideas…", then the trip on Plan, with New trip gone from the history (`ui/ai/PlannerViewModel.kt` `apply`; `ui/nav/AppActions.kt` `plannerDone`)
- [x] Save errors: "This trip was deleted.", "X changed after this draft was made…", "The trip changed since this review…", "Couldn't save the changes. Try again.", "Answer the questions before saving." (`ai/DraftEdits.kt`; `ai/PlannerProcess.kt`)

### Models and limits
- [ ] Built-in planner: Gemini through Firebase AI Logic with App Check, `gemini-flash-latest`; busy → 2 s and again, then `gemini-flash-lite-latest`; out of quota → flash-lite at once (`ai/GeminiPlanner.kt`; **network**; **owner**: Firebase iPhone app, AI Logic, App Check with App Attest)
- [x] 40 AI answers a day per phone on the built-in planner (planner turns, picture reads, Copy from a link's sorting): "That's the limit for today. Try again tomorrow."; no limit with an own key (`ai/PlannerAllowance.kt`)
- [x] A build without Firebase: "The AI planner isn't set up in this build. Use your own key in Me › AI." (`ai/PlannerError.kt` `NotSetUp`)
- [x] Gemini and OpenRouter keys plan step by step (8 rounds); Claude and ChatGPT answer in one reply with at most one correction (`ai/own/OwnKeyApi.kt`; `ai/PlannerEngine.kt`)
- [x] Built-in errors: no connection, busy, "Google couldn't confirm this copy of the app…", "Gemini wouldn't plan from this text…", incomplete, too long, not in your country, "Something went wrong…" (`ai/PlannerError.kt`)
- [x] Own-key errors: wrong key, no credit, too many requests, model not available, no internet, no secure connection, busy, refused, not in your country, incomplete, too long (`ai/own/OwnKeyError.kt`)
- [ ] Once Pro is on sale: 2 free plans, a PRO tag on the bar, "N free plans left. Explore Pro." / "Your free plans are used. Continue with Pro." (`ai/PlannerAllowance.kt`; `ui/ai/PlannerScreen.kt` `Allowance`)

### Me › AI
- [x] "Planner answers come from": Built-in ("Google's Gemini through BoringTravel.") / Your own key (`ui/me/AiScreen.kt`)
- [x] "Using your <Provider> key · <model>" or "The built-in planner answers until a key works."; providers Gemini, Claude, ChatGPT, OpenRouter, each with its own key and model (`ui/me/AiScreen.kt` `OwnKeySection`; `ai/own/OwnAiProvider.kt`)
- [x] Saved key masked ("Key sk-…a1b2"); a hidden field "Paste your <Provider> key"; keyboard Done tests (`ai/own/OwnKeyStore.kt` `mask`)
- [x] "Get a key at <site>"; "a ChatGPT Plus or Claude Pro subscription doesn't include this" (`ui/me/AiScreen.kt` `KeyPageLink`)
- [x] Model: "Model <name>", "Each name follows the provider's newest model…", "Search or type a model id" with chips and hints; off without a key (`ui/me/AiScreen.kt`; `ai/own/OwnAiProvider.kt` `OwnAiModels`)
- [x] Models: Gemini flash / pro / flash-lite latest; Claude sonnet / opus / haiku latest; ChatGPT's latest aliases; OpenRouter's latest aliases that take tools (`ai/own/OwnAiProvider.kt`; `ai/own/OpenRouterRest.kt`)
- [x] "Test" ("Testing…", 45 s) checks the key, lists the models and asks the model a tiny question, then saves and switches: "Works. <model> answered."; a failure changes nothing (`ui/me/AiScreen.kt` `test`; **network**; **owner**: a live check with real keys)
- [x] Test errors: "Paste a key first.", "This key lists no model that can plan a trip…", "This model isn't available with your key…", "This model can't plan trips here…", "This key can't list the models. Type a model id." (`ai/own/OwnKeyError.kt` `inAiSettings`)
- [x] "Remove key" (red): this provider's key and model, "Key removed." (`ui/me/AiScreen.kt` `removeKey`)
- [x] "With your own key, the trip, its plan and your text go straight from this phone to <provider>. The key stays on this phone." (`ui/me/AiScreen.kt` `privacyNote`)
- [x] Keys kept encrypted on this phone only, out of every backup; a pasted key cleaned of spaces and quotes; Delete account wipes them (`ai/own/OwnAi.kt` `KeystoreCipher`; **iPhone API**: Keychain, this device only)
- [x] The own key also reads pictures and sorts Copy from a link (`ai/own/OwnAi.kt` `engine`)
- [x] Claude's answers streamed with one retry; Stop ends the stream (`ai/own/ClaudeKeyApi.kt`; **network**: streaming over Ktor, no Anthropic SDK for iPhone)

## 21. Copy from a link

### Ways in
- [x] Me › "Import trips from a link" ("A blog, a Google Doc or a Google Maps list") (`ui/me/MeScreen.kt`)
- [x] Trip ⋮ › Add from a link, and the Plan's "Add from a link" card, with that trip chosen (`ui/trip/TripScreen.kt`; `ui/trip/PlanTab.kt`)
- [x] The planner's "Copy the notes and pictures from…" with the address and the trip filled in (`ui/ai/PlannerScreen.kt` `SiteOffer`)
- [ ] Add to plan's "Copy its notes and pictures" and a Maps list's "Add its N places" (`ui/share/ShareToPlanSheet.kt`)
- [x] A Maps link is read at once; a blog address waits for Read; a copy running or on offer is kept (`ui/site/CopySiteScreen.kt`)

### Address and reading
- [x] "Copy from a link" with its two explanations, "Address" ("myblog.tumblr.com") with ✕, "Paste", "Read" (or keyboard Go), "Only public pages are read. Nothing is copied until you choose." (`ui/site/CopySiteScreen.kt` `AddressForm`; **iPhone API**: UIPasteboard)
- [x] "Reading…", "Reading… N posts", "Sorting N posts into trips…"; "Stop and use what's found"; Cancel (`ui/site/CopySiteScreen.kt` `Reading`; `site/SiteImporter.kt`)
- [x] Reading and copying go on after leaving the screen; coming back shows where it is (`site/SiteImporter.kt`; **iPhone API**: beginBackgroundTask; on iOS 26 BGContinuedProcessingTask with the copy's progress)
- [x] Read errors under the address: no address, can't be read, no posts, only reblogs, private Tumblr, post can't be read, Doc not shared or not found, the Maps list errors (`site/SiteReader.kt`; `links/MapsLists.kt`)

### Sources
- [x] Google Docs in any link form: through Drive when connected, else the public export with its pictures, else the published page; no comments (`site/GoogleDocs.kt`; `site/SiteReader.kt` `doc`; **network**; **owner**: Google iOS OAuth client for the Drive way)
- [x] Tumblr: name.tumblr.com, tumblr.com/name, its own domain, tmblr.co links, /tagged/x; 50 posts a page; reblogs left out and counted (`site/TumblrApi.kt`; **network**)
- [x] RSS and Atom feeds (WordPress, Blogger, Substack, Medium, Ghost, Squarespace, Wix…), older pages followed, then the sitemap when the feed is short (`site/Feeds.kt`; **network**)
- [x] A site with no feed: its dated articles through the sitemap (robots.txt respected) or "Older posts" pages (`site/SiteArticles.kt`; **network**)
- [x] Any other page as one post, without menus, comments and share buttons (`site/PageReader.kt`; jsoup has no iPhone build: Ksoup)
- [x] A link to one post copies only that post: "Only the post at this link." with "Copy the whole blog instead" (`site/SiteReader.kt`; `site/SiteImporter.kt` `readWhole`)
- [x] One long page or document becomes a note per section at its repeated headings, dates read from them ("2016-04-03", "3 April 2016", Greek dates, "Day 3") (`site/SiteSections.kt`; `site/HeadingDates.kt`)
- [x] Polite reading: 0.4 s between pages, https only, the app's User-Agent (`site/SiteWeb.kt`)
- [x] Limits per copy: 500 posts, 2,000 pictures, 60 pages, 6 MB a page, 15 MB a picture; posts copied before are counted, not read again (`site/SitePost.kt` `SiteLimits`)

### Sorting into trips
- [x] With AI: posts grouped into trips with titles ("Japan, spring 2016"), each post's places with town and country, dates for undated posts when the words say; the AI never writes a note's words (`site/SiteOrganizer.kt`; **network**)
- [x] Without AI, or when it fails: by dates, a gap over 14 days starting a new trip, undated posts together, "<Site> · May – Jun 2016" (`site/SiteOrganizer.kt` `byDates`)
- [x] A trip is cut where posts are 30 days apart; a dated post goes to the trip its date falls in (`site/SiteOrganizer.kt` `splitLong`, `placeDated`)
- [x] New posts near an earlier copy join that trip ("Adds N notes to <trip>"), its dates growing (`site/SiteOrganizer.kt` `joinExisting`)
- [x] A post about several places becomes a note per place, titled by its heading; places without a part of their own still become plan items (`site/SiteParts.kt` `cut`)

### Proposal
- [x] "N notes, M pictures from <host>" with "N already copied", "Only the post at this link.", "N reblogs left out…", "The reading stopped early…", "That is as much as one copy takes…" (`ui/site/CopySiteScreen.kt` `Proposal`)
- [x] "The AI didn't answer, so no places were found." with "Ask the AI again" (no reading again) (`site/SiteImporter.kt` `organiseAgain`)
- [x] "Organised by AI" / "Grouped by dates" / "Organised by AI, some grouped by dates" (`ui/site/CopySiteScreen.kt`)
- [x] "One trip for everything", "Notes and pictures" (off: only places, as ideas), "Add places" (a pin on each note, an item in the plan) (`site/SiteImporter.kt` `setOneTrip`, `setWithNotes`, `setAddPlaces`)
- [x] "Add to": New trip(s) or one of your trips (`ui/site/CopySiteScreen.kt` `Destination`)
- [x] Trip rows: a tick, the title or "Adds N notes to <trip>", "<dates> · N notes · M pictures", the first 6 places, a pencil to rename (80); a tap shows its notes (60, then "and N more") (`ui/site/CopySiteScreen.kt` `TripRow`, `NoteLine`)
- [x] "Copy only what is yours: your own words and pictures." (`ui/site/CopySiteScreen.kt`)
- [x] "Copy N notes" / "Add N notes" / "Add N places", "Copy the whole blog instead", "Start over"; all copied already: "This post is already copied." / "All N posts from <host> are already copied." (`ui/site/CopySiteScreen.kt`)
- [x] A Google Maps list: "N places from a Google Maps list" ("…its first N."), "Each becomes an idea in the plan, with its pin and its Google Maps link.", Add to, the list's name with rename, places with notes and addresses, "Add N places" (`ui/site/CopySiteScreen.kt` `ListProposal`)

### Copying
- [x] "Copying… X of N notes" / "Adding places… X of N", a bar, "N pictures so far. You can leave this screen: the copy goes on.", Cancel keeping what's copied (`ui/site/CopySiteScreen.kt` `Copying`)
- [x] Each note saved whole or not at all; a new trip made just before its first note (`site/SiteImporter.kt` `copyTrip`)
- [x] Into one of your trips: notes in time order, days unchanged, a place on its day inside the trip, else an idea (`site/SiteImporter.kt` `merged`, `planDay`)
- [x] Result: "Copied" / "Added" / "Stopped", counts of notes, pictures, trips and places, what was skipped; each trip opens (Journal, or Plan with no notes); Done back to Trips (`ui/site/CopySiteScreen.kt` `Done`)
- [x] Notes keep the words exactly: title, date, bold, italic, underline, strike, links, headings, quotes, lists, pictures with captions, dividers, embeds as links, tables as rows, tags as a last line (`site/SiteNotes.kt`; `site/HtmlBlocks.kt`; **engine**: doc/EntryDoc.kt)
- [x] Pictures at the largest size offered, 3 at a time; odd formats kept as JPEG; SVG and pictures over 15 MB skipped; their time and place kept (`site/AppSiteStore.kt` `savePicture`; **iPhone API**: ImageIO; iPhone: a HEIC kept as HEIC and an AVIF made a JPEG as Android does, the time and place read from every format)
- [x] Copying again never doubles notes, pictures or items (ids from the addresses, UUID v3 byte for byte on both phones) (`site/SiteNotes.kt` `entryId`)
- [x] Each place found is looked up (its town first), pinned on its note and put in the plan on its day; a past day's place ticked; 60 lookups, one a second (`site/SitePlaces.kt`; `site/SiteNotes.kt` `placeItem`; **engine**: places/NearTrip.kt; **network**)
- [x] A place already in the plan (same name within 200 m) isn't added; one deleted earlier stays deleted (`site/AppSiteStore.kt` `addPlanItem`)

## 22. Community

Every line here needs **network** (Firestore and Firebase Auth) and a real account waits on the **owner** for the Firebase iPhone app (with Google's iPhone client and the Drive API key). Since round 24 the lines ticked here are built and tested against fakes (Firebase Auth, Firestore holding the owner's rules, the posters' Drives in memory), as Backups' Drive lines are since round 23: Firestore and Firebase Auth over their REST APIs (`firebase/FirestoreRest.kt`, `community/FirestoreCommunity.kt`, `community/CommunityAuth.kt`) behind Android's own `CommunityRepository`, the screens and rules Android's, read in.

### Signing in
- [x] Community tab (`ui/nav/TripNav.kt` `HomeTab`; `ui/community/CommunityScreen.kt`)
- [x] A build without Firebase: "Not in this build · The community needs the app from…" (`ui/community/CommunityScreen.kt`; iPhone: "…from the App Store.")
- [x] Signed out: three drawn postcards, "Trips, clips and photo albums from other travellers.", five lines of what it does, "Report or block anyone, any time." (`ui/community/CommunityScreen.kt` `SignInGate`; iPhone `ui/community/CommunityPhone.kt`, with the contact line under it, 1170)
- [x] Quiet sign-in ("Signing in") when Google is already signed in (`community/Community.kt` `signIn`; iPhone: also with the Sign in with Apple account)
- [x] "Sign in with Google to see and post…": Google, Drive and Firebase in one tap; errors in red (`ui/community/CommunityParts.kt` `SignInCard`; Apple 4.8: Sign in with Apple beside it; iPhone: Google's page, then Firebase's signInWithIdp with its ID token)
- [ ] Posts, profiles and follow lists need sign-in to read; liking needs sign-in; posting and following need a username (`ui/community/PostScreen.kt`; `ui/community/FollowParts.kt`; `android-boringtravel/firebase/firestore.rules`; all but posting's username, round 25)
- [x] Signing out of Google signs out of the community (`AppGraph.kt`; iPhone `ios/App.kt`: the account's `onSignedOut`)

### Feed
- [x] Header "BoringTravel / Community", + ("Post to community"), your avatar (your profile, or Community profile) (`ui/community/CommunityScreen.kt` `CommunityHeader`)
- [x] Latest · Following · Top (`ui/community/FeedViewModel.kt`)
- [x] Latest: newest first, 20 a page, the next page 4 rows before the end (`community/FirestoreCommunity.kt` `latest`)
- [x] "Pick a username · You need one to post and follow. Liking works without." with Make my profile (`ui/community/CommunityScreen.kt`)
- [x] Pull to refresh (also re-reads your profile and blocks); skeletons while loading (`ui/community/FeedViewModel.kt` `refresh`; `ui/community/CommunityParts.kt` `SkeletonCard`)
- [x] Offline: "Offline · showing what was saved", or "You're offline · Connect to see the community." with Try again (`community/CommunityRepository.kt`)
- [x] "Couldn't load" / "Couldn't load more" with Try again; "That's all" (`ui/community/CommunityScreen.kt`)
- [x] Empty: "Nothing here yet · Be the first: post a trip, a clip or photos." with Post (`ui/community/CommunityScreen.kt`)
- [x] Lists reload after your post, delete or block and on another sign-in; blocked people's posts left out everywhere (`ui/community/FeedViewModel.kt`; `community/Leaderboard.kt`)
- [x] Post card: 4:5 cover (a Drive photo, else a drawn scene), CLIP / ALBUM · 12 / PLAN · 12 DAYS, play mark on clips, title, "@username" (opens the profile) · "5m" / "3h" / "2d" / "12 Mar 2026", heart and count, 4 flags, "12 days · 1,240 km" (`ui/community/CommunityParts.kt` `PostCard`; `community/CommunityModel.kt` `PostText`)
- [x] Counts 999, 1.2k, 12k, 1.2m (`community/CommunityModel.kt` `count`)
- [x] Pictures read from the poster's Drive by link with the app's API key, never your token; 150 MB cache (`community/Community.kt` `images`; `google/PublicDrive.kt`; iPhone `community/CommunityPictures.kt`, `google/PublicDrive.kt`: the key with the bundle id's header, else Google's public download address; **owner**: the Drive API key restricted to the iPhone app)
- [x] Top: "This month" / "All time", most liked first, equal likes share a rank, gold, silver, bronze, "Most liked · Nov 2026"; rows with rank, cover, title, "@username · facts", hearts; "No posts this month yet" (`community/Leaderboard.kt` `rank`; `ui/community/CommunityScreen.kt` `TopRow`)
- [x] Following: posts of the people you follow (your newest 150), newest first; "Showing posts of the 150 people you followed last." (`community/FollowingFeed.kt`; `community/Follows.kt`)
- [x] Following empty: "Follow travellers…" / "Nothing new yet…", then "Most liked travellers", 5 with Follow (`ui/community/FeedViewModel.kt` `suggest`)

### Likes and the post page
- [x] Heart: likes or unlikes at once with a pop and a haptic tick; the same on feed, post and profile; your own posts show the count only (`ui/community/CommunityParts.kt` `HeartButton`; `community/Leaderboard.kt` `LikeBook`; **iPhone API**: UIFeedbackGenerator)
- [x] Offline likes kept and sent later; a refused like shows the server's state (`community/Community.kt` `toggleLike`)
- [x] Pictures swiped at 4:5 with dots, back and ⋮ over them, a plain bar fading in past them (`ui/community/PostScreen.kt` `Hero`)
- [x] Head: title, avatar and "@username", "Posted 3d ago", a large heart, flags, "12 days · 1,240 km · Mar 2027", countries, caption (`ui/community/PostScreen.kt` `PostHead`)
- [x] Trip post: "The plan · N days · M items", a small map with the planned places and a dashed route, "Day N" rows (kind or place photo, time, title, place), then Ideas or Places (`ui/community/PostScreen.kt` `tripContent`; `ui/community/PostViewModel.kt` `planMap`; **iPhone API**: MapLibre; iPhone `ios/MapLook.kt` and `ios/BtMap.kt` `compactRoute`: dots and a thin dashed route, as Android's)
- [x] Trip post notes: cards with day, date, title, text and photos; album: "Photos · N", 3 a row (`ui/community/PostScreen.kt` `NoteCard`; `ui/community/CommunityParts.kt` `PictureGrid`)
- [x] "This trip's plan can't be read. Update BoringTravel to see it."; "This post is gone · Its poster deleted it."; "Couldn't load" (`community/SharedTrip.kt`; `ui/community/PostScreen.kt`)
- [x] A blocked poster: "You blocked @x…" with Unblock (`ui/community/PostScreen.kt`)
- [x] Photo viewer: full screen, swipe, "3 / 12", close (`ui/community/MediaViewer.kt` `PhotoViewer`)
- [x] A clip: downloaded from the poster's Drive (200 MB; 6 clips or 300 MB kept), full screen with sound, looping; "Couldn't load the clip. Try again." (`ui/community/PostViewModel.kt` `loadClip`; **iPhone API**: AVPlayer; iPhone `community/CommunityPictures.kt` `clip`, played by the app's ClipViewer from the caches, `ios/PhotoViewer.kt` `openAt`)
- [x] "Share": "<Title> · @user on BoringTravel" and the post's link (`community/CommunityLinks.kt` `shareText`; **iPhone API**: share sheet)
- [x] "Copy this trip" on others' posts with a plan ("Copies the plan into your trips, without the journal."): first-day picker with Copy, "Without dates", Cancel; ideas-only plans copy at once (`ui/community/CommunitySheets.kt` `CopyTripDialog`; `community/TripCopy.kt`)
- [x] The copy: the post's title, Day N on start + N − 1, nothing ticked, travel and links kept; "Copied to your trips", then its Plan; "Couldn't copy the trip. Try again." (`community/TripCopy.kt` `build`; **engine**: clip/TravelModes.kt)

### Report and block
- [x] ⋮ on someone else's post: "Report post" and "Block @user" (red) (`ui/community/PostScreen.kt` `MoreMenu`)
- [x] "Report this post" / "Report @user": Spam, Inappropriate, Hate or harassment, Other; "Report sent. Thank you."; offline "Saved. It goes out when you're online." (`ui/community/CommunitySheets.kt` `ReportSheet`; Apple 1.2: already there)
- [x] "Block @x?… They aren't told." with Block; from a post the page closes (`community/Community.kt` `block`; Apple 1.2: already there)
- [x] Blocking hides their posts everywhere, their lists, post page and profile; follows end both ways, none across a block (`community/FirestoreCommunity.kt` `block`)
- [x] Unblock from their post or profile, profile ⋮, or the Blocked list (`community/Community.kt` `unblock`)
- [x] A contact address in the community screens (not on Android yet; Apple 1.2 asks for one) (`ui/me/AboutApp.kt` `CONTACT`; iPhone `ui/community/CommunityPhone.kt` `CommunityContact`: "Something wrong? Write to …" under the sign-in card and in the Report sheet, a tap writes a mail)

### Posting
- [ ] + opens "Post a trip": your own trips, newest first; "Your trips show here. Demo trips can't be posted." with New trip (`ui/community/CommunityScreen.kt` `TripPickerSheet`)
- [ ] Demo trip: "Demo trips can't be posted…"; deleted trip: "This trip is gone…" (`community/PostDraft.kt` `blocked`)
- [ ] What's still needed, in order: sign in, "Couldn't check your profile…", "Pick a username first…", "Connect Google Drive…" (only when something goes to Drive) (`ui/community/ComposeScreen.kt` `ComposeForm`; **owner**: Google iOS OAuth client)
- [ ] "What to share": Trip · Clip · Album (`community/CommunityModel.kt` `PostKind`)
- [ ] Trip: "N days · M items in the plan", "Places, times and links go. Plan notes, bookings and your route don't.", "Include notes" with a list of your notes, "Up to 30 photos…" (`ui/community/ComposeScreen.kt` `tripChoices`; `community/PostDraft.kt`)
- [ ] Clip: the trip's saved clips, newest first; "No clips of this trip yet." with Make a clip (`ui/community/ComposeScreen.kt` `ClipChoices`)
- [ ] Album: your photos 4 a row, picks numbered, "Pick up to 30", "N picked"; "No photos in this trip yet." (`ui/community/ComposeScreen.kt` `albumChoices`)
- [ ] Cover row: the drawn picture and your photos, the picks, or the clip's frame (`community/PostDraft.kt` `coverChoices`)
- [ ] Title (80, the trip's name) and Caption ("What was it like?", 500) with counters (`ui/community/CommunityParts.kt` `CommunityField`)
- [ ] Preview: the card as the feed will show it (`community/PostDraft.kt` `preview`)
- [ ] Why it can't post yet: no plan, no photos, no clip, no title, too long, over 30 photos, pick the cover again (`community/PostDraft.kt` `problem`)
- [ ] "Changed the trip later? Its ⋮ › Community post › Update now updates this post."; "Photos go up smaller and without their location, to BoringTravel/Community in your Google Drive…" (`ui/community/ComposeScreen.kt`)
- [ ] "Post" only when everything is ready; the first time it shows the community rules (`ui/community/CommunitySheets.kt` `RulesSheet`)
- [ ] Upload card: "Making a folder in your Drive", "Uploading photos · 3 of 12", "Uploading the clip", "Posting", "Posted"; "Keep this screen open until it's posted." (`community/PostUploader.kt`; **iPhone API**: beginBackgroundTask)
- [ ] Photos as 1600 px JPEG, quality 85, upright, without EXIF; a clip's cover from 40 % in (`community/CommunityMedia.kt` `PostPhotos`; **iPhone API**: ImageIO, AVAssetImageGenerator)
- [ ] Drive folder BoringTravel/Community/"<title> <date>", link-shared, with photo-N.jpg and clip.mp4; a plan post with a drawn cover needs no Drive (`community/CommunityMedia.kt` `DrivePostStore`)
- [ ] Failure: "Try again" carries on and never posts twice, or "Stop"; Back asks "Stop posting?…" and deletes what went to Drive (`ui/community/ComposeViewModel.kt` `post`, `stop`)
- [ ] Too big: notes cut to 1,500 characters, then the last notes, then ideas; else "This trip's plan is too long to post." (`community/SharedTrip.kt` `fit`)
- [ ] A trip post carries day numbers (no dates), titles, kinds, places, pins, times, one link, travel and the chosen notes; never the route (only its km), stops, ticks, plan notes, where notes were written, or others' notes (`community/SharedTrip.kt` `build`)
- [ ] Facts: days, km (the route over 1 km, else straight lines by the plan's rules), countries in order, the first month (`community/PostDraft.kt` `days`, `km`; **engine**: model/TravelDefault.kt, places/Geo.kt)

### Your posts
- [ ] "Delete post": "Delete this post?… its photos leave your Drive." with Delete ("Post deleted. Its photos stay in your Drive…" without Drive); offline fails (`community/Community.kt` `deletePost`)
- [ ] Trip ⋮ › Community post: the post's title, "Last updated 5 minutes ago", "The trip changed since then." / "The post shows the trip as it is now." (`ui/community/PostSyncSheet.kt`; `community/PostSync.kt`)
- [ ] "Update now": the plan and the chosen notes (new notes too when all were picked), only new photos up and dropped ones out; title, caption, likes and link stay; its answers ("Updated.", "Already up to date.", …) (`community/PostSync.kt` `updateNow`)
- [ ] "Open the post", "Stop updating it"; nothing goes up by itself (`ui/community/PostSyncSheet.kt`)
- [ ] The trip-to-post link stays on this phone, out of backups (`community/PostLinks.kt`)

### Profiles and following
- [ ] Profile: avatar (traveller figure or initial), "@username", name, bio; posts · likes · followers · following, the last two opening their lists (`ui/community/ProfileScreen.kt` `ProfileHead`)
- [ ] Posts grid, 2 columns (`ui/community/ProfileScreen.kt` `ProfileTile`)
- [ ] "No posts yet · Post a trip, a clip or photos from a trip's ⋮ menu." / "@x hasn't posted yet."; "No profile here" (`ui/community/ProfileScreen.kt`)
- [ ] Your own: Edit profile, ⋮ Delete profile (red); someone else's: Follow, ⋮ Report @x, Block @x / Unblock (`ui/community/ProfileScreen.kt`)
- [ ] Follow / Following with a tick, one tap, the count moving at once; without a username: "Pick a username to follow people."; "Couldn't follow. Try again."; offline queued (`ui/community/FollowParts.kt`; `community/FollowKeeper.kt`)
- [ ] Followers · Following: avatar, "@username", name, 30 a page, blocked and deleted left out, their empty texts (`ui/community/FollowListScreen.kt`)

### Community profile
- [ ] Signed out: what a profile is, with the sign-in card; "Couldn't check your profile" with Try again (`ui/community/ProfileEditScreen.kt`)
- [ ] Picture: "Initial" / "My traveller", Head & shoulders / Whole figure, "Turn" in 5° steps, "Who and their look" (the traveller sheet "Your picture") (`ui/community/ProfileEditScreen.kt` `AvatarChoices`; `community/ProfileAvatar.kt`)
- [ ] Username "@" ("3–20 letters, numbers, _ and ."), checked live ("@x is free", "@x is taken. Try another.", "Checking…"), its rules and reserved names (`ui/community/ProfileEditScreen.kt` `onUsername`; `community/Usernames.kt`)
- [ ] First profile: the name from Google, a username suggested from it or the email (`community/Usernames.kt` `suggest`)
- [ ] Name (40) and Bio (150); "Make my profile" / "Save" → "Profile made" / "Saved"; "Others see your username, name, bio and picture." (`ui/community/ProfileEditScreen.kt`)
- [ ] A new username frees the old one and renames your posts (`community/FirestoreCommunity.kt` `saveProfile`, `renamePosts`)
- [ ] Blocked list with Unblock, or "Nobody. People you block show here." (`ui/community/ProfileEditScreen.kt`)
- [ ] "Community rules" before the first profile or post (be kind; only your own photos and clips; no nudity, hate, violence or spam; breakers removed; photos in your Drive), Agree / Not now (`ui/community/CommunitySheets.kt` `RulesSheet`)
- [ ] "Delete profile": "Delete your profile?… This can't be undone." ("Deleting…") → "Profile deleted"; offline fails before deleting anything (`community/Community.kt` `deleteProfile`)
- [ ] Server limits: title 1–80, caption 500, name 40, bio 150, 30 photos, 30 countries, 0–400 days; posts only as yourself with 0 likes; one like each; no user search (`community/CommunityModel.kt` `CommunityLimits`; `android-boringtravel/firebase/firestore.rules`)

## 23. Travel together

Every line here needs **network** (Drive and Firestore) and waits on the **owner** for the Google iPhone sign-in client and a Drive API key restricted to the iPhone app; build it against a fake Drive first.

### Sharing and joining
- [ ] Trips' last row "Join a friend's trip · Paste the link they sent you": "Join a trip", "Paste the link or the whole message", "That doesn't look like a BoringTravel trip link.", Next / Cancel (`ui/together/JoinTripRow.kt`)
- [ ] Example trips: "Example trips can't be shared. Make a trip of your own first." (`ui/together/TogetherSheet.kt`)
- [ ] "Travel together · Plan and write this trip with friends", what it does, the Drive note, "Invite" / "Connect Drive and invite" (`ui/together/TogetherSheet.kt`)
- [ ] Invite runs only the missing Google steps, makes BoringTravel/Shared/<trip>/trip.btshare (link-shared) and opens the share sheet; photos start uploading (`ui/together/TogetherGoogle.kt`; `together/TogetherEngine.kt` `share`; **iPhone API**: GoogleSignIn, share sheet)
- [ ] Invite text "Join my trip “<Title>” on BoringTravel, so we can plan it together:" with the link, the same link each time (`ui/together/TogetherSheet.kt` `TogetherText.invite`)
- [ ] "Join “<Title>”": "<Name> invited you to plan this trip together…" with Not now / Join (`ui/together/JoinSheet.kt`)
- [ ] Join reads the inviter's copy first, adds the whole trip, turns demo mode off, then "Saving your copy of the trip to your Google Drive…" (`together/TogetherEngine.kt` `open`)
- [ ] "You're in": "Your changes reach the others by themselves." / "One more step: send <Name> your link once…" with Send my link / "…To add your changes, connect Google Drive." with Connect Drive (`ui/together/JoinSheet.kt`)
- [ ] Send-back text "I joined “<Title>”. Open this so my changes show on your phone too:" (`ui/together/TogetherSheet.kt` `sendBack`)
- [ ] A member's link: "Add <Name> to “<Title>”?" with Add, then "<Name> is in…"; a known link: "Already here" (`ui/together/JoinSheet.kt`; `together/TogetherEngine.kt` `addLocked`)
- [ ] Join errors: already on this phone, link dead, doesn't match, "<Name> left this trip…", "This trip already has 12 people…", "Couldn't open the trip. Try again." (`together/TogetherEngine.kt`)
- [ ] Joining a trip you left carries on as the same member (`together/TogetherEngine.kt` `open`)
- [ ] Trip mailbox: members pick up a newcomer without a link back; snackbar "Maria joined Spain 2027" ("Maria and Nikos…", "…and 2 more…", "A friend…"); without Firebase the link-only way still works (`together/TogetherMail.kt`; `together/FirestoreTripMail.kt`; `ui/nav/AppNav.kt`; **owner**: Firebase iPhone app)

### Members and sync
- [ ] Members: "You" (or "Viewing only") first, then "Left the trip", "Can't reach their copy", "Viewing only", "Changed 2 min ago", "Joined" (`ui/together/TogetherSheet.kt` `MemberRow`)
- [ ] "Syncing…", a failure in red, "Up to date · just now / 2 min ago / yesterday", "Not synced yet" (`ui/together/TogetherSheet.kt` `SyncLine`)
- [ ] "Invite" and "Sync now"; "Friends who open your link and join show up here."; "Not showing up for the others? Send your link" when the mailbox can't carry it (`ui/together/TogetherSheet.kt`; `together/TripMail.kt` `offerLink`)
- [ ] Joined without Drive: "…To add your changes, connect Google Drive." with Connect Drive / Try again (`ui/together/TogetherSheet.kt`)
- [ ] No roles: everyone adds, changes, writes and adds photos; no removing members or stopping the share, only leaving; 12 people at most; a member is named by their Google first name (`together/ShareCodec.kt` `MAX_MEMBERS`; `together/Together.kt` `myName`)
- [ ] While the trip is on screen: a sync every 10 s and the mailbox watched; stops in the background, syncs on return (`together/Together.kt` `keepInSync`)
- [ ] Every change, from any screen, sent about 4 s later and kept through closing the app, waiting for a connection and retrying (`together/SendQueue.kt`; `together/Together.kt` `sendSoon`; **iPhone API**: BackgroundTasks, background URLSession)
- [ ] At app start, shared trips that ended in the last 30 days sync; then every 3 h in the background (`together/Together.kt` `syncAll`, `schedule`; **iPhone API**: BGAppRefreshTask, best effort)
- [ ] An unchanged copy isn't downloaded or written again (`together/TogetherEngine.kt` `sync`, `publish`)
- [ ] Others' photos arrive after their rows and appear in place; failed downloads retry from 1 min up to 6 h (`together/TogetherEngine.kt` `fetchPhotos`; `ui/components/MediaImage.kt`)
- [ ] Your photos go as 1600 px copies, videos as they are; with Only on Wi-Fi, videos over 25 MB wait for Wi-Fi (`together/TogetherEngine.kt` `sendPhotos`)
- [ ] Each member's copy in their own Drive (trip.btshare and photos, link-shared), read by the others with the app's API key; nobody signs in to another's Drive; a deleted copy is made again (`together/Together.kt` `DriveOwn`; `google/PublicDrive.kt`; `together/ShareCodec.kt`)
- [ ] "Shared trips can't be read right now. Update BoringTravel, or try again later." (`google/PublicDrive.kt` `check`)

### Merges
- [ ] The later edit wins per row (item, note, photo, trip details); a tie keeps this phone's; deletions travel (`together/TogetherMerge.kt`; **engine**: backup/BackupFormat.kt)
- [ ] Nobody else can delete or hide the trip on your phone; your tracking level stays yours (`together/TogetherMerge.kt` `trip`)
- [ ] Friends' notes are listed in the trip's Journal, marked "By <Name>" and read only; in Media only your own photos can be deleted (`ui/journal/TripTabs.kt`; `ui/journal/EntryScreen.kt`; `ui/trip/TripMedia.kt`)
- [ ] Bad files: "This shared trip is damaged.", "This isn't a BoringTravel trip.", "A member has a newer BoringTravel…", "…too big to open.", "This trip is too big to share."; a single bad row is mended (`together/ShareCodec.kt`)

### Leaving
- [ ] "Leave trip": "Leave this trip?… The others keep it, with what you added. Your copy in Drive stops being shared." with Leave; its calendar events go (`ui/together/TogetherSheet.kt`; `data/Deletions.kt` `leaveTrip`)
- [ ] A shared trip nobody joined is deleted normally and sharing stops (`data/Deletions.kt` `deleteTrip`)
- [ ] After leaving: the others see "Left the trip", your photo folder is unshared, your mailbox entry removed; offline it finishes at the next sync (`together/TogetherEngine.kt` `leaveLocked`, `sayGoodbye`)
- [ ] Shared trips' state goes into backups, and a restore brings them back (`together/TogetherEngine.kt` `exportState`, `importState`; **engine**: backup/BackupFormat.kt `sharedTrips`)

## 24. Route tracking

### Me › Route tracking
- [x] Me row "Route tracking" with its level, Off by default; nothing asked at first start (`ui/me/MeScreen.kt`; `data/settings/Settings.kt` `trackingLevel`)
- [x] "Now" card: a status line and "Start recording" / "Stop for today" (on a trip day with location allowed) (`ui/map/TrackingPermissionFlow.kt` `RecordingNow`)
- [x] Status lines: "Off. Pick a level…", "No trip today. Recording starts when you open BoringTravel during a trip.", "Off for <trip>.", "Waiting for location permission…", "Stopped for today · <trip>", "Recording <trip> · <Level>", "Not recording <trip> yet." (`ui/map/TrackingPermissionFlow.kt` `recordingText`)
- [x] "For every trip": Off ("You add places yourself."), Low ("A point about every 30 min. Lightest on battery."), Balanced ("About every 10 min while you move."), Detailed ("Every few seconds while you move. Best for clips; uses the most battery.") (`ui/map/MapScreen.kt` `TrackingSettingsScreen`; **engine**: model/Kinds.kt `TrackingLevel`)
- [x] The explainer: "Records where you go during a trip's dates… Stop there pauses it until tomorrow…" (`ui/map/TrackingPermissionFlow.kt` `howItRecords`)
- [x] The level is this phone's own, not in backups (`data/settings/Settings.kt` `backupPreferences`)
- [x] Trip map › "Route tracking": "Starts on <date>." before the trip; Off / Low / Balanced / Detailed for this trip only; status and Start / Stop on a trip day; its footnote (`ui/map/TripMapBoard.kt` `TrackingLevelSheet`; `tracking/TrackingController.kt` `setTripLevel`; **engine**: data/db/Entities.kt)

### Permissions
- [x] One step at a time, the system prompt only after "Allow": "Precise location" ("Your route needs your exact position…"), then "Notifications" ("A quiet notification shows while your route records…") (`ui/map/TrackingPermissionFlow.kt`; `tracking/TrackingPlan.kt` `missing`; **iPhone API**: Core Location When In Use with a purpose string; iPhone: Precise location is While Using with Precise Location on, then Notifications, as Android's; an Always step is built behind `IosTrackingPlan.ASK_ALWAYS`, off as Android's background recording)
- [x] Refused: "Open settings" (`ui/map/TrackingPermissionFlow.kt` `ask`; **iPhone API**: openSettingsURLString)
- [x] "Location is off" / "The route pauses until you turn it on." with "Turn on" (`ui/map/TrackingPermissionFlow.kt`; iPhone: opens the app's settings)
- [x] "Battery settings" ("If points go missing, let BoringTravel run without battery limits.") (`tracking/TrackingPermissions.kt`; iPhone has no such setting: left out)
- [x] Checked again on every return to the app (`ui/map/TrackingPermissionFlow.kt`)

### Recording
- [x] Records only during your own trip's dates, never the examples; a trip's own level wins over Me's (`tracking/TrackingPlan.kt` `target`, `levelFor`)
- [x] Starts when the app comes on screen during a trip (or Start recording), then goes on in the background until Stop, the trip's last day or Off (`tracking/TrackingController.kt` `apply`; `tracking/RouteRecordingService.kt`; **iPhone API**: Core Location background mode, the blue location pill; **owner**: GPS and battery test on a real iPhone, review notes for background location)
- [x] Stop pauses this trip until tomorrow or Start recording (`tracking/TrackingController.kt` `stopForToday`)
- [x] A level changed while recording applies at once (`tracking/RouteRecordingService.kt` `update`)
- [x] Low: a point about every 30 min / 500 m (`tracking/RouteRecordingService.kt` `requestFor`; **iPhone API**: significant-change location; iPhone: Wi-Fi and cell towers every 500 m, kept no sooner than 15 min apart as Android's; the significant-change service only on the Always path, off by default)
- [x] Balanced: a point every 10 min / 150 m, plus a best fix about every 20 min, slowing to 40 and 60 while you stay put (`tracking/BestFixAlarm.kt`; `tracking/TrackingPlan.kt` `bestFixDelayMs`)
- [x] Detailed: every 15 s / 15 m, resting after 10 min still and waking on movement (`tracking/RouteRecordingService.kt` `rest`, `wake`; **iPhone API**: Core Location; iPhone: it wakes on the first fix that moved, Android's way without a motion sensor (Core Motion would ask the Motion question))
- [x] Each point keeps time, position, accuracy, speed, altitude and level; bad fixes dropped; points stay on the phone and go into backups (`tracking/LocationUpdatesReceiver.kt` `TrackWriter`; **engine**: data/db/Entities.kt, backup/BackupFormat.kt)
- [x] Checked again on app open, a level or date change, after a restart or update, and nightly (`tracking/TrackingController.kt` `start`; `tracking/BootReceiver.kt`)

### From points to a route
- [x] Spike filter: fixes worse than 1 km, impossible jumps and out-and-back spikes dropped; fast straight legs kept; raw points never changed (`tracking/SpikeFilter.kt`)
- [x] The route splits at gaps (25 km, or 2 km after 3 h) and is simplified to 4 m (trip map) or 25 m (all trips) (`tracking/RouteShape.kt` `pieces`)
- [x] Distances "650 m", "18.4 km", "212 km" (`tracking/RouteShape.kt` `formatDistance`)
- [x] Stops: stays of 20 min within 150 m; one stray fix ignored; stays at one spot within 30 min joined; a stay stays open while recording (`tracking/StopDetector.kt` `detect`)
- [x] Your own stops never changed; a deleted suggestion never comes back; your stop within 1 km covers a stay (`tracking/StopDetector.kt` `merge`)
- [x] Automatic stops named by town and every stop given a country (offline outlines, then the geocoder, 12 a run) (`tracking/TrackProcessor.kt` `processNow`; `geo/ReverseGeocoder.kt`; **iPhone API**: CLGeocoder; **network**)
- [x] Processing when a map opens, and nightly for today's trip and those ended in the last 3 days (`tracking/TrackProcessor.kt`; **iPhone API**: BGProcessingTask, best effort, plus on app open)

## 25. Notifications

- [x] Notifications asked only by the route tracking flow (`ui/map/TrackingPermissionFlow.kt`; **iPhone API**: UserNotifications)
- [x] "Recording your route · <Level>" with the trip and Stop while recording; a tap opens the app (`tracking/TrackingNotifications.kt` `recording`; iPhone: a Live Activity in place of the ongoing notification: on the Lock Screen and in the Dynamic Island, "Recording your route · <Level>", the trip and Stop (iOS 17; on iOS 16.1 to 16.x no button and a tap opens Me › Route tracking), beside the blue location sign; **iPhone API**: ActivityKit, App Intents)
- [x] "Your route isn't recording" / "<trip> · Open BoringTravel to go on." when recording stopped without you asking (`tracking/TrackingNotifications.kt` `showPaused`)
- [x] "Location is off" / "<trip> · The route pauses until you turn it on." while recording with location off (`tracking/BootReceiver.kt` `LocationWatch`)
- [x] At most once a night: "Location is not allowed for BoringTravel, so the route isn't kept."; a tap opens the app's settings (`tracking/TrackingController.kt` `checkPermissionNote`; **iPhone API**: BGAppRefreshTask, best effort)
- [x] Two kinds, "Route recording" and "Route notes": silent, no badge, the plane icon (`tracking/TrackingNotifications.kt` `ensureChannels`; iPhone: passive interruption level)

## 26. Widgets and shortcuts

- [x] "Today" widget ("Today's trip, and quick Write, Photo and Voice."): the logo on the theme's yellow, the trip (else "Today"), "Day 4 of 9 · 2 written today" or "No trip today · <date>", Write, Photo (when wide), Voice, in the theme's colours (`widget/TodayWidget.kt`; `widget/WidgetModel.kt` `todayLine`; **iPhone API**: WidgetKit; iPhone: small (Write, Voice) and medium (Write, Photo, Voice))
- [x] "Quick bar" ("A note or a photo in one tap, with today's trip."): the theme's paper and texture, the logo stamp, two lines of context, a "Note" pill, a round Camera, "Voice note" when wide (`widget/QuickWidget.kt` `QuickBar`; `widget/WidgetModel.kt` `WidgetLook`; **iPhone API**: WidgetKit medium)
- [x] Quick bar context: "Day 4 · Naxos" (the day's name, else its stops or places), "Day 4 of 9", "Today"; with no trip "No trip today" over "Cyclades in 12 days" / "Cyclades tomorrow" (60 days) or the date (`widget/WidgetModel.kt` `context`; **engine**: model/DayNames.kt)
- [x] As the bar narrows, Voice goes first, then the stamp, then the text; Note and Camera stay (`widget/QuickBarLayout.kt`)
- [x] "Quick bar, small" ("Note and Camera in one tap.") (`widget/QuickWidget.kt` `QuickSmallWidget`; **iPhone API**: WidgetKit small)
- [x] Write / Note: a new note in today's trip (outside trips when none) (`widget/Deeplink.kt`; `ui/nav/AppNav.kt`; **iPhone API**: widget URLs, App Intents; iPhone: links on the medium widgets, an App Intent per button on the small ones (iOS 17; one tap target, Write, on iOS 16))
- [x] Photo / Camera: the camera, then the photo as a new note in Write at its own time (within 2 min of the camera) and place; a closed camera creates nothing; "The photo couldn't be added." (`ui/editor/TakePhoto.kt` `rememberQuickCamera`, `PhotoEntries.create`; `data/repo/PhotoTime.kt` `forShot`; iPhone: the app opens, then the camera)
- [x] Voice: Write with dictation started (`ui/nav/AppNav.kt` `Deeplink.VOICE`)
- [x] A tap on the Quick bar: today's trip on its Journal (`widget/Deeplink.kt`)
- [ ] Widgets redraw after changes to trips, notes, plan, stops, theme or demo mode, and hourly; the gallery preview shows "Day 14 · El Chaltén" (`widget/WidgetUpdates.kt`; **iPhone API**: WidgetCenter, App Group; **owner**: the App Group in the Apple Developer account)
- [x] Long press on the app icon: Write, Take a photo, Voice note, New trip (`widget/Shortcuts.kt`; **iPhone API**: Home Screen Quick Actions)

## 27. Sharing into the app

- [ ] BoringTravel in other apps' share sheets for text, links and 1–10 pictures ("Plan from picture") (`app/src/main/AndroidManifest.xml`; `MainActivity.kt` `receiveShare`, `receivePictures`; **iPhone API**: Share Extension with an App Group; **owner**: extension and App Group in the Apple Developer account)
- [ ] Short text with a link (280 characters, 4 lines) → Add to plan; a file, a Google Docs link or long text (30,000) → the planner; a few words with no link → "Nothing to add: share a link"; pictures → the planner; a join link → the Join sheet (`links/SharedLinks.kt` `route`; `ui/nav/AppNav.kt`)
- [ ] The link taken: the first Google Maps link, else the first link; its name the words beside it (the Maps app's place name), else the page subject (`links/SharedLinks.kt` `parse`)
- [ ] "Add to plan" sheet with the link's preview (`ui/share/ShareToPlanSheet.kt`; **network**)
- [ ] Trip chips: your trips, the one on now first, else the next, the newest undated, the latest; examples only when you have none ("No trips of your own yet. Add it to a demo trip?"); "Untitled trip" (`ui/share/ShareTargets.kt` `trips`, `defaultTrip`)
- [ ] Day chips: each trip day (today marked "·") and Ideas; today during the trip, else its first day; Ideas for an undated trip (`ui/share/ShareTargets.kt` `defaultDay`)
- [ ] Place / Food / Stay, Food or Stay first when the name says so (`ui/share/ShareTargets.kt` `defaultKind`; **engine**: model/Kinds.kt)
- [ ] "Add": the item at the end of that day, named by the Maps app's name, else the page title; a Maps link kept as its Maps link, others as its website; then the Plan on that day (`ui/share/ShareToPlanSheet.kt` `add`; `ui/nav/AppActions.kt` `openPlanDay`)
- [ ] A short Maps link added gets its pin afterwards (`data/repo/Repositories.kt` `fillFromLink`; **network**)
- [ ] No trips: "No trips yet. Start one, then share the link again." with New trip (`ui/share/ShareToPlanSheet.kt`)
- [ ] A blog, a site's front page or a Google Doc also offers "Copy its notes and pictures" (`site/SiteLinks.kt` `looksLikeSite`)
- [ ] A Maps list: "A Google Maps list of N places. Each becomes an idea in the plan." with "Add its N places" / "Add them to a new trip" (`ui/share/ShareToPlanSheet.kt`)

## 28. Me and every setting

- [x] Me tab: "BoringTravel" over "Your space" (`ui/me/MeScreen.kt`)
- [ ] In order: Account card; Theme, Titles and dates, Text; Community profile, Voice notes, Route tracking, Backup & sync, Import trips from a link, AI, Your traveller & rides, Trips look, Plan view, Place photos, Demo mode, BoringTravel Pro (only on sale); Privacy policy, Version; "Boring travel, when everything can be interesting." (`ui/me/MeScreen.kt`)
- [x] Each row: a title, a one-line value and an arrow, the whole row tappable (`ui/me/MeScreen.kt` `MeRow`)
- [x] "Voice notes": "Phone language" or the language; the sheet "Choose the language you speak in notes and planner chat.": Phone language · <name>, English (en-US), Ελληνικά (el-GR), Español (es-AR), Français (fr-FR), Deutsch (de-DE), with the note on offline models; closes on a pick (`ui/me/VoiceSheet.kt`)
- [x] "Backup & sync": "Off", the last backup ("22:00" today, else "5 Nov") or "Google Drive" (`backup/BackupNames.kt` `BackupText.short`)
- [x] "Trips look": Tickets (default) / Pictures (`ui/me/TripsLookSetting.kt`; `data/settings/TripsLook.kt`)
- [x] "Plan view": List (default) / Calendar, the same choice as on the Plan (`data/settings/Settings.kt` `PlanView`)
- [x] "Place photos", on by default ("Pictures from Wikipedia for places in your plans"); off, Wikipedia is never asked (`data/settings/Settings.kt` `placePhotos`)
- [x] "Privacy policy" ("What stays on this phone and what goes where") opens justdataplease.com/apps/boringtravel/privacy.html (`ui/me/AboutApp.kt` `openPrivacyPolicy`; **iPhone API**: SFSafariViewController)
- [x] "Version" with the version (`ui/me/MeScreen.kt`)

## 29. Your traveller & rides

- [x] Me row "Your traveller & rides": a small figure and "Man · walks and rides your trip videos" or what's changed (`ui/me/TravellerSetting.kt`; `ui/traveller/TravellerChoices.kt` `summary`)
- [x] Sheet with People | Rides, "Walks the legs on foot in your trip videos." / "How each vehicle looks in your trip videos.", "A video can have its own in its Traveller tool." (`ui/me/TravellerSetting.kt` `TravellerSheet`)
- [x] People: the figure walking on the spot in the theme's colours, "Drag to turn"; Who: Man / Woman / Couple, and for a couple "Change: Man | Woman" (`ui/me/TravellerSetting.kt` `PeoplePane`; `ui/traveller/TravellerFigure.kt`; **iPhone API**: Skia port of the Android drawing)
- [x] Skin (8 tones), Hair (Short, Curly, Long, Bob, Ponytail, Bun, Shaved, Bald), Facial hair (None, Stubble, Moustache, Goatee, Beard), Hair colour (10) (`ui/traveller/TravellerChoices.kt` `rows`; `clip/Traveller.kt`)
- [x] Top (T-shirt, Long sleeves, Hoodie, Jacket, Tank top) and its colour (14; "Dress colour" with a dress), Bottom (Trousers, Shorts, Skirt, Dress) and its colour (`ui/traveller/TravellerChoices.kt`)
- [x] Hat (None, Cap, Beanie, Sun hat) and its colour, Glasses (None, Glasses, Sunglasses), Backpack (None, Backpack); rows only when they apply (`ui/traveller/TravellerChoices.kt`)
- [x] "Back to the default look" for the person being edited (`ui/me/TravellerSetting.kt`)
- [x] Rides: Car, Bike, Train, Boat, Plane, the model turning ("Drag to turn", rotors moving) (`ui/me/TravellerSetting.kt` `RidesPane`; `ui/traveller/RideFigure.kt`)
- [x] Models: Hatchback, SUV, Camper van, Convertible; Bicycle, Scooter, Motorbike; Steam, High-speed, Regional, Tram; Ferry, Sailboat, Speedboat, Cruise ship; Airliner, Small plane, Seaplane, Helicopter (`clip/Rides.kt` `RideModel`)
- [x] Body and Trim: "Theme" plus 14 colours; "Back to the default look" once changed (`ui/traveller/RideChoices.kt`)
- [x] The traveller and rides go into backups (`data/settings/Settings.kt` `backupPreferences`)

## 30. Themes and fonts

- [x] Postcard Pop, the default theme (cream, coral and sun, ink outlines, hard shadows), on every screen there is (`ui/theme/Looks.kt`, compiled as is)
- [x] Me › Theme: five swatch cards, 3 a row, each on its own paper with its name in its own face, the picked one ringed; it applies across the app, the widgets and the system bars (`ui/me/ThemePicker.kt` `ThemeSection`, `ThemeCard`; the status bar since the parity round)
- [x] Trail: plum dusk, sunrise colours, capital titles, round controls, outlined chips (the one dark theme) (`ui/theme/Looks.kt` `BtLooks`)
- [x] Sticker Book: dot paper, navy ink, yellow highlighter, polaroids with pink washi tape (`ui/theme/Looks.kt`)
- [x] Sketchbook: ruled paper, ballpoint blue, see-through hand-drawn boxes, wobbly round buttons, yellow tape on photo corners (`ui/theme/Looks.kt`)
- [x] Market: kraft paper with fibre dots, brown ink, dashed stitching, kraft tape, dot stickers (`ui/theme/Looks.kt`)
- [x] A theme changes paper, colours, fonts, cards, buttons, chips, photo frames (tilt, tape, polaroid), the bottom bar, drawn pictures, paper vehicles and the brand mark's yellow (`ui/theme/Looks.kt` `BtTokens`; `ui/theme/Theme.kt`)
- [x] "Titles and dates": Theme's own (default), Notebook, Field book, Graph paper, Kraft, Night Route (capital titles), any set with any theme (`ui/theme/Looks.kt` `FontSet`; `ui/me/ThemePicker.kt`)
- [x] "Text": Phone (Roboto) (default; on iPhone Google's Roboto, bundled, the face Android draws), Theme's own, Albert Sans, Literata, Source Serif, Work Sans, Lora, Barlow (`ui/theme/Looks.kt` `TextFont`; iPhone `ui/theme/FontFiles.kt` BtRoboto)
- [x] The bundled fonts drawn from their own files (`ui/theme/Fonts.kt`; iPhone `ui/theme/FontFiles.kt`)
- [x] Greek and other letters a font lacks fall back to the system font letter by letter (`ui/theme/Fonts.kt` `BtFonts`)
- [x] Old theme names from backups map to the nearest theme, unknown ones to Postcard Pop (`ui/theme/Looks.kt` `Look.of`)
- [x] Theme and fonts go into backups (`data/settings/Settings.kt` `backupPreferences`)
- [ ] Drawn scenes wherever there is no photo (covers, ideas, note rows, place pages, community, widgets, the world card): Mountain, Lake, City, Village, Coast, Forest, Glacier, Countryside, Desert, City at night, in the theme's colours, picked from the words (English and Greek), the country, the spot, then a hash (`ui/components/Pictures.kt` `DrawnScene`; `art/SceneArt.kt`; `art/Scenes.kt` `ScenePick`; **iPhone API**: Skia port)
- [ ] The brand mark on Welcome, the empty demo and Pro (`ui/components/Components.kt` `BrandMark`; `art/PlaneMark.kt`, compiled)

## 31. Demo mode

- [x] A store install starts with no examples and demo mode off (`data/DemoMode.kt` `start`; `data/DemoTrips.kt` `initialMode`)
- [x] Me › "Demo mode" ("Shows the example trips instead of yours. Yours stay as they are."): on adds missing examples, brings back deleted ones and moves the demo to today (`ui/me/MeScreen.kt`; `data/DemoMode.kt` `set`; `data/SampleData.kt` `ensure`)
- [x] Argentina & Patagonia, happening now: day 14 of 31 in El Chaltén, earlier items ticked, a journal with 17 Wikimedia Commons photos and their credits, 4 stays, a Fitz Roy cover, ideas "Still to book" and "Photo credits" (`data/SampleArgentina.kt`; `data/DemoPhotos.kt`; assets `demo/argentina`)
- [x] Kyoto & Osaka next (from 3 October next year, nothing ticked); Lisbon & Porto past (all done, a short journal with drawn pictures, 2 stays) (`data/SampleKyoto.kt`; `data/SampleLisbon.kt`; `data/SampleBuilder.kt`)
- [x] Only the examples show everywhere (Trips, Calendar, the globe and its numbers, Media); your trips, hand-marked countries and notes outside trips are hidden, never changed (`data/DemoTrips.kt` `visible`)
- [x] Writing and the camera go into the demo trip; route tracking only ever records your own (`data/repo/Repositories.kt` `currentOn`, `ownCurrentOn`)
- [x] DEMO tag on the examples: Trips, Now card, trip header, place page, Calendar (`ui/components/Components.kt` `DemoTag`)
- [x] Trips: the banner "Example trips. Yours are hidden.", a red "Exit demo" in the header, no Notes outside trips card or Trip ideas row (`ui/trips/TripsScreen.kt` `DemoBanner`)
- [x] Every example deleted: the brand mark, "No example trips", "Exit demo to see your own trips and plan a new one." with Exit demo (`ui/trips/TripsScreen.kt` `EmptyDemo`)
- [x] Each start in demo mode: "You're looking at the demo. Turn it off in Me › Demo mode." with "Turn off" (`ui/nav/AppNav.kt` `DemoNotice`)
- [ ] Turned off by Exit demo, the Me switch, Turn off, opening New trip, making a trip of your own ("Demo mode off"), joining a shared trip (`data/DemoMode.kt` `leave`; `data/DemoTrips.kt` `leavesDemo`)
- [x] Kept around today: on open, once a day on return and when turned on, every example moves so today is Argentina's day 14 (plan days, day names, note and photo times, stays) (`data/DemoMode.kt` `anchor`; `data/DemoTrips.kt` `reanchor`; **engine**: model/DayNames.kt)
- [x] Edit trip on an example: one start-date picker, everything moving by the same days, "The demo's plan, journal and pictures move with the dates." (`ui/trips/EditTripScreen.kt` `demoDates`; `data/repo/Repositories.kt` `moveDemo`)
- [ ] Examples can't be posted or shared and stay out of backups and restores (`ui/community/ComposeScreen.kt`; `data/DemoTrips.kt` `withoutDemo`)
- [ ] Lisbon's drawn pictures redraw in the current theme; exports and books use their Postcard Pop copies (`art/DemoArt.kt`)

## 32. Accounts and sign-in

- [x] Account card signed out: a grey person, "Not signed in", "Your trips are saved on this phone." (or the Drive status) (`ui/me/AccountCard.kt`)
- [x] Signed in: a round initial, the name, "Signed in as <email>", and "Google Drive connected" / "…not connected" / "…needs you to connect again" (`ui/me/AccountCard.kt`; `ui/onboarding/GoogleUi.kt` `AccountAvatar`; **owner**: Google iOS OAuth client)
- [x] "Sign in": Google's account sheet, then Drive permission, in one tap; an account used before goes straight through; a no to Drive still signs in (`ui/onboarding/GoogleUi.kt` `rememberGoogleConnect`; `google/CredentialSignIn.kt`; **iPhone API**: ASWebAuthenticationSession, sharing Safari's Google sign-in; **network**; **owner**: Google iOS OAuth client)
- [x] Sign in with Apple beside Google, since Google sign-in also signs in to the community (Apple 4.8; new on iPhone) (**iPhone API**: AuthenticationServices; **owner**: the Apple capability and Firebase's Apple provider)
- [ ] Google sign-in also signs in to Firebase for the community and trip mailboxes (`community/Community.kt` `signInWith`; **owner**: Firebase iPhone app on the same web client id; the community's since round 24, the trip mailboxes' with round 26)
- [x] A build without sign-in: "Google sign-in isn't set up in this build." and no Sign in (`ui/me/AccountCard.kt`)
- [x] Errors under the card: "There's no Google account on this phone…", "Google sign-in didn't work: …", "Google didn't return an account." (`google/CredentialSignIn.kt` `failed`; **owner**: Google iOS OAuth client; iPhone: "Google sign-in didn't work. Try again." and "Google didn't return an account."; the first can't happen, Google's page adds an account itself)
- [x] "Sign out": "Backups to Google Drive stop until you sign in again. Your trips and journal stay on this phone."; forgets the account and Drive token, stops the daily backup, signs out of the community (`ui/me/AccountCard.kt`; `google/GoogleAccount.kt` `signOut`; **owner**: Google iOS OAuth client)
- [x] "Delete account" (a red link when signed in or Drive is connected): what goes and what stays, and "How to delete everything" (`ui/me/AccountCard.kt`; `ui/me/AboutApp.kt` `openDeletionPage`; **owner**: Google iOS OAuth client; Apple 5.1.1(v): in the app, already there)
- [ ] Delete runs in order and finishes even if you leave Me: community data and the Firebase account, trip mailboxes, own AI keys, then Drive access revoked and sign-out; a community failure stops it ("Couldn't delete your community data…") (`community/Community.kt` `deleteAccount`; `together/Together.kt` `forgetMail`; `google/GoogleAccount.kt` `removeAccount`; **network**; **owner**: Firebase iPhone app; all but the trip mailboxes since round 24, `ios/Account.kt`: with an Apple account its community data goes after Apple's sheet, before Apple's token is revoked; the mailboxes with round 26)

## 33. Backups and restore

The Google Drive and Backing up lines, and the Drive side of Restoring, wait on the **owner** for the Google iPhone sign-in client for a real account (since round 23 they are built and tested against a fake Drive); the Backup file lines don't.

### Google Drive
- [x] Backup & sync › Google Drive: "Google Drive connected" / "not connected", the email, "Backups go to the BoringTravel folder." / "Back up trips, entries and photos to your own Drive." (`ui/me/BackupScreen.kt` `DriveCard`)
- [x] "Connect Google Drive" ("Connecting…"), asking only drive.file and drive.appdata → "Google Drive connected."; its errors ("…access wasn't given.", "No connection to Google…", "…isn't registered with Google yet…") (`google/DriveAuth.kt` `connect`, `describe`; **network**; **owner**: Google iOS OAuth client with these scopes)
- [x] "Google wants you to confirm Drive access again." with Connect again / Disconnect (`google/DriveAuth.kt` `needsReconnect`)
- [x] "Disconnect": "Disconnect Google Drive?… Files already in your Drive stay there." → access revoked, daily backups stop, "Google Drive disconnected." (`google/GoogleAccount.kt` `disconnectDrive`)

### Backing up
- [x] "Last backup": "Today, 22:00", "Yesterday, …", "5 Nov, …", "Never"; "Nothing new since. Last checked: …" (`backup/BackupNames.kt` `BackupText.since`)
- [x] "Back up now" (off without Drive or while busy): steps with progress ("Saving photos to Drive · n of N", "Packing trips and entries", "Uploading the backup", "Saving videos to Drive"), going on when you leave (`backup/BackupManager.kt` `backupNow`; **network**; **iPhone API**: beginBackgroundTask)
- [x] Results: "Backed up: 2 trips · 31 entries · 148 photos.", "Nothing new since the backup of <date>." (no new zip), "N photos couldn't be saved this time…", "N videos saved to BoringTravel › Videos." / "…wait for the next backup.", "Nothing to back up yet…" (`backup/BackupNames.kt` `backedUp`; `backup/BackupSafety.kt`)
- [x] Failures never quiet: "The backup didn't pass its own check…", "…didn't reach Google Drive whole…", "No internet connection…", "Google didn't answer in time…" (`backup/BackupManager.kt` `uploadRows`; `google/Http.kt` `readable`)
- [x] One bad photo never stops a backup (skipped and counted; after three refusals the rest wait); a place off the Earth is mended (`backup/BackupManager.kt` `uploadPhotos`; `backup/BackupSafety.kt` `clean`)
- [x] In Drive: BoringTravel › Backups ("BoringTravel backup YYYY-MM-DD HH.mm.zip", the newest 10 kept), Photos (each own photo once), Videos (each saved video once), Videos › Editor files, a hidden marker (`backup/BackupNames.kt` `KEEP`; `backup/BackupVideos.kt`; `google/DriveApi.kt`; **engine**: backup/BackupFormat.kt)
- [x] Backups never carry the examples or other members' photos (`backup/BackupManager.kt` `readSnapshot`; `backup/BackupSafety.kt` `ownPhotos`)
- [x] "My Drive › BoringTravel › Backups" with Open; a renamed or moved folder is still found (`ui/me/BackupScreen.kt` `openFolder`; `backup/BackupFolder.kt`)
- [x] The info line: the newest 10, every photo and video once, the settings too (`ui/me/BackupScreen.kt`)
- [x] "Back up every day" (on; "Around 22:00, when the battery isn't low.") and "Only on Wi-Fi" (on; videos wait even on Back up now) (`backup/BackupWorker.kt` `BackupSchedule`; `backup/BackupManager.kt` `videosWait`)
- [x] The daily backup about once a day from 22:00, on Wi-Fi when asked, not on low battery, not within 18 h of the last, retried 3 times; no notifications (`backup/BackupWorker.kt`; **iPhone API**: BGProcessingTask, best effort, and on opening the app after 22:00 when one was missed; Wi‑Fi as a network that isn't costly, the battery as Low Power Mode off)
- [x] A Trips banner when backups stall: "Backups stopped. Reconnect Google Drive." or "No backup for N days." (3 or more) with Open (`ui/trips/TripsScreen.kt` `BackupTrouble`; `backup/BackupNames.kt` `trouble`)

### Restoring
- [x] Restore offer once Drive is connected on a phone that never backed up: "Restore your backup?" with date and counts; red, with "It replaces what's on this phone now. You can undo it in Me › Backup & sync.", when the phone has trips; Restore / Not now; asked again when it couldn't look (`ui/me/RestoreOffer.kt`; `backup/BackupNames.kt` `best`; **owner**: Google iOS OAuth client)
- [x] Until it's answered, the daily backup doesn't upload from a new phone (`backup/BackupSafety.kt` `waitForRestoreAnswer`; **owner**: Google iOS OAuth client)
- [x] "Restore" / "Hide backups": "Backups in your Drive" ("Looking in your Drive", "Couldn't read your backups…", "No backups in your Drive yet."), rows with date and counts (`ui/me/BackupScreen.kt` `BackupList`; **owner**: Google iOS OAuth client)
- [x] "Restore this backup?… This phone goes back to it: what you added or changed since is removed. You can undo it here." with a red Restore (`ui/me/BackupScreen.kt`; **owner**: Google iOS OAuth client)
- [x] A restore replaces like a database: rows come back as they were, rows not in it go, the route trip by trip; no file deleted; shared trips only gain; examples untouched; twice changes nothing (`backup/BackupMerge.kt`; `backup/BackupTransactions.kt`; **engine**: backup/BackupFormat.kt)
- [x] Missing or damaged photos (checked by checksum), saved videos and editor files come down: "Restored the backup of <date>: …", "This phone already matched…", "N videos came back.", "N photos couldn't be downloaded…" (`backup/BackupManager.kt` `runRestore`; **network**; **owner**: Google iOS OAuth client)
- [ ] A restore turns demo mode off, brings back shared-trip memberships and puts the settings back (theme, fonts, share and book choices, trips look, voice language, place photos, plan and map views, traveller, rides, trip photo defaults, video choices); account, Drive, tracking, calendar links and the community rules never travel (`backup/BackupManager.kt` `mergeIn`; `data/settings/Settings.kt` `restorePreferences`; `backup/PrefsBackup.kt`; **engine**: backup/BackupFormat.kt `BackupPreferences`)
- [x] Errors: "This backup is from a newer BoringTravel…", "This isn't a BoringTravel backup.", "…damaged…", "…too big to open on this phone.", "…checksum doesn't match…" (`backup/BackupCodec.kt`; `backup/BackupArchive.kt`)
- [x] "Before the last restore" with "Undo restore": "Undo the restore?…" → "Undone. This phone is as it was before the restore."; an undo can itself be undone; none kept when the phone held nothing (`backup/BackupManager.kt` `undoRestore`, `keepForUndo`)
- [x] One backup or restore at a time, its progress on every screen (`backup/BackupManager.kt` `progress`)
- [x] An Android backup restores on iPhone and an iPhone backup on Android: same rows, media, video and book files (`backup/*`; **engine**: backup/BackupFormat.kt)

### Backup file
- [x] "Backup file": "Trips, entries, photos, saved videos and your settings in one zip. Keep it anywhere…" (`ui/me/BackupScreen.kt`)
- [x] "Save a file": the system save picker, "BoringTravel-YYYY-MM-DD.zip" (rows, media, extras), "Saving photos and files · n of N", "Saved: …" with missing photos counted; a stopped save leaves no half file (`backup/BackupManager.kt` `runSaveFile`; `backup/BackupArchive.kt`; **iPhone API**: fileExporter)
- [x] "Restore a file": the file picker, its summary, "Restore this file?…" with a red Restore, then like a Drive restore; "Couldn't read that file. Pick a BoringTravel backup file." (`backup/BackupManager.kt` `runRestoreFile`; **iPhone API**: fileImporter)
- [x] "Stop": "Stopped. No file was saved." / "Stopped. Restore the file again to finish." (`backup/BackupManager.kt` `cancelFile`)

## 34. Pro and purchases

Not on sale on Android (`billing/ProBilling.kt` `PRO_FOR_SALE = false`): everything is open, nothing is sold. The iPhone keeps the same switch; these lines are for the day it turns on.

- [ ] Me › "BoringTravel Pro" ("AI planner, clips") (`ui/me/MeScreen.kt`)
- [ ] Pro screen: logo and PRO tag, "More time for the trip", "Get help turning ideas into a plan, and make longer travel films to share." / "Pro is on. Thank you.", "AI planner" and "Trip clips without the mark"; with Pro, "Plan a trip from notes" (`ui/pro/ProScreen.kt`)
- [ ] Offers "For life" (once, picked) and "Yearly", prices from the store; "Get Pro" once prices are in (`ui/pro/ProScreen.kt` `Offer`; `billing/ProBilling.kt` `pro`, `pro_life`; **iPhone API**: StoreKit 2; **owner**: App Store Connect products; Apple 3.1.1: through StoreKit only, with renewal terms and Terms and Privacy links)
- [ ] Messages: the purchase couldn't start, prices not available, waiting for payment (`ui/pro/ProScreen.kt`)
- [ ] "Restore purchases": "Pro restored." / "No Pro purchase found…" / "…didn't answer…" (`billing/ProBilling.kt` `restore`; **iPhone API**: AppStore.sync; required by Apple)
- [ ] "Manage subscription" (yearly) and the renewal line (`billing/ProBilling.kt` `manageUrl`; **iPhone API**: manageSubscriptions)
- [ ] Ownership cached; offline keeps Pro; only a complete store answer takes it away (`billing/Entitlement.kt` `reconcile`; **iPhone API**: Transaction.currentEntitlements)
- [ ] The offer opens from every PRO tag and "Go Pro" (`ui/nav/AppActions.kt` `paywall`)
- [ ] Debug builds: "Pretend Pro" (`ui/pro/ProScreen.kt`)

## 35. Everything else

- [x] Trips, plan items and notes kept on the phone between starts (iPhone today: the backup's data.json text) (**engine**: engine/TripBook.kt, backup/BackupFormat.kt)
- [ ] The full store on iPhone: the database and the files folder for photos, videos, route points, stops, countries, books and clips (`data/db/*`; `data/repo/*`; **engine**: data/db/Entities.kt; Room has an iPhone build)
- [x] App icon: the paper plane on yellow (`docs/brand/plane_icon.py`; iPhone `iosApp/BoringTravel/Assets.xcassets`)
- [x] Times on a 24-hour clock ("13:10") (**engine**: util/Dates.kt `time`)
- [x] Date texts in the phone's language ("Fri 18 Jun 2027", "Παρ 18 Ιουν 2027"), and numbers ("18,4 km") (**engine**: util/Dates.kt, `Dates.ios.kt` and `PhoneLocale.ios.kt`; compat's java.time and String.format, `compat/PhoneLocale.kt`; **iPhone API**: NSDateFormatter, NSNumberFormatter, NSLocale.preferredLanguages)
- [x] Spoken labels on the plan's controls ("Mark … done", "Add to day 3", "Rename this day") (`ui/trip/PlanTab.kt`)
- [x] The shared confirm dialog, text dialog and red Delete button (`ui/components/BtConfirm.kt`, compiled)
- [x] Long press copies names and addresses, with a haptic tick (`ui/components/CopyText.kt`; **iPhone API**: UIPasteboard, haptics)
- [x] Photos everywhere: cropped to fill, a quiet colour while loading, videos as their first frame with a play mark (`ui/components/MediaImage.kt`; **iPhone API**: AVAssetImageGenerator)

## Build order

27 rounds of about 30 to 45 lines each (995 in all): the most-used things first, then the trip video and the book, then what waits on the owner. Numbers are the areas above; a line that needs a later round works once that round is in.

1. **The frame** (47): 2 Tabs and navigation (all but Links into the app); 10 Notes outside trips, with 3's card for them; 28 Me and every setting; 30 Themes and fonts, with the drawn scenes (and so 3's and 5's destination pictures). Needs: nothing new; the screens sit on the theme files already compiled.
   *Mostly done (18 Nov 2026): the bar, its two modes and Back; Notes outside trips and their card; Me's header, rows, Trips look and Plan view; the five themes and both font choices; the drawn scenes (the Android app's shapes, palettes and picking rules compiled, drawn with Compose) on covers, trip ideas and note rows. Left: 71 and 1371 the status-bar style; 72 the screens' fade; 78 and 85 the pages and links that aren't on iPhone yet (community, widgets, the Map tab); 80 other tabs keep their state; 87 a shared trip left elsewhere; 91 the swipe back; 97 the widget's camera (it came with round 10); 107 Android's toasts; 421 and 422 Notes outside trips have a Write here, and Write without a trip doesn't use the trip on now; 1344, 1346 and 1347 the Me rows that need the account or Drive (Voice notes is in since round 6); 1376 and 1383 the paper vehicles and the scenes on screens still to come (the paper vehicles came with round 18); themes through a backup came with round 12; 1384 the mark on Pro.*
2. **Trips home and the plan** (39): 3 Sections and rows (flags from round 11), Now card (its mic from round 6, camera from round 5); 4 Delete trip; 6 Plan top row and views, List, press-and-hold rename, Dragging and the row menu, Calendar view; 7 the sheet's Share icon, the 12/24-hour clock, Sharing an item; 35 the full store, date texts in the phone's language, long-press copy. Needs: the database and files in the engine (the plan's phase 2); PlanLogic, PlanViewLogic and PlanDrop off java.time.
   *Done (18 Nov 2026) but for parts of later rounds: tickets and pictures with their countdowns, colours and covers, the Now card with Write today and the camera, the full delete dialog with the photos' files freed, Expand and Collapse, List and Calendar, dragging with the Android app's own reorderable library, the row menu, the 12/24-hour clock, sharing an item. PlanLogic, PlanViewLogic and PlanDrop are written again in `ios/PlanLogic.kt` (still not in the engine). The country flags came with round 11. Left: the Now card's mic is in since round 6; 172 the route, book, videos and calendar events a deleted trip takes (none exist here yet; its photos' files go; all came with rounds 10, 12, 20 and 21: now 174); 211 "Add to plan" (the share extension, round 8); 1477 the database (phase 2); 1480 dates in the phone's language (the engine's iPhone dates, `Dates.ios.kt`).*
3. **Travel, tags, the Calendar tab, demo mode** (39): 6 Travel between places; 7 Getting there and tags; 14 Calendar tab, with 4's "New trip from here"; 31 Demo mode. Needs: round 2; the paper vehicles drawn with Skia; the demo's photos show once round 5 is in.
   *Done (18 Nov 2026) but for: 257 the chooser's paper vehicles (its tiles show the vehicles' line icons until `PaperVehicle3D` is drawn with Skia; they came with round 18); the Calendar's flags came with round 11; 1392 the globe and the Media tab in demo mode (the Media tab came with round 20); 1393 route tracking (it came with round 10); 1398 joining a shared trip; 1401 posting and backups; 1402 exports and books (Lisbon's pictures are written in Postcard Pop and drawn live in the theme). The demo is Android's: Argentina's table, journal and 17 Wikimedia photos, Kyoto and Lisbon from the engine's trip ideas, Lisbon's drawn pictures, moved around today on open, on return and when turned on.*
4. **The note editor: text** (33): 10 A trip's Journal, Reading a note; 11 Screen, Toolbar, Blocks and the + menu, Saving. Needs: round 2; compose-rich-editor (it has an iPhone build); DocRender, HtmlRuns and EditorHtml in the engine.
   *Mostly done (18 Nov 2026), with this app's own rich text (ios/RichText.kt: the library is built with a newer Kotlin). Left: 432 a member's name on a shared trip's note; 441 a shared trip's video not here yet; 459 the Media tab; 475 the printed page break (the toolbar's mic is in since round 6); 540 and 541 saving when the app goes to the background and coming back to a note being written.*
5. **Photos** (42): 11 Adding photos and videos, Editing a photo, Layouts, Media tab's "Add media"; 4 the cover and Photos in notes; 6 Media tab photos; 35 photo tiles. Needs: round 4; PHPicker, the camera, ImageIO.
   *Partly done (18 Nov 2026): picking, the camera, single photos and layouts with every tool, photo tiles. Left: 161 to 166 the cover and Photos in notes; 273 to 276 and 459 the Media tab (it came with round 20); 483 a trip's photo defaults; 485 HEIC kept as HEIC (iPhone writes a JPEG); 489 a recording up to 30 minutes; 490 a pick that returns while the note reloads. (The cover, Photos in notes, the photo defaults, HEIC kept and the 30 minutes came with the second parity round: now 163 to 168, 485, 487 and 491.)*
6. **Videos, dictation, check-ins** (34): 11 Videos; 10 Video player; 13 Dictation; 12 Check-ins; 7 the item's Journal section and Write. Needs: round 5; AVFoundation, Speech, Core Location.
   *Mostly done (18 Nov 2026): videos go in whole and play in the phone's player; 500 is done. Dictation is Android's: the mic at the end of the toolbar and on the Now card, the first-use word and the way to Settings, the listening pill with its level bars, words at the cursor fixed at each pause, Me › Voice notes, on the phone first and online without an offline pack, the same recovery, errors and long pause (the Android app's DictationText and DictationRecovery read in, its Dictation written again over `SpeechEngine`: the Speech framework and AVAudioEngine in `iosMain/…/IosSpeech.kt`, Apple's errors read as Android's codes, an utterance ended after 2.5 s without new words). Check-ins are Android's (DailyCheckIn read in; the store, claim and confirmation on the trip store; the fix through `PhoneLocation.fix`, Core Location). Left: the rest of Videos (493 to 499: no 720p MP4, the limits differ), 447 Android's own player controls, 328 and 329 (all came with the second parity round: now 495 to 499, 501, 443, 449, 330 and 331; 500 waits on Drive); 548 a member's own check-in in a shared trip (round 26); 570 the Voice widget and shortcut (it came with round 10). Only checked on the desktop with a scripted recognizer: the real recognizer, the microphone and Greek need a Mac build and a real iPhone.*
7. **Places** (33): 8 Search and naming, Place page; 7 Place, Open in Google Maps, naming by reverse geocoding; 6 a day's title opening its place page; 11 Entry details, Place chips. Needs: Ktor for Photon; Core Location and CLGeocoder; PlaceModel and PlaceNames off java.util.Locale.
   *Done (18 Nov 2026) but for parts of later rounds: the network is Ktor behind one interface (`ios/Net.kt`); Photon's search and reverse naming, CLGeocoder first; the item sheet's Place field (search, a typed name, a pasted Google Maps link, Use my location with Core Location); a day's title opening its place page, with its chevron; the place page in all five heroes, its country from the Android app's outlines (`assets/geo/countries.json`, read in at build time) or the geocoder; Open in Google Maps; a note's place found on the map and from the trip. The rules are written again in `ios/` (PlaceSearch, Links, Wiki, MapsLists, TripPlaces, DayPlaces) and checked by the Android app's own tests, read in at build time. (The header's flags came with round 11, the place page from the map with round 9.) Left: 527, 529 and 530 the last route point when the phone has none (they came with round 10). The flag is the phone's emoji in a round sticker (Android paints the same emoji into a bitmap); Windows draws its letters.*
8. **Links and sharing in** (34): 7 Website and link preview, Renew details, items made from a link alone; 8 Google Maps links and lists, Link previews and Wikipedia photos; 27 Sharing into the app; 2 Links into the app. Needs: round 7; a Share Extension with an App Group and universal links (owner for the device run).
   *Done (18 Nov 2026) for everything the app can do without a share extension: the website and its preview, Paste a link, items made from a link alone, a short Maps link filled after the sheet closes, Renew details, Maps links and lists as Android reads them, previews 3 at a time kept in the caches (400), Wikipedia photos with credits, pictures in a 40 MB disk cache, every request naming the app ("BoringTravel/1.0 (iPhone; +…)"), Me › Place photos, and Copy from a link for a Google Maps list (Add from a link in the trip's ⋮ and under the plan, Me › Import trips from a link). Left: 1328 to 1339 Sharing into the app (a Share Extension target with an App Group: the owner's Apple Developer account); 100 and 101 post and join links (universal links and the boringtravel:// scheme: apple-app-site-association on justdataplease.com, owner); 102 and 103 shares and joins waiting for Welcome and surviving a restart (they come with the extension and the links); 211 the List opening on the day Add to plan chose. A blog or a Google Doc in Copy from a link says it isn't on iPhone yet (rounds 16 and 17; they came with round 17).*
9. **Maps** (36): 9 Maps and routes; 24 From points to a route; 6 visited places ticked. Needs: MapLibre iOS or maplibre-compose; Ktor for styles; SpikeFilter, StopDetector, RouteShape and MapData in the engine.
   *Mostly done (18 Nov 2026): MapLibre Native for iOS 7.0.0 (Swift package `maplibre-gl-native-distribution`) drawn from Swift (`iosApp/BoringTravel/MapLibreMap.swift`) behind a small Kotlin interface (`iosMain/…/IosMap.kt`), a drawn stand-in on the desktop. Everything else is common Kotlin: OpenFreeMap's style fetched and kept in the caches, recoloured per look, Terrain and Earth, the app's layers, pins and lines (`ios/MapLook.kt`, `MapPictures.kt`), the camera's fit, pins that hide behind each other and what a tap reaches (`ios/MapCamera.kt`), the Map tab, all trips, the trip map and its leg sheet (`ios/MapScreen.kt`), points into stops (`ios/TrackProcessor.kt`). The Android app's own rules are read in at build time and its tests run against them (MapStyles, Geo, RouteShape, SpikeFilter, StopDetector, VisitMatcher, MapData, LegSheetModel). The Globe came with round 11. Left: 383 the dot while recording and 387 Route tracking's sheet (they came with round 10); 394 Play the route clip (round 18: it says "Not on iPhone yet."; it came with round 18); 1301 the nightly run (BGProcessingTask: it came with round 10). The map doesn't turn (Android's does), so Kotlin finds what a tap reaches on the same projection (it turns since the second parity round, and the projection with it). The Swift has not been compiled yet: the Mac build does that.*
10. **Route tracking, notifications, widgets** (38): 24 Me › Route tracking, Permissions, Recording; 25 Notifications; 26 Widgets and shortcuts. Needs: round 9; Core Location's background mode, UserNotifications, WidgetKit with an App Group.
   *Done (19 Nov 2026) but for the App Group. Route tracking is Android's: Me › Route tracking (the row with its level, Off until one is picked and nothing asked before; "Now" with Start recording and Stop for today; the level for every trip; the steps), a trip map's Route tracking button and sheet (the trip's own level, "Starts on …" before the trip) and the dot on the distance chip while it records. The rules are the Android app's own `tracking/TrackingPlan.kt`, read in (what records today, a trip's own level, the stop for today, Balanced's best fix backing off, Detailed's rest; its TrackingPlanTest runs here); Android's TrackingController, recording service, best-fix alarm, TrackWriter, LocationWatch and notifications are written again in `ios/Tracking.kt` (`RouteTracking`) over three small interfaces: `RouteSensor` (Core Location), `PhoneNotes` (UserNotifications) and `NightlyRuns` (BGTaskScheduler), all in `iosMain/…/IosTracking.kt`, with stand-ins that record in the tests and pictures. It records with Core Location's standard updates, started while the app is on screen and going on in the background with the blue location sign (While Using is enough: Android's rule for its location service); each level asks for the least that gives Android's spacing (Low and Balanced on Wi-Fi and cell towers at 500 and 150 m, Detailed on GPS every 15 m, resting at 50 m after ten still minutes and waking on a fix that moved) and the fixes are thinned to Android's (no sooner than half the level's interval, and its distance). The steps are Android's: Precise location (While Using with Precise Location on), then Notifications, each asked only on Allow and leading to Settings once answered no. Always is built behind one switch, `IosTrackingPlan.ASK_ALWAYS`, off as Android's `TrackingPlan.BACKGROUND_RECORDING` is: with it on, the steps end with Always (Android's "Allow all the time" and its disclosure) and, once it is given, the significant-change service runs beside the recording (alone for Low), so iOS starts the app again after it was closed; App Review would then need background-location notes for Always; an Always given by hand in Settings meanwhile counts as While Using (nothing of the Always path starts). Also in the steps: "Location is off" with Turn on (the app's settings: iPhone can't open Location Services); Battery settings left out (iPhone has none). The points are the store's TrackPoint rows, Android's own (time, position, accuracy, speed, altitude, level), so a backup carries them either way: each fix is kept at once in a small inbox in this phone's settings and goes into the store in batches (20 points, ten minutes, the app leaving the screen, a map or a note asking; engine: `TripBook.addTrackPoints`); a deleted trip's route goes with it (`TripBook.removeTrackPoints`). The nightly check (a BGProcessingTask and a BGAppRefreshTask from five minutes after midnight, when the phone gives them time, and every start): the new day's decision, the recent trips' stops, the reminder. The notes are Android's: "Your route isn't recording", "Location is off" and the nightly "Location is not allowed for BoringTravel…", quiet (passive, no sound, no badge), a tap opening the app or its settings, asked only by the steps; no note while it records (the blue sign instead). The widgets are Android's "Today" (small and medium), "Quick bar" (medium) and "Quick bar, small" as a WidgetKit extension (`iosApp/BoringTravelWidget`, SwiftUI) drawing the snapshot the app writes into the App Group's folder (`ios/Widgets.kt`; Android's `widget/WidgetModel.kt` and `QuickBarLayout.kt` read in, `PaperGrid` copied, their four Android tests run here): today and the next seven days, so the day turns at midnight without the app; links on the medium widgets, an App Intent per button on the small ones (iOS 17; one tap target, Write, on iOS 16). The Home Screen quick actions are Write, Take a photo, Voice note and New trip (`UIApplicationShortcutItems`); they and the widgets' links (`boringtravel://`) open their screens once Welcome is done and are dropped before, as on Android; Voice opens Write dictating (572); a note's place falls back on the last route point (529, 531, 532). Left: 1325 the widgets' data on a real iPhone (the App Group in the owner's Apple Developer account; the Simulator's unsigned build has none, so its widgets show their empty look); 174 a deleted trip's book and videos (rounds 20, 21; the videos came with round 20, the book with round 21). Only the desktop has run it: Core Location in the background, the significant-change relaunch, BGTaskScheduler, UserNotifications, the widget extension, its App Intent and the quick actions need the Mac build, GPS accuracy and battery a real iPhone (owner). The widget pictures on Windows are Compose's drawing of the same snapshot, not SwiftUI's.*
11. **Your world** (43): 16 Your world and the globe; 6 the header's DEMO tag and flags. Needs: the globe and share card drawn with Skia; countries.json in the app.
   *Done (18 Nov 2026): the Map tab opens on the Globe (as on Android), drawn with Compose's canvas (Skia) from the Android app's own maths, read in at build time (`globe/GlobeMath.kt`, `GlobeGeometry.kt`, `RingClipper.kt`, `PinLayout.kt`, `GlobePalette.kt`, `geo/Countries.kt`, `geo/CountryInfo.kt`); its renderer and flag stickers written again over Compose (`globe/GlobeRenderer.kt`, `FlagBadges.kt`); the globe's touch, spin, flights and VoiceOver actions (`ios/Globe.kt`), Your world's sheet, the country and continent sheets, Places I've been and Share your world (`ios/WorldMode.kt`, `WorldSheets.kt`, `PlacesBeen.kt`, `ShareWorld.kt`, `world/ShareCard.kt`); the world worked out from the rows as Android's `world/WorldModel.kt` (written again in `world/WorldModel.kt`, days by epoch day; the Android app's WorldModelTest, ShareCardTest, CountryInfoTest, CountriesTest, GlobeMathTest, PinLayoutTest and RingClipperTest run against it); hand-marked countries as the engine's country marks (`TripBook.markCountryVisited`, `setCountryWish`, `removeCountry`); flags on Trips (tickets, pictures, both Now cards), the trip's header and the Calendar's bars and undated rows. The flag is the phone's emoji in Android's round sticker; Windows has no flag emoji, so the desktop and its pictures draw the two letters (Android's own fallback). "Save image" says "Saved to Photos." on iPhone (Android: Pictures/BoringTravel). Left: 1394 the demo's Media tab (round 20; it came with round 20).*
12. **The phone's calendar and backup files** (37): 15 The phone's calendar, with 6's ⋮ Calendar and 7's calendar lines; 33 Backup file and the restore rules that don't need Drive (replace, undo, errors, settings, Android and iPhone backups). Needs: EventKit; BackupMerge, BackupCodec and BackupArchive in the engine (java.util.zip replaced).
   *Mostly done (18 Nov 2026). The phone's calendar: the trip's ⋮ › Calendar sheet, the Plan tab's line and the item sheet's Add to calendar, on Android's rules written again with zones by their ids (`calendar/TripEvents.kt`, `CalendarDiff.kt`, `IcsWriter.kt` with TripZone and CalendarIntents, `CalendarImport.kt`, `CalendarSync.kt` with CalendarLinks); EventKit behind `PhoneCalendar` (`iosMain/…/IosCalendar.kt`; a recording fake, `MemoryPhoneCalendar`, in the tests and pictures); the app's marker is the event's URL and the same "BoringTravel link:" line in its notes; a calendar kept updated follows each change while the app is open and on return to it (Android: a background job). Backup files: Me › Backup & sync › Save a file and Restore a file (`ios/Backups.kt`, `BackupScreen.kt`), the engine's format exactly (manifest.json, data.json, media/), in plain-Kotlin zip (`backup/Zip.kt`: stored entries written, stored or deflated read, every CRC checked) and Android's codec rules (`backup/BackupFile.kt`), the restore as Android's BackupMerge (written again in `backup/BackupMerge.kt`; the Android app's BackupMergeTest runs against it) and BackupSafety (read in), Undo restore, the settings back with the rows (the ones this phone hasn't got are carried on to the next backup), the Files app's save sheet and picker. Left: 605 the zone guessed from the planned places' countries (no offline country-to-zone table on iPhone: chosen, the notes' zone, else the phone's; it came with the second parity round); 621 leaving a shared trip (round 26; deleting removes its events); 174 a deleted trip's route, book and videos (the route came with round 10, the videos with round 20, the book with round 21); 1449 and 1450's Drive downloads and shared trips (round 23, 26; the Drive downloads came with round 23); 1454 saved videos and the video editor's files in a backup file (extras/, round 20: this phone neither writes nor restores them, their rows travel on; the trip video's came with round 20, the book's came with round 21: a book is its row in data.json, which a backup file carries both ways); 1349 the Me row says "Off" until Drive (Drive's values came with round 23). The newest-10 rule doesn't apply: this phone keeps no backup files (the save sheet copies the file out and the copy here is removed); the one copy kept is the phone as it was before the last restore, for Undo.*
13. **Share (print and export)** (29): 19 Share, with 6's "Make a clip, Make a book, Share" line. Needs: WKWebView to PDF through UIPrintPageRenderer, PDFKit, UIPrintInteractionController; TripHtml, PrintCss and TripDocx in the engine. Google Docs is finished in round 23.
   *Done (18 Nov 2026) but for the Google parts. The Android app's export files are read in at build time and compiled as they are (`export/TripHtml.kt`, `TripText.kt`, `TripDocx.kt`, `TripBook.kt`, `PrintCss.kt`, `PhotoRows.kt`, `ExportOptions.kt`, `ExportFiles.kt`, `RouteSvg.kt`, `book/BookFacts.kt`, `BookSvg.kt`, `Flags.kt`, `PageGeometry.kt`, `CropMath.kt`, `doc/DocRender.kt`): their java.time, java.io, java.util.zip and java.net calls go to `compat/JavaTime.kt` and `JavaIo.kt` (the same names in plain Kotlin, dates in English) and their String.format calls to `JavaFormat`. Android's own export tests run against them (70 tests), and a test compiles Android's files untouched for the desktop and compares: the text, the print and Docs HTML and every part of the Word file are byte for byte Android's for every style, size, paper and day range; the Word file's zip stores its parts dated 1980 where Android deflates them and dates them by the clock. The PDF is WebKit's: WKWebView's print formatter in UIPrintPageRenderer, page by page into a PDF context, with the page's ground and the footer drawn by UIKit (WebKit has no @page footers: the iPhone's Noteworthy where Android prints Caveat); PDFKit draws the preview, UIPrintInteractionController prints it (`iosMain/…/IosPrint.kt`, behind `PagePrinter`). The desktop draws the same HTML with a stand-in (`DesktopPrint.kt`) into a real PDF of page pictures. Photos are made small, upright and tag-free with Skia (`ios/Exports.kt` `PhotoPrep`), a video gives its first frame. The screen is Android's (`ios/ShareScreen.kt`); the choices are this phone's settings under a backup's names. Left: 922 the book's default style (round 21; it came with round 21); 934 to 936 and 947 Google Docs (round 23, owner; they came with round 23); 202 Make a clip and Make a book say "Not on iPhone yet." (rounds 18, 21), as does the screen's "Make a book" line (Make a clip came with round 18, Make a book with round 21). Only the desktop has run it: WebKit's pages, the footer's place, PDFKit and printing need a Mac build and a real printer.*
14. **AI planner I: the chat** (36): 20 Screen and empty chat, Writing a message, While it works, Me › AI. Needs: Ktor for the providers (Claude streamed without its Java SDK), Keychain; tested with an own key, no owner needed.
   *Done (19 Nov 2026) but for sharing into the app. The chat is Android's PlannerViewModel and PlannerScreen written again (`ios/PlannerChat.kt`, `PlannerScreen.kt`), kept by the app while another screen covers it and dropped on leaving; Android's own files make every rule (the planner's process, prompt, tools, review, limits, the questions' sheet, the attachments: read in at build time, `aiFiles` in the build file). Pictures come from PHPicker (photos only), a link from Add a link with the clipboard's link already in, dictation from the phone's recognizer. Me › AI is Android's (`ios/AiScreen.kt`), the keys in the Keychain (`iosMain/…/IosKeychain.kt`, this device only, never in a backup). Left: 984 and 985 a shared .txt file and shared text, pictures or a Docs link (the share extension, round 8).*
15. **AI planner II: what it does** (37): 20 Trip questions, Pictures and links, What it can change. Needs: round 14; rounds 7 and 8 for map search and links; an HTML parser in place of jsoup.
   *Done (19 Nov 2026) but for reading pages. Every tool runs as on Android (ToolRunner, ToolLoop, PlannerTools, PendingPlan, PendingPlaces read in; their Android tests run here), with this app's place search, Google Maps links and lists. Left: 1015 read_link reads a Google Doc shared with its link only; a post, a blog or a published Doc says Android's "no text to read" (the site reader is jsoup on Android: Copy from a link's readers, round 17; it came with round 17).*
16. **AI planner III, and into Copy from a link** (34): 20 Replies, review and saving, Models and limits; 4 New trip's three AI ways; 6 Plan with AI (header and card); 21 Ways in, Address and reading. Needs: round 15; the built-in Gemini waits on the owner.
   *The planner's part done (19 Nov 2026): the review and saving (Android's DraftLayout and DraftApplier, saved through the engine's `TripBook.saveDraft`), the summary, the trip on Plan with New trip gone from the history, the report by mail (or copied), New trip's three AI ways, the trip header's Plan with AI and the Plan tab's card, Me's AI row. Own keys: Gemini and OpenRouter in the tool loop, Claude streamed over Ktor (its request JSON compared with the Anthropic SDK's in a test), ChatGPT in one answer; the built-in planner is Firebase AI Logic over REST from build-time values (`firebase.projectId`, `firebase.appId`, `firebase.apiKey`, `firebase.aiModel` in `ios-boringtravel/local.properties` or `BORINGTRAVEL_FIREBASE_*`; without them Android's "isn't set up in this build"). Left: 1041 the built-in planner needs the owner's Firebase iPhone app and App Check (App Attest): no token is sent yet; 1042 and 1061 Copy from a link's sorting (round 17; done there); 1047 Pro (round 27); 1060 Delete account wiping the keys (round 23; it came with round 23). Copy from a link's Ways in and Address and reading (area 21) are round 17's now. Only the desktop has run it: the Keychain, URLSession's streaming and PHPicker's photos filter need a Mac build; no model was ever asked live.*
17. **Copy from a link** (34): 21 Sources, Sorting into trips, Proposal, Copying; 6 Add from a link (menu and card). Needs: round 16; UUID v3 ids byte for byte; a background task for long copies.
   *Done (19 Nov 2026) but for the share extension and Drive. The Android app's Copy from a link is read in at build time and compiled as it is (`siteFiles` in the build file: SitePost, CssFormats, HtmlBlocks, Feeds with SiteDates, GoogleDocs, HeadingDates, InlinePictures, PageReader, SiteArticles, TumblrApi, SiteSections, SiteParts, SiteNotes, SitePlaces, SiteOrganizer, SiteReader, SiteImporter, SiteLinks, and the planner's ai/tools/LinkReader.kt): jsoup's names are Ksoup's (`com.fleeksoft.ksoup` 0.2.6, jsoup ported to Kotlin Multiplatform, built with Kotlin 2.3.10), OkHttp's HttpUrl is compat's over WebUrl (`compat/HttpUrl.kt`), java.time is compat's with the feeds' date reading (`compat/JavaTimeParse.kt`: ISO moments, RFC 822 and 1123, Tumblr's), java.io's files and streams are compat's over kotlinx-io. Android's own tests run against the read-in copies on virtual time (19 files, 154 tests, and LinkReaderTest's 9). Written again: `site/SiteWeb.kt` (NetSiteWeb over Net: https only, the crawler form after a 403, the page's own character set through the charset library Ksoup brings, a document with its pictures inside streamed into a file), `ios/SiteCopy.kt` (BookSiteStore on the trip store: a note and its photos in one write, a new trip just before its first note ending demo mode, pictures kept as Android keeps them with Skia, the map one question a second; the importer kept by the app as Android's AppGraph keeps it, its reading off the main thread; `PlannerKit.siteAi`: the own key, else the built-in planner, each sorting one of the day's 40 answers), `ios/CopySite.kt` (Android's screen and texts), `iosMain/…/IosBackground.kt` (UIApplication's background task while it reads or copies, behind `BackgroundWork`). The notes', pictures' and items' ids are Android's UUID v3, checked against java.util.UUID. read_link now reads posts, blogs' front pages with their posts, feeds and published Docs as Android does (1015); the sorting counts and goes to the own key (1042, 1061). Left: 1070 Add to plan's "Copy its notes and pictures" and "Add its N places" (the share extension, round 8); 1080's way through Drive (round 23, owner: the Google iPhone client; the public export with its pictures and the published page are in; Drive's way came with round 23). A HEIC or AVIF picture on a page that Skia can't draw is left out and counted (Android keeps a HEIC and makes an AVIF a JPEG), and a picture's time and place are read from JPEGs only (both as Android since the second parity round). Only the desktop has run it: Ksoup on iPhone, the background task, a document streamed by URLSession and the decoding of other character sets need the Mac build.*
18. **Trip video I: the picture** (40): 17 Opening, Top bar, Preview, What the video shows, Pro and the mark. Needs: rounds 5 and 9; ClipRenderer, GlobeGround and PaperVehicle3D drawn with Skia; MapLibre snapshots.
   *Done (19 Nov 2026) for the picture and its preview. Everything the Android app works out for a clip is read in at build time and compiled as it is (`clipFiles` in the build file: ClipPlan with its timing, cameras, globe, zooms, labels, tags, photos, day cards and inserts, ClipDesign, ClipTimeline, ClipScenes, ClipEdits, ClipPicks, StopStays, the paper vehicles' and the traveller's meshes and walk, Rides and Traveller, the music's catalogue, shapes, beats and mix, ClipPrefs and MusicPrefs, PlannedReplay and RouteLegs, PreviewMath), and so is the renderer that draws a frame (ClipRenderer, GlobeGround, PaperVehicle3D, VehicleSprites, PlaneMarkBitmap): their android.graphics and android.text are `compat/graphics` (Canvas, Paint, Path, Matrix, the shaders, colour matrices, StaticLayout and TextUtils under Android's names, drawn with Skia through Compose's bridges, Android's rules kept: a paint's alpha, a shadow layer's blur and colour, a colour matrix's 0–255 offsets, bilinear bitmaps), the Map tab's globe is this app's own (`compat/graphics/ClipFaces.kt`), java.time, Locale, Random, TreeMap and the settings files are compat's (`compat/AndroidContext.kt`: the "clip", "clip_music" and "map_style" files kept as a backup carries them, so a backup restores the video's choices on either phone). The Android app's own clip tests run against the read-in files (37 files, 347 tests, and PreviewMathTest's 7). Written again here: the state of the screen (`ios/ClipStudio.kt`, Android's ClipViewModel as far as the picture goes: the route sources, the plan, the map, the photos, the style, the shape kept), a frame drawn from the clip and its number (`ios/ClipFrames.kt`: what the export will hand to AVAssetWriter), the prepared preview (`PreviewCache`: 15 fps of JPEGs from the playhead, within a sixth of the phone's memory class, a few workers), the map's pictures (`ios/ClipMaps.kt`, Android's BaseMaps: taken in the look's style and measured; on iPhone by MapLibre's MLNMapSnapshotter through Swift, `MapLibreSnapshots` in `iosApp/BoringTravel/MapLibreMap.swift` and `iosMain/…/IosClipMaps.kt`; the desktop, the tests and the screenshots draw the countries instead), the photos decoded at Android's size (`clip/ClipExport.kt`), and the screen (`ios/ClipScreen.kt`: the studio, its top bar, the stage, the play row and full screen). The studio's colours, type, controls and icons are the Android app's `ui/studio` files, read in. The ways in are the trip's ⋮ › Make a clip and the trip map's Play the route clip; the travel chooser's tiles are the paper vehicles now (VehicleSprites). Left: 684 the composer's Make a clip (Community, round 25); 685 the studio's light status-bar icons (the app sets no status bar style yet, 71); 693 scene edits per route (read here, made and kept by the editor, round 19; switching the route is round 20's Route tool; the edits came with round 19); 698 the export following the size and 699 Export (it says "Not on iPhone yet.", round 20; they came with round 20); 707 the music under the preview and 708 phone videos in it (round 19; they came with round 19); 709 the export's progress over the preview (round 20; it came with round 20); 710 dragging a name or a card (it needs a scene chosen on the tracks, round 19; it came with round 19); 799 phone videos in the clip (rounds 19 and 20: AVAssetImageGenerator, AVAssetReader; the preview's came with round 19); 828 Pro (round 27); 202 Make a book (round 21; it came with round 21). The play row under the preview shows the time and Play; its Undo and Redo come with the editor (714; they came with round 19). Only the desktop has run it: the MapLibre snapshotter's Swift, Skia and Compose's text on Kotlin/Native and the preview's speed and memory on a phone need the Mac build and a real iPhone. A frame takes about 12 ms at the preview's 540 × 960 and 34 ms at 1080 × 1920 on the Windows PC.*
19. **Trip video II: the editor** (46): 17 Time row and tracks, Tools, Text, Audio, Media, Camera. Needs: round 18; AVAudioPlayer, AVAudioRecorder, PHPicker for videos.
   *Done (19 Nov 2026) but for round 20's tools. The Android app's editor screens are read in at build time and compiled as they are, every word theirs (`clipFiles` in the build file: `ui/clip/TrackLayout.kt` with the tracks' blocks, the tools each scene has, the scale under the fixed playhead and its snapping, its TrackLayoutTest run here; `ClipTracks.kt`, the five tracks, the ruler, scrubbing and pinching; `ClipStage.kt`, the stage with a chosen name or card ringed and dragged; `ClipTools.kt`, `ClipSceneTools.kt`, `ClipTagTools.kt`, `ClipControls.kt` and `ClipPlacePhotos.kt`, every tool's panel with its sliders, thumb pad, fields, chips, the place's media grid and the Add media sheet). Their view-model is `ios/ClipStudio.kt`, Android's ClipViewModel written again with its members and its states as StateFlows ("ClipViewModel" reads ClipStudio in the read-in files): every change goes through Android's edit history (EditHistory, read in: 100 steps, typing one step, leg travel and Make default left out), the trip's scene edits are kept in clip-edits/<trip>.json in Android's form per route source (loaded again with the route, `setSource`), the clip's choices in the "clip", "clip_music" and "map_style" settings files a backup carries, phone videos copied into clip-media/<trip>/ and voice lines recorded into clip-voice/<trip>/ under Android's names (`clip/ClipMedia.kt`, `clip/ClipVoice.kt`, written again; files no edit uses go after an hour). What the screens reach of Android is compat's (`compat/AndroidOs.kt`: SystemClock, LocalConfiguration, a locale's uppercase, collectAsStateWithLifecycle on Compose's own; `compat/ActivityResult.kt`: the microphone's permission and the photo picker's launchers, so Android's rememberEditorMedia compiles as it is), Java's number formats are written out as Java writes them. The phone's side is behind `ClipDevice` (`ios/ClipDevice.kt`; iPhone: `iosMain/…/IosClipDevice.kt`): the Android app's eight bundled CC0 tracks (its own assets, read in) through AVAudioPlayer, for Audio's Listen and under the preview in step with the playhead (`ui/clip/MusicPreview.kt`, Android's rules: from the clip's music start, looping past the track's end, waiting while the preview waits, jumping back only when 220 ms off; voices and video sound only in the export); a voice line through AVAudioRecorder and the audio session, the microphone asked only when the first line starts; a phone video from PHPicker's videos filter (`IosMediaPicker.pickVideos`), its length from AVURLAsset, its pictures for the preview from AVAssetImageGenerator, cropped to fill with Skia (Android's VideoFit) and kept as 8-a-second JPEGs (`clip/ClipVideoFrames.kt`'s PreparedVideoFrames, Android's). The time row and the tools step aside while the keyboard is up (Compose's ime insets). The desktop runs stand-ins (silent music, a placeholder microphone, drawn videos: `DesktopClipDevice.kt`) and the tests fakes that record what they are asked. Left: 725's Length, Route, Look and Stats say "Not on iPhone yet." (their panels are Android's own and compiled with the rest, behind `ROUND_20_TOOLS` in `ios/ClipScreen.kt`: lines 771 to 789, round 20; Export too, 699; they came with round 20); 726's Back: the editor has no Back on iPhone, its panel's Done and the bar's Let go do what Back does; 728's controls off while exporting wait for the export (every panel already takes `enabled`; it came with round 20); 799's export pictures (AVAssetReader, round 20: the preview shows the video's own pictures; they came with round 20). Only the desktop has run it: AVAudioPlayer, AVAudioRecorder, the audio session and its record permission, AVURLAsset, AVAssetImageGenerator and PHPicker's videos need the Mac build and a real iPhone (749: the owner's microphone check).*
20. **Trip video III: export and sharing** (43): 17 Length, Route, Look and stats, Export, Sharing the video; 29 Your traveller & rides; 6 the Media tab's clips. Needs: round 19; AVAssetWriter, AVAudioEngine, Photos. The Drive buttons are finished in round 23.
   *Done (19 Nov 2026) but for Drive, TikTok's own posting and what other rounds bring. The four tools are Android's own panels (compiled since round 19, open now): Length (Auto and the set lengths, Pace with each speed's length, a place's Time here), Route (Route from with its notes, Left out with Put back, a recording's Travel as paper models, the line's style, width and colour, a planned place's How you got here and Count in km, and Traveller & rides for this video: Android's `ui/clip/ClipTravellerTool.kt` and `ui/traveller` read in, with Use my default and Make default into Me's), Look (the four looks, Custom… after a change, Letterbox bars, Grain, Tone, Reset every scene) and Stats; every change one step of the edit history, kept in Android's form; a leg's travel changed on the plan re-plans an open clip (the trips' revision; while an export runs the change waits for it, as Android's deferred choices). **The export** is Android's ClipViewModel.makeClip, ClipExport.make, Mp4Encoder's settings and ClipMusic's steps written again as common Kotlin (`ios/ClipExporter.kt`, `clip/music/ClipMusic.kt`, `clip/ClipVideoExport.kt`): the room checked first (2.5 times the video's size and 50 MB, else Android's failure); the sound made alongside the frames (the music decoded from its start; Android's MusicMix, read in: faded in over 0.3 s, 0.8 s mid-song, and out over 1.8 s, looped with a 1.5 s equal-power crossfade, the voice lines on top with the music ducked to 0.3, the phone videos' own sound at 0.45 under music, 0.9 alone); every frame drawn by ClipFrames (the preview's renderer) in order and handed to the writer with its number, at the chosen size (1080 × 1920 for 9:16), 30 fps, Android's bit rate (16 Mbit/s for 9:16) and a key frame every 2 s, the phone videos' pictures read forward exactly (AVAssetReader; Android's DecodedVideoFrames picking and its VideoFit turning, written again); then the pictures and the sound (AAC, 128 kbit/s, cut at the last picture) joined into one MP4 beside the last one and moved over it only when whole; Android's ExportBar (read in) for the progress, a picture from 40 % in for the trip's Media. The iPhone side is a thin writer behind `VideoWriter` (`iosMain/…/IosVideoWriter.kt`: AVAssetWriter with a pixel-buffer adaptor for the pictures, H.264 High where the phone takes it, no B-frames; the join with a passthrough input and an AAC input; AVAssetReader for sounds and videos; Photos; canOpenURL), written with Apple's documented call order; the desktop's writer records what it is given and writes a Motion-JPEG AVI with the same pictures and sound. **In the background:** an iPhone's video encoder stops when the app leaves the screen, so the pictures go into parts: when the app resigns active the part ends at the frame it is on, the export asks for background time (UIApplication's background task) to finish it while the sound goes on, waits, and starts a new part from the next frame when the app is back; a part the phone broke off is made again from its first frame; the parts are joined at the end. iOS gives the app about half a minute and may end it meanwhile: then the export is gone (Android's foreground service goes on), as it is on Android when the editor is left. **Sharing:** Android's Export sheet word for word (the line "9:16 · 0:42 · Latin Groove", Create video, "The map is still coming in…", "Making the video · N%" with "You can close this; it carries on." and Cancel, the same over the preview with every control off, Adding the sound, Finishing, Your video is ready, "It's also kept in the trip's Media.", "Couldn't add the music, so this video is silent.", the failure with Try again), Watch (the phone's own player, full screen with its play, scrub and time), Send video (the share sheet), TikTok always and Instagram and WhatsApp when they are on the phone ("… isn't on this phone. Save the video to your phone and add it there." when one isn't), Save to phone (Photos, add-only access asked at that moment; it says "Saved to Photos." where Android names its Movies › BoringTravel folder, as Share your world does), Save to Drive saying "Google Drive isn't connected." with Connect Drive (Me › Backup & sync). **Kept:** every finished clip in the trip's Media (clips/, Android's sidecar and ids, `data/SavedClips.kt`; Android's SavedClipsTest runs against it); the Media tab is Android's (Clips · N with play, Share and Delete; the trip's photos and videos by day, a tap opening the note, press and hold deleting one of the traveller's own with its layout reflowed; Add media opening a new note with the photo library); a backup file carries the trip videos' scene edits, voice lines, phone videos and saved clips (extras/, Android's names and checks, `backup/BackupExtras.kt`) both ways; a deleted trip takes them with it. **Me › Your traveller & rides** is Android's own row and sheet (`ui/me/TravellerSetting.kt` read in, its figures on Skia), the traveller and rides this phone's own settings under a backup's names (Android's RidesTest and TravellerChoicesTest run here); the demo's Media tab shows only the examples (1394). Left: 684 the composer's Make a clip (Community, round 25); 685 the studio's light status bar (71); 820 going straight into an app: TikTok's direct posting needs its Share Kit and the owner's TikTok developer app, so an installed TikTok, Instagram or WhatsApp takes the video from the share sheet (its own share extension) and a missing one says Android's words; 822 and 823 Drive (round 23, the owner's Google iPhone client; they came with round 23); 828 Pro (round 27); 174 and 1454 a deleted trip's book and the book's files in a backup (round 21; they came with round 21). Only the desktop has run it: AVAssetWriter and its pixel buffers, AVAssetReader, the parts joined by moving their samples, the background task around a part, Photos' video and canOpenURL need the Mac build (the tour now makes a video) and a real iPhone (owner: the export's speed and memory). On the Windows PC a 23 s clip's 679 frames take about 21 s to draw and hand over at 1080 × 1920 (30 ms a frame), and the process's memory stays flat from the 60th frame to the last (the JVM process committed about 1.1 GB at the 60th frame and 0.9 to 1.0 GB at the last, in two runs: nothing is kept per frame).*
21. **The book I** (32): 18 Making it, Looks, All pages, Book look. Needs: round 13's PDF path; Core Text measuring that breaks lines as WebKit does.
   *Done (19 Nov 2026), and with it part of round 22. The Android app's whole book is read in at build time and compiled as it is (`bookFiles` in the build file: BookModel, BookJson, BookLayout, TextFit, PageLayout, BookFonts, BookCss, BookHtml, FreeEl, FreeLayout, FreeEdit, FreeMath, Layers, AlbumEdit; with round 13's BookFacts, BookSvg, Flags, PageGeometry, CropMath; `book/BookModelParts.kt`, round 13's copy, is gone): their java.time, UUID, Math, String.format and Map calls go to compat's or Kotlin's (the engine's days, `data.repo.newId`, `JavaFormat`, getOrPut). So are the book's screens as far as they are plain Compose: the overview of all pages (`ui/book/BookScreen.kt` with BookScreen, BookEditor and MakeSheet cut out on the way in: the build's new `cuts`, each checked), the Book look sheet, the layouts, the photo grids and album pages (`BookSheets.kt`), the page thumbnails (`PageViews.kt` with its WebView cut out; its `moveScript` and `resetScript` stay for round 22) and the looks' mini pages (`ui/export/ThemeThumbs.kt`). Android's own book tests run against the read-in copies (11 files, 91 tests), and a test compiles Android's book untouched and finds the first layout's JSON, every page's HTML in each mode, one editor page and the photos' sizes byte for byte Android's for every look, size, font set, paper and bleed, and each phone reading the other's book to the same JSON (BookAgainstAndroidTest). Written again: the view-model (`ui/book/BookViewModel.kt`, Android's with its State and rules: undo over 30 steps, the text poured off the main thread, a save 0.4 s after a change and one on leaving), the screens Android's file reaches Android from (`ios/BookScreen.kt`: BookScreen, BookEditor, MakeSheet, and a page opened large with Android's PageEditor's top bar and stage, until round 22 puts its tools on it), the books' store and files (`ios/BookExports.kt`, Android's BookExporter: the draft is the trip store's `books` row, the engine's `TripBook.latestBook`, `saveBook`, `removeBooks`, so a backup carries it either way and a deleted trip takes it; photos made ready by round 13's PhotoPrep at the page's needs, about 300 dpi; the PDF at the book's own sheet), the page on screen (`ui/book/PagePreview.kt`: WebKit on iPhone through the UIKit interop, the page's own scripts off and the editor's moves through evaluateJavaScript ready for round 22; the desktop's stand-in draws the same HTML, `desktopMain/…/BookPageDrawer.kt`). **Text** is measured as the page prints it: Core Text on iPhone (`iosMain/…/book/CoreTextMeasure.kt`: the bundled font files, the weight on the 'wght' axis as Android's Paint sets it, each word one CTLine), Skia's shaper on the same files on the desktop (`desktopMain/…/book/SkiaTextMeasure.kt`), behind Android's TextMeasure. **WebKit's forms** (`ios/WebKitPrint.kt`): the highlighter's bands (`.mk`, the mono dates' `.mk`, the notebooks' bold words; `var(--hl)`) are one flat colour sized and placed where the band was; the scrim over a "Full page with caption" photo is a picture of itself (`ios/ScrimPicture.kt`: a 2 × 256 PNG drawn with Skia, black, clear at the top to 62 % at the foot); for paper, the zoom for WebKit's print scale on its rounded width and each page's section a pixel shorter than WebKit's page, so none runs onto the next sheet; tested against Android's stylesheet and every page it writes (BookWebKitTest). Also: the trip's ⋮ › Make a book and Share's "Make a book" open it (202), a new book takes Share's style (922), a deleted trip's book is marked deleted (174), a backup carries the book both ways (1454); 868 and 869 (the page large, its top bar), 897 and 898 (From phone in the grids, the empty grids' words), 907 (saving), 908 and 909 (the PDF sheet, Save, Share, Print at the book's size) are in. Left (round 22's): 870 to 879 the page editor (selecting, moving, resizing and turning by hand, its tools' row, Back, the layers list), 882 to 896 the page tools, 906 a panel visit as one undo step (the panels come with them; all came with round 22); 910 Send to Drive (round 23, owner: the Google iPhone client; it makes the PDF and says Drive isn't connected; it came with round 23); 911's "Too many large photos for one go…" can't show on iPhone, which ends an app out of memory where Android throws (the other messages are in). Only the desktop has run it: Core Text's measuring, WebKit's pages on screen and in print (the bands, the scrim picture, the section heights against WebKit's own rounding), and printing at the book's size need the Mac build (the tour now makes the tour trip's book, opens its cover and makes its PDF) and a real printer (owner).*
22. **The book II** (35): 18 Page editor, Page tools, Undo, saving and the PDF. Needs: round 21; JavaScript in WKWebView for live moves.
   *Done (19 Nov 2026) but for Drive. Android's page editor is read in at build time and compiled as it is (`bookFiles` in the build file): `ui/book/PageEditor.kt` (the page with its top bar, the stage with "+ Photos", Undo and Redo, the tool row: Page and Add with nothing selected, what is selected's own tools and Arrange otherwise, hidden while the keyboard is up; the open tool's panel or the layers in place of it), `PageCanvas.kt` (the layer over the page: a tap selects what is on top there, again opens its main tool, beside the page lets go; a drag moves it, snapping to the page's edges and middle, the margins and the other elements with dashed guides, 6 mm kept on the page; a corner resizes it, photos and stamps keeping their shape, 4 mm at least; the round handle turns it, snapping to eighths; "W × H mm" or "N°" under it while the finger moves, drawn with Android's paint and text on compat/graphics' Skia), `ToolPanels.kt` (every tool's panel: Layout with the album arrangements and the 16 layouts, Paper, Look, Page with Duplicate, Use this layout on, Add a page after, Add photo pages, Back to the layout, Hide entry, Delete page; Add and its shortcuts and flags; a photo's Replace, Choose, Crop, Frame and Caption; words' Edit, Page text, Font, Colour, Size and Align; shapes' and stickers' Style; Rotate with its nudges; "+ Photos" with No room and its three ways out) and `LayerList.kt` ("On this page · top first" with "+ Add", the rows with their pictures, names and details, the handle that drags a row forward or back, the eyes, the background row last, the photos a layout has no room for with New page). A few lines are rewritten on the way in: the page's folder is the view-model's (Android: AppGraph's books), its paper colour compat's, its fonts under fonts/ as round 21's page; Compose's `isImeVisible` (Android's only) is `compat/ImeInsets.kt`'s from the keyboard's insets; a face's font file for its chip and row is `book/FaceFonts.kt`'s `Face.fontRes` (the same files as Android's PaintTextMeasure names). They edit through round 21's view-model (`ui/book/BookViewModel.kt`): every change is a step of Android's EditHistory (30 steps; a panel's visit is one step: its changes shown live, the step ended when it closes or another is selected), kept in the trip store's `books` row in Android's JSON, so a page arranged on one phone opens arranged on the other (the tests read every edit with Android's own BookJson). `ios/BookScreen.kt` keeps Android's BookEditor's selection and open tool with its rules (another selection closes the panel, a page opens with nothing selected) and gives the editor the room above the home indicator. **The page follows the finger on iPhone:** while an element is dragged, Android's own `moveScript` moves it in the WKWebView through evaluateJavaScript and `resetScript` puts it back (round 21's `PagePreview.ios.kt`), the page laid out again from its edited HTML when the finger lifts; the touches are Compose's, over the UIKit view (which takes none). On the desktop the stand-in draws whole pages from the edited HTML (no JavaScript), so the tests and pictures show a move once the finger lifts (`book-editor-moved`). 876: iPhone has no Back; the panel's Done, a tap beside the page and All pages do Android's three steps (as in the trip video's editor, 726; the swipe from the edge is line 92). Tested on the desktop with taps, drags and typing (BookEditorOnDesktopTest, 15 tests: selecting, moving with snapping and 6 mm kept, resizing, turning to 45°, the page arranged by hand looking the same, Undo and Redo, the layers' rows, eyes and handle, "+ Photos" and its No room, the Layout, Paper, Page and Add tools, a photo's, words' and shapes' tools, Arrange; a live visit as one undo step and 30 steps kept) and on both platforms (BookEditorSmokeTest: the live move's script with Java's numbers, the page's handles in its HTML, a move kept in Android's JSON, snapping, the layers' words, the colours' checks, every face's font file). Left: 910 Send to Drive (round 23, owner: the Google iPhone client; it makes the PDF and says Drive isn't connected; it came with round 23); 911's "Too many large photos for one go…" can't show on iPhone, which ends an app out of memory where Android throws. Only the desktop has run it: Compose's touches over the WKWebView, evaluateJavaScript's live moves and how quickly WebKit shows a page again after each change need the Mac build (the tour now opens the tour trip's title page in the editor and selects its title) and a real iPhone.*
23. **Google account and Drive backups** (43): 1 First launch; 32 Accounts and sign-in, with Sign in with Apple; 33 Google Drive, Backing up, the Drive side of Restoring. Needs: Drive over Ktor, BackgroundTasks; the owner's Google iPhone client and Firebase app.
   *Done (19 Nov 2026) against fakes, waiting on the owner's Google iPhone client for a real account. **Sign-in** is Google's OAuth for installed apps in common Kotlin (`google/GoogleOAuth.kt`): Google's page in ASWebAuthenticationSession (`iosMain/…/IosGoogle.kt` `IosAuthBrowser`, sharing Safari's Google sign-in, so an account used before goes straight through), PKCE (S256) with the iOS client's own address (`com.googleusercontent.apps.<id>:/oauth2redirect`, caught by the session itself: no URL type), asking exactly Android's scopes (openid, email, profile with drive.file and drive.appdata, `include_granted_scopes`), the refresh token in the Keychain (its own service, this device only, gone after a reinstall as the AI keys'), the name and email from the ID token of Google's token answer; no SDK and no Swift. One button as on Android: "Continue with Google" asks for the account and Drive on one page, and a no to Drive (Google's tick box) still signs in. **Sign in with Apple** beside Google on Welcome and Me (Apple 4.8): ASAuthorizationController with a hashed nonce and Apple's own button (`IosAppleSignIn`, `IosAppleButton`; the desktop draws a stand-in), the account kept in the owner's Firebase Authentication over REST (`google/AppleSignIn.kt`: signInWithIdp, the refresh token in the Keychain); offered only when the build value `apple.signIn` is on and Firebase's ids are set. **Delete account** (Me's red link, Android's words): with Apple, Apple's sheet once more for a fresh code, Firebase revokes Apple's token with it (accounts:revokeToken, App Review 5.1.1(v)) and deletes the account, and if that sheet is closed nothing is deleted ("Apple's sign-in was closed, so nothing was deleted."); then, as on Android, the own AI keys go (1060), Drive's access is revoked and the account forgotten, finishing in the app's scope even if Me is left. **Drive** is Drive's REST v3 over the app's Net (Ktor; `google/RestDrive.kt`): Android's DriveJson read in (its DriveJsonTest runs here), multipart uploads up to 5 MB and resumable ones in 8 MB pieces picking up where Drive says, one new token on a 401, busy answers retried, folders remembered by id (a renamed or moved folder still found), files shared by link, Docs exported (`google/Http.kt` with Android's readable failures). **Backups** are Android's BackupManager's Drive half written again (`ios/DriveBackups.kt` over round 12's `ios/Backups.kt`): nothing new means no zip (BackupStamp), the zip checked before and after its upload, the marker in appDataFolder, the newest 10 kept, every own photo once in Photos (checked by checksum, one bad photo skipped and counted, three refusals and the rest wait), every saved video once in Videos (Android's BackupVideos read in) with the editor's files, videos waiting for Wi‑Fi when asked; restores through round 12's merge with Undo, missing or damaged photos, videos and editor files downloaded, the trip videos' scene edits back; the restore offer and the wait for its answer (`ios/RestoreOffer.kt`, BackupSafety); Backup & sync, Welcome's account step, Me's Account card and Trips' backup line are Android's screens written again (`ios/BackupScreen.kt`, `Welcome.kt`, `Account.kt`, `TripsHome.kt`), words and order Android's. The daily backup is a BGProcessingTask with network (`IosBackupTask`, `com.justdataplease.boringtravel.backup.daily`) asked for the next 22:00 after each backup or change of the switches, and on opening the app after 22:00 when it was missed; Android's rules (not within 18 h, three retries, Only on Wi‑Fi as a network that isn't costly, the battery as Low Power Mode off); no notifications. **Every Drive button:** the trip video's Save to Drive and Send Drive link (822, 823), the book's Send to Drive and its link (910), Share's Google Docs with Android's plainer pages and photos by count (934 to 936, 947), Copy from a link's Docs through Drive's HTML export first (1080), Me's Account card and Backup & sync row (1349), and a note's "Videos take a while to upload." when backups are on (500's first half). **Without the build values** (CI, the Simulator's tour) every place says Android's "Google sign-in isn't set up in this build." and Welcome's plain Continue goes to Trips (59: an iPhone can't reach Drive without the iOS client). Left: 59 (above); 500's shared trips, 1450's shared-trip memberships (round 26); 1412 Google sign-in into Firebase for the community and trip mailboxes, 1415's community sign-out and 1417's community data and trip mailboxes in Delete account (rounds 24 and 26); 1346's Community profile and Pro rows (rounds 25, 27). Tested on the desktop against a fake Google (`desktopMain/…/GoogleFakes.kt`: its token, revoke, Drive v3 and Firebase routes in memory, a browser that accepts, unticks Drive, closes, denies or breaks, an Apple sheet): RestDriveOnDesktopTest, BackupNamesOnDesktopTest, GoogleAccountOnDesktopTest, DriveBackupsOnDesktopTest, AccountScreensOnDesktopTest, DocsThroughDriveOnDesktopTest, and new tests in BookOnDesktopTest, ShareOnDesktopTest, ClipExportScreenOnDesktopTest and SecondParityTest; on both platforms DriveSmokeTest. No live call in any test or picture. Pictures: `welcome-*`, `me-account-*`, `me-delete-*`, `me-sign-out-ask`, `backup-drive-*`, `backup-no-google`, `restore-offer*`, `trips-backup-*`, `book-drive-sent`, `share-docs-*`, `clip-export-drive-*`. Only the desktop has run it: ASWebAuthenticationSession, ASAuthorizationController and Apple's button, BGTaskScheduler's daily task, the costly-network check, Low Power Mode, SecRandomCopyBytes and Ktor Darwin's large uploads and downloads need the Mac build, and a real Google and Apple account the owner's clients.*
24. **Community I** (42): 22 Signing in, Feed, Likes and the post page, Report and block; 4 Start from a community trip. Needs: round 23; the Firebase iOS SDK and the iPhone Drive API key (owner).
   *Done (19 Nov 2026) against fakes, without the Firebase iOS SDK: its REST APIs do all of it. The Android app's community is read in at build time and compiled as it is (`communityFiles` in the build file: CommunityModel, CommunityRepository, Leaderboard, FollowingFeed, Follows, FollowKeeper, CommunityLinks, SharedTrip, TripCopy, Usernames, ProfileAvatar; the screens CommunityParts, CommunityScreen, FeedViewModel, PostScreen, PostViewModel, CommunitySheets, MediaViewer, FollowParts, ProfileScreen, FollowListScreen, ProfileEditScreen), every word and rule Android's; what reaches Firebase, Coil, Android's sign-in and its video view is cut out on the way in and written again in `ui/community/CommunityPhone.kt` (DrivePicture, SignInCard, SignInGate, TripPickerSheet, ClipViewer); Android's Toast, share intent, LocalContext and view-models are compat's (`compat/AndroidScreens.kt`: a view-model store per screen on the stack, cleared when it goes), AppGraph and AppActions this app's (`ios/CommunityNav.kt`). Android's own community tests run here (10 files). **Transport:** Firestore's REST API (`firebase/FirestoreRest.kt`: typed values, documents, structured queries with filters, orders and cursors, collection groups, counts, commits with server times and increments, transactions, the ID token renewed once on a 401, App Check's header when there is a token) under Android's `CommunityRepository` (`community/FirestoreCommunity.kt`: Android's paths, documents, queries, writes and words, a username claimed in a transaction, a like with its count, a follow with both halves, everything of theirs deleted); Firebase Auth's REST API for the account (`community/CommunityAuth.kt`: Google's ID token through signInWithIdp, the Sign in with Apple account's own Firebase account, Secure Token's refresh, the session in the Keychain, an account Firebase no longer has signed out). What the SDK keeps on the phone is kept by hand (`community/CommunityStore.kt`): the lists and documents last read in the caches (20 MB, shown offline: "Offline · showing what was saved"), and the writes made offline (likes, reports, blocks, follows) in this phone's settings, sent in order on the next write, a return to the app, a refresh or a sign-in. `community/Community.kt` is Android's Community written again (hearts, follows, blocks, deleting). Other people's pictures and clips come from their Drive by link with the build's Drive API key and the bundle id's header, else Google's public download address (`google/PublicDrive.kt`, `community/CommunityPictures.kt`: 150 MB of pictures, clips downloaded once, 6 or 300 MB kept), never with the viewer's Drive token; a clip plays in the app's own player from the caches. **The app:** the Community tab and its pages (a post, a profile, follow lists, Community profile) under the tab's bar and brought back after a restart; a post's link (`https://justdataplease.com/apps/boringtravel/post/#<id>` as a universal link, the Associated Domains entitlement in `iosApp/project.yml`, and `boringtravel://post/#<id>` for the website to fall back on) opens the post; New trip's "Start from a community trip" (152); Me's Sign out signs out of the community (1415) and Delete account deletes the community data and its Firebase account first (with Apple: after Apple's sheet, before Apple's token is revoked); a trip post's small map draws dots and a thin dashed route as Android's (`ios/MapLook.kt` compact). **Apple 1.2:** Report (a post, a profile) and Block are Android's; the iPhone adds a way to reach the developer, "Something wrong? Write to …", under the sign-in card and in the Report sheet (1170); the community rules (Android's RulesSheet) come before the first profile or post (round 25's posting). **Apple 4.8:** Sign in with Apple beside Google on the community's sign-in card. Left: 1129 posting's username (round 25); Posting and Your posts (round 25: + opens "Post a trip", a trip there says "Not on iPhone yet."); Profiles and following, Community profile: the screens are Android's and work against the fakes (pictures `profile-*`, `follow-list`, `community-profile-blocked-list`), their lines ticked with round 25 and its tests; 1412 and 1417's trip mailboxes (round 26). Tested on the desktop: CommunityOnDesktopTest (17: the tab signed out with Google's and Apple's sign-in and the contact line, signed in quietly, Latest, Top and Following, a heart reaching Firestore and waiting offline, Report a post (and offline) and a profile, Block from a post with the follows ended, unblocking from a profile and the Blocked list, a post gone and a plan that can't be read, Copy this trip without dates and a plan of ideas, Share and a post's link, the app's sign-out and Delete account, New trip's way in, offline from what was saved, a build without it), FirestoreCommunityOnDesktopTest (13: the transport against the in-memory Firestore that holds the owner's rules as far as the app meets them), Android's 10 community test files; on both platforms CommunitySmokeTest (9). No live call in any test or picture. Pictures: `community-*`, `post-*`, `profile-*`, `follow-list`, `new-trip-community` (`gradlew :composeApp:screenshots -PshotsOnly=community`); the Simulator tour's `03b-community` (the tab signed out, or "Not in this build"). Only the desktop has run it: Ktor Darwin's calls to Firestore, Firebase Auth and Drive, the Keychain's session, AVPlayer from the caches, the compact map layers in MapLibre, the heart's haptic tick and universal links (the site's file and a signed build) need the Mac build and a real iPhone.*
25. **Community II** (43): 22 Posting, Your posts, Profiles and following, Community profile; 6 Post to community, Community post. Needs: round 24; round 20 for clip posts.
26. **Travel together** (37): 23 Travel together; 6 the Shared badge, Travel together, Leave trip. Needs: round 23; universal links (owner); BGAppRefreshTask.
27. **Pro** (9): 34 Pro and purchases. Needs: the owner's decision to sell Pro and the App Store Connect products; StoreKit 2.

**The parity round (19 Nov 2026).** The owner: "we need to be ios and android exactly the same". Done: the status bar per theme and studio; the swipe from the left edge as Android's system Back, with every one of Android's BackHandlers; the screens' fades; each tab keeping its pages and place; a note saved when the app leaves the screen, and the screens, tabs, trip tab and day and the note being written brought back after iOS closed the app (scene storage); dates and numbers in the phone's language exactly as Android writes them (NSDateFormatter and NSNumberFormatter with the phone's first language behind the engine's Dates and compat's java.time and String.format, Android's patterns and Greek capitals); the printed footer in Caveat 600 and IBM Plex Mono 500 from the bundled files; printing on the printer's paper nearest the page; the share sheet's subject; a note's photos only looked at and Notes outside trips without Write, as on Android; Google's Roboto as "Phone (Roboto)" and wherever Android draws its system face (the widgets too); Android's versionName in Me and as the App Store version; the launch screen with the icon as Android 12's splash; the app turning sideways as Android's does. Pictures: `status-*`, `el-*`, `landscape-*`, `notes-outside-trips*`, `me-version`; tests: ParityTest, ParitySmokeTest.

**The second parity round (19 Nov 2026).** Done: Android's own video player (ClipViewer: plays at once with sound, a tap pauses, Play/Pause, the bar, "0:12 / 0:45", Close, "This video can't be played.", and "This video isn't on this phone yet…" for a missing file) for a note's video and a saved clip, and Android's FullScreenVideo for Watch, both drawn by Compose over an AVPlayerLayer (`ios/PhotoViewer.kt`, `ui/clip/ClipPreview.kt`; `iosMain/…/IosVideoPlayback.kt`); a note's video made 720p as Android's JournalVideos makes it (H.264 at Android's bit rate, the short side 720, AAC 128 kbit/s, HDR as Rec. 709, upright; AVAssetReader and AVAssetWriter in `iosMain/…/IosVideoPrep.kt`), with "Preparing the video… N%", its X, "Stop adding the video?", the screen kept on, Android's limits (30 minutes, 256 MB, 240 MB as is) and words, a recording up to 30 minutes, and its time and place from its own tags (Android's MediaTypes, VideoLimits and VideoTags in `data/repo/MediaTypes.kt`); photos kept in their own format (PHPicker's current representation: a HEIC stays a HEIC), their time, place and size read from every format with ImageIO and drawn through ImageIO where Skia can't (`iosMain/…/IosImages.kt`), a camera photo keeping the camera's metadata; Copy from a link keeping a HEIC and making an AVIF a JPEG; New trip's and Edit trip's cover and Photos in notes, and new photos in a note taking the trip's size and frame (Android's TripPhotos read in); the item sheet's "Journal · N notes" and Write, placing the note at the item; the map turning with two fingers (no compass, no tilt), its taps and fits on the turned projection; the calendar's zone from the planned places' countries (IANA's zone.tab, bundled); a restored clip's picture from 40 % in; Watch's and the clip preview's phone-video pictures at their exact times; mixed localizations declared; the route's Live Activity (Lock Screen and Dynamic Island, Stop on iOS 17: `iosApp/BoringTravelWidget/RecordingLiveActivity.swift`, `RecordingAttributes.swift`, `iosApp/BoringTravel/RecordingActivityBridge.swift`, driven by `ios/Tracking.kt`'s `RecordingCard`); iOS 26's continued processing for the export and Copy from a link (`iosMain/…/IosBackground.kt`). Pictures: `player-*`, `clip-watch*`, `write-preparing-video-*`, `write-stop-video-ask-*`, `new-trip-cover-*`, `edit-trip-cover-*`, `item-journal-*`, `map-turned-*`, `calendar-zone-country`, `live-activity-*` (Compose's drawing of the same words; the SwiftUI view is seen only on a Mac), `video-*`; tests: SecondParityTest, SecondParitySmokeTest, two more in ClipExportOnDesktopTest and one in RouteTrackingTest.

## Waiting on the owner

- **Apple Developer Program.** Unblocks runs on a real iPhone, TestFlight, and the capabilities: App Groups (widgets, the share extension: since round 10 the project asks for `group.com.justdataplease.boringtravel` for the app and its widget extension `com.justdataplease.boringtravel.widget`; register both there), Associated Domains, Sign in with Apple, background location on a device.
- **Where the Mac CI runs** (the private repository pays for macOS minutes). Unblocks the Simulator check of every round.
- **A Google OAuth client for iPhone** in the Google Cloud project of Android's web client: Credentials › Create OAuth client ID › type iOS, bundle ID `com.justdataplease.boringtravel` (the App Store ID and team ID once the app has them); the Drive API enabled; drive.file and drive.appdata (with openid, email, profile) on the consent screen, as Android asks them, and the same verification (test users until it passes). Its client ID goes in the build value `google.iosClientId` in `ios-boringtravel/local.properties` or `BORINGTRAVEL_GOOGLE_IOS_CLIENT_ID` (never in git); the app derives its own redirect from it, so no URL scheme is added. Unblocks a real account for everything round 23 built (sign-in, Drive backups and restore, Google Docs, Send to Drive, Save to Drive) and community sign-in and travel together (rounds 24–26).
- **Sign in with Apple** (round 23, App Review 4.8 beside Google): the Sign in with Apple capability on the App ID `com.justdataplease.boringtravel` in the Apple Developer account (the project asks for the `com.apple.developer.applesignin` entitlement); in Firebase Authentication the Apple provider turned on (a Services ID, a key with Sign in with Apple, the team ID; the key is what lets Firebase revoke Apple's tokens when an account is deleted, 5.1.1(v)). Then the build value `apple.signIn=true` (or `BORINGTRAVEL_APPLE_SIGN_IN=true`) with Firebase's `firebase.projectId`, `firebase.appId`, `firebase.apiKey` (the iPhone app's API key restricted to the bundle ID). App Store Connect's privacy answers then add Email Address and User ID, linked to the person, for app functionality, not tracking (as `PrivacyInfo.xcprivacy` says since round 23).
- **The Firebase iPhone app** (GoogleService-Info.plist, Google and Apple providers, App Check with App Attest, AI Logic). Unblocks the built-in AI planner, Sign in with Apple (round 23), the community, trip mailboxes and their part of account deletion (rounds 16, 23–26). For the community (round 24) the app needs no plist and no SDK, only the build values `firebase.projectId`, `firebase.appId` and `firebase.apiKey` (or `BORINGTRAVEL_FIREBASE_*`; the iPhone app's API key restricted to the bundle ID, with the Identity Toolkit, Token Service and Cloud Firestore APIs allowed); the Google provider on in Firebase Authentication; Google's iPhone OAuth client in the same Google Cloud project as Firebase, or its client ID added under the Google provider's "Safelist client IDs from external projects" (Firebase checks the ID token's audience); the community's Firestore rules as they are deployed for Android (nothing new). If App Check is enforced on Firestore, the iPhone needs App Attest first (App Check's header is sent once the phone has a token; none is made yet).
- **A Drive API key restricted to the iPhone app.** Unblocks community pictures and clips, and shared trips (rounds 24–26). An API key in the Google Cloud project with the Drive API allowed and "iOS apps" with the bundle ID `com.justdataplease.boringtravel` as its restriction (the app sends the bundle ID's header with it); it goes in the build value `drive.apiKey` in `ios-boringtravel/local.properties` or `BORINGTRAVEL_DRIVE_API_KEY` (never in git). Without it the app reads the posters' files from Google's public download address, which Google may limit.
- **apple-app-site-association on justdataplease.com** for `/apps/boringtravel/post*` and `/join*` (the owner deploys the site). Unblocks post and join links (rounds 8, 24, 26). Since round 24 the app asks for `applinks:justdataplease.com` (the Associated Domains capability on the App ID, Apple Developer Program above). The file, served at `https://justdataplease.com/.well-known/apple-app-site-association` as `application/json` with no redirect (`<TEAM_ID>` the Apple Developer team ID): `{"applinks":{"details":[{"appIDs":["<TEAM_ID>.com.justdataplease.boringtravel"],"components":[{"/":"/apps/boringtravel/post*","comment":"community posts"},{"/":"/apps/boringtravel/join*","comment":"travel together"}]}]}}`. The post page on the site can also send `boringtravel://post/#<id>` (the app opens it today, without the file).
- **Community moderation** (Apple 1.2, round 24). Reports land in Firestore's `reports` collection (the post, the person, the reason, who and when), as Android's do; Apple wants them acted on within 24 hours (the post removed, the person who posted it removed) and the community rules agreed to before posting (Android's RulesSheet, round 25). The app has Report, Block and the contact line "Something wrong? Write to …"; it has no automatic filter for objectionable words or pictures (neither has Android): App Review may ask how content is filtered, and the review notes should say that reports are checked daily and how.
- **App Store Connect.** The app record, the privacy answers (precise location, kept on the phone, not linked to the person) and review notes for background location (since round 10 the app declares the `location`, `fetch` and `processing` background modes and records in the background with While Using after the traveller opens it during a trip: the notes say it records only between a trip's dates, after the traveller picks a level, and the points stay on the phone. Always is built but off, as on Android (`IosTrackingPlan.ASK_ALWAYS`); turning it on is the owner's call and adds Always to those notes); the Pro products (yearly, for life) when Pro goes on sale. Since round 24 the privacy answers add, for the community: Name, Email Address, User ID and Other User Content (the profile, posts and their plans, likes, follows, blocks, reports), linked to the person, for app functionality, not tracking (as `PrivacyInfo.xcprivacy` says); round 25's posting adds Photos or Videos only if the owner counts the posters' Drive files (they stay in the poster's own Drive). Unblocks TestFlight, review and round 27.
- **A real iPhone** (and a printer). Unblocks the last check of the camera, the microphone and Greek dictation, the trip video's music and voice lines, GPS and battery for tracking, check-in accuracy, video export speed and memory and the export going on after the app was away from the screen, printing (rounds 5, 6, 10, 13, 19, 20, 22).
- **Four decisions.** Sign in with Apple beside Google (needed because Google sign-in also signs in to the community; built in round 23 behind `apple.signIn`, so it ships only when the owner turns it on); when Pro goes on sale; whether TikTok's direct button stays (it needs a TikTok developer app, and on iPhone TikTok's Share Kit: until then an installed TikTok takes the video from the share sheet); whether route tracking ever asks for Always (built and tested since round 10, off as Android's background recording; one constant, `IosTrackingPlan.ASK_ALWAYS`, turns it on, and App Review then needs background-location notes for Always).

## Where the iPhone still differs from Android

After the two parity rounds of 19 Nov 2026 the iPhone is built to look and behave as Android does wherever iOS allows it. What still differs:

- **Forced by iOS or Apple's rules:**
  - Back at the bottom (Trips) does nothing: Android's system Back leaves the app there, and an iPhone app doesn't close itself.
  - The status bar's light or dark icons follow the window's light or dark style (SwiftUI's root controller asks the app for no style of its own), so on Trail and in the dark studios iOS's own sheets, pickers, alerts and the keyboard are dark too, where Android draws its system UI in the phone's own mode. In landscape an iPhone hides the status bar.
  - Sideways, the screens keep clear of the Dynamic Island's side (iOS's safe area; Android's camera hole is a dot its screens draw over). Android has no landscape layouts: both phones stretch the same screens to the width, so a few (the trip video's editor) leave little room for their preview on either.
  - The share sheet gives the subject to the apps that take one (Mail); it has no chooser title (Android's createChooser title).
  - Print: iOS's print dialog takes the printer's paper nearest the page's own size and may scale the page into the paper's printable area; Android's print job asks for the media size itself.
  - A place opens Google Maps' universal link (the Google Maps app when the phone has it, else its web page) where Android hands a geo: link to the phone's maps app; the link can't carry the pin's label without a place id.
  - After iOS closes the app in the background, the screens, the tabs' pages, the trip's tab and day and the note being written come back; the rest of each screen's remembered state (a form's fields, a list's scroll), which Android's saved state also brings back, starts afresh. A photo just taken, dictation and the photo library opening at the start don't run again (Android's saved flags keep them from running twice).
  - Apple's own sheets (share, print, pickers, alerts, permission questions) are drawn by iOS in San Francisco.
  - A saved clip in the trip's Media tab plays in Android's own full-screen player inside the app (once, with its controls), where Android hands the file to the phone's video app: iOS has no other app to hand a file to. Every player's picture is AVPlayer's under Android's controls, and its sound plays with the silent switch on (the audio session's playback category), as Android's media sound ignores the ringer.
  - A note's video is written again by AVFoundation (AVAssetReader and AVAssetWriter) where Android uses Media3's Transformer: the same settings, another encoder, so a file's size differs a little. iOS stops the video encoder while the app is away from the screen: the work goes on under a background task, and when the encoder was stopped it starts again from the beginning once the app is back (three tries), where Android goes on in the background.
  - The route's "Recording your route · <Level>" is a Live Activity on the Lock Screen and in the Dynamic Island (iPhone has no ongoing notification), shown when notifications are allowed (as Android's notification needs them) and Live Activities are on for the app. A Live Activity starts only while the app is on screen, so a recording that starts in the background shows its card the next time the app is opened; iOS ends a Live Activity after eight hours, and the app shows it again on its next return. Stop is on the card from iOS 17 (an App Intent); on iOS 16.1 to 16.x a Live Activity can't have a button, so a tap opens Me › Route tracking, where Stop for today is; before iOS 16.1 there is none.
  - The export and Copy from a link go on away from the screen through iOS 26's continued processing (the system shows their name and progress and the traveller can stop them there); before iOS 26 an export pauses away from the screen and goes on when the app is back, and a copy has the background task's half minute. Whether the video encoder keeps writing under a continued task is only known on a real iPhone on iOS 26; if it doesn't, the export's parts go on as before iOS 26.
  - A photo taken in the app keeps the camera's own metadata (time, exposure, orientation), but UIImagePickerController hands over no GPS with it, so the photo file itself carries no place (Android's camera app writes one when its own location setting is on).
  - Google sign-in (round 23) is Google's web page in iOS's sign-in sheet, after iOS's own question ""BoringTravel" wants to use "google.com" to sign in" with Continue, where Android shows its Credential Manager sheet and then Google's Drive consent; on iPhone the account and Drive are asked on one page (unticking Drive there is Android's no to Drive). A build without Google's iPhone client can't connect Drive at all (Android's build without sign-in still can, through Play services), so Welcome's Continue only goes on to Trips (59).
  - Sign in with Apple is on iPhone only (App Review 4.8), beside Google on Welcome and Me; it signs in without Drive (Drive is connected in Backup & sync, as after Android's no to Drive). Delete account with Apple asks Apple's sheet once more: Apple's token can only be revoked with a fresh code (5.1.1(v)).
  - Disconnect gives the whole grant back to Google (Google's token revoke has no per-scope form), so Google asks for the account again at the next Connect; Android revokes Drive's access through Play services. Sign out keeps Google's grant, as on Android.
  - The daily backup is a BGProcessingTask: iOS picks the moment after 22:00 (often while charging overnight) and may skip days, so a missed one also runs when the app is opened after 22:00; WorkManager runs it about once a day from 22:00. "Only on Wi‑Fi" is a network that isn't costly or constrained (Low Data Mode), and "the battery isn't low" is Low Power Mode off: iOS tells an app neither Wi‑Fi itself nor the battery's level in the background. Back up now goes on away from the screen for the time iOS gives (UIApplication's background task, about half a minute), where Android's work goes on to the end; the next backup carries on from there (photos already in Drive aren't sent again).
  - A Google Doc read through Drive (Copy from a link) is taken whole, up to 10 MB, where Android streams it.
  - The community (round 24) has a way to reach the developer, "Something wrong? Write to …", under its sign-in card and in the Report sheet (Apple 1.2; Android has none), and signs in with the Sign in with Apple account too (4.8). A build without the community's Firebase ids says "…needs the app from the App Store." where Android names Google Play and GitHub.
  - The community speaks to Firestore and Firebase Auth through their REST APIs where Android uses the Firebase SDK (no SDK for this app's Kotlin on iPhone): the same documents, rules and words. What the SDK keeps on the phone is kept by hand: offline a list shows the page last read for the same list (the SDK can also answer another list from documents it has, so Android may show a little more offline), and a like, report, block or follow made offline goes out with the next write, a return to the app, a pull to refresh or a sign-in (the SDK sends it as soon as the connection is back while the app runs).
  - A post's web link opens the app only once the site has the owner's apple-app-site-association file (Android's App Links verify the same way); until then it opens the website, and `boringtravel://post/#<id>` opens the post.
- **Choices left to the owner:**
  - Roboto's SemiBold (600: titles, rows, buttons): Google's roboto-2 release has no 600, so Medium stands in, as Android 10 and 11 draw it from their static Roboto; Android 12 and later draw a true 600 from their variable Roboto (roboto-3-classic, under the OFL), which would match them exactly if bundled instead.
- **Not a difference, but worth knowing:** java.time writes Latin digits in every language, and so does the iPhone; Android's "%02d" clock times and String.format numbers use a language's own digits (Arabic, Persian), which the iPhone's numbers follow (NSNumberFormatter's zero digit) but its clock times don't.
- **Not a difference, but worth knowing:** the calendar's guess of a trip's zone from its countries reads IANA's zone.tab (release 2026f, bundled as `files/tz/zone.tab`) under ICU's older names ("Calcutta", "Saigon", "Kiev"), where Android asks the ICU inside the phone's system. They agree today; a later tz release on one phone (a country's new zone) can differ until the bundled table is brought up to date. zone.tab, not zone1970.tab: zone1970.tab files several countries under a neighbour's zone (Denmark under Berlin, the Netherlands under Brussels), which ICU doesn't.
- **Android's own inconsistencies not copied:** Android's Navigation sometimes brings back a page left straight over Trips (Notes outside trips) after a tab switch, depending on which tab was left first; the iPhone never does, as Android does in practice.
- **Android's own ways copied as they are:** a cover photo picked on New trip or Edit trip and then left with Back (no Save) stays in the trip's photos, as on Android (Save removes the ones not kept). The community's photo viewer shows the theme's light chip colour around a photo that doesn't fill the screen, not black: Android's DrivePicture paints its loading colour over the viewer's black (`ui/community/CommunityParts.kt`, `MediaViewer.kt`); a fix there would change both phones.
- **Not a difference, but worth knowing:** a username suggested from a name takes the accents off Latin and Greek letters by the engine's table (`art/SceneText.kt` `decomposed`) where Android uses Java's Normalizer; a letter outside the table (Vietnamese "ạ") is left out on iPhone where Android keeps its base letter. Only the first suggestion is affected; the rules are the same.
- **Still to build (not differences of the platform):** every line still open above (the Counts table); most wait on the owner's accounts (the community's posting and profiles, travel together, sharing into the app, Pro; the account and Drive are built since round 23, and the community's reading, hearts, Report and Block since round 24, and wait only on the owner's clients and Firebase app for a real account). Of what the second parity round touched, 492 (a pick that returns while the note reloads) stays open, and 500 (Drive's upload note) is in for backups, its shared trips waiting on round 26.
