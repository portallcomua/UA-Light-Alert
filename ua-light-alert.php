<?php
/**
 * Plugin Name: UA Light Alert
 * Description: Графіки відключень, CRM рекламодавців та сповіщення про зміни електропостачання.
 * Version: 4.4.4
 * Plugin URI: https://uaserver.pp.ua/svitlo/
 * Update URI: https://github.com/portallcomua/UA-Light-Alert
 * Author: UAServer
 * Author URI: https://uaserver.pp.ua/
 * Text Domain: ua-light-alert
 */

if (!defined('ABSPATH')) {
    exit;
}

require_once plugin_dir_path(__FILE__) . 'includes/class-ua-light-alert-push.php';

final class UA_Light_Alert {

    private $option_key = 'ua_light_ads';
    private $settings_key = 'ua_light_settings';

    public function __construct() {
        add_shortcode('ua_light_schedule', [$this, 'render_frontend']);
        // Friendly Ukrainian alias; the legacy shortcode remains fully supported.
        add_shortcode('grafik_svitla', [$this, 'render_frontend']);

        add_action('admin_menu', [$this, 'add_admin_menu'], 99);
        add_filter('plugin_action_links_' . plugin_basename(__FILE__), [$this, 'add_plugin_action_links']);
        add_action('admin_init', [$this, 'handle_admin_actions']);
        add_action('wp_head', [$this, 'print_pwa_manifest']);
        add_action('template_redirect', [$this, 'serve_pwa_resources']);

        add_action('wp_ajax_ua_track_ad', [$this, 'track_ad_click']);
        add_action('wp_ajax_nopriv_ua_track_ad', [$this, 'track_ad_click']);

        add_action('wp_ajax_ua_get_ads', 'ua_light_get_ads');
        add_action('wp_ajax_nopriv_ua_get_ads', 'ua_light_get_ads');
    }


    public function print_pwa_manifest() {
        $page_id = get_queried_object_id();
        $start_page = $page_id ? get_permalink($page_id) : '';
        if (!$start_page) {
            $start_page = home_url('/');
        }
        $manifest_url = add_query_arg(
            ['ua_light_manifest' => '1', 'ua_light_start' => $start_page],
            home_url('/')
        );
        echo '<link rel="manifest" href="' . esc_url($manifest_url) . '">' . "\n";
        echo '<meta name="mobile-web-app-capable" content="yes">' . "\n";
        echo '<meta name="apple-mobile-web-app-capable" content="yes">' . "\n";
        echo '<meta name="theme-color" content="#ff6b00">' . "\n";
    }

    public function serve_pwa_resources() {
        if (isset($_GET['ua_light_manifest'])) {
            $icon_192 = get_site_icon_url(192);
            $icon_512 = get_site_icon_url(512);
            $start_url = home_url('/');
            $requested_start = isset($_GET['ua_light_start']) ? esc_url_raw(wp_unslash($_GET['ua_light_start'])) : '';
            $referrer = !empty($_SERVER['HTTP_REFERER']) ? esc_url_raw(wp_unslash($_SERVER['HTTP_REFERER'])) : '';
            $candidate = $requested_start ? $requested_start : $referrer;
            if ($candidate
                && wp_parse_url($candidate, PHP_URL_HOST) === wp_parse_url(home_url('/'), PHP_URL_HOST)
                && 0 === strpos((string) wp_parse_url($candidate, PHP_URL_PATH), (string) wp_parse_url(home_url('/'), PHP_URL_PATH))) {
                $start_url = $candidate;
            }
            $icons = [];
            if ($icon_192) {
                $icons[] = ['src' => $icon_192, 'sizes' => '192x192', 'type' => 'image/png', 'purpose' => 'any'];
            }
            if ($icon_512) {
                $icons[] = ['src' => $icon_512, 'sizes' => '512x512', 'type' => 'image/png', 'purpose' => 'any'];
            }
            $manifest = [
                'name' => get_bloginfo('name') . ' — UA Light Alert',
                'short_name' => 'UA Light Alert',
                'description' => 'Графіки та сповіщення про відключення електроенергії',
                'start_url' => $start_url,
                'scope' => home_url('/'),
                'display' => 'standalone',
                'background_color' => '#ffffff',
                'theme_color' => '#ff6b00',
                'icons' => $icons
            ];
            nocache_headers();
            header('Content-Type: application/manifest+json; charset=utf-8');
            echo wp_json_encode($manifest);
            exit;
        }

        if (isset($_GET['ua_light_sw'])) {
            $file = plugin_dir_path(__FILE__) . 'assets/ua-light-alert-sw.js';
            if (!is_readable($file)) {
                status_header(404);
                exit;
            }
            nocache_headers();
            header('Content-Type: application/javascript; charset=utf-8');
            header('Service-Worker-Allowed: ' . wp_parse_url(home_url('/'), PHP_URL_PATH));
            readfile($file);
            exit;
        }
    }

    private function default_settings() {
        return [
            'notice_minutes' => 5,
            'sound_url'      => '',
            'sound_off_url'  => '',
            'sound_on_url'   => '',
            'voice_enabled'  => 1,
            'voice_volume'   => 80,
            'sound_volume'   => 80,
            'alert_repeat'   => 0,
            'quiet_start'    => '',
            'quiet_end'      => '',
        ];
    }

    private function get_settings() {
        $settings = get_option($this->settings_key, []);
        $settings = wp_parse_args(is_array($settings) ? $settings : [], $this->default_settings());
        // Backward compatibility: the old single sound is used for both event types.
        if (empty($settings['sound_off_url']) && !empty($settings['sound_url'])) {
            $settings['sound_off_url'] = $settings['sound_url'];
        }
        if (empty($settings['sound_on_url']) && !empty($settings['sound_url'])) {
            $settings['sound_on_url'] = $settings['sound_url'];
        }
        return $settings;
    }

    /* ---------------------------------------------------------
     * ADMIN
     * ------------------------------------------------------ */

    public function add_admin_menu() {
        // Main menu entry.
        add_menu_page(
            'Графік світла',
            'Графік світла',
            'manage_options',
            'ua-light-alert',
            [$this, 'render_admin_page'],
            'dashicons-lightbulb',
            30
        );

        // Also expose it under Settings so it remains easy to find in the dashboard.
        add_submenu_page(
            'options-general.php',
            'Налаштування графіка світла',
            'Графік світла',
            'manage_options',
            'ua-light-alert',
            [$this, 'render_admin_page']
        );
    }

    public function add_plugin_action_links($links) {
        $settings_link = '<a href="' . esc_url(admin_url('admin.php?page=ua-light-alert')) . '">Налаштування</a>';
        array_unshift($links, $settings_link);
        return $links;
    }

    public function handle_admin_actions() {
        if (!current_user_can('manage_options')) {
            return;
        }

        if (isset($_POST['ua_save_settings'])) {
            check_admin_referer('ua_settings_action');

            $notice_minutes = absint($_POST['notice_minutes'] ?? 5);
            if (!in_array($notice_minutes, [1, 5, 10], true)) {
                $notice_minutes = 5;
            }
            $voice_volume = isset($_POST['voice_volume']) ? absint($_POST['voice_volume']) : 80;
            $voice_volume = min(100, $voice_volume);
            $sound_volume = isset($_POST['sound_volume']) ? min(100, absint($_POST['sound_volume'])) : 80;
            $alert_repeat = isset($_POST['alert_repeat']) ? absint($_POST['alert_repeat']) : 0;
            $alert_repeat = min(2, $alert_repeat);
            $quiet_start = sanitize_text_field(wp_unslash($_POST['quiet_start'] ?? ''));
            $quiet_end = sanitize_text_field(wp_unslash($_POST['quiet_end'] ?? ''));
            if (!preg_match('/^(?:[01]\d|2[0-3]):[0-5]\d$/', $quiet_start)) $quiet_start = '';
            if (!preg_match('/^(?:[01]\d|2[0-3]):[0-5]\d$/', $quiet_end)) $quiet_end = '';
            $settings = [
                'notice_minutes' => $notice_minutes,
                'sound_off_url'  => esc_url_raw(wp_unslash($_POST['sound_off_url'] ?? '')),
                'sound_on_url'   => esc_url_raw(wp_unslash($_POST['sound_on_url'] ?? '')),
                'voice_enabled'  => isset($_POST['voice_enabled']) ? 1 : 0,
                'voice_volume'   => $voice_volume,
                'sound_volume'   => $sound_volume,
                'alert_repeat'   => $alert_repeat,
                'quiet_start'    => $quiet_start,
                'quiet_end'      => $quiet_end,
            ];

            update_option($this->settings_key, $settings);

            wp_safe_redirect(admin_url('admin.php?page=ua-light-alert&msg=settings_saved'));
            exit;
        }

        if (isset($_POST['ua_save_ad'])) {
            check_admin_referer('ua_save_ad_action');

            $ads = get_option($this->option_key, []);
            if (!is_array($ads)) {
                $ads = [];
            }

            $ad_id = isset($_POST['ad_id']) ? absint($_POST['ad_id']) : -1;

            $ad_data = [
                'name'         => sanitize_text_field(wp_unslash($_POST['ad_name'] ?? '')),
                'email'        => sanitize_email(wp_unslash($_POST['ad_email'] ?? '')),
                'contacts'     => sanitize_textarea_field(wp_unslash($_POST['ad_contacts'] ?? '')),
                'region'       => sanitize_text_field(wp_unslash($_POST['ad_region'] ?? '')),
                'group'        => sanitize_text_field(wp_unslash($_POST['ad_group'] ?? '')),
                'html'         => wp_kses_post(wp_unslash($_POST['ad_html'] ?? '')),
                'cost'         => sanitize_text_field(wp_unslash($_POST['ad_cost'] ?? '')),
                'period_start' => sanitize_text_field(wp_unslash($_POST['ad_period_start'] ?? '')),
                'period_end'   => sanitize_text_field(wp_unslash($_POST['ad_period_end'] ?? '')),
                'payment_info' => sanitize_textarea_field(wp_unslash($_POST['ad_payment_info'] ?? '')),
            ];

            if ($ad_id >= 0 && isset($ads[$ad_id])) {
                $ad_data['impressions'] = absint($ads[$ad_id]['impressions'] ?? 0);
                $ad_data['clicks'] = absint($ads[$ad_id]['clicks'] ?? 0);
                $ads[$ad_id] = $ad_data;
            } else {
                $ad_data['impressions'] = 0;
                $ad_data['clicks'] = 0;
                $ads[] = $ad_data;
            }

            update_option($this->option_key, $ads);

            wp_safe_redirect(admin_url('admin.php?page=ua-light-alert&msg=saved'));
            exit;
        }

        if (isset($_GET['delete_ad'], $_GET['_wpnonce'])) {
            $index = absint($_GET['delete_ad']);

            if (wp_verify_nonce(sanitize_text_field(wp_unslash($_GET['_wpnonce'])), 'ua_delete_ad')) {
                $ads = get_option($this->option_key, []);

                if (is_array($ads) && isset($ads[$index])) {
                    unset($ads[$index]);
                    update_option($this->option_key, array_values($ads));
                }

                wp_safe_redirect(admin_url('admin.php?page=ua-light-alert&msg=deleted'));
                exit;
            }
        }
    }

    public function render_admin_page() {
        wp_enqueue_media();
        $push_count = UA_Light_Alert_Push::get_subscription_count();
        $cron_status = UA_Light_Alert_Push::get_cron_status();
        $ads = get_option($this->option_key, []);
        if (!is_array($ads)) {
            $ads = [];
        }

        $settings = $this->get_settings();

        $edit_id = isset($_GET['edit_ad']) ? absint($_GET['edit_ad']) : -1;
        $edit_ad = ($edit_id >= 0 && isset($ads[$edit_id])) ? $ads[$edit_id] : null;
        ?>
        <div class="wrap">
            <h1>Графік світла — Налаштування та рекламодавці</h1>
            <div class="notice notice-info">
                <p><strong>Фонові сповіщення:</strong> активних push-підписок — <?php echo absint($push_count); ?>.
                Плагін сам створює щохвилинне завдання WordPress — окремо налаштовувати його в адмінці не потрібно.</p>
                <p><strong>Стан планувальника:</strong>
                    <?php if ($cron_status['scheduled']) : ?>
                        завдання заплановано<?php if ($cron_status['next_run']) : ?>, наступний запуск: <?php echo esc_html(wp_date('d.m.Y H:i:s', $cron_status['next_run'])); ?><?php endif; ?>.
                    <?php else : ?>
                        <span style="color:#b32d2e;">завдання не заплановане</span>.
                    <?php endif; ?>
                    <?php if ($cron_status['last_run']) : ?>
                        Остання перевірка: <?php echo esc_html(wp_date('d.m.Y H:i:s', $cron_status['last_run'])); ?>.
                    <?php else : ?>
                        Перевірка ще не запускалася.
                    <?php endif; ?>
                </p>
                <?php if ($cron_status['wp_cron_disabled']) : ?>
                    <p style="color:#b32d2e;"><strong>Увага:</strong> на сайті вимкнено WP-Cron (DISABLE_WP_CRON). Плагін не може самостійно запускати серверні завдання в такому режимі.</p>
                <?php elseif ($cron_status['last_run'] && time() - $cron_status['last_run'] > 300) : ?>
                    <p style="color:#b32d2e;"><strong>Увага:</strong> WordPress не запускав фонову перевірку понад 5 хвилин. Відправлення push може затримуватися, якщо на сайті немає відвідувачів.</p>
                <?php endif; ?>
                <p>Коли сторінка закрита, звук push-сповіщення визначає операційна система; довільний MP3 примусово програвати у фоні браузер не дозволяє.</p>
            </div>

            <?php if (isset($_GET['msg'])) : ?>
                <div class="notice notice-success is-dismissible">
                    <p>Зміни збережено.</p>
                </div>
            <?php endif; ?>

            <div style="background:#fff;padding:20px;border:1px solid #ccd0d4;margin:20px 0;">
                <h2>🔔 Налаштування сповіщень</h2>

                <form method="post">
                    <?php wp_nonce_field('ua_settings_action'); ?>

                    <table class="form-table">
                        <tr>
                            <th scope="row">Попереджати за (хвилин)</th>
                            <td>
                                <select name="notice_minutes">
                                    <?php foreach ([1, 5, 10] as $minutes) : ?>
                                        <option value="<?php echo esc_attr($minutes); ?>" <?php selected((int) $settings['notice_minutes'], $minutes); ?>>
                                            <?php echo esc_html($minutes); ?> хвилин
                                        </option>
                                    <?php endforeach; ?>
                                </select>
                                <p class="description">Окреме попередження перед запланованою зміною. Плагін не стверджує, що фактичне вимкнення чи ввімкнення вже відбулося.</p>
                            </td>
                        </tr>
                        <tr>
                            <th scope="row">Звук перед вимкненням</th>
                            <td>
                                <input type="url" id="ua-light-sound-off" name="sound_off_url"
                                    value="<?php echo esc_attr($settings['sound_off_url']); ?>" class="regular-text"
                                    placeholder="https://example.com/off.mp3">
                                <button type="button" class="button ua-light-select-audio" data-target="ua-light-sound-off">Обрати MP3 з пристрою</button>
                                <button type="button" class="button ua-light-preview-audio" data-target="ua-light-sound-off">Прослухати</button>
                                <p class="description">Можна вибрати MP3. Якщо файл не задано, плагін відтворить вбудований короткий сигнал.</p>
                            </td>
                        </tr>
                        <tr>
                            <th scope="row">Звук перед увімкненням</th>
                            <td>
                                <input type="url" id="ua-light-sound-on" name="sound_on_url"
                                    value="<?php echo esc_attr($settings['sound_on_url']); ?>" class="regular-text"
                                    placeholder="https://example.com/on.mp3">
                                <button type="button" class="button ua-light-select-audio" data-target="ua-light-sound-on">Обрати MP3 з пристрою</button>
                                <button type="button" class="button ua-light-preview-audio" data-target="ua-light-sound-on">Прослухати</button>
                                <p class="description">Окремий MP3 перед запланованим відновленням. Якщо файл не задано — вбудований висхідний сигнал.</p>
                            </td>
                        </tr>
                    
                    <tr>
                        <th scope="row">Голосові повідомлення</th>
                        <td>
                            <label><input type="checkbox" name="voice_enabled" value="1" <?php checked(!empty($settings['voice_enabled'])); ?>> Озвучувати попередження українською мовою, коли сторінка відкрита</label>
                            <p class="description">Використовує синтез мовлення браузера. Наявність українського голосу залежить від пристрою та браузера.</p>
                        </td>
                    </tr>
                    <tr>
                        <th scope="row">Гучність голосу</th>
                        <td>
                            <input type="range" name="voice_volume" min="0" max="100" step="5" value="<?php echo esc_attr((int) $settings['voice_volume']); ?>" oninput="this.nextElementSibling.value=this.value+'%'">
                            <output><?php echo esc_html((int) $settings['voice_volume']); ?>%</output>
                        </td>
                    </tr>
                    <tr>
                        <th scope="row">Гучність звукового сигналу</th>
                        <td><input type="range" name="sound_volume" min="0" max="100" step="5" value="<?php echo esc_attr((int) $settings['sound_volume']); ?>" oninput="this.nextElementSibling.value=this.value+'%'"> <output><?php echo esc_html((int) $settings['sound_volume']); ?>%</output></td>
                    </tr>
                    <tr>
                        <th scope="row">Повтор попередження</th>
                        <td><select name="alert_repeat"><option value="0" <?php selected((int) $settings['alert_repeat'], 0); ?>>Без повтору</option><option value="1" <?php selected((int) $settings['alert_repeat'], 1); ?>>Повторити 1 раз</option><option value="2" <?php selected((int) $settings['alert_repeat'], 2); ?>>Повторити 2 рази</option></select></td>
                    </tr>
                    <tr>
                        <th scope="row">Тихі години</th>
                        <td><label>З <input type="time" name="quiet_start" value="<?php echo esc_attr($settings['quiet_start']); ?>"></label> <label>до <input type="time" name="quiet_end" value="<?php echo esc_attr($settings['quiet_end']); ?>"></label><p class="description">У цей проміжок плагін не відтворює звуки, голос і не показує активні сповіщення. Залиште обидва поля порожніми, щоб вимкнути тихі години. Працює і через північ.</p></td>
                    </tr>
                    </table>
                    <script>
                    jQuery(function($) {
                        $('.ua-light-select-audio').on('click', function(e) {
                            e.preventDefault();
                            var target = $('#' + $(this).data('target'));
                            var frame = wp.media({
                                title: 'Оберіть MP3-файл',
                                button: { text: 'Використати цей MP3' },
                                library: { type: 'audio' },
                                multiple: false
                            });
                            frame.on('select', function() {
                                var item = frame.state().get('selection').first().toJSON();
                                if (item.url) target.val(item.url);
                            });
                            frame.open();
                        });
                        $('.ua-light-preview-audio').on('click', function(e) {
                            e.preventDefault();
                            var url = $('#' + $(this).data('target')).val();
                            if (!url) { window.alert('Спочатку оберіть або вкажіть MP3.'); return; }
                            var audio = new Audio(url);
                            audio.volume = <?php echo max(0, min(100, (int) $settings['sound_volume'])) / 100; ?>;
                            audio.play().catch(function() { window.alert('Браузер не дозволив відтворити файл. Перевірте посилання.'); });
                        });
                    });
                    </script>

                    <?php submit_button('Зберегти налаштування', 'primary', 'ua_save_settings'); ?>
                </form>
            </div>

            <div style="display:flex;gap:20px;align-items:flex-start;flex-wrap:wrap;">
                <div style="flex:1;min-width:300px;background:#fff;padding:20px;border:1px solid #ccd0d4;">
                    <h2><?php echo $edit_ad ? 'Редагувати клієнта' : 'Додати нового клієнта'; ?></h2>

                    <form method="post">
                        <?php wp_nonce_field('ua_save_ad_action'); ?>

                        <?php if ($edit_ad) : ?>
                            <input type="hidden" name="ad_id" value="<?php echo esc_attr($edit_id); ?>">
                        <?php endif; ?>

                        <table class="form-table">
                            <tr>
                                <th scope="row">Назва компанії</th>
                                <td>
                                    <input type="text" name="ad_name" required class="regular-text"
                                        value="<?php echo esc_attr($edit_ad['name'] ?? ''); ?>">
                                </td>
                            </tr>

                            <tr>
                                <th scope="row">Email</th>
                                <td>
                                    <input type="email" name="ad_email" class="regular-text"
                                        value="<?php echo esc_attr($edit_ad['email'] ?? ''); ?>">
                                </td>
                            </tr>

                            <tr>
                                <th scope="row">Контакти</th>
                                <td>
                                    <textarea name="ad_contacts" rows="3" class="large-text"><?php
                                        echo esc_textarea($edit_ad['contacts'] ?? '');
                                    ?></textarea>
                                </td>
                            </tr>

                            <tr>
                                <th scope="row">Регіон (regionId)</th>
                                <td>
                                    <input type="text" name="ad_region" class="regular-text"
                                        placeholder="kyiv"
                                        value="<?php echo esc_attr($edit_ad['region'] ?? ''); ?>">
                                </td>
                            </tr>

                            <tr>
                                <th scope="row">Група</th>
                                <td>
                                    <input type="text" name="ad_group" class="regular-text"
                                        placeholder="3.1"
                                        value="<?php echo esc_attr($edit_ad['group'] ?? ''); ?>">
                                </td>
                            </tr>

                            <tr>
                                <th scope="row">HTML реклами</th>
                                <td>
                                    <textarea name="ad_html" rows="5" class="large-text" required><?php
                                        echo esc_textarea($edit_ad['html'] ?? '');
                                    ?></textarea>
                                </td>
                            </tr>

                            <tr>
                                <th scope="row">Вартість</th>
                                <td>
                                    <input type="text" name="ad_cost" class="regular-text"
                                        value="<?php echo esc_attr($edit_ad['cost'] ?? ''); ?>">
                                </td>
                            </tr>

                            <tr>
                                <th scope="row">Період показу</th>
                                <td>
                                    <input type="date" name="ad_period_start"
                                        value="<?php echo esc_attr($edit_ad['period_start'] ?? ''); ?>">
                                    &mdash;
                                    <input type="date" name="ad_period_end"
                                        value="<?php echo esc_attr($edit_ad['period_end'] ?? ''); ?>">
                                </td>
                            </tr>

                            <tr>
                                <th scope="row">Оплата</th>
                                <td>
                                    <textarea name="ad_payment_info" rows="2" class="large-text"><?php
                                        echo esc_textarea($edit_ad['payment_info'] ?? '');
                                    ?></textarea>
                                </td>
                            </tr>
                        </table>

                        <?php submit_button($edit_ad ? 'Зберегти зміни' : 'Додати клієнта', 'primary', 'ua_save_ad'); ?>

                        <?php if ($edit_ad) : ?>
                            <a href="<?php echo esc_url(admin_url('admin.php?page=ua-light-alert')); ?>" class="button">
                                Скасувати
                            </a>
                        <?php endif; ?>
                    </form>
                </div>

                <div style="flex:2;min-width:320px;">
                    <h2>База клієнтів та статистика</h2>

                    <?php if (empty($ads)) : ?>
                        <p>Ще немає клієнтів.</p>
                    <?php else : ?>
                        <table class="wp-list-table widefat fixed striped">
                            <thead>
                                <tr>
                                    <th>Назва</th>
                                    <th>Контакти</th>
                                    <th>Умови</th>
                                    <th>Статистика</th>
                                    <th>Дії</th>
                                </tr>
                            </thead>
                            <tbody>
                                <?php foreach ($ads as $i => $ad) : ?>
                                    <tr>
                                        <td>
                                            <strong><?php echo esc_html($ad['name'] ?? ''); ?></strong><br>
                                            <small><?php echo esc_html($ad['email'] ?? ''); ?></small>
                                        </td>
                                        <td>
                                            <?php echo nl2br(esc_html($ad['contacts'] ?? '')); ?>
                                        </td>
                                        <td>
                                            <b>Регіон:</b> <?php echo esc_html($ad['region'] ?: 'Усі'); ?><br>
                                            <b>Група:</b> <?php echo esc_html($ad['group'] ?: 'Усі'); ?><br>
                                            <b>Вартість:</b> <?php echo esc_html($ad['cost'] ?? ''); ?><br>
                                            <b>Період:</b>
                                            <?php echo esc_html($ad['period_start'] ?? ''); ?> —
                                            <?php echo esc_html($ad['period_end'] ?? ''); ?>
                                        </td>
                                        <td>
                                            👁 Показів: <b><?php echo absint($ad['impressions'] ?? 0); ?></b><br>
                                            🖱 Кліків: <b><?php echo absint($ad['clicks'] ?? 0); ?></b>
                                        </td>
                                        <td>
                                            <a href="<?php echo esc_url(admin_url('admin.php?page=ua-light-alert&edit_ad=' . $i)); ?>">
                                                Редагувати
                                            </a>
                                            |
                                            <a
                                                href="<?php echo esc_url(wp_nonce_url(
                                                    add_query_arg(
                                                        ['delete_ad' => $i],
                                                        admin_url('admin.php?page=ua-light-alert')
                                                    ),
                                                    'ua_delete_ad'
                                                )); ?>"
                                                onclick="return confirm('Видалити рекламодавця?')"
                                                style="color:red;"
                                            >Видалити</a>
                                        </td>
                                    </tr>
                                <?php endforeach; ?>
                            </tbody>
                        </table>
                    <?php endif; ?>
                </div>
            </div>
        </div>
        <?php
    }

    /* ---------------------------------------------------------
     * FRONTEND
     * ------------------------------------------------------ */

    public function render_frontend() {
        $settings = $this->get_settings();

        $notice_minutes = absint($settings['notice_minutes']);
        $sound_off_url = esc_url($settings['sound_off_url']);
        $push_public_key = UA_Light_Alert_Push::get_public_key();
        $push_subscribe_url = rest_url('ua-light-alert/v1/subscribe');
        $sound_on_url = esc_url($settings['sound_on_url']);
        $service_worker_url = add_query_arg('ua_light_sw', '1', home_url('/'));
        $service_worker_scope = home_url('/');

        $ajax_url = admin_url('admin-ajax.php');
        $nonce = wp_create_nonce('ua_light_frontend');

        ob_start();
        ?>
        <div id="ua-light-widget" class="ua-light-widget">
            <audio id="ua-alert-sound" preload="none"></audio>

            <div class="ua-controls">
                <label class="ua-field">
                    <strong>Область:</strong>
                    <select id="ua-region">
                        <option value="">— Оберіть область —</option>
                        <option value="kyiv">Київ</option>
                        <option value="kyiv-region">Київська область</option>
                        <option value="odesa">Одеська область</option>
                        <option value="dnipro">Дніпропетровська область</option>
                        <option value="lviv">Львівська область</option>
                        <option value="kharkiv">Харківська область</option>
                    </select>
                </label>

                <label class="ua-field">
                    <strong>Група:</strong>
                    <select id="ua-group" disabled>
                        <option value="">Спочатку оберіть область</option>
                    </select>
                </label>

                <button type="button" id="ua-show">
                    Показати графік та увімкнути сповіщення
                </button>

                <p class="ua-help">
                    Ваш вибір області та групи збережеться на цьому пристрої.
                    Його можна змінити в будь-який момент.
                </p>
            </div>

            <div id="ua-timer-box" class="ua-timer-box" style="display:none;">
                <h3>⏳ Наступна зміна статусу</h3>
                <div id="ua-timer-text">Очікування даних...</div>
            </div>

            <div id="ua-notice" class="ua-notice" style="display:none;"></div>
            <div id="ua-result" class="ua-result"></div>
            <div id="ua-ad-slot" class="ua-ad-slot"></div>
        </div>

        <style>
            .ua-light-widget {
                box-sizing: border-box;
                width: 100%;
                padding: 16px;
                border: 1px solid #e2e2e2;
                border-radius: 10px;
                background: #fff;
                color: #222;
                font-family: Arial, sans-serif;
            }
            .ua-light-widget * { box-sizing: border-box; }
            .ua-field {
                display: block;
                margin-bottom: 14px;
            }
            .ua-field select {
                display: block;
                width: 100%;
                min-height: 42px;
                margin-top: 6px;
                padding: 8px;
                border: 1px solid #ccc;
                border-radius: 5px;
                background: #fff;
            }
            #ua-show {
                width: 100%;
                min-height: 44px;
                padding: 10px 15px;
                border: 0;
                border-radius: 5px;
                background: #ff6b00;
                color: #fff;
                font-weight: bold;
                cursor: pointer;
            }
            #ua-show:disabled { opacity: .6; }
            .ua-help {
                margin: 9px 0 0;
                color: #666;
                font-size: 12px;
            }
            .ua-timer-box {
                margin-top: 15px;
                padding: 15px;
                border-left: 4px solid #f0ad00;
                border-radius: 5px;
                background: #fff3cd;
                color: #765500;
                text-align: center;
            }
            .ua-timer-box h3 {
                margin: 0 0 8px;
                font-size: 17px;
            }
            #ua-timer-text {
                font-size: 20px;
                font-weight: bold;
            }
            .ua-notice {
                margin-top: 12px;
                padding: 12px;
                border-radius: 5px;
                background: #e8f4ff;
                color: #17466b;
                font-weight: bold;
            }
            .ua-result { margin-top: 15px; }
            .ua-result table {
                width: 100%;
                border-collapse: collapse;
                text-align: center;
                font-size: 13px;
            }
            .ua-result th, .ua-result td {
                padding: 7px 4px;
                border: 1px solid #ddd;
            }
            .ua-result th { background: #f3f3f3; }
            .ua-ad-slot { margin-top: 20px; }
            @media (max-width: 480px) {
                .ua-light-widget { padding: 10px; }
                .ua-result table { font-size: 12px; }
                .ua-result th, .ua-result td { padding: 6px 2px; }
            }
        </style>

        <script>
        (function() {
            'use strict';

            var widget = document.getElementById('ua-light-widget');
            if (!widget || widget.dataset.initialized === '1') {
                return;
            }
            widget.dataset.initialized = '1';

            var regionSelect = document.getElementById('ua-region');
            var groupSelect = document.getElementById('ua-group');
            var showButton = document.getElementById('ua-show');
            var resultDiv = document.getElementById('ua-result');
            var adSlot = document.getElementById('ua-ad-slot');
            var timerBox = document.getElementById('ua-timer-box');
            var timerText = document.getElementById('ua-timer-text');
            var noticeBox = document.getElementById('ua-notice');
            var soundEl = document.getElementById('ua-alert-sound');
            var alertAudioContext = null;

            function unlockAlertAudio() {
                var AudioContextClass = window.AudioContext || window.webkitAudioContext;
                if (!AudioContextClass) return;
                try {
                    if (!alertAudioContext) alertAudioContext = new AudioContextClass();
                    if (alertAudioContext.state === 'suspended') {
                        alertAudioContext.resume().catch(function() {});
                    }
                } catch (e) {}
            }

            function playDefaultAlertTone(status) {
                if (!alertAudioContext || soundVolume <= 0) return;
                if (alertAudioContext.state === 'suspended') {
                    alertAudioContext.resume().catch(function() {});
                }
                var notes = status === 'yes' ? [523.25, 659.25, 783.99] : [783.99, 587.33, 440];
                var spacing = 0.19;
                for (var repeat = 0; repeat <= alertRepeat; repeat++) {
                    notes.forEach(function(frequency, index) {
                        var startAt = alertAudioContext.currentTime + (repeat * notes.length + index) * spacing;
                        var oscillator = alertAudioContext.createOscillator();
                        var gain = alertAudioContext.createGain();
                        oscillator.type = 'sine';
                        oscillator.frequency.setValueAtTime(frequency, startAt);
                        gain.gain.setValueAtTime(0.0001, startAt);
                        gain.gain.exponentialRampToValueAtTime(Math.max(0.0001, soundVolume * 0.18), startAt + 0.02);
                        gain.gain.exponentialRampToValueAtTime(0.0001, startAt + 0.15);
                        oscillator.connect(gain);
                        gain.connect(alertAudioContext.destination);
                        oscillator.start(startAt);
                        oscillator.stop(startAt + 0.16);
                    });
                }
            }

            var currentData = null;
            var currentGroupData = null;
            var activeRegion = '';
            var activeGroup = '';
            var ticker = null;
            var refreshTicker = null;
            var noticeMinutes = <?php echo (int) $notice_minutes; ?>;
            var voiceEnabled = <?php echo !empty($settings['voice_enabled']) ? 'true' : 'false'; ?>;
            var voiceVolume = <?php echo max(0, min(100, (int) $settings['voice_volume'])) / 100; ?>;
            var soundVolume = <?php echo max(0, min(100, (int) $settings['sound_volume'])) / 100; ?>;
            var alertRepeat = <?php echo max(0, min(2, (int) $settings['alert_repeat'])); ?>;
            var quietStart = <?php echo wp_json_encode($settings['quiet_start']); ?>;
            var quietEnd = <?php echo wp_json_encode($settings['quiet_end']); ?>;
            var soundUrls = {
                off: <?php echo wp_json_encode($sound_off_url); ?>,
                on: <?php echo wp_json_encode($sound_on_url); ?>
            };
            var serviceWorkerUrl = <?php echo wp_json_encode($service_worker_url); ?>;
            var serviceWorkerScope = <?php echo wp_json_encode($service_worker_scope); ?>;
            var serviceWorkerRegistration = null;
            var ajaxUrl = <?php echo wp_json_encode($ajax_url); ?>;
            var ajaxNonce = <?php echo wp_json_encode($nonce); ?>;
            var vapidPublicKey = <?php echo wp_json_encode($push_public_key); ?>;
            var pushSubscribeUrl = <?php echo wp_json_encode($push_subscribe_url); ?>;

            var storageKey = 'ua_light_alert_selection_v1';
            var alertedEvents = {};

            function saveSelection() {
                try {
                    localStorage.setItem(storageKey, JSON.stringify({
                        region: regionSelect.value,
                        group: groupSelect.value
                    }));
                } catch (e) {}
            }

            function readSelection() {
                try {
                    var saved = JSON.parse(localStorage.getItem(storageKey) || '{}');
                    if (saved && saved.region) {
                        regionSelect.value = saved.region;
                        loadRegion(saved.region, saved.group || '');
                    }
                } catch (e) {}
            }

            function getLatestData(data) {
                if (!data || !data.fact || !data.fact.data) {
                    return null;
                }

                var keys = Object.keys(data.fact.data).sort();
                if (!keys.length) {
                    return null;
                }

                var latestKey = keys[keys.length - 1];
                return data.fact.data[latestKey] || null;
            }

            function loadRegion(region, preferredGroup) {
                currentData = null;
                currentGroupData = null;
                groupSelect.disabled = true;
                groupSelect.innerHTML = '<option value="">Завантаження груп...</option>';

                if (ticker) {
                    clearInterval(ticker);
                    ticker = null;
                }
                if (refreshTicker) {
                    clearInterval(refreshTicker);
                    refreshTicker = null;
                }

                timerBox.style.display = 'none';
                resultDiv.innerHTML = '';
                adSlot.innerHTML = '';

                if (!region) {
                    groupSelect.innerHTML = '<option value="">Спочатку оберіть область</option>';
                    return;
                }

                var url = 'https://raw.githubusercontent.com/Baskerville42/outage-data-ua/main/data/'
                    + encodeURIComponent(region) + '.json';

                fetch(url, {cache: 'no-store'})
                    .then(function(response) {
                        if (!response.ok) {
                            throw new Error('Не вдалося отримати графік');
                        }
                        return response.json();
                    })
                    .then(function(data) {
                        currentData = data;

                        var groupsData = getLatestData(data);
                        if (!groupsData) {
                            throw new Error('У файлі немає даних про групи');
                        }

                        groupSelect.innerHTML = '<option value="">— Оберіть групу —</option>';

                        Object.keys(groupsData).sort().forEach(function(groupName) {
                            var option = document.createElement('option');
                            option.value = groupName;
                            option.textContent = groupName.replace('GPV', '');
                            groupSelect.appendChild(option);
                        });

                        groupSelect.disabled = false;

                        if (preferredGroup && groupsData[preferredGroup]) {
                            groupSelect.value = preferredGroup;
                            if (groupSelect.value) {
                                activateSchedule();
                            }
                        }
                    })
                    .catch(function(error) {
                        groupSelect.innerHTML = '<option value="">Помилка завантаження</option>';
                        resultDiv.textContent = error.message;
                    });
            }

            regionSelect.addEventListener('change', function() {
                saveSelection();
                loadRegion(this.value, '');
            });

            groupSelect.addEventListener('change', function() {
                saveSelection();
            });

            showButton.addEventListener('click', function() {
                unlockAlertAudio();
                if (!regionSelect.value) {
                    alert('Оберіть область.');
                    return;
                }

                if (!groupSelect.value) {
                    alert('Оберіть групу.');
                    return;
                }

                if (!currentData) {
                    alert('Дані ще завантажуються. Спробуйте трохи пізніше.');
                    return;
                }

                saveSelection();

                if ('Notification' in window) {
                    var permissionRequest = Notification.permission === 'default'
                        ? Notification.requestPermission()
                        : Promise.resolve(Notification.permission);
                    permissionRequest.then(function(permission) {
                        if (permission === 'granted') {
                            registerPwaServiceWorker().then(function(registration) {
                                if (registration) {
                                    subscribeForPush(registration);
                                }
                            });
                        }
                    }).catch(function() {});
                }

                if (soundEl) {
                    var testSound = soundUrls.off || soundUrls.on;
                    if (testSound) {
                        soundEl.src = testSound;
                        soundEl.play().then(function() {
                            soundEl.pause();
                            soundEl.currentTime = 0;
                        }).catch(function() {});
                    }
                }

                activateSchedule();
            });

            function activateSchedule() {
                var groupsData = getLatestData(currentData);
                var group = groupSelect.value;

                if (!groupsData || !groupsData[group]) {
                    resultDiv.textContent = 'Дані для цієї групи відсутні.';
                    return;
                }

                activeRegion = regionSelect.value;
                activeGroup = group;
                currentGroupData = groupsData[group];

                saveSelection();
                renderSchedule(currentGroupData);

                if (ticker) {
                    clearInterval(ticker);
                }

                if (refreshTicker) {
                    clearInterval(refreshTicker);
                }

                checkNextChange();
                ticker = setInterval(checkNextChange, 1000);

                refreshTicker = setInterval(function() {
                    refreshScheduleData();
                }, 300000);

                loadAds(activeRegion, activeGroup);
            }

            function normalizeHalfHourStatus(status, half) {
                if (status === 'yes' || status === 'no') return status;
                // Source semantics: "first" = outage in the first half-hour;
                // "second" = outage in the second half-hour.
                if (status === 'first') return half === 0 ? 'no' : 'yes';
                if (status === 'second') return half === 0 ? 'yes' : 'no';
                return null;
            }

            function renderSchedule(groupData) {
                resultDiv.innerHTML = '';

                var updated = currentData.lastUpdated || '';
                if (updated) {
                    var updatedLine = document.createElement('p');
                    updatedLine.textContent = 'Оновлено: ' + new Date(updated).toLocaleString('uk-UA');
                    resultDiv.appendChild(updatedLine);
                }

                var table = document.createElement('table');
                var thead = document.createElement('thead');
                var header = document.createElement('tr');

                var hourHeader = document.createElement('th');
                hourHeader.textContent = 'Година';

                var statusHeader = document.createElement('th');
                statusHeader.textContent = 'Статус';

                header.appendChild(hourHeader);
                header.appendChild(statusHeader);
                thead.appendChild(header);
                table.appendChild(thead);

                var tbody = document.createElement('tbody');

                for (var slot = 0; slot < 48; slot++) {
                    var hourKey = String(Math.floor(slot / 2) + 1);
                    var sourceStatus = groupData[hourKey];
                    var half = slot % 2;
                    var status = normalizeHalfHourStatus(sourceStatus, half);
                    var startMinutes = slot * 30;
                    var endMinutes = (startMinutes + 30) % 1440;
                    var fmt = function(value) {
                        return String(Math.floor(value / 60)).padStart(2, '0') + ':' +
                            String(value % 60).padStart(2, '0');
                    };

                    var row = document.createElement('tr');
                    var hourCell = document.createElement('td');
                    var statusCell = document.createElement('td');
                    hourCell.textContent = fmt(startMinutes) + ' — ' + fmt(endMinutes);
                    statusCell.style.fontWeight = 'bold';

                    if (status === 'yes') {
                        statusCell.textContent = 'Світло є';
                        statusCell.style.backgroundColor = '#d4edda';
                        statusCell.style.color = '#155724';
                    } else if (status === 'no') {
                        statusCell.textContent = 'Світла немає';
                        statusCell.style.backgroundColor = '#f8d7da';
                        statusCell.style.color = '#721c24';
                    } else {
                        statusCell.textContent = 'Немає даних';
                        statusCell.style.backgroundColor = '#fff3cd';
                        statusCell.style.color = '#856404';
                    }

                    row.appendChild(hourCell);
                    row.appendChild(statusCell);
                    tbody.appendChild(row);
                }

                table.appendChild(tbody);
                resultDiv.appendChild(table);
            }

            /*
             * Ключі джерела:
             * 1 = 00:00–01:00
             * 2 = 01:00–02:00
             * ...
             * 21 = 20:00–21:00
             *
             * Тому при переході на 20:00 порівнюються
             * ключі 20 та 21. Додатковий зсув не застосовується.
             */

            function checkNextChange() {
                if (!currentGroupData || !activeGroup) return;
                var now = new Date();
                var currentSlot = now.getHours() * 2 + (now.getMinutes() >= 30 ? 1 : 0);
                var currentKey = String(Math.floor(currentSlot / 2) + 1);
                var currentStatus = normalizeHalfHourStatus(currentGroupData[currentKey], currentSlot % 2);
                if (!currentStatus) {
                    timerBox.style.display = 'block';
                    timerText.textContent = 'Для поточного часу немає достовірних даних.';
                    return;
                }

                var nextEvent = null;
                for (var offset = 1; offset <= 48; offset++) {
                    var slot = (currentSlot + offset) % 48;
                    var key = String(Math.floor(slot / 2) + 1);
                    var status = normalizeHalfHourStatus(currentGroupData[key], slot % 2);
                    if (!status) continue;
                    if (status !== currentStatus) {
                        var target = new Date(now);
                        target.setSeconds(0, 0);
                        target.setMinutes(Math.floor(target.getMinutes() / 30) * 30);
                        target.setMinutes(target.getMinutes() + offset * 30);
                        nextEvent = {
                            time: target,
                            status: status,
                            key: activeRegion + '_' + activeGroup + '_' + target.getFullYear() + '_' +
                                target.getMonth() + '_' + target.getDate() + '_' + target.getHours() + '_' + target.getMinutes()
                        };
                        break;
                    }
                }

                timerBox.style.display = 'block';
                if (!nextEvent) {
                    timerText.textContent = 'Наступних змін у графіку не знайдено.';
                    return;
                }
                var diff = nextEvent.time.getTime() - now.getTime();
                var secondsLeft = Math.max(0, Math.floor(diff / 1000));
                var minutesLeft = Math.floor(secondsLeft / 60);
                var seconds = secondsLeft % 60;
                var eventText = nextEvent.status === 'yes'
                    ? 'За графіком очікується відновлення електропостачання'
                    : 'За графіком очікується відключення електроенергії';

                if (diff > 0 && diff <= noticeMinutes * 60 * 1000) {
                    timerText.textContent = eventText + ' через ' + minutesLeft +
                        ' хв ' + String(seconds).padStart(2, '0') + ' сек';
                    if (!alertedEvents[nextEvent.key]) {
                        alertedEvents[nextEvent.key] = true;
                        sendActivePageAlert(eventText, nextEvent);
                    }
                } else {
                    var hh = String(nextEvent.time.getHours()).padStart(2, '0');
                    var mm = String(nextEvent.time.getMinutes()).padStart(2, '0');
                    timerText.textContent = 'Наступна зміна за графіком о ' + hh + ':' + mm +
                        '. Попередження за ' + noticeMinutes + ' хвилин.';
                }
            }

            function registerPwaServiceWorker() {
                if (!('serviceWorker' in navigator)) {
                    return Promise.resolve(null);
                }
                return navigator.serviceWorker.register(serviceWorkerUrl, {scope: serviceWorkerScope})
                    .then(function(registration) {
                        serviceWorkerRegistration = registration;
                        return registration;
                    })
                    .catch(function() { return null; });
            }

            function urlBase64ToUint8Array(base64String) {
                var padding = '='.repeat((4 - base64String.length % 4) % 4);
                var base64 = (base64String + padding).replace(/-/g, '+').replace(/_/g, '/');
                var raw = window.atob(base64);
                var output = new Uint8Array(raw.length);
                for (var i = 0; i < raw.length; i++) {
                    output[i] = raw.charCodeAt(i);
                }
                return output;
            }

            function subscribeForPush(registration) {
                if (!registration.pushManager || !vapidPublicKey || !window.fetch) {
                    return Promise.resolve(null);
                }
                return registration.pushManager.getSubscription().then(function(subscription) {
                    if (subscription) {
                        return subscription;
                    }
                    return registration.pushManager.subscribe({
                        userVisibleOnly: true,
                        applicationServerKey: urlBase64ToUint8Array(vapidPublicKey)
                    });
                }).then(function(subscription) {
                    return fetch(pushSubscribeUrl, {
                        method: 'POST',
                        headers: {'Content-Type': 'application/json'},
                        body: JSON.stringify({
                            subscription: subscription.toJSON(),
                            region: regionSelect.value,
                            group: groupSelect.value,
                            page_url: window.location.href
                        })
                    }).then(function(response) {
                        if (!response.ok) {
                            throw new Error('Не вдалося зберегти push-підписку.');
                        }
                        noticeBox.style.display = 'block';
                        noticeBox.textContent = 'Сповіщення для вибраної області та групи увімкнено.';
                        return subscription;
                    });
                }).catch(function(error) {
                    if (window.console && console.warn) {
                        console.warn('UA Light Alert push subscription:', error);
                    }
                    return null;
                });
            }

            function isQuietHoursNow() {
                if (!quietStart || !quietEnd || quietStart === quietEnd) return false;
                var now = new Date();
                var current = now.getHours() * 60 + now.getMinutes();
                var startParts = quietStart.split(':');
                var endParts = quietEnd.split(':');
                var start = parseInt(startParts[0], 10) * 60 + parseInt(startParts[1], 10);
                var end = parseInt(endParts[0], 10) * 60 + parseInt(endParts[1], 10);
                return start < end ? (current >= start && current < end) : (current >= start || current < end);
            }

            function sendActivePageAlert(message, event) {
                noticeBox.style.display = 'block';
                noticeBox.textContent = '🔔 Увага! ' + message + '!';
                if (isQuietHoursNow()) return;

                var soundUrl = event.status === 'yes' ? soundUrls.on : soundUrls.off;
                if (!soundUrl) {
                    playDefaultAlertTone(event.status);
                }
                if (soundEl && soundUrl) {
                    soundEl.pause();
                    soundEl.src = soundUrl;
                    soundEl.volume = soundVolume;
                    var remainingPlays = alertRepeat + 1;
                    soundEl.onended = function() {
                        remainingPlays--;
                        if (remainingPlays > 0) {
                            soundEl.currentTime = 0;
                            soundEl.play().catch(function() {});
                        } else {
                            soundEl.onended = null;
                        }
                    };
                    soundEl.currentTime = 0;
                    soundEl.play().catch(function() {});
                }

                if (voiceEnabled && 'speechSynthesis' in window && 'SpeechSynthesisUtterance' in window) {
                    try {
                        window.speechSynthesis.cancel();
                        var spoken = new SpeechSynthesisUtterance(message + '. Час за графіком: ' +
                            String(event.time.getHours()).padStart(2, '0') + ':' +
                            String(event.time.getMinutes()).padStart(2, '0') + '.');
                        spoken.lang = 'uk-UA';
                        spoken.volume = voiceVolume;
                        var speakRemaining = alertRepeat + 1;
                        spoken.onend = function() {
                            speakRemaining--;
                            if (speakRemaining > 0 && !isQuietHoursNow()) {
                                var repeated = new SpeechSynthesisUtterance(spoken.text);
                                repeated.lang = 'uk-UA';
                                repeated.volume = voiceVolume;
                                repeated.onend = spoken.onend;
                                window.speechSynthesis.speak(repeated);
                            }
                        };
                        window.speechSynthesis.speak(spoken);
                    } catch (e) {}
                }
                if (navigator.vibrate) navigator.vibrate([250, 120, 250]);

                if ('Notification' in window && Notification.permission === 'granted') {
                    registerPwaServiceWorker().then(function(registration) {
                        var title = 'UA Light Alert';
                        var body = message + ' о ' +
                            String(event.time.getHours()).padStart(2, '0') + ':00';
                        if (registration && registration.showNotification) {
                            registration.showNotification(title, {
                                body: body,
                                icon: '<?php echo esc_js(get_site_icon_url(192)); ?>',
                                tag: event.key,
                                renotify: false,
                                data: {url: window.location.href}
                            }).catch(function() {});
                        } else {
                            try {
                                var notification = new Notification(title, {body: body, tag: event.key});
                                notification.onclick = function() {
                                    window.focus();
                                    notification.close();
                                };
                            } catch (e) {}
                        }
                    });
                }
            }

            function refreshScheduleData() {
                if (!activeRegion || !activeGroup) {
                    return;
                }

                var url = 'https://raw.githubusercontent.com/Baskerville42/outage-data-ua/main/data/'
                    + encodeURIComponent(activeRegion) + '.json';

                fetch(url, {cache: 'no-store'})
                    .then(function(response) {
                        if (!response.ok) {
                            throw new Error('Помилка оновлення');
                        }
                        return response.json();
                    })
                    .then(function(data) {
                        currentData = data;
                        var groups = getLatestData(data);

                        if (groups && groups[activeGroup]) {
                            currentGroupData = groups[activeGroup];
                            renderSchedule(currentGroupData);
                        }
                    })
                    .catch(function() {});
            }

            function loadAds(region, group) {
                var body = new URLSearchParams();
                body.append('action', 'ua_get_ads');
                body.append('region', region);
                body.append('group', group);
                body.append('_ajax_nonce', ajaxNonce);

                fetch(ajaxUrl, {
                    method: 'POST',
                    headers: {'Content-Type': 'application/x-www-form-urlencoded'},
                    body: body.toString()
                })
                .then(function(response) {
                    return response.text();
                })
                .then(function(html) {
                    adSlot.innerHTML = html;

                    if (html.indexOf('ua-ad-item') !== -1) {
                        trackAd('impression', region, group);
                    }
                })
                .catch(function() {
                    adSlot.innerHTML = '';
                });
            }

            function trackAd(type, region, group) {
                var body = new URLSearchParams();
                body.append('action', 'ua_track_ad');
                body.append('type', type);
                body.append('region', region);
                body.append('group', group);
                body.append('_ajax_nonce', ajaxNonce);

                fetch(ajaxUrl, {
                    method: 'POST',
                    headers: {'Content-Type': 'application/x-www-form-urlencoded'},
                    body: body.toString()
                }).catch(function() {});
            }

            window.uaLightTrackClick = function(region, group) {
                trackAd('click', region, group);
            };

            readSelection();
        })();
        </script>
        <?php

        return ob_get_clean();
    }

    /* ---------------------------------------------------------
     * AJAX: AD STATISTICS
     * ------------------------------------------------------ */

    public function track_ad_click() {
        $type = sanitize_text_field(wp_unslash($_POST['type'] ?? ''));
        $region = sanitize_text_field(wp_unslash($_POST['region'] ?? ''));
        $group = sanitize_text_field(wp_unslash($_POST['group'] ?? ''));

        $ads = get_option($this->option_key, []);
        if (!is_array($ads)) {
            $ads = [];
        }

        foreach ($ads as $i => $ad) {
            if (!empty($ad['region']) && $ad['region'] !== $region) {
                continue;
            }

            if (!empty($ad['group']) && $ad['group'] !== $group) {
                continue;
            }

            if ($type === 'impression') {
                $ads[$i]['impressions'] = absint($ad['impressions'] ?? 0) + 1;
            } elseif ($type === 'click') {
                $ads[$i]['clicks'] = absint($ad['clicks'] ?? 0) + 1;
            }
        }

        update_option($this->option_key, $ads);
        wp_die();
    }
}

/* -------------------------------------------------------------
 * AJAX: GET ADVERTISEMENTS
 * ---------------------------------------------------------- */

function ua_light_get_ads() {
    $region = sanitize_text_field(wp_unslash($_POST['region'] ?? ''));
    $group = sanitize_text_field(wp_unslash($_POST['group'] ?? ''));

    $ads = get_option('ua_light_ads', []);
    if (!is_array($ads)) {
        $ads = [];
    }

    $today = current_time('Y-m-d');
    $output = '';

    foreach ($ads as $ad) {
        if (!empty($ad['region']) && $ad['region'] !== $region) {
            continue;
        }

        if (!empty($ad['group']) && $ad['group'] !== $group) {
            continue;
        }

        if (!empty($ad['period_start']) && $today < $ad['period_start']) {
            continue;
        }

        if (!empty($ad['period_end']) && $today > $ad['period_end']) {
            continue;
        }

        $output .= '<div class="ua-ad-item" style="border:1px solid #ddd;padding:10px;margin-bottom:10px;">';
        $output .= wp_kses_post($ad['html'] ?? '');
        $output .= '</div>';
    }

    if (!$output) {
        $output = '<p><em>Реклами для цього запиту немає.</em></p>';
    }

    echo $output;
    wp_die();
}


/**
 * GitHub Releases updater.
 *
 * A public GitHub release is the source of truth. The installed plugin checks
 * the latest release and lets WordPress install its tag archive from Plugins.
 */
final class UA_Light_Alert_GitHub_Updater {
    const REPOSITORY = 'portallcomua/UA-Light-Alert';
    const SLUG = 'ua-light-alert';
    const CACHE_KEY = 'ua_light_alert_github_release_v2';
    const CACHE_TTL = 300;

    private $plugin_file;

    public function __construct() {
        $this->plugin_file = plugin_basename(__FILE__);

        // Required for plugins that declare a custom Update URI (WordPress 5.8+).
        add_filter('update_plugins_github.com', [$this, 'filter_github_update'], 10, 4);

        // Compatibility path for older WordPress versions.
        add_filter('pre_set_site_transient_update_plugins', [$this, 'filter_update_transient']);
        // Also inject when WordPress reads the cached update list (Plugins screen).
        add_filter('site_transient_update_plugins', [$this, 'filter_site_update_transient']);

        add_filter('plugins_api', [$this, 'plugin_information'], 20, 3);
        add_filter('upgrader_source_selection', [$this, 'normalize_package_directory'], 10, 4);
    }

    private function get_release($force = false) {
        if (!$force) {
            $cached = get_transient(self::CACHE_KEY);
            if (is_array($cached) && !empty($cached['tag_name'])) {
                return $cached;
            }
        }

        $response = wp_remote_get(
            'https://api.github.com/repos/' . self::REPOSITORY . '/releases/latest',
            [
                'timeout' => 12,
                'headers' => [
                    'Accept' => 'application/vnd.github+json',
                    'User-Agent' => 'UA-Light-Alert-WordPress-Updater',
                    'X-GitHub-Api-Version' => '2022-11-28',
                ],
            ]
        );

        if (is_wp_error($response) || 200 !== (int) wp_remote_retrieve_response_code($response)) {
            return false;
        }

        $release = json_decode(wp_remote_retrieve_body($response), true);
        if (!is_array($release) || empty($release['tag_name']) || !empty($release['draft']) || !empty($release['prerelease'])) {
            return false;
        }

        set_transient(self::CACHE_KEY, $release, self::CACHE_TTL);
        return $release;
    }

    private function get_release_package($release, $version) {
        $asset_name = self::SLUG . '-' . $version . '.zip';
        if (!empty($release['assets']) && is_array($release['assets'])) {
            foreach ($release['assets'] as $asset) {
                if (!empty($asset['name']) && $asset['name'] === $asset_name && !empty($asset['browser_download_url'])) {
                    return esc_url_raw($asset['browser_download_url']);
                }
            }
        }
        return !empty($release['zipball_url'])
            ? esc_url_raw($release['zipball_url'])
            : 'https://github.com/' . self::REPOSITORY . '/archive/refs/tags/' . rawurlencode($release['tag_name']) . '.zip';
    }

    private function get_update_object() {
        $release = $this->get_release();
        if (!$release) {
            return false;
        }

        $version = ltrim((string) $release['tag_name'], "vV");
        $plugin_data = get_file_data(__FILE__, ['Version' => 'Version']);
        $installed_version = isset($plugin_data['Version']) ? $plugin_data['Version'] : '0';

        if (!preg_match('/^\d+(?:\.\d+){1,3}(?:[-+][A-Za-z0-9.-]+)?$/', $version)
            || !version_compare($version, $installed_version, '>')) {
            return false;
        }

        $package = $this->get_release_package($release, $version);

        return (object) [
            'id' => 'https://github.com/' . self::REPOSITORY,
            'slug' => self::SLUG,
            'plugin' => $this->plugin_file,
            'version' => $version,
            'new_version' => $version,
            'url' => 'https://github.com/' . self::REPOSITORY,
            'package' => $package,
            'tested' => '',
            'requires' => '5.8',
            'requires_php' => '7.4',
            'icons' => [],
            'banners' => [],
            'banners_rtl' => [],
            'upgrade_notice' => !empty($release['body']) ? wp_strip_all_tags($release['body']) : '',
        ];
    }

    public function filter_github_update($update, $plugin_data = null, $plugin_file = null, $locales = null) {
        if ($plugin_file && $plugin_file !== $this->plugin_file) {
            return $update;
        }
        $candidate = $this->get_update_object();
        return $candidate ? $candidate : $update;
    }

    public function filter_update_transient($transient) {
        return $this->inject_update_transient($transient);
    }

    public function filter_site_update_transient($transient) {
        return $this->inject_update_transient($transient);
    }

    private function inject_update_transient($transient) {
        if (!is_object($transient)) {
            return $transient;
        }

        $candidate = $this->get_update_object();
        if (!$candidate) {
            return $transient;
        }

        if (!isset($transient->response) || !is_array($transient->response)) {
            $transient->response = [];
        }
        $transient->response[$this->plugin_file] = $candidate;

        if (isset($transient->no_update) && is_array($transient->no_update)) {
            unset($transient->no_update[$this->plugin_file]);
        }

        return $transient;
    }



    /**
     * GitHub's generated ZIP has a commit-suffixed root folder. Rename only
     * this plugin's extracted package to the currently installed directory
     * so WordPress updates in place instead of creating a duplicate plugin.
     */
    public function normalize_package_directory($source, $remote_source, $upgrader, $hook_extra) {
        if (empty($hook_extra['plugin']) || $hook_extra['plugin'] !== $this->plugin_file) {
            return $source;
        }

        $expected_dir = dirname($this->plugin_file);
        if ('.' === $expected_dir || '' === $expected_dir) {
            $expected_dir = self::SLUG;
        }

        $source = untrailingslashit($source);
        $parent = dirname($source);
        $target = trailingslashit($parent) . $expected_dir;

        if (basename($source) === $expected_dir) {
            return $source;
        }

        if (is_dir($target)) {
            return new WP_Error(
                'ua_light_alert_update_directory_conflict',
                'Не вдалося оновити UA Light Alert: у тимчасовому каталозі вже існує папка призначення.'
            );
        }

        if (!@rename($source, $target)) {
            return new WP_Error(
                'ua_light_alert_update_directory_rename_failed',
                'Не вдалося підготувати архів UA Light Alert для оновлення.'
            );
        }

        return trailingslashit($target);
    }

    public function plugin_information($result, $action, $args) {
        if ('plugin_information' !== $action || empty($args->slug) || self::SLUG !== $args->slug) {
            return $result;
        }

        $release = $this->get_release();
        if (!$release) {
            return $result;
        }

        $version = ltrim((string) $release['tag_name'], "vV");
        $download = $this->get_release_package($release, $version);

        return (object) [
            'name' => 'UA Light Alert',
            'slug' => self::SLUG,
            'version' => $version,
            'author' => '<a href="https://uaserver.pp.ua/">UAServer</a>',
            'homepage' => 'https://uaserver.pp.ua/svitlo/',
            'download_link' => $download,
            'trunk' => $download,
            'requires' => '5.8',
            'requires_php' => '7.4',
            'sections' => [
                'description' => 'Графіки відключень, рекламодавці та звукові сповіщення про зміни електропостачання.',
                'changelog' => !empty($release['body']) ? nl2br(esc_html($release['body'])) : 'Інформація про зміни доступна в GitHub Releases.',
            ],
        ];
    }
}

new UA_Light_Alert();
new UA_Light_Alert_GitHub_Updater();
new UA_Light_Alert_Push();
register_activation_hook(__FILE__, ['UA_Light_Alert_Push', 'activate']);
register_deactivation_hook(__FILE__, ['UA_Light_Alert_Push', 'deactivate']);

