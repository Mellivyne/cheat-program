# v1.1 verification

The release uses one Fabric JAR per exact Minecraft version: 1.20.1, 1.20.4, 1.21.4, 1.21.10, 1.21.11, 26.1, 26.1.1, 26.1.2 and 26.2. Java 21 is required for the 1.20.x and 1.21.x artifacts; the 26.x artifacts require Java 25.

## Isolated runtime checks

Minecraft 1.20.1, 1.20.4 and 1.21.4 were checked in separate development worlds on the OpenGL backend. The checks cover:

- Exact feature registration and absence of excluded module implementations.
- Native glass shaders, player/model previews and block geometry.
- Building dimensions, material totals excluding air, layer indexing and per-layer quantities.
- Building-plan validation, legal native block states, invalid dimensions, unknown blocks and out-of-bounds fills.
- First-person visit camera movement, flight and isolation from the live player's position.
- Image payloads in compatible Chat Completions and Responses request formats, removal of local image paths, unsupported-model errors and cancellation.
- Local synthetic image-to-particle requests, native particle data, portable CPS1 round trips, preview emission and rendered particle pixels outside the player body, private-data rejection and preservation of the existing particle project.

The base-version checks also cover projection key rebinding, multi-screen interaction routing, curved-surface picking and per-line lyric placement. These projection checks use synthetic capture sessions and local input queues.

## Release checks

Each artifact is built separately with clean Java outputs. Baseline cleanup preserves other target outputs; subset builds retain valid verification records for untouched artifacts. Packaging verifies the project version, exact Minecraft and Java requirements, expected mixin classes, the declared 47 module implementations, absence of optional test fixtures, and SHA-256 checksums. Source packaging uses an explicit allowlist that excludes runtime accounts, credentials, profiles, logs, test worlds and screenshots.

## Limits of these checks

Synthetic local model responses verify request and data handling; real paid AI-provider generation has not been tested with a user's credentials. Complete gameplay, every external application's Windows input behavior, Vulkan and exhaustive multiplayer-server behavior have not been retested. Optional-mod and development-login warnings can appear in isolated clients. The test client can also log vanilla chunk-scheduling warnings while shutting down; its test worlds are separate from normal installations.


## Local follow-up: model selection, desktop audio and ore backfill

AI building and particle workspaces use independent refreshable model catalogues and searchable selection dialogs. A local HTTP fixture verifies authorization, pagination, selectable models and catalogue invalidation. The isolated 1.21.4 client rendered the building form and model dialog and completed its preview checks. Real paid provider calls were not made.

The native desktop-audio checks verify same-application UI-child/audio-sibling capture, inactive sessions before playback, nonzero PCM, route restoration, helper failure, heartbeat expiry and preservation of user endpoint changes. System default endpoints remained unchanged. Non-migrating application streams are explicitly reported as needing playback restart rather than falsely reporting isolation. These fixtures do not certify every desktop application.

Ore backfill now derives heading from accumulated movement. The old per-tick 0.08-block threshold could suppress all placement while crouching or walking slowly. The standalone BackfillTrailCheck covers slow travel, corners, stopping and resetting after corrections. Existing reach, collision, inventory, active mining, freecam and server confirmation checks remain in effect. Actual placement on the user's server has not been retested.

These results describe the checks completed for the v1.1 release.

## Local audio lease recovery follow-up

A live helper that stops reporting now expires after eight seconds (fifteen seconds for its initial response). The client releases and restores that lease before attempting another connection, instead of withholding spatial replay indefinitely. Environment refresh preserves the helper's specific waiting-for-playback-restart status and diagnostic details. An unsafe or missing playback environment revokes the lease and gates replay. Report publication, failures and shutdown all check the current lease under the same lock, preventing an old reader from overwriting a replacement's state.

An isolated Java harness compiled the actual production source with minimal dependencies and exercised ten behavioral cases: initial response grace and expiry, stale reports, healthy leases, readiness gating, specific status/detail preservation, three unsafe environments, and obsolete readers publishing either data or errors after replacement. A controlled Process fixture supplies pipe state without launching the native helper or changing device settings. The earlier native fixture results remain the evidence for endpoint restoration; this follow-up has not been tested against real third-party desktop audio or a deliberately stalled native helper. Non-migrating streams still need the source application to reopen playback on the selected output.

## Local provider address presets

AI building and AI particle forms expose OpenAI, DeepSeek and custom address controls above the endpoint field. Existing AI control/player controls already provide those presets. Selecting an official preset updates the address and default model, invalidates the old model catalogue, clears unsaved key input and cancels the workspace's previous generation. Cross-provider saved-key clearing uses the existing endpoint-bound credential store. Preset selection does not refresh models or make a provider request; users explicitly refresh after supplying their key.

The isolated 1.21.4 runtime rendered both new preset rows without overlap. All source variants use the shared picker. This UI change has not made a real paid-provider request.

## Cross-version rendering recovery

The migrated immediate GUI canvas now scopes and restores its caller's render state instead of leaving custom shader/VAO/projection settings active. Modern state snapshots also restore the original native projection buffer identity. After a deferred native GUI submission finishes, the bridge explicitly binds Minecraft's actual GUI target before continuing immediate glass drawing; this respects the world-panel target override. Previously, native pass completion could leave the window framebuffer active, and the final game-image blit would overwrite glass inventory content drawn there.

An isolated 1.21.11 client established that the inventory key opened the real creative inventory even when its contents appeared absent. With glass inventory disabled for the input baseline, the native inventory rendered, and native attack/use routines removed and placed blocks with confirmation from the integrated server. Hand-held items and nearby animals were present in the diagnostic screenshot. This does not verify all native mouse input behavior or all supported versions. Final GUI-target and projection-identity changes are compile-checked; the user requested to perform the remaining gameplay retest. No claim of exhaustive cross-version gameplay verification is made.

## Native model transform isolation follow-up

Code inspection of the 1.21.11 native GUI renderer found a second, distinct state leak. The GUI bridge re-established its immediate canvas transform after every flush, even when called outside an R2D scope. That left the global model-view at Z=-11000. Native GUI item-atlas preparation uses a projection spanning -1000 to +1000 and native model draws consume the global model-view; inheriting the canvas translation clips those models. An empty native item-atlas entry can then remain cached. The leaked transform also crossed the boundary to subsequent native model passes.

Modern bridges now establish an identity model-view only while submitting native GUI work and restore the caller afterward. Canvas coordinates and the actual GUI render target are established explicitly inside R2D.begin and restored by its matching end. Inventory glass now preserves the full rendering snapshot around its entire operation, including work before R2D.begin. Modern snapshots additionally preserve native color/depth output overrides and native render-type scissors. These changes cover the shared 1.21.10/1.21.11 and 26.x source variants.

All nine normal Fabric builds and artifact checks passed after this follow-up. The final changes were reviewed against native source and compiled only; no game was launched for this follow-up, as requested. The earlier input baseline remains useful but is not a runtime verification of this final patch. Gameplay and visual confirmation are pending the user's retest.

## AI text input and building task recovery

The user confirmed native chat could enter Chinese, while the AI building and AI particle inputs could not show composition/candidates or enter Chinese. Native 26.x EditBox notifies Minecraft.onTextInputFocusChange and handles PreeditEvent; the custom widget layer previously did neither. The 26.x compatibility screen and GUI base now notify the native text input manager immediately after focus-changing mouse/key callbacks, receive composition events without inserting them as committed text, and publish Minecraft's own IMEPreeditOverlay after the custom canvas has completed. Candidate positioning uses the scrolled caret transformed from canvas pixels to GUI coordinates. Closing a custom screen clears the overlay and ends text-input mode; existing subclasses that skipped their superclass removal now perform that cleanup. The committed CharacterEvent-to-Unicode text path remains shared, and native chat has not been replaced. Earlier Minecraft versions do not acquire dependencies on the 26.x input API.

A CPU-only harness executed the actual new focus/anchor/preedit method bodies with fake native notifications for both 26.x source variants. It checked starting input, stable frames, composition display, scaled candidate placement, clearing on commit, field transfer, removal, stale events, inactive screens, returning and blur. This tests bridge behavior, not a real Windows IME or rendered candidate window. TextFieldUnicodeCheck compiled the production TextField and UnicodeText and passed Chinese commits, supplementary ideographs, emoji deletion, selected replacement, bounded input and viewport surrogate handling, and single change notification. Its Minecraft clipboard dependency was stubbed and no game was launched.

Building jobs now each own their tick-submission gate. Previously, a queued operation abandoned by a stopped integrated server could leave a single shared gate permanently locked for later worlds. Rejected server submissions and failed steps now release their UI state. Lifecycle cleanup also preserves title-screen library file loading/deletion when no world/session remains. The standalone BuildingJobQueueCheck checks abandoned-server isolation, coalescing, stale completions, executor rejection, action failures and reentrant submission. The changes cover all building source variants. Actual import/placement in the user's world and native IME behavior await user retesting; no paid AI requests were made.

The user's clarified building symptom was missing world selection boxes and no visible F6 feedback. Modern world overlay submission now explicitly binds Minecraft's main scene target and the captured world projection after native level rendering, restoring the complete caller snapshot afterward. Native GL pass completion can release the framebuffer to the window; immediate world markers drawn there are replaced by the final scene blit. This also keeps building placement previews on the intended scene target. The selected 26.2 target uses GameRenderer.mainRenderTarget, while earlier modern profiles use Minecraft.getMainRenderTarget.

F6/F7/F8 selection now responds to press/release callbacks in addition to the held-key fallback. A short tap between 20 Hz game ticks is no longer missed. Marking sends a coordinate-bearing notification, or an aim hint when no valid block can be selected, rather than silently reporting a null mark as successful. A CPU harness executed the actual selectionKey bodies from all five source variants and checked short taps, repeat/held suppression, release, all three shortcuts and inactive state. World marker visibility and Windows IME candidates remain compile/code-reviewed until the user's gameplay retest.

## Album scene refinement and artwork preloading

There are eight retained layouts: coverflow, ring and wall, plus the new floor, constellation, spiral staircase, music sphere and immersive gallery. Record-crate and album-book implementations, their special geometry and their language entries have been removed. An old saved preference for either removed layout fails the normal whitelist and falls back to coverflow. The gallery retains two-sided album placement and its drag/focus/dolly controls. Opaque architecture, frames and light fixtures are replaced by perspective liquid-glass sheets that sample the same Backdrop textures and Theme.sheetGlass material parameters as the surrounding interface. Transparent glass does not block cover picking.

Gallery upward/leftward dragging advances through later tracks and its viewport window can reach the end of the library. Prefetch indices follow the viewport independently of the playing/selected track. All layout screens and native renderers use consistent visible-workset preloading. Public artwork still uses a bounded GPU cache; up to 112 visible/preloaded textures are protected, with sixteen slots reserved for other music controls. Far-away artwork is progressively warmed on disk by a separate worker rather than uploaded into the GPU working set. The cursor covers the full library, survives changes in browse focus, defers rejected submissions, and makes up to two delayed passes over temporary failures. Original platform host validation and empty request headers remain in use. Existing disk age and capacity limits still apply.

AlbumSceneGeometryCheck covers the five spatial layouts, empty/small/100,000-track libraries, bounded/finite transforms, removed-layout rejection, selected last-track reachability, drag versus tap, sphere near-plane bounds, gallery focus at zoom limits, upward/leftward browsing to the final track, and prefetch coverage of visible covers. A separate headless check executed the actual renderer's hitSpace and triangle-depth bodies to confirm last-track picking and visible covers after browsing. ArtworkPreloadCheck verifies full coverage with continually moving focus for up to 45,000 tracks, retries and resets. No-network/no-GL clipboard-free stubs for the four cache variants also checked 320 disk entries, recovery from transient failures, zero background GPU uploads, protection of 100 visible covers under 400 unrelated requests, the GPU limit and rejected nonplatform hosts.

The actual gallery glass vertex/fragment shaders compiled and linked in a separate hidden native OpenGL context. Rendering the production gallery matrices against a synthetic background verified framebuffer drawing, backdrop sampling and transparent empty areas; the resulting image was visually inspected. The optional reproducible fixture is GalleryGlassMaterialCheck. This did not launch Minecraft or use account data. Native integration and real service artwork timing still await the user's gameplay retest. No paid AI requests were made for these checks.

## v1.1-r2: project link and licensing

All six control-panel source adapters draw the same fixed repository badge, including the embedded 3D panel. An isolated check compiled the production badge and encoded address helper with minimal GUI stubs. It checked the exact URI and full label, sixteen font-width/scale combinations within the header bounds, explicit-click-only opening and overlay click blocking. The encoded helper class does not contain the plaintext HTTPS URL. This is an edit deterrent, not a guarantee against modifying publicly available source or compiled code. Minecraft was not launched for this revision.

Release packaging checks the PolyForm Noncommercial metadata and byte-for-byte copies of LICENSE, LICENSING.md, LICENSE-LEGACY-MIT and THIRD_PARTY_NOTICES.md in every supported JAR. The official PolyForm text and the original MIT notice are preserved. The original v1.1 release and its MIT grants are not rewritten. Source archives continue to use the explicit privacy allowlist.
