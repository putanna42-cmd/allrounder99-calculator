(function () {
    'use strict';

    function enableAutofill() {
        Array.prototype.forEach.call(document.querySelectorAll('form'), function (form) {
            form.setAttribute('autocomplete', 'on');
        });

        var passwords = Array.prototype.slice.call(
            document.querySelectorAll('input[type="password"]'));
        passwords.forEach(function (password) {
            password.setAttribute('autocomplete', 'current-password');

            var form = password.form || document;
            var candidates = Array.prototype.slice.call(form.querySelectorAll(
                'input[type="text"], input[type="email"], input[type="tel"], input:not([type])'));
            if (!candidates.length) return;

            var username = candidates.filter(function (input) {
                var hint = String(input.getAttribute('autocomplete') || '').toLowerCase();
                var name = String(input.name || input.id || '').toLowerCase();
                return hint === 'username' || /user|email|login|phone|mobile/.test(name);
            })[0] || candidates[0];
            username.setAttribute('autocomplete', 'username');
        });
    }

    enableAutofill();
    if (typeof MutationObserver !== 'undefined') {
        new MutationObserver(enableAutofill).observe(document.documentElement, {
            childList: true,
            subtree: true
        });
    }
}());
