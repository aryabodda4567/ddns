/**
 * exit-node.js — Graceful Node Exit
 *
 * Injects an "Exit Node" button into the topbar of chain-node pages.
 * Only rendered when the server is in NODE mode (not BOOTSTRAP).
 *
 * Flow:
 *  1. Check /auth/session for mode === 'NODE'
 *  2. Inject button into .topbar
 *  3. On click: show password modal
 *  4. On confirm: POST /node/exit { password }
 *  5. On success: redirect to /
 */
(function () {
    // --- Modal HTML ---
    const MODAL_HTML = `
    <div id="exitModal" style="
        display:none; position:fixed; inset:0; z-index:9999;
        background:rgba(10,26,50,0.55); backdrop-filter:blur(4px);
        align-items:center; justify-content:center;">
        <div style="
            background:#fff; border-radius:18px; padding:28px 24px;
            width:min(400px,92vw); box-shadow:0 24px 60px rgba(10,26,50,0.22);
            border:1px solid #e0e8f4;">
            <h2 style="margin:0 0 6px; color:#0b3558; font-size:20px;">Exit This Node?</h2>
            <p style="margin:0 0 18px; font-size:13px; color:#486581; line-height:1.5;">
                This will remove your node from the network and inform all peers.<br>
                Enter your password to confirm.
            </p>
            <label style="display:block; font-size:12px; font-weight:600; color:#486581; margin-bottom:6px;">
                Web Password
            </label>
            <input id="exitPasswordInput" type="password" placeholder="Your password"
                style="width:100%; padding:10px 11px; border-radius:10px; border:1px solid #d9e2ec;
                       font-size:14px; color:#102a43; background:#f8fbff; box-sizing:border-box;">
            <div id="exitModalError" style="
                display:none; margin-top:10px; padding:10px 12px; border-radius:10px;
                background:#fdecec; color:#8a1c1c; border:1px solid #f3aaaa; font-size:13px;">
            </div>
            <div style="display:flex; gap:8px; margin-top:16px;">
                <button id="exitConfirmBtn" style="
                    flex:1; padding:11px 14px; border:none; border-radius:10px;
                    font-size:14px; font-weight:700; cursor:pointer; color:#fff;
                    background:linear-gradient(145deg,#c0392b,#96281b);
                    transition:box-shadow 0.15s ease;">
                    Confirm Exit
                </button>
                <button id="exitCancelBtn" style="
                    flex:1; padding:11px 14px; border:1px solid #c8dcff; border-radius:10px;
                    font-size:14px; font-weight:700; cursor:pointer; color:#102a43;
                    background:#f2f7ff;">
                    Cancel
                </button>
            </div>
        </div>
    </div>`;

    // --- Helper: show/hide modal ---
    function showModal() {
        const modal = document.getElementById('exitModal');
        if (modal) {
            document.getElementById('exitPasswordInput').value = '';
            document.getElementById('exitModalError').style.display = 'none';
            document.getElementById('exitConfirmBtn').disabled = false;
            document.getElementById('exitConfirmBtn').textContent = 'Confirm Exit';
            modal.style.display = 'flex';
            document.getElementById('exitPasswordInput').focus();
        }
    }

    function hideModal() {
        const modal = document.getElementById('exitModal');
        if (modal) modal.style.display = 'none';
    }

    function showModalError(msg) {
        const el = document.getElementById('exitModalError');
        if (el) {
            el.textContent = msg;
            el.style.display = 'block';
        }
    }

    // --- Exit flow ---
    async function performExit() {
        const password = document.getElementById('exitPasswordInput').value.trim();
        if (!password) {
            showModalError('Password is required.');
            return;
        }

        const btn = document.getElementById('exitConfirmBtn');
        btn.disabled = true;
        btn.textContent = 'Exiting…';
        document.getElementById('exitModalError').style.display = 'none';

        try {
            const res = await fetch('/node/exit', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                credentials: 'same-origin',
                body: JSON.stringify({password})
            });
            const json = await res.json();

            if (!res.ok || json.error) {
                showModalError(json.error || 'Exit failed. Please try again.');
                btn.disabled = false;
                btn.textContent = 'Confirm Exit';
                return;
            }

            // Success — redirect to setup chooser
            window.location.replace('/');
        } catch (e) {
            showModalError('Server unreachable: ' + e.message);
            btn.disabled = false;
            btn.textContent = 'Confirm Exit';
        }
    }

    // --- Inject button into topbar ---
    function injectExitButton() {
        const topbar = document.querySelector('.topbar');
        if (!topbar) return;

        const btn = document.createElement('button');
        btn.id = 'exitNodeBtn';
        btn.textContent = 'Exit Node';
        btn.title = 'Gracefully exit this node from the network';
        btn.style.cssText = `
            margin:0; padding:7px 13px; font-size:13px; font-weight:700;
            border-radius:10px; cursor:pointer; color:#fff; border:none;
            background:linear-gradient(145deg,#c0392b,#96281b);
            box-shadow:0 4px 12px rgba(192,57,43,0.22);
            transition:box-shadow 0.15s ease, transform 0.06s ease;`;
        btn.addEventListener('mouseenter', () => {
            btn.style.boxShadow = '0 8px 20px rgba(192,57,43,0.35)';
        });
        btn.addEventListener('mouseleave', () => {
            btn.style.boxShadow = '0 4px 12px rgba(192,57,43,0.22)';
        });
        btn.addEventListener('click', showModal);
        topbar.appendChild(btn);
    }

    // --- Inject modal into body ---
    function injectModal() {
        const div = document.createElement('div');
        div.innerHTML = MODAL_HTML.trim();
        document.body.appendChild(div.firstElementChild);

        document.getElementById('exitConfirmBtn').addEventListener('click', performExit);
        document.getElementById('exitCancelBtn').addEventListener('click', hideModal);

        // Close on backdrop click
        document.getElementById('exitModal').addEventListener('click', function (e) {
            if (e.target === this) hideModal();
        });

        // Enter key in password field
        document.getElementById('exitPasswordInput').addEventListener('keydown', function (e) {
            if (e.key === 'Enter') performExit();
        });
    }

    // --- Init: only in NODE mode ---
    (async function init() {
        try {
            const res = await fetch('/auth/session', {credentials: 'same-origin'});
            const json = await res.json();
            if (json.mode !== 'NODE') return; // Bootstrap or unset — don't show button
        } catch (_) {
            return;
        }

        // Wait for DOM to be ready before injecting
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', () => {
                injectModal();
                injectExitButton();
            });
        } else {
            injectModal();
            injectExitButton();
        }
    })();
})();
