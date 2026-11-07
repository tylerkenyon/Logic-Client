use std::io;
use std::path::PathBuf;

fn env_path(name: &str) -> Option<PathBuf> {
    std::env::var_os(name)
        .filter(|path| !path.is_empty())
        .map(PathBuf::from)
}

pub fn root() -> PathBuf {
    #[cfg(windows)]
    let base = env_path("APPDATA")
        .or_else(|| env_path("USERPROFILE").map(|home| home.join("AppData/Roaming")));
    #[cfg(target_os = "macos")]
    let base = env_path("HOME").map(|home| home.join("Library/Application Support"));
    #[cfg(all(unix, not(target_os = "macos")))]
    let base = env_path("XDG_DATA_HOME")
        .filter(|path| path.is_absolute())
        .or_else(|| env_path("HOME").map(|home| home.join(".local/share")));
    base.unwrap_or_else(std::env::temp_dir)
        .join(".logic")
}

pub fn initialize() -> io::Result<PathBuf> {
    let root = root();
    for name in ["configs", "logs", "runtime"] {
        std::fs::create_dir_all(root.join(name))?;
    }
    Ok(root)
}

pub fn native_log(pid: u32) -> PathBuf {
    root()
        .join("logs")
        .join(format!("logic-native-{pid}.log"))
}
