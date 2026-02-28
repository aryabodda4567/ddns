(function () {
    const acceptanceOnlyPaths = new Set([
        '/index.html',
        '/create.html',
        '/update.html',
        '/delete.html',
        '/lookup.html',
        '/status.html',
        '/vote.html'
    ]);

    async function checkAuth() {
        try {
            const response = await fetch('/auth/session', { credentials: 'same-origin' });
            const session = await response.json();

            if (session.requireLogin && !session.authenticated) {
                const next = encodeURIComponent(window.location.pathname);
                window.location.href = '/login.html?next=' + next;
                return;
            }

            if (!session.accepted) {
                document
                    .querySelectorAll('[data-requires-accepted=\"true\"]')
                    .forEach((el) => {
                        el.style.display = 'none';
                    });

                if (acceptanceOnlyPaths.has(window.location.pathname)) {
                    window.location.href = '/home.html';
                }
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
