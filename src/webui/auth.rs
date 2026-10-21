//! WebUI password and sessions (3.0.x model): an argon2 hash in `passwd`
//! (mode 0600), random 48-character session tokens valid for 24 hours.

use std::collections::HashMap;
use std::path::Path;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use argon2::password_hash::{PasswordHash, PasswordHasher, PasswordVerifier, SaltString, rand_core::OsRng};
use argon2::Argon2;
use rand::Rng;

pub const SESSION_TTL: Duration = Duration::from_secs(86400);
pub const MIN_PASSWORD_LEN: usize = 6;

pub fn hash_password(password: &str) -> Result<String, String> {
    let salt = SaltString::generate(&mut OsRng);
    Argon2::default()
        .hash_password(password.as_bytes(), &salt)
        .map(|h| h.to_string())
        .map_err(|e| format!("hash error: {e}"))
}

pub fn verify_password(password: &str, hash: &str) -> bool {
    PasswordHash::new(hash).is_ok_and(|parsed| Argon2::default().verify_password(password.as_bytes(), &parsed).is_ok())
}

pub fn write_passwd(path: &Path, hash: &str) -> Result<(), String> {
    if let Some(p) = path.parent() {
        std::fs::create_dir_all(p).map_err(|e| e.to_string())?;
    }
    std::fs::write(path, hash).map_err(|e| format!("write passwd: {e}"))?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600)).map_err(|e| e.to_string())?;
    }
    Ok(())
}

pub fn read_passwd(path: &Path) -> Result<String, String> {
    std::fs::read_to_string(path).map(|s| s.trim().to_string()).map_err(|e| format!("read passwd: {e}"))
}

/// In-memory session store (sessions end when the WebUI restarts).
#[derive(Default)]
pub struct SessionStore {
    sessions: Mutex<HashMap<String, Instant>>,
}

impl SessionStore {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn create(&self) -> String {
        let token: String = rand::thread_rng().sample_iter(&rand::distributions::Alphanumeric).take(48).map(char::from).collect();
        let mut s = self.sessions.lock().unwrap();
        let now = Instant::now();
        s.retain(|_, exp| *exp > now);
        s.insert(token.clone(), now + SESSION_TTL);
        token
    }

    pub fn validate(&self, token: &str) -> bool {
        let mut s = self.sessions.lock().unwrap();
        match s.get(token) {
            Some(&exp) if Instant::now() < exp => true,
            Some(_) => {
                s.remove(token);
                false
            }
            None => false,
        }
    }

    pub fn remove(&self, token: &str) {
        self.sessions.lock().unwrap().remove(token);
    }
}

/// Non-interactive password source for container deployments:
/// the file at `PASSWORD_FILE` (default `/run/secrets/admin_password`),
/// then `ADMIN_PASSWORD` (empty counts as unset, so a compose file can pass
/// it through unconditionally).
pub fn env_password() -> Result<Option<String>, String> {
    let secret_path = std::env::var("PASSWORD_FILE").unwrap_or_else(|_| "/run/secrets/admin_password".into());
    if Path::new(&secret_path).exists() {
        let pw = std::fs::read_to_string(&secret_path).map_err(|e| format!("读取密码文件 {secret_path} 失败: {e}"))?;
        let pw = pw.trim().to_string();
        if pw.len() < MIN_PASSWORD_LEN {
            return Err("密码文件中的密码长度不能少于 6 位".into());
        }
        tracing::info!("从密码文件 {secret_path} 读取管理密码");
        return Ok(Some(pw));
    }
    if let Some(pw) = std::env::var("ADMIN_PASSWORD").ok().filter(|p| !p.is_empty()) {
        if pw.len() < MIN_PASSWORD_LEN {
            return Err("ADMIN_PASSWORD 密码长度不能少于 6 位".into());
        }
        tracing::info!("从环境变量 ADMIN_PASSWORD 读取管理密码");
        return Ok(Some(pw));
    }
    Ok(None)
}

/// First-run interactive password setup on the terminal.
pub fn interactive_setup(passwd_path: &Path) -> Result<(), String> {
    println!("=== msst-net-webui 首次运行 ===");
    println!("尚未设置访问密码，请现在设置。");
    loop {
        let pw = rpassword::prompt_password("访问密码: ").map_err(|e| e.to_string())?;
        if pw.len() < MIN_PASSWORD_LEN {
            eprintln!("密码长度不能少于 6 位，请重新输入。");
            continue;
        }
        let pw2 = rpassword::prompt_password("确认密码: ").map_err(|e| e.to_string())?;
        if pw != pw2 {
            eprintln!("两次输入的密码不一致，请重试。");
            continue;
        }
        write_passwd(passwd_path, &hash_password(&pw)?)?;
        println!("密码设置成功，正在启动服务…");
        return Ok(());
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hash_and_verify() {
        let h = hash_password("secret123").unwrap();
        assert!(verify_password("secret123", &h));
        assert!(!verify_password("wrong", &h));
        assert!(!verify_password("secret123", "not-a-hash"));
    }

    #[test]
    fn sessions() {
        let s = SessionStore::new();
        let t = s.create();
        assert_eq!(t.len(), 48);
        assert!(s.validate(&t));
        s.remove(&t);
        assert!(!s.validate(&t));
    }
}
