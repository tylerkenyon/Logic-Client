 
 
 
 
 
 
 
use super::Key;
use std::fs::File;
use std::io::Read;
use std::process::{Command, Stdio};
use std::sync::mpsc::{channel, Receiver, RecvTimeoutError};
use std::sync::{Mutex, OnceLock};
use std::time::Duration;

extern "C" {
    fn signal(signum: i32, handler: usize) -> usize;
    fn pipe(fds: *mut i32) -> i32;
    fn write(fd: i32, buf: *const u8, n: usize) -> isize;
    fn read(fd: i32, buf: *mut u8, n: usize) -> isize;
}
const SIGINT: i32 = 2;

static SAVED: Mutex<Option<String>> = Mutex::new(None);
static INPUT: OnceLock<Mutex<Option<Receiver<u8>>>> = OnceLock::new();
static SIG_WRITE_FD: std::sync::atomic::AtomicI32 = std::sync::atomic::AtomicI32::new(-1);

fn input() -> &'static Mutex<Option<Receiver<u8>>> {
    INPUT.get_or_init(|| Mutex::new(None))
}

fn tty_stdin() -> Stdio {
    match File::open("/dev/tty") {
        Ok(f) => Stdio::from(f),
        Err(_) => Stdio::inherit(),
    }
}

fn stty(args: &[&str]) -> Option<String> {
    let out = Command::new("stty")
        .args(args)
        .stdin(tty_stdin())
        .stderr(Stdio::null())
        .output()
        .ok()?;
    out.status
        .success()
        .then(|| String::from_utf8_lossy(&out.stdout).trim().to_string())
}

 
pub fn enable_ansi() {}

extern "C" fn on_sigint(_sig: i32) {
    let fd = SIG_WRITE_FD.load(std::sync::atomic::Ordering::Relaxed);
    if fd >= 0 {
        let b = 1u8;
        unsafe { write(fd, &b, 1) };
    }
}

fn install_sigint() {
    let mut fds = [0i32; 2];
    if unsafe { pipe(fds.as_mut_ptr()) } != 0 {
        return;
    }
    SIG_WRITE_FD.store(fds[1], std::sync::atomic::Ordering::Relaxed);
    let rfd = fds[0];
    std::thread::spawn(move || {
        let mut b = 0u8;
        loop {
            let n = unsafe { read(rfd, &mut b, 1) };
            if n == 1 {
                restore_terminal();
                println!();
                std::process::exit(130);
            }
            if n == 0 {
                return;
            }
        }
    });
    unsafe { signal(SIGINT, on_sigint as extern "C" fn(i32) as usize) };
}

fn enter_raw() -> Option<Receiver<u8>> {
    let saved = stty(&["-g"])?;
    *SAVED.lock().ok()? = Some(saved);
    stty(&["-icanon", "-echo", "min", "1", "time", "0"])?;
    let mut tty: Box<dyn Read + Send> = match File::open("/dev/tty") {
        Ok(f) => Box::new(f),
        Err(_) => Box::new(std::io::stdin()),
    };
    let (tx, rx) = channel();
    std::thread::spawn(move || {
        let mut b = [0u8; 1];
        while let Ok(1) = tty.read(&mut b) {
            if tx.send(b[0]).is_err() {
                break;
            }
        }
    });
    install_sigint();
    Some(rx)
}

 
pub fn poll_key() -> Option<Key> {
    let mut guard = input().lock().ok()?;
    if guard.is_none() {
        *guard = Some(enter_raw()?);
    }
    let rx = guard.as_ref()?;
    loop {
        let b = rx.try_recv().ok()?;
        match b {
            b'\n' | b'\r' => return Some(Key::Enter),
            b'k' => return Some(Key::Up),
            b'j' => return Some(Key::Down),
            27 => {
                let ms30 = Duration::from_millis(30);
                match rx.recv_timeout(ms30) {
                    Err(RecvTimeoutError::Timeout) | Err(RecvTimeoutError::Disconnected) => {
                        return Some(Key::Escape)
                    }
                    Ok(b'[') | Ok(b'O') => match rx.recv_timeout(ms30) {
                        Ok(b'A') => return Some(Key::Up),
                        Ok(b'B') => return Some(Key::Down),
                        _ => {}
                    },
                    Ok(_) => {}
                }
            }
            _ => {}
        }
    }
}

 
pub fn restore_terminal() {
    let saved = SAVED.lock().ok().and_then(|mut s| s.take());
    if let Some(s) = saved {
        stty(&[&s]);
    }
}
