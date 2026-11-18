//! Headless-only command builder for an operator-pinned browser. Never attaches to a user's profile.
//! This is a foundation for an owned CDP adapter; it does not claim web reads or effects occurred.
use crate::{error, AppServerConfig, Result};
use std::path::PathBuf;
use std::process::Stdio;
use std::sync::atomic::{AtomicU64, Ordering};
use tokio::process::Command;
static PROFILE_SEQUENCE: AtomicU64 = AtomicU64::new(1);

pub struct HeadlessBrowser {
    executable: PathBuf,
    profile: PathBuf,
    args: Vec<String>,
}

impl HeadlessBrowser {
    /// All paths/pins come from trusted local configuration. There is no visible mode parameter.
    pub fn prepare(
        executable: PathBuf,
        sha256: String,
        runtime_directory: PathBuf,
    ) -> Result<Self> {
        AppServerConfig {
            executable: executable.clone(),
            executable_sha256: sha256,
            runtime_directory: runtime_directory.clone(),
        }
        .validate()?;
        let profile = runtime_directory.join(format!(
            "ziphap-headless-profile-{}-{}",
            std::process::id(),
            PROFILE_SEQUENCE.fetch_add(1, Ordering::Relaxed)
        ));
        std::fs::create_dir(&profile).map_err(|_| error("BROWSER_PROFILE"))?;
        let profile = profile
            .canonicalize()
            .map_err(|_| error("BROWSER_PROFILE"))?;
        let args = vec![
            "--headless=new".into(),
            "--remote-debugging-pipe".into(),
            "--no-first-run".into(),
            "--no-default-browser-check".into(),
            "--disable-sync".into(),
            "--disable-extensions".into(),
            "--incognito".into(),
            format!("--user-data-dir={}", profile.display()),
            "about:blank".into(),
        ];
        Ok(Self {
            executable,
            profile,
            args,
        })
    }
    pub fn arguments(&self) -> &[String] {
        &self.args
    }
    pub fn profile(&self) -> &std::path::Path {
        &self.profile
    }
    /// The caller owns the isolated child and its CDP pipes; this never launches a desktop shell.
    pub fn command(&self) -> Command {
        let mut command = Command::new(&self.executable);
        command
            .args(&self.args)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .kill_on_drop(true);
        #[cfg(windows)]
        command.creation_flags(0x08000000);
        command
    }
}

impl Drop for HeadlessBrowser {
    fn drop(&mut self) {
        // Empty unused profiles can be removed. A browser-populated profile requires the
        // caller's owned process-cleanup verification; never recursively delete here.
        let _ = std::fs::remove_dir(&self.profile);
    }
}
