Wizzeye
========

Wizzeye is a very basic open-source remote assistance solution for
[Iristick Smart Safety Glasses](https://iristick.com).  It allows a glass
wearer to stream the video from the glasses' cameras to the browser of a remote
observer.  Bidirectional audio communication is also enabled.

Under the hood, Wizzeye makes use of the [WebRTC](https://webrtc.org/)
technology to enable direct peer-to-peer communication between the
glass-wearer's phone and the remote observer's computer.


Using Wizzeye
--------------

A public instance of Wizzeye is available at <https://wizzeye.app/>.
The public instance can be used for evaluation and demonstration purposes.
For real-world usage, one must [install its own server](#installation).

Wizzeye allows a glass wearer to communicate with a remote observer.  The glass
wearer enters a room through the Wizzeye Android app installed on the phone
that is connected to the Iristick Smart Safety Glasses.  The remote observer
can join the room with any compatible web browser.  When both glass wearer and
remote observer have joined the room, video will be streamed from the Iristick
smart glasses to the web browser.

Multi-party conferencing is not supported.  Only one glass wearer and one
remote observer can be in the same room at any time.

On the phone, a room can be joined by entering its name in the start screen, or
by opening a `https://wizzeye.app/room-name` link with Wizzeye.  The remote
observer can open the same link in a browser.  Once a room is joined on the
phone, the room link can be shared (e.g., by mail, text message, chat, etc.) by
clicking on the share button in the top action bar.


Settings provisioning with QR code
-----------------------------------

The Wizzeye Android app provides a number of settings to configure the server
to connect to and the STUN and TURN infrastructure.  To ease the input, the app
can import settings from a QR code.  The QR code should contain a UTF-8-encoded
semicolon-separated list of `key=value` pairs.  The following table contains
the recognized keys.

Key             | Default value             | Description
----------------|---------------------------|----------------------------------
`video_quality` | `NORMAL`                  | Call quality (one of `LOW`, `NORMAL`, `HD`)
`server`        | `https://wizzeye.app`     | Signaling server
`stun_hostname` | `stun.l.google.com:19302` | STUN server
`turn_hostname` | *(empty)*                 | TURN server
`turn_username` | *(empty)*                 | Username for the TURN server
`turn_password` | *(empty)*                 | Password for the TURN server

For example, the following QR code data resets the settings to their default value:

```
video_quality=NORMAL;server=https://wizzeye.app;stun_hostname=stun.l.google.com:19302;turn_hostname=;turn_username=;turn_password=
```


App customization
------------------

A nice feature of Android is that apps can register themselves to handle
specific URLs.  Wizzeye makes use of this feature to catch URLs with pattern
`https://wizzeye.app/room-name` and open them with Wizzeye instead of the web
browser.  However the URL patterns cannot be changed at run time on the phone.
Hence, while the settings of the Wizzeye Android app allow to set another
server to connect to, the URL pattern will not be updated.

For this reason, it is recommended to build a customized app tailored for your
Wizzeye server installation.  The most important parameters have been gathered
together in one file: `app/custom.properties`.  Most people will only need to
edit this one file and [build the app](#building-the-android-app).

**Important:** whenever you make customizations to the app, you should change
the application ID to prevent conflicts with the official Wizzeye app in the
Play Store.  For example, if your company has a domain name `mycompany.com`,
you could use `com.mycompany.wizzeye` as application ID.


Installation
-------------

The following steps will guide you through installing Wizzeye on your own
server.  The steps assume a Linux server, but can be adapted for macOS or
Windows.

1. Download the pre-compiled ZIP package from [the releases page][latest].
2. Extract the package to some folder on your server.
   We will assume the package has been extracted to `/opt/wizzeye`.
3. You may want to change the listening address and port in `config.toml`, but
   the default value should work just fine.
4. Start the server using your system's init system.  If you are using systemd,
   a unit file is provided in
   [`server/config`](server/config/wizzeye-server.service).  Copy the file to
   `/etc/systemd/system` and then run:
   ``` shell
   useradd -rMU -s /usr/sbin/nologin -d /opt/wizzeye wizzeye
   systemctl daemon-reload
   systemctl enable --now wizzeye-server
   ```
5. Configure a reverse proxy to forward HTTPS requests to
   `http://127.0.0.1:9000` (or whatever you specified in the `config.toml`).
   Special handling may be required for the `/ws` path that serves a WebSocket
   connection.  For nginx, an example configuration is available in
   [`server/config`](server/config/nginx.conf).
6. You should also have a STUN and TURN infrastructure to facilitate the
   peer-to-peer video communication.  You can either install it yourself (e.g.,
   with [coturn][]) or use a hosted infrastructure.  In both cases, you will
   have to setup the Wizzeye Android app to use your infrastructure.
7. Enjoy!

Alternatively, an [official docker image][] is available to run in a managed
cloud infrastructure.  You can run it with
``` shell
docker run -p 8080:8080 wizzeye/wizzeye
```
This will expose an HTTP server on port 8080.  You will have to configure a
reverse proxy or load balancer that terminates HTTPS requests, as browsers do
not allow WebRTC over unencrypted HTTP.  You might also want to set up a
STUN/TURN infrastructure.

[latest]: https://github.com/wizzeye/wizzeye/releases/latest
[coturn]: https://github.com/coturn/coturn
[official docker image]: https://hub.docker.com/r/wizzeye/wizzeye


Building the server
--------------------

1. Make sure [Go 1.11][go] or later is installed.
2. Clone the Wizzeye repository.
3. Optionally set the `GOOS` and/or `GOARCH` environment variables to the
   operating system and CPU architecture you want to build for.
4. In the `server` folder, run
   ``` shell
   CGO_ENABLED=0 go build
   ```
5. The resulting binary will be available as `server` or `server.exe`.
   Copy it, along with the `webroot` folder, to your server.

Note that the Wizzeye server provides a download link for the Android app's
APK.  This APK should have been copied to `webroot/s/Wizzeye.apk`.

[go]: https://golang.org/dl/


Building the Android app
-------------------------

To build the Android app, you can either open the source repository in
[Android Studio][], or follow the following steps:

1. Make sure the [Android SDK][] is installed.
2. Clone the Wizzeye repository.
3. If you want to make a signed release build, create a file named
   `gradle.properties` in the repository root with the following content
   (modifying placeholders with information about your signing key):
   ```
   storeFile = /path/to/keystore.jks
   storePassword = PASSWORD_FOR_KEYSTORE
   keyAlias = ALIAS_OF_THE_KEY_IN_THE_KEYSTORE
   keyPassword = PASSWORD_FOR_THE_KEY
   ```
4. Run *one* of the following commands:
   ``` shell
   ./gradlew assembleDebug    # for a debug build (without signing key above)
   ./gradlew assembleRelease  # for a signed release build
   ```
5. The APK will be available under `app/build/outputs/apk/`

[Android Studio]: https://developer.android.com/studio/
[Android SDK]: https://developer.android.com/studio/#command-tools
