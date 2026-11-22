<?php
define('ABSPATH', __DIR__);
$GLOBALS['ua_test_options'] = [];

function add_filter() {}
function add_action() {}
function get_option($key, $default = false) {
    return array_key_exists($key, $GLOBALS['ua_test_options']) ? $GLOBALS['ua_test_options'][$key] : $default;
}
function update_option($key, $value, $autoload = null) {
    $GLOBALS['ua_test_options'][$key] = $value;
    return true;
}
function wp_json_encode($value) {
    return json_encode($value);
}
function sanitize_email($value) {
    return filter_var((string) $value, FILTER_SANITIZE_EMAIL);
}

require_once __DIR__ . '/../includes/class-ua-light-alert-push.php';

function b64url($value) {
    return rtrim(strtr(base64_encode($value), '+/', '-_'), '=');
}
function hkdf_expand_test($prk, $info, $length) {
    $output = '';
    $last = '';
    for ($i = 1; strlen($output) < $length; $i++) {
        $last = hash_hmac('sha256', $last . $info . chr($i), $prk, true);
        $output .= $last;
    }
    return substr($output, 0, $length);
}

if (!extension_loaded('openssl')) {
    fwrite(STDERR, "OpenSSL extension is required.\n");
    exit(1);
}

$service = new UA_Light_Alert_Push();
$publicKey = UA_Light_Alert_Push::get_public_key();
if (!$publicKey || strlen(base64_decode(strtr($publicKey, '-_', '+/'))) < 65) {
    throw new RuntimeException('VAPID public key generation failed.');
}

$clientPrivate = openssl_pkey_new([
    'private_key_type' => OPENSSL_KEYTYPE_EC,
    'curve_name' => 'prime256v1',
]);
$clientDetails = openssl_pkey_get_details($clientPrivate);
$clientPublic = "\x04" . $clientDetails['ec']['x'] . $clientDetails['ec']['y'];
$authSecret = random_bytes(16);
$clientKeys = ['p256dh' => b64url($clientPublic), 'auth' => b64url($authSecret)];

$encrypt = new ReflectionMethod(UA_Light_Alert_Push::class, 'encrypt_payload');
$encrypt->setAccessible(true);
$payload = '{"title":"Test","body":"Push encryption check"}';
$record = $encrypt->invoke($service, $payload, $clientKeys);
if (!is_string($record) || strlen($record) < 100) {
    throw new RuntimeException('Web Push payload encryption returned invalid output.');
}

$salt = substr($record, 0, 16);
$recordSize = unpack('N', substr($record, 16, 4))[1];
$keyLength = ord($record[20]);
$serverPublic = substr($record, 21, $keyLength);
if ($recordSize !== 4096 || $keyLength !== 65 || strlen($serverPublic) !== 65 || $serverPublic[0] !== "\x04") {
    throw new RuntimeException('Web Push aes128gcm record header is invalid.');
}

$serverDer = hex2bin('3059301306072A8648CE3D020106082A8648CE3D030107034200') . $serverPublic;
$serverPem = "-----BEGIN PUBLIC KEY-----\n" . chunk_split(base64_encode($serverDer), 64, "\n") . "-----END PUBLIC KEY-----\n";
$serverPublicKey = openssl_pkey_get_public($serverPem);
$shared = openssl_pkey_derive($serverPublicKey, $clientPrivate, 32);
$authPrk = hash_hmac('sha256', $shared, $authSecret, true);
$inputKey = hkdf_expand_test($authPrk, "WebPush: info\x00" . $clientPublic . $serverPublic, 32);
$prk = hash_hmac('sha256', $inputKey, $salt, true);
$cek = hkdf_expand_test($prk, "Content-Encoding: aes128gcm\x00", 16);
$nonce = hkdf_expand_test($prk, "Content-Encoding: nonce\x00", 12);
$cipherAndTag = substr($record, 86);
$tag = substr($cipherAndTag, -16);
$cipher = substr($cipherAndTag, 0, -16);
$decrypted = openssl_decrypt($cipher, 'aes-128-gcm', $cek, OPENSSL_RAW_DATA, $nonce, $tag);
if ($decrypted !== $payload . "\x02") {
    throw new RuntimeException('Web Push encryption round-trip failed.');
}

$jwtMethod = new ReflectionMethod(UA_Light_Alert_Push::class, 'create_vapid_jwt');
$jwtMethod->setAccessible(true);
$keys = get_option(UA_Light_Alert_Push::KEYS_OPTION);
$jwt = $jwtMethod->invoke($service, 'https://push.example.test', $keys);
$parts = explode('.', (string) $jwt);
if (count($parts) !== 3) {
    throw new RuntimeException('VAPID JWT generation failed.');
}
$claims = json_decode(base64_decode(strtr($parts[1] . str_repeat('=', (4 - strlen($parts[1]) % 4) % 4), '-_', '+/')), true);
if (empty($claims['aud']) || $claims['aud'] !== 'https://push.example.test' || empty($claims['exp'])) {
    throw new RuntimeException('VAPID JWT claims are invalid.');
}

echo "Web Push crypto tests passed.\n";
