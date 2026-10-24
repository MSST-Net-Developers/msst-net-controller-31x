//! Update reminder.
//!
//! The Controller — not Core: this is a user-facing concern, and the
//! privileged daemon has no business fetching from the internet for it —
//! asks the release repositories for the latest Core and Controller
//! releases and compares their manifests' versions with the running Core
//! (`Health.core_version`, protocol 0.5) and this Controller.  GitHub and
//! Gitee are asked in parallel; whichever answers first with a usable
//! manifest wins.
//!
//! Updating is the installer's job, so the UI only tells the user to run
//! it.  Nothing is downloaded but the small manifests, and nothing is
//! trusted beyond their version numbers: the installer verifies signatures
//! and hashes before it installs anything.
//!
//! Android has no installer: Core is built into the app, which is updated
//! as a whole from the Controller's releases (the APKs).  There only the
//! Controller is checked ([`Scope::ControllerOnly`]) and the reminder points
//! to the Controller's release pages.
//!
//! Checks run shortly after start and then daily (hourly after a failure).
//! `MSST_UPDATE_CHECK=0` turns them off.

use std::cmp::Ordering;
use std::time::Duration;

use serde::{Deserialize, Serialize};
use tokio::task::JoinHandle;

use crate::ipc::client::IpcClient;

/// Where the installer is downloaded from (its own repository).
pub const INSTALLER_GITHUB: &str = "https://github.com/MSST-Net-Developers/msst-net-installer-31x/releases";
pub const INSTALLER_GITEE: &str = "https://gitee.com/Abjust/msst-net-installer-31x/releases";
/// Where the Android app (APKs) is downloaded from: the Controller's releases.
pub const APP_GITHUB: &str = "https://github.com/MSST-Net-Developers/msst-net-controller-31x/releases";
pub const APP_GITEE: &str = "https://gitee.com/Abjust/msst-net-controller-31x/releases";

/// What is checked, and how it is updated.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Scope {
    /// Core and the Controller, updated with the installer.
    Full,
    /// The Controller only, updated by installing a new app (Android:
    /// Core comes with the app).
    ControllerOnly,
}

impl Scope {
    /// This platform's scope.
    pub fn for_platform() -> Self {
        if cfg!(target_os = "android") { Scope::ControllerOnly } else { Scope::Full }
    }
}

const FIRST_CHECK: Duration = Duration::from_secs(10);
const CHECK_EVERY: Duration = Duration::from_secs(24 * 3600);
const RETRY_AFTER: Duration = Duration::from_secs(3600);
const MANIFEST_MAX: usize = 1024 * 1024;

/// A release repository.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Product {
    Core,
    Controller,
}

impl Product {
    /// `product` in the release manifest.
    fn manifest_name(self) -> &'static str {
        match self {
            Product::Core => "msst-net-core",
            Product::Controller => "msst-net-controller",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Source {
    GitHub,
    Gitee,
}

impl Source {
    fn label(self) -> &'static str {
        match self {
            Source::GitHub => "GitHub",
            Source::Gitee => "Gitee",
        }
    }

    /// The `releases/latest` API URL (stable releases only).  Repositories
    /// and their overrides are the installer's (`release.rs`).
    fn latest_url(self, product: Product) -> String {
        let (var, default) = match (self, product) {
            (Source::GitHub, Product::Core) => ("MSST_CORE_GITHUB_REPO", "MSST-Net-Developers/msst-net-core-31x"),
            (Source::GitHub, Product::Controller) => ("MSST_CONTROLLER_GITHUB_REPO", "MSST-Net-Developers/msst-net-controller-31x"),
            (Source::Gitee, Product::Core) => ("MSST_CORE_GITEE_REPO", "Abjust/msst-net-core-31x"),
            (Source::Gitee, Product::Controller) => ("MSST_CONTROLLER_GITEE_REPO", "Abjust/msst-net-controller-31x"),
        };
        let repo = std::env::var(var).ok().filter(|v| !v.is_empty()).unwrap_or_else(|| default.to_string());
        match self {
            Source::GitHub => format!("https://api.github.com/repos/{repo}/releases/latest"),
            Source::Gitee => format!("https://gitee.com/api/v5/repos/{repo}/releases/latest"),
        }
    }
}

/// The latest releases found by one check.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct LatestReleases {
    /// Unix seconds.
    pub checked_at: i64,
    /// `None`: this product couldn't be checked this time.
    pub core: Option<String>,
    pub controller: Option<String>,
    /// Which repository answered first, e.g. `"Gitee"`.
    pub source: String,
}

/// What the UI shows (`ControllerView.update`).
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct UpdateInfo {
    pub checked_at: i64,
    pub source: String,
    /// The running Core's version (`None`: Core not connected, or older
    /// than protocol 0.5).
    pub core_current: Option<String>,
    pub core_latest: Option<String>,
    pub core_update: bool,
    pub controller_current: String,
    pub controller_latest: Option<String>,
    pub controller_update: bool,
    /// Updated by running the installer (`false`: by installing a new app).
    pub via_installer: bool,
    /// Download pages: the installer's, or the app's.
    pub download_github: &'static str,
    pub download_gitee: &'static str,
}

impl UpdateInfo {
    pub fn available(&self) -> bool {
        self.core_update || self.controller_update
    }
}

/// Compare a check's result with what runs.  With
/// [`Scope::ControllerOnly`] Core is not compared (it comes with the app).
pub fn evaluate(latest: &LatestReleases, core_current: Option<&str>, controller_current: &str, scope: Scope) -> UpdateInfo {
    let newer = |latest: Option<&str>, current: Option<&str>| matches!((latest, current), (Some(l), Some(c)) if is_newer(l, c));
    let core_current = if scope == Scope::Full { core_current } else { None };
    let full = scope == Scope::Full;
    UpdateInfo {
        checked_at: latest.checked_at,
        source: latest.source.clone(),
        core_current: core_current.map(str::to_string),
        core_latest: latest.core.clone(),
        core_update: newer(latest.core.as_deref(), core_current),
        controller_current: controller_current.to_string(),
        controller_latest: latest.controller.clone(),
        controller_update: newer(latest.controller.as_deref(), Some(controller_current)),
        via_installer: full,
        download_github: if full { INSTALLER_GITHUB } else { APP_GITHUB },
        download_gitee: if full { INSTALLER_GITEE } else { APP_GITEE },
    }
}

// ── Versions ─────────────────────────────────────────────────────────────────

/// `major.minor.patch[-pre][+build]`, compared as SemVer.
#[derive(Debug, Clone, PartialEq, Eq)]
struct Version {
    release: (u64, u64, u64),
    pre: Vec<String>,
}

impl Version {
    fn parse(s: &str) -> Option<Self> {
        let s = s.trim().trim_start_matches('v');
        let s = s.split('+').next()?;
        let (release, pre) = match s.split_once('-') {
            Some((r, p)) => (r, Some(p)),
            None => (s, None),
        };
        let mut parts = release.split('.').map(|p| p.parse::<u64>().ok());
        let release = (parts.next()??, parts.next()??, parts.next()??);
        if parts.next().is_some() {
            return None;
        }
        let pre = match pre {
            Some(p) if p.is_empty() || p.split('.').any(|i| i.is_empty() || !i.chars().all(|c| c.is_ascii_alphanumeric())) => return None,
            Some(p) => p.split('.').map(str::to_string).collect(),
            None => Vec::new(),
        };
        Some(Version { release, pre })
    }
}

impl Ord for Version {
    fn cmp(&self, other: &Self) -> Ordering {
        self.release.cmp(&other.release).then_with(|| match (self.pre.is_empty(), other.pre.is_empty()) {
            (true, true) => Ordering::Equal,
            (true, false) => Ordering::Greater, // 3.1.10 > 3.1.10-beta
            (false, true) => Ordering::Less,
            (false, false) => {
                for (a, b) in self.pre.iter().zip(&other.pre) {
                    let o = match (a.parse::<u64>(), b.parse::<u64>()) {
                        (Ok(x), Ok(y)) => x.cmp(&y),
                        (Ok(_), Err(_)) => Ordering::Less,
                        (Err(_), Ok(_)) => Ordering::Greater,
                        (Err(_), Err(_)) => a.cmp(b),
                    };
                    if o != Ordering::Equal {
                        return o;
                    }
                }
                self.pre.len().cmp(&other.pre.len())
            }
        })
    }
}

impl PartialOrd for Version {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

/// `latest` is a newer release than `current` (both must parse).
pub fn is_newer(latest: &str, current: &str) -> bool {
    matches!((Version::parse(latest), Version::parse(current)), (Some(l), Some(c)) if l > c)
}

// ── Fetching ─────────────────────────────────────────────────────────────────

#[derive(Deserialize)]
struct ApiRelease {
    #[serde(default)]
    assets: Vec<ApiAsset>,
}

#[derive(Deserialize)]
struct ApiAsset {
    name: String,
    browser_download_url: String,
}

#[derive(Deserialize)]
struct ManifestHead {
    product: String,
    version: String,
}

/// The release version in a manifest of `product` (the rest of the
/// manifest is the installer's business).
fn manifest_version(bytes: &[u8], product: Product) -> Result<String, String> {
    let m: ManifestHead = serde_json::from_slice(bytes).map_err(|e| format!("清单无法解析：{e}"))?;
    if m.product != product.manifest_name() {
        return Err(format!("清单属于 {}，不是 {}", m.product, product.manifest_name()));
    }
    Version::parse(&m.version).ok_or_else(|| format!("清单中的版本号无效：{}", m.version))?;
    Ok(m.version.trim().trim_start_matches('v').to_string())
}

pub fn http_client() -> reqwest::Client {
    reqwest::Client::builder()
        .user_agent(concat!("msst-net-controller/", env!("CARGO_PKG_VERSION")))
        .connect_timeout(Duration::from_secs(10))
        .timeout(Duration::from_secs(20))
        .build()
        .unwrap_or_default()
}

async fn get(http: &reqwest::Client, url: &str, github: bool) -> Result<reqwest::Response, String> {
    let mut req = http.get(url);
    if github {
        req = req.header("Accept", "application/vnd.github+json");
    }
    let resp = req.send().await.map_err(|e| e.to_string())?;
    if !resp.status().is_success() {
        return Err(format!("HTTP {}", resp.status()));
    }
    Ok(resp)
}

/// The latest version of `product` on `source`, from its release manifest.
async fn fetch(http: &reqwest::Client, source: Source, product: Product) -> Result<String, String> {
    let github = source == Source::GitHub;
    let rel: ApiRelease = get(http, &source.latest_url(product), github).await?.json().await.map_err(|e| e.to_string())?;
    let url = rel
        .assets
        .iter()
        .find(|a| a.name == "manifest.json")
        .map(|a| a.browser_download_url.clone())
        .ok_or("最新发布中没有 manifest.json")?;
    let mut resp = get(http, &url, false).await?;
    let mut bytes = Vec::new();
    while let Some(chunk) = resp.chunk().await.map_err(|e| e.to_string())? {
        bytes.extend_from_slice(&chunk);
        if bytes.len() > MANIFEST_MAX {
            return Err("清单过大".into());
        }
    }
    manifest_version(&bytes, product)
}

/// The first of GitHub and Gitee to answer with a usable manifest.
async fn fetch_fastest(http: &reqwest::Client, product: Product) -> Result<(String, Source), String> {
    let github = fetch(http, Source::GitHub, product);
    let gitee = fetch(http, Source::Gitee, product);
    tokio::pin!(github, gitee);
    let (first, rest) = tokio::select! {
        r = &mut github => match r {
            Ok(v) => return Ok((v, Source::GitHub)),
            Err(e) => (format!("GitHub：{e}"), Source::Gitee),
        },
        r = &mut gitee => match r {
            Ok(v) => return Ok((v, Source::Gitee)),
            Err(e) => (format!("Gitee：{e}"), Source::GitHub),
        },
    };
    let other = if rest == Source::GitHub { github.await } else { gitee.await };
    other.map(|v| (v, rest)).map_err(|e| format!("{first}；{}：{e}", rest.label()))
}

/// Ask for the latest releases in `scope`.  Fails only if none could be
/// found.
pub async fn check(http: &reqwest::Client, scope: Scope) -> Result<LatestReleases, String> {
    let core = async {
        match scope {
            Scope::Full => fetch_fastest(http, Product::Core).await,
            Scope::ControllerOnly => Err("未检查".to_string()),
        }
    };
    let (core, controller) = tokio::join!(core, fetch_fastest(http, Product::Controller));
    match (&core, &controller) {
        (_, Err(b)) if scope == Scope::ControllerOnly => return Err(format!("控制器：{b}")),
        (Err(a), Err(b)) => return Err(format!("Core：{a}；控制器：{b}")),
        _ => {}
    }
    let source = [&core, &controller].iter().find_map(|r| r.as_ref().ok().map(|(_, s)| s.label())).unwrap_or_default();
    Ok(LatestReleases {
        checked_at: std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0),
        core: core.ok().map(|(v, _)| v),
        controller: controller.ok().map(|(v, _)| v),
        source: source.to_string(),
    })
}

/// Whether this Controller checks for updates.
pub fn enabled() -> bool {
    !matches!(std::env::var("MSST_UPDATE_CHECK").as_deref().map(str::trim), Ok("0" | "off" | "false" | "no"))
}

/// Check in the background for as long as `client` runs, publishing each
/// result in its view.  `None` when checks are turned off.
pub fn spawn(client: &IpcClient) -> Option<JoinHandle<()>> {
    if !enabled() {
        tracing::info!("update check disabled");
        return None;
    }
    let client = client.clone();
    let scope = Scope::for_platform();
    Some(tokio::spawn(async move {
        let http = http_client();
        tokio::time::sleep(FIRST_CHECK).await;
        loop {
            let next = match check(&http, scope).await {
                Ok(latest) => {
                    tracing::info!(core = ?latest.core, controller = ?latest.controller, source = %latest.source, "update check");
                    client.set_latest_releases(latest).await;
                    CHECK_EVERY
                }
                Err(e) => {
                    tracing::warn!("update check failed: {e}");
                    RETRY_AFTER
                }
            };
            tokio::time::sleep(next).await;
        }
    }))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn versions_compare_as_semver() {
        assert!(is_newer("3.1.10", "3.1.9"), "numeric, not lexical");
        assert!(is_newer("v3.2.0", "3.1.99"));
        assert!(is_newer("3.1.10", "3.1.10-beta.2"), "a release beats its pre-releases");
        assert!(is_newer("3.1.10-beta.10", "3.1.10-beta.2"));
        assert!(is_newer("3.1.10-rc.1", "3.1.10-beta.9"));
        assert!(!is_newer("3.1.9", "3.1.9"));
        assert!(!is_newer("3.1.9", "3.1.10"));
        assert!(!is_newer("3.1.9+build.7", "3.1.9"), "build metadata is ignored");
        for bad in ["", "3.1", "3.1.x", "3.1.9.1", "3.1.9-", "3.1.9-a..b", "<script>"] {
            assert!(!is_newer(bad, "0.0.1"), "{bad}");
            assert!(!is_newer("9.9.9", bad), "{bad}");
        }
    }

    #[test]
    fn manifests_are_checked_before_their_version_is_used() {
        let m = |p: &str, v: &str| serde_json::json!({ "schema": 1, "product": p, "version": v, "artifacts": [] }).to_string();
        assert_eq!(manifest_version(m("msst-net-core", "3.1.10").as_bytes(), Product::Core).unwrap(), "3.1.10");
        assert_eq!(manifest_version(m("msst-net-controller", "v3.1.10").as_bytes(), Product::Controller).unwrap(), "3.1.10");
        assert!(manifest_version(m("msst-net-controller", "3.1.10").as_bytes(), Product::Core).is_err());
        assert!(manifest_version(m("msst-net-core", "latest").as_bytes(), Product::Core).is_err());
        assert!(manifest_version(b"not json", Product::Core).is_err());
    }

    #[test]
    fn evaluation() {
        let latest = LatestReleases { checked_at: 1, core: Some("3.1.10".into()), controller: Some("3.1.9".into()), source: "Gitee".into() };
        let u = evaluate(&latest, Some("3.1.9"), "3.1.9", Scope::Full);
        assert!(u.core_update && !u.controller_update && u.available());
        assert!(u.via_installer);
        assert_eq!(u.download_gitee, INSTALLER_GITEE);
        let u = evaluate(&latest, None, "3.1.8", Scope::Full);
        assert!(!u.core_update, "unknown Core version: no claim");
        assert!(u.controller_update);
        let none = LatestReleases { core: None, controller: None, ..latest.clone() };
        assert!(!evaluate(&none, Some("3.1.0"), "3.1.0", Scope::Full).available());

        // Android: the app is updated as a whole; its Core is not compared.
        let u = evaluate(&latest, Some("3.1.9"), "3.1.9", Scope::ControllerOnly);
        assert!(!u.available());
        assert_eq!(u.core_current, None);
        let u = evaluate(&LatestReleases { controller: Some("3.1.10".into()), ..latest }, Some("3.1.9"), "3.1.9", Scope::ControllerOnly);
        assert!(u.controller_update && !u.core_update && !u.via_installer);
        assert_eq!((u.download_github, u.download_gitee), (APP_GITHUB, APP_GITEE));
    }
}
