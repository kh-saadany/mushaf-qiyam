
- When committing changes to the Mushaf Qiyam project, you MUST ask the user if they want to build a [full] version, a [lite] version, or [both]. Add the chosen keyword to the git commit message (e.g. `git commit -m "[lite] fixed bugs"`).
- BEFORE committing, you MUST increment the version number in both `package.json` and `App.js` (the `APP_VERSION` variable) based on the user's choice:
  - If building a `[full]` version: Increment the MINOR version by 1 (e.g., `1.0.0` -> `1.1.0`).
  - If building a `[lite]` version: Increment the PATCH version by 1 (e.g., `1.0.0` -> `1.0.1`).
- BEFORE committing, you MUST invoke the `mushaf_code_reviewer` subagent to review the modified files (such as App.js, download_assets.js, android-build.yml) and verify that they PASS all checks to avoid breaking the build on GitHub.

