#!/usr/bin/env node

/**
 * Creates a Pull Request for a specific issue.
 * Usage: node create_pull_request.cjs <issue_number> <branch_name> <title> <body>
 */

const { execSync } = require('child_process');

const issueNumber = process.argv[2];
const branchName = process.argv[3];
const prTitle = process.argv[4];
const prBody = process.argv[5];

if (!issueNumber || !branchName || !prTitle || !prBody) {
  console.error('Usage: node create_pull_request.cjs <issue_number> <branch_name> <title> <body>');
  process.exit(1);
}

function runCommand(command) {
  try {
    console.log(`Running: ${command}`);
    return execSync(command, { encoding: 'utf8' });
  } catch (error) {
    console.error(`Error executing command: ${command}`);
    console.error(error.stdout);
    console.error(error.stderr || error.message);
    process.exit(1);
  }
}

// 1. Check if branch exists, if so, delete it or pick a new one (let's assume branchName is unique for now)
// 2. Create and switch to branch
runCommand(`git checkout -b ${branchName}`);

// 3. Stage and commit (assuming changes are already made)
runCommand(`git add .`);
runCommand(`git commit -m "${prTitle} (fixes #${issueNumber})"`);

// 4. Push branch
runCommand(`git push origin ${branchName}`);

// 5. Create PR
const prUrl = runCommand(`gh pr create --title "${prTitle}" --body "${prBody}\n\nFixes #${issueNumber}" --base main --head ${branchName}`);

console.log(`Successfully created PR: ${prUrl}`);
