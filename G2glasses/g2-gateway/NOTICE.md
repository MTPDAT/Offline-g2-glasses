# Notice

The G2 Bluetooth protocol code in this module (`protocol/`, `ble/`, `G2BleGateway.kt`) is a
Kotlin port of:

- **men-g2-ble-gateway** by Segment (gpsnmeajp), `mentraos/g2/` — MIT License
  (`licenses/men-g2-ble-gateway_LICENSE`). https://github.com/gpsnmeajp/men-g2-ble-gateway
- which ports **MentraOS** `G2.kt`
  (`mobile/modules/bluetooth-sdk/android/src/main/java/com/mentra/bluetoothsdk/sgcs/G2.kt`) —
  Apache License 2.0 (`licenses/MentraOS_LICENSE`). https://github.com/Mentra-Community/MentraOS

Only text display, connection management and diagnostic events were ported. Image rendering,
microphone audio, menus beyond one launch entry, and the gateway's HTTP, WebSocket and MCP servers
were left out. Android BLE details (MTU 247, high connection priority, one GATT operation at a
time, write retries with an 8 ms packet gap) follow G2.kt.
