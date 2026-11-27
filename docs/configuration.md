# Interface and configuration

Open the menu with Right Shift. The final **Configuration** dropdown contains:

- **Interface**: FPS, ping, username, coordinates, server and clock switches; light/dark theme; HUD scale, Y offset, spacing, right margin, padding and opacity. Left-click its switch to hide the entire HUD; right-click the row to edit settings.
- **ClickGUI settings**: panel dragging, menu scale, movement while the menu is open, screen background opacity and panel opacity. Click the row to expand it.

While the menu is open, mouse movement does not turn the player. Hold the arrow keys to look left, right, up or down. Arrow-key looking pauses while editing text, assigning a binding, or when the game loses focus. Closing the menu restores normal mouse look.

The HUD uses one horizontal, right-aligned row of rounded rectangles without shadows. It wraps only if the enabled widgets cannot fit on the screen. Theme changes apply to both the HUD and the menu, including while Interface itself is disabled.

**Visuals > Arraylist** displays enabled feature modules below the Interface HUD. The default **Panel** layout uses a single compact background with right-aligned text; **Rows** and background-free **Text** layouts are also available. Entries fade and slide, smoothly reorder, and reserve/collapse their space separately so text does not get cropped during transitions. **Show modes** adds subdued mode labels and the configured FastPlace rate. Sort by measured width or alphabetically, or disable animations. Scale, right margin, Y offset, spacing, background opacity and an optional accent line remain configurable. All layouts share the light/dark palette and 6px default rounding, with no shadows or outlines. The list excludes itself, the ClickGUI opener and Configuration pages. It is enabled by default.

**Movement > SnapTap** prioritizes the most recently pressed opposing movement key. **A/D priority** is on by default; **W/S priority** is optional. These labels describe the directions: actual input follows your rebound movement keys. Releasing the winning key restores an opposing key that is still physically held. If both keys are first observed together without a known order, that pair remains neutral until another edge resolves it. It suspends in Minecraft menus and on focus loss, and works in the ClickGUI only when **Move in ClickGUI** is enabled. Disabling it restores normal movement key states. The module itself starts disabled.

**Interface > Screenshare protection** switches all Mica visuals, including the menu, HUD and ESP, between the capture-excluded overlay (on) and normal game rendering (off). It applies live, saves with the profile and remains effective when the Interface HUD is disabled. It defaults on for Windows and off on other systems. Capture exclusion depends on the capture method and Windows support.

Drag category headers to reposition dropdowns. Right-click a header to collapse or expand it. With dragging disabled, left-clicking a header also collapses it. Right-click a feature module to expand its settings. Scroll inside a panel for settings below the visible area. The initial layout wraps categories on smaller windows.

Ctrl+click a numeric setting or slider to type its value. Enter saves and Escape cancels. For a range, enter two endpoints such as `40, 80`, or one number such as `60` to set both endpoints. Values use the setting's existing bounds and step size. Ctrl+A selects the text and Ctrl+V pastes.

**Combat > Anchor Macro** automatically selects the required hotbar item from any held item. It chains confirmed placement, charging and detonation on the same anchor, with one restoration after the sequence in Temporary mode. Each stage has its own min/max swap and interaction delays. New timing settings default to 0–25 ms, while saved custom delays retain their values. **Restore delay** controls returning to the original slot. Every swap, use and restore occupies a separate client tick, even at zero delay. Actions run during normal input processing and use the live crosshair, vanilla reach and vanilla block interaction. The next stage waits for the server's block update, and its swap delay runs from the previous interaction. Rejected or unconfirmed interactions pause that target until you aim away. Changing slots yields the hotbar to you, and holding attack pauses the macro.

**Misc > KeyPearl** is a single-use action. Expand the module and assign its module bind, then press it during gameplay to select a hotbar pearl and throw it at your current aim. An offhand pearl is supported when the hotbar has none. **Swap delay**, **Throw delay** and **Restore delay** are independent min/max ranges in milliseconds. **Restore slot** defaults on. Presses during an active sequence are ignored, and vanilla pearl cooldown is respected. Switching slots, opening a menu, losing focus, disconnecting or changing player cancels the action. KeyPearl interrupts the anchor sequence, waits for its slot restoration, and throws before movement processing. Active throws are never restored from saved configuration.

Settings, module enabled states, key bindings, category positions and collapsed categories save automatically once per second and on normal exit. The menu itself always starts closed. Mod and injected launches share **%APPDATA%\.logic\latest.json** on Windows; other systems use **~/.logic/latest.json**. The injector respects its supplied data directory. Startup always restores this working session. Old `config.json` files are left untouched and are not imported. Malformed files are backed up before recovery.

**Configuration > Profiles** manages manually saved configs in the data directory's `configs` folder. Enter a name and use **Save as** to capture the entire session, including theme and panel layout. Select a saved config and use **Load** to apply it and update `latest.json`; later autosaves never change the named file. **Overwrite** explicitly updates the selected snapshot, **Rename** uses the name field, and **Delete** removes the selected snapshot. Overwrite and delete require confirmation. **Refresh** picks up files added externally. Operation errors appear inside Profiles even with notifications disabled.

Profile loads run at the start of a client tick, cancel active actions and input editors, and keep the ClickGUI open. Omitted settings use registered defaults. Invalid profiles and failures saving the current session prevent switching; activation or final-save failures restore the previous settings. Profile selection and name-entry state are not saved inside snapshots.

To customize colors, close Minecraft, edit the `theme.dark` or `theme.light` objects in that JSON file, then restart. All supplied CSS token names are retained without the `--` prefix, for example:

```json
"theme": {
  "dark": {
    "bg-primary": "#2A3140",
    "bg-secondary": "#323B48",
    "text-primary": "#E2E8F0",
    "accent": "#6B8CA7",
    "overlay": "#00000080"
  },
  "radius-sm": 4,
  "radius-md": 6,
  "radius-lg": 10
}
```

This is an excerpt, not a replacement for the whole file. Colors accept `#RRGGBB` or `#RRGGBBAA` (alpha last). Unknown tokens are ignored; invalid colors retain their defaults. Shadows are not rendered. Registered module values live under `modules.<module name>.settings`; numeric values are clamped to their allowed ranges, and invalid dropdown choices are ignored.

Build everything with `build.bat -Offline -SmokeTest -OutputDirectory dist-ui` when dependencies are already cached, or omit `-Offline` to allow downloads. Font atlas rasterization and configuration persistence checks run as part of the build. The native loader smoke test uses a disposable fixture JVM.

## Eagle and advanced settings

Enable **Movement > Eagle** to sneak before predicted support loss at a block edge. It starts disabled.

- **Mode** defaults to **Autoalign**: it adds block-axis probes for near-horizontal movement, keeps the original movement-path check, and requires extra clearance for two complete client ticks before releasing sneak. This prevents edge seams from repeatedly releasing and re-pressing sneak. It leaves camera rotation and your movement keys unchanged. **Standard** restores immediate edge-based release.
- **Shift in air** keeps sneak pressed while airborne when the selected pitch, backward-key and held-block checks pass. While airborne it overrides the On ground and No jumping checks; the option defaults off.
- **Checks** is a multi-select dropdown: independently enable **Pitch**, **Only holding S**, **Only blocks**, **On ground** and **No jumping**. Every selected check must pass. The S check follows your rebound backward key.
- **Pitch range** has two handles and defaults to 60-90 degrees looking down. It is used when the Pitch check is selected.
- **Block hands** lets you select main hand, off hand, or both for the Only blocks check. With neither hand selected, that check cannot pass.
- **Lead time (ms)** is a min/max slider. Both endpoints default to 100 ms. Equal endpoints use a fixed advance; different endpoints choose a time within the range for an approach. This is advance prediction, not a wait after walking off. Movement is processed in ticks, so the prediction includes the upcoming 50 ms tick and is not an exact wall-clock deadline.
- **Edge margin** adds a small inset to the player's support footprint, default 0.05 blocks.

Eagle samples actual block collision shapes along the intended movement path, including gaps before the destination. It pauses in menus, in the ClickGUI and on focus loss, and does not run while flying, riding, climbing or in liquids. It releases its input on disable or session change and preserves manual sneak input, including Minecraft's toggle-to-sneak mode. With Shift in air off, it releases its automatic input once the player has no support. Shift in air adds crouching while airborne; it does not stop a fall.

Multi-select dropdowns stay open while you toggle individual choices. Min/max sliders let you drag the nearest endpoint; endpoints cannot cross. Both setting types are available to every module through `registerSetting`, and both save automatically. Multi-selects use JSON arrays of option names. Ranges use an object such as `{"low": 100, "high": 150}`.


## Auto Armor

**Misc > Auto Armor** equips armor from the main inventory and hotbar. It starts disabled, with **Inventory only** enabled. Disable that option to allow equipping while playing with no screen open.

- **Armor slots** selects helmets, chestplates, leggings and boots independently.
- **Replace armor** upgrades equipped pieces, keeping old armor in inventory. Replacements require a free inventory slot; nothing is dropped. Removal and equipping happen on separate ticks.
- **Keep elytra** preserves an equipped elytra. **Avoid Binding Curse** skips cursed candidates.
- **Preference** chooses Protection or remaining Durability. Protection compares armor points plus one quarter of toughness, optionally adding general Protection enchantment levels. Equal protection scores favor remaining durability.
- **Minimum durability** excludes candidates below the selected remaining percentage. **Action delay** controls client ticks between clicks.

Auto Armor pauses in other menus, while using an item, while the ClickGUI is open, while another combat module controls inventory, and while an item is on the cursor.

## Chest Stealer

**Misc > Chest Stealer** transfers stacks from storage menus you open. It starts disabled and uses normal shift-click transfers.

- **Containers** enables chest-style storage (including barrels and ender chests), shulker boxes, hoppers and dispensers independently. Only chest-style storage is selected by default.
- **Open delay** waits after opening storage. **Transfer delay** chooses a random interval in milliseconds between transfers. **Slot order** chooses first to last, last to first, or random.
- **Item filter** supports All, Whitelist and Blacklist. **Item IDs** accepts comma-separated exact identifiers, up to 64 characters; `diamond` means `minecraft:diamond`. An empty whitelist takes nothing.
- **Auto close** closes when no eligible items remain, after **Close delay**. **Close when full** optionally also closes when no eligible stack fits. Partial merges into existing inventory stacks remain possible when every slot is occupied.

It pauses while the ClickGUI is open or an item is on the cursor. Both modules' options and keybinds save through the normal configuration system.

## FastPlace timing

**Misc > FastPlace** clears vanilla's held-use delay and schedules attempts against the player's client ticks. At **20 CPS**, it allows one attempt on every client tick, without skipping ticks because of wall-clock jitter. Lower CPS settings preserve the cadence across tick rounding rather than repeatedly rounding the interval up. Activation delay still uses game ticks after the initial attempt, duplicate uses within a tick are blocked, and timing resets on player/session changes. Blocks can be held in either hand. The Arraylist's rate suffix shows the configured attempt rate; actual successful placement depends on the game interaction and server.


## Backtrack targets and status

**Combat > Backtrack** now accepts living mobs as well as players by default. Enable **Players only** to restrict it to player targets. This also permits testing against mobs in singleplayer; earlier builds silently rejected every mob attack.

To observe it, enable Backtrack, attack a moving mob with a sword or axe and keep attack held while the **Hold attack** check is enabled. You can disable Hold attack for individual click-and-release attacks. Backtrack starts delaying updates when the recent target moves farther away within the configured distance range. Its Real position box shows the undelayed position. It releases updates when the hold expires, the target comes closer, eligibility is lost, or a configured hit/teleport barrier occurs.

**Freeze** targets a player after an attack and shows their real position. **Release distance** sets the maximum distance in blocks; **Release after ticks** flushes the queue after that many client ticks (0 disables the timer). Enable **Partial release** to replay the configured number of ticks of queued history at each interval while continuing to hold newer packets. A full release requires another attack to start freezing again.

**Hitbox** begins recording a player's incoming movement after your first attack. Its breadcrumb joins the actual queued positions, and aiming at a reachable recorded hitbox shows a preview when the same ray also intersects the latest known target hitbox. A historical position alone cannot trigger an entity attack. A normal attack releases the incoming packet prefix through that position before dispatching the attack, retaining newer history. **Max blocks** bounds the total retained path length and flushes everything when exceeded. Like Freeze, it shows the real position in blue and supports **Release distance**, **Release after ticks**, and optional **Partial release** with the same tick-based amount and interval. Taking knockback does not end the freeze. Automatic partial releases advance the breadcrumb origin and keep newer history. A full release requires another attack to start freezing again. Blocks and nearer crosshair entities occlude the selection, and normal interaction reach applies. Mode changes, disconnects and player/world replacement clear the preview and history. The server validates attacks against its own current state.

With Arraylist **Show modes** enabled, Backtrack shows the actual queued-packet count while holding updates. **No target**, **Hold attack**, **Need weapon** and **Cooldown** identify common inactive states. A stationary target need not produce a visible delay. This is client-side packet delay; it does not increase server-side attack reach.

## Self Destruct

Click **Misc > Self Destruct > Unload** to stop Logic for the current Minecraft session. The action runs at the next client tick boundary after the current GUI frame has completed. It saves your ordinary settings, disables modules, releases Backtrack's pending packets for the current connection, restores input and focus preferences, removes the Logic overlay, clears listeners/module references and frees unused renderer resources. An active renderer shared with other Mica consumers is retained.

Injected mode also restores the original game-method bytecode captured at injection, unregisters native bindings and requests delayed native-library release on Windows. If hook restoration or native unregistration fails, native code is retained rather than unmapped unsafely. Mod mode leaves its installed mixins inactive. The action is never saved as enabled and cannot execute automatically when loading a config. In injected mode, wait approximately two seconds for the DLL to finish releasing, then run the launcher against the same Minecraft process to inject a fresh client. The class loader and client static state are recreated for each injection. A Fabric mod that was unloaded can also be started again through the injector; its original installed mod classes remain inactive.

The injectable client now runs in a disposable class loader. Unload disconnects its hook delegate, closes the extracted payload JAR and deletes that per-injection copy. The client classes become eligible for JVM garbage collection; their exact reclamation time is controlled by the JVM. The permanent hook bridge and shared ImGui/JNA runtime remain loaded for reuse, and an installed Fabric mod's registration remains until Minecraft exits. Configurations, logs and distribution JARs are retained. Restart Minecraft once when upgrading from older builds that appended the entire client to the game class loader; those older JAR handles cannot be retroactively released safely.


The build includes `logic-runtime.jar`, which contains the shared bridge and rendering dependencies, and `logic-payload.jar`, which contains the reloadable client. Both are bundled into the native loader. The shared runtime is cached separately; the payload gets a unique extracted filename each injection. Lifecycle hooks register once in the game class loader and forward only to the current client, so repeated injections do not accumulate payload callbacks.

Verification includes three Java payload load/unload cycles with fresh static state and closed JARs, plus two real native DLL injection/unload cycles in a single fixture JVM. Full Minecraft reinjection still needs an in-game check.
