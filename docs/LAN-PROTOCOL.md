# Tina2S Local Wi-Fi protocol (recovered from the APK, no proxy)

Source: static decompilation of `PoloprintLocal_V1.1.0.apk` (`com.weedo3d.pplocal`),
main managed assembly `e051.dll` (unpacked from the XALZ `assemblies.blob`).
Decompiled sources live in `../decompiled/`. Everything below is read straight
out of the app's own IL — no mitmproxy, no live capture.

## TL;DR — it is just OctoPrint

The printer runs an **OctoPrint-compatible REST API over plain HTTP on port 80**,
with a couple of small custom extensions. To print you:

1. Find the printer's IP (UDP broadcast on **5090**, see below) — or just know it.
2. `POST /api/files/local` (multipart) to upload a `.gcode`.
3. `POST /api/files/local/<name.gcode>` `{"command":"select","print":true}` to start.
4. Poll `GET /api/printer` and `GET /api/job`.

**No API key, no auth header, no token.** `RestService` never sets `X-Api-Key` or
any auth; requests are anonymous. The only custom TLS handling is a hostname
verifier that trusts self-signed `CN=localhost` certs (see `HttpsClientHandlerService`),
i.e. the app is built to tolerate the printer's self-signed HTTPS if it uses it —
but the default endpoint is **`http://<ip>:80/`**.

## Base URL / endpoint construction

From `RestService.SetEndPointDirty` and the constructor:

```
EndPoint = "http://" + <IP> + ":" + <Port> + "/"
Port     = "80"          // default set in ctor
```

- `IP` may be a bare IP or a hostname; if it already starts with `http` it is used as-is.
- A trailing `/` is stripped from the host part, then `":" + port + "/"` is appended.
- All calls are `new Uri(EndPoint + location)`, e.g. `http://192.168.1.50:80/api/printer`.
- `HttpClient.Timeout` = 10 s (`SetHttpTimeout(10)`).

## Host discovery (UDP)

`PoloprintLocal.Services.UDPDeviceFinder`:

- Listens (UDP, `BeginReceive`) on **port 5090**, bound to `IPAddress.Any`.
- The printer **broadcasts** an ASCII datagram. The app parses space-separated
  tokens with `IDENT:` prefixes:
  - `WTIP:<ip>`     — printer IP
  - `WTNAME:<name>` — device name, e.g. `TINA2S` (used to pick the device profile)
  - `WTTYPE:<type>`
  - `WTID:<serial>` — serial number
- A device is only accepted if both `WTIP:` and `WTNAME:` are present.

Example datagram (format, not captured): `WTIP:192.168.1.50 WTNAME:TINA2S WTTYPE:... WTID:...`

For an OSS client you can either listen on UDP 5090 for the broadcast, or just let
the user type the IP. There is no mDNS in this app.

## Device profiles (`WOSDeviceManager`)

`ParseDeviceName(name)` maps the broadcast `WTNAME` to a profile:

| Name        | NozzleMax | BedMax | MinMotorVer | MinWifiVer |
|-------------|-----------|--------|-------------|------------|
| UNKNOWN     | 230       | 60     | 1.0.0       | 1.0.0      |
| TINA2S      | 230       | 60     | 1.3.0       | 1.3.0      |
| hephi3s     | 250       | 105    | 1.0.0       | 1.0.0      |
| D120        | 230       | 60     | 1.0.0       | 1.0.0      |
| X40         | 280       | 110    | 1.0.0       | 1.0.0      |
| F370        | 290       | 110    | 1.0.0       | 1.0.0      |
| F370X       | 290       | 110    | 1.0.0       | 1.0.0      |
| TINA2Lite   | 230       | 0      | 1.0.0       | 1.0.0      |

`hasCam`/`dualNozzle`/`hasWifiDisk` flags: only X40 (cam+disk+dual), F370/F370X (cam;
F370X dual). TINA2S has no cam.

## HTTP API surface (all under `EndPoint`)

### Info / status (GET)

| Method & path | Returns / used for |
|---|---|
| `GET api/version` | `WOSVersionInfo`: `api`, `server`, `server_hardware`, `text`, `device_version`, `device_hardware`. **Custom fields** `device_*` are an extension over stock OctoPrint; used for min-version check and brand. |
| `GET api/connection` | → `.current` → `WOSConnectionState`: `state`, `port`, `baudrate`, `printerProfile`. |
| `GET api/printer` | full printer state (`WOSFullPrinterState`). |
| `GET api/printer?exclude=temperature,sd` | `WOSPrinterState` (`.state` → `text`, `flags`). |
| `GET api/printer/tool` | `WOSTemperatureState` (tool temps). |
| `GET api/printer/endstop` | `WOSEndstopData`. |
| `GET api/printer/option` | `WOSOptionData`. |
| `GET api/printeroption` | `WOSOptionData`. |
| `GET api/printer/params` | `WOSParamsData`. |
| `GET api/printerprofiles` | profiles. |
| `GET api/job` | `WOSJobInfo` (`.job`) + `WOSJobProgress` (`.progress`). |
| `GET api/files` | file list (→ `WOSFolder` tree). |
| `GET api/files/<path>` | single file/folder. |
| `GET update` | update status (`WOSUpdatStatusData`). |
| `GET log/bootlog` `log/errorlog` `log/printlog` `log/summary` | printer logs. |

### Job control (POST `api/job`, JSON `{command, action?}`)

`WOSJobTracker`:
- `{"command":"start"}` — StartJob
- `{"command":"cancel"}` — CancelJob
- `{"command":"restart"}` — RestartJob
- `{"command":"pause","action":"pause"}` — PauseJob
- `{"command":"pause","action":"resume"}` — ResumeJob

409 = "current job state incompatible".

### Files / printing (`WOSFileTracker`)

- **Upload**: `POST api/files/local` (or `api/files/<location>`), `multipart/form-data`
  with boundary `----Boundary<hex ticks>`. Fields, in order:
  - `filename` (StringContent = basename)
  - the file part (ByteArrayContent, `application/octet-stream`)
  - `path` (StringContent = online folder path)
  - `select` (`"true"`/`"false"`)
  - `print`  (`"true"`/`"false"`)
  Response: 409 if file in use by slicer/printer, 404 not found.
- **Select + print**: `POST api/files/<location>/<path>` JSON `{"command":"select","print":true}`.
  (409 = printer probably not operational.)
- **Slice on printer**: `POST api/files/<location>/<path>` JSON
  `{"command":"slice","slicer":..,"position":{"x":..,"y":..},"select":..,"print":..,
    "profile":..,"gcode":.., <extra profile params>}`.
  Errors: 400 unsupported, 404 not found, 409 busy/not operational, 415 not an STL.
- **Copy**: `{"command":"copy","destination":..}`.
- **Delete**: `DELETE api/files/<location>/<path>`.
- **Create folder**: `POST /api/files/local` multipart with `foldername` + `path`.
- **Format SD/disk**: `POST api/local/format` body `"format"`.

### Printer control (`WOSPrinterTracker`) — all POST JSON

| Endpoint | Body | Meaning |
|---|---|---|
| `api/printer/printhead` | `{"command":"home","axes":[..]}` | home axes |
| `api/printer/printhead` | `{"command":"jog","absolute":bool,"x":..,"y":..,"z":..,"speed":..}` | jog |
| `api/printer/printhead` | `{"command":"feedrate","feedrate":<n>}` | feedrate |
| `api/printer/tool` | `{"command":"target","targets":{"tool0":<temp>}}` | set hotend temp |
| `api/printer/tool` | `{"command":"offset","offsets":{"tool0":<n>}}` | temp offset |
| `api/printer/tool` | `{"command":"select","tool":"tool0"}` | select tool |
| `api/printer/tool` | `{"command":"extrude","amount":<mm>}` | extrude |
| `api/printer/tool` | `{"command":"flowrate","flowrate":<n>}` | flow rate |
| `api/printer/bed`  | `{"command":"target","target":<temp>}` | set bed temp |
| `api/printer/bed`  | `{"command":"offset","offset":<n>}` | bed offset |
| `api/printer/command` | `{"command":"<gcode>"}` | raw G-code |
| `api/printer/reset` | (POST) | reset printer |

`SendUpdateCommand(device,url)` posts to `update`.

## JSON response shapes (from model classes)

- **`WOSJobInfo`** (from `job`): `estimatedPrintTime` (int sec), `filament{length,volume}`,
  `file{...}` → `WOSFile`.
- **`WOSJobProgress`** (from `progress`): `completion` (double %), `filepos`,
  `printTime`, `printTimeLeft`.
- **`WOSFile`**: `name`, `path`, `type`, `hash`, `size`, `date`, `origin`,
  `refs{resource,download}`, `gcodeAnalysis{estimatedPrintTime,filament{length,volume}}`,
  plus custom `printFailure`, `printSuccess`, `printLastDate`, `printLastSuccess`.
- **`WOSConnectionState`** (`.current` of `api/connection`): `state`, `port`,
  `baudrate`, `printerProfile`.
- **`WOSPrinterState`** (`.state` of `api/printer`): `text`, `flags`.
- **`WOSVersionInfo`**: `api`, `server`, `server_hardware`, `text`, `device_version`,
  `device_hardware`.

State string matching (`RestService.UpdateServerState`) — the printer's `state.text`
contains one of: `Operational`, `Printing`, `Pausing`, `Paused`, `Cancelling`,
`ready` (→ PrintEnd), `Connecting`.

## VERIFIED against the live Tina2S (2026-11-08)

Device found at **`http://10.0.0.96:80/`** (no auth). Everything below is a real
captured response.

`GET /api/version`:
```json
{"api":"1.0","server":"1.4.0","server_hardware":"22ESP32WIFIV2","text":"TINA2S",
 "device_version":"1.4.6","device_hardware":"22WFV6","mem":98424,"log":true}
```
(extra custom fields: `device_version`, `device_hardware`, `mem`, `log`.)

`GET /api/connection`:
```json
{"current":{"state":"Operational","port":"com1","baudrate":115200,"printerProfile":"TINA2S"}}
```

`GET /api/printer` (also `/api/printer/tool`):
```json
{"sd":{"ready":true},
 "state":{"flags":{"cancelling":false,"closedOrError":false,"error":false,
   "finishing":false,"operational":true,"paused":false,"pausing":false,
   "printing":false,"ready":true,"resuming":false,"sdReady":true},
  "text":"Operational"},
 "temperature":{"tool0":{"actual":21,"offset":0,"target":0},
                "bed":{"actual":20,"offset":0,"target":0}}}
```

`GET /api/job`:
```json
{"job":{"file":{"date":0,"name":"NONE","path":"NONE","origin":"NONE","size":0},
 "filament":{"length":0,"volume":0},"estimatedPrintTime":0},
 "progress":{"completion":0,"filepos":0,"printTime":0,"printTimeLeft":0},
 "state":"Operational"}
```

`GET /api/printerprofiles`:
```json
{"profiles":{"TINA2S":{"id":"TINA2S","name":"TINA2S","model":"MyPrinter",
 "default":true,"current":true,"resource":"http://www.weedo3d.com",
 "volume":{"formFactor":"rectangular","origin":"lowerleft",
   "width":100,"depth":100,"height":100},
 "heatedBed":true,"heatedChamber":false,
 "axes":{"x":{"speed":3000},"y":{"speed":3000},"z":{"speed":200},"e":{"speed":300}},
 "extruder":{"count":1,"offsets":[{"x":0,"y":0}]}}}}
```

`GET /api/printer/params` (Marlin M-codes):
```json
{"params":["Z-Probe Offset (mm):","M851 X0.00 Y0.00 Z-2.30",
 "Maximum feedrates (units/s):","M203 X300.00 Y200.00 Z20.00 E50.00",
 "Maximum Acceleration (units/s2):","M201 X1000.00 Y600.00 Z400.00 E200.00",
 "PID settings:","M301 P25.30 I2.20 D72.50","Power-Loss Recovery:","M413 S0"]}
```

`GET /api/printer/endstop`:
```json
{"endstop":{"xmin":false,"xmax":false,"ymin":false,"ymax":false,
 "zmin":false,"zmax":false,"runout0":false,"runout1":false}}
```

### Live-validated upload + print + delete

1. **Upload** `POST /api/files/local` (multipart `filename` + `file` + `path` +
   `select` + `print`) → **HTTP 201**:
   ```json
   {"done":"true","files":{"local":{"name":"goose_probe.gcode",
     "origin":"local","path":"goose_probe.gcode"}}}
   ```
   The file is written **directly to the SD card**, renamed to **8.3**
   (`goose_probe.gcode` → `GOOSE_~1.GCO`). It then appears in `GET /api/files`
   with `"origin":"sdcard"`.
2. **Select / print** — the correct path on this firmware is
   **`POST /api/files/sdcard/<8.3 name>`** → **HTTP 204**:
   ```json
   {"command":"select","print":false}
   ```
   NOTE: the app's `local` location (`api/files/local/<name>`) returns **404
   "This URI does not exist"** on the Tina2S — use **`sdcard`**. (`print:true`
   starts the job.)
3. **Delete** `DELETE /api/files/sdcard/<8.3 name>` → **HTTP 204** (file removed).
4. **Not present on Tina2S**: `GET /api/printeroption` → 404 "This URI does not
   exist"; `POST api/files/sdcard/<f>` with `{"command":"delete"}` → 400.
5. No UDP `WTIP:` broadcast was observed even with the printer on — discovery may
   only fire on demand. The API is reachable directly by IP regardless.

### Practical flow that works

```bash
IP=10.0.0.96
# upload (auto-renamed to 8.3 on SD)
curl -F "filename=part.gcode" -F "file=@part.gcode;type=application/octet-stream" \
     -F "path=" -F "select=false" -F "print=true" http://$IP/api/files/local
# or upload then select+print by the 8.3 name:
curl -X POST -H 'Content-Type: application/json' \
     -d '{"command":"select","print":true}' http://$IP/api/files/sdcard/PART_~1.GCO
# monitor
curl http://$IP/api/printer ; curl http://$IP/api/job
```

## Auth / security notes

- No API key. Requests are anonymous plain HTTP.
- `HttpsClientHandlerService` exists only so the app can accept the printer's
  self-signed cert (custom `IHostnameVerifier` accepting `CN=localhost`, and a
  `ServerCertificateCustomValidationCallback` that accepts any cert with issuer
  `CN=localhost` or zero `SslPolicyErrors`). It is not used for auth.

## Minimal OSS client flow (Python)

```python
import requests
IP = "192.168.1.50"; BASE = f"http://{IP}:80"
# 1. sanity check
print(requests.get(f"{BASE}/api/version").json())
# 2. upload + start
with open("part.gcode","rb") as f:
    requests.post(f"{BASE}/api/files/local",
        files={"file": ("part.gcode", f, "application/octet-stream")},
        data={"path":"", "select":"true", "print":"true"})
# 3. poll
print(requests.get(f"{BASE}/api/printer").json())
print(requests.get(f"{BASE}/api/job").json())
```

(`select`/`print` may also be sent as the `command:select` JSON afterwards if the
firmware ignores the multipart flags — the app does both depending on code path.)

## Relationship to the UART protocol

This LAN protocol is the **phone/PC ↔ ESP32 network module** half. The other half,
**ESP32 ↔ mainboard (WEEDO controller)**, is documented from open firmware in
`../firmware/tina2s_wifi/` (framing `0xEB … CRC8 … 0x0A`, register map in
`WIFI_ADDR.h`). The ESP32 network module firmware itself is closed-source, but it
simply exposes this OctoPrint-compatible HTTP API on the LAN and bridges the
commands down to the mainboard over UART.

## Open questions / notes

- **Resolved:** file paths on the Tina2S use `api/files/sdcard/<8.3 name>`, not the
  app's `local`. Upload auto-renames to 8.3 on the SD card.
- UDP `WTIP:` broadcast on 5090 was **not observed** with the printer on — it may
  only be emitted on demand or periodically; direct-by-IP works fine.
- `select`/`print` can be given either as multipart fields on upload or via the
  follow-up JSON select. Upload with `print:true` in the multipart is simplest.
- Camera stream `:8080/stream` is referenced but only X40/F370 have `hasCam`.
