(function () {
    'use strict';

    if (window.__fp24NotificationBridgeInstalled) return;
    window.__fp24NotificationBridgeInstalled = true;

    var startedAt = Date.now();
    var lastSent = { deposit: 0, withdraw: 0 };

    function classify(text) {
        var value = String(text || '').toLowerCase();
        if (/withdraw|withdrawal|payout/.test(value)) return 'withdraw';
        if (/deposit|utr|add[ -]?balance/.test(value)) return 'deposit';
        return '';
    }

    function report(type) {
        if (!type || !window.PanelBridge || !window.PanelBridge.onEvent) return;
        var now = Date.now();
        if (now - lastSent[type] < 8000) return;
        lastSent[type] = now;
        window.PanelBridge.onEvent(type);
    }

    function inspectText(text) {
        report(classify(text));
    }

    function wrapToastr() {
        if (!window.toastr) return false;
        ['success', 'info', 'warning', 'error'].forEach(function (level) {
            var original = window.toastr[level];
            if (typeof original !== 'function' || original.__fp24Wrapped) return;
            var wrapped = function (message, title) {
                inspectText(String(title || '') + ' ' + String(message || ''));
                return original.apply(this, arguments);
            };
            wrapped.__fp24Wrapped = true;
            window.toastr[level] = wrapped;
        });
        return true;
    }

    var attempts = 0;
    var toastrTimer = window.setInterval(function () {
        attempts += 1;
        if (wrapToastr() || attempts > 30) window.clearInterval(toastrTimer);
    }, 1000);
    wrapToastr();

    function inspectNode(node) {
        if (!node || node.nodeType !== 1 || Date.now() - startedAt < 5000) return;
        var element = node;
        var isAlert = element.matches && element.matches(
            '.toast, .toast-message, .swal2-popup, [role="alert"], .notification, .alert-danger, .alert-success'
        );
        var nestedAlert = element.querySelector && element.querySelector(
            '.toast, .toast-message, .swal2-popup, [role="alert"], .notification, .alert-danger, .alert-success'
        );

        if (isAlert || nestedAlert) {
            inspectText(element.textContent || '');
            return;
        }

        if (element.tagName === 'TR' && element.parentElement
                && element.parentElement.tagName === 'TBODY') {
            var path = window.location.pathname.toLowerCase();
            if (path.indexOf('/withdraw') === 0) report('withdraw');
            else if (path.indexOf('/deposit') === 0) report('deposit');
        }
    }

    if (document.body && window.MutationObserver) {
        var observer = new MutationObserver(function (mutations) {
            mutations.forEach(function (mutation) {
                Array.prototype.forEach.call(mutation.addedNodes || [], inspectNode);
            });
        });
        observer.observe(document.body, { childList: true, subtree: true });
    }
}());
