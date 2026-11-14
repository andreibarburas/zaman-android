# Changelog

## 1.5.0 (unreleased)

### Added
- **Vinci integration**: Zaman now reads the same Nextcloud account's address book directly over CardDAV (`CardDavContactsClient` — PROPFIND discovery + addressbook-query REPORT, minimal vCard reader for UID/FN) instead of needing Vinci's own app code. An event can be "linked" to a contact from the event-edit screen; tapping the linked name opens `vinci://contact/{uid}` the same way Zaman's own `zaman://event/{href}` deep link works in reverse. The link is a Zaman-local annotation (`EventEntity.linkedContactUid/Name`) — it's never written back to the ICS as an ATTENDEE, so it can't corrupt an event another app or person wrote.
- **Periodic background sync**: `CalDavSyncWorker.enqueuePeriodic()` schedules a real `PeriodicWorkRequest` (15-minute floor, network-constrained) in addition to the existing manual "Sync now" one-shot — armed on login, re-armed harmlessly on every app launch (`ExistingPeriodicWorkPolicy.KEEP`), cancelled on sign-out. Previously Zaman only ever synced when a human opened Settings and tapped the button.
- **Event reminders**: `ICalParser` now reads a VEVENT's first `VALARM` (relative "before start" triggers only — `-PT15M`, `-PT1H`, `-P1D`, etc.) into `EventEntity.reminderMinutesBeforeStart`; `ICalWriter` writes a matching `VALARM` back when a reminder is set from Zaman, so it round-trips through Nextcloud instead of being a local-only effect. Actual delivery is a `ReminderScheduler` (`AlarmManager.setAndAllowWhileIdle` — inexact, so no SCHEDULE_EXACT_ALARM permission prompt) + `ReminderReceiver` that posts a real notification; a `BootReceiver` + `RescheduleRemindersWorker` re-arm every future reminder after a reboot, since inexact alarms don't survive one. `POST_NOTIFICATIONS` is requested at runtime on API 33+.
- **Recurring-event create/edit**: EventEditScreen has a Repeat picker (None/Daily/Weekly/Monthly/Yearly) wired through to RRULE on save. An RRULE Nextcloud wrote that doesn't match one of those (an INTERVAL, BYDAY list, COUNT/UNTIL, …) shows as "Custom (from Nextcloud)" and is preserved as-is rather than being silently flattened to the nearest simple option. **Known limitation**: there's still no "edit this occurrence vs. the whole series" — editing a recurring event edits that one VEVENT only, same as before this release.
- **Attendees** (read-only): `ATTENDEE` lines are parsed (CN param or bare `mailto:` address) and shown on the event-edit screen. Zaman doesn't write invites back — this is display-only, consistent with the suite's "don't risk corrupting data you can't fully round-trip" posture.

## 1.4.0 (unreleased)

### Added
- **Local-only mode** (Settings → Data): when enabled, Zaman never talks to Nextcloud — discovery, sync, and all event create/edit/delete calls no-op on the network side and only touch the local Room cache. Matches the suite-wide v2 milestone already shipped for Vinci/Tavla/Koto.
- **Export / Import** (Settings → Data): "Export data" writes every cached calendar and event to a JSON file via the system's document picker (SAF `CreateDocument`); "Import data" reads one back (`OpenDocument`) and upserts it, so a backup from local-only mode — or a transfer to a new device — doesn't depend on Nextcloud being reachable.
- **In-app search**: a new search screen (reachable from Today's top bar) queries summary text across every enabled calendar, including Koto tasks and Tavla cards, with the same KOTO/TAVLA badges as the agenda.
- **OLED theme**: Settings → Display → Theme now has a 4th option alongside System/Light/Dark — a true-black scheme for OLED panels, matching the rest of the suite.
- **Today top app bar**: brand wordmark, a search shortcut, a one-tap Light/Dark toggle, and a settings shortcut — the mockup had all four and the shipped screen had none.
- **Deep link**: `zaman://event/{eventHref}` now resolves to the real event-edit screen (manifest intent-filter + a `navDeepLink` on the route), so Vinci/Tavla/Koto can link straight into a specific Zaman event instead of only being able to open the app to Today.

### Fixed
- `glassFill()`/`glassBorder()`/`inkMuted()` defaulted to `isSystemInDarkTheme()`, which only reflects the OS setting — so a user who explicitly chose Light while their phone's system theme was Dark (or vice versa) got glass-card tinting from the wrong palette. They now read the actually-applied theme via a new `LocalZamanIsDark` composition local that `ZamanTheme` sets.

## 1.3.2 (unreleased)

### Fixed
- **Subscription calendars never appeared anywhere in the app — not just "not synced", not discovered at all.** `discoverCalendars()`'s PROPFIND parser only treated a collection as a calendar if its `<d:resourcetype>` contained `<cal:calendar/>`. Nextcloud's own ICS subscriptions (added via Calendar app → "+ New subscription") report a *different* resourcetype — `<d:collection/><cs:subscribed/>`, with no `<cal:calendar/>` element — so every subscription was silently dropped before `isSubscription` was even evaluated, before it ever reached Room. The 1.3.0 fix only addressed syncing a subscription calendar that was already in the database; this fixes the actual root cause, so subscriptions are discovered and show up in Calendars, Today, and Month like any other source.

### Changed
- Today screen rebuilt to match the original design mockup instead of a generic Material3 list: a horizontal week-strip day picker above the agenda, a time-rail with a connecting line per entry, a live "now" indicator row that places itself among the day's events, and a colored left-edge stripe per card (tinted by source app for Koto/Tavla items, the suite accent for native CalDAV events). Tapping a day in the week strip switches the agenda to that day instead of always showing today.
- Month screen: cells are now tappable, and a selected-day agenda list (date header + item count + stripe-coded rows) appears below the grid, matching the mockup's `.sel` + `.list` section — previously the grid was the whole screen and tapping a day did nothing.
- Calendars screen: rows restyled with per-calendar color swatches (including Koto/Tavla accents for cross-app sources), a dashed "Add calendar or subscription" row, and an info-tinted hint card, replacing the plain Material3 rows. Sections are now Nextcloud / Subscribed in Nextcloud / Tasks with due dates, so subscriptions and cross-app sources are visually distinct instead of all mixed together.
- Bottom tab bar replaced the stock Material3 `NavigationBar` with the mockup's translucent glass bar (hairline top border, tinted active tab), matching the rest of the suite's look instead of default Android chrome.

## 1.3.0 (unreleased)

### Added
- Settings → Display section, matching the rest of the suite's own apps (e.g. Vinci): Theme (Light/Dark/System), Text size (Small/Default/Large/Extra Large), and a toggle for the custom DM Serif + Inter Tight font pairing vs. system fonts. Stored in its own DataStore (`zaman_display`), separate from the account store, so signing out never resets these. Text size is applied as a `LocalDensity` font-scale override so it affects every screen uniformly; the font toggle is read by `ZamanType`'s styles directly, so it takes effect without threading a parameter through every screen.

### Fixed
- **Subscription calendars not syncing**: ICS subscription calendars were discovered but their events never appeared. `syncOne()` tried `sync-collection` REPORT first (unsupported by subscription calendars), failed silently, and never fell back to `fetchEventsFullRange()`. Subscription calendars now skip sync-collection entirely and always do a full fetch; regular calendars also fall back to full fetch on any sync-collection error.
- **Tavla entries showing on wrong dates**: The Room query for `observeInRange` treated events with `endEpochMillis = NULL` (all Tavla cards, Koto tasks) as spanning from their start time to infinity — so every past Tavla card appeared on every day's agenda. Point-in-time events (null end) now match only when their start falls within the requested range.
- **Settings screen not scrollable / missing items**: Settings was a plain `Column` that got cut off on smaller screens. Now scrollable, with layout and composable structure matching Tavla's Settings (section labels, toggle rows with icons, chip groups). Added "Buy me a coffee" link.
- **Font size gaps in type scale**: `bodySmall` was 11sp and `bodyMedium` didn't exist, leaving a 4sp gap between bodyLarge (15sp) and bodySmall. Added `bodyMedium` at 13sp and bumped `bodySmall` to 12sp so the scale reads 15 → 13 → 12.

## 1.2.1 (unreleased)

### Fixed
- `NetworkOnMainThreadException` on every real CalDAV/Deck/Login-Flow request: the network clients never switched off the caller's dispatcher (viewModelScope defaults to Main, and OkHttp's `.execute()` blocks synchronously). Every network call in `LoginFlowV2Auth`, `CalDavClient` and `DeckApiClient` now runs inside `withContext(Dispatchers.IO)`.
- Onboarding's "Get started" dropped the user into the Settings tab instead of a dedicated connect step, so it looked like nothing happened. Replaced with a proper first-run connect screen (`ConnectScreen`, modeled on Tavla's `LoginScreen`) that the root nav graph routes to directly — matches the rest of the suite's onboarding → login → main pattern instead of hiding the connect flow inside Settings.

## 1.2.0 (unreleased)

### Added
- First-run onboarding screen, matching the rest of the suite's pattern (skipped automatically for anyone already connected or who's seen it before).
- Settings: a real "Sync now" action, and a Support section (GitHub, privacy policy, Reddit, barburas.com) with the app version read from `BuildConfig` instead of a hand-maintained string.

### Fixed
- Replaced the adaptive-icon XML (`mipmap-anydpi-v26`) with plain per-density PNGs — the background layer referenced `@mipmap/ic_launcher`, which on API 26+ resolves back to that same adaptive-icon XML, a self-reference that left the launcher icon blank. Matches how Tavla ships its icon.
- `network_security_config.xml` only trusted system CAs — a self-hosted Nextcloud behind a self-signed or custom CA (the norm for this suite's audience) failed TLS and surfaced as a flat "could not reach that server". Now trusts user-installed certs too.
- Login errors are now classified (unresolvable host, HTTPS required, certificate/trust problem, timeout, connection refused) instead of a single generic message, and a network hiccup *during* poll no longer gets silently swallowed — it's surfaced if the whole poll window times out.
- Signing out no longer wipes the "onboarding seen" flag, so a returning user who reconnects doesn't see the welcome screen again.

## 1.1.0 (unreleased)

### Added
- Koto integration: Nextcloud Tasks lists are discovered as VTODO-only CalDAV collections and synced read-only into the agenda alongside events.
- Tavla integration: `DeckApiClient` reads Nextcloud Deck's OCS REST API directly for cards with due dates, surfaced as a read-only pseudo-calendar.
- Event create/edit/delete screen, reachable from the Today FAB and agenda rows, writing real VEVENTs via `ICalWriter` with `If-Match`/`If-None-Match` concurrency.
- Month view now colors each day's dots from the event's real owning-calendar color instead of a placeholder.
- Optional biometric lock (Settings toggle), gating the app behind `BiometricPrompt` and re-locking on background.
- Adaptive icon (`mipmap-anydpi-v26`) replacing the flat-PNG-only launcher icon.

### Fixed
- CalDAV `<d:href>` values (server-relative paths, per RFC 4918/how Nextcloud actually responds) are now resolved to absolute URLs before being requested or written to — previously stored and reused as-is, which would have thrown on every real sync/write.
- `AppModule`'s OkHttp auth interceptor was calling a `suspend fun` from a synchronous interceptor body (wouldn't compile); bridged with `runBlocking` over a DataStore-only read.
- `roundIcon` in the manifest pointed at `ic_launcher` instead of `ic_launcher_round`.

## 1.0.0 (unreleased)

### Added
- Initial project scaffold: direct CalDAV client (no DAVx5/ICSx), Login Flow v2 auth, iCalendar + RRULE parsing, Room cache, WorkManager sync.
- Today, Month, Calendars and Settings screens in Compose, built on the BarburasLab design system's `accent-zaman` tokens.
- Per-calendar enable/disable toggles, including Nextcloud ICS subscriptions.
- Settings screen wired end to end to Login Flow v2: server entry, browser approval, polling, sign-in/sign-out state, automatic calendar discovery + sync on success.
- Real bundled DM Serif Display + Inter Tight fonts (static instances from Google's variable sources, OFL-licensed) in place of the downloadable-font placeholder.
