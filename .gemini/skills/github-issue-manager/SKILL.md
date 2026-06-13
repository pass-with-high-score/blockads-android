---
name: github-issue-manager
description: Fetch, analyze, and resolve GitHub issues. Use when the user wants to get a daily report of issue activity, analyze complex comment threads, or automatically implement fixes and create Pull Requests for reported bugs.
---

# GitHub Issue Manager

## Overview

This skill automates the lifecycle of GitHub issues within the current repository. It leverages the `gh` CLI to fetch data, generates structured reports using AI-driven comment analysis, and provides a workflow for implementing and validating fixes.

## Core Workflow

### 1. Ingestion & Analysis
Use `scripts/fetch_issue_data.cjs` to get the latest activity.
- **Trigger:** "Show me latest issues", "Give me a daily report", "What happened on GitHub today?"
- **Action:**
  1. Run the fetch script.
  2. Provide a concise summary of the identified issues.
  3. **Mandatory:** Use the `ask_user` tool to ask the user which issue they would like to fix or if they want to proceed with a full report.

### 2. Issue Resolution
Once an issue is selected:
- **Research:** Deep dive into the selected issue's description and comments.
- **Reproduce:** Attempt to reproduce the issue locally (e.g., via unit tests or UI inspection).
- **Fix:** Implement the fix following project conventions.
- **Validate:** Run `./gradlew assembleDebug` and relevant tests.

### 3. Pull Request Creation
Use `scripts/create_pull_request.cjs` to automate the final step.
- **Trigger:** "Create a PR for this fix", "Wrap up issue #123".
- **Action:** Create a branch, commit changes, push, and create a PR referencing the issue.

## Bundled Resources

### scripts/
- `fetch_issue_data.cjs`: Fetches issues and comments updated within a specific timeframe.
- `create_pull_request.cjs`: Automates branch creation, commit, push, and PR submission.

### references/
- `report_template.md`: A structured Markdown template for daily status reports.

## Usage Examples

### "Get latest issues from the last 24h"
1. Run `node .gemini/skills/github-issue-manager/scripts/fetch_issue_data.cjs 1`
2. Summarize each issue and its comments.
3. Identify which issues need immediate attention.

### "Fix issue #45"
1. Read the issue description and comments carefully.
2. Search the codebase for related symbols or UI components.
3. Implement the fix.
4. Run `node .gemini/skills/github-issue-manager/scripts/create_pull_request.cjs 45 fix/issue-45 "Fix crash in UI" "This PR addresses the crash reported in #45..."`
