<?php
/**
 * Background Web Push delivery for UA Light Alert.
 * Requires HTTPS and the PHP OpenSSL extension.
 */
if (!defined('ABSPATH')) {
    exit;
}

final class UA_Light_Alert_Push {
    const KEYS_OPTION = 'ua_light_push_vapid_keys';
    const SUBS_OPTION = 'ua_light_push_subscriptions';
    const CRON_HOOK = 'ua_light_alert_push_tick';
    const LAST_RUN_OPTION = 'ua_light_push_last_run';
    const LAST_STATUS_OPTION = 'ua_light_push_last_status';

    public function __construct() {
        add_filter('cron_schedules', [$this, 'add_minute_schedule']);
        add_action('init', [$this, 'ensure_cron']);
        add_action(self::CRON_HOOK, [$this, 'dispatch_due_alerts']);
        add_action('rest_api_init', [$this, 'register_routes']);
    }

    public function add_minute_schedule($schedules) {
        if (!isset($schedules['ua_light_minute'])) {
            $schedules['ua_light_minute'] = [
                'interval' => 60,
                'display' => 'UA Light Alert: every minute',
            ];
        }
        return $schedules;
    }

    public function ensure_cron() {
        if (!wp_next_scheduled(self::CRON_HOOK)) {
            wp_schedule_event(time() + 30, 'ua_light_minute', self::CRON_HOOK);
        }
    }

    public static function activate() {
        if (!wp_next_scheduled(self::CRON_HOOK)) {
            wp_schedule_event(time() + 30, 'ua_light_minute', self::CRON_HOOK);
        }
    }

    public static function get_cron_status() {
        $next = wp_next_scheduled(self::CRON_HOOK);
        $last = (int) get_option(self::LAST_RUN_OPTION, 0);
        return [
            'scheduled' => (bool) $next,
            'next_run' => $next ? (int) $next : 0,
            'last_run' => $last,
            'last_status' => (string) get_option(self::LAST_STATUS_OPTION, ''),
            'wp_cron_disabled' => defined('DISABLE_WP_CRON') && DISABLE_WP_CRON,
        ];
    }

    public static function get_public_key() {
        $keys = self::get_vapid_keys();
        return $keys && !empty($keys['public']) ? $keys['public'] : '';
    }

    public static function get_subscription_count() {
        $subscriptions = get_option(self::SUBS_OPTION, []);
        return is_array($subscriptions) ? count($subscriptions) : 0;
    }

    public static function deactivate() {
        wp_clear_scheduled_hook(self::CRON_HOOK);
    }

    private static function get_vapid_keys() {
        $keys = get_option(self::KEYS_OPTION, []);
        if (is_array($keys) && !empty($keys['private_pem']) && !empty($keys['public'])) {
            return $keys;
        }
        if (!function_exists('openssl_pkey_new') || !function_exists('openssl_pkey_derive')) {
            return false;
        }

        $key = openssl_pkey_new([
            'private_key_type' => OPENSSL_KEYTYPE_EC,
            'curve_name' => 'prime256v1',
        ]);
        if (!$key || !openssl_pkey_export($key, $privatePem)) {
            return false;
        }
        $details = openssl_pkey_get_details($key);
        if (empty($details['ec']['x']) || empty($details['ec']['y'])) {
            return false;
        }
        $publicRaw = "\x04" . $details['ec']['x'] . $details['ec']['y'];
        $keys = [
            'private_pem' => $privatePem,
            'public' => self::base64url_encode($publicRaw),
        ];
        update_option(self::KEYS_OPTION, $keys, false);
        return $keys;
    }

    public function register_routes() {
        register_rest_route('ua-light-alert/v1', '/subscribe', [
            'methods' => 'POST',
            'callback' => [$this, 'save_subscription'],
            'permission_callback' => '__return_true',
        ]);
        register_rest_route('ua-light-alert/v1', '/unsubscribe', [
            'methods' => 'POST',
            'callback' => [$this, 'delete_subscription'],
            'permission_callback' => '__return_true',
        ]);
    }

    public function save_subscription(WP_REST_Request $request) {
        $body = $request->get_json_params();
        $subscription = isset($body['subscription']) && is_array($body['subscription']) ? $body['subscription'] : [];
        $endpoint = isset($subscription['endpoint']) ? esc_url_raw($subscription['endpoint']) : '';
        $keys = isset($subscription['keys']) && is_array($subscription['keys']) ? $subscription['keys'] : [];
        $p256dh = isset($keys['p256dh']) ? sanitize_text_field($keys['p256dh']) : '';
        $auth = isset($keys['auth']) ? sanitize_text_field($keys['auth']) : '';
        $region = isset($body['region']) ? sanitize_key($body['region']) : '';
        $group = isset($body['group']) ? sanitize_text_field($body['group']) : '';
        $pageUrl = isset($body['page_url']) ? esc_url_raw($body['page_url']) : home_url('/');

        $endpointHost = strtolower((string) wp_parse_url($endpoint, PHP_URL_HOST));
        $decodedPublic = self::base64url_decode($p256dh);
        $decodedAuth = self::base64url_decode($auth);
        if (!$endpoint || 'https' !== wp_parse_url($endpoint, PHP_URL_SCHEME)
            || !$this->is_allowed_push_host($endpointHost)
            || !is_string($decodedPublic) || strlen($decodedPublic) !== 65 || $decodedPublic[0] !== "\x04"
            || !is_string($decodedAuth) || strlen($decodedAuth) !== 16
            || !$region || !$group) {
            return new WP_Error('ua_light_invalid_subscription', 'Некоректні дані push-підписки.', ['status' => 400]);
        }
        if (wp_parse_url($pageUrl, PHP_URL_HOST) !== wp_parse_url(home_url('/'), PHP_URL_HOST)) {
            $pageUrl = home_url('/');
        }

        $subscriptions = get_option(self::SUBS_OPTION, []);
        if (!is_array($subscriptions)) {
            $subscriptions = [];
        }
        $id = hash('sha256', $endpoint);
        $old = isset($subscriptions[$id]) && is_array($subscriptions[$id]) ? $subscriptions[$id] : [];
        $subscriptions[$id] = [
            'endpoint' => $endpoint,
            'keys' => ['p256dh' => $p256dh, 'auth' => $auth],
            'region' => $region,
            'group' => $group,
            'page_url' => $pageUrl,
            'last_event' => isset($old['last_event']) ? $old['last_event'] : '',
            'updated_at' => time(),
        ];
        update_option(self::SUBS_OPTION, $subscriptions, false);
        return new WP_REST_Response(['success' => true], 200);
    }

    public function delete_subscription(WP_REST_Request $request) {
        $body = $request->get_json_params();
        $endpoint = isset($body['endpoint']) ? esc_url_raw($body['endpoint']) : '';
        if (!$endpoint) {
            return new WP_Error('ua_light_missing_endpoint', 'Не вказано endpoint.', ['status' => 400]);
        }
        $subscriptions = get_option(self::SUBS_OPTION, []);
        if (is_array($subscriptions)) {
            unset($subscriptions[hash('sha256', $endpoint)]);
            update_option(self::SUBS_OPTION, $subscriptions, false);
        }
        return new WP_REST_Response(['success' => true], 200);
    }

    public function dispatch_due_alerts() {
        update_option(self::LAST_RUN_OPTION, time(), false);
        update_option(self::LAST_STATUS_OPTION, 'running', false);

        if (!function_exists('openssl_pkey_derive')) {
            update_option(self::LAST_STATUS_OPTION, 'openssl_missing', false);
            return;
        }
        $subscriptions = get_option(self::SUBS_OPTION, []);
        if (!is_array($subscriptions) || !$subscriptions) {
            update_option(self::LAST_STATUS_OPTION, 'no_subscriptions', false);
            return;
        }
        $settings = get_option('ua_light_settings', []);
        $settings = wp_parse_args(is_array($settings) ? $settings : [], ['notice_minutes' => 5]);
        $lead = in_array((int) $settings['notice_minutes'], [1, 5, 10], true) ? (int) $settings['notice_minutes'] : 5;
        $now = current_datetime();

        // Quiet hours suppress scheduled push alerts as well as on-page sounds.
        $quietStart = isset($settings['quiet_start']) ? (string) $settings['quiet_start'] : '';
        $quietEnd = isset($settings['quiet_end']) ? (string) $settings['quiet_end'] : '';
        if (preg_match('/^(?:[01]\\d|2[0-3]):[0-5]\\d$/', $quietStart)
            && preg_match('/^(?:[01]\\d|2[0-3]):[0-5]\\d$/', $quietEnd)
            && $quietStart !== $quietEnd) {
            $currentTime = $now->format('H:i');
            $isQuiet = $quietStart < $quietEnd
                ? ($currentTime >= $quietStart && $currentTime < $quietEnd)
                : ($currentTime >= $quietStart || $currentTime < $quietEnd);
            if ($isQuiet) {
                update_option(self::LAST_STATUS_OPTION, 'quiet_hours', false);
                return;
            }
        }

        foreach ($subscriptions as $id => $subscription) {
            if (empty($subscription['region']) || empty($subscription['group']) || empty($subscription['endpoint'])) {
                continue;
            }
            $data = $this->get_region_data($subscription['region']);
            if (!$data) {
                continue;
            }
            $groups = $this->latest_groups($data);
            $group = $subscription['group'];
            if (!$groups || !isset($groups[$group]) || !is_array($groups[$group])) {
                continue;
            }
            $event = $this->find_next_event($groups[$group], $now);
            if (!$event) {
                continue;
            }
            $seconds = $event['time']->getTimestamp() - $now->getTimestamp();
            if ($seconds < 0 || $seconds > ($lead * 60)) {
                continue;
            }
            $eventKey = $subscription['region'] . '|' . $group . '|' . $event['time']->format('Y-m-d-H-i');
            if (!empty($subscription['last_event']) && hash_equals((string) $subscription['last_event'], $eventKey)) {
                continue;
            }

            $message = 'yes' === $event['status']
                ? 'За графіком очікується відновлення електропостачання'
                : 'За графіком очікується відключення електроенергії';
            $payload = [
                'title' => 'Графік світла',
                'body' => 'Через ' . $lead . ' хвилин. ' . $message . ' о ' . $event['time']->format('H:i') . '. Це попередження за графіком, не підтвердження фактичного стану.',
                'url' => !empty($subscription['page_url']) ? $subscription['page_url'] : home_url('/'),
                'tag' => $eventKey,
                'vibrate' => [500, 200, 500],
            ];
            $sent = $this->send_web_push($subscription, $payload);
            if ($sent === true) {
                $subscriptions[$id]['last_event'] = $eventKey;
            } elseif (in_array($sent, [404, 410], true)) {
                unset($subscriptions[$id]);
            }
        }
        update_option(self::SUBS_OPTION, $subscriptions, false);
        update_option(self::LAST_STATUS_OPTION, 'ok', false);
    }

    private function get_region_data($region) {
        $cacheKey = 'ua_light_push_data_' . md5($region);
        $cached = get_transient($cacheKey);
        if (is_array($cached)) {
            return $cached;
        }
        $url = 'https://raw.githubusercontent.com/Baskerville42/outage-data-ua/main/data/' . rawurlencode($region) . '.json';
        $response = wp_remote_get($url, ['timeout' => 12, 'headers' => ['User-Agent' => 'UA-Light-Alert']]);
        if (is_wp_error($response) || 200 !== (int) wp_remote_retrieve_response_code($response)) {
            return false;
        }
        $data = json_decode(wp_remote_retrieve_body($response), true);
        if (!is_array($data)) {
            return false;
        }
        set_transient($cacheKey, $data, 45);
        return $data;
    }

    private function latest_groups($data) {
        if (empty($data['fact']['data']) || !is_array($data['fact']['data'])) {
            return false;
        }
        $days = array_keys($data['fact']['data']);
        sort($days, SORT_STRING);
        $latest = end($days);
        return $latest && isset($data['fact']['data'][$latest]) && is_array($data['fact']['data'][$latest])
            ? $data['fact']['data'][$latest] : false;
    }

    private function normalize_half_hour_status($status, $half) {
        if ('yes' === $status || 'no' === $status) {
            return $status;
        }
        // "first": outage in the first 30 minutes; "second": outage in the second 30.
        if ('first' === $status) {
            return 0 === $half ? 'no' : 'yes';
        }
        if ('second' === $status) {
            return 0 === $half ? 'yes' : 'no';
        }
        return '';
    }

    private function find_next_event($groupData, DateTimeImmutable $now) {
        $hour = (int) $now->format('G');
        $minute = (int) $now->format('i');
        $currentSlot = ($hour * 2) + ($minute >= 30 ? 1 : 0);
        $currentKey = (string) (intdiv($currentSlot, 2) + 1);
        $currentStatus = $this->normalize_half_hour_status(
            isset($groupData[$currentKey]) ? $groupData[$currentKey] : '',
            $currentSlot % 2
        );
        if (!$currentStatus) {
            return false;
        }

        for ($offset = 1; $offset <= 48; $offset++) {
            $slot = ($currentSlot + $offset) % 48;
            $key = (string) (intdiv($slot, 2) + 1);
            $status = $this->normalize_half_hour_status(
                isset($groupData[$key]) ? $groupData[$key] : '',
                $slot % 2
            );
            if (!$status) {
                continue;
            }
            if ($status !== $currentStatus) {
                $minutesFromMidnight = ($currentSlot * 30) + ($offset * 30);
                $target = $now->setTime(0, 0)->modify('+' . $minutesFromMidnight . ' minutes');
                return ['time' => $target, 'status' => $status];
            }
        }
        return false;
    }

    private function is_allowed_push_host($host) {
        if (in_array($host, ['fcm.googleapis.com', 'web.push.apple.com'], true)) {
            return true;
        }
        foreach (['.push.services.mozilla.com', '.notify.windows.com'] as $suffix) {
            if (strlen($host) > strlen($suffix) && substr($host, -strlen($suffix)) === $suffix) {
                return true;
            }
        }
        return false;
    }

    private function send_web_push($subscription, $payload) {
        $endpointHost = strtolower((string) wp_parse_url($subscription['endpoint'], PHP_URL_HOST));
        if (!$this->is_allowed_push_host($endpointHost)) {
            return false;
        }
        $keys = self::get_vapid_keys();
        if (!$keys) {
            return false;
        }
        $endpoint = $subscription['endpoint'];
        $origin = wp_parse_url($endpoint, PHP_URL_SCHEME) . '://' . wp_parse_url($endpoint, PHP_URL_HOST);
        $jwt = $this->create_vapid_jwt($origin, $keys);
        if (!$jwt) {
            return false;
        }
        $encrypted = $this->encrypt_payload(wp_json_encode($payload), $subscription['keys']);
        if (!$encrypted) {
            return false;
        }
        $response = wp_remote_post($endpoint, [
            'timeout' => 12,
            'redirection' => 0,
            'headers' => [
                'TTL' => '300',
                'Urgency' => 'high',
                'Content-Type' => 'application/octet-stream',
                'Content-Encoding' => 'aes128gcm',
                'Authorization' => 'vapid t=' . $jwt . ', k=' . $keys['public'],
            ],
            'body' => $encrypted,
        ]);
        if (is_wp_error($response)) {
            return false;
        }
        $code = (int) wp_remote_retrieve_response_code($response);
        return ($code >= 200 && $code < 300) ? true : $code;
    }

    private function create_vapid_jwt($audience, $keys) {
        $private = openssl_pkey_get_private($keys['private_pem']);
        if (!$private) {
            return false;
        }
        $header = self::base64url_encode(wp_json_encode(['typ' => 'JWT', 'alg' => 'ES256']));
        $claims = self::base64url_encode(wp_json_encode([
            'aud' => $audience,
            'exp' => time() + 43200,
            'sub' => 'mailto:' . sanitize_email(get_option('admin_email')),
        ]));
        $unsigned = $header . '.' . $claims;
        if (!openssl_sign($unsigned, $derSignature, $private, OPENSSL_ALGO_SHA256)) {
            return false;
        }
        $rawSignature = $this->der_to_raw_signature($derSignature);
        return $rawSignature ? $unsigned . '.' . self::base64url_encode($rawSignature) : false;
    }

    private function encrypt_payload($payload, $clientKeys) {
        $clientPublic = self::base64url_decode($clientKeys['p256dh']);
        $authSecret = self::base64url_decode($clientKeys['auth']);
        if (strlen($clientPublic) !== 65 || strlen($authSecret) < 16) {
            return false;
        }

        $clientDer = hex2bin('3059301306072A8648CE3D020106082A8648CE3D030107034200') . $clientPublic;
        $clientPem = "-----BEGIN PUBLIC KEY-----\n" . chunk_split(base64_encode($clientDer), 64, "\n") . "-----END PUBLIC KEY-----\n";
        $clientKey = openssl_pkey_get_public($clientPem);
        $serverKey = openssl_pkey_new(['private_key_type' => OPENSSL_KEYTYPE_EC, 'curve_name' => 'prime256v1']);
        if (!$clientKey || !$serverKey) {
            return false;
        }
        $sharedSecret = openssl_pkey_derive($clientKey, $serverKey, 32);
        $serverDetails = openssl_pkey_get_details($serverKey);
        if (!$sharedSecret || empty($serverDetails['ec']['x']) || empty($serverDetails['ec']['y'])) {
            return false;
        }
        $serverPublic = "\x04" . $serverDetails['ec']['x'] . $serverDetails['ec']['y'];

        $authPrk = hash_hmac('sha256', $sharedSecret, $authSecret, true);
        $keyInfo = "WebPush: info\x00" . $clientPublic . $serverPublic;
        $inputKeyMaterial = $this->hkdf_expand($authPrk, $keyInfo, 32);
        $salt = random_bytes(16);
        $prk = hash_hmac('sha256', $inputKeyMaterial, $salt, true);
        $cek = $this->hkdf_expand($prk, "Content-Encoding: aes128gcm\x00", 16);
        $nonce = $this->hkdf_expand($prk, "Content-Encoding: nonce\x00", 12);
        $plain = $payload . "\x02";
        $cipher = openssl_encrypt($plain, 'aes-128-gcm', $cek, OPENSSL_RAW_DATA, $nonce, $tag, '', 16);
        if ($cipher === false) {
            return false;
        }
        return $salt . pack('N', 4096) . chr(strlen($serverPublic)) . $serverPublic . $cipher . $tag;
    }

    private function hkdf_expand($prk, $info, $length) {
        $output = '';
        $last = '';
        for ($i = 1; strlen($output) < $length; $i++) {
            $last = hash_hmac('sha256', $last . $info . chr($i), $prk, true);
            $output .= $last;
        }
        return substr($output, 0, $length);
    }

    private function der_to_raw_signature($der) {
        $offset = 0;
        if (ord($der[$offset++]) !== 0x30) {
            return false;
        }
        $sequenceLength = ord($der[$offset++]);
        if ($sequenceLength & 0x80) {
            $bytes = $sequenceLength & 0x7f;
            $sequenceLength = 0;
            for ($i = 0; $i < $bytes; $i++) {
                $sequenceLength = ($sequenceLength << 8) | ord($der[$offset++]);
            }
        }
        if (ord($der[$offset++]) !== 0x02) {
            return false;
        }
        $rLength = ord($der[$offset++]);
        $r = substr($der, $offset, $rLength);
        $offset += $rLength;
        if (ord($der[$offset++]) !== 0x02) {
            return false;
        }
        $sLength = ord($der[$offset++]);
        $s = substr($der, $offset, $sLength);
        $r = str_pad(ltrim($r, "\x00"), 32, "\x00", STR_PAD_LEFT);
        $s = str_pad(ltrim($s, "\x00"), 32, "\x00", STR_PAD_LEFT);
        return strlen($r) === 32 && strlen($s) === 32 ? $r . $s : false;
    }

    private static function base64url_encode($value) {
        return rtrim(strtr(base64_encode($value), '+/', '-_'), '=');
    }

    private static function base64url_decode($value) {
        $value = strtr((string) $value, '-_', '+/');
        return base64_decode($value . str_repeat('=', (4 - strlen($value) % 4) % 4), true);
    }
}
