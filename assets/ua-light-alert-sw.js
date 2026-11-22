/* UA Light Alert service worker */
'use strict';

self.addEventListener('install', function(event) {
    self.skipWaiting();
});

self.addEventListener('activate', function(event) {
    event.waitUntil(self.clients.claim());
});

self.addEventListener('push', function(event) {
    var payload = {};
    if (event.data) {
        try {
            payload = event.data.json();
        } catch (e) {
            payload = {body: event.data.text()};
        }
    }
    var options = {
        body: payload.body || 'Наближається зміна електропостачання.',
        icon: payload.icon || '',
        badge: payload.badge || '',
        tag: payload.tag || 'ua-light-alert',
        renotify: false,
        requireInteraction: true,
        vibrate: Array.isArray(payload.vibrate) ? payload.vibrate : [500, 200, 500],
        data: {url: payload.url || self.registration.scope}
    };
    event.waitUntil(self.registration.showNotification(payload.title || 'Графік світла', options));
});

self.addEventListener('notificationclick', function(event) {
    event.notification.close();
    var target = event.notification.data && event.notification.data.url
        ? event.notification.data.url
        : self.registration.scope;
    event.waitUntil(
        self.clients.matchAll({type: 'window', includeUncontrolled: true}).then(function(clients) {
            for (var i = 0; i < clients.length; i++) {
                if (clients[i].url.indexOf(self.registration.scope) === 0 && 'focus' in clients[i]) {
                    return clients[i].focus().then(function(client) {
                        if ('navigate' in client && client.url !== target) {
                            return client.navigate(target);
                        }
                        return client;
                    });
                }
            }
            return self.clients.openWindow(target);
        })
    );
});
