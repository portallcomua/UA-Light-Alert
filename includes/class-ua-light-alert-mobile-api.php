<?php
/**
 * Mobile app REST API, configuration sync, ad delivery, and optional Pro trial/license manager.
 */
if (!defined('ABSPATH')) {
    exit;
}

final class UA_Light_Alert_Mobile_API {
    const MOBILE_SETTINGS_KEY = 'ua_light_mobile_settings';
    const LICENSES_KEY        = 'ua_light_mobile_licenses';
    const DEVICES_KEY         = 'ua_light_mobile_devices';

    public function __construct() {
        add_action('rest_api_init', [$this, 'register_routes']);
        add_action('admin_init', [$this, 'handle_admin_actions']);
    }

    public static function default_mobile_settings() {
        return [
            'monetization_enabled' => 0,
            'trial_days'           => 30,
            'pro_price_label'      => '100 грн (один раз)',
            'payment_url'          => '',
            'payment_note'         => 'Після оплати 100 грн надішліть код вашого пристрою адміністратору, щоб отримати ключ активації.',
            'apk_download_url'     => '',
        ];
    }

    public static function get_mobile_settings() {
        $raw = get_option(self::MOBILE_SETTINGS_KEY, []);
        $settings = wp_parse_args(is_array($raw) ? $raw : [], self::default_mobile_settings());
        $settings['monetization_enabled'] = !empty($settings['monetization_enabled']) ? 1 : 0;
        $settings['trial_days'] = max(1, min(365, absint($settings['trial_days'])));
        return $settings;
    }

    public static function get_regions() {
        return [
            ['id' => 'kyiv', 'name' => 'Київ'],
            ['id' => 'kyiv-region', 'name' => 'Київська область'],
            ['id' => 'odesa', 'name' => 'Одеська область'],
            ['id' => 'dnipro', 'name' => 'Дніпропетровська область'],
            ['id' => 'lviv', 'name' => 'Львівська область'],
            ['id' => 'kharkiv', 'name' => 'Харківська область'],
        ];
    }

    public function register_routes() {
        register_rest_route('ua-light-alert/v1', '/config', [
            'methods'             => 'GET',
            'callback'            => [$this, 'rest_get_config'],
            'permission_callback' => '__return_true',
        ]);

        register_rest_route('ua-light-alert/v1', '/ads', [
            'methods'             => 'GET',
            'callback'            => [$this, 'rest_get_ads'],
            'permission_callback' => '__return_true',
        ]);

        register_rest_route('ua-light-alert/v1', '/ads/track', [
            'methods'             => 'POST',
            'callback'            => [$this, 'rest_track_ad'],
            'permission_callback' => '__return_true',
        ]);

        register_rest_route('ua-light-alert/v1', '/license/activate', [
            'methods'             => 'POST',
            'callback'            => [$this, 'rest_activate_license'],
            'permission_callback' => '__return_true',
        ]);
    }

    private function normalize_device_id($raw) {
        $clean = strtoupper(preg_replace('/[^A-Za-z0-9_-]/', '', (string) $raw));
        return substr($clean, 0, 64);
    }

    private function normalize_license_code($raw) {
        $clean = strtoupper(preg_replace('/[^A-Za-z0-9-]/', '', (string) $raw));
        return substr($clean, 0, 40);
    }

    private function evaluate_pro_status($device_id, $app_version = '') {
        $mobile = self::get_mobile_settings();
        $now = time();

        if ($device_id !== '') {
            $devices = get_option(self::DEVICES_KEY, []);
            if (!is_array($devices)) {
                $devices = [];
            }
            $key = hash('sha256', $device_id);
            if (!isset($devices[$key]) || !is_array($devices[$key])) {
                $devices[$key] = [
                    'device_id'    => $device_id,
                    'first_seen'   => $now,
                    'last_seen'    => $now,
                    'license_code' => '',
                    'app_version'  => $app_version,
                ];
            } else {
                $devices[$key]['last_seen'] = $now;
                if ($app_version !== '') {
                    $devices[$key]['app_version'] = $app_version;
                }
            }
            // Keep stored device map bounded to avoid unbounded option growth.
            if (count($devices) > 5000) {
                uasort($devices, function($a, $b) {
                    return ($a['last_seen'] ?? 0) <=> ($b['last_seen'] ?? 0);
                });
                $devices = array_slice($devices, -4000, null, true);
            }
            update_option(self::DEVICES_KEY, $devices, false);
        }

        if (empty($mobile['monetization_enabled'])) {
            return [
                'monetization_enabled' => false,
                'has_pro_access'       => true,
                'status'               => 'free',
                'trial_days_total'     => (int) $mobile['trial_days'],
                'trial_days_left'      => (int) $mobile['trial_days'],
                'price_label'          => (string) $mobile['pro_price_label'],
                'payment_url'          => (string) $mobile['payment_url'],
                'payment_note'         => (string) $mobile['payment_note'],
                'license_code'         => '',
            ];
        }

        $licenses = get_option(self::LICENSES_KEY, []);
        if (!is_array($licenses)) {
            $licenses = [];
        }
        $devices = get_option(self::DEVICES_KEY, []);
        $device_record = ($device_id !== '' && is_array($devices))
            ? ($devices[hash('sha256', $device_id)] ?? [])
            : [];

        $bound_code = isset($device_record['license_code']) ? (string) $device_record['license_code'] : '';
        if ($bound_code !== '' && isset($licenses[$bound_code]) && ($licenses[$bound_code]['status'] ?? '') === 'active') {
            return [
                'monetization_enabled' => true,
                'has_pro_access'       => true,
                'status'               => 'licensed',
                'trial_days_total'     => (int) $mobile['trial_days'],
                'trial_days_left'      => (int) $mobile['trial_days'],
                'price_label'          => (string) $mobile['pro_price_label'],
                'payment_url'          => (string) $mobile['payment_url'],
                'payment_note'         => (string) $mobile['payment_note'],
                'license_code'         => $bound_code,
            ];
        }

        $first_seen = !empty($device_record['first_seen']) ? (int) $device_record['first_seen'] : $now;
        $trial_seconds = (int) $mobile['trial_days'] * DAY_IN_SECONDS;
        $remaining_seconds = ($first_seen + $trial_seconds) - $now;
        $days_left = max(0, (int) ceil($remaining_seconds / DAY_IN_SECONDS));
        $in_trial = $remaining_seconds > 0;

        return [
            'monetization_enabled' => true,
            'has_pro_access'       => $in_trial,
            'status'               => $in_trial ? 'trial' : 'expired',
            'trial_days_total'     => (int) $mobile['trial_days'],
            'trial_days_left'      => $days_left,
            'price_label'          => (string) $mobile['pro_price_label'],
            'payment_url'          => (string) $mobile['payment_url'],
            'payment_note'         => (string) $mobile['payment_note'],
            'license_code'         => '',
        ];
    }

    public function rest_get_config(WP_REST_Request $request) {
        $device_id = $this->normalize_device_id($request->get_param('device_id'));
        $app_version = sanitize_text_field((string) $request->get_param('app_version'));

        $raw_settings = get_option('ua_light_settings', []);
        $raw_settings = is_array($raw_settings) ? $raw_settings : [];
        $defaults = [
            'notice_minutes' => 5,
            'alert_mode'     => 'both',
            'sound_url'      => '',
            'sound_off_url'  => '',
            'sound_on_url'   => '',
            'voice_enabled'  => 1,
            'voice_gender'   => 'female',
            'voice_rate'     => 0.95,
            'voice_pitch'    => 1.0,
            'voice_text_off' => 'Увага! За графіком очікується відключення електроенергії.',
            'voice_text_on'  => 'Увага! За графіком очікується відновлення електропостачання.',
            'voice_volume'   => 80,
            'sound_volume'   => 80,
            'alert_repeat'   => 0,
            'quiet_start'    => '',
            'quiet_end'      => '',
        ];
        $settings = wp_parse_args($raw_settings, $defaults);
        if (empty($raw_settings['alert_mode'])) {
            $settings['alert_mode'] = !empty($settings['voice_enabled']) ? 'both' : 'audio';
        }
        if (empty($settings['sound_off_url']) && !empty($settings['sound_url'])) {
            $settings['sound_off_url'] = $settings['sound_url'];
        }
        if (empty($settings['sound_on_url']) && !empty($settings['sound_url'])) {
            $settings['sound_on_url'] = $settings['sound_url'];
        }

        $plugin_data = get_file_data(dirname(__DIR__) . '/ua-light-alert.php', ['Version' => 'Version']);
        $schedule_page = get_page_by_path('svitlo');
        $schedule_url = $schedule_page ? get_permalink($schedule_page) : (get_option('ua_light_schedule_page_url', '') ?: home_url('/'));

        return new WP_REST_Response([
            'plugin_version' => $plugin_data['Version'] ?? '4.8.1',
            'site_name'      => 'Графік відключень',
            'schedule_url'   => $schedule_url,
            'regions'        => self::get_regions(),
            'settings'       => [
                'notice_minutes' => (int) $settings['notice_minutes'],
                'alert_mode'     => (string) $settings['alert_mode'],
                'sound_off_url'  => (string) $settings['sound_off_url'],
                'sound_on_url'   => (string) $settings['sound_on_url'],
                'voice_enabled'  => !empty($settings['voice_enabled']),
                'voice_gender'   => (string) $settings['voice_gender'],
                'voice_rate'     => (float) $settings['voice_rate'],
                'voice_pitch'    => (float) $settings['voice_pitch'],
                'voice_text_off' => (string) $settings['voice_text_off'],
                'voice_text_on'  => (string) $settings['voice_text_on'],
                'voice_volume'   => (int) $settings['voice_volume'],
                'sound_volume'   => (int) $settings['sound_volume'],
                'alert_repeat'   => (int) $settings['alert_repeat'],
                'quiet_start'    => (string) $settings['quiet_start'],
                'quiet_end'      => (string) $settings['quiet_end'],
            ],
            'pro'            => $this->evaluate_pro_status($device_id, $app_version),
        ], 200);
    }

    public function rest_activate_license(WP_REST_Request $request) {
        $body = $request->get_json_params();
        $device_id = $this->normalize_device_id($body['device_id'] ?? '');
        $code = $this->normalize_license_code($body['code'] ?? '');

        if ($device_id === '' || $code === '') {
            return new WP_REST_Response([
                'success' => false,
                'message' => 'Вкажіть коректний код пристрою та ключ активації.',
            ], 400);
        }

        $licenses = get_option(self::LICENSES_KEY, []);
        if (!is_array($licenses) || !isset($licenses[$code])) {
            return new WP_REST_Response([
                'success' => false,
                'message' => 'Ключ активації не знайдено.',
            ], 404);
        }

        $license = $licenses[$code];
        $status = $license['status'] ?? 'unused';
        if ($status === 'revoked') {
            return new WP_REST_Response([
                'success' => false,
                'message' => 'Цей ключ заблоковано адміністратором.',
            ], 403);
        }
        if ($status === 'active' && !empty($license['device_id']) && $license['device_id'] !== $device_id) {
            return new WP_REST_Response([
                'success' => false,
                'message' => 'Цей ключ уже активовано на іншому пристрої.',
            ], 409);
        }

        $licenses[$code]['status'] = 'active';
        $licenses[$code]['device_id'] = $device_id;
        $licenses[$code]['activated_at'] = time();
        update_option(self::LICENSES_KEY, $licenses, false);

        $devices = get_option(self::DEVICES_KEY, []);
        if (!is_array($devices)) {
            $devices = [];
        }
        $key = hash('sha256', $device_id);
        if (!isset($devices[$key]) || !is_array($devices[$key])) {
            $devices[$key] = [
                'device_id'  => $device_id,
                'first_seen' => time(),
                'last_seen'  => time(),
            ];
        }
        $devices[$key]['license_code'] = $code;
        $devices[$key]['last_seen'] = time();
        update_option(self::DEVICES_KEY, $devices, false);

        return new WP_REST_Response([
            'success' => true,
            'message' => 'Pro-функції активовано!',
            'pro'     => $this->evaluate_pro_status($device_id),
        ], 200);
    }

    public function rest_get_ads(WP_REST_Request $request) {
        $region = sanitize_text_field((string) $request->get_param('region'));
        $group = sanitize_text_field((string) $request->get_param('group'));

        $ads = get_option('ua_light_ads', []);
        if (!is_array($ads)) {
            $ads = [];
        }

        $today = current_time('Y-m-d');
        $items = [];

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
            $html = wp_kses_post($ad['html'] ?? '');
            $primary_url = '';
            if (preg_match('/href=["\']([^"\']+)["\']/i', $html, $m)) {
                $primary_url = esc_url_raw(html_entity_decode($m[1], ENT_QUOTES, 'UTF-8'));
            }
            $image_url = '';
            if (preg_match('/<img[^>]+src=["\']([^"\']+)["\']/i', $html, $imgMatch)) {
                $image_url = esc_url_raw(html_entity_decode($imgMatch[1], ENT_QUOTES, 'UTF-8'));
            }
            $alt_text = '';
            if (preg_match('/<img[^>]+alt=["\']([^"\']*)["\']/i', $html, $altMatch)) {
                $alt_text = trim(html_entity_decode($altMatch[1], ENT_QUOTES, 'UTF-8'));
            }
            $items[] = [
                'name'        => (string) ($ad['name'] ?? ''),
                'html'        => $html,
                'text'        => trim(wp_strip_all_tags($html)),
                'image_url'   => $image_url,
                'alt'         => $alt_text,
                'primary_url' => $primary_url,
            ];
        }

        return new WP_REST_Response([
            'ads' => $items,
        ], 200);
    }

    public function rest_track_ad(WP_REST_Request $request) {
        $body = $request->get_json_params();
        $type = sanitize_key($body['type'] ?? '');
        $region = sanitize_text_field($body['region'] ?? '');
        $group = sanitize_text_field($body['group'] ?? '');

        if (!in_array($type, ['impression', 'click'], true)) {
            return new WP_REST_Response(['success' => false], 400);
        }

        $ads = get_option('ua_light_ads', []);
        if (!is_array($ads)) {
            $ads = [];
        }

        $today = current_time('Y-m-d');
        foreach ($ads as $i => $ad) {
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
            if ($type === 'impression') {
                $ads[$i]['impressions'] = absint($ad['impressions'] ?? 0) + 1;
            } else {
                $ads[$i]['clicks'] = absint($ad['clicks'] ?? 0) + 1;
            }
        }

        update_option('ua_light_ads', $ads, false);
        return new WP_REST_Response(['success' => true], 200);
    }

    public function handle_admin_actions() {
        if (!current_user_can('manage_options')) {
            return;
        }

        if (isset($_POST['ua_save_mobile_settings'])) {
            check_admin_referer('ua_mobile_settings_action');

            $trial_days = isset($_POST['trial_days']) ? absint($_POST['trial_days']) : 30;
            $trial_days = max(1, min(365, $trial_days));

            $mobile_settings = [
                'monetization_enabled' => isset($_POST['monetization_enabled']) ? 1 : 0,
                'trial_days'           => $trial_days,
                'pro_price_label'      => sanitize_text_field(wp_unslash($_POST['pro_price_label'] ?? '100 грн (один раз)')),
                'payment_url'          => esc_url_raw(wp_unslash($_POST['payment_url'] ?? '')),
                'payment_note'         => sanitize_textarea_field(wp_unslash($_POST['payment_note'] ?? '')),
                'apk_download_url'     => esc_url_raw(wp_unslash($_POST['apk_download_url'] ?? '')),
            ];
            update_option(self::MOBILE_SETTINGS_KEY, $mobile_settings, false);

            wp_safe_redirect(admin_url('admin.php?page=ua-light-alert&msg=mobile_saved'));
            exit;
        }

        if (isset($_POST['ua_generate_license'])) {
            check_admin_referer('ua_mobile_license_action');

            $licenses = get_option(self::LICENSES_KEY, []);
            if (!is_array($licenses)) {
                $licenses = [];
            }

            $custom_code = $this->normalize_license_code(wp_unslash($_POST['license_code'] ?? ''));
            if ($custom_code === '') {
                $custom_code = 'UALA-' . strtoupper(wp_generate_password(4, false, false)) . '-' . strtoupper(wp_generate_password(4, false, false));
            }
            $device_id = $this->normalize_device_id(wp_unslash($_POST['license_device_id'] ?? ''));
            $note = sanitize_text_field(wp_unslash($_POST['license_note'] ?? ''));

            $licenses[$custom_code] = [
                'code'         => $custom_code,
                'status'       => $device_id !== '' ? 'active' : 'unused',
                'device_id'    => $device_id,
                'note'         => $note,
                'created_at'   => time(),
                'activated_at' => $device_id !== '' ? time() : 0,
            ];
            update_option(self::LICENSES_KEY, $licenses, false);

            if ($device_id !== '') {
                $devices = get_option(self::DEVICES_KEY, []);
                if (!is_array($devices)) {
                    $devices = [];
                }
                $dkey = hash('sha256', $device_id);
                if (!isset($devices[$dkey]) || !is_array($devices[$dkey])) {
                    $devices[$dkey] = [
                        'device_id'  => $device_id,
                        'first_seen' => time(),
                        'last_seen'  => time(),
                    ];
                }
                $devices[$dkey]['license_code'] = $custom_code;
                update_option(self::DEVICES_KEY, $devices, false);
            }

            wp_safe_redirect(admin_url('admin.php?page=ua-light-alert&msg=license_created'));
            exit;
        }

        if (isset($_GET['ua_license_action'], $_GET['code'], $_GET['_wpnonce'])) {
            $code = $this->normalize_license_code(wp_unslash($_GET['code']));
            $action = sanitize_key(wp_unslash($_GET['ua_license_action']));
            if (wp_verify_nonce(sanitize_text_field(wp_unslash($_GET['_wpnonce'])), 'ua_license_manage_' . $code)) {
                $licenses = get_option(self::LICENSES_KEY, []);
                if (is_array($licenses) && isset($licenses[$code])) {
                    if ($action === 'revoke') {
                        $licenses[$code]['status'] = 'revoked';
                    } elseif ($action === 'restore') {
                        $licenses[$code]['status'] = !empty($licenses[$code]['device_id']) ? 'active' : 'unused';
                    } elseif ($action === 'delete') {
                        unset($licenses[$code]);
                    }
                    update_option(self::LICENSES_KEY, $licenses, false);
                }
                wp_safe_redirect(admin_url('admin.php?page=ua-light-alert&msg=license_updated'));
                exit;
            }
        }
    }

    public static function render_admin_section() {
        $mobile = self::get_mobile_settings();
        $licenses = get_option(self::LICENSES_KEY, []);
        if (!is_array($licenses)) {
            $licenses = [];
        }
        $devices = get_option(self::DEVICES_KEY, []);
        $device_count = is_array($devices) ? count($devices) : 0;
        ?>
        <div style="background:#fff;padding:20px;border:1px solid #ccd0d4;margin:20px 0;">
            <h2>📱 Android-додаток та монетизація (Pro-функції)</h2>
            <p class="description">
                Android-додаток автоматично підтягує всі ваші налаштування сповіщень, аудіо й рекламу з
                <code><?php echo esc_html(rest_url('ua-light-alert/v1/config')); ?></code>.
                Зафіксовано пристроїв: <strong><?php echo absint($device_count); ?></strong>.
            </p>

            <form method="post">
                <?php wp_nonce_field('ua_mobile_settings_action'); ?>
                <table class="form-table">
                    <tr>
                        <th scope="row">Посилання на APK (опційно)</th>
                        <td>
                            <input type="url" name="apk_download_url" class="regular-text"
                                value="<?php echo esc_attr($mobile['apk_download_url']); ?>"
                                placeholder="https://uaserver.pp.ua/wp-content/uploads/ua-light-alert.apk">
                            <p class="description">Якщо вказати посилання на <code>.apk</code>, на сторінці графіка для Android-відвідувачів з’явиться кнопка завантаження додатка.</p>
                        </td>
                    </tr>
                    <tr>
                        <th scope="row">Монетизація Pro-функцій</th>
                        <td>
                            <label>
                                <input type="checkbox" name="monetization_enabled" value="1" <?php checked(!empty($mobile['monetization_enabled'])); ?>>
                                Увімкнути обмежений пробний період для власних налаштувань (свій MP3, запис голосу, власний час попередження)
                            </label>
                            <p class="description">Коли галочку знято — усі функції Android-додатка безкоштовні без обмежень. Коли увімкнено — графік і стандартні сповіщення сайту лишаються безкоштовними завжди, а персональні налаштування працюють безкоштовно протягом тріалу.</p>
                        </td>
                    </tr>
                    <tr>
                        <th scope="row">Пробний період (днів)</th>
                        <td>
                            <input type="number" name="trial_days" min="1" max="365" value="<?php echo esc_attr((int) $mobile['trial_days']); ?>" class="small-text"> днів
                        </td>
                    </tr>
                    <tr>
                        <th scope="row">Вартість розблокування</th>
                        <td>
                            <input type="text" name="pro_price_label" class="regular-text" value="<?php echo esc_attr($mobile['pro_price_label']); ?>">
                        </td>
                    </tr>
                    <tr>
                        <th scope="row">Посилання на оплату</th>
                        <td>
                            <input type="url" name="payment_url" class="regular-text" value="<?php echo esc_attr($mobile['payment_url']); ?>" placeholder="https://send.monobank.ua/jar/...">
                        </td>
                    </tr>
                    <tr>
                        <th scope="row">Інструкція для користувача</th>
                        <td>
                            <textarea name="payment_note" rows="2" class="large-text"><?php echo esc_textarea($mobile['payment_note']); ?></textarea>
                        </td>
                    </tr>
                </table>
                <?php submit_button('Зберегти налаштування додатка', 'primary', 'ua_save_mobile_settings'); ?>
            </form>

            <hr style="margin:24px 0;">
            <h3>🔑 Ключі активації Pro</h3>
            <form method="post" style="display:flex;gap:10px;flex-wrap:wrap;align-items:center;margin-bottom:14px;">
                <?php wp_nonce_field('ua_mobile_license_action'); ?>
                <input type="text" name="license_code" placeholder="Ключ (порожньо — авто)" class="regular-text" style="max-width:210px;">
                <input type="text" name="license_device_id" placeholder="Код пристрою (необов’язково)" class="regular-text" style="max-width:220px;">
                <input type="text" name="license_note" placeholder="Коментар / клієнт" class="regular-text" style="max-width:220px;">
                <?php submit_button('Згенерувати ключ', 'secondary', 'ua_generate_license', false); ?>
            </form>

            <?php if (!empty($licenses)) : ?>
                <table class="wp-list-table widefat fixed striped">
                    <thead>
                        <tr>
                            <th>Ключ</th>
                            <th>Статус</th>
                            <th>Пристрій</th>
                            <th>Коментар</th>
                            <th>Дії</th>
                        </tr>
                    </thead>
                    <tbody>
                        <?php foreach (array_reverse($licenses, true) as $code => $lic) : ?>
                            <?php $st = $lic['status'] ?? 'unused'; ?>
                            <tr>
                                <td><code><?php echo esc_html($code); ?></code></td>
                                <td>
                                    <?php if ($st === 'active') : ?>
                                        <span style="color:#155724;font-weight:600;">Активовано</span>
                                    <?php elseif ($st === 'revoked') : ?>
                                        <span style="color:#b32d2e;font-weight:600;">Заблоковано</span>
                                    <?php else : ?>
                                        <span>Очікує активації</span>
                                    <?php endif; ?>
                                </td>
                                <td><code><?php echo esc_html($lic['device_id'] ?: '—'); ?></code></td>
                                <td><?php echo esc_html($lic['note'] ?? ''); ?></td>
                                <td>
                                    <?php
                                    $nonce_action = 'ua_license_manage_' . $code;
                                    if ($st !== 'revoked') :
                                        $revoke_url = wp_nonce_url(add_query_arg(['ua_license_action' => 'revoke', 'code' => $code], admin_url('admin.php?page=ua-light-alert')), $nonce_action);
                                        echo '<a href="' . esc_url($revoke_url) . '">Заблокувати</a> | ';
                                    else :
                                        $restore_url = wp_nonce_url(add_query_arg(['ua_license_action' => 'restore', 'code' => $code], admin_url('admin.php?page=ua-light-alert')), $nonce_action);
                                        echo '<a href="' . esc_url($restore_url) . '">Розблокувати</a> | ';
                                    endif;
                                    $del_url = wp_nonce_url(add_query_arg(['ua_license_action' => 'delete', 'code' => $code], admin_url('admin.php?page=ua-light-alert')), $nonce_action);
                                    echo '<a href="' . esc_url($del_url) . '" style="color:#b32d2e;">Видалити</a>';
                                    ?>
                                </td>
                            </tr>
                        <?php endforeach; ?>
                    </tbody>
                </table>
            <?php endif; ?>
        </div>
        <?php
    }
}
