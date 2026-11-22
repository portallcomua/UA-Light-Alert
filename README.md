# UA Light Alert 4.3.0

WordPress plugin for electricity outage schedules, advertiser management, and advance alerts.

## Existing functionality preserved

- Shortcode: `[ua_light_schedule]`
- Region and group selection with local-device selection memory
- Outage schedule table and next-change countdown
- Advertiser records, targeting, impression and click statistics

## Configurable advance alerts

In **UA Light Alert → Налаштування сповіщень**:

- Select an alert lead time of 1, 5, or 10 minutes; the timer triggers at the selected threshold, not at a rounded-up minute.
- Set a separate MP3 for the upcoming outage and for power restoration.
- Choose an audio file from the WordPress Media Library (including a file uploaded from a computer or phone), or paste a direct MP3 URL.
- Preview each configured sound.

Existing installations that used the previous single `sound_url` setting retain that sound for both event types until new sounds are selected.

## PWA support

The plugin exposes a web app manifest and a root-scoped service worker. After a visitor chooses a region/group and presses **Показати графік та увімкнути сповіщення**, the browser can request notification permission and register the service worker. When an alert is generated while the schedule page is active, the plugin displays a system notification. Clicking that notification focuses the existing app/window or opens the page, which can launch the installed PWA when the platform supports it.

## Browser limitations

Browsers and operating systems do not permit websites to force-launch an installed PWA or force-play an arbitrary MP3 without an active page and user gesture. The PWA is not opened automatically at alert time. Version 4.3.0 includes server-side Web Push delivery, so supported browsers can show system notifications while the schedule page is closed; the operating system controls the notification sound.

## Background push notifications

Version 4.3.0 adds server-side Web Push subscriptions and minute-based schedule checks. A visitor selects a region/group and grants notification permission; the browser stores a push subscription, and the server sends an alert for the selected group even after the schedule page is closed.

Requirements and limitations:
- HTTPS, browser support for Push API/Service Workers, and notification permission are required.
- PHP OpenSSL must be enabled on the WordPress host.
- WordPress WP-Cron schedules checks once per minute. The default WP-Cron runner depends on site traffic; for reliable timing, configure a real server cron to request `wp-cron.php` every minute.
- When the page is open, the configured event-specific MP3 can play in the page. When the page/app is closed, the operating system controls the push-notification sound. Browsers do not allow a website to force-play an arbitrary MP3 in the background.
- Existing advertiser records and settings remain in the same WordPress options: `ua_light_ads` and `ua_light_settings`.

## Requirements

- WordPress with PHP 7.4 or newer
- HTTPS is required for service workers, notifications, and PWA installation (except localhost)
- A site icon configured in WordPress is recommended for the installed app icon

## Validation

The repository workflow checks PHP syntax and the service-worker JavaScript syntax on pushes and pull requests.


## Оновлення через GitHub Releases

Плагін перевіряє останній опублікований GitHub Release репозиторію
`portallcomua/UA-Light-Alert` і показує стандартне оновлення WordPress на сторінці
**Плагіни** та в **Консоль → Оновлення**. Перевірка кешується на 6 годин.

Після зміни номера `Version` у заголовку `ua-light-alert.php` та злиття змін у `main`
workflow `.github/workflows/release.yml` автоматично створює опублікований реліз
`vX.Y.Z` і прикріплює інсталяційний ZIP `ua-light-alert-X.Y.Z.zip`.
Updater завантажує саме цей інсталяційний ZIP і зберігає назву чинного каталогу
плагіна, зокрема `wp-content/plugins/ua-light-alert`, щоб оновлення відбувалося поверх
наявної інсталяції.

**Перший перехід:** версії 4.1.0 не мають GitHub-updater. Якщо на сайті ще встановлена
4.1.0, перший перехід на версію з updater потрібно виконати вручну через інсталяційний ZIP.
Після цього наступні опубліковані релізи з'являтимуться як звичайні оновлення в адмінці.
