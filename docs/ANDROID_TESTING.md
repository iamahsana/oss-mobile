# Android build and testing (phone-only workflow)

OSS is configured to build its debug APK on GitHub Actions. A PC is not required for the basic test cycle.

## How to get the APK

1. Open the repository: https://github.com/iamahsana/oss-mobile
2. Open the **Actions** tab.
3. Select **Android debug build**.
4. Open the newest run on the `main` branch.
5. Wait until the build job finishes successfully.
6. In **Artifacts**, download `oss-debug-apk`.
7. Extract the ZIP on the Android phone. Install `app-debug.apk`.

If GitHub shows a failed run, do not install an APK from that run. Share the failing step and its error log so it can be fixed first.

## First device test

Use a spare test stream or a private/unlisted destination first. Do not post a stream key in GitHub, screenshots, chat, or issue reports.

1. Open OSS.
2. Paste the complete RTMP/RTMPS publishing URL into the destination field.
3. Tap **Start screen stream**.
4. Android will show its screen-capture consent prompt. Review it and choose the option to share the screen.
5. Check the streaming destination's preview.
6. Return to OSS and tap **Stop streaming** when finished.

The first proof-of-concept currently captures the screen and uses the microphone as its audio source. Internal device audio, scene composition, overlays, reconnect handling, and secure local credential storage are not complete yet.

## Important limitations

- A successful APK build only proves that the source compiles; it does not prove stable streaming on every device.
- Screen capture is controlled by Android and requires explicit consent each session.
- Android may show a microphone permission prompt.
- Do not share your RTMP URL if it contains a private stream key.
- Test on the target phone before relying on OSS for a public livestream.
