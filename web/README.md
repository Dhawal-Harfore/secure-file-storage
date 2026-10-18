# Vault

Browser UI for the Policy-Based Secure Cloud File Storage project.
Nandishwar Singh (26MCA0137), MCA Cloud Computing, VIT Vellore.

Plain HTML, CSS and JavaScript. No build step and no framework.
Styling comes from the hosanna-ui stylesheet, copied into `vendor/hosanna`.

## Run

    python3 -m http.server 5173

Open `http://localhost:5173/?api=mock` to run against the in-browser mock instead of the
Spring Boot API. The choice sticks for the tab. In mock mode the sign-in link gets printed
to the browser console instead of being emailed. `?api=live` switches back.

## Test

    for t in test/*.mjs; do node "$t"; done

- `deck-conformance` checks the access levels and quality tiers still match CONTRACT.md.
- `sha256` checks the streaming SHA-256 used for uploads against Node's crypto.
- `uploader` checks chunked uploads, resume, retries and cancel against a fake API.
- `api-surface` checks the mock and the live client expose the same calls.
