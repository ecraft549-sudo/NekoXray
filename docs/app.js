(() => {
  const I18N = {
    ru: {
      navFeatures: 'Возможности',
      navScreenshots: 'Скриншоты',
      navDownload: 'Скачать',
      navInstall: 'Установка',
      headerDownloadCta: 'Скачать APK',
      heroBadgeLoading: 'Загрузка последнего релиза с GitHub…',
      heroHeadline: 'Свободный туннель на Xray-core нового поколения',
      heroSubtitle:
        'Нативный Android-клиент на Kotlin и Jetpack Compose: VLESS REALITY, XHTTP, постквантовый ML-KEM-768, встроенный SSH-мост, умный роутинг, виджеты 1×1 / 2×1 / 4×2 и шторка.',
      heroDownloadDefault: 'Скачать последний APK',
      heroDownloadMetaLoading: 'Автоопределение архитектуры…',
      heroAllBuilds: 'Все архитектуры и SHA-256',
      floatingCardTopSub: 'Постквантовый pqv & XHTTP',
      floatingCardBottomTitle: 'Виджеты 1×1, 2×1, 4×2',
      floatingCardBottomSub: 'И кнопка в трее / шторке',
      downloadEyebrow: 'ПРЯМАЯ ЗАГРУЗКА С GITHUB RELEASES',
      downloadTitle: 'Скачать последний релиз NekoXray',
      changeRepoBtn: 'Указать репозиторий',
      repoInputLabel: 'Репозиторий GitHub (формат: username/NekoXray):',
      repoApplyBtn: 'Загрузить релиз',
      releaseFetching: 'Получаем данные последнего релиза через GitHub API…',
      releaseErrorTitle: 'Релиз пока не найден',
      retryBtn: 'Повторить',
      openGithubReleases: 'Открыть GitHub Releases',
      publishedLabel: 'Опубликован:',
      totalDownloadsLabel: 'Скачиваний:',
      viewOnGithub: 'Открыть на GitHub ↗',
      changelogSummary: 'Что нового в этом релизе',
      featuresEyebrow: 'ВОЗМОЖНОСТИ',
      featuresTitle: 'Максимум контроля без лишней сложности',
      featuresSubtitle: 'Всё нужное для стабильного обхода DPI, быстрой смены узлов и гибкой маршрутизации.',
      feat1Title: 'VLESS REALITY, XHTTP & ML-KEM-768',
      feat1Desc:
        'Полная поддержка xtls-rprx-vision, XHTTP (SplitHTTP), постквантового шифрования ML-KEM-768 с pqv, а также VMess, Trojan, Shadowsocks 2022, WireGuard и Custom JSON.',
      feat2Title: 'Встроенный SSH-туннель',
      feat2Desc:
        'Собственный SOCKS5 TCP и DNS-over-TCP мост на базе mwiede/jsch: вход по паролю, приватному ключу PEM/OpenSSH и пиннинг публичного ключа сервера.',
      feat3Title: 'Роутинг и Per-App Proxy',
      feat3Desc:
        'Правила по доменам (geosite:, domain:), IP/CIDR, портам и приложениям. Импорт и экспорт списков приложений в формате NekoBox (false/true).',
      feat4Title: 'Виджеты 1×1, 2×1, 4×2 и шторка',
      feat4Desc:
        'Кнопка 1×1 в один блок, компактный переключатель 2×1, карточка 4×2 со скоростью, постоянный виджет в трее уведомлений и плитка Quick Settings.',
      feat5Title: 'Крупные QR-коды и сканер',
      feat5Desc:
        'Векторный генератор QR до 40-й версии для длинных ключей (2200+ символов), живой сканер CameraX и чтение скриншотов через Photo Picker без лишних прав.',
      feat6Title: 'Генератор ключей и SNI Probe',
      feat6Desc:
        'Мгновенная генерация пары ключей X25519 + UUID + Short ID с готовым серверным конфигом, проверка доменов на TLSv1.3 и мгновенный язык RU / EN.',
      screensEyebrow: 'ИНТЕРФЕЙС',
      screensTitle: 'Тёмная эстетика Wired & Material 3',
      screensSubtitle: 'Нажмите на экран, чтобы рассмотреть детали интерфейса.',
      scr1: 'Профили и пинг узлов',
      scr2: 'Роутинг и Per-App Proxy',
      scr3: 'Генератор REALITY и SNI',
      scr4: 'Настройки и выбор языка',
      scr5: 'QR-код для длинных URI',
      installEyebrow: 'БЫСТРЫЙ СТАРТ',
      installTitle: 'Установка за 3 шага',
      step1Title: 'Скачайте APK',
      step1Desc:
        'Для большинства современных смартфонов подходит сборка arm64-v8a — она меньше по размеру и быстрее. Если не уверены, выберите universal.',
      step2Title: 'Установите на Android',
      step2Desc:
        'Откройте загруженный .apk файл и разрешите установку из браузера. По желанию сверьте контрольную сумму из SHA256SUMS.txt.',
      step3Title: 'Импортируйте узел',
      step3Desc:
        'Вставьте ссылку vless:// / vmess:// / ssh:// из буфера обмена или отсканируйте QR-код, затем добавьте виджет 1×1 или плитку в шторку.',
      footerTagline: 'Открытый Android-клиент Xray-core на Kotlin и Jetpack Compose.',
      footerReleases: 'Релизы',
      footerIssues: 'Сообщить об ошибке',

      recommendedBadge: 'Рекомендуется',
      downloadBtn: 'Скачать',
      downloadsShort: 'скач.',
      noNotes: 'Список изменений для этого релиза не указан.',
      needRepoMsg:
        'Сайт открыт вне GitHub Pages. Укажите репозиторий в формате owner/repo выше (или через ?repo=owner/repo), чтобы подгрузить релиз.',
      noReleaseYetMsg:
        'В репозитории {repo} пока нет опубликованных GitHub Releases. Создайте тег v1.0.0 или запустите workflow Release Multi-ABI APKs.',
      apiErrorMsg: 'Ошибка обращения к GitHub API ({status}) для {repo}. Проверьте имя репозитория или повторите позже.',
      heroBadgeReady: 'Последний релиз {tag} · Xray-core v26.10.31',
      heroDownloadReady: 'Скачать {tag} ({abi})',
      abiDescriptions: {
        'arm64-v8a': 'Современные 64-битные ARM смартфоны (2017+)',
        'armeabi-v7a': 'Старые 32-битные ARM устройства',
        universal: 'Универсальный APK для любого устройства',
        x86_64: 'Эмуляторы Android и планшеты x86_64',
        x86: '32-битные эмуляторы x86',
      },
    },
    en: {
      navFeatures: 'Features',
      navScreenshots: 'Screenshots',
      navDownload: 'Download',
      navInstall: 'Install',
      headerDownloadCta: 'Download APK',
      heroBadgeLoading: 'Fetching latest GitHub Release…',
      heroHeadline: 'Next-Gen Xray-core Tunnel for Android',
      heroSubtitle:
        'Native Kotlin & Jetpack Compose client: VLESS REALITY, XHTTP, post-quantum ML-KEM-768, embedded SSH bridge, smart routing, 1×1 / 2×1 / 4×2 home widgets & quick tray.',
      heroDownloadDefault: 'Download Latest APK',
      heroDownloadMetaLoading: 'Detecting device architecture…',
      heroAllBuilds: 'All Architectures & SHA-256',
      floatingCardTopSub: 'Post-quantum pqv & XHTTP',
      floatingCardBottomTitle: '1×1, 2×1 & 4×2 Widgets',
      floatingCardBottomSub: 'Plus Notification Tray & QS Tile',
      downloadEyebrow: 'DIRECT DOWNLOAD FROM GITHUB RELEASES',
      downloadTitle: 'Download Latest NekoXray Release',
      changeRepoBtn: 'Change Repo',
      repoInputLabel: 'GitHub Repository (format: username/NekoXray):',
      repoApplyBtn: 'Load Release',
      releaseFetching: 'Loading latest release metadata from GitHub API…',
      releaseErrorTitle: 'Could Not Load Release',
      retryBtn: 'Retry',
      openGithubReleases: 'Open GitHub Releases',
      publishedLabel: 'Published:',
      totalDownloadsLabel: 'Downloads:',
      viewOnGithub: 'View on GitHub ↗',
      changelogSummary: 'Release Notes',
      featuresEyebrow: 'CAPABILITIES',
      featuresTitle: 'Full Protocol Control, Zero Clutter',
      featuresSubtitle: 'Built for resilient censorship circumvention, instant node switching, and fine-grained routing.',
      feat1Title: 'VLESS REALITY, XHTTP & ML-KEM-768',
      feat1Desc:
        'Full support for xtls-rprx-vision, XHTTP (SplitHTTP), post-quantum ML-KEM-768 with pqv, plus VMess, Trojan, Shadowsocks 2022, WireGuard, and Custom JSON.',
      feat2Title: 'Embedded SSH Tunneling',
      feat2Desc:
        'Built-in SOCKS5 TCP & DNS-over-TCP relay powered by mwiede/jsch with password auth, PEM/OpenSSH private keys, and server public key pinning.',
      feat3Title: 'Smart Routing & Per-App Proxy',
      feat3Desc:
        'Domain (geosite:, domain:), IP/CIDR, port, and protocol rules, plus NekoBox-compatible per-app list import/export (false/true format).',
      feat4Title: '1×1, 2×1, 4×2 Widgets & Tray',
      feat4Desc:
        'Single-block 1×1 tile, 2×1 switch, full 4×2 telemetry card, optional persistent Notification Tray widget, and Android Quick Settings tile.',
      feat5Title: 'High-Density QR & Scanner',
      feat5Desc:
        'Vector QR generator up to Version 40 for 2200+ char URIs, live CameraX scanner with torch, and zero-permission Android Photo Picker scanning.',
      feat6Title: 'REALITY Keygen & SNI Probe',
      feat6Desc:
        'Instant X25519 + UUID + Short ID generator with ready server config.json, TLSv1.3 SNI probe, and instant RU / EN language switching.',
      screensEyebrow: 'INTERFACE',
      screensTitle: 'Wired Cyber Dark & Material 3',
      screensSubtitle: 'Click any screenshot to inspect the UI in full detail.',
      scr1: 'Profiles & Latency',
      scr2: 'Routing & Per-App Proxy',
      scr3: 'REALITY Keygen & SNI',
      scr4: 'Settings & Language',
      scr5: 'Vector QR Sharing',
      installEyebrow: 'QUICK START',
      installTitle: 'Install in 3 Simple Steps',
      step1Title: 'Download APK',
      step1Desc:
        'Most modern Android phones use arm64-v8a for smaller size and top performance. Pick universal if you are unsure.',
      step2Title: 'Install on Android',
      step2Desc:
        'Tap the downloaded .apk file and allow browser installs if prompted. Optionally verify checksums using SHA256SUMS.txt.',
      step3Title: 'Import Your Node',
      step3Desc:
        'Paste a vless:// / vmess:// / ssh:// URI from clipboard or scan a QR code, then pin the 1×1 widget or Quick Settings tile.',
      footerTagline: 'Open-source Xray-core Android client built with Kotlin & Jetpack Compose.',
      footerReleases: 'Releases',
      footerIssues: 'Report Issue',

      recommendedBadge: 'Recommended',
      downloadBtn: 'Download',
      downloadsShort: 'downloads',
      noNotes: 'No release notes provided for this tag.',
      needRepoMsg:
        'Page opened outside GitHub Pages. Enter a GitHub repo (owner/repo) above or pass ?repo=owner/repo to fetch releases.',
      noReleaseYetMsg:
        'Repository {repo} has no published GitHub Releases yet. Push tag v1.0.0 or run the Release Multi-ABI APKs workflow.',
      apiErrorMsg: 'GitHub API error ({status}) while loading {repo}. Check repository name or retry shortly.',
      heroBadgeReady: 'Latest Release {tag} · Xray-core v26.10.31',
      heroDownloadReady: 'Download {tag} ({abi})',
      abiDescriptions: {
        'arm64-v8a': 'Modern 64-bit ARM phones (2017+)',
        'armeabi-v7a': 'Older 32-bit ARM devices',
        universal: 'Works on all Android CPU architectures',
        x86_64: 'Android emulators & x86_64 tablets',
        x86: '32-bit x86 emulators',
      },
    },
  };

  let currentLang = detectInitialLang();
  let currentRepo = '';
  let cachedRelease = null;

  const els = {
    langToggle: document.getElementById('langToggle'),
    langLabel: document.getElementById('langLabel'),
    headerGithubLink: document.getElementById('headerGithubLink'),
    footerRepoLink: document.getElementById('footerRepoLink'),
    footerReleasesLink: document.getElementById('footerReleasesLink'),
    footerIssuesLink: document.getElementById('footerIssuesLink'),
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
    if (saved === 'ru' || saved === 'en') return saved;
    const nav = (navigator.language || 'ru').toLowerCase();
    return nav.startsWith('ru') ? 'ru' : 'en';
  }

  function applyTranslations() {
    const t = I18N[currentLang];
    document.documentElement.lang = currentLang;
    els.langLabel.textContent = currentLang.toUpperCase();
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

    // 1. Check repo-config.json generated by GitHub Actions pages.yml
    try {
      const cfgResp = await fetch('repo-config.json', { cache: 'no-store' });
      if (cfgResp.ok) {
        const cfg = await cfgResp.json();
        if (isValidRepoSlug(cfg.repo)) return cfg.repo.trim();
      }
    } catch (_) {
      // Not deployed via pages.yml; continue auto-detection
    }

    // 2. Check <meta name="github-repo">
    const metaRepo = document.querySelector('meta[name="github-repo"]')?.content;
    if (isValidRepoSlug(metaRepo)) return metaRepo.trim();

    // 3. Auto-detect from GitHub Pages domain: <owner>.github.io/<repo>/
    const host = window.location.hostname.toLowerCase();
    if (host.endsWith('.github.io')) {
      const owner = host.slice(0, -'.github.io'.length);
      const parts = window.location.pathname.split('/').filter(Boolean);
      const repoName = parts.length > 0 ? parts[0] : `${owner}.github.io`;
      const candidate = `${owner}/${repoName}`;
      if (isValidRepoSlug(candidate)) return candidate;
    }

    // 4. Local saved override
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
    if (!md || !md.trim()) {
      return `<p>${escapeHtml(I18N[currentLang].noNotes)}</p>`;
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
    const t = I18N[currentLang];
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

    // Update Hero live badge and CTA button
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

    // Update Release card header
    els.relTagBadge.textContent = tag;
    els.relPrereleaseBadge.classList.toggle('hidden', !release.prerelease);
    els.relTitle.textContent = release.name || `NekoXray ${tag}`;

    const pubDate = release.published_at ? new Date(release.published_at) : null;
    els.relDate.textContent = pubDate
      ? pubDate.toLocaleDateString(currentLang === 'ru' ? 'ru-RU' : 'en-US', {
          year: 'numeric',
          month: 'short',
          day: 'numeric',
        })
      : '—';

    const totalDl = assets.reduce((sum, a) => sum + (a.download_count || 0), 0);
    els.relDownloads.textContent = totalDl.toLocaleString(currentLang === 'ru' ? 'ru-RU' : 'en-US');
    els.relGithubUrl.href = release.html_url || `https://github.com/${currentRepo}/releases`;

    // Render APK grid
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

        const desc = t.abiDescriptions[item.abi] || item.asset.name;
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

    // Render .aab / SHA256SUMS.txt / other assets
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
    const t = I18N[currentLang];
    updateRepoLinks(repo);

    if (!repo) {
      els.heroReleaseBadgeText.textContent = `NekoXray · Xray-core v26.10.31`;
      els.heroDownloadMeta.textContent = 'GitHub Releases';
      els.releaseErrorDesc.textContent = t.needRepoMsg;
      els.repoForm.classList.remove('hidden');
      setReleaseView('error');
      return;
    }

    setReleaseView('loading');
    try {
      let resp = await fetch(`https://api.github.com/repos/${repo}/releases/latest`, {
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

  // Event Listeners
  els.langToggle.addEventListener('click', () => {
    currentLang = currentLang === 'ru' ? 'en' : 'ru';
    localStorage.setItem('nekoxray_site_lang', currentLang);
    applyTranslations();
    if (!cachedRelease) {
      fetchLatestRelease(currentRepo);
    }
  });

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

  // Lightbox
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

  // Init
  (async () => {
    applyTranslations();
    currentRepo = await resolveRepository();
    await fetchLatestRelease(currentRepo);
  })();
})();
