document.querySelector('.home-link')?.addEventListener('click', event => {
  if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
  try { sessionStorage.setItem('blockified-return-home', '1'); }
  catch (_) { /* Navigation still works; only reverse animation is skipped. */ }
});

const searchEntries = [
  ['Install Blockified', 'Guide', './guide.html#install', 'requirements neoforge minecraft bbs connector fabric api setup'],
  ['Choose a content type', 'Guide', './guide.html#content-type', 'lightweight song complete mod pack folders assets'],
  ['Create a first song', 'Guide', './guide.html#first-song', 'chart editor audio import ogg stems difficulties'],
  ['Charting workflow', 'Guide', './guide.html#charting', 'notes sustains events waveform metronome offset save playtest'],
  ['Complete pack layout', 'Guide', './guide.html#complete-pack', 'mods scripts stages characters machines weeks worlds'],
  ['Songs and chart formats', 'Wiki', './wiki.html#songs', 'psych v-slice codename difficulties events audio original directory'],
  ['Presentation modes', 'Wiki', './wiki.html#modes', 'minecraft fnf legacy look mode'],
  ['Characters and visuals', 'Wiki', './wiki.html#characters', 'bbs psych sprites forms outlines icons camera offsets'],
  ['Creator tools', 'Wiki', './wiki.html#editors', 'chart character free camera note settings editor'],
  ['Machines and worlds', 'Wiki', './wiki.html#machines', 'designer hitbox chunk loader bundled world lua menu'],
  ['Lua and events overview', 'Wiki', './wiki.html#lua', 'gameplay psych machine menu scripting'],
  ['Multiplayer and safety', 'Wiki', './wiki.html#multiplayer', 'server lan sandbox cache limits permissions'],
  ['Gameplay rollback', 'Wiki', './wiki.html#rollback', 'world inventory blocks gamemode time quit finish loss restart'],
  ['Chart events', 'Documentation', './documentation.html#events', 'camera focus orbit rotation command character shading property sound'],
  ['Gameplay Lua API', 'Documentation', './documentation.html#gameplay-lua', 'psych callbacks functions scripts folders globals sprites text world camera tweens timers notes properties custom notetypes safety sandbox multiplayer limitations'],
  ['Machine-menu Lua API', 'Documentation', './documentation.html#machine-lua', 'ui button image animated sprite graph play song screen settings'],
  ['Property paths', 'Documentation', './documentation.html#properties', 'getProperty setProperty strums healthbar camera chunk points'],
  ['Creator tools', 'Documentation', './documentation.html#creator-tools', 'chart character noteskin editor week maker machine profile free camera tutorial'],
  ['Commands', 'Documentation', './documentation.html#commands', 'fnf editor reload world export import cheats operator'],
  ['Compatibility boundaries', 'Documentation', './documentation.html#compatibility', 'psych v-slice codename sandbox unsupported dedicated server']
].map(([title, section, url, keywords]) => ({ title, section, url, keywords }));

const searchForm = document.querySelector('.header-search');
const searchInput = searchForm?.querySelector('input[type="search"]');
let searchResults;

function normalizedWords(value) {
  return value.toLowerCase().trim().split(/\s+/).filter(Boolean);
}

function findSearchMatches(value) {
  const words = normalizedWords(value);
  if (!words.length) return [];
  return searchEntries
    .map(entry => {
      const haystack = `${entry.title} ${entry.section} ${entry.keywords}`.toLowerCase();
      const matches = words.filter(word => haystack.includes(word)).length;
      const titleBonus = words.filter(word => entry.title.toLowerCase().includes(word)).length;
      return { entry, score: matches + titleBonus };
    })
    .filter(result => result.score >= words.length)
    .sort((a, b) => b.score - a.score || a.entry.title.localeCompare(b.entry.title))
    .slice(0, 7)
    .map(result => result.entry);
}

function hideSearchResults() {
  if (searchResults) searchResults.hidden = true;
}

function renderSearchResults(value) {
  if (!searchResults || value.trim().length < 2) {
    hideSearchResults();
    return;
  }

  const matches = findSearchMatches(value);
  searchResults.replaceChildren();
  if (!matches.length) {
    const empty = document.createElement('p');
    empty.className = 'search-empty';
    empty.textContent = 'No matching Blockified topic.';
    searchResults.append(empty);
  } else {
    for (const entry of matches) {
      const link = document.createElement('a');
      link.className = 'search-result';
      link.href = entry.url;
      const title = document.createElement('strong');
      title.textContent = entry.title;
      const section = document.createElement('span');
      section.textContent = entry.section;
      link.append(title, section);
      searchResults.append(link);
    }
  }
  searchResults.hidden = false;
}

if (searchForm && searchInput) {
  searchResults = document.createElement('div');
  searchResults.className = 'search-results';
  searchResults.hidden = true;
  searchResults.setAttribute('aria-live', 'polite');
  searchForm.append(searchResults);

  searchInput.addEventListener('input', () => renderSearchResults(searchInput.value));
  searchInput.addEventListener('focus', () => renderSearchResults(searchInput.value));
  searchInput.addEventListener('keydown', event => {
    if (event.key === 'Escape') {
      hideSearchResults();
      searchInput.blur();
    }
    if (event.key === 'ArrowDown') {
      const first = searchResults?.querySelector('a');
      if (first) {
        event.preventDefault();
        first.focus();
      }
    }
  });

  searchResults.addEventListener('keydown', event => {
    if (!(event.target instanceof HTMLAnchorElement)) return;
    const links = [...searchResults.querySelectorAll('a')];
    const index = links.indexOf(event.target);
    if (event.key === 'ArrowDown' && links[index + 1]) {
      event.preventDefault();
      links[index + 1].focus();
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      if (links[index - 1]) links[index - 1].focus();
      else searchInput.focus();
    } else if (event.key === 'Escape') {
      hideSearchResults();
      searchInput.focus();
    }
  });

  searchForm.addEventListener('submit', event => {
    const first = findSearchMatches(searchInput.value)[0];
    if (!first) return;
    event.preventDefault();
    window.location.href = first.url;
  });

  document.addEventListener('pointerdown', event => {
    if (!searchForm.contains(event.target)) hideSearchResults();
  });
}

const dynamicToc = document.querySelector('.on-this-page-links');
const mainSectionLinks = [...document.querySelectorAll('.section-index a[href^="#"], .docs-nav a[href^="#"]')];
if (!dynamicToc) mainSectionLinks.push(...document.querySelectorAll('.on-this-page a[href^="#"]'));
const sections = [...new Set(mainSectionLinks.map(link => document.querySelector(link.hash)).filter(Boolean))];
let currentMainSection = '';

function populateOnThisPage(section) {
  if (!dynamicToc || !section) return;
  dynamicToc.replaceChildren();
  const entries = [...section.querySelectorAll('[data-toc][id]')];
  for (const entry of entries) {
    const link = document.createElement('a');
    link.href = `#${entry.id}`;
    link.textContent = entry.dataset.toc;
    dynamicToc.append(link);
  }
}

function setActiveSection(id) {
  for (const link of mainSectionLinks) link.classList.toggle('active', link.hash === `#${id}`);
  if (currentMainSection !== id) {
    currentMainSection = id;
    populateOnThisPage(document.getElementById(id));
  }
}

function setActiveSubsection(id) {
  if (!dynamicToc) return;
  for (const link of dynamicToc.querySelectorAll('a')) {
    link.classList.toggle('active', link.hash === `#${id}`);
  }
}

// IntersectionObserver updates the highlight while scrolling, but an anchor can
// land outside its narrow observation band. Select a clicked entry immediately
// so the previous subsection never remains highlighted after navigation.
dynamicToc?.addEventListener('click', event => {
  const link = event.target instanceof Element ? event.target.closest('a[href^="#"]') : null;
  if (link) setActiveSubsection(link.hash.slice(1));
});

window.addEventListener('hashchange', () => {
  const target = window.location.hash ? document.querySelector(window.location.hash) : null;
  if (!target) return;
  const section = target.closest('.reference-section');
  if (section) setActiveSection(section.id);
  if (target.matches('[data-toc]')) setActiveSubsection(target.id);
});

for (const link of mainSectionLinks) {
  link.addEventListener('click', () => setActiveSection(link.hash.slice(1)));
}

const scroller = document.querySelector('.placeholder-page');
if ('IntersectionObserver' in window && sections.length) {
  const observer = new IntersectionObserver(entries => {
    const visible = entries.filter(entry => entry.isIntersecting)
      .sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top)[0];
    if (visible) setActiveSection(visible.target.id);
  }, { root: scroller, rootMargin: '-8% 0px -72% 0px', threshold: 0 });
  sections.forEach(section => observer.observe(section));

  if (dynamicToc) {
    const subsectionObserver = new IntersectionObserver(entries => {
      const visible = entries.filter(entry => entry.isIntersecting)
        .sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top)[0];
      if (visible) setActiveSubsection(visible.target.id);
    }, { root: scroller, rootMargin: '-10% 0px -76% 0px', threshold: 0 });
    document.querySelectorAll('[data-toc][id]').forEach(entry => subsectionObserver.observe(entry));
  }
}

const hashTarget = window.location.hash ? document.querySelector(window.location.hash) : null;
const initialSection = hashTarget?.closest('.reference-section') || sections[0];
if (initialSection) setActiveSection(initialSection.id);
if (hashTarget) {
  if (hashTarget.matches('[data-toc]')) setActiveSubsection(hashTarget.id);
  hashTarget.classList.add('site-search-hit');
  hashTarget.addEventListener('animationend', () => hashTarget.classList.remove('site-search-hit'), { once: true });
}
