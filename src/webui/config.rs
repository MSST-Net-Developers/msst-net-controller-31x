//! WebUI configuration (`config.toml`), compatible with 3.0.x files.

use std::path::{Path, PathBuf};

use ipnetwork::IpNetwork;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Config {
    pub port: u16,
    pub allowed_cidrs: Vec<String>,
    /// 3.0.x HTTP endpoint of Core; unused since 3.1 (IPC), preserved so a
    /// 3.0.x WebUI can still read the file after a downgrade.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub core_url: Option<String>,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            port: 18080,
            allowed_cidrs: [
                "127.0.0.0/8",
                "::1/128",
                "10.0.0.0/8",
                "172.16.0.0/12",
                "192.168.0.0/16",
                "fc00::/7",
            ]
            .map(String::from)
            .to_vec(),
            core_url: None,
        }
    }
}

impl Config {
    pub fn load(path: &Path) -> Result<Self, String> {
        let text = std::fs::read_to_string(path).map_err(|e| format!("failed to read {}: {e}", path.display()))?;
        toml::from_str(&text).map_err(|e| format!("failed to parse {}: {e}", path.display()))
    }

    /// Load, or write and return the defaults when the file does not exist.
    /// A file that exists but cannot be parsed is an error: silently falling
    /// back to defaults would widen the IP allowlist.
    pub fn load_or_create(path: &Path) -> Result<Self, String> {
        if path.exists() {
            return Self::load(path);
        }
        let cfg = Self::default();
        if let Err(e) = cfg.save(path) {
            tracing::warn!("could not write default config: {e}");
        }
        Ok(cfg)
    }

    pub fn save(&self, path: &Path) -> Result<(), String> {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent).map_err(|e| e.to_string())?;
        }
        let text = toml::to_string_pretty(self).map_err(|e| e.to_string())?;
        std::fs::write(path, text).map_err(|e| e.to_string())
    }

    pub fn parsed_cidrs(&self) -> Vec<IpNetwork> {
        self.allowed_cidrs.iter().filter_map(|s| s.parse().ok()).collect()
    }
}

/// `~/.config/msst-net-webui` (`%APPDATA%\msst-net-webui` on Windows) —
/// the 3.0.x location, so existing passwords and configs are reused.
pub fn config_dir() -> PathBuf {
    #[cfg(windows)]
    let base = std::env::var("APPDATA").map(PathBuf::from).unwrap_or_else(|_| PathBuf::from("."));
    #[cfg(not(windows))]
    let base = std::env::var("HOME").map(|h| PathBuf::from(h).join(".config")).unwrap_or_else(|_| PathBuf::from("."));
    base.join("msst-net-webui")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_3_0_config() {
        let cfg: Config = toml::from_str(
            "port = 18081\ncore_url = \"http://127.0.0.1:21030\"\nallowed_cidrs = [\"127.0.0.0/8\"]\n",
        )
        .unwrap();
        assert_eq!(cfg.port, 18081);
        assert_eq!(cfg.parsed_cidrs().len(), 1);
        assert!(toml::to_string(&cfg).unwrap().contains("core_url"));
    }
}
