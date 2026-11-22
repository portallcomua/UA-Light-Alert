/* UA Light Alert service worker. */
'use strict';

self.addEventListener('install', function (event) {
    self.skipWaiting();
});

self.addEventListener('activate', function (event) {
    event.waitUntil(self.clients.claim());
});

self.addEventListener('notificationclick', function (event) {
    event.notification.close();
    var target = event.notification.data && event.notification.data.url
        ? event.notification.data.url
        : self.registration.scope;

    event.waitUntil(
        self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(function (clients) {
            for (var i = 0; i < clients.length; i++) {
                if ('focus' in clients[i]) {
                    return clients[i].focus();
                }
            }
            if (self.clients.openWindow) {
                return self.clients.openWindow(target);
            }
        })
    );
});

self.addEventListener('push', function (event) {
    var payload = {};
    try {
        payload = event.data ? event.data.json() : {};
    } catch (e) {
        payload = { body: event.data ? event.data.text() : '' };
    }

    var title = payload.title || 'UA Light Alert';
    var options = {
        body: payload.body || 'Є оновлення графіка електропостачання.',
        icon: payload.icon || '',
        badge: payload.badge || '',
        tag: payload.tag || 'ua-light-alert',
        renotify: true,
        data: { url: payload.url || self.registration.scope }
    };

    event.waitUntil(self.registration.showNotification(title, options));
});
