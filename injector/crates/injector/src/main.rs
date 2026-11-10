 
 
 
 
 
 
 
#[path = "../../paths.rs"]
mod paths;
mod platform;
#[cfg(windows)]
mod update;
#[cfg(windows)]
mod gui;

use std::fs;
use std::io::{self, Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::process::ExitCode;
use std::time::{Duration, Instant};

 
 
const FOLLOW_TIMEOUT: Duration = Duration::from_secs(180);

const RESET: &str = "\x1b[0m";
const DIM: &str = "\x1b[90m";
const CYAN: &str = "\x1b[96m";
const GREEN: &str = "\x1b[92m";
const RED: &str = "\x1b[91m";

fn prepare_runtime(_offline: bool, _ask_for_updates: bool) -> Result<PathBuf, String> {
    #[cfg(windows)]
    if !_offline && _ask_for_updates {
        match update::check() {
            Ok(true) => std::process::exit(0),
            Ok(false) => {},
            Err(error) => eprintln!("Update check failed: {error}. Continuing with the installed version."),
        }
    }
    let path = std::env::current_exe()
        .map_err(|error| error.to_string())?
        .with_file_name(platform::LIB_NAME);
    if path.is_file() {
        Ok(path)
    } else {
        Err(format!("Logic requires {} beside the CLI executable. Extract the complete offline bundle.", platform::LIB_NAME))
    }
}

fn banner() -> String {
    format!("{CYAN}Logic launcher{RESET}\nMinecraft 26.2 and 26.3 / Fabric API / bundled injectable Mica\n\n")
}

fn step(label: &str, message: &str) {
    println!(
        "{DIM}[{:>7}]{RESET} {CYAN}{label:<12}{RESET} {message}",
        clock()
    );
    log_launcher(label, message);
}
fn ok(label: &str, message: &str) {
    println!(
        "{DIM}[{:>7}]{RESET} {GREEN}{label:<12}{RESET} {message}",
        clock()
    );
    log_launcher(label, message);
}
fn fail(label: &str, message: &str) {
    println!(
        "{DIM}[{:>7}]{RESET} {RED}{label:<12}{RESET} {message}",
        clock()
    );
    log_launcher(label, message);
}

fn log_launcher(label: &str, message: &str) {
    let path = paths::root()
        .join("logs")
        .join(format!("launcher-{}.log", std::process::id()));
    if let Ok(mut file) = fs::OpenOptions::new().create(true).append(true).open(path) {
        let _ = writeln!(file, "[{}] {label} {message}", clock());
    }
}

 
fn clock() -> String {
    use std::sync::OnceLock;
    static START: OnceLock<Instant> = OnceLock::new();
    format!(
        "{:.1}s",
        START.get_or_init(Instant::now).elapsed().as_secs_f32()
    )
}

fn main() -> ExitCode {
    let code = real_main();
    platform::restore_terminal();
    code
}

fn real_main() -> ExitCode {
    platform::enable_ansi();
    let _ = clock();
    let args: Vec<String> = std::env::args().skip(1).collect();
    if let Err(error) = paths::initialize() {
        fail("storage", &error.to_string());
        return ExitCode::from(1);
    }
    #[cfg(windows)]
    if args.is_empty() {
        return gui::launch();
    }
    let interactive = args.is_empty()
        || matches!(args.as_slice(), [flag] if flag == "--console" || flag == "--offline");

    let (pid, dll): (Option<u32>, Result<PathBuf, String>) = match args.as_slice() {
        _ if interactive => {
            let library = prepare_runtime(args.iter().any(|arg| arg == "--offline"), true);
            if let Err(error) = &library {
                fail("runtime", error);
                return ExitCode::from(1);
            }
            (pick_process(), library)
        }
        [pid] => (parse_pid(pid), prepare_runtime(true, false)),
        [pid, dll] => (parse_pid(pid), Ok(PathBuf::from(dll))),
        _ => {
            eprintln!("usage: LogicLauncher [--console | --offline | <pid> [lib path]]");
            return ExitCode::from(2);
        }
    };
    let Some(pid) = pid else {
         
        return if interactive {
            ExitCode::SUCCESS
        } else {
            ExitCode::from(2)
        };
    };
    if !interactive {
        print!("{}", banner());
    }
    let dll = match dll {
        Ok(path) => path,
        Err(error) => {
            fail("runtime", &error);
            return ExitCode::from(1);
        }
    };

    let code = run(pid, &dll);
    if interactive {
        print!("\nPress Enter to close...");
        let _ = io::stdout().flush();
        let mut line = String::new();
        let _ = io::stdin().read_line(&mut line);
    }
    code
}

fn parse_pid(text: &str) -> Option<u32> {
    text.parse().ok()
}

fn run(pid: u32, dll: &Path) -> ExitCode {
    println!();
    step("target", &format!("process {pid}"));
    let dll = match dll.canonicalize() {
        Ok(p) => p,
        Err(e) => {
            fail("error", &format!("cannot find {}: {e}", dll.display()));
            return ExitCode::from(1);
        }
    };
    let dll_display = dll.display().to_string();
    #[cfg(windows)]
    let dll_display = dll_display.trim_start_matches(r"\\?\").to_string();
    step("loader", &dll_display);

    let log_path = paths::native_log(pid);
    let logs = [log_path.clone(), dll.with_file_name("logic-native.log")];
    let starts = logs
        .each_ref()
        .map(|path| fs::metadata(path).map(|m| m.len()).unwrap_or(0));

    step(
        "checking",
        "64-bit JVM, process access, library not already loaded",
    );
    step("injecting", "loading the native library into the JVM");
    match platform::inject(pid, &dll) {
        Ok(()) => ok(
            "injected",
            &format!("{} is loaded in the game process", platform::LIB_NAME),
        ),
        Err(e) => {
            fail("failed", &e);
            return ExitCode::from(1);
        }
    }

    step(
        "transforming",
        "following the loader log (attach, class loader, hook transformer)...",
    );
    match follow_log(&logs, starts) {
        Outcome::Done => {
            println!();
            ok(
                "success",
                "successfully injected - Logic is running in the game",
            );
            ExitCode::SUCCESS
        }
        Outcome::Failed(line) => {
            fail("failed", &format!("injection did not complete: {line}"));
            println!(
                "{DIM}  details: {} and the game's latest.log{RESET}",
                log_path.display()
            );
            ExitCode::from(1)
        }
        Outcome::TimedOut => {
            fail(
                "timeout",
                "no completion message from the loader within 3 minutes",
            );
            println!(
                "{DIM}  check {} and the game's latest.log{RESET}",
                log_path.display()
            );
            ExitCode::from(1)
        }
    }
}

enum Outcome {
    Done,
    Failed(String),
    TimedOut,
}

 
fn follow_log(paths: &[PathBuf; 2], mut offsets: [u64; 2]) -> Outcome {
    let deadline = Instant::now() + FOLLOW_TIMEOUT;
    let mut pending = [String::new(), String::new()];
    while Instant::now() < deadline {
        for (index, path) in paths.iter().enumerate() {
            let offset = &mut offsets[index];
            let pending = &mut pending[index];
            if let Ok(mut file) = fs::File::open(path) {
                if file.seek(SeekFrom::Start(*offset)).is_ok() {
                    let mut chunk = String::new();
                    if let Ok(n) = file.read_to_string(&mut chunk) {
                        *offset += n as u64;
                        pending.push_str(&chunk);
                    }
                }
            }
            while let Some(end) = pending.find('\n') {
                let line: String = pending.drain(..=end).collect();
                 
                if index == 1 {
                    if let Ok(mut log) = fs::OpenOptions::new()
                        .create(true)
                        .append(true)
                        .open(&paths[0])
                    {
                        if log.write_all(line.as_bytes()).is_ok() {
                            offsets[0] += line.len() as u64;
                        }
                    }
                }
                let line = line.trim_end();
                 
                let message = line.split_once("] ").map(|(_, m)| m).unwrap_or(line);
                let lower = message.to_ascii_lowercase();
                 
                let fatal = message.starts_with("FAILED:")
                    || lower.contains("start failed")
                    || lower.contains("client init failed")
                    || lower.contains("could not hook");
                if fatal {
                    fail("loader", message);
                    return Outcome::Failed(message.to_string());
                }
                if message.contains("client initialised") {
                    ok("loader", message);
                    return Outcome::Done;
                }
                step("loader", message);
            }
        }
        std::thread::sleep(Duration::from_millis(200));
    }
    Outcome::TimedOut
}

 
fn pick_process() -> Option<u32> {
    let mut selected = 0usize;
    let mut processes = platform::java_processes();
    let mut last_refresh = Instant::now();
    draw(&processes, selected);
    loop {
        let deadline = Instant::now() + Duration::from_millis(50);
        while Instant::now() < deadline {
            if let Some(key) = platform::poll_key() {
                let previous = selected;
                match key {
                    platform::Key::Up => selected = selected.saturating_sub(1),
                    platform::Key::Down => {
                        selected = (selected + 1).min(processes.len().saturating_sub(1))
                    }
                    platform::Key::Enter => {
                        if let Some(p) = processes.get(selected) {
                            return Some(p.pid);
                        }
                    }
                    platform::Key::Escape => return None,
                }
                if selected != previous {
                    draw(&processes, selected);
                }
            }
            std::thread::sleep(Duration::from_millis(10));
        }
        if last_refresh.elapsed() >= Duration::from_millis(750) {
            let refreshed = platform::java_processes();
            if refreshed != processes {
                processes = refreshed;
                selected = selected.min(processes.len().saturating_sub(1));
                draw(&processes, selected);
            }
            last_refresh = Instant::now();
        }
    }
}

fn draw(processes: &[platform::JavaProcess], selected: usize) {
    let mut out = String::from("\x1b[2J\x1b[H");
    out.push_str(&banner());
    out.push_str("Minecraft 26.2 and 26.3 (Fabric, 64-bit) - Up/Down select, Enter inject, Esc quit\n\n");
    if processes.is_empty() {
        out.push_str("  waiting for a Minecraft JVM (java/javaw)...\n");
    }
    for (i, p) in processes.iter().enumerate() {
        let marker = if i == selected { ">" } else { " " };
        out.push_str(&format!(
            "{marker} [{:>6}] {}  ({})\n",
            p.pid, p.title, p.exe
        ));
    }
    print!("{out}");
    let _ = io::stdout().flush();
}
