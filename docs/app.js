(() => {
  const I18N = window.NEKO_I18N || {};
  const SUPPORTED_LANGS = ['ru', 'en', 'zh-CN', 'zh-TW', 'fa', 'es'];

  let currentLang = detectInitialLang();
  let currentRepo = '';
  let cachedRelease = null;

  const els = {
    langSelect: document.getElementById('langSelect'),
    headerGithubLink: document.getElementById('headerGithubLink'),
    footerRepoLink: document.getElementById('footerRepoLink'),
    footerReleasesLink: document.getElementById('footerReleasesLink'),
    footerIssuesLink: document.getElementById('footerIssuesLink'),
    footerLicenseLink: document.getElementById('footerLicenseLink'),
    heroReleaseBadgeText: document.getElementById('heroReleaseBadgeText'),
    heroPrimaryDownload: document.getElementById('heroPrimaryDownload'),
    heroDownloadTitle: document.getElementById('heroDownloadTitle'),
    heroDownloadMeta: document.getElementById('heroDownloadMeta'),
    repoSlugLabel: document.getElementById('repoSlugLabel'),
    editRepoBtn: document.getElementById('editRepoBtn'),
    repoForm: document.getElementById('repoForm'),
    repoInput: document.getElementById('repoInput'),
    releaseLoading: document.getElementById('releaseLoading'),
    releaseError: document.getElementById('releaseError'),
    releaseErrorDesc: document.getElementById('releaseErrorDesc'),
    fallbackGithubReleasesLink: document.getElementById('fallbackGithubReleasesLink'),
    retryReleaseBtn: document.getElementById('retryReleaseBtn'),
    releaseContent: document.getElementById('releaseContent'),
    relTagBadge: document.getElementById('relTagBadge'),
    relPrereleaseBadge: document.getElementById('relPrereleaseBadge'),
    relTitle: document.getElementById('relTitle'),
    relDate: document.getElementById('relDate'),
    relDownloads: document.getElementById('relDownloads'),
    relGithubUrl: document.getElementById('relGithubUrl'),
    abiGrid: document.getElementById('abiGrid'),
    otherAssetsRow: document.getElementById('otherAssetsRow'),
    relNotes: document.getElementById('relNotes'),
    lightbox: document.getElementById('lightbox'),
    lightboxImg: document.getElementById('lightboxImg'),
    lightboxClose: document.getElementById('lightboxClose'),
  };

  function detectInitialLang() {
    const saved = localStorage.getItem('nekoxray_site_lang');
    if (saved && SUPPORTED_LANGS.includes(saved)) return saved;
    const nav = (navigator.language || 'ru').toLowerCase();
    if (nav.startsWith('ru')) return 'ru';
    if (nav.startsWith('fa')) return 'fa';
    if (nav.startsWith('es')) return 'es';
    if (nav.startsWith('zh')) {
      return nav.includes('tw') || nav.includes('hk') || nav.includes('hant') ? 'zh-TW' : 'zh-CN';
    }
    return 'en';
  }

  function localeForDates(lang) {
    if (lang === 'ru') return 'ru-RU';
    if (lang === 'zh-CN') return 'zh-CN';
    if (lang === 'zh-TW') return 'zh-TW';
    if (lang === 'fa') return 'fa-IR';
    if (lang === 'es') return 'es-ES';
    return 'en-US';
  }

  function applyTranslations() {
    const t = I18N[currentLang] || I18N.en;
    document.documentElement.lang = currentLang;
    document.documentElement.dir = currentLang === 'fa' ? 'rtl' : 'ltr';
    if (els.langSelect) {
      els.langSelect.value = currentLang;
    }
    document.querySelectorAll('[data-i18n]').forEach((node) => {
      const key = node.getAttribute('data-i18n');
      if (t[key]) node.textContent = t[key];
    });
    if (cachedRelease) {
      renderRelease(cachedRelease);
    }
  }

  async function detectPreferredAbi() {
    try {
      if (navigator.userAgentData && navigator.userAgentData.getHighEntropyValues) {
        const info = await navigator.userAgentData.getHighEntropyValues(['architecture', 'bitness']);
        const arch = (info.architecture || '').toLowerCase();
        const bits = String(info.bitness || '');
        if (arch.includes('arm')) return bits === '32' ? 'armeabi-v7a' : 'arm64-v8a';
        if (arch.includes('x86')) return bits === '32' ? 'x86' : 'x86_64';
      }
    } catch (_) {
      // ignore UA-CH failure
    }
    const ua = (navigator.userAgent || '').toLowerCase();
    if (ua.includes('x86_64') || ua.includes('amd64')) return 'x86_64';
    if (ua.includes('i686') || ua.includes('x86')) return 'x86';
    if (ua.includes('armv7') || ua.includes('armeabi')) return 'armeabi-v7a';
    return 'arm64-v8a';
  }

  function isValidRepoSlug(val) {
    return /^[a-zA-Z0-9._-]+\/[a-zA-Z0-9._-]+$/.test((val || '').trim());
  }

  async function resolveRepository() {
    const urlParams = new URLSearchParams(window.location.search);
    const fromParam = urlParams.get('repo');
    if (isValidRepoSlug(fromParam)) return fromParam.trim();

    try {
      const cfgResp = await fetch('repo-config.json', { cache: 'no-store' });
      if (cfgResp.ok) {
        const cfg = await cfgResp.json();
        if (isValidRepoSlug(cfg.repo)) return cfg.repo.trim();
      }
    } catch (_) {
      // ignore when not deployed via pages.yml
    }

    const metaRepo = document.querySelector('meta[name="github-repo"]')?.content;
    if (isValidRepoSlug(metaRepo)) return metaRepo.trim();

    const host = window.location.hostname.toLowerCase();
    if (host.endsWith('.github.io')) {
      const owner = host.slice(0, -'.github.io'.length);
      const parts = window.location.pathname.split('/').filter(Boolean);
      const repoName = parts.length > 0 ? parts[0] : `${owner}.github.io`;
      const candidate = `${owner}/${repoName}`;
      if (isValidRepoSlug(candidate)) return candidate;
    }

    const saved = localStorage.getItem('nekoxray_repo');
    if (isValidRepoSlug(saved)) return saved.trim();

    return '';
  }

  function updateRepoLinks(repo) {
    const base = repo ? `https://github.com/${repo}` : 'https://github.com';
    els.repoSlugLabel.textContent = repo || 'repo not set';
    els.headerGithubLink.href = base;
    els.footerRepoLink.href = base;
    els.footerReleasesLink.href = repo ? `${base}/releases` : base;
    els.footerIssuesLink.href = repo ? `${base}/issues` : base;
    if (els.footerLicenseLink) {
      els.footerLicenseLink.href = repo ? `${base}/blob/main/LICENSE` : 'https://opensource.org/licenses/MIT';
    }
    els.fallbackGithubReleasesLink.href = repo ? `${base}/releases` : base;
  }

  function setReleaseView(state) {
    els.releaseLoading.classList.toggle('hidden', state !== 'loading');
    els.releaseError.classList.toggle('hidden', state !== 'error');
    els.releaseContent.classList.toggle('hidden', state !== 'content');
  }

  function formatBytes(bytes) {
    if (!bytes || bytes <= 0) return '—';
    const mb = bytes / (1024 * 1024);
    if (mb >= 1) return `${mb.toFixed(1)} MB`;
    return `${Math.round(bytes / 1024)} KB`;
  }

  function classifyAssetAbi(name) {
    const n = name.toLowerCase();
    if (!n.endsWith('.apk')) return null;
    if (n.includes('arm64-v8a') || n.includes('arm64')) return 'arm64-v8a';
    if (n.includes('armeabi-v7a') || n.includes('arm-v7a') || n.includes('armv7')) return 'armeabi-v7a';
    if (n.includes('x86_64')) return 'x86_64';
    if (n.includes('x86')) return 'x86';
    if (n.includes('universal')) return 'universal';
    return 'universal';
  }

  function escapeHtml(str) {
    return str
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function renderMarkdownLite(md) {
    const t = I18N[currentLang] || I18N.en;
    if (!md || !md.trim()) {
      return `<p>${escapeHtml(t.noNotes)}</p>`;
    }
    const lines = escapeHtml(md).split(/\r?\n/);
    let html = '';
    let inList = false;

    const formatInline = (text) =>
      text
        .replace(/`([^`]+)`/g, '<code>$1</code>')
        .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
        .replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g, '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>');

    for (const rawLine of lines) {
      const line = rawLine.trim();
      if (!line) {
        if (inList) {
          html += '</ul>';
          inList = false;
        }
        continue;
      }
      if (line.startsWith('### ')) {
        if (inList) { html += '</ul>'; inList = false; }
        html += `<h4>${formatInline(line.slice(4))}</h4>`;
      } else if (line.startsWith('## ') || line.startsWith('# ')) {
        if (inList) { html += '</ul>'; inList = false; }
        html += `<h3>${formatInline(line.replace(/^#+\s+/, ''))}</h3>`;
      } else if (line.startsWith('- ') || line.startsWith('* ')) {
        if (!inList) {
          html += '<ul>';
          inList = true;
        }
        html += `<li>${formatInline(line.slice(2))}</li>`;
      } else {
        if (inList) { html += '</ul>'; inList = false; }
        html += `<p>${formatInline(line)}</p>`;
      }
    }
    if (inList) html += '</ul>';
    return html;
  }

  async function renderRelease(release) {
    cachedRelease = release;
    const t = I18N[currentLang] || I18N.en;
    const preferredAbi = await detectPreferredAbi();
    const assets = Array.isArray(release.assets) ? release.assets : [];

    const apkOrder = ['arm64-v8a', 'universal', 'armeabi-v7a', 'x86_64', 'x86'];
    const apkAssets = [];
    const otherAssets = [];

    for (const asset of assets) {
      const abi = classifyAssetAbi(asset.name || '');
      if (abi) {
        apkAssets.push({ asset, abi });
      } else {
        otherAssets.push(asset);
      }
    }

    apkAssets.sort((a, b) => {
      const ia = apkOrder.indexOf(a.abi);
      const ib = apkOrder.indexOf(b.abi);
      return (ia === -1 ? 99 : ia) - (ib === -1 ? 99 : ib);
    });

    const recommendedEntry =
      apkAssets.find((x) => x.abi === preferredAbi) ||
      apkAssets.find((x) => x.abi === 'arm64-v8a') ||
      apkAssets.find((x) => x.abi === 'universal') ||
      apkAssets[0] ||
      null;

    const tag = release.tag_name || release.name || 'latest';
    els.heroReleaseBadgeText.textContent = t.heroBadgeReady.replace('{tag}', tag);

    if (recommendedEntry) {
      els.heroPrimaryDownload.href = recommendedEntry.asset.browser_download_url;
      els.heroPrimaryDownload.setAttribute('download', recommendedEntry.asset.name);
      els.heroDownloadTitle.textContent = t.heroDownloadReady
        .replace('{tag}', tag)
        .replace('{abi}', recommendedEntry.abi);
      els.heroDownloadMeta.textContent = `${recommendedEntry.asset.name} · ${formatBytes(recommendedEntry.asset.size)}`;
    } else if (release.html_url) {
      els.heroPrimaryDownload.href = release.html_url;
      els.heroPrimaryDownload.target = '_blank';
      els.heroDownloadTitle.textContent = `${t.heroDownloadDefault} (${tag})`;
      els.heroDownloadMeta.textContent = 'GitHub Releases';
    }

    els.relTagBadge.textContent = tag;
    els.relPrereleaseBadge.classList.toggle('hidden', !release.prerelease);
    els.relTitle.textContent = release.name || `NekoXray ${tag}`;

    const pubDate = release.published_at ? new Date(release.published_at) : null;
    els.relDate.textContent = pubDate
      ? pubDate.toLocaleDateString(localeForDates(currentLang), {
          year: 'numeric',
          month: 'short',
          day: 'numeric',
        })
      : '—';

    const totalDl = assets.reduce((sum, a) => sum + (a.download_count || 0), 0);
    els.relDownloads.textContent = totalDl.toLocaleString(localeForDates(currentLang));
    els.relGithubUrl.href = release.html_url || `https://github.com/${currentRepo}/releases`;

    els.abiGrid.innerHTML = '';
    if (apkAssets.length === 0) {
      const linkCard = document.createElement('a');
      linkCard.className = 'abi-card recommended';
      linkCard.href = release.html_url || '#';
      linkCard.target = '_blank';
      linkCard.rel = 'noopener noreferrer';
      linkCard.innerHTML = `
        <div class="abi-top">
          <span class="abi-name">GitHub Release</span>
          <span class="abi-badge">${escapeHtml(tag)}</span>
        </div>
        <p class="abi-desc">${escapeHtml(t.openGithubReleases)}</p>
        <div class="abi-bottom">
          <span>APK / Source</span>
          <span class="abi-dl-pill">${escapeHtml(t.downloadBtn)} ↓</span>
        </div>
      `;
      els.abiGrid.appendChild(linkCard);
    } else {
      for (const item of apkAssets) {
        const isRec = recommendedEntry && item.asset.id === recommendedEntry.asset.id;
        const card = document.createElement('a');
        card.className = `abi-card${isRec ? ' recommended' : ''}`;
        card.href = item.asset.browser_download_url;
        card.setAttribute('download', item.asset.name);

        const desc = (t.abiDescriptions && t.abiDescriptions[item.abi]) || item.asset.name;
        card.innerHTML = `
          <div class="abi-top">
            <span class="abi-name">${escapeHtml(item.abi)}</span>
            ${isRec ? `<span class="abi-badge">${escapeHtml(t.recommendedBadge)}</span>` : ''}
          </div>
          <p class="abi-desc">${escapeHtml(desc)}</p>
          <div class="abi-bottom">
            <span class="mono">${escapeHtml(formatBytes(item.asset.size))} · ${(item.asset.download_count || 0).toLocaleString()} ${escapeHtml(t.downloadsShort)}</span>
            <span class="abi-dl-pill">${escapeHtml(t.downloadBtn)} ↓</span>
          </div>
        `;
        els.abiGrid.appendChild(card);
      }
    }

    els.otherAssetsRow.innerHTML = '';
    for (const asset of otherAssets) {
      const chip = document.createElement('a');
      chip.className = 'other-asset-chip mono';
      chip.href = asset.browser_download_url;
      chip.setAttribute('download', asset.name);
      chip.textContent = `${asset.name} (${formatBytes(asset.size)}) ↓`;
      els.otherAssetsRow.appendChild(chip);
    }

    els.relNotes.innerHTML = renderMarkdownLite(release.body);
    setReleaseView('content');
  }

  async function fetchLatestRelease(repo) {
    const t = I18N[currentLang] || I18N.en;
    updateRepoLinks(repo);

    if (!repo) {
      els.heroReleaseBadgeText.textContent = 'NekoXray · Xray-core v26.10.31';
      els.heroDownloadMeta.textContent = 'GitHub Releases';
      els.releaseErrorDesc.textContent = t.needRepoMsg;
      els.repoForm.classList.remove('hidden');
      setReleaseView('error');
      return;
    }

    setReleaseView('loading');
    try {
      const resp = await fetch(`https://api.github.com/repos/${repo}/releases/latest`, {
        headers: { Accept: 'application/vnd.github+json' },
      });

      if (resp.status === 404) {
        const listResp = await fetch(`https://api.github.com/repos/${repo}/releases?per_page=1`, {
          headers: { Accept: 'application/vnd.github+json' },
        });
        if (listResp.ok) {
          const list = await listResp.json();
          if (Array.isArray(list) && list.length > 0) {
            await renderRelease(list[0]);
            return;
          }
          els.releaseErrorDesc.textContent = t.noReleaseYetMsg.replace('{repo}', repo);
          setReleaseView('error');
          return;
        }
      }

      if (!resp.ok) {
        els.releaseErrorDesc.textContent = t.apiErrorMsg
          .replace('{status}', String(resp.status))
          .replace('{repo}', repo);
        setReleaseView('error');
        return;
      }

      const data = await resp.json();
      await renderRelease(data);
    } catch (_) {
      els.releaseErrorDesc.textContent = t.apiErrorMsg
        .replace('{status}', 'network')
        .replace('{repo}', repo);
      setReleaseView('error');
    }
  }

  if (els.langSelect) {
    els.langSelect.addEventListener('change', (e) => {
      const next = e.target.value;
      if (SUPPORTED_LANGS.includes(next)) {
        currentLang = next;
        localStorage.setItem('nekoxray_site_lang', currentLang);
        applyTranslations();
        if (!cachedRelease) {
          fetchLatestRelease(currentRepo);
        }
      }
    });
  }

  els.editRepoBtn.addEventListener('click', () => {
    els.repoForm.classList.toggle('hidden');
    if (!els.repoForm.classList.contains('hidden')) {
      els.repoInput.value = currentRepo;
      els.repoInput.focus();
    }
  });

  els.repoForm.addEventListener('submit', (e) => {
    e.preventDefault();
    const val = els.repoInput.value.trim().replace(/^https?:\/\/github\.com\//i, '').replace(/\/+$/, '');
    if (!isValidRepoSlug(val)) {
      els.repoInput.focus();
      return;
    }
    currentRepo = val;
    localStorage.setItem('nekoxray_repo', currentRepo);
    els.repoForm.classList.add('hidden');
    fetchLatestRelease(currentRepo);
  });

  els.retryReleaseBtn.addEventListener('click', () => fetchLatestRelease(currentRepo));

  document.querySelectorAll('.screen-item').forEach((item) => {
    item.addEventListener('click', () => {
      const src = item.getAttribute('data-lightbox');
      if (!src) return;
      els.lightboxImg.src = src;
      els.lightbox.classList.remove('hidden');
    });
  });

  const closeLightbox = () => {
    els.lightbox.classList.add('hidden');
    els.lightboxImg.src = '';
  };

  els.lightboxClose.addEventListener('click', closeLightbox);
  els.lightbox.addEventListener('click', (e) => {
    if (e.target === els.lightbox) closeLightbox();
  });
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && !els.lightbox.classList.contains('hidden')) {
      closeLightbox();
    }
  });

  (async () => {
    applyTranslations();
    currentRepo = await resolveRepository();
    await fetchLatestRelease(currentRepo);
  })();
})();
