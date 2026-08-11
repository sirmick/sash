# Passchain

Credential provider that allows you to use your USB/Bluetooth/NFC security key for passkeys/fido2/u2f authentication

## Download

[<img alt="Get it on IzzyOnDroid" src="./assets/IzzyOnDroid-badge.png" height=100 >](https://apt.izzysoft.de/fdroid/index/apk/s1m.hwfido2provider)

You can download the app from the [releases](https://codeberg.org/s1m/hw-fido2-provider/releases).

To verify the APK, use the following certificate fingerprints:

```
SHA-256 digest: 94bd36ba4648d38697cdfcf3385b6c0088ff3d551ce9fc16050ee080b9553ec5
SHA-1 digest: 1932dfa9af09f7f23056a61b56bfa6741449f8f0
```

## Configure

To enable the app, select it as a passkey provider at `Settings > Passwords & Passkeys > Preferred Service`. Once that is configured, you will be presented with a dialog when a passkey is requested, prompting you to use your security key device.

## Status

Today, it is possible to sign in and register with discoverable and non-discoverable, with and without PIN.

## Todo

- Add in-app QR code reader, to login on another device. You can use another QR code reader and open the link with Passchain until then.
- Allow default browser (in addition to the allow list)
- Support Bluetooth keys

## Technical details

This app exposes a [CredentialProviderService](https://developer.android.com/reference/androidx/credentials/provider/CredentialProviderService) that can be used to try login with a hardware security key. To avoid re-invented the wheel, it uses and embeds [microG](https://microg.org/) implementation of [the fido2 service](https://github.com/microg/GmsCore/tree/master/play-services-fido).

If you analyze the apk, you will find `com.google.gms.*` classes. This isn't from a Google's GMS library but a microG one. MicroG need to use the same class names to be a drop-in replacement of GMS libs.

## Translations

You can contribute to translations on [Weblate](https://translate.codeberg.org/projects/passchain/)
