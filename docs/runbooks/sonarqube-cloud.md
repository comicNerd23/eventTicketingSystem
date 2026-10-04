# Runbook: SonarQube Cloud setup (ADR-023)

One-time setup that connects CI to SonarQube Cloud. Until it is done, CI's `--sonar` step is
skipped and only the tests run, so the order doesn't matter.

What CI expects afterwards:

| Where | Name | Value |
|---|---|---|
| Repository secret | `SONAR_TOKEN` | a SonarQube Cloud token with analysis rights |
| Repository variable | `SONAR_ORGANIZATION` | the organization key, e.g. `comicnerd23` |
| SonarQube Cloud | 7 projects | keys `<organization>_<target>`, see step 3 |

Both go on the **repository**, not on the `production` environment: the CI jobs don't use an
environment.

Steps checked against SonarQube Cloud's documentation on 2026-10-04. The UI changes from time to
time; if a label differs, the linked doc pages show the current one.

## 1. Sign up with GitHub

Open <https://sonarcloud.io>, choose **Start now** → **GitHub**, and sign in with your GitHub
account ([Getting started with GitHub](https://docs.sonarsource.com/sonarqube-cloud/getting-started/github.md)).

## 2. Create the organization (Free plan)

1. When asked to connect a GitHub organization, **install the SonarQube Cloud GitHub app** on your
   personal account `comicNerd23`. Under repository access, choose **Only select repositories** →
   `eventTicketingSystem`.
2. **Select a subscription plan:** **Free**.
3. Check the organization name and **key** shown at the bottom (e.g. `comicnerd23`), then
   **Create organization**. The key goes into `SONAR_ORGANIZATION` and into every project key.
4. SonarQube Cloud then offers to import repositories (**Analyze <n> projects** / **Bulk import
   all**). **Don't import `eventTicketingSystem` here:** that creates a single project for the
   whole repository, with automatic analysis. The seven projects come from step 3. If it happened
   anyway, delete that project (see Troubleshooting).

## 3. Create the seven projects (monorepo)

([Monorepo support](https://docs.sonarsource.com/sonarqube-cloud/analyzing-source-code/monorepo-support))

1. **✚** (top right) → **Analyze new project**.
2. Click the small link **Setup a monorepo**, to the right of the **Organization** field. Don't pick
   the repository from the normal list, because that creates one project.
3. Choose your **Organization**, then the **Repository** `eventTicketingSystem`. If it's missing,
   give the GitHub app access to it: GitHub → **Settings → Applications → SonarQube Cloud →
   Configure → Repository access**.
4. Click **Add new project** seven times and **overwrite each proposed key**. CI derives the keys
   as `<organization>_<target>`, so they must match exactly:

   | Project key | Project name |
   |---|---|
   | `<org>_api-gateway` | api-gateway |
   | `<org>_event-service` | event-service |
   | `<org>_booking-service` | booking-service |
   | `<org>_payment-service` | payment-service |
   | `<org>_notification-service` | notification-service |
   | `<org>_waitlist-service` | waitlist-service |
   | `<org>_frontend` | frontend |

   Alternatively, upload these seven entries (`projectKey`, `projectName`) in the **Import JSON**
   tab → **Review projects** → **Create projects**.
5. Click **Set up monorepo**. On **Set up new code**, choose **Number of days: 30**, then
   **Create projects**. "Previous version" would rely on a project version that CI doesn't set,
   so all code since the first analysis would count as new.
6. Monorepo projects support only CI-based analysis, so automatic analysis stays off. If a project
   page asks for the analysis method, choose GitHub Actions but skip its YAML: `ci.yml` already
   has the step.
7. Check the keys without logging in (Free-plan projects are public):

   ```bash
   curl -s "https://sonarcloud.io/api/components/search_projects?organization=<org>&ps=50" | grep -o '"key":"[^"]*"'
   ```

   Expect exactly the seven keys above.

## 4. Token, secret and variable

([Managing personal access tokens](https://docs.sonarsource.com/sonarqube-cloud/managing-your-account/managing-tokens.md))

1. In SonarQube Cloud: account menu (top right) → **My account** → **Access Tokens** → tab
   **Personal Tokens**.
2. Under **Generate Tokens**, enter a name such as `github-actions-eventTicketingSystem`, pick an
   **expiration** (**90 days** is the longest preset; **Custom** allows a later date), then
   **Generate Token**.
3. **Copy the token right away.** It can't be shown again once you leave the page.

In Git Bash, from the repository root (`read -s` keeps the token out of the terminal and history;
in Git Bash `gh secret set` doesn't prompt by itself):

```bash
read -rs -p "SonarQube Cloud token: " T; echo
printf '%s' "$T" | tr -d '
 ' | gh secret set SONAR_TOKEN   # no stray  from pasting
unset T
gh variable set SONAR_ORGANIZATION --body "<organization key>"
gh secret list; gh variable list    # SONAR_TOKEN and SONAR_ORGANIZATION are listed
```

**Token expiry:** CI's Sonar step fails once the token expires, so note the date. A token without
an expiration date expires after 60 days without use. Rotate it as described in Notes.

## 5. First analysis

A manual CI run analyzes every target, not just the changed ones:

```bash
gh workflow run ci.yml --ref master
gh run watch "$(gh run list --workflow ci.yml --limit 1 --json databaseId -q '.[0].databaseId')"
```

Each job log shows the scanner, then `QUALITY GATE STATUS: PASSED`. The seven projects then show
issues, coverage and duplication on SonarQube Cloud.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| Job log says `SonarQube: skipped (SONAR_TOKEN or SONAR_ORGANIZATION not set)` | Secret or variable missing, or set on an environment instead of the repository (step 4). |
| `Project not found` or `You're not authorized to analyze this project` | The project key doesn't match `<org>_<target>`, or the token belongs to another account (step 3). |
| `You are running CI analysis while Automatic Analysis is enabled` | Turn it off in that project's **Administration → Analysis Method**. |
| An extra project named after the repository (e.g. `<Org>_eventTicketingSystem`) | Created by the repository import after step 2, or by picking the repository without **Setup a monorepo**. Delete it: open the project → **Administration → Deletion** (the menu is hidden while the project only shows its setup screen; open **Overview** first). Organization admins can also use the organization's **Administration → Projects Management**. As a last resort, use the Web API: `curl -u "$T:" -X POST "https://sonarcloud.io/api/projects/delete?project=<key>"` returns 204. |
| `Failed to query JRE metadata: invalid header value` (Maven) or `403` (frontend) on the first run | The secret has a stray ``/newline from pasting, or the token is wrong. `ci.js` strips whitespace and logs the token's length and character set (never the value). If it still fails, generate a new token and set it again (step 4). |
| CI suddenly fails with `Not authorized` after weeks of working | The token expired. Generate a new one and set the secret again (step 4). |
| `QUALITY GATE STATUS: FAILED` | Working as intended: new code has issues or too little coverage. The log links to the project page with the failed conditions. |
| Coverage shows 0% | The report wasn't produced: `services/<name>/target/site/jacoco/jacoco.xml` or `frontend/coverage/frontend/lcov.info`. Check the test step of that job. |

## Notes

- **Free plan limits:** only `master` and pull requests into it are analyzed. If feature branches
  ever need checks, move to the free **OSS plan**: add an OSI license file (e.g. MIT) and apply to
  Sonar. The projects and CI stay as they are (ADR-023).
- **Rotating the token:** generate a new one (step 4), set the secret again, then revoke the old one
  under **My account → Access Tokens**.
- **Organization-scoped tokens** would avoid tying CI to a personal account, but they need the Team
  plan or higher. On the Free plan, a personal token is the only option.
