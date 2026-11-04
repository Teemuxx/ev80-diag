# EV80-diag

Android app that reads the CAN bus of a **Maxus EV80** van through the OBD port and shows battery,
charging and driving data. It records charge sessions (and optionally drives) as raw CAN logs that
can be shared and analysed on a PC.

**Listen-only:** the app never sends anything on the CAN bus (no OBD requests). STN adapters run in
silent monitor mode, gs_usb adapters in listen-only mode; plain ELM327 clones are refused.

## Supported adapters

- OBDLink LX/MX (STN chip) over Bluetooth
- gs_usb / candleLight USB-CAN adapters over USB OTG

## Screens

Live · Tiles · Battery (cell heatmap and history) · Charging · Details (every decoded signal with its
CAN source) · Connect · Export & Debug · Settings. English and German.

## Build

Android Studio or `./gradlew :app:assembleDebug` (JDK 17+, Android SDK 36). The debug build installs
next to a release build as `io.github.ev80diag.dev`.

## Tests

`./gradlew :app:testDebugUnitTest`. Tests that replay real CAN logs need the private test-data folder
`../EV80-diag-testdata` (or `-Pev80.testdata=<dir>`); without it they are skipped.

Note: `connectedDebugAndroidTest` uninstalls the debug app afterwards and so deletes its recordings.

## Licence

Not decided yet.
