import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

// Content is plain ESM plus Vite's ?raw imports. Resolve those imports in memory
// so source fidelity can be checked with node --test, without a build or Vite.
const compiledModules = new Map();
const rawImports = [];
async function contentModuleUrl(url) {
  if (compiledModules.has(url.href)) return compiledModules.get(url.href);
  const source = await readFile(url, 'utf8');
  const imports = [...source.matchAll(/^import\s+(.+?)\s+from\s+(['"])(.+?)\2;?[ \t]*$/gm)];
  let result = '';
  let cursor = 0;
  for (const entry of imports) {
    const [, binding, , specifier] = entry;
    assert.ok(specifier.startsWith('.'), `Content import must be local: ${specifier}`);
    result += source.slice(cursor, entry.index);
    if (specifier.endsWith('?raw')) {
      assert.match(binding, /^[A-Za-z_$][\w$]*$/, 'Raw sources use a default import');
      const snapshot = new URL(specifier.slice(0, -4), url);
      const content = await readFile(snapshot, 'utf8');
      rawImports.push({ url: snapshot, content });
      result += `const ${binding} = ${JSON.stringify(content)};`;
    } else {
      const dependency = await contentModuleUrl(new URL(specifier, url));
      result += `import ${binding} from ${JSON.stringify(dependency)};`;
    }
    cursor = entry.index + entry[0].length;
  }
  result += source.slice(cursor);
  const compiled = `data:text/javascript;base64,${Buffer.from(result).toString('base64')}`;
  compiledModules.set(url.href, compiled);
  return compiled;
}

const courseUrl = await contentModuleUrl(new URL('../src/content/course.js', import.meta.url));
const { modules, sources, glossary, buildSearchIndex, moduleById, sourceById } = await import(courseUrl);
const repository = new URL('../../', import.meta.url);
const lessons = modules.flatMap((module) => module.lessons);
const moduleIds = new Set(modules.map(({ id }) => id));
const sourceIds = new Set(sources.map(({ id }) => id));
const glossaryIds = new Set(glossary.map(({ id }) => id));

function nonempty(value, context) {
  assert.equal(typeof value, 'string', `${context} must be a string`);
  assert.ok(value.trim().length > 0, `${context} must not be empty`);
}

function links(ids, available, context) {
  assert.ok(Array.isArray(ids) && ids.length > 0, `${context} must have links`);
  assert.equal(new Set(ids).size, ids.length, `${context} must not repeat a link`);
  for (const id of ids) assert.ok(available.has(id), `${context} points to missing ${id}`);
}

test('the complete curriculum publishes eight ordered modules and 24 addressable lessons', () => {
  assert.equal(modules.length, 8);
  assert.equal(moduleIds.size, modules.length, 'Module IDs must be unique');
  assert.deepEqual(modules.map(({ number }) => number), ['01', '02', '03', '04', '05', '06', '07', '08']);
  assert.equal(lessons.length, 24);
  assert.equal(new Set(lessons.map(({ id }) => id)).size, lessons.length, 'Lesson IDs must be unique');
  for (const module of modules) {
    assert.equal(module.lessons.length, 3, `${module.id} needs its three lessons`);
    assert.ok(['code', 'system', 'data'].includes(module.lens), `${module.id} has an unsupported explorer lens`);
    assert.equal(moduleById(module.id), module, `${module.id} must resolve without the fallback`);
    for (const field of ['id', 'title', 'summary', 'outcome', 'duration']) nonempty(module[field], `${module.id}.${field}`);
    for (const lesson of module.lessons) {
      for (const field of ['id', 'title', 'kicker', 'lead']) nonempty(lesson[field], `${lesson.id}.${field}`);
      assert.ok(Array.isArray(lesson.paragraphs) && lesson.paragraphs.length > 0, `${lesson.id} needs lesson content`);
      lesson.paragraphs.forEach((paragraph) => nonempty(paragraph, `${lesson.id} paragraph`));
      nonempty(lesson.takeaway?.title, `${lesson.id} takeaway title`);
      nonempty(lesson.takeaway?.text, `${lesson.id} takeaway text`);
    }
  }
});

test('all lesson, module, source and glossary references resolve without fallback', () => {
  assert.equal(sourceIds.size, sources.length, 'Source IDs must be unique even when files are reused');
  assert.equal(glossaryIds.size, glossary.length, 'Glossary IDs must be unique');
  for (const module of modules) {
    links(module.sourceIds, sourceIds, `${module.id} source links`);
    for (const lesson of module.lessons) {
      links(lesson.sourceIds, sourceIds, `${lesson.id} source links`);
      links(lesson.concepts, glossaryIds, `${lesson.id} concepts`);
    }
  }
  for (const source of sources) assert.equal(sourceById(source.id), source, `${source.id} must resolve without the fallback`);
  for (const term of glossary) {
    assert.ok(moduleIds.has(term.moduleId), `${term.id} points to missing module ${term.moduleId}`);
    nonempty(term.term, `${term.id} term`);
    nonempty(term.definition, `${term.id} definition`);
  }
});

test('all 25 displayed raw source snapshots exactly match the actual repository files', async () => {
  assert.equal(sources.length, 25);
  assert.equal(rawImports.length, 25);
  assert.equal(new Set(rawImports.map(({ url }) => url.href)).size, 25, 'Each displayed source has a distinct snapshot import');
  for (const { content, url } of rawImports) {
    assert.ok(sources.some((source) => source.content === content), `Unused snapshot import ${url.pathname}`);
  }
  await Promise.all(sources.map(async (source) => {
    nonempty(source.path, `${source.id} repository path`);
    assert.ok(!source.path.startsWith('/') && !source.path.includes('\\') && !source.path.split('/').includes('..'), `${source.id} must use an exact repository-relative path`);
    const actualUrl = new URL(source.path, repository);
    assert.ok(actualUrl.href.startsWith(repository.href), `${source.id} must remain in the repository`);
    const actual = await readFile(actualUrl, 'utf8');
    assert.equal(source.content, actual, `${source.id} has drifted from ${source.path}; update the snapshot and recheck its teaching claims`);
  }));
});

test('source focus ranges select real nonempty lines and include useful context', () => {
  for (const source of sources) {
    for (const field of ['title', 'group', 'note', 'content']) nonempty(source[field], `${source.id}.${field}`);
    assert.equal(source.focus.length, 2, `${source.id} needs a start and end line`);
    const [start, end] = source.focus;
    const lines = source.content.split(/\r?\n/);
    assert.ok(Number.isInteger(start) && Number.isInteger(end), `${source.id} focus lines must be integers`);
    assert.ok(start >= 1 && end >= start && end <= lines.length, `${source.id} focus ${start}..${end} is outside its ${lines.length} lines`);
    nonempty(lines.slice(start - 1, end).join('\n'), `${source.id} highlighted excerpt`);
  }
});

test('every module has an actionable mission and an optional local lab with prerequisites and evidence', async () => {
  // All supplied lab commands invoke the real repository wrapper, never a
  // fictional development launcher or a cloud deployment from this course.
  assert.ok((await readFile(new URL('mvnw', repository), 'utf8')).length > 0);
  for (const module of modules) {
    for (const field of ['title', 'brief', 'goal']) nonempty(module.mission?.[field], `${module.id} mission ${field}`);
    const lab = module.lab;
    nonempty(lab?.title, `${module.id} lab title`);
    assert.ok(Array.isArray(lab.prerequisites) && lab.prerequisites.length >= 2, `${module.id} lab must name its prerequisites`);
    assert.ok(Array.isArray(lab.steps) && lab.steps.length >= 3, `${module.id} lab must provide a concrete sequence`);
    assert.ok(Array.isArray(lab.commands) && lab.commands.length > 0, `${module.id} lab must provide a runnable command`);
    for (const text of [...lab.prerequisites, ...lab.steps, lab.expected]) {
      nonempty(text, `${module.id} lab content`);
      assert.doesNotMatch(text, /\bTODO\b|coming soon|lorem ipsum/i, `${module.id} contains unfinished lab content`);
    }
    for (const command of lab.commands) {
      assert.match(command, /^\.\/mvnw\s+/, `${module.id} lab command must use the repository wrapper`);
      assert.doesNotMatch(command, /[;&|]/, `${module.id} lab command must be a single reviewable operation`);
    }
    assert.ok(lab.expected.length >= 60, `${module.id} lab must describe what its evidence establishes`);
  }
});

test('search covers every published lesson, mission, glossary term and source with valid navigation', () => {
  const entries = buildSearchIndex();
  assert.equal(entries.length, lessons.length + modules.length + glossary.length + sources.length);
  assert.equal(new Set(entries.map(({ id }) => id)).size, entries.length, 'Search IDs must be globally unambiguous');
  const byId = new Map(entries.map((entry) => [entry.id, entry]));
  for (const module of modules) {
    module.lessons.forEach((lesson, step) => {
      const entry = byId.get(lesson.id);
      assert.equal(entry?.type, 'Lesson');
      assert.equal(entry.moduleId, module.id);
      assert.equal(entry.step, step);
    });
    assert.equal(byId.get(`mission-${module.id}`)?.moduleId, module.id);
  }
  for (const source of sources) assert.equal(byId.get(source.id)?.type, 'Source');
  for (const term of glossary) assert.equal(byId.get(term.id)?.type, 'Glossary');
  for (const entry of entries) {
    nonempty(entry.title, `${entry.id} search title`);
    nonempty(entry.detail, `${entry.id} search detail`);
    nonempty(entry.text, `${entry.id} search text`);
  }
});
