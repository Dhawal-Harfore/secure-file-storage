# Vault

Browser UI for the Policy-Based Secure Cloud File Storage project.
Nandishwar Singh (26MCA0137), MCA Cloud Computing, VIT Vellore.

Plain HTML, CSS and JavaScript. No build step and no framework.
Styling comes from the hosanna-ui stylesheet; `vendor/hosanna` is a symlink to its dist folder.

## Run

    python3 -m http.server 5173

`apiSource` in `js/config.js` is set to `mock`, so this runs without the backend.
The sign-in link gets printed to the browser console instead of being emailed.

## Test

    node test/deck-conformance.mjs

Checks the access levels and quality tiers still match the rules in CONTRACT.md.
