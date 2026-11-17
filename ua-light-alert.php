<?php
/**
 * Plugin Name: UA Light Alert
 * Description: Відображення графіків відключень + CRM + Звукове оповіщення
 * Version: 4.0
 * Author: You
 */

if (!defined('ABSPATH')) exit;

class UA_Light_Alert {

    private $option_key = 'ua_light_ads';
    private $settings_key = 'ua_light_settings';

    public function __construct() {
        add_shortcode('ua_light_schedule', [$this, 'render_frontend']);
        add_action('admin_menu', [$this, 'add_admin_menu']);
        add_action('admin_init', [$this, 'handle_admin_actions']);
        add_action('wp_ajax_ua_track_ad', [$this, 'track_ad_click']);
        add_action('wp_ajax_nopriv_ua_track_ad', [$this, 'track_ad_click']);
    }

    /* ---------- Адмінка ---------- */

    public function add_admin_menu() {
        add_menu_page('UA Light Alert', 'UA Light Alert', 'manage_options', 'ua-light-alert', [$this, 'render_admin_page'], 'dashicons-lightbulb', 30);
    }

    public function handle_admin_actions() {
        if (!current_user_can('manage_options')) return;

        // Збереження налаштувань (хвилини попередження)
        if (isset($_POST['ua_save_settings']) && check_admin_referer('ua_settings_action')) {
            $settings = [
                'notice_minutes' => intval($_POST['notice_minutes']),
                'sound_url' => esc_url_raw($_POST['sound_url'])
            ];
            update_option($this->settings_key, $settings);
            wp_redirect(admin_url('admin.php?page=ua-light-alert&msg=settings_saved'));
            exit;
        }

        // Додавання / Редагування рекламодавця
        if (isset($_POST['ua_save_ad']) && check_admin_referer('ua_save_ad_action')) {
            $ads = get_option($this->option_key, []);
            $ad_id = isset($_POST['ad_id']) ? intval($_POST['ad_id']) : -1;
            
            $ad_data = [
                'name'    => sanitize_text_field($_POST['ad_name']),
                'email'   => sanitize_email($_POST['ad_email']),
                'contacts'=> sanitize_textarea_field($_POST['ad_contacts']),
                'region'  => sanitize_text_field($_POST['ad_region']),
                'group'   => sanitize_text_field($_POST['ad_group']),
                'html'    => wp_kses_post($_POST['ad_html']),
                'cost'    => sanitize_text_field($_POST['ad_cost']),
                'period_start' => sanitize_text_field($_POST['ad_period_start']),
                'period_end'   => sanitize_text_field($_POST['ad_period_end']),
                'payment_info' => sanitize_textarea_field($_POST['ad_payment_info']),
            ];

            if ($ad_id >= 0 && isset($ads[$ad_id])) {
                $ad_data['impressions'] = $ads[$ad_id]['impressions'] ?? 0;
                $ad_data['clicks'] = $ads[$ad_id]['clicks'] ?? 0;
                $ads[$ad_id] = $ad_data;
            } else {
                $ad_data['impressions'] = 0;
                $ad_data['clicks'] = 0;
                $ads[] = $ad_data;
            }
            update_option($this->option_key, $ads);
            wp_redirect(admin_url('admin.php?page=ua-light-alert&msg=saved'));
            exit;
        }

        // Видалення
        if (isset($_GET['delete_ad']) && isset($_GET['_wpnonce'])) {
            if (wp_verify_nonce($_GET['_wpnonce'], 'ua_delete_ad')) {
                $ads = get_option($this->option_key, []);
                $index = intval($_GET['delete_ad']);
                if (isset($ads[$index])) {
                    unset($ads[$index]);
                    update_option($this->option_key, array_values($ads));
                }
                wp_redirect(admin_url('admin.php?page=ua-light-alert&msg=deleted'));
                exit;
            }
        }
    }

    public function render_admin_page() {
        $ads = get_option($this->option_key, []);
        $settings = get_option($this->settings_key, ['notice_minutes' => 30, 'sound_url' => '']);
        
        $edit_id = isset($_GET['edit_ad']) ? intval($_GET['edit_ad']) : -1;
        $edit_ad = ($edit_id >= 0 && isset($ads[$edit_id])) ? $ads[$edit_id] : null;

        if (isset($_GET['msg'])) {
            echo '<div class="notice notice-success is-dismissible"><p>Зміни збережено!</p></div>';
        }
        ?>
        <div class="wrap">
            <h1>UA Light Alert — Налаштування та Рекламодавці</h1>

            <!-- Налаштування звуку -->
            <div style="background: #fff; padding: 20px; border: 1px solid #ccd0d4; margin-bottom: 20px;">
                <h2>🔔 Налаштування звукового оповіщення</h2>
                <form method="post">
                    <?php wp_nonce_field('ua_settings_action'); ?>
                    <table class="form-table">
                        <tr>
                            <th>Попереджати за (хвилин)</th>
                            <td>
                                <input type="number" name="notice_minutes" value="<?php echo esc_attr($settings['notice_minutes']); ?>" min="1" max="120">
                                <p class="description">Якщо до зміни статусу залишиться менше цього часу, з'явиться таймер.</p>
                            </td>
                        </tr>
                        <tr>
                            <th>Посилання на MP3 файл</th>
                            <td>
                                <input type="url" name="sound_url" value="<?php echo esc_attr($settings['sound_url']); ?>" class="regular-text" placeholder="https://ваш-сайт.com/wp-content/uploads/alert.mp3">
                                <p class="description">Завантажте mp3-файл через "Медіафайли" і скопіюйте його URL сюди.</p>
                            </td>
                        </tr>
                    </table>
                    <?php submit_button('Зберегти налаштування', 'primary', 'ua_save_settings'); ?>
                </form>
            </div>

            <div style="display:flex; gap:20px; align-items:flex-start;">
                <!-- Форма рекламодавця -->
                <div style="flex: 1; background: #fff; padding: 20px; border: 1px solid #ccd0d4;">
                    <h2><?php echo $edit_ad ? 'Редагувати клієнта' : 'Додати нового клієнта'; ?></h2>
                    <form method="post">
                        <?php wp_nonce_field('ua_save_ad_action'); ?>
                        <?php if ($edit_ad): ?>
                            <input type="hidden" name="ad_id" value="<?php echo $edit_id; ?>">
                        <?php endif; ?>
                        
                        <table class="form-table">
                            <tr>
                                <th>Назва компанії</th>
                                <td><input type="text" name="ad_name" required class="regular-text" value="<?php echo esc_attr($edit_ad['name'] ?? ''); ?>"></td>
                            </tr>
                            <tr>
                                <th>Email</th>
                                <td><input type="email" name="ad_email" class="regular-text" value="<?php echo esc_attr($edit_ad['email'] ?? ''); ?>"></td>
                            </tr>
                            <tr>
                                <th>Загальні контакти</th>
                                <td><textarea name="ad_contacts" rows="3" class="large-text"><?php echo esc_textarea($edit_ad['contacts'] ?? ''); ?></textarea></td>
                            </tr>
                            <tr>
                                <th>Регіон (regionId)</th>
                                <td>
                                    <input type="text" name="ad_region" placeholder="напр. kyiv" class="regular-text" value="<?php echo esc_attr($edit_ad['region'] ?? ''); ?>">
                                </td>
                            </tr>
                            <tr>
                                <th>Група</th>
                                <td>
                                    <input type="text" name="ad_group" placeholder="напр. 3.1" class="regular-text" value="<?php echo esc_attr($edit_ad['group'] ?? ''); ?>">
                                </td>
                            </tr>
                            <tr>
                                <th>HTML реклами</th>
                                <td><textarea name="ad_html" rows="4" class="large-text" required><?php echo esc_textarea($edit_ad['html'] ?? ''); ?></textarea></td>
                            </tr>
                            <tr>
                                <th>Вартість</th>
                                <td><input type="text" name="ad_cost" class="regular-text" value="<?php echo esc_attr($edit_ad['cost'] ?? ''); ?>"></td>
                            </tr>
                            <tr>
                                <th>Період</th>
                                <td>
                                    <input type="date" name="ad_period_start" value="<?php echo esc_attr($edit_ad['period_start'] ?? ''); ?>">
                                    &mdash;
                                    <input type="date" name="ad_period_end" value="<?php echo esc_attr($edit_ad['period_end'] ?? ''); ?>">
                                </td>
                            </tr>
                            <tr>
                                <th>Коли і як сплачено</th>
                                <td><textarea name="ad_payment_info" rows="2" class="large-text"><?php echo esc_textarea($edit_ad['payment_info'] ?? ''); ?></textarea></td>
                            </tr>
                        </table>
                        
                        <?php submit_button($edit_ad ? 'Зберегти зміни' : 'Додати клієнта', 'primary', 'ua_save_ad'); ?>
                        <?php if ($edit_ad): ?>
                            <a href="<?php echo admin_url('admin.php?page=ua-light-alert'); ?>" class="button">Скасувати</a>
                        <?php endif; ?>
                    </form>
                </div>

                <!-- Список рекламодавців -->
                <div style="flex: 2;">
                    <h2>База клієнтів та статистика</h2>
                    <?php if (empty($ads)): ?>
                        <p>Ще немає клієнтів.</p>
                    <?php else: ?>
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
                            <?php foreach ($ads as $i => $ad): ?>
                                <tr>
                                    <td>
                                        <strong><?php echo esc_html($ad['name']); ?></strong><br>
                                        <small><?php echo esc_html($ad['email']); ?></small>
                                    </td>
                                    <td style="font-size:12px;">
                                        <?php echo nl2br(esc_html($ad['contacts'])); ?>
                                    </td>
                                    <td style="font-size:12px;">
                                        <b>Регіон:</b> <?php echo esc_html($ad['region'] ?: 'Усі'); ?><br>
                                        <b>Група:</b> <?php echo esc_html($ad['group'] ?: 'Усі'); ?><br>
                                        <b>Вартість:</b> <?php echo esc_html($ad['cost']); ?><br>
                                        <b>Період:</b> <?php echo esc_html($ad['period_start']); ?> - <?php echo esc_html($ad['period_end']); ?>
                                    </td>
                                    <td style="font-size:12px;">
                                        👁 Показів: <b><?php echo intval($ad['impressions'] ?? 0); ?></b><br>
                                        🖱 Кліків: <b><?php echo intval($ad['clicks'] ?? 0); ?></b>
                                    </td>
                                    <td>
                                        <a href="<?php echo admin_url('admin.php?page=ua-light-alert&edit_ad=' . $i); ?>">Редагувати</a> | 
                                        <a href="<?php echo wp_nonce_url(add_query_arg(['delete_ad' => $i], admin_url('admin.php?page=ua-light-alert')), 'ua_delete_ad'); ?>" onclick="return confirm('Видалити?')" style="color:red;">Видалити</a>
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

    /* ---------- Фронтенд ---------- */

    public function render_frontend() {
        $settings = get_option($this->settings_key, ['notice_minutes' => 30, 'sound_url' => '']);
        $notice_minutes = intval($settings['notice_minutes']);
        $sound_url = $settings['sound_url'];

        ob_start();
        ?>
        <!-- Аудіоелемент для звуку -->
        <audio id="ua-alert-sound" preload="auto">
            <source src="<?php echo esc_url($sound_url); ?>" type="audio/mpeg">
        </audio>

        <div id="ua-light-widget" style="padding: 15px; border: 1px solid #eee; border-radius: 8px; font-family: sans-serif;">
            <div class="ua-controls">
                <label style="display:block; margin-bottom:10px; font-weight:bold;">Регіон:
                    <select id="ua-region" style="width: 100%; padding: 8px; margin-top:5px;">
                        <option value="">— Оберіть —</option>
                        <option value="kyiv">Київ</option>
                        <option value="kyiv-region">Київська область</option>
                        <option value="odesa">Одеська область</option>
                        <option value="dnipro">Дніпропетровська область</option>
                        <option value="lviv">Львівська область</option>
                        <option value="kharkiv">Харківська область</option>
                    </select>
                </label>

                <label style="display:block; margin-bottom:10px; font-weight:bold;">Група:
                    <select id="ua-group" style="width: 100%; padding: 8px; margin-top:5px;" disabled>
                        <option value="">Спочатку оберіть регіон</option>
                    </select>
                </label>

                <button id="ua-show" style="padding: 10px 20px; background: #ff6b00; color: white; border: none; border-radius: 4px; font-weight: bold; width: 100%; cursor:pointer;">Показати графік та увімкнути таймер</button>
            </div>

            <!-- Блок таймера -->
            <div id="ua-timer-box" style="display:none; margin-top:15px; padding:15px; background:#fff3cd; border-left:4px solid #ffc107; border-radius:4px;">
                <h3 style="margin:0 0 10px 0; color:#856404;">⏳ Наступна зміна статусу:</h3>
                <p id="ua-timer-text" style="font-size:24px; font-weight:bold; margin:0; color:#856404; text-align:center;">--:--</p>
            </div>

            <div id="ua-result" style="margin-top:15px; font-size: 14px;"></div>
            <div id="ua-ad-slot" style="margin-top:20px;"></div>
        </div>

        <script>
        document.addEventListener('DOMContentLoaded', function() {
            var regionSelect = document.getElementById('ua-region');
            var groupSelect = document.getElementById('ua-group');
            var resultDiv = document.getElementById('ua-result');
            var adSlot = document.getElementById('ua-ad-slot');
            var timerBox = document.getElementById('ua-timer-box');
            var timerText = document.getElementById('ua-timer-text');
            var soundEl = document.getElementById('ua-alert-sound');
            
            var currentData = null;
            var timerInterval = null;
            var noticeMinutes = <?php echo $notice_minutes; ?>;

            regionSelect.addEventListener('change', function() {
                var region = this.value;
                groupSelect.innerHTML = '<option value="">Завантаження груп...</option>';
                groupSelect.disabled = true;
                resultDiv.innerHTML = '';
                adSlot.innerHTML = '';
                timerBox.style.display = 'none';
                if(timerInterval) clearInterval(timerInterval);

                if (!region) {
                    groupSelect.innerHTML = '<option value="">Спочатку оберіть регіон</option>';
                    return;
                }

                fetch('https://raw.githubusercontent.com/Baskerville42/outage-data-ua/main/data/' + region + '.json')
                    .then(r => r.json())
                    .then(data => {
                        currentData = data;
                        groupSelect.innerHTML = '<option value="">— Оберіть групу —</option>';
                        
                        if (data.fact && data.fact.data) {
                            var timeKeys = Object.keys(data.fact.data);
                            if (timeKeys.length > 0) {
                                var groups = Object.keys(data.fact.data[timeKeys[0]]);
                                groups.forEach(function(g) {
                                    var opt = document.createElement('option');
                                    opt.value = g;
                                    opt.textContent = g.replace('GPV', '');
                                    groupSelect.appendChild(opt);
                                });
                            }
                        }
                        groupSelect.disabled = false;
                    })
                    .catch(err => {
                        groupSelect.innerHTML = '<option value="">Помилка завантаження</option>';
                    });
            });

            document.getElementById('ua-show').addEventListener('click', function() {
                var region = regionSelect.value;
                var group = groupSelect.value;

                if (!region) { alert('Оберіть регіон'); return; }
                if (!group) { alert('Оберіть групу'); return; }
                if (!currentData) { alert('Дані ще не завантажені'); return; }

                // Розблокування звуку (браузери вимагають кліку для відтворення)
                if(soundEl) {
                    soundEl.play().then(() => { soundEl.pause(); soundEl.currentTime = 0; }).catch(e => {});
                }

                resultDiv.innerHTML = '';
                timerBox.style.display = 'none';
                if(timerInterval) clearInterval(timerInterval);
                
                var timeKeys = Object.keys(currentData.fact.data);
                if (timeKeys.length === 0) {
                    resultDiv.innerHTML = '<p>Немає даних для цього регіону.</p>';
                    return;
                }

                var latestTime = timeKeys[timeKeys.length - 1]; 
                var groupData = currentData.fact.data[latestTime][group];

                if (!groupData) {
                    resultDiv.innerHTML = '<p>Дані для цієї групи відсутні.</p>';
                    return;
                }

                var upd = currentData.lastUpdated || '—';
                var updP = document.createElement('p');
                updP.innerHTML = '<strong>Оновлено:</strong> ' + new Date(upd).toLocaleString('uk-UA');
                resultDiv.appendChild(updP);

                // Малюємо таблицю
                var table = document.createElement('table');
                table.style.width = '100%';
                table.style.borderCollapse = 'collapse';
                table.style.textAlign = 'center';
                table.style.fontSize = '13px';

                var thead = document.createElement('thead');
                var trHead = document.createElement('tr');
                trHead.innerHTML = '<th style="border:1px solid #ddd; padding:5px; background:#f5f5f5;">Година</th><th style="border:1px solid #ddd; padding:5px; background:#f5f5f5;">Статус</th>';
                thead.appendChild(trHead);
                table.appendChild(thead);

                var tbody = document.createElement('tbody');
                
                for (var h = 1; h <= 24; h++) {
                    var status = groupData[h.toString()];
                    var tr = document.createElement('tr');
                    
                    var tdHour = document.createElement('td');
                    tdHour.style.border = '1px solid #ddd';
                    tdHour.style.padding = '5px';
                    tdHour.textContent = (h < 10 ? '0' : '') + h + ':00 - ' + (h < 10 ? '0' : '') + h + ':59';
                    
                    var tdStatus = document.createElement('td');
                    tdStatus.style.border = '1px solid #ddd';
                    tdStatus.style.padding = '5px';
                    tdStatus.style.fontWeight = 'bold';

                    if (status === 'yes') {
                        tdStatus.textContent = 'Світло є';
                        tdStatus.style.backgroundColor = '#d4edda';
                        tdStatus.style.color = '#155724';
                    } else if (status === 'no') {
                        tdStatus.textContent = 'Світла немає';
                        tdStatus.style.backgroundColor = '#f8d7da';
                        tdStatus.style.color = '#721c24';
                    } else {
                        tdStatus.textContent = 'Перехід';
                        tdStatus.style.backgroundColor = '#fff3cd';
                        tdStatus.style.color = '#856404';
                    }

                    tr.appendChild(tdHour);
                    tr.appendChild(tdStatus);
                    tbody.appendChild(tr);
                }
                table.appendChild(tbody);
                resultDiv.appendChild(table);

                // Логіка таймера
                startTimer(groupData);

                loadAds(region, group, adSlot);
            });

            function startTimer(groupData) {
                // Отримуємо поточний час за Києвом
                var now = new Date();
                // Якщо у вас інший часовий пояс, це можна змінити. Київ це UTC+2 або UTC+3.
                // Для простоти беремо локальний час користувача.
                
                var currentHour = now.getHours(); 
                var currentMinute = now.getMinutes();

                // Шукаємо наступну зміну статусу
                var nextChangeHour = -1;
                var nextStatus = '';
                var currentStatus = groupData[(currentHour + 1).toString()]; // поточна година (індекси 1-24)

                // Проходимось по годинах, починаючи з наступної
                for (var i = 1; i <= 25; i++) {
                    var checkHour = (currentHour + i) % 24;
                    if (checkHour === 0) checkHour = 24; // Кінець дня
                    
                    var status = groupData[checkHour.toString()];
                    if (status && status !== currentStatus && status !== 'first' && status !== 'second') {
                        nextChangeHour = checkHour;
                        nextStatus = status;
                        break;
                    }
                }

                if (nextChangeHour === -1) {
                    timerText.textContent = "Змін не знайдено";
                    timerBox.style.display = 'block';
                    return;
                }

                // Обчислюємо час до наступної зміни
                var targetTime = new Date();
                targetTime.setHours(nextChangeHour - 1, 0, 0, 0); // Початок години
                
                if (targetTime < now) {
                    targetTime.setDate(targetTime.getDate() + 1);
                }

                var diffMs = targetTime - now;
                var diffMins = Math.floor(diffMs / 60000);

                // Показуємо таймер тільки якщо залишилось менше noticeMinutes
                if (diffMins <= noticeMinutes && diffMins >= 0) {
                    timerBox.style.display = 'block';
                    var statusText = nextStatus === 'yes' ? 'Світло з\'явиться' : 'Світло зникне';
                    
                    timerInterval = setInterval(function() {
                        var now2 = new Date();
                        var diff2 = targetTime - now2;
                        
                        if (diff2 <= 0) {
                            clearInterval(timerInterval);
                            timerText.textContent = "Час настав!";
                            
                            // Відтворюємо звук
                            if(soundEl && soundEl.src) {
                                soundEl.play().catch(e => console.log('Помилка звуку:', e));
                            }
                            
                            // Вібрація
                            if(navigator.vibrate) {
                                navigator.vibrate([500, 200, 500]);
                            }

                            // Попап
                            alert('Увага! ' + statusText + '!');
                            
                            // Оновлюємо таймер
                            setTimeout(() => {
                                startTimer(groupData);
                            }, 1000);
                            
                            return;
                        }

                        var mins = Math.floor(diff2 / 60000);
                        var secs = Math.floor((diff2 % 60000) / 1000);
                        timerText.innerHTML = statusText + ' через <br>' + mins + ' хв ' + (secs < 10 ? '0' : '') + secs + ' сек';
                    }, 1000);
                } else {
                    timerBox.style.display = 'block';
                    timerText.textContent = 'Наступна зміна о ' + (nextChangeHour < 10 ? '0' : '') + nextChangeHour + ':00. Таймер увімкнеться за ' + noticeMinutes + ' хв.';
                }
            }

            function loadAds(region, group, container) {
                var xhr = new XMLHttpRequest();
                xhr.open('POST', '<?php echo admin_url('admin-ajax.php'); ?>');
                xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
                xhr.onload = function() {
                    if (xhr.status === 200) {
                        container.innerHTML = xhr.responseText;
                        if (xhr.responseText.indexOf('ua-ad-item') !== -1) {
                            var trackXhr = new XMLHttpRequest();
                            trackXhr.open('POST', '<?php echo admin_url('admin-ajax.php'); ?>');
                            trackXhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
                            trackXhr.send('action=ua_track_ad&type=impression&region=' + encodeURIComponent(region) + '&group=' + encodeURIComponent(group));
                        }
                    }
                };
                xhr.send('action=ua_get_ads&region=' + encodeURIComponent(region) + '&group=' + encodeURIComponent(group));
            }
        });
        </script>
        <?php
        return ob_get_clean();
    }

    /* ---------- AJAX ---------- */

    public function track_ad_click() {
        $type = sanitize_text_field($_POST['type'] ?? '');
        $region = sanitize_text_field($_POST['region'] ?? '');
        $group  = sanitize_text_field($_POST['group'] ?? '');
        $ads = get_option($this->option_key, []);
        
        foreach ($ads as $i => $ad) {
            if (!empty($ad['region']) && $ad['region'] !== $region) continue;
            if (!empty($ad['group']) && $ad['group'] !== $group) continue;

            if ($type === 'impression') {
                $ads[$i]['impressions'] = ($ad['impressions'] ?? 0) + 1;
            } elseif ($type === 'click') {
                $ads[$i]['clicks'] = ($ad['clicks'] ?? 0) + 1;
            }
        }
        update_option($this->option_key, $ads);
        wp_die();
    }
}

new UA_Light_Alert();

/* AJAX для отримання реклами */
add_action('wp_ajax_ua_get_ads', 'ua_light_get_ads');
add_action('wp_ajax_nopriv_ua_get_ads', 'ua_light_get_ads');

function ua_light_get_ads() {
    $region = sanitize_text_field($_POST['region'] ?? '');
    $group  = sanitize_text_field($_POST['group'] ?? '');
    $ads    = get_option('ua_light_ads', []);
    $output = '';
    $today  = date('Y-m-d');

    foreach ($ads as $ad) {
        if (!empty($ad['region']) && $ad['region'] !== $region) continue;
        if (!empty($ad['group']) && $ad['group'] !== $group) continue;
        if (!empty($ad['period_start']) && $today < $ad['period_start']) continue;
        if (!empty($ad['period_end']) && $today > $ad['period_end']) continue;

        $output .= '<div class="ua-ad-item" style="border:1px solid #ddd;padding:10px;margin-bottom:10px;cursor:pointer;" 
                         onclick="trackClick(\'' . esc_js($region) . '\', \'' . esc_js($group) . '\')">'
                 . $ad['html']
                 . '</div>';
    }

    if ($output) {
        $output .= '<script>
            function trackClick(region, group) {
                var xhr = new XMLHttpRequest();
                xhr.open("POST", "' . admin_url('admin-ajax.php') . '");
                xhr.setRequestHeader("Content-Type", "application/x-www-form-urlencoded");
                xhr.send("action=ua_track_ad&type=click&region=" + encodeURIComponent(region) + "&group=" + encodeURIComponent(group));
            }
        </script>';
    } else {
        $output = '<p><em>Реклами для цього запиту немає.</em></p>';
    }

    echo $output;
    wp_die();
}
