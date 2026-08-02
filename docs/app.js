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
  const releasesUrl = "https://github.com/Migokc/Blockified-engine_a-Minecraft-mod-made-with-AI/releases";
  const latestReleaseApi = "https://api.github.com/repos/Migokc/Blockified-engine_a-Minecraft-mod-made-with-AI/releases/latest";
  let latestReleasePromise;

  const escapeHtml = (value) => String(value)
    .replaceAll("&", "&amp;").replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;").replaceAll('"', "&quot;");

  const textOnly = (html) => {
    const node = document.createElement("div");
    node.innerHTML = html;
    return node.textContent || "";
  };

  function loadLatestRelease() {
    if (!latestReleasePromise) {
      latestReleasePromise = fetch(latestReleaseApi, {
        headers: { Accept: "application/vnd.github+json" }
      }).then((response) => {
        if (!response.ok) throw new Error("No published release");
        return response.json();
      });
    }
    return latestReleasePromise;
  }

  function releaseVersion(release) {
    const assetNames = Array.isArray(release.assets)
      ? release.assets.map((asset) => asset.name || "").join(" ")
      : "";
    const match = (release.name + " " + assetNames + " " + release.tag_name)
      .match(/\b\d+\.\d+\.\d+bbs\b/i);
    return match ? match[0] : release.tag_name;
  }

  function hydrateLatestRelease() {
    const title = document.getElementById("latest-release-title");
    if (!title) return;
    const status = document.getElementById("latest-release-status");
    const jarLink = document.getElementById("latest-release-jar");
    const pageLink = document.getElementById("latest-release-page");

    loadLatestRelease().then((release) => {
      const assets = Array.isArray(release.assets) ? release.assets : [];
      const jars = assets.filter((asset) => String(asset.name || "").toLowerCase().endsWith(".jar"));
      const jar = jars.find((asset) => !/(sources|dev|javadoc)/i.test(asset.name)) || jars[0];
      const published = release.published_at
        ? new Intl.DateTimeFormat(undefined, { dateStyle: "medium" }).format(new Date(release.published_at))
        : "published on GitHub";
      title.textContent = release.name || release.tag_name || "Latest release";
      status.textContent = jar
        ? jar.name + " · " + published
        : "Published " + published + ". This release has no JAR asset; check its notes.";
      pageLink.href = release.html_url || releasesUrl;
      jarLink.href = jar ? jar.browser_download_url : (release.html_url || releasesUrl);
      jarLink.textContent = jar ? "Download JAR" : "Open latest release";
      document.querySelectorAll(".version-chip").forEach((chip) => {
        chip.textContent = releaseVersion(release) || chip.textContent;
      });
    }).catch(() => {
      title.textContent = "Published releases";
      status.textContent = "Latest release could not be checked. Open GitHub Releases to choose the newest JAR.";
      jarLink.href = releasesUrl;
      jarLink.textContent = "View releases";
      pageLink.href = releasesUrl;
    });
  }

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

    hydrateLatestRelease();

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
      '</strong><small>' + escapeHtml(page.eyebrow) + '</small><span>' + escapeHtml(page.description) + '</span></a>'
    ).join("");
    searchResults.querySelectorAll("a").forEach((link) => link.addEventListener("click", closeSearch));
  }

  renderNav();
  renderPage();
  loadLatestRelease().then((release) => {
    document.querySelectorAll(".version-chip").forEach((chip) => {
      chip.textContent = releaseVersion(release) || chip.textContent;
    });
  }).catch(() => {});
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
