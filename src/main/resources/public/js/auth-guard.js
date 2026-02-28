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
    const protectedPaths = new Set([
        '/home.html',
        '/create_election.html',
        '/join_result.html',
        '/index.html',
        '/create.html',
        '/update.html',
        '/delete.html',
        '/lookup.html',
        '/status.html',
        '/vote.html'
    ]);

    function redirectToLogin() {
        const next = encodeURIComponent(window.location.pathname);
        window.location.replace('/login.html?next=' + next);
    }

    async function checkAuth() {
        try {
            const response = await fetch('/auth/session', { credentials: 'same-origin' });
            const session = await response.json();

            if (session.requireJoin && protectedPaths.has(window.location.pathname)) {
                window.location.replace('/join.html');
                return;
            }

            if (session.requireLogin && !session.authenticated) {
                redirectToLogin();
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
            if (protectedPaths.has(window.location.pathname)) {
                redirectToLogin();
            }
        }
    }

    checkAuth();
})();
