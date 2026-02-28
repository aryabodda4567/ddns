(function () {
    // Pages that are visible only after node acceptance.
    const acceptanceOnlyPaths = new Set([
        '/index.html',
        '/create.html',
        '/update.html',
        '/delete.html',
        '/lookup.html',
        '/status.html',
        '/vote.html'
    ]);

    // Pages that always require a valid login session.
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
        const nextPath = encodeURIComponent(window.location.pathname);
        window.location.replace('/login.html?next=' + nextPath);
    }

    async function checkAuth() {
        try {
            const response = await fetch('/auth/session', { credentials: 'same-origin' });
            const sessionState = await response.json();

            if (sessionState.requireJoin && protectedPaths.has(window.location.pathname)) {
                window.location.replace('/join.html');
                return;
            }

            if (sessionState.requireLogin && !sessionState.authenticated) {
                redirectToLogin();
                return;
            }

            if (!sessionState.accepted) {
                document
                    .querySelectorAll('[data-requires-accepted="true"]')
                    .forEach((element) => {
                        element.style.display = 'none';
                    });

                if (acceptanceOnlyPaths.has(window.location.pathname)) {
                    window.location.href = '/home.html';
                }
            }
        } catch (error) {
            if (protectedPaths.has(window.location.pathname)) {
                redirectToLogin();
            }
        }
    }

    checkAuth();
})();
