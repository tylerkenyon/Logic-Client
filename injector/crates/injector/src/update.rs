use semver::Version;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::BTreeMap;
use std::fs;
use std::io::{self, Read, Write};
use std::path::Path;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

const FILES: [&str; 2] = ["LogicLauncher.exe", "logic_native.dll"];

#[derive(Deserialize, Serialize)]
struct Metadata {
    schema: u32,
    repository: String,
    version: Version,
    files: BTreeMap<String, String>,
}

fn repository_valid(repo: &str) -> bool {
    let parts: Vec<_> = repo.split('/').collect();
    parts.len() == 2 && parts.iter().all(|part| !part.is_empty() && *part != "." && *part != ".."
        && part.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.')))
}

fn download(client: &reqwest::blocking::Client, url: &str, limit: u64) -> Result<Vec<u8>, String> {
    let response = client.get(url).send().map_err(|e| e.to_string())?.error_for_status().map_err(|e| e.to_string())?;
    let mut data = Vec::new();
    response.take(limit + 1).read_to_end(&mut data).map_err(|e| e.to_string())?;
    if data.len() as u64 > limit { return Err("download exceeds size limit".into()); }
    Ok(data)
}

pub fn check() -> Result<bool, String> {
    let exe = std::env::current_exe().map_err(|e| e.to_string())?;
    if !exe.file_name().is_some_and(|name| name.eq_ignore_ascii_case("LogicLauncher.exe")) {
        return Err("the launcher must be named LogicLauncher.exe to update".into());
    }
    let dir = exe.parent().ok_or("launcher directory missing")?;
    let local: Metadata = match fs::read(dir.join("meta.json")) {
        Ok(data) => serde_json::from_slice(&data).map_err(|e| format!("invalid local metadata: {e}"))?,
        Err(e) if e.kind() == io::ErrorKind::NotFound => return Ok(false),
        Err(e) => return Err(e.to_string()),
    };
    if local.schema != 1 || !repository_valid(&local.repository) { return Err("invalid local repository metadata".into()); }
    let client = reqwest::blocking::Client::builder().user_agent("Logic-Launcher")
        .timeout(Duration::from_secs(30)).build().map_err(|e| e.to_string())?;
    let base = format!("https://github.com/{}/releases", local.repository);
    let data = download(&client, &format!("{base}/latest/download/meta.json"), 64 * 1024)?;
    let remote: Metadata = serde_json::from_slice(&data).map_err(|e| format!("invalid release metadata: {e}"))?;
    if remote.schema != 1 || remote.repository != local.repository { return Err("release metadata does not match repository".into()); }
    if remote.version <= local.version { return Ok(false); }
    loop {
        print!("Logic {} is available (installed {}). Would you like to update? [y/n] ", remote.version, local.version);
        io::stdout().flush().map_err(|e| e.to_string())?;
        let mut answer = String::new();
        if io::stdin().read_line(&mut answer).map_err(|e| e.to_string())? == 0 { return Ok(false); }
        match answer.trim().to_ascii_lowercase().as_str() { "n" => return Ok(false), "y" => break, _ => {} }
    }
    let stamp = SystemTime::now().duration_since(UNIX_EPOCH).map_err(|e| e.to_string())?.as_nanos();
    let stage = dir.join(format!(".logic-update-{}-{stamp}", std::process::id()));
    fs::create_dir(&stage).map_err(|e| e.to_string())?;
    let result = (|| {
        for name in FILES {
            let expected = remote.files.get(name).ok_or_else(|| format!("missing checksum for {name}"))?;
            if expected.len() != 64 || !expected.bytes().all(|b| b.is_ascii_hexdigit()) { return Err(format!("invalid checksum for {name}")); }
            let bytes = download(&client, &format!("{base}/download/v{}/{name}", remote.version), 128 * 1024 * 1024)?;
            let hash = format!("{:x}", Sha256::digest(&bytes));
            if !hash.eq_ignore_ascii_case(expected) { return Err(format!("checksum mismatch for {name}")); }
            fs::write(stage.join(name), bytes).map_err(|e| e.to_string())?;
        }
        fs::write(stage.join("meta.json"), &data).map_err(|e| e.to_string())?;
        install(dir, &stage)
    })();
    result?;
    println!("Update installed. Restarting Logic launcher...");
    if let Err(error) = std::process::Command::new(&exe).current_dir(dir).spawn() {
        eprintln!("Updated files installed; restart the launcher manually: {error}");
    }
    Ok(true)
}

fn install(dir: &Path, stage: &Path) -> Result<(), String> {
    let mut backed_up = Vec::new();
    let mut installed = Vec::new();
    let result = (|| {
        for name in ["LogicLauncher.exe", "logic_native.dll", "meta.json"] {
            let target = dir.join(name);
            if target.exists() {
                fs::rename(&target, stage.join(format!("{name}.old"))).map_err(|e| format!("cannot replace {name}: {e}"))?;
                backed_up.push(name);
            }
            fs::rename(stage.join(name), &target).map_err(|e| format!("cannot install {name}: {e}"))?;
            installed.push(name);
        }
        Ok(())
    })();
    if result.is_err() {
        for name in installed.iter().rev() { let _ = fs::remove_file(dir.join(name)); }
        for name in backed_up.iter().rev() {
            if let Err(error) = fs::rename(stage.join(format!("{name}.old")), dir.join(name)) {
                return Err(format!("rollback failed for {name}: {error}; backup remains in {}", stage.display()));
            }
        }
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn validates_repositories() {
        assert!(repository_valid("tylerkenyon/Logic-Client"));
        for invalid in ["../repo", "owner/repo/extra", "https://github.com/a/b", "a/", "a/b?x"] { assert!(!repository_valid(invalid)); }
    }

    #[test]
    fn replaces_both_files_and_metadata() {
        let dir = std::env::temp_dir().join(format!("logic-update-test-{}", std::process::id()));
        let stage = dir.join("stage");
        fs::create_dir_all(&stage).unwrap();
        for name in ["LogicLauncher.exe", "logic_native.dll", "meta.json"] {
            fs::write(dir.join(name), b"old").unwrap();
            fs::write(stage.join(name), b"new").unwrap();
        }
        install(&dir, &stage).unwrap();
        for name in ["LogicLauncher.exe", "logic_native.dll", "meta.json"] {
            assert_eq!(fs::read(dir.join(name)).unwrap(), b"new");
            assert_eq!(fs::read(stage.join(format!("{name}.old"))).unwrap(), b"old");
        }
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn rolls_back_when_a_staged_file_is_missing() {
        let dir = std::env::temp_dir().join(format!("logic-rollback-test-{}", std::process::id()));
        let stage = dir.join("stage");
        fs::create_dir_all(&stage).unwrap();
        for name in ["LogicLauncher.exe", "logic_native.dll", "meta.json"] { fs::write(dir.join(name), b"old").unwrap(); }
        fs::write(stage.join("LogicLauncher.exe"), b"new").unwrap();
        assert!(install(&dir, &stage).is_err());
        for name in ["LogicLauncher.exe", "logic_native.dll", "meta.json"] { assert_eq!(fs::read(dir.join(name)).unwrap(), b"old"); }
        fs::remove_dir_all(dir).unwrap();
    }
}
