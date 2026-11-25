# Reklamsız VPN

Reklamsız, takipçisiz, tek tuşla bağlanan Android VPN uygulaması.
Kendi sunucusu yoktur: [VPN Gate](https://www.vpngate.net/) gönüllü sunucu listesini
otomatik indirir ve OpenVPN ile bağlanır.

## Özellikler
- Tek tuşla bağlanma; seçili ülkedeki sunuculara önce paralel erişim testi yapılır,
  yanıt verenler en hızlıdan başlayarak denenir (en fazla 8)
- Sunucu reddederse, ulaşılamazsa ya da bağlandıktan sonraki 45 saniye içinde koparsa
  otomatik olarak sıradakine geçilir; sorunlu sunucular 15 dakika atlanır
- Ülke seçimi (bağlıyken ülkeye dokunmak doğrudan o ülkeye geçirir)
- Sunucu listesi önbelleğe alınır, site açılmasa da son liste kullanılır
- Sabit DNS (1.1.1.1 / 9.9.9.9), reklam ve analitik SDK'sı yok

## Kurulum (telefon)
Depodaki **Releases** bölümünden son `.apk` dosyasını indirip açın.

## Mimari
| Modül | İçerik |
|---|---|
| `app` | Kotlin + Jetpack Compose arayüz, VPN Gate istemcisi, bağlantı denetleyicisi |
| `openvpn` | [OpenVPN for Android (ics-openvpn)](https://github.com/schwabe/ics-openvpn) çekirdeği, kütüphane olarak |
| `third_party/ics-openvpn` | git alt modülü |

- `data/VpnGateRepository` – `vpngate.net/api/iphone/` CSV'sini indirir, önbelleğe alır
- `vpn/OpenVpnProfiles` – `.ovpn` dosyasını OpenVPN profiline çevirir (şifre uyumluluğu, DNS)
- `vpn/VpnController` – sunucuları sırayla dener, durumu `StateFlow` olarak yayınlar

## Derleme
GitHub Actions her `main` yüklemesinde APK'yı derler (`.github/workflows/apk-derle.yml`).
Yerelde: `git submodule update --init --recursive`, `swig` ve Gradle 9.7.1 kurulu olmalı, sonra
`gradle :app:assembleRelease` (Android Studio projeyi doğrudan açabilir).

## Uyarılar
- VPN Gate sunucularını gönüllüler işletir; sunucu sahibi şifrelenmemiş (HTTP) trafiği görebilir.
- `keystore/imza.jks` herkese açık ortak bir anahtardır. Play Store'a çıkmadan önce gizli bir anahtarla değiştirin.

## Lisans
GPLv2. OpenVPN for Android (Arne Schwabe) GPLv2 lisanslıdır ve bu proje onun türev çalışmasıdır;
ayrıntılar için `third_party/ics-openvpn/doc/LICENSE.txt`.
