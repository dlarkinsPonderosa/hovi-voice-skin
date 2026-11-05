# Hovi

A voice skin for [Kiosk Satellite](https://github.com/jxlarrea/kiosk-satellite). While it is picked, your assistant is Hovi, a little hovering robot, instead of the usual overlay.

![Hovi resting, listening, thinking and answering, with the conversation beside it](docs/hovi.webp)

Hovi isn't a still picture with a face on it: the whole robot is animated. It hovers and bobs, its arms sway and lift as it talks, and its head tilts, leans in to listen and turns towards where it's looking.

Its face is drawn live on its screen, like lit glass: lens eyes with glints that blink and glance around, and a mouth that opens into a little screen of level bars as it talks. Its light strips (ear rings, chest ring, hover ring, forearms and fingers) move with the conversation.

- **Resting:** it bobs gently, blinks and looks around.
- **Listening:** a sweep of light runs up its body and keeps flashing down its arms, its ear rings pulse, and it leans in with wide eyes.
- **Thinking:** it looks up and aside while light chases round its eyes and its chest ring spins.
- **Answering:** its mouth, chest light and hover ring move with the answer.
- **Muted:** with the wake word muted, its ear rings glow red, its eyes half close and it looks down.

## On screen

Hovi follows **Overlay mode** in Kiosk Satellite's **Voice Satellite > Appearance**:

- **Full screen:** Hovi fills the screen with the conversation beside it on a landscape screen, or below it on a portrait one. The conversation is Hovi's name, what you said, the answer, and a line for each thing it did, such as locking a door.
- **Docked:** a small bubble near the bottom: a little Hovi beside what you said and the answer, up to three lines. There's no name or list of actions; the latest action shows until the answer arrives.

The answer is always white, in a plain font chosen for reading. The other lines (the name, what you said and the actions) take the colour of Hovi's lights, lifted until it reads on black, so they change colour with the holiday. With **Accent font** on **Holiday**, they change font too.

## Appearance

![Robot and Robot Cat](docs/appearances.png)

**Robot** (the default) or **Robot Cat**, the same robot with cat ears, a floating tail and a cat's face: lidded eyes with slit pupils, a cat's mouth and fibre-optic whiskers.

## Holidays

![Hovi dressed for each holiday in turn](docs/holidays.webp)

The **Holiday** setting dresses Hovi up with outfits, props and light colours: New Year's, Valentine's, St Patrick's, Easter, Canada Day, Independence Day, Thanksgiving, Halloween, Remembrance, Christmas, Birthday. Some bring extras, such as fireworks, snow, confetti or falling leaves, and Halloween and Valentine's change its eyes.

Pick one by hand, or choose **Follow Home Assistant** and set **Holiday entity** to any entity whose state names a holiday, such as an `input_select` you change yourself or a template sensor that follows the calendar. A state that isn't one of those holidays shows the everyday robot. "Independence Day", "4th of July" and "July 4th" all work, and so do the other holidays' common spellings.

## Settings

| Setting | What it does | Changes |
| --- | --- | --- |
| Holiday | **Follow Home Assistant**, **None** (the everyday cyan robot), or a holiday: its outfit, props and light colours. | The robot, and the colour (and with Accent font on Holiday, the font) of the text around the answer |
| Holiday entity | The entity **Follow Home Assistant** reads. Leave it empty for the everyday robot. | Nothing unless Holiday is Follow Home Assistant |
| Frame rate | **Auto** (the default) starts at full speed and drops to 30, then 20 fps on a device that can't keep up. Each kiosk decides for itself, so it suits a fleet. **Full**, **30 fps** and **20 fps** fix the rate. | The robot's animation |
| Darkness | 0 to 100%: how dark the white shell is. Highlights stay bright. | The shell only, not the lights, face or holiday props |
| Appearance | **Robot** or **Robot Cat**. | The robot |
| Name | The name over the conversation, **Hovi** unless you give yours another. Leave it empty for none. | Full screen only |
| Answer font | **Kiosk Satellite** (its own font, the default) or **Atkinson Hyperlegible**, designed to read easily. | The answer, full screen and docked |
| Accent font | **Holiday** (each holiday's own font, and monospace on everyday days), **Monospace** all year, or **Same as the answer**. | Full screen: the name, what you said and the actions. Docked: what you said |

Hovi also follows the rest of **Voice Satellite > Appearance**: **Background** sets how much of the dashboard shows through on a full screen, **Text size** scales the conversation in both layouts, and with **Reactive activity bar** off, nothing moves with the voice.

## Requirements

- Kiosk Satellite 2026.10.30 or later
- The native Voice Satellite runtime turned on

## Installation

### Community Plugins (Recommended)

Hovi is listed in [Community Plugins](https://kiosksatellite.com/plugins/). Use the button below to open it in your Kiosk Satellite and install it.

[![Install in Kiosk Satellite](https://kiosksatellite.com/img/badges/plugin-add.svg)](https://kiosksatellite.com/redirect/plugin/?repository=dlarkinsPonderosa/hovi-voice-skin)

Or search for `Hovi` in **Settings > Plugin Manager > Browse plugins** in Kiosk Satellite.

Then, in **Voice Satellite > Appearance**, set **Assistant 1 skin** (or **Assistant 2 skin**) to **Hovi**.

### Manual

1. Open **Settings > Plugin Manager** in Kiosk Satellite and turn on **Enable Plugins**.
2. Choose **Add plugin**, paste `https://github.com/dlarkinsPonderosa/hovi-voice-skin` and choose **Preview**.
3. Review the plugin and choose **Trust and install**.
4. In **Voice Satellite > Appearance**, set **Assistant 1 skin** (or **Assistant 2 skin**) to **Hovi**.

## License

The code is [Apache-2.0](LICENSE). The robot artwork is not: see [ART-LICENSE.md](ART-LICENSE.md).

The fonts under `assets/fonts/` come from [Google Fonts](https://fonts.google.com), unmodified, each with its own licence beside it (SIL Open Font License 1.1, or Apache-2.0 for Chewy and Mountains of Christmas).

The SDK under `sdk/` and the build tools under `tools/` come from [kiosk-satellite-plugins](https://github.com/jxlarrea/kiosk-satellite-plugins) (Apache-2.0).
