import assert from 'node:assert/strict';
import { spawn, spawnSync, execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { createRequire } from 'node:module';
import { mkdtemp, mkdir, open, readFile, rm, writeFile } from 'node:fs/promises';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';

const root = fileURLToPath(new URL('../', import.meta.url));
const tools = path.resolve(process.env.BROWSER_VALIDATION_TOOLS ?? 'test-results/browser-tools');
const require = createRequire(path.join(tools, 'package.json'));
const { chromium } = require('playwright-core');
const axe = await readFile(require.resolve('axe-core/axe.min.js'), 'utf8');
const evidence = path.resolve(process.env.BROWSER_VALIDATION_OUTPUT ?? 'test-results/browser-validation');
const jar = path.resolve(process.env.BROWSER_VALIDATION_JAR ?? path.join(root, 'backend', 'target', 'campus-tracker-2.0.0.jar'));
const revision = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim();
const workingTree = execFileSync('git', ['status', '--porcelain'], { cwd: root, encoding: 'utf8' }).trim();
const results = { revision, workingTree, browser: null, jarSha256: null, checks: [], scans: [] };
const password = 'Lanterns carry water over copper 57!';
const newPassword = 'Peaches sail around snowy hills 782!';
const accountA = { email: 'browser-a@example.test', name: 'Browser Tester A' };
const accountB = { email: 'browser-b@example.test', name: 'Browser Tester B' };
const company = 'Acme Synthetic';
const note = 'Browser test original note';

async function listen(server) {
  await new Promise((resolve, reject) => server.once('error', reject).listen(0, '127.0.0.1', resolve));
  return server.address().port;
}

function stopServer(server) {
  return new Promise(resolve => server.close(resolve));
}

function javaExecutable() {
  const name = process.platform === 'win32' ? 'java.exe' : 'java';
  if (process.env.JAVA_HOME) return path.join(process.env.JAVA_HOME, 'bin', name);
  const probe = spawnSync('java', ['-XshowSettings:properties', '-version'], { encoding: 'utf8' });
  assert.equal(probe.status, 0, 'Java was not found on PATH');
  const home = /^\s*java\.home\s*=\s*(.+)$/m.exec(probe.stderr)?.[1]?.trim();
  return home ? path.join(home, 'bin', name) : name;
}

const mail = [];
const connections = new Set();
const smtp = createServer(socket => {
  connections.add(socket);
  socket.on('error', () => {});
  socket.on('close', () => connections.delete(socket));
  socket.setEncoding('utf8');
  socket.write('220 localhost browser test capture\r\n');
  let buffer = '';
  let recipient = '';
  let message = null;
  socket.on('data', chunk => {
    buffer += chunk;
    let lineEnd;
    while ((lineEnd = buffer.indexOf('\r\n')) >= 0) {
      const line = buffer.slice(0, lineEnd);
      buffer = buffer.slice(lineEnd + 2);
      if (message !== null) {
        if (line === '.') {
          mail.push({ recipient, message: message.replaceAll(/=\r\n/g, '').replaceAll('=3D', '=') });
          message = null;
          socket.write('250 captured\r\n');
        } else {
          message += `${line}\r\n`;
        }
      } else if (/^EHLO |^HELO /i.test(line)) {
        socket.write('250-localhost\r\n250 SIZE 1048576\r\n');
      } else if (/^RCPT TO:/i.test(line)) {
        recipient = line;
        socket.write('250 ok\r\n');
      } else if (line === 'DATA') {
        message = '';
        socket.write('354 end with dot\r\n');
      } else if (line === 'QUIT') {
        socket.end('221 bye\r\n');
      } else {
        socket.write('250 ok\r\n');
      }
    }
  });
});

async function eventually(test, description, timeout = 15000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) {
    const value = await test();
    if (value) return value;
    await delay(100);
  }
  throw new Error(description);
}

function mailToken(account, fragment) {
  const pattern = new RegExp(`#${fragment}=([A-Za-z0-9_-]{43})`);
  return eventually(() => mail.find(item => item.recipient.includes(account.email) && pattern.test(item.message))
    ?.message.match(pattern)[1], `Expected ${fragment} message in the local capture server`);
}

const button = (scope, name) => scope.getByRole('button', { name, exact: typeof name === 'string' });
const heading = (scope, name) => scope.getByRole('heading', { name, exact: typeof name === 'string' });
const drawer = page => page.getByRole('dialog');
const workspace = page => page.getByRole('navigation', { name: 'Workspace' });
const openRecord = page => button(page, /^Open Acme Synthetic\b/);
const add = page => button(page.getByRole('main').locator('.welcome'), 'Add application');
const appearance = page => page.getByRole('combobox', { name: 'Appearance' });
const visible = locator => locator.waitFor({ state: 'visible' });
const hidden = locator => locator.waitFor({ state: 'hidden' });
const statusText = (page, pattern) => visible(page.getByRole('status').filter({ hasText: pattern }).first());
const field = (scope, name) => scope.getByLabel(name, { exact: typeof name === 'string' });

async function check(name, action) {
  try {
    const detail = await action();
    results.checks.push({ name, result: 'pass', ...(detail !== undefined && { detail }) });
    console.log(`PASS ${name}`);
  } catch (error) {
    results.checks.push({ name, result: 'fail', detail: error.message });
    console.error(`FAIL ${name}\n${error.message}`);
    process.exitCode = 1;
  }
}

async function scan(page, name) {
  await page.evaluate(async () => {
    await document.fonts.ready;
    await Promise.allSettled(document.getAnimations().map(animation => animation.finished));
  });
  await page.evaluate(axe);
  const result = await page.evaluate(() => window.axe.run({ runOnly: {
    type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'],
  } }));
  const summarize = rule => ({ id: rule.id, nodes: rule.nodes.map(node => ({
    target: node.target, summary: node.failureSummary ?? node.any.map(check => check.message).join(' '),
  })) });
  const violations = result.violations.map(summarize);
  results.scans.push({ name, violations, incomplete: result.incomplete.map(summarize) });
  assert.deepEqual(violations.map(rule => rule.id), [], 'axe reported violations');
}

async function textContrast(page, surface, selectors) {
  const ratios = await page.evaluate(({ surface, selectors }) => {
    function luminance(color) {
      const channels = color.match(/\d+(?:\.\d+)?/g).slice(0, 3).map(value => Number(value) / 255);
      return channels.map(value => value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4)
        .reduce((total, value, index) => total + value * [0.2126, 0.7152, 0.0722][index], 0);
    }
    const style = getComputedStyle(document.querySelector(surface));
    const background = [style.backgroundColor, ...style.backgroundImage.matchAll(/rgb\([^)]*\)/g)].map(String);
    const backgrounds = background.filter(color => color !== 'rgba(0, 0, 0, 0)').map(luminance);
    const minimumBackgrounds = style.backgroundImage === 'none' ? 1 : 2;
    if (backgrounds.length < minimumBackgrounds) throw new Error(`Could not read background colors for ${surface}`);
    return selectors.map(selector => {
      const element = document.querySelector(selector);
      const front = luminance(getComputedStyle(element).color);
      const ratio = Math.min(...backgrounds.map(back => (Math.max(front, back) + 0.05) / (Math.min(front, back) + 0.05)));
      return { selector, ratio };
    });
  }, { surface, selectors });
  assert.ok(ratios.every(({ ratio }) => ratio >= 4.5), JSON.stringify(ratios));
  return ratios;
}

async function login(page, account, secret = password) {
  await field(page, 'Email').fill(account.email);
  await field(page, 'Password').fill(secret);
  await button(page, 'Sign in').click();
}

async function assertAccount(page, account) {
  await visible(page.getByText(account.name, { exact: true }).filter({ visible: true }));
  await visible(page.getByText('Saved to your account', { exact: true }));
}

async function register(page, base, account, testUnverified = false) {
  await page.goto(base);
  await button(page, /Create an account/).click();
  await field(page, 'Your name').fill(account.name);
  await field(page, 'Email').fill(account.email);
  await field(page, 'Password').fill(password);
  await field(page, /Start with 10/).uncheck();
  await button(page, 'Create workspace').click();
  await statusText(page, /awaiting verification/);
  if (testUnverified) {
    await button(page, 'Back to sign in').click();
    await login(page, account);
    await statusText(page, /awaiting verification/);
  }
  const token = await mailToken(account, 'verify-email');
  await page.goto(`${base}/#verify-email=${token}`);
  await visible(heading(page, 'Verify your email.'));
  assert.equal(new URL(page.url()).hash, '');
  await field(page, 'Signup password').fill(password);
  await button(page, 'Verify email').click();
  await statusText(page, /Your email is verified/);
  await button(page, 'Back to sign in').click();
  await login(page, account);
  await assertAccount(page, account);
}

async function signOut(page) {
  await button(page, 'Sign out').click();
  await visible(heading(page, 'Welcome back.'));
}

async function chooseTheme(page, mode) {
  const saved = page.waitForResponse(response => response.url().endsWith('/api/me/preferences') && response.request().method() === 'PUT');
  await appearance(page).selectOption(mode);
  assert.equal((await saved).status(), 200);
  await eventually(() => appearance(page).isEnabled(), 'Appearance remains disabled');
}

async function activeElement(page) {
  return page.evaluate(() => {
    const element = document.activeElement;
    if (element === document.body) return '(page body)';
    const label = element.labels?.[0]?.cloneNode(true);
    label?.querySelector('select')?.remove();
    return element.getAttribute('aria-label') || label?.textContent.trim() || element.textContent.trim();
  });
}

async function createApplication(page) {
  const today = await page.evaluate(() => new Date().toLocaleDateString('en-CA'));
  await add(page).click();
  const modal = drawer(page);
  await field(modal, /^Company/).fill(company);
  await field(modal, /^Role/).fill('Browser intern');
  await modal.getByRole('combobox', { name: 'Status', exact: true }).selectOption('Interviewing');
  await field(modal, /^Application date/).fill(today);
  await field(modal, 'Follow-up date').fill(today);
  await field(modal, 'Notes').fill(note);
  await button(modal, 'Add stage').click();
  await field(modal, 'Stage 1 name').fill('Technical screen');
  await field(modal, 'Stage 1 status').selectOption('Scheduled');
  await field(modal, 'Stage 1 date').fill(today);
  await button(modal, 'Add application').click();
  await hidden(modal);
  await visible(openRecord(page));
}

async function updateFromDrawer(page, value) {
  await openRecord(page).click();
  await button(drawer(page), 'Edit application').click();
  await field(drawer(page), 'Notes').fill(value);
  await button(drawer(page), 'Save changes').click();
}

async function exercise(page, second, isolated, base) {
  await page.goto(base);
  await check('Sign-in labels, native validation and keyboard focus', async () => {
    await visible(heading(page, 'Welcome back.'));
    await page.keyboard.press('Tab');
    assert.equal(await activeElement(page), 'Appearance');
    await page.keyboard.press('Tab');
    assert.equal(await activeElement(page), 'Email');
    await page.keyboard.type('malformed');
    await page.keyboard.press('Tab');
    assert.equal(await activeElement(page), 'Password');
    await page.keyboard.type(password);
    await page.keyboard.press('Enter');
    assert.equal(await activeElement(page), 'Email');
    assert.equal(await field(page, 'Email').evaluate(input => input.validity.typeMismatch), true);
  });
  const signInContrast = () => textContrast(page, '.auth-shell', ['.auth-story .eyebrow', '.auth-story h1', '.auth-story > p', '.auth-note']);
  await check('Sign-in contrast and semantics in light', async () => {
    await scan(page, 'sign-in/light');
    return signInContrast();
  });
  await appearance(page).selectOption('dark');
  await check('Sign-in contrast and semantics in dark', async () => {
    await scan(page, 'sign-in/dark');
    return signInContrast();
  });
  await appearance(page).selectOption('light');

  await register(page, base, accountA, true);
  await check('Verification, pre-verification sign-in denial and post-verification sign-in', () => assertAccount(page, accountA));
  await check('Application shortcut, modal entry, Escape and focus return', async () => {
    await page.keyboard.press('Control+k');
    assert.equal(await activeElement(page), 'Search applications');
    await add(page).focus();
    await page.keyboard.press('Enter');
    await visible(drawer(page));
    const initial = await activeElement(page);
    const reachedBackground = [];
    for (let step = 0; step < 20; step++) {
      await page.keyboard.press('Tab');
      if (await page.evaluate(() => document.activeElement !== document.body && !document.activeElement.closest('dialog'))) {
        reachedBackground.push(await activeElement(page));
      }
    }
    await page.keyboard.press('Escape');
    await hidden(drawer(page));
    assert.deepEqual(reachedBackground, []);
    assert.match(initial, /^(Company|Close application)/);
    assert.equal(await activeElement(page), 'Add application');
    return { initial };
  });
  if (await drawer(page).isVisible()) await button(drawer(page), 'Close application').click();

  await check('Application create and detail read', async () => {
    await createApplication(page);
    await check('Company button accessible name includes visible company and location', async () => {
      await visible(button(page, 'Open Acme Synthetic Location not set'));
    });
    await openRecord(page).click();
    await visible(drawer(page).getByText(note, { exact: true }));
    await button(drawer(page), 'Close application').click();
  });
  if (await drawer(page).isVisible()) await button(drawer(page), 'Close application').click();
  const workspaceContrast = async () => (await Promise.all([
    textContrast(page, '.offer-card', ['.offer-card .summary-label', '.offer-card .summary-value', '.offer-card p']),
    textContrast(page, '.desktop-sidebar .little-note', ['.desktop-sidebar .little-note > strong', '.desktop-sidebar .little-note > p']),
    textContrast(page, '.tabs > .active > span', ['.tabs > .active > span']),
  ])).flat();
  await check('Workspace contrast and semantics in light', async () => {
    await chooseTheme(page, 'light');
    await scan(page, 'workspace/light');
    return workspaceContrast();
  });
  await check('Workspace contrast and semantics in dark', async () => {
    await chooseTheme(page, 'dark');
    assert.equal(await page.locator('html').getAttribute('data-theme'), 'dark');
    await scan(page, 'workspace/dark');
    return workspaceContrast();
  });
  await check('Keyboard navigation between application groups', async () => {
    const filters = page.getByRole('group', { name: 'Application groups' });
    const all = button(filters, /^All applications/);
    await visible(all);
    await all.focus();
    await page.keyboard.press('Tab');
    assert.equal(await activeElement(page), 'In progress');
    await page.keyboard.press('Tab');
    await page.keyboard.press('Space');
    assert.equal(await activeElement(page), 'Saved');
    assert.equal(await button(filters, 'Saved').getAttribute('aria-pressed'), 'true');
    assert.equal(await all.getAttribute('aria-pressed'), 'false');
    await hidden(openRecord(page));
    await all.click();
    await visible(openRecord(page));
  });
  await check('Interview completion, follow-up completion and undo', async () => {
    await button(workspace(page), 'Interviews').click();
    await visible(button(page, /^Technical screen/));
    await button(page, `Complete ${company} interview`).click();
    await hidden(button(page, `Complete ${company} interview`));
    await button(workspace(page), /^Follow-ups/).click();
    await button(page, `Complete ${company} follow-up`).click();
    await hidden(button(page, `Complete ${company} follow-up`));
    await button(page, 'Undo').click();
    await visible(button(page, `Complete ${company} follow-up`));
    await button(workspace(page), /^Applications/).click();
    await openRecord(page).click();
    await button(drawer(page), 'Edit application').click();
    assert.equal(await field(drawer(page), 'Stage 1 status').inputValue(), 'Completed');
    await button(drawer(page), 'Close application').click();
  });
  if (await drawer(page).isVisible()) await button(drawer(page), 'Close application').click();
  await button(workspace(page), /^Applications/).click();

  await second.goto(base);
  await login(second, accountA);
  await assertAccount(second, accountA);
  await check('Two independent sessions produce a real HTTP 409; remote change survives', async () => {
    await openRecord(page).click();
    await button(drawer(page), 'Edit application').click();
    await field(drawer(page), 'Notes').fill('Unsaved stale-tab note');
    await updateFromDrawer(second, 'Concurrent browser note');
    await hidden(drawer(second));
    const response = page.waitForResponse(r => r.request().method() === 'PUT' && /\/api\/applications\//.test(r.url()));
    await button(drawer(page), 'Save changes').click();
    assert.equal((await response).status(), 409);
    const alert = drawer(page).getByRole('alert');
    await visible(alert);
    const error = await alert.innerText();
    assert.equal(await activeElement(page), error);
    assert.equal(await field(drawer(page), 'Notes').inputValue(), 'Unsaved stale-tab note');
    await button(drawer(page), 'Close application').click();
    await button(page, 'Refresh').click();
    await openRecord(page).click();
    await visible(drawer(page).getByText('Concurrent browser note', { exact: true }));
    await button(drawer(page), 'Close application').click();
    return error;
  });
  if (await drawer(page).isVisible()) await button(drawer(page), 'Close application').click();

  await check('Application delete confirmation and undo', async () => {
    await openRecord(page).click();
    await button(drawer(page), 'Delete').click();
    assert.match(await activeElement(page), /Delete this application/);
    await button(drawer(page), 'Delete application').click();
    await hidden(openRecord(page));
    await visible(button(page, 'Undo'));
    await check('Deletion puts keyboard focus on Undo when the opener disappears', async () => {
      await eventually(async () => await activeElement(page) === 'Undo', 'Undo did not receive keyboard focus', 2000);
    });
    await button(page, 'Undo').click();
    await visible(openRecord(page));
    await statusText(page, /Change undone/);
  });

  await check('Browser CSV correction, review, update acknowledgement and lost-commit recovery', async () => {
    const download = page.waitForEvent('download');
    await button(page, 'Export CSV').click();
    const exported = await (await download).createReadStream();
    const chunks = [];
    for await (const chunk of exported) chunks.push(chunk);
    const id = Buffer.concat(chunks).toString().match(/^\uFEFF?.*\r?\n"?([0-9a-f-]{36})"?,/i)?.[1];
    assert.ok(id, 'Export should start each record with Application ID');
    await button(page, 'Import CSV').click();
    const modal = drawer(page);
    const fixture = `Application ID,Company,Role,Notes\n${id},,,Reviewed browser CSV update\n,Orion CSV,,From browser import\n`;
    await field(modal, /^CSV file/).setInputFiles({ name: 'browser-validation.csv', mimeType: 'text/csv', buffer: Buffer.from(fixture) });
    await button(modal, 'Read file and show columns').click();
    await visible(heading(modal, '3. Review and correct'));
    for (const [index, destination] of ['applicationId', 'company', 'role', 'notes'].entries()) {
      await modal.locator(`#import-map-${index}`).selectOption(destination);
    }
    await button(modal, 'Create revised preview').last().click();
    const correction = modal.locator('#import-row-2-role[aria-invalid="true"]');
    await visible(correction);
    await eventually(() => correction.isEnabled(), 'The CSV correction remained disabled');
    await button(modal, 'Go to next row with errors').click();
    assert.ok(await correction.getAttribute('aria-describedby'));
    await check('CSV validation can focus the described invalid Role field', async () => {
      await eventually(() => correction.evaluate(input => input === document.activeElement), 'Next error did not focus the invalid Role');
    });
    await correction.fill('CSV intern');
    const correctedResponse = page.waitForResponse(response => response.url().endsWith('/api/application-imports/preview'));
    await button(modal, 'Create revised preview').last().click();
    const corrected = await correctedResponse;
    assert.equal(corrected.status(), 201, await corrected.text());
    await button(modal, 'Review final confirmation').click();
    const commit = button(modal, 'Import 1 new applications and update 1 existing applications');
    assert.equal(await commit.isDisabled(), true);
    await field(modal, /I understand that 1 existing/).check();
    const commitPattern = /\/api\/application-imports\/[^/]+\/commit$/;
    const statusPattern = /\/api\/application-imports\/[^/]+$/;
    let commits = 0;
    let reads = 0;
    let serverCommit;
    let blockedResponse = true;
    const loseCommit = async route => {
      commits++;
      serverCommit = (await route.fetch({ maxRetries: 0, maxRedirects: 0 })).status();
      await route.abort('failed');
    };
    const failFirstRead = async route => {
      if (route.request().method() !== 'GET') return route.fallback();
      reads++;
      if (blockedResponse) await route.abort('failed');
      else await route.continue();
    };
    await page.route(commitPattern, loseCommit);
    await page.route(statusPattern, failFirstRead);
    try {
      await commit.click();
      await visible(heading(modal, 'Completion not yet confirmed'));
      await button(modal, 'Close').click();
      assert.equal(await add(page).isDisabled(), true);
      await button(page, /^Check import /).click();
      blockedResponse = false;
      await button(modal, 'Check status').click();
      await visible(heading(modal, 'Import completed'));
      assert.equal(commits, 1);
      assert.equal(serverCommit, 200);
      assert.ok(reads >= 1);
      await button(modal, 'Close').click();
      await visible(button(page, /^Open Orion CSV\b/));
      await openRecord(page).click();
      await visible(drawer(page).getByText('Reviewed browser CSV update', { exact: true }));
      await button(drawer(page), 'Close application').click();
      return { commits, statusReads: reads, serverCommit };
    } finally {
      await page.unroute(commitPattern, loseCommit);
      await page.unroute(statusPattern, failFirstRead);
    }
  });
  if (await drawer(page).isVisible()) await page.keyboard.press('Escape');

  await check('Appearance persists across reloads and resolves system preference', async () => {
    await page.reload();
    await assertAccount(page, accountA);
    await eventually(async () => await appearance(page).inputValue() === 'dark', 'Dark preference did not persist');
    await chooseTheme(page, 'system');
    await page.emulateMedia({ colorScheme: 'dark' });
    await eventually(async () => await page.locator('html').getAttribute('data-theme') === 'dark', 'System dark did not apply');
    await page.emulateMedia({ colorScheme: 'light' });
    await eventually(async () => await page.locator('html').getAttribute('data-theme') === 'light', 'System light did not apply');
    await chooseTheme(page, 'dark');
  });

  await isolated.goto(base);
  await login(isolated, accountA);
  await assertAccount(isolated, accountA);
  await check('Logout in one tab and switching accounts in another preserves account data and theme boundaries', async () => {
    const peer = await page.context().newPage();
    await peer.goto(base);
    await assertAccount(peer, accountA);
    await signOut(page);
    await visible(heading(peer, 'Welcome back.'));
    await register(page, base, accountB);
    await assertAccount(peer, accountB);
    await hidden(openRecord(page));
    await hidden(openRecord(peer));
    await chooseTheme(page, 'light');
    await signOut(page);
    await login(page, accountA);
    await assertAccount(page, accountA);
    await visible(openRecord(page));
    await eventually(async () => await appearance(page).inputValue() === 'dark', 'Account A preference was replaced');
    await peer.close();
  });

  await check('Password reset email, mismatch, completion, prior session revocation and old/new password', async () => {
    await signOut(page);
    await button(page, 'Forgot password?').click();
    await field(page, 'Account email').fill(accountA.email);
    await button(page, 'Request reset link').click();
    await statusText(page, /If recovery is available/);
    const token = await mailToken(accountA, 'reset-password');
    await page.goto(`${base}/#reset-password=${token}`);
    await visible(heading(page, 'Choose a new password.'));
    assert.equal(new URL(page.url()).hash, '');
    await field(page, 'New password').fill(newPassword);
    await field(page, 'Confirm new password').fill(password);
    await button(page, 'Reset password').click();
    await visible(page.getByRole('alert').filter({ hasText: /match/i }));
    await field(page, 'Confirm new password').fill(newPassword);
    await button(page, 'Reset password').click();
    await statusText(page, /Sign in with your new password/);
    await isolated.reload();
    await visible(heading(isolated, 'Welcome back.'));
    await button(page, 'Back to sign in').click();
    await login(page, accountA);
    await visible(page.getByRole('alert'));
    await login(page, accountA, newPassword);
    await assertAccount(page, accountA);
    await visible(openRecord(page));
  });

  for (const width of [390, 320]) {
    await page.setViewportSize({ width, height: 844 });
    await check(`Workspace and application form at ${width}px`, async () => {
      await visible(button(page, 'Open navigation'));
      const documentWidths = await page.evaluate(() => [document.documentElement.clientWidth, document.documentElement.scrollWidth]);
      assert.deepEqual(documentWidths, [width, width]);
      await add(page).click();
      const bounds = await drawer(page).boundingBox();
      assert.ok(bounds && bounds.x >= -1 && bounds.x + bounds.width <= width + 1);
      await button(drawer(page), 'Close application').click();
    });
  }
  await check('Narrow navigation keyboard entry and Escape return', async () => {
    assert.equal(await workspace(page).isVisible(), false);
    await button(page, 'Open navigation').focus();
    await page.keyboard.press('Enter');
    await visible(button(page, 'Close navigation'));
    assert.equal(await activeElement(page), 'Close navigation');
    await page.keyboard.press('Escape');
    await hidden(button(page, 'Close navigation'));
    assert.equal(await activeElement(page), 'Open navigation');
    await page.keyboard.press('Enter');
    await button(workspace(page), /^Follow-ups/).focus();
    await page.keyboard.press('Enter');
    await hidden(button(page, 'Close navigation'));
    await visible(heading(page, 'Follow-up list'));
    assert.equal(await activeElement(page), 'Open navigation');
    await page.keyboard.press('Enter');
    await visible(button(page, 'Close navigation'));
    await page.setViewportSize({ width: 1440, height: 900 });
    await hidden(button(page, 'Close navigation'));
    assert.equal(await activeElement(page), 'Search applications');
  });
  await page.setViewportSize({ width: 320, height: 844 });
  await page.screenshot({ path: path.join(evidence, 'workspace-320.png') });
}

await mkdir(evidence, { recursive: true });
const temp = await mkdtemp(path.join(tmpdir(), 'campus-browser-'));
const log = await open(path.join(evidence, 'server.log'), 'w');
let app;
let browser;
try {
  const hash = createHash('sha256');
  for await (const bytes of createReadStream(jar)) hash.update(bytes);
  results.jarSha256 = hash.digest('hex');
  const reserve = createServer();
  const port = await listen(reserve);
  await stopServer(reserve);
  const mailPort = await listen(smtp);
  const base = `http://127.0.0.1:${port}`;
  const db = path.join(temp, 'browser').replaceAll('\\', '/');
  app = spawn(javaExecutable(), [
    '-jar', jar, `--server.port=${port}`, '--server.address=127.0.0.1',
    `--spring.datasource.url=jdbc:h2:file:${db};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;WRITE_DELAY=0`,
    '--spring.datasource.username=sa', '--spring.datasource.password=', '--spring.profiles.active=default',
    '--campus.registration-enabled=true', '--server.servlet.session.cookie.secure=false',
    '--spring.mail.host=127.0.0.1', `--spring.mail.port=${mailPort}`,
    '--spring.mail.properties.mail.smtp.auth=false', '--spring.mail.properties.mail.smtp.starttls.enable=false',
    '--spring.mail.properties.mail.smtp.starttls.required=false', `--campus.mail.public-origin=${base}`,
    '--campus.mail.worker-enabled=true', '--campus.verification.rate-limits-enabled=false',
    '--campus.reset.development-enabled=true', '--debug=false', '--trace=false', '--logging.level.root=INFO',
  ], { cwd: path.join(root, 'backend'), stdio: ['ignore', log.fd, log.fd], windowsHide: true });
  let launchError;
  app.once('error', error => { launchError = error; });
  await eventually(async () => {
    if (launchError) throw launchError;
    if (app.exitCode !== null) throw new Error(`Spring Boot exited ${app.exitCode}; see server.log`);
    return fetch(base, { signal: AbortSignal.timeout(1000) }).then(r => r.ok, () => false);
  }, 'Bundled app did not become ready; see server.log', 60000);
  browser = await chromium.launch({ channel: 'chrome', executablePath: process.env.BROWSER_VALIDATION_CHROME });
  results.browser = `Google Chrome ${browser.version()}`;
  const createPage = async () => {
    const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, colorScheme: 'light' });
    context.setDefaultTimeout(7000);
    return context.newPage();
  };
  await exercise(await createPage(), await createPage(), await createPage(), base);
} catch (error) {
  results.checks.push({ name: 'Browser scenario setup or dependency', result: 'fail', detail: error.message });
  console.error(error.stack);
  process.exitCode = 1;
} finally {
  await browser?.close();
  if (app?.pid && app.exitCode === null) {
    const exited = new Promise(resolve => app.once('exit', resolve));
    app.kill();
    await exited;
  }
  for (const socket of connections) socket.destroy();
  if (smtp.listening) await stopServer(smtp);
  await log.close();
  await writeFile(path.join(evidence, 'results.json'), `${JSON.stringify(results, null, 2)}\n`);
  await rm(temp, { recursive: true, force: true, maxRetries: 3 });
}
