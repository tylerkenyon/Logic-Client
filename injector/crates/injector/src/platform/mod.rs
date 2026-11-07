 
 

 
#[derive(PartialEq)]
pub struct JavaProcess {
    pub pid: u32,
    pub title: String,
    pub exe: String,
}

pub enum Key {
    Up,
    Down,
    Enter,
    Escape,
}

#[cfg(windows)]
mod win;
#[cfg(windows)]
pub use win::*;

#[cfg(unix)]
mod unix_attach;
#[cfg(unix)]
mod unix_procs;
#[cfg(unix)]
mod unix_term;
#[cfg(unix)]
pub use unix_attach::inject;
#[cfg(unix)]
pub use unix_procs::java_processes;
#[cfg(unix)]
pub use unix_term::{enable_ansi, poll_key, restore_terminal};

#[cfg(windows)]
pub const LIB_NAME: &str = "logic_native.dll";
#[cfg(target_os = "macos")]
pub const LIB_NAME: &str = "liblogic_native.dylib";
#[cfg(all(unix, not(target_os = "macos")))]
pub const LIB_NAME: &str = "liblogic_native.so";
