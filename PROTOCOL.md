# Serial protocol (F5-3 profile)

Decoded from bus captures and confirmed against the vendor firmware's
test utilities (`McuTestActivity`, `A485McuUtil.send()`,
`F5SmallLightUtil`, `F5MessageSensorChange`). No vendor code is used;
frame byte constants are functional facts of the protocol.

Port: the profile's serial device (`/dev/ttyAS2` on the F5-3) at
115200 baud, raw. Frames are 7 bytes: `fc` … `fe`.

## Inbound frames (MCU → Android)

| Frame | Meaning |
|---|---|
| `fc 00 1c 00 00 00 fe` | Motion on |
| `fc 00 0c 00 00 00 fe` | Motion off (after ~30–60 s idle) |
| `fc 00 00 00 01 04 fe` | Button 4 (momentary) |
| `fc 00 00 00 01 05 fe` | Button 5 |
| `fc 00 00 00 01 06 fe` | Button 6 |
| `fc 00 00 00 01 07 fe` | Button 7 |
| `fc 00 00 00 01 08 fe` | Button 8 |
| `fc 00 00 a1 <01/00> 01 fe` | Relay 1 state |
| `fc 00 00 a2 <01/00> 02 fe` | Relay 2 state |
| `fc 00 00 a3 <01/00> 03 fe` | Relay 3 state |
| `fc 00 00 a4 00 00 fe` | Relay 4 marker — terminates a query batch (its id byte is `00`, not `04`; detect it via the `a4` code byte) |

The entity number IS the frame's id/code byte: relays 1–3, then
momentary buttons 4–8. A hold is one frame, indistinguishable from a
tap; a double-tap is two frames — detect either by timing in Home
Assistant if needed.

## Outbound frames (Android → MCU)

| Frame | Meaning |
|---|---|
| `fc 00 00 a1 01 01 fe` / `fc 00 00 a1 00 01 fe` | Relay 1 on / off |
| `fc 00 00 a2 01 02 fe` / `fc 00 00 a2 00 02 fe` | Relay 2 on / off |
| `fc 00 00 a3 01 03 fe` / `fc 00 00 a3 00 03 fe` | Relay 3 on / off |
| `fc 00 00 aa ff 00 fe` | Query relay states → batch `a1/a2/a3/a4` |
| `fc 00 1d 01 00 00 fe` | Night light on |
| `fc 00 1d 00 00 00 fe` | Night light off |
| `fc 00 1d 02 <00–64 hex> 00 fe` | Night light brightness 0–100 |

The real relay set frame's last byte is the relay id, identical to an
inbound press frame. The vendor test app's frames with a `00` last
byte are TEST frames: they change the MCU's reported/query state but
not the button LED or the actual relay. Serial writes are spaced
200 ms apart; back-to-back frames (e.g. night light on → brightness)
are not reliably acted on otherwise.

Relay 4 appears in every query batch but has no physical button and
is not exposed as a switch. The `/proc/vendor/relay_first/second/third`
files do **not** reflect MCU relay state.

## Press vs query batch

A virtual relay set produces NO immediate frame; the new state is
only visible via a subsequent query batch. A physical press produces
an immediate unsolicited single frame. The daemon publishes relay
state on every frame, but a press event only for frames that are not
part of a query batch: relay frames are held as candidates for 80 ms,
and a batch (a single burst terminated by the `a4` frame) discards
its candidates, while a frame still unclaimed after 80 ms was a real
press. This is why Home Assistant toggle automations must trigger on
the press event, not on relay state — otherwise a lights→relay sync
(external light change → relay switch) re-triggers the toggle and
loops.

A related MCU behaviour drives the per-relay momentary mode: any full
relay set activates the MCU's motion/backlight state invisibly (no
motion frame), and the eventual clear then arrives as a no-op, so
HA-driven state sync can make panel motion sensing miss real
walk-ins. In momentary mode nothing flips a relay remotely, so the
only activation left coincides with a person at the panel.

## Motion vs presence — research note

The F5 manual calls the sensor "Induction (Radar PIR)" and describes
waving a hand to trigger it; vendor code calls the frames
`F5MessageSensorChange`, not presence. Traditional PIR detects
movement only; true mmWave presence sensors detect a stationary
person (breathing, micro-motion). No F5 specification claims static
detection, and the observed behaviour (fires on walk-up, clears after
30–60 s idle) fits motion. The entity is therefore Motion with
`device_class: motion`, not Occupancy.

Decisive test still open: stand still in front of the panel for over
60 s — if the sensor stays on, it is static presence and the
class/name should flip to Occupancy/Presence; if it clears while you
are still there, motion is confirmed.
