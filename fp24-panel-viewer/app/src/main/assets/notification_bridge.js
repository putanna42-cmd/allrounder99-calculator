(function () {
    'use strict';

    if (window.__fp24BridgeHealthV4) return;
    window.__fp24BridgeHealthV4 = true;

    var path = String(window.location.pathname || '').toLowerCase();
    var pageType = path.indexOf('/withdraw') === 0 ? 'withdraw'
        : (path.indexOf('/deposit') === 0 ? 'deposit' : '');
    if (!pageType) return;

    var seen = Object.create(null);
    var eventCounter = 0;
    var observedBody = null;
    var rowObserver = null;

    function cleanId(value) {
        if (value === null || typeof value === 'undefined') return '';
        var text = String(value).replace(/\s+/g, ' ').trim();
        return text.length <= 180 ? text : text.slice(0, 180);
    }

    function hashText(value) {
        var text = String(value || '');
        var hash = 2166136261;
        for (var i = 0; i < text.length; i += 1) {
            hash ^= text.charCodeAt(i);
            hash += (hash << 1) + (hash << 4) + (hash << 7)
                + (hash << 8) + (hash << 24);
        }
        return 'h-' + (hash >>> 0).toString(16);
    }

    function firstValue(object, names) {
        if (!object || typeof object !== 'object') return '';
        for (var i = 0; i < names.length; i += 1) {
            var value = object[names[i]];
            if (value !== null && typeof value !== 'undefined' && String(value).trim()) {
                return cleanId(value);
            }
        }
        return '';
    }

    function eventIdentity(event, payload, eventType) {
        var names = eventType === 'deposit'
            ? ['id', 'deposit_id', 'transaction_id', 'request_id']
            : ['id', 'withdraw_id', 'withdrawal_id', 'transaction_id', 'request_id'];
        var id = firstValue(payload, names) || firstValue(event, names);
        if (id) return id;

        try {
            var serialized = JSON.stringify(event || payload || {});
            if (serialized && serialized !== '{}') return hashText(serialized);
        } catch (ignored) {
            // A generated id below still guarantees that back-to-back events are not dropped.
        }
        eventCounter += 1;
        return 'live-' + Date.now() + '-' + eventCounter;
    }

    function report(type, id) {
        if (!window.PanelBridge || typeof window.PanelBridge.onEvent !== 'function') return;
        var eventId = cleanId(id);
        if (!eventId) {
            eventCounter += 1;
            eventId = 'live-' + Date.now() + '-' + eventCounter;
        }
        var key = type + ':' + eventId;
        if (seen[key]) return;
        seen[key] = true;
        window.PanelBridge.onEvent(type, eventId);
    }

    function findUserId() {
        var scripts = Array.prototype.slice.call(document.scripts || []);
        for (var i = 0; i < scripts.length; i += 1) {
            var text = scripts[i].textContent || '';
            var match = text.match(/Echo\.private\(\s*[`'"]user\.(\d+)[`'"]/);
            if (match) return match[1];
        }

        var statement = document.querySelector('a[href*="/account-statement"]');
        if (statement) {
            var href = statement.getAttribute('href') || '';
            var hrefMatch = href.match(/\/(\d+)\/account-statement/);
            if (hrefMatch) return hrefMatch[1];
        }
        return '';
    }

    function reconnectEchoIfNeeded() {
        try {
            if (typeof Echo === 'undefined' || !Echo || !Echo.connector) return;
            var pusher = Echo.connector.pusher;
            var connection = pusher && pusher.connection;
            if (!connection || typeof connection.connect !== 'function') return;
            if (connection.state === 'disconnected'
                    || connection.state === 'unavailable'
                    || connection.state === 'failed') {
                connection.connect();
            }
        } catch (ignored) {
            // The native page-health reload is the second reconnect fallback.
        }
    }

    function attachExactEchoListener() {
        var echoClient;
        try {
            if (typeof Echo === 'undefined' || !Echo || typeof Echo.private !== 'function') {
                return false;
            }
            echoClient = Echo;
        } catch (ignored) {
            return false;
        }

        var userId = findUserId();
        if (!userId) return false;

        try {
            var channel = echoClient.private('user.' + userId);
            var marker = '__fp24NativeV4_AllRequests';
            if (channel[marker]) return true;
            channel[marker] = true;

            channel.listen('.DepositAdded', function (event) {
                var deposit = event && event.deposit ? event.deposit : {};
                report('deposit', eventIdentity(event, deposit, 'deposit'));
            });
            channel.listen('.WithdrawAdded', function (event) {
                var withdraw = event && (event.withdraw || event.withdrawal)
                    ? (event.withdraw || event.withdrawal) : {};
                report('withdraw', eventIdentity(event, withdraw, 'withdraw'));
            });
            return true;
        } catch (ignored) {
            return false;
        }
    }

    function rowEventId(row) {
        if (!row || row.nodeType !== 1) return '';
        if (row.classList && row.classList.contains('dataTables_empty')) return '';

        var direct = row.getAttribute('data-id')
            || row.getAttribute('data-deposit-id')
            || row.getAttribute('data-withdraw-id')
            || row.getAttribute('data-withdrawal-id');
        if (direct) return cleanId(direct);

        var identified = row.querySelector(
            '[data-id], [data-deposit-id], [data-withdraw-id], [data-withdrawal-id]');
        if (identified) {
            direct = identified.getAttribute('data-id')
                || identified.getAttribute('data-deposit-id')
                || identified.getAttribute('data-withdraw-id')
                || identified.getAttribute('data-withdrawal-id');
            if (direct) return cleanId(direct);
        }

        var links = Array.prototype.slice.call(row.querySelectorAll('a[href]'));
        for (var i = 0; i < links.length; i += 1) {
            var href = links[i].getAttribute('href') || '';
            var match = href.match(/\/(?:deposits?|withdrawals?)\/(\d+)(?:\D|$)/i)
                || href.match(/[?&](?:id|deposit_id|withdraw_id|withdrawal_id)=(\d+)/i);
            if (match) return cleanId(match[1]);
        }

        var cells = Array.prototype.slice.call(row.cells || []);
        if (!cells.length) return '';
        var stableText = cells.slice(0, Math.min(cells.length, 6)).map(function (cell) {
            return cleanId(cell.textContent || '');
        }).join('|');
        return stableText ? hashText(stableText) : '';
    }

    function tableBody() {
        var selector = pageType === 'deposit'
            ? '.deposit_list_table tbody'
            : '.withdraw_list_table tbody';
        return document.querySelector(selector);
    }

    function currentRowIds() {
        var body = tableBody();
        if (!body) return [];
        var ids = [];
        Array.prototype.forEach.call(body.querySelectorAll('tr'), function (row) {
            var id = rowEventId(row);
            if (id && ids.indexOf(id) === -1) ids.push(id);
        });
        return ids;
    }

    function sendSnapshot() {
        if (!window.PanelBridge || typeof window.PanelBridge.onSnapshot !== 'function') return;
        window.PanelBridge.onSnapshot(pageType, JSON.stringify(currentRowIds()));
    }

    function attachTableObserver() {
        var body = tableBody();
        if (!body || typeof MutationObserver === 'undefined') return false;
        if (body === observedBody && rowObserver) return true;
        if (rowObserver) rowObserver.disconnect();
        observedBody = body;
        rowObserver = new MutationObserver(function (mutations) {
            mutations.forEach(function (mutation) {
                Array.prototype.forEach.call(mutation.addedNodes || [], function (node) {
                    if (!node || node.nodeType !== 1) return;
                    if (node.tagName === 'TR') {
                        var id = rowEventId(node);
                        if (id) report(pageType, id);
                    }
                    if (node.querySelectorAll) {
                        Array.prototype.forEach.call(node.querySelectorAll('tr'), function (row) {
                            var nestedId = rowEventId(row);
                            if (nestedId) report(pageType, nestedId);
                        });
                    }
                });
            });
        });
        rowObserver.observe(body, { childList: true, subtree: true });
        sendSnapshot();
        return true;
    }

    function heartbeat() {
        window.__fp24BridgeHealthV4 = true;
        if (window.PanelBridge && typeof window.PanelBridge.onHeartbeat === 'function') {
            window.PanelBridge.onHeartbeat();
        }
    }

    attachExactEchoListener();
    attachTableObserver();
    heartbeat();

    window.setInterval(function () {
        reconnectEchoIfNeeded();
        attachExactEchoListener();
        attachTableObserver();
        heartbeat();
    }, 5000);

    window.setInterval(sendSnapshot, 20000);
}());
