#!/usr/bin/env node

/**
 * Fetches GitHub issues and their comments for the current repository.
 * Usage: node fetch_issue_data.cjs [days] [state]
 * Example: node fetch_issue_data.cjs 1 open
 */

const { execSync } = require('child_process');

const days = process.argv[2] || 1;
const state = process.argv[3] || 'all'; // open, closed, all

function runCommand(command) {
  try {
    return execSync(command, { encoding: 'utf8' });
  } catch (error) {
    console.error(`Error executing command: ${command}`);
    console.error(error.stderr || error.message);
    process.exit(1);
  }
}

// Calculate the timestamp for "days" ago
const since = new Date(Date.now() - days * 24 * 60 * 60 * 1000).toISOString();

console.log(`Fetching issues updated since: ${since} (state: ${state})`);

// 1. Fetch issues
const sinceSearch = since.split('T')[0]; // Simple YYYY-MM-DD for search
const issuesJson = runCommand(`gh issue list --state ${state} --search "updated:>=${sinceSearch}" --json number,title,author,updatedAt,labels,state`);
const issues = JSON.parse(issuesJson);

if (issues.length === 0) {
  console.log('No issues found in the specified timeframe.');
  process.exit(0);
}

console.log(`Found ${issues.length} issues. Fetching details and comments...`);

const detailedIssues = issues.map(issue => {
  const detailJson = runCommand(`gh issue view ${issue.number} --json body,comments`);
  const details = JSON.parse(detailJson);
  
  return {
    ...issue,
    body: details.body,
    comments: details.comments.map(c => ({
      author: c.author.login,
      body: c.body,
      createdAt: c.createdAt
    }))
  };
});

// Output formatted for the LLM
detailedIssues.forEach(issue => {
  console.log(`\n--- ISSUE #${issue.number}: ${issue.title} ---`);
  console.log(`State: ${issue.state} | Author: ${issue.author.login} | Updated: ${issue.updatedAt}`);
  console.log(`Labels: ${issue.labels.map(l => l.name).join(', ')}`);
  console.log(`\nDescription:\n${issue.body}\n`);
  
  if (issue.comments.length > 0) {
    console.log(`Comments (${issue.comments.length}):`);
    issue.comments.forEach(c => {
      console.log(`  [${c.author} at ${c.createdAt}]: ${c.body.substring(0, 500)}${c.body.length > 500 ? '...' : ''}`);
    });
  } else {
    console.log('No comments.');
  }
});
