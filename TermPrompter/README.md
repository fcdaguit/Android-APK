# TermPrompter

Offline, voice-following teleprompter for Android 10+. Black screen, neon-green terminal text, no ads, no login, no network permission.

## Build the APK

**Option A – GitHub (no setup):** create a repo, upload this folder, open *Actions → Build APK → Run workflow*. Download `TermPrompter-apk` (contains `app-release.apk`).

**Option B – Android Studio:** run `bash fetch-models.sh` (bundles the English model), open the folder, *Build → Build APK(s)*.

The release APK is signed with the debug key so it installs directly. Use your own keystore before distributing it.

## Languages and offline models

| Language | Model | Notes |
|---|---|---|
| English | `vosk-model-small-en-us-0.15` (bundled by the build) | ~40 MB |
| Filipino | Import a Vosk Filipino model `.zip` | Check the current list at alphacephei.com/vosk/models; some Filipino models are large |
| Bisaya (Cebuano) | Import a Bisaya model `.zip` if you have one | I know of no ready-made Vosk Cebuano model. Without one, Bisaya uses the **Filipino** model |

Import on the phone: **Lang → Import voice model (.zip) →** pick the language slot → pick the zip. This is a one-time local file copy; nothing is downloaded by the app.

**Bisaya mode** adds Cebuano-aware matching: o/u, e/i, c/k, ph/f and doubled letters are treated as equal, and word comparison tolerates more spelling drift. Expect lower accuracy on the Filipino model; raise the tolerance slider to 4–5 if it pauses too often.

## Using it

- **Script:** paste/edit, open a `.txt`, save/load locally. `[text in brackets]` is shown but never expected to be spoken.
- **Tap** the text to play/pause. **Long-press** or the ⋮ button opens the settings panel. Drag to scroll manually at any time.
- **Voice mode:** the current phrase is highlighted and the screen moves only after that phrase is read. If several words in a row don't match the script, it turns amber (**AD-LIB — waiting**) and stops. It resumes when you read a few script words in a row again, and moves on after you complete that phrase.
- **Tolerance 1–5:** how many skipped or paraphrased words count as "read", how fuzzy word matching is, and how many off-script words trigger ad-lib.
- **Timer mode:** fixed-speed scroll for when you don't want the microphone.

## Known limits

- Not yet compiled or tested on a device. Expect small fixes on first build.
- Numerals in the script (e.g. "2026") won't match spoken words; write them out ("twenty twenty-six").
- Mixed-language (Taglish / Bisaya-English) speech works best when the script is written the way you'll actually say it.
