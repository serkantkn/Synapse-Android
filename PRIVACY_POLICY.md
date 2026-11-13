# Synapse Gizlilik Politikası

**Son Güncelleme:** 2 Mayıs 2026

Merhaba! Synapse'i kullandığınız için teşekkürler. Bu uygulama, Android cihazınız ile macOS bilgisayarınız arasında bir köprü kurmak amacıyla geliştirildi. Gizliliğinizin sizin için ne kadar önemli olduğunu biliyoruz, bu yüzden Synapse'i tasarlarken "önce gizlilik" ilkesini benimsedik.

Bu belge, uygulamayı kullanırken hangi verilere eriştiğimizi ve bu verilerle ne yaptığımızı (aslında ne yapmadığımızı) açıklar.

### 1. Veri Toplama ve Depolama
Synapse, **hiçbir kişisel verinizi merkezi bir sunucuya göndermez veya depolamaz.** Tüm veri trafiği, yalnızca sizin yerel ağınız (Wi-Fi) üzerinden, sizin kontrolünüzdeki cihazlar (Telefonunuz ve Mac'iniz) arasında gerçekleşir.

- **Analiz verileri:** Uygulama kullanım alışkanlıklarınızı takip etmiyoruz.
- **Bulut depolama:** Verileriniz herhangi bir bulut servisine yedeklenmez.

### 2. Hassas İzinler ve Kullanım Amaçları
Uygulamanın çalışması için gerekli olan hassas izinler yalnızca şu amaçlarla kullanılır:

- **Erişilebilirlik Servisi (Accessibility Service):** Bu izin, Mac bilgisayarınız üzerinden telefonunuza gönderdiğiniz tıklama ve kaydırma hareketlerini simüle etmek için kullanılır. Bu servis üzerinden herhangi bir kişisel bilginiz okunmaz veya kaydedilmez.
- **Ekran Kaydı (Media Projection):** Telefonunuzun ekranını Mac'inize yansıtmak için gereklidir. Görüntü verisi doğrudan ve şifreli olarak yerel ağınız üzerinden bilgisayarınıza aktarılır.
- **Bildirim Erişimi:** Telefonunuza gelen bildirimleri Mac ekranınızda görebilmeniz için gereklidir.
- **Telefon ve SMS İzinleri:** Gelen aramaları ve SMS bildirimlerini Mac'inize raporlamak ve Mac üzerinden arama/SMS başlatabilmenizi sağlamak için kullanılır.

### 3. Veri Güvenliği (Şifreleme)
Cihazlarınız arasındaki tüm iletişim, **RSA ve AES-256** standartlarında uçtan uca şifrelenir. Bu, yerel ağınızdaki başka birinin veya bizim, aktarılan verileri görmemizin teknik olarak imkansız olduğu anlamına gelir.

### 4. Üçüncü Taraf Paylaşımı
Verileriniz asla satılmaz, kiralanmaz veya reklam ağları dahil hiçbir üçüncü tarafla paylaşılmaz. Synapse içerisinde herhangi bir reklam kütüphanesi veya izleyici (tracker) bulunmaz.

### 5. Değişiklikler
Bu gizlilik politikasını zaman zaman güncelleyebiliriz. Önemli bir değişiklik olması durumunda uygulama içerisinden sizi bilgilendireceğiz.

### İletişim
Gizlilikle ilgili herhangi bir sorunuz olursa bana GitHub üzerinden ulaşabilirsiniz: [serkantkn](https://github.com/serkantkn)

---
*Bu uygulama bir geliştirici projesi olarak, kullanıcı güvenliği ve şeffaflık ön planda tutularak oluşturulmuştur.*
