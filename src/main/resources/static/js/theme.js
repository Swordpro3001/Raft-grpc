// Loaded before the page paints, so that a stored theme never flashes the other one.
// Follows the system until a theme has been chosen. Without JavaScript the system decides and the switch stays hidden.
(function () {
    const KEY = 'raft-theme';
    const root = document.documentElement;
    const system = window.matchMedia('(prefers-color-scheme: dark)');

    function stored() {
        try {
            return localStorage.getItem(KEY);
        } catch (e) {
            return null;
        }
    }

    function store(theme) {
        try {
            localStorage.setItem(KEY, theme);
        } catch (e) {
            // Private modes can refuse this. The choice then lasts until the page reloads.
        }
    }

    function apply(theme) {
        root.dataset.theme = theme;
        document.querySelectorAll('[data-theme-choice]').forEach(button => {
            button.setAttribute('aria-pressed', String(button.dataset.themeChoice === theme));
        });
        document.dispatchEvent(new Event('themechange'));
    }

    function preferred() {
        return stored() ?? (system.matches ? 'dark' : 'light');
    }

    apply(preferred());
    system.addEventListener('change', () => apply(preferred()));
    document.addEventListener('DOMContentLoaded', () => apply(preferred()));
    document.addEventListener('click', event => {
        const button = event.target.closest('[data-theme-choice]');
        if (button) {
            store(button.dataset.themeChoice);
            apply(button.dataset.themeChoice);
        }
    });
})();
