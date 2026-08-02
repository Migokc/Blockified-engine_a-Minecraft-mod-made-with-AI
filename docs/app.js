(function () {
  "use strict";

  const docs = window.BLOCKIFIED_DOCS;
  const nav = document.getElementById("nav");
  const content = document.getElementById("doc-content");
  const toc = document.getElementById("toc");
  const sidebar = document.getElementById("sidebar");
  const scrim = document.getElementById("sidebar-scrim");
  const mobileMenu = document.getElementById("mobile-menu");
  const overlay = document.getElementById("search-overlay");
  const searchTrigger = document.getElementById("search-trigger");
  const searchClose = document.getElementById("search-close");
  const searchInput = document.getElementById("search-input");
  const searchResults = document.getElementById("search-results");

  const escapeHtml = (value) => String(value)
    .replaceAll("&", "&amp;").replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;").replaceAll('"', "&quot;");

  const textOnly = (html) => {
    const node = document.createElement("div");
    node.innerHTML = html;
    return node.textContent || "";
  };

  function route() {
    const slug = location.hash.replace(/^#\/?/, "").split("?")[0];
    return docs.pages[slug] ? slug : "home";
  }

  function renderNav() {
    nav.innerHTML = docs.groups.map((group) =>
      '<section class="nav-group"><div class="nav-group-title">' + escapeHtml(group.title) + '</div>' +
      group.pages.map((slug) => {
        const homeLogo = slug === "home";
        const label = homeLogo
          ? '<img class="nav-home-logo" src="./assets/blockified-engine-logo.png" alt="Blockified Engine" width="1024" height="284">'
          : escapeHtml(docs.pages[slug].title);
        return '<a class="nav-link' + (homeLogo ? ' home-logo-link' : '') + '" data-page="' + slug +
          '" href="#/' + slug + '">' + label + '</a>';
      }).join("") + '</section>'
    ).join("");
  }

  function renderPage() {
    const slug = route();
    const page = docs.pages[slug];
    const pageTitle = page.titleLogo
      ? '<h1 class="page-title-logo-wrap"><img class="page-title-logo" src="' + escapeHtml(page.titleLogo) +
        '" alt="' + escapeHtml(page.title) + '" width="1024" height="284"></h1>'
      : '<h1>' + escapeHtml(page.title) + '</h1>';
    document.title = page.title + " | Blockified Engine Docs";
    content.innerHTML = '<header class="page-header"><div class="eyebrow">' + escapeHtml(page.eyebrow) +
      '</div>' + pageTitle + '<p class="page-lead">' + escapeHtml(page.description) +
      '</p><div class="page-meta">' + page.tags.map((tag) => '<span class="meta-tag">' + escapeHtml(tag) +
      '</span>').join("") + '</div></header>' + page.body;

    document.querySelectorAll(".nav-link").forEach((link) => {
      const active = link.dataset.page === slug;
      link.classList.toggle("active", active);
      active ? link.setAttribute("aria-current", "page") : link.removeAttribute("aria-current");
    });

    content.querySelectorAll("pre").forEach((pre) => {
      const button = document.createElement("button");
      button.className = "copy-code";
      button.type = "button";
      button.textContent = "Copy";
      button.addEventListener("click", async () => {
        try {
          await navigator.clipboard.writeText(pre.querySelector("code").textContent);
          button.textContent = "Copied";
          setTimeout(() => { button.textContent = "Copy"; }, 1200);
        } catch (_) {
          button.textContent = "Select text";
        }
      });
      pre.appendChild(button);
    });

    const headings = Array.from(content.querySelectorAll(".doc-section > h2"));
    toc.innerHTML = headings.map((heading) => '<a href="#' + heading.parentElement.id + '">' +
      escapeHtml(heading.textContent) + '</a>').join("");
    closeSidebar();
    window.scrollTo({ top: 0, behavior: "instant" });
  }

  function openSidebar() {
    sidebar.classList.add("open");
    scrim.hidden = false;
    mobileMenu.setAttribute("aria-expanded", "true");
  }

  function closeSidebar() {
    sidebar.classList.remove("open");
    scrim.hidden = true;
    mobileMenu.setAttribute("aria-expanded", "false");
  }

  function openSearch() {
    overlay.hidden = false;
    document.body.style.overflow = "hidden";
    searchInput.value = "";
    renderSearch("");
    requestAnimationFrame(() => searchInput.focus());
  }

  function closeSearch() {
    overlay.hidden = true;
    document.body.style.overflow = "";
    searchTrigger.focus();
  }

  function renderSearch(query) {
    const needle = query.trim().toLowerCase();
    const entries = Object.entries(docs.pages).map(([slug, page]) => ({
      slug, page,
      haystack: (page.title + " " + page.description + " " + page.tags.join(" ") + " " + textOnly(page.body)).toLowerCase()
    }));
    const matches = entries.filter((entry) => !needle || entry.haystack.includes(needle)).slice(0, 12);
    if (!matches.length) {
      searchResults.innerHTML = '<div class="search-empty">No Blockified documentation matched that search.</div>';
      return;
    }
    searchResults.innerHTML = matches.map(({ slug, page }) =>
      '<a class="search-result" href="#/' + slug + '"><strong>' + escapeHtml(page.title) +
      '</strong><span>' + escapeHtml(page.description) + '</span></a>'
    ).join("");
    searchResults.querySelectorAll("a").forEach((link) => link.addEventListener("click", closeSearch));
  }

  renderNav();
  renderPage();
  window.addEventListener("hashchange", renderPage);
  mobileMenu.addEventListener("click", () => sidebar.classList.contains("open") ? closeSidebar() : openSidebar());
  scrim.addEventListener("click", closeSidebar);
  searchTrigger.addEventListener("click", openSearch);
  searchClose.addEventListener("click", closeSearch);
  searchInput.addEventListener("input", () => renderSearch(searchInput.value));
  overlay.addEventListener("click", (event) => { if (event.target === overlay) closeSearch(); });
  document.addEventListener("keydown", (event) => {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "k") {
      event.preventDefault();
      openSearch();
    } else if (event.key === "Escape" && !overlay.hidden) {
      closeSearch();
    }
  });
}());
