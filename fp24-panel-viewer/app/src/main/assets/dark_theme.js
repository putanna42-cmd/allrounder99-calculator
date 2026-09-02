(function () {
    'use strict';
    if (!document || !document.documentElement) return;

    document.documentElement.style.colorScheme = 'dark';
    var meta = document.querySelector('meta[name="theme-color"]');
    if (!meta) {
        meta = document.createElement('meta');
        meta.setAttribute('name', 'theme-color');
        (document.head || document.documentElement).appendChild(meta);
    }
    meta.setAttribute('content', '#05080d');

    if (document.getElementById('fp24-native-black-theme-v3')) return;
    var style = document.createElement('style');
    style.id = 'fp24-native-black-theme-v3';
    style.textContent = `
        :root {
            color-scheme: dark !important;
            --fp24-black: #05080d;
            --fp24-panel: #0d141d;
            --fp24-card: #111b27;
            --fp24-border: #263445;
            --fp24-text: #edf3f9;
            --fp24-muted: #a9b7c7;
            --fp24-green: #18c37e;
        }

        html, body, .wrapper, .content-wrapper, .content, .main-footer,
        .login-page, .register-page, .hold-transition {
            background: var(--fp24-black) !important;
            color: var(--fp24-text) !important;
        }

        .main-header, .navbar, .navbar-white, .navbar-light,
        .content-header, .brand-link, .main-sidebar, .sidebar,
        .control-sidebar, .control-sidebar-content {
            background: #080d14 !important;
            color: var(--fp24-text) !important;
            border-color: var(--fp24-border) !important;
        }

        .card, .card-body, .card-header, .card-footer,
        .info-box, .small-box, .box, .box-body, .box-header,
        .modal-content, .modal-header, .modal-body, .modal-footer,
        .dropdown-menu, .list-group-item, .login-box-body,
        .register-box-body, .jumbotron, .well, .callout,
        .bg-white, .bg-light {
            background: var(--fp24-card) !important;
            color: var(--fp24-text) !important;
            border-color: var(--fp24-border) !important;
        }

        .card, .info-box, .small-box, .box, .modal-content,
        .login-box-body, .register-box-body {
            box-shadow: 0 8px 24px rgba(0, 0, 0, .38) !important;
        }

        table, .table, .dataTable, .table-responsive,
        .dataTables_wrapper, .dataTables_scroll,
        .dataTables_scrollHead, .dataTables_scrollBody {
            background: var(--fp24-panel) !important;
            color: var(--fp24-text) !important;
            border-color: var(--fp24-border) !important;
        }

        .table thead th, .table tbody td, .table tfoot th,
        table.dataTable thead th, table.dataTable tbody td,
        table.dataTable tfoot th, th, td {
            background: var(--fp24-panel) !important;
            color: var(--fp24-text) !important;
            border-color: var(--fp24-border) !important;
        }

        .table-striped tbody tr:nth-of-type(odd) td,
        table.dataTable.stripe tbody tr.odd td {
            background: #101a25 !important;
        }

        .table-hover tbody tr:hover td,
        table.dataTable.hover tbody tr:hover td {
            background: #172534 !important;
        }

        input, textarea, select, .form-control, .custom-select,
        .input-group-text, .select2-selection, .select2-dropdown,
        .select2-results, .select2-search__field {
            background: #0a1119 !important;
            color: var(--fp24-text) !important;
            border-color: #34455a !important;
        }

        input::placeholder, textarea::placeholder,
        .text-muted, small, .help-block, .dataTables_info,
        .dataTables_length, .dataTables_filter, .breadcrumb-item.active {
            color: var(--fp24-muted) !important;
        }

        .page-link, .pagination > li > a, .pagination > li > span {
            background: var(--fp24-card) !important;
            color: var(--fp24-text) !important;
            border-color: var(--fp24-border) !important;
        }

        .page-item.active .page-link, .pagination > .active > a,
        .pagination > .active > span {
            background: var(--fp24-green) !important;
            border-color: var(--fp24-green) !important;
            color: #03130c !important;
        }

        .nav-link, .navbar-nav .nav-link, .brand-text, .card-title,
        .modal-title, h1, h2, h3, h4, h5, h6, label,
        .breadcrumb-item, .dropdown-item, .text-dark {
            color: var(--fp24-text) !important;
        }

        .card p, .card span, .info-box span, .small-box p, .small-box span,
        .login-box p, .login-box span, .register-box p, .register-box span {
            color: var(--fp24-text) !important;
        }

        a:not(.btn):not(.nav-link):not(.page-link) {
            color: #53d9b0 !important;
        }

        .btn, button, [class*="btn-"] {
            color: #ffffff !important;
        }

        hr, fieldset, .border, .border-top, .border-bottom {
            border-color: var(--fp24-border) !important;
        }

        ::-webkit-scrollbar { width: 8px; height: 8px; }
        ::-webkit-scrollbar-track { background: var(--fp24-black); }
        ::-webkit-scrollbar-thumb { background: #34455a; border-radius: 8px; }
    `;
    (document.head || document.documentElement).appendChild(style);
}());
