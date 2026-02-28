(function () {
    async function checkAuth() {
        try {
            const response = await fetch('/auth/session', { credentials: 'same-origin' });
            const session = await response.json();

            if (session.requireLogin && !session.authenticated) {
                const next = encodeURIComponent(window.location.pathname);
                window.location.href = '/login.html?next=' + next;
            }
        } catch (e) {
            // If auth check fails, stay on page and let API calls surface errors.
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', checkAuth);
    } else {
        checkAuth();
    }
})();
