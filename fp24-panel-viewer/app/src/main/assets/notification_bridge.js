(function () {
    'use strict';

    if (window.__fp24ExactNotificationBridgeV2) return;
    window.__fp24ExactNotificationBridgeV2 = true;

    var path = String(window.location.pathname || '').toLowerCase();
    var pageType = path.indexOf('/withdraw') === 0 ? 'withdraw'
        : (path.indexOf('/deposit') === 0 ? 'deposit' : '');
    if (!pageType) return;

    var seen = Object.create(null);
    var lastWithoutId = 0;

    function cleanId(value) {
        if (value === null || typeof value === 'undefined') return '';
        var text = String(value).trim();
        return text.length <= 80 ? text : text.slice(0, 80);
    }

    function report(type, id) {
        if (!window.PanelBridge || typeof window.PanelBridge.onEvent !== 'function') return;

        var eventId = cleanId(id);
        if (eventId) {
            var key = type + ':' + eventId;
            if (seen[key]) return;
            seen[key] = true;
        } else {
            var now = Date.now();
            if (now - lastWithoutId < 2000) return;
            lastWithoutId = now;
        }
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
            var marker = '__fp24Native_' + pageType;
            if (channel[marker]) return true;
            channel[marker] = true;

            if (pageType === 'deposit') {
                channel.listen('.DepositAdded', function (event) {
                    var deposit = event && event.deposit ? event.deposit : {};
                    report('deposit', deposit.id || deposit.deposit_id || '');
                });
            } else {
                channel.listen('.WithdrawAdded', function (event) {
                    var withdraw = event && event.withdraw ? event.withdraw : {};
                    report('withdraw', withdraw.id || withdraw.withdraw_id || '');
                });
            }
            return true;
        } catch (ignored) {
            return false;
        }
    }

    var echoAttempts = 0;
    var echoTimer = window.setInterval(function () {
        echoAttempts += 1;
        if (attachExactEchoListener() || echoAttempts >= 60) {
            window.clearInterval(echoTimer);
        }
    }, 1000);
    attachExactEchoListener();

    function rowEventId(row) {
        if (!row || !row.cells || row.cells.length < 2) return '';
        return cleanId(row.cells[1].textContent || '');
    }

    function attachTableObserver() {
        var selector = pageType === 'deposit'
            ? '.deposit_list_table tbody'
            : '.withdraw_list_table tbody';
        var tableBody = document.querySelector(selector);
        if (!tableBody || typeof MutationObserver === 'undefined') return false;

        var observer = new MutationObserver(function (mutations) {
            mutations.forEach(function (mutation) {
                Array.prototype.forEach.call(mutation.addedNodes || [], function (node) {
                    if (!node || node.nodeType !== 1) return;
                    if (node.tagName === 'TR') {
                        report(pageType, rowEventId(node));
                    }
                    if (node.querySelectorAll) {
                        Array.prototype.forEach.call(node.querySelectorAll('tr'), function (row) {
                            report(pageType, rowEventId(row));
                        });
                    }
                });
            });
        });
        observer.observe(tableBody, { childList: true, subtree: true });
        return true;
    }

    window.setTimeout(function startObserverWhenStable() {
        if (!attachTableObserver()) {
            window.setTimeout(startObserverWhenStable, 1500);
        }
    }, 8000);
}());
