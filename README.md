# Synapse - Android Client 📱

Synapse projesinin mobil istemcisidir. macOS sunucusu ile haberleşerek ekran yansıtma, uzaktan kontrol ve veri senkronizasyonu sağlar.

## 🌟 Özellikler

- **Yüksek FPS Ekran Paylaşımı:** GPU tabanlı ImageReader ve HandlerThread optimizasyonu ile takılmayan görüntü aktarımı.
- **Erişilebilirlik Servisi Entegrasyonu:** Mac'ten gelen dokunmatik komutlarını `AccessibilityService` üzerinden işletim sistemine iletir.
- **Gerçek Zamanlı Pano Paylaşımı:** Arka planda pano değişimlerini izler ve Mac ile senkronize eder.
- **Sistem Olayları:** Arama, SMS ve uygulama bildirimlerini anında Mac'e raporlar.
- **Hızlı Eşleşme:** QR Kod tarama veya PIN kodu ile kolay kurulum.
- **Batarya Dostu:** Arka planda düşük güç tüketimi için optimize edilmiş foreground service.

## 🛠️ Teknoloji Yığını

- **Dil:** Kotlin
- **UI:** Jetpack Compose
- **Ağ:** Java Sockets (TCP), NSD (Network Service Discovery)
- **Multimedya:** MediaProjection API, ImageReader
- **Güvenlik:** RSA/AES Encryption, SHA-256

## 📲 Gereksinimler

- Android 9.0 (API 28) ve üzeri.
- Uzaktan kontrol için **Erişilebilirlik Servisi** izni.
- Ekran yansıtma için **Ekran Kaydı** izni.

## 🚀 Başlarken

1. Projeyi Android Studio ile açın.
2. Gerekli izinleri (Kamera, Bildirim, Telefon vb.) onaylayın.
3. Mac üzerindeki Synapse uygulamasını açıp QR kodu telefonunuzla taratın.

---
*Geliştiren: [serkantkn](https://github.com/serkantkn)*
