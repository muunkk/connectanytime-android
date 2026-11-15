# Battery measurement for ConnectAnytime: report and proposal (v15)

Base: origin/main = 0.1.4 (versionCode 5), PR #6 merged. Branch `battery-measure`.
Setup being optimized: phone hotspot + VPN connected + Sharing card (hotspot) + tether proxy + Automatic PAC.
Symptom: the battery dies every ~2 h. *If* the phone has a typical 17–19 Wh battery, emptying it in 2–2.5 h is about 7–9.5 W on average (the phone model and real capacity are not known yet; the export records them). For illustration only (not device evidence), that would be
more than a hotspot alone is often assumed to draw (roughly 2–4 W under load), so it's worth measuring where it goes.

## 1. Feasibility: can the app measure its own battery impact without root?

Yes for the **phone's total drain while the app runs**, measured well. Partly for **the app's own share**, estimated and labelled as estimates.

| Source (public, no permission) | What it gives | Accuracy limits |
|---|---|---|
| `BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER` | remaining charge, µAh | Fuel-gauge counter; resolution varies by OEM (fine on Pixel, coarse on some). Best primary signal over ≥10 min windows. |
| `BATTERY_PROPERTY_CURRENT_NOW` / `CURRENT_AVERAGE` | instantaneous / averaged current, µA | Documented as µA, positive when charging. Some OEMs deviate, so it's stored raw and not normalized or used for totals. `CURRENT_AVERAGE` is often unsupported (returns `Integer.MIN_VALUE` or 0). |
| `BATTERY_PROPERTY_ENERGY_COUNTER` | nWh | Usually unsupported (`Long.MIN_VALUE`). Record it only when it's valid. |
| `ACTION_BATTERY_CHANGED` (sticky) | level/scale, voltage mV, temperature, plugged, status; `EXTRA_CYCLE_COUNT` on API 34+ | Level is in 1 % steps, so the error is about ±2 points per window. Accuracy depends on how many points were used (about ±10 % needs ~20 points lost). |
| `PowerManager.isInteractive`, `isDeviceIdleMode`, `isPowerSaveMode`, `getCurrentThermalStatus` (29+), `getThermalHeadroom` (30+) | screen and Doze state, thermal status | State flags are exact. Thermal headroom is a forecast and may be unsupported, so v1 doesn't record it. |
| `TrafficStats.getUidRx/TxBytes(myUid)`, `getMobileRx/TxBytes`, `getTotalRx/TxBytes` | the app's bytes; the device's cellular bytes | The app UID's bytes include the proxy legs and the OpenConnect socket, so forwarded traffic is counted more than once. The proxy's own counter is exact. |
| `Process.getElapsedCpuTime()`, `/proc/self/stat`, `/proc/self/task/*/stat` (own process) | the app's CPU time in total and per thread, by thread name | Exact CPU time. Turning it into energy is an estimate: no per-core power model is public. A thread's CPU vanishes from `/proc` when it exits, so v1 measures proxy work per task (§3.3 item 3) and labels the VPN figure a lower bound. |
| `SystemHealthManager.takeMyUidSnapshot()` (API 24+) → `UidHealthStats` | Android's own BatteryStats slice for **this UID**: user/system CPU ms, `CPU_POWER_MAMS`, `TIMER_MOBILE_RADIO_ACTIVE`, mobile/Wi-Fi bytes, packets and ms, `MOBILE_POWER_MAMS`, wakelock timers | Since the last stats reset (unplug or full charge). The `*_POWER_MAMS` fields are legacy, vendor-dependent attribution values; a 0 doesn't reliably mean unsupported. This is the closest public thing to per-app attribution, but it counts the shared devices' traffic as this app's, and `CPU_POWER_MAMS` is deprecated. **Not used in v1**. |
| `SystemHealthManager.getSupportedPowerMonitors` / `getPowerMonitorReadings` (API 35+) | Energy per hardware rail or subsystem (Pixel ODPM: modem, Wi-Fi, CPU clusters, display…), µWs | Device-wide, not per app, but measured rather than modelled. Only on supported hardware (Pixel 6+). Rail names are hardware IDs, with no personal data. It mixes measured rails with modelled consumers, so it's **deferred to v1.1**. |

**Not available without system permissions:** `BatteryStats` / `BatteryUsageStats` / `dumpsys batterystats` for other UIDs or for the
whole-device per-app breakdown (`BATTERY_STATS` is signature|privileged), and the per-app "battery usage" screen numbers. Over adb they are available, see §4.

**Estimating the app's share anyway (observation, not isolation; see §3.9):**
1. Every sample carries a *situation key*: screen on/off, charging, VPN state, hotspot on/off, sharing on/off, uplink kind. Each interval is marked in use or idle by proxy throughput (above 50 kB/min; back to idle after 3 min under 10 kB/min).
   Drain is reported per situation. "Hotspot on, proxy in use, screen off" against "hotspot on, proxy not in use (laptop DIRECT
   or idle), screen off" shows what the proxy+VPN path *appears* to add on top of the hotspot, given comparable workloads. The user creates these A/B windows by
   turning the proxy off on the laptop for an hour, without touching the VPN.
2. The app records what it does itself: CPU time split into VPN (OpenConnect thread), proxy and other; bytes through the proxy; reconnects;
   the phone's awake share; and the recorder's own CPU. (HealthStats is deferred to v1.1.)
3. Ground truth against batterystats over adb (§4).

## 2. What is likely draining the battery (ranked)

Outside the app's control (most of the absolute drain):
- **A. Wi-Fi hotspot (SoftAP) + cellular modem under load.** Hypothesis: probably the biggest share in absolute terms, more with weak signal, 5G NSA, or a hot battery (no device measurement yet). The app can't change it, but it can report signal-independent context (uplink kind, thermal).
- **B. Screen.** Significant when on (no device measurement yet). Situations with the screen on are reported separately.
- **D. Notifications and the tile while flapping:** `state()` posts a notification and calls `requestListeningState` on every status change (`ConnectVpnService.kt:142-143`). Low.
- **C. Heat.** Hotspot + cellular + CPU warms the phone. The recorder captures temperature and thermal status as context; whether heat raises drain on this phone is unmeasured.

Inside the app's control, ranked by expected impact. **These are hypotheses until measured**: the watt figures are order-of-magnitude.
1. **Everything the laptop's proxy-aware apps do goes through the proxy and the VPN, in user space.** `PacFile.kt:90` returns `PROXY …; DIRECT` for every
   non-local, non-tailnet host. Every byte is copied through the JVM twice (`TetherProxy.kt:330`, `ProxyProtocol.kt:55`, 16 KB buffers,
   up to two worker threads per connection, `TetherProxy.kt:246,324`), then read from the tun by OpenConnect, encrypted by OpenSSL, and sent over TLS or DTLS. This **bypasses Android's tethering
   hardware offload** (kernel/IPA NAT forwarding, which costs almost no CPU), so CPU energy grows with the laptop's throughput.
   On top of that, if DTLS fails (`ocbridge.c:268,325` falls back to TLS only), TCP-over-TCP adds retransmissions and keeps the radio active longer.
   *Hypothesis:* cost grows with throughput; magnitude unknown until measured. It is likely the largest cost the app has. All traffic stays on the VPN (a split PAC is ruled out), so the
   lever is the per-byte cost of the proxy path itself (Q1). Measure first.
2. **The proxy rescans every 2 s forever** (`TetherProxy.kt:91`); each rescan also publishes a `TetherUsage` (`:202,207`). An equal value doesn't emit, but while traffic flows the byte counts change, so it emits every 2 s and wakes the widget's `combine` (`ConnectWidget.kt:128`) and a visible SharingCard (`SharingCard.kt:59`). It also sweeps idle sessions (`:146`). Timer and Binder overhead happens on every rescan; emissions happen only with traffic. Each `reconcile` with sharing on enumerates every interface once
   (`TetherProxy.kt:106`; line 121 runs only for the unknown-hotspot fallback at :191), plus `allNetworks` and `getLinkProperties` binder calls (lines 99–103, 113),
   which also cost CPU in `system_server`, billed to the system rather than to this app. That's 43 k scans a day, while the same events arrive
   as `TETHER_STATE_CHANGED` (line 75) and the prefs listener. *Expected:* low to moderate. It doesn't hold a wakelock, but while the
   hotspot keeps the phone awake it fires every time.
3. **Per-connection overhead.** `stillServes` (127–131) calls `upstream()` (a binder call) and `NetworkInterface.getByName`; `vpn()` (268–277)
   does `allNetworks` plus a capabilities and link-properties binder call per network, plus `getByInetAddress` (an interface enumeration);
   `phoneOwnsAddress` (`ProxyProtocol.kt:43`) enumerates interfaces per destination; and request heads are parsed **one `recv` syscall per byte**
   (`ProxyProtocol.kt:60`, unbuffered socket stream at `TetherProxy.kt:293–302`; each byte also runs `nanoTime()` and `meter.traffic`). Browsers open many connections. *Expected:* low.
4. **OpenConnect timers.** DPD and keepalive come from the server (`cstp.c` logs "CSTP connected. DPD %d, Keepalive %d" at INFO, so it's
   already in the activity log); the DTLS retry period is 60 s; the watchdog runs every 10 s (`ConnectVpnService.kt:448`, cheap). While the VPN is down, a forced reconnect fires every 60 s
   (`ConnectVpnService.kt:465–471`), and rejoins back off. Each reconnect is a TLS handshake. *Expected:* low unless the uplink flaps, so we count
   reconnects per hour.
5. **Compression.** OpenConnect asks for stateless (LZS) compression by default (`library.c:206`). If NYU accepts it, every outgoing
   packet gets a compression attempt, which is wasted CPU on traffic that's already encrypted. Unknown; the log shows it.
6. **Half-closed sessions poll every 1 s** (`TetherProxy.kt:348`), each for up to the 300 s idle cutoff (line 146). Low.
7. **The activity log rewrites the whole file** (up to 1500 lines) 1 s after any line (`Storage.kt:214–224`), and copies the whole list for its StateFlow on every line (`Storage.kt:213`). Low; matters only while flapping.
8. Negligible: the widget (distinct + debounced, `ConnectWidget.kt:127–130`, 30 min period), the updater (only when the app opens, at most every 6 h,
   `Updates.kt:192`), notifications (only on state change, `ConnectVpnService.kt:142`). There are **no wakelocks, alarms or WorkManager** in the app (grep).
   No busy loops were found: all sockets block and the timers are fixed-delay.

## 3. The toggle (what this PR builds), v15: one consolidated spec

This replaces all earlier §3 text and every override layer from council rounds 1–14. There is one source of truth below, with a string table in §3.9.

### 3.1 Scope
- **v1 records:** phone drain (%/h, plus W where computable); phone awake share; the app's CPU (VPN thread, forwarding, other); hotspot proxy bytes and USB proxy bytes;
  device cellular bytes; VPN transport (inferred), VPN state and reconnect episodes; context (screen, Doze, power save, thermal status, temperature, uplink kind, hotspot, sharing);
  the recorder's own CPU.
- **v1 shows:** a hero for one fixed situation, one comparison, the app's processor use, the top situations, and a collapsible More detail.
- **v1 exports:** a CSV.
- **Not in v1:** HealthStats, PowerMonitor rails, any "app mAh" figure, temperature in the UI (it's CSV only), and any change to VPN or proxy behaviour.

### 3.2 Components and threads
| Component | Thread | Lifetime |
|---|---|---|
| `BatteryRecorder`: sampling, receivers, flow observation. **Owner:** `ConnectApp.onCreate` starts a collector of (pref, `state.busy`) that starts and stops it, so a cold process started by the VPN service (Always-on, sticky restart) resumes recording without an Activity (Robolectric test) | `HandlerThread("battery-rec")` | only while recording (pref on **and** `ConnectApp.state.value.busy`) |
| `BatteryStore`: CSV append, rotation, report build, export, Clear | single-thread executor `battery-io` (core times out after 30 s, created lazily) | independent of recording |
| `BatteryReport`: pure functions (intervals, windows, aggregates) | called on `battery-io` | — |
| `BatteryScreen` + Home row | main | — |
Every task is wrapped in `runCatching`, start and stop are idempotent, and a generation number drops tasks posted by an earlier run. The recorder never touches `ConnectVpnService.queue`,
`deadlines`, `sessionLock`, native calls, or the connect/rejoin/reconnect paths.

### 3.3 Edits to existing code (complete list; each one is checked in the diff)
1. **Thread names** (constants shared with the `/proc` classifier, which tests use): `Thread(runnable, "oc-run")` at `ConnectVpnService.kt:320` (the overload change only). `TetherProxy`: timer `proxy-timer` (`:51`), the `workers` ThreadFactory
   `proxy-worker` (`:52`), and accept `proxy-accept` (name argument at `:250`). All are ≤15 chars, checked by a unit test.
2. **`ProxyCounters`** (new object): `AtomicLong`s for hotspot up/down, USB up/down, and `forwardingCpuNs`; a `@Volatile var gen` (0 = metering off); incremented beside `meter.traffic` in
   the counting stream (`TetherProxy.kt:293-297`).
3. **Forwarding CPU meter:** `workers` becomes a small `ThreadPoolExecutor` subclass with the same parameters. The ThreadFactory only names threads. `beforeExecute` installs a fresh
   `ThreadLocal<CpuMeter>` for that **task** (`last = threadCpuTimeNanos()`, `gen` = current), and `afterExecute` flushes it and clears it, so every task, even a short one on a reused worker, is flushed when it ends. In the counting stream's `count(n)` (`TetherProxy.kt:295`, not the synchronized `Session.count()` at `:288`), when `ProxyCounters.gen != 0`
   and ≥1 s has passed since the meter's last flush (one `nanoTime` comparison, reusing the value `count()` already reads), it does: `now = Debug.threadCpuTimeNanos()`; if the meter's `gen` equals
   `ProxyCounters.gen`, add `now − last`; otherwise adopt the current gen and **discard** the stale delta; then `last = now`. With metering off (gen 0), `beforeExecute`/`afterExecute` read no clock either.
4. **`ConnectApp.vpnTransitions` / `reconnectEpisodes`** (`AtomicLong`) plus `lastStatus: AtomicReference<String>`, updated in `ConnectVpnService.state()` (`:138`):
   `prev = lastStatus.getAndSet(status)`. If prev differs, vpnTransitions++. If status is "Reconnecting…" and prev was "Connected", reconnectEpisodes++. These are constant-time and can't throw.
5. **`ActivityLog.add`:** an optional `@Volatile observer`, called after the line is stored, inside `try { } catch (t: Throwable) { }`. The recorder's observer only does `startsWith`
   checks, writes a `@Volatile` transport enum, and increments `transportChanges` when the class changes. It's set to null on stop.
6. UI and navigation: `MainActivity.kt:108-123` (the `Boolean activity` → `enum Screen`, the `AnimatedContent` direction, `BackHandler`), `ActivityScreen.kt:100-103` (the same one-line `contentPadding` width cap: the **only** non-battery UI change, called out in the PR), `HomeScreen` (the second row), the new `BatteryScreen`, strings, the `ic_battery` and `ic_error` vectors if missing, a `FileProvider` + `res/xml/battery_paths.xml`,
   and a `measureBattery` pref group in `Store`.

### 3.4 Observation
- `gen` (the situation generation) is incremented on: the screen on/off broadcast; the hotspot broadcast; the sharing pref listener; an uplink change; a transport class change; and VPN, where gen also adds the Δ of
  `vpnTransitions`. `pgen` (the power generation) is incremented on power connected/disconnected broadcasts, and when the `status` or `plugged` read at sample time differs from the last row.
- Flows are collected as one distinct projection, `combine(state.map{status}, uplink, sharing prefs).distinctUntilChanged()`, on `battery-rec`. **`tetherUsage` is never collected.**
- **Hotspot:** register `TETHER_STATE_CHANGED` exactly as `TetherProxy.kt:81-90` does (`RECEIVER_EXPORTED` on 33+, the sticky return, the `heardBroadcast` lock), with `activeTethers()`
  filtered to `wifiTetherName` interfaces. That gives on / off / unknown (unknown = no broadcast heard). Handler: `battery-rec`.
- Screen and power receivers use the `battery-rec` handler. Battery values come from the sticky `ACTION_BATTERY_CHANGED` read at sample time plus `BatteryManager` properties.
- **Transport classifier** (exact OpenConnect 9.21 strings). "CSTP connected" → tls. "Established DTLS connection" → dtls. "DTLS unavailable; using TLS only", "DTLS Dead Peer
  Detection detected dead peer", "DTLS handshake failed", "DTLS handshake timed out", "DTLS Rehandshake failed", "DTLS session resume failed" → tls. "Reconnecting to the server",
  or the VPN not connected → unknown. At start it's seeded from the in-memory log tail (the newest classifying line after the newest `BUILD_DESCRIPTION` line), else unknown. Known limitation:
  a DTLS write error can reconnect DTLS without a classifying line.
- Each transition posts one coalesced event-row task (an `AtomicBoolean`).

### 3.5 Rows
- CSV header: `#` lines (`# schema=1`, `# build=`, `# device=<manufacturer> <model>`, `# android=<release> (API n)`, `# capacity_mAh_est=`, `# exported=`, and a pointer to the
  README's adb steps). Parsers skip `#` lines.
- Columns: `v, boot, rec, seq, gen, pgen, transport_gen, kind(periodic|event|gap|start|stop), wall_min, elapsed_ms, uptime_ms, level, scale, status, plugged, charge_uAh,
  energy_nWh, current_now_raw, current_avg_raw, voltage_mV, temp_dC, interactive, doze, power_save, thermal, vpn, transport, hotspot, sharing_on, hot_up, hot_down, usb_up,
  usb_down, mobile_rx, mobile_tx, uplink, reconnects, cpu_proc_ms, cpu_vpn_ms, cpu_fwd_ms, sampler_cpu_us, io_cpu_us`.
- Unsupported values are written **empty**: `Integer.MIN_VALUE`, `Long.MIN_VALUE`, `TrafficStats.UNSUPPORTED`, and 0 where documented as unsupported. `CURRENT_NOW` is stored raw (µA,
  positive when charging per the docs) and not used for totals.
- `rec` is a monotonic counter persisted in prefs, with `seq` per rec. `boot` = `Settings.Global.BOOT_COUNT`. No addresses or device names, ever: `LinkUsage.devices` is never read.
- CPU: `cpu_proc_ms` = `/proc/self/stat` utime+stime. `cpu_vpn_ms` comes from `/proc/self/task/*/stat`, the `oc-run` threads keyed by (tid, starttime), last value seen per key, so it's a lower bound.
  Fields are parsed after the last `)`. Missing or unreadable files are skipped. `cpu_fwd_ms` = `forwardingCpuNs` plus `proxy-timer`/`proxy-accept` from /proc, keyed by (tid, starttime) with last-seen totals kept after a thread exits (monotonic across listener restarts; test) from /proc. `sampler_cpu_us` (renamed from rec_cpu_us) = the `battery-rec`
  thread's CPU.

### 3.6 Cadence, bounds, storage
- Periodic rows every 60 s via `postDelayed`, with no wakelock. While plugged in there are no periodic rows, only the plug and unplug event rows.
- At most 120 buffered rows. Flush every 5 rows, on stop, and before export. A failed flush backs off 1, 2, 4… up to 30 min. On overflow the oldest are dropped and a `gap` row is written. Report rows count the dropped coverage.
- `filesDir/battery/samples.csv`, capped at 4 MiB (4 × 1024 × 1024 bytes) to retain a full seven-day run at one row per minute. Rotation keeps the newest half via tmp + rename. Before appending, an incomplete tail is truncated to the last `\n`. Retry dedup skips buffered
  rows ≤ the file's last complete `(rec, seq)`. Rows from a failed stop flush keep their `(rec, seq)` and are written first next time.
- Clear deletes the CSV and the buffer, and clears `batteryAutoStopped`. Shared exports stay readable until the one-hour prune removes them. If recording, it starts a new `rec`. `io_cpu_us` = the `battery-io` thread's CPU for its tasks (per-task `threadCpuTimeNanos` deltas), written on the next row.
- `batteryAutoStopped` is **observed live** on the Battery screen and latched into `rememberSaveable` for the visit (so an auto-stop while the screen is open shows too), and the pref is cleared when the user **leaves** that screen, turns measuring on again, or clears. The auto-stop copy stays visible while the screen is open (Robolectric test).
- Auto-stop: the deadline (start + 7 days) is persisted. When it passes: pref off, `batteryAutoStopped = true`, and a log line. Turning it on resets the deadline.
- Activity log lines: start, stop, auto-stop, and a summary at most every 6 h.
- `ProxyCounters.gen` = the current `rec` while recording, and 0 otherwise.

### 3.7 Calculations (`BatteryReport`, pure)
- **Epoch** = all rows with one `rec` value. A **new persisted `rec` is allocated on every recorder start**: turning on, the VPN becoming busy again, process recovery, and Clear. So an epoch never spans VPN-off time or a process restart (counter reset), and no interval crosses one (rule below). Baselines, capacity and q are per epoch; the report aggregates across epochs.
- `/proc` CPU ticks are converted with `Os.sysconf(OsConstants._SC_CLK_TCK)`: ms = ticks × 1000 ÷ clk_tck.
- **Two calculation paths, chosen per epoch:** the *charge path* when `charge_uAh` is present on ≥90 % of rows **and** it moves (it must not stay flat while the level drops ≥3 points), else the *level path*. A capacity estimate that is zero, non-finite, or from level 0 is rejected, and rows with level 0 are skipped for capacity. Synthetic tests cover a flat counter and a zero level.
- **Interval** i→j: same boot and rec; neither row is a gap; both unplugged and discharging; **same pgen**; 0 < Δelapsed ≤ 2 h; and Δcharge ≤ 0 (charge path, both values present) or Δlevel ≤ 0 (level path). Anything else is excluded from all drain and W figures.
- **Situation of an interval:** if gen_j == gen_i, it's key_i, which must equal key_j. If gen_j == gen_i+1 and row j is that transition's event row, it's key_i. Otherwise it's **Mixed**.
- **Row key:** (screen, hotspot, sharing_on, uplink), which is what must match at interval endpoints. **In use/idle** is then attached per interval before windowing, so the window key is row key + in use/idle. *In use* is per interval, from the hotspot proxy bytes: it becomes in use above 50 kB/min and returns to idle after **3 min of elapsed time**
  under 10 kB/min.
- **Window:** a maximal run of same-key intervals. Under 10 min it goes to "Other or short".
- **Drain, charge path:** a window's drain is first − last `charge_uAh`. Situation = Σ windows. **%/h = 100 × Σ Δcharge_uAh ÷ capacity_uAh ÷ hours**, where capacity_uAh = median of `charge_uAh ÷ (level/scale)` over the epoch. Test through formatting: 100 000 µAh lost from 5 000 000 µAh in 1 h → "2.0%" an hour.
- **Drain, level path:** points lost (first − last level ÷ scale × 100) ÷ h. It needs no capacity, and W is unavailable. Tests cover unsupported charge and energy counters with a valid level and scale.
- **Range** (a heuristic, shown as "sampling estimate" next to the value; it reflects both gauge steps and consumption between samples): ±2q per window, summed over windows. q = the median step between distinct counter values, taken from periodic pairs 50–70 s apart when there are ≥10;
  else from all distinct steps when there are ≥10; else **unavailable**, never 0. Level path: q = 1 point.
- **Precision:** %/h to 1 decimal under 10, 0 decimals at 10 or more; W to 1 decimal; CPU to 1 decimal under 10 %.
- **Ready:** hours ≥ 0.5 and range available and range ≤ 25 % of the value. If not ready, the reason is "too short" or "readings too uneven".
- **W:** Σ ΔE ÷ Σ Δt over valid intervals. **Per interval:** the energy source is used if both endpoints have `energy_nWh` and ΔE_consumed = first − last ≥ 0. Otherwise the charge source is used (charge path,
  ΔµAh × mean V ÷ 1e6). With neither, the interval is left out of W only. A counter that stays flat for ≥30 min while charge falls is marked unusable for the epoch. Label: "measured" when every interval used energy,
  "about" when any used charge. Tests: increasing, missing, flat-energy and mixed-source cases.
- **Awake share:** Δuptime ÷ Δelapsed.
- **CPU:** **% of one core = 100 × Δcpu_ms ÷ Δelapsed_ms** (test: 6 000 ms over 60 000 ms → "10%" of a core). Forwarding and Other are "about"; VPN is "at least"; Other = proc − vpn − fwd, floored at 0 in the UI, raw in the CSV. Values are percentages and **may exceed 100** (several busy threads on a multi-core phone); they're never clamped (tests: 90 000 ms over 60 000 ms → "150%"; 120 000 ms → "200%"). The formatter is given value ÷ 100.
- **Comparison:** pick the uplink kind with the most qualifying hours on **both** sides, and name it. Automatic setup: (screen off, hotspot on, sharing on, in use) vs (screen off,
  hotspot on, sharing off). Manual setup: in use vs idle (both with sharing on). Show the difference only when both sides are ready and their ranges don't overlap; when both are ready and the ranges overlap, show `battery_compare_none` (test). The difference is formatted as a **plain decimal** (no % sign; test that the string has no "%"). Its sign picks `battery_compare_more` or `battery_compare_less` (unit test for the negative case), and it's always followed by `battery_compare_caveat`. The hero uses the **same uplink** as the comparison (when there's no comparison, the uplink with the most ready hours for the hero situation).
- Everything is time-weighted and comes only from the retained CSV plus the buffer.

### 3.8 Screens
**Home** (`HomeScreen.kt:364-370`): `Group(1)` becomes `Group(2)` with `segmentedShapes(i, 2)`. Row 0 is Activity (unchanged). Row 1: leading `ic_battery`, headline `battery_title`, trailing chevron.
Supporting text is `battery_home_help` when off, otherwise the status string (§3.9), with **no ticking duration**. While measuring, the leading `ic_battery` is tinted `primary` (as `SharingCard.kt:181` does); there's no second icon. For CouldNotSave the supporting text is `colorScheme.error` with an 18 dp `ic_error` glyph inline before the text (`contentDescription = null`). The row has a merged spoken form ("Battery drain, measuring") and `onClick(label = battery_open)` ("open details").

**Navigation:** `rememberSaveable { mutableStateOf(Screen.Home) }` with `enum Screen { Home, Activity, Battery }`. The existing `AnimatedContent` slide keys its direction on `targetState != Home`, with one
`BackHandler(screen != Home)`. `HotspotConfirm` stays outside `AnimatedContent`.

**Battery screen**, top to bottom. A `LazyColumn` fills the screen. It uses `Modifier.consumeWindowInsets(padding)` as Home does, then horizontal `contentPadding = max(16.dp, (maxWidth − 640.dp)/2)` + `WindowInsets.displayCutout.only(Horizontal)` (the cutout padding comes from `WindowInsets.displayCutout.only(Horizontal).asPaddingValues()` read **after** `consumeWindowInsets`, so it's zero when already applied; landscape cutout golden; Activity gets the same).
**Loading:** the report rebuilds on screen open and after each flush. The last report stays visible during a rebuild. Only the first load (or the one after Clear) shows a centred M3 `LoadingIndicator` wrapped in `Modifier.semantics { contentDescription = battery_loading }`. Section titles use the
existing `SectionTitle` (`heading()`). Groups use `Group(n)` + `SegmentedListItem(colors = segmentColors())`.
1. `MediumFlexibleTopAppBar`: title `battery_title` (the subtitle may wrap to two lines in the expanded state; golden at fontScale 2 portrait), back button as on the Activity screen (`ic_back`), subtitle = duration and dates (`battery_subtitle_*`, `MeasureFormat` SHORT, `DateUtils.formatDateRange`), not a
   live region. Actions are icon-only, like Activity: Share (`battery_share`) and Clear (`battery_clear`). Both are disabled only before the first report has loaded or while there are no rows (not during later rebuilds; Share snapshots at click time). The switch row and status line always render, also during the first load; the indicator sits where the report goes. Every section is a `LazyColumn` item with a stable `key` (switch, status, panel, hero, compared, app, situations, more, foot), and a Compose test keeps focus on a situation row across a rebuild.
2. **Switch row:** headline `battery_measure`, supporting text is always the static `battery_measure_help`. Whole-row `Role.Switch`, `toggleableState`, `Switch(onCheckedChange = null)`, and
   **`stateDescription`** = "On, measuring", "On, paused while charging" and so on. No live region on the row.
   **Status line:** a **visible** `bodyMedium` line directly under the switch group, its own node with real bounds: a 18 dp status glyph (`contentDescription = null`) and the status text.
   It never ticks. It gets `liveRegion = Polite` only while the latest change was system-caused (charging, VPN off, CouldNotSave, auto-stop); after the user's own toggle the live region is
   dropped, so the toggle announcement isn't doubled. Compose test checks both cases, plus a TalkBack check in device acceptance (plug the charger in with the screen open). No `announceForAccessibility` (deprecated at API 36).
3. **CouldNotSave row** (only in that state), in the same group. The row itself is **not clickable**; only Retry is (Compose test: no row onClick, Retry ≥48 dp). In this state the status line sits **between** the switch row and this row (the group is split), shows `battery_status_problem` ("Problem") visibly, and its spoken form is `battery_status_problem_spoken` (Compose test asserts the announced text): `ic_error`, headline `battery_save_failed` in `error`, supporting `battery_save_failed_help`, and a `TextButton(battery_retry)` at 48 dp.
   It's trailing, or below the text at `fontScale >= 1.5`, and focusable on its own.
4. **State panel:** shown when off with no data (`battery_off_help` paragraph), when measuring without a ready hero (`battery_keep_title` + `battery_keep_body`), or when paused (body only: `battery_paused_vpn_body` or `battery_paused_charging_body`). It's a `Surface(surfaceContainerHigh, shapes.large)` (a message block like Home's StatusPanel), 16 dp padding, 24 dp `ic_battery` in `onSurfaceVariant`, `titleMedium` title. The **status line stays visible and in the tree in every state except Off** (it holds the only live region, so a pause is announced; Compose test: Measuring → PausedCharging is announced). The paused panel then shows only its body text, with no title, to avoid repeating the status. Panel: 16 dp padding, 24 dp `ic_battery` in `onSurfaceVariant`, and a `titleMedium` title
   (`battery_off_title` when off). Measuring-panel body: `battery_keep_body_noshare` when `store.shareOverHotspot` is false; else `battery_keep_body_level` on the level path; else `battery_keep_body`.
5. **Hero** (only when the fixed situation "screen off, hotspot, sharing in use" on the chosen uplink is ready): a `Surface(surfaceContainerHigh, shapes.extraLarge)` with 24 dp padding, like the StatusPanel.
   Inside: label `battery_hero_label` (`heading()`); a row with the value `NumberFormat` percent in `displayMediumEmphasized` (`tnum`) and `battery_per_hour` in `titleLarge` `onSurfaceVariant`,
   in a `FlowRow` with `Modifier.alignByBaseline()` on each child and `verticalArrangement = spacedBy(4.dp)` (at 200 % "an hour" wraps to its own line; golden); `bodyLarge` `battery_hero_range` (the percent formatter, no "points"); `bodyLarge` `battery_hero_watts_measured` (every interval used the energy counter) or `battery_hero_watts_about`;
   `bodyLarge` `battery_life` (when ≥1 %/h); and `bodyMedium` `onSurfaceVariant` `battery_hero_note`. The label stays its own `heading()` node; only the value/range/watts/life column is
   merged with `clearAndSetSemantics` (heading navigation lands on the label, test).
   **Percent formatting** everywhere: `NumberFormat.getPercentInstance(locale)` given `rate / 100`, with `RoundingMode.HALF_UP` and min and max fraction digits from the §3.7 precision rule (unit test: 4.25 %/h → "4.3%" in en-US).
   The same formatter is used for the range, `battery_rate_short`, transport shares and CPU. **Exception:** the comparison difference uses `NumberFormat.getNumberInstance(locale)` (also HALF_UP) with the same fraction rule; a difference that formats to zero shows `battery_compare_none` (test 0.04) (tests: 1.25 → "1.3" en-US, "1,3" de-DE).
6. **Compared** (`SectionTitle battery_compared_auto` or `_manual` by `store.proxySetup`): When ready, `bodyLarge` `battery_compare_more` or `_less`, then `bodyMedium` `battery_points_help` + `battery_compare_caveat` as its supporting text. `Group(if (bothReady) 2 else 3)` with `segmentedShapes(i, n)`, a row per side: headline (the side name), supporting on two lines: line 1 = duration or the not-ready reason; line 2 = `battery_side_detail` (sizes via `Formatter.formatShortFileSize`, as `SharingCard.kt:209` does), trailing the short rate or `battery_not_yet`. When a side isn't ready, a `SegmentedListItem` with `ic_info`,
   headline `battery_how_title`, supporting `battery_how_auto` or `battery_how_manual`, chosen by `store.proxySetup`.
7. **This app** (`SectionTitle battery_this_app`): one row, headline `battery_cpu_title`, trailing `battery_cpu_total` ("6% of one core"), with no split here (the split lives only in More detail).
   Under the This app title, `bodyMedium` `battery_this_app_note`.
8. **By situation** (`SectionTitle battery_situations`): the top 3 by hours, plus one "Other or short" row (Mixed time included). The headline is the fixed pattern `battery_situation` (screen · sharing · uplink),
   with hotspot added only when it isn't "on". When any situation is ready but the hero isn't, the measuring panel adds `battery_keep_other_ready`. Supporting = `battery_situation_detail` (plus `battery_highest` for the highest), trailing = the short rate or `battery_not_yet`. "Highest" goes in the supporting text, never as colour.
9. **More detail:** one clickable row (at least 56 dp, `Role.Button`, `stateDescription` Expanded/Collapsed (string `battery_expanded`/`_collapsed`) **and** `expand {}`/`collapse {}` actions, so API 29–35 announce the state, a chevron rotating ±90° by layout direction that snaps under reduced motion,
   `rememberSaveable`) that expands with `AnimatedVisibility(expandVertically(motionScheme.defaultSpatialSpec()), exit = shrinkVertically(fastSpatialSpec))`, with its rows in a `Group(n)` inside a single lazy item. `battery_d_transport` has headline `battery_transport_title` and the shares in supporting text.
   Rows: `battery_d_all`, `battery_d_vpn`, `battery_d_fwd`, `battery_d_other`, `battery_d_reconnects`, `battery_d_transport` (supporting `battery_transport_note`), `battery_d_awake`,
   `battery_d_proxy_mb`, `battery_d_cell_mb`, `battery_d_thermal`. The sampler thread CPU, USB MB and dropped coverage are **CSV only**.
10. Footnotes (`Footnote`): `battery_foot_measured`, `battery_foot_estimated`, `battery_foot_excluded`.
- All numbers use `NumberFormat`, durations use `MeasureFormat`, and spoken forms come from plurals. Rows with a custom spoken form use `clearAndSetSemantics`. No meaning is carried by colour (the `primary` tint on the Home icon while measuring is decoration; CouldNotSave on Home also tints that icon `error`). At
  `fontScale >= 1.5`, values stack under labels.
- **Clear dialog:** as on the Activity screen (`ic_delete`): title `battery_clear_title`, body `battery_clear_body` (with the hours) or `battery_clear_body_on`, buttons Clear / Cancel at 48 dp. Confirming closes the dialog at once, a second Clear while one runs is ignored (`AtomicBoolean` in `BatteryStore`), and Clear resets the screen to its first-load state; durations under a minute use the "less than a minute" plural. Confirm is disabled while a
  clear is running.
- **Share:** `battery_share` via `Intent.createChooser`. A `bodySmall` `battery_share_note` sits under the footnotes. `ACTION_SEND` `text/csv`, `EXTRA_STREAM` from the FileProvider (authority constant `${applicationId}.battery`, `cache-path`
  `battery-export/` only), `ClipData` + the read grant, and `EXTRA_TEXT` = the summary lines only. File: `battery-<yyyyMMdd-HHmmss>-<4 hex>.csv`, created exclusively. Exports older than 1 h are deleted first.

### 3.9 String table (display → spoken where different)
| id | display | spoken (if different) |
|---|---|---|
| battery_title | Battery drain | |
| battery_home_help | How fast the phone's battery drains while the VPN is on. | |
| battery_measure | Measure battery drain | |
| battery_measure_help | Checks the battery about once a minute while the phone is awake, the VPN is on and the phone is unplugged. Turns itself off 7 days after you turn it on. | |
| status (one set; Home / screen line / stateDescription) | Measuring: "Measuring" / "Measuring" / "On, measuring" · PausedVpnOff: "Paused: the VPN is off" / "Paused: the VPN is off" / "On, paused: the VPN is off" · PausedCharging: "Paused while charging" / same / "On, paused while charging" · CouldNotSave: "Couldn't save measurements. Open to retry" / "Problem" / "On, couldn't save" · AutoStopped: "Stopped after 7 days" / same / "Off, stopped after 7 days" · Off: battery_home_help / no status line (turning off is always the user's own action) / "Off" | |
| battery_subtitle_on / _stopped / _auto | %1$s measured · %2$s / Stopped · %1$s measured · %2$s / Stopped after 7 days · %1$s measured · %2$s | commas instead of ·, dates in words |
| (durations) | `MeasureFormat` SHORT, e.g. "3 h 20 min"; hours only above 3 h in battery_life | WIDE, e.g. "3 hours, 20 minutes" |
| battery_save_failed_help | Recent measurements are kept in memory for now. Older ones may be lost. | |
| battery_retry | Try saving again | |
| battery_off_help | Turn this on, then use the phone as usual with the VPN on. It records battery charge, temperature, screen on or off, the network type, the app's processor time and traffic totals. No sites, addresses or names of connected devices. Exports include your phone's model and Android version. | |
| battery_status_problem / _spoken | Couldn't save / Couldn't save measurements. Try saving again is below. | |
| battery_hero_spoken | Built by joining the **spoken forms** of its parts with ". ", leaving out unavailable ones: the value ("%1$s an hour"), `battery_hero_range` spoken, `battery_hero_watts_measured` or `_about` spoken, `battery_life`. Compose test for both the measured and the about case. | |
| battery_keep_other_ready | Other situations already have numbers below. The main number needs screen-off time while a device uses the proxy. | |
| battery_uplink_none / uplink_short_none | no network / No network | |
| battery_keep_body_noshare | Turn on Share over hotspot. The number appears after at least 30 minutes of screen-off time while a device uses it. | |
| battery_share_note | Includes the phone's model and Android version. No sites or device names. | |
| battery_keep_title / battery_keep_body / _body_level | Keep measuring / The number appears after at least 30 minutes of screen-off time while a device uses the hotspot proxy. / This phone reports battery in whole percent, so the number can take a few hours. | |
| battery_paused_vpn_body | Connect the VPN to keep measuring. | |
| battery_paused_charging_body | Unplug the phone to keep measuring. Time on the charger isn't counted. | |
| battery_off_title | Not measuring yet | |
| battery_hero_label | Whole phone · screen off, sharing in use · %1$s (title-case `uplinkShortName`) | Whole phone, with the screen off and sharing in use, on %1$s |
| battery_uplink_* (in sentences) | mobile data / Wi‑Fi / Ethernet / another network | |
| battery_per_hour | an hour | |
| battery_hero_range | Give or take %1$s an hour (sampling estimate) | Give or take %1$s an hour, a sampling estimate |
| battery_hero_watts_measured / _about | %1$s W from the battery (energy counter) / About %1$s W from the battery | … watts … |
| battery_life | At this rate, about %1$s from full to empty. | |
| battery_hero_note | Includes the hotspot and the cellular radio. | |
| battery_compared_auto / _manual | Sharing in use vs off / Sharing in use vs idle | |
| battery_points_help | Points are percent of the battery. | |
| battery_compare_more / _less | %2$s: %1$s points an hour more with sharing in use / %2$s: %1$s points an hour less with sharing in use (%2$s = the title-case uplinkShortName) | … percentage points … |
| battery_compare_caveat | Observed whole-phone drain. Traffic and network conditions may have differed; similar use on both sides gives a fairer comparison. | |
| side names | Sharing in use / Sharing off / Sharing idle | |
| battery_not_ready_short / _coarse | %1$s so far, needs %2$s (MeasureFormat) / Needs more battery use to be precise | |
| battery_how_title | How to compare | |
| battery_how_auto | Keep the screen off. Turn Share over hotspot off for an hour. The VPN stays on. If a device can't browse, turn its proxy off for that hour. | |
| battery_how_manual | Keep the screen off. Turn the proxy off on your laptop or iPhone for an hour, and keep Share over hotspot on. | |
| battery_this_app / battery_cpu_title | This app / Processor use | |
| battery_situations / battery_other_short | By situation / Other or short (includes mixed time) | |
| battery_situation | %1$s · %2$s · %3$s [· %4$s when the hotspot isn't on] (translator comment: screen · sharing · uplink) | commas |
| screen values | Screen off / Screen on | |
| sharing values | Sharing in use / Sharing idle / Sharing off | |
| hotspot values | Hotspot off / Hotspot state unknown | |
| uplink values | the existing `uplinkShortName` strings (`SharingCard.kt:117`); "No network" when none | |
| battery_d_all | All measured time: %1$s an hour over %2$s | |
| battery_d_vpn / _fwd / _other | VPN: at least %1$s of one core / Forwarding: about %1$s of one core / Other: about %1$s of one core | |
| battery_d_reconnects (plurals) | VPN reconnects: %1$d (%2$s an hour) | |
| battery_d_transport | (see battery_transport) | |
| battery_d_awake | Phone awake %1$s of the time | |
| battery_d_proxy_mb / _cell_mb | Through the proxy: %1$s an hour / Cellular data: %1$s an hour | |
| battery_d_thermal | Warmest: %1$s | |
| thermal values | normal / warm / hot / very hot (NONE / LIGHT / MODERATE / SEVERE and above) | |
| battery_rate_short | %1$s/h | %1$s an hour |
| battery_not_yet | Not yet | Not enough data yet |
| battery_more | More detail | |
| battery_transport_title / battery_transport | Connection type / Fast (DTLS) %1$s · backup (TLS) %2$s · unknown %3$s of connected time | |
| battery_transport_note | Inferred from the VPN log. The backup connection can use more battery when the network drops packets. | |
| battery_foot_measured | Measured: battery charge, the app's processor time and traffic. | |
| battery_foot_estimated | Estimated: ranges, and watts unless marked as from the energy counter. | |
| battery_foot_excluded | Not counted while the VPN is off or the phone is charging. In use means more than %1$s a minute through the proxy, until it stays under %2$s a minute for 3 minutes. (sizes via `Formatter.formatShortFileSize`) | |
| battery_expanded / _collapsed | Expanded / Collapsed | |
| battery_open | open details | |
| status glyphs | Measuring `ic_battery`, PausedCharging `ic_charging`, PausedVpnOff `ic_vpn`, CouldNotSave `ic_error`, AutoStopped `ic_info` (new vectors added where missing: `ic_battery`, `ic_charging`) | |
| battery_compare_none | No clear difference yet. The two ranges overlap; keep measuring both. | |
| battery_side_detail | Proxy %1$s/h · Cellular %2$s/h · Awake %3$s | proxy %1$s an hour, cellular %2$s an hour, awake %3$s of the time |
| battery_situation_detail / _no_w / battery_highest | %1$s · about %2$s W / %1$s (level path, no W) / Highest | commas |
| battery_loading | Loading battery data | |
| battery_this_app_note | This app can't read Android's per-app battery estimate. The comparison shows whole-phone drain with different sharing use. | |
| battery_cpu_total | %1$s of one core | %1$s of one processor core |
| battery_share / battery_clear | Share battery data / Clear battery data | |
| battery_clear_title / _body / _body_on | Clear battery data? / This removes %1$s of battery measurements saved on this device. / … Measuring continues. | |

### 3.10 Verification
- **Unit:** the interval, pgen and Mixed rules (A→B→A, plug flicker with a net decrease, conflated VPN flips, gap, boot/rec change, counter increase); windows and elapsed-time hysteresis; range
  (sleep-heavy, single-step, event-heavy then stable); readiness reasons; capacity; W on both paths with irregular intervals; awake share; the comparison's uplink selection and gating; `/proc` parsing
  (comm with spaces or parentheses, TID reuse, missing files); CpuMeter (two threads reading one stream, a stale gen discarded, gen 0 reads no clock, monotonic); the transport classifier and seeding
  (sequences built from the 9.21 strings); the throwing observer (`add` still stores and returns); CSV round trip, `#` header, partial tail, dedup after a failed-stop restart, rotation, buffer bounds and
  gap rows, sentinels; thread names ≤15 chars; reconnect episode counting.
- **Robolectric:** tether receiver flags plus the Wi-Fi filter; the recorder lifecycle (on/off/on, VPN start/stop, auto-stop, flush failure); store operations with recording off; the FileProvider path
  restriction; navigation (back from Battery returns Home; HotspotConfirm over Battery).
- **Compose/semantics:** the switch row's stateDescription, the status line's live region (system-caused only), no text change per minute, hero heading navigation, same-trace range at two sampling densities, retry focusable on its own and leaving measuring on.
- **Screenshot goldens:** off, measuring not ready, ready with comparison, CouldNotSave on Home (and at w891dp), landscape at 200 %, RTL.
- **VPN regression:** the existing suite, plus a diff audit showing only §3.3 items 1, 4 and 5 touch the VPN or log paths.
- **Device acceptance (PR test plan):**
  1. Overhead: uninstrumented 0.1.4 vs this build with measuring off vs on, under the same proxied download **and** a many-short-connections, header-heavy page load. Process CPU and throughput. Target < 0.5 % of a core extra.
  2. With measuring on: Wi-Fi ↔ mobile, a forced reconnect, a tunnel switch, a process restart, and a rejoin without Duo. All behave as in 0.1.4.
  3. Export and Clear during a reconnect.
  4. The comparison reaches ready on the real phone.
  5. A batterystats cross-check (§4).

### 3.11 Build notes from round 15 (both members: no blockers; these refine §3.1–3.10)
- **CouldNotSave copy:** status line glyph + "Couldn't save" (spoken `battery_status_problem_spoken`), row headline `battery_save_failed` = "Couldn't save measurements". No "Problem" anywhere. In that state the switch row is its own `Group(1)`, then the status line, then the error row as a second `Group(1)`.
- **Home CouldNotSave:** inline `ic_error` + error text only; the leading icon keeps its normal tint. Spoken: "Battery drain, on, couldn't save".
- **Sections by state:** off with no rows shows switch + panel + footnotes. Any state with rows shows the report sections. Paused shows the panel body above the report. AutoStopped is the same as off with data, plus the subtitle.
- `Footnote` gets a `Footnote(text: String)` overload (edit to `HomeScreen.kt`).
- **Subtitle:** counted time (unplugged, VPN on) rounded to 10 min. The semantics test is "no change between flushes".
- `battery_compare_more/_less` avoid plurals: "%1$s percentage points an hour more/less with sharing in use" (display and spoken).
- **Range:** per-window errors are combined **root-sum-square** (±2q per window). Test "same total, 1 vs 4 windows". The footnote says the range is a sampling estimate, not a confidence interval, and leaves out error in the estimated capacity.
- **Status line semantics:** in Measuring the line is hidden from accessibility (`hideFromAccessibility`), but the node and its live-region capability are kept for system-caused changes; it's a spoken node for Paused, CouldNotSave and AutoStopped.
- Labels use `·` throughout for display and commas when spoken: "Whole phone · screen off · sharing in use · %1$s".
- `battery_cpu_title` supporting: "Time the app's threads spent on the processor. Not the hotspot or radio." `battery_this_app_note` = "This app can't read Android's per-app battery estimate."
- The paused panel is body only, with no icon. Duplicate panel style text is ignored.
- Icons: `ic_battery`, `ic_charging` from Material Symbols Rounded (24 dp, weight 400), matching `res/drawable`. The More detail chevron is the existing `ic_chevron_right`.
- Extra golden: 360 dp × fontScale 2 (Compared rows).
- `battery_how_auto`: "Keep the screen off. Turn Share over hotspot off for an hour. The VPN stays on. Devices fall back to a direct connection, which can take a few seconds per site. If one can't browse, turn its proxy off for that hour."
- **Insets:** horizontal padding = `max(16.dp, (maxWidth − 640.dp)/2)` + the **unconsumed** cutout inset via `Modifier.windowInsetsPadding(WindowInsets.displayCutout.only(Horizontal))` after `consumeWindowInsets(padding)` (inset-aware, so it's not applied twice). Landscape cutout golden.
- **Deadline:** expiry is enforced by its own `postAtTime` on `battery-rec`, independent of sampling, and checked before any recorder start (including process recovery and VPN resume). Tests: expiry while charging, while the VPN is off (checked on next start), and after process recovery.

## 4. Ground truth over adb

The phone isn't attached now (`adb devices` is empty, and T3 device_list shows no Android target). When it is:
- A USB cable charges the phone and spoils the measurement. Prefer **wireless debugging** (Android 11+): Mac and phone on the same Wi-Fi (joining the phone's own hotspot works on many phones but is device-dependent),
  Developer options → Wireless debugging → pair (`adb pair <ip:port>`, then `adb connect <ip:port>`), unplug. Fallback: `adb shell dumpsys batterystats --reset` over USB, unplug for the window, plug back in and dump at once (the dump covers the unplugged window; ignore the minutes after re-plugging).
- `adb shell dumpsys batterystats --reset` at the start of a window. After it: `adb shell dumpsys batterystats --charged com.muunkk.connectanytime`
  (UID CPU, mobile radio, wakelocks, estimated mAh) and `adb shell dumpsys batterystats --checkin` for machine-readable numbers.
- `adb bugreport bugreport.zip`, then load it into Battery Historian (`docker run -p 9999:9999 gcr.io/android-battery-historian/stable:3.1`) for the
  timeline (hotspot, radio, CPU running, screen).
- `adb shell top -H -p $(adb shell pidof com.muunkk.connectanytime)` for live per-thread CPU (matches the recorder's categories);
  `adb shell dumpsys thermalservice`, `adb shell dumpsys netstats detail`.
- Compare: recorder %/h vs batterystats' discharge over the same window; recorder app CPU ms vs batterystats UID CPU; `--charged` for this UID as context. batterystats reads the same fuel gauge and uses its own attribution model, so treat it as a **cross-check**, not independent truth; CPU ms for the UID should match closely, and drain should match within the gauge's resolution.

## 5. Quick wins (follow-ups, measured before and after with this tool; **not** in this PR, except the thread naming)

| # | Change | Expected impact | Risk to VPN staying up |
|---|---|---|---|
| Q1 | Cheaper proxy copy path: larger buffers and fewer wakeups per byte, measured with the proxy CPU meter | Unknown until measured; scales with throughput | None to the VPN; moderate code risk in the proxy |
| Q2 | Rescan event-driven (tether broadcast + network callback + prefs) with a 15–30 s fallback instead of every 2 s | Low–moderate | None; the proxy picks up an address change more slowly (`stillServes` still guards each client) |
| Q3 | Cache the VPN `Network` and the phone's address set once per rescan instead of per connection | Low | None |
| Q4 | Disable OpenConnect compression if the log shows it's negotiated | Unknown, low–moderate | Low, but it touches the handshake: test against NYU |
| Q5 | Buffered head parsing (keep any bytes already read for the tunnel) | Low | None to the VPN; moderate code risk in the proxy |
| Q6 | Activity log: append instead of a full rewrite, and coalesce flushes to 5 s | Negligible | None |
| ✗ | Lengthen DPD/keepalive | Small | **High**: cellular NAT timeouts cause reconnects. Not recommended |

## 6. What to build first

1. This recorder + report + export (this PR), plus thread naming.
2. The user runs it for a day: an hour with the laptop on the proxy and an hour with it DIRECT, both screen off; then compares with `dumpsys batterystats`.
3. Then Q2/Q3 (safe), and Q1 if the proxy CPU share is a large part of the proxy-in-use cost. A split PAC is out of scope: all traffic stays on the VPN.
