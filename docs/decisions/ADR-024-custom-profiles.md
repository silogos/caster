# ADR-024: Profiles hold every cast setting; the user adds their own

- **Status:** accepted (2026-11-11, product owner). Built and unit-tested; **not yet walked on the tablet** (see Consequences). Issue #165.

## Context

Until now the phone had one settings document:

- Four presets (Cool, Balanced, Performance, Sharp) on Home. Picking one overwrote resolution, frame rate, bitrate and most of the Advanced values.
- A *Custom* tab with dials. Any change there left the preset, and the result was an unnamed "Custom" state. There was one of it.
- An *Advanced* page behind a gear: encoder, codec, H.264 profile, what to give up under network pressure, encoder fps limit, bitrate mode, automatic quality, low-latency game audio.

What went wrong with that (found 2026-11-11, [latency-measurement.md](../development/latency-measurement.md)):

- The tablet cast with a setup that had started as Sharp and was changed to 60 fps. It showed as "Custom" and still carried Sharp's 8–12 Mbps window and "Keep resolution". Nothing on screen said so, and that combination is what gave up frame rate and starved the audio when the uplink ran short.
- Tapping a preset silently reset Advanced: a user who had set "Keep frame rate" lost it the next time they tapped Sharp.
- A user who wants "sharp for streaming" and "smooth for fast games" had to re-enter the values each time.
- *Bitrate mode: VBR* did nothing: libwebrtc fixes the hardware encoder to CBR.

The product owner's direction (2026-11-11): add custom profiles; with every setting inside a profile there is no Advanced page left; remove Bitrate mode; put the game-audio transport in the profile too, so Home's Audio card is only its switches.

## Decision

**A profile is everything that shapes a cast.** One profile is selected, and the next cast runs on it.

| | In a profile | Not in a profile |
|---|---|---|
| Picture | Resolution, frame rate, bitrate window | |
| Behaviour | Under network pressure, Automatic quality | |
| Encoder | Encoder (hardware / software), video codec, H.264 profile, encoder fps limit | |
| Audio | Game-audio transport (Low latency / Standard) | Game audio on/off, Microphone on/off, which microphone |

- **Four built-in profiles**, fixed: they cannot be edited, renamed or deleted. Their picture and pressure strategy are the presets' as before; the rest is the shipped default (hardware, H.264, automatic quality on, standard audio).
- **The user's own profiles**: a name (up to 20 characters) over its own values. Add, edit, duplicate, delete. Up to 12. A new profile is always a copy, of the values on screen or of any profile, so it never starts from values nobody chose.
- **Edits save at once** and apply to the next cast, like every setting before.
- **Deleting the selected profile** selects Balanced, the app's default.
- **The Custom tab, the Advanced page and the gear are gone.** The read-only "Encoder in use" moves to Home's STATUS zone, shown while casting.
- **Bitrate mode is removed** from the model, the stored document and the UI.
- **During a cast**, picking a profile steers the running cast to its picture (resolution, frame rate, bitrate), as a preset pick did. The stored selection is not changed, and the profile's behaviour and encoder values apply to the next cast.

| Option | Result |
|---|---|
| Keep one document, add "save current as…" | Rejected: the preset-resets-Advanced trap stays, and Advanced stays a second place to look |
| Make the built-in profiles editable too | Rejected: "Sharp" would stop meaning one thing, and auto quality's ladder is built from them |
| Per-profile audio switches | Rejected: whether the mic rides the cast is a choice about the session, and it is toggled live |
| **Profiles own every cast value; built-ins fixed** | **Chosen** |

### Stored settings: v7

`CastSettingsCodec` v7 stores the selection (`builtin:<label>` / `custom:<id>`), the user's profiles, and the audio switches. v1–v6 documents (one flat set of values) still decode and migrate without losing a value:

- a flat set that equals a built-in profile, value for value, selects that profile;
- anything else becomes the user's first profile, **"Custom 1"**, selected.

Two flat fields have no successor and are dropped: `bitrateAuto` (a profile's window is its window) and `bitrateMode`. A stored Cool with the old `vbr` default is still Cool.

This is storage on the phone, not a wire protocol. The desktop is untouched: `session-info.profile` was already free text, and now carries a built-in's label or the user's profile name.

## Consequences

- **A profile is the whole answer to "what will this cast do"**, and each row on Home says in words what it gives up under network pressure.
- **A user on a built-in profile cannot change an encoder or behaviour setting directly**: they add a profile (a copy) first. Automatic quality included: built-ins have it on.
- **The live dials are gone.** During a cast the picture can be steered only by picking a profile. To try other values mid-cast: edit a profile (saved at once), then pick it.
- **Not walked on the tablet yet:** the migration of the tablet's real stored settings, the profile list, edit, add, delete, and a cast on the user's profile.
- **Not done here, deliberately:** the design draft ([`designs/mobile-app.figcraft`](../../designs/mobile-app.figcraft), page "Mobile design v2") shows "Under network pressure" and "Automatic quality" as one field, "When the cast struggles". The app keeps them as two. One field needs a new automatic-quality rule (lower on symptoms, not on the temperature alone; come back by itself; follow the profile's priority) that changes the documented thermal policy ([thermal.md](../architecture/thermal.md)) and has to be measured in a real game cast first. Until then, merging them would take away the one combination that suits a game profile today: follow the network, ignore the heat.
- **Revisit if:** twelve profiles or twenty characters turn out too few, or users expect a built-in profile to be editable in place.
