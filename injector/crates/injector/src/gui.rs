use super::{paths, platform, prepare_runtime, run};
use std::cell::RefCell;
use std::path::PathBuf;
use std::process::ExitCode;
use std::ptr::{null, null_mut};
use std::thread::JoinHandle;
use windows_sys::Win32::{
    Foundation::{HWND, LPARAM, LRESULT, WPARAM},
    Graphics::Gdi::{GetStockObject, COLOR_WINDOW, DEFAULT_GUI_FONT},
    System::{Console::FreeConsole, LibraryLoader::GetModuleHandleW},
    UI::{Input::KeyboardAndMouse::EnableWindow, WindowsAndMessaging::*},
};

const INJECT: usize = 101;
const REFRESH: usize = 102;

thread_local! {
    static APP: RefCell<Option<App>> = const { RefCell::new(None) };
}

struct App {
    list: HWND,
    status: HWND,
    inject: HWND,
    dll: PathBuf,
    processes: Vec<platform::JavaProcess>,
    job: Option<JoinHandle<ExitCode>>,
}

fn wide(value: &str) -> Vec<u16> {
    value.encode_utf16().chain(Some(0)).collect()
}

impl App {
    unsafe fn refresh(&mut self) {
        let processes = platform::java_processes();
        if processes != self.processes {
            let selection = SendMessageW(self.list, LB_GETCURSEL, 0, 0);
            let selected_pid = self.processes.get(selection as usize).map(|process| process.pid);
            SendMessageW(self.list, LB_RESETCONTENT, 0, 0);
            for process in &processes {
                let label = wide(&format!("{}  (PID {})", process.title, process.pid));
                SendMessageW(self.list, LB_ADDSTRING, 0, label.as_ptr() as LPARAM);
            }
            if !processes.is_empty() {
                let index = processes.iter().position(|process| Some(process.pid) == selected_pid).unwrap_or(0);
                SendMessageW(self.list, LB_SETCURSEL, index, 0);
            }
            self.processes = processes;
        }
        EnableWindow(self.inject, (!self.processes.is_empty() && self.job.is_none()) as i32);
    }

    unsafe fn inject(&mut self) {
        if self.job.is_some() {
            return;
        }
        let selected = SendMessageW(self.list, LB_GETCURSEL, 0, 0);
        let Some(process) = self.processes.get(selected as usize) else { return };
        let pid = process.pid;
        let dll = self.dll.clone();
        self.job = Some(std::thread::spawn(move || run(pid, &dll)));
        EnableWindow(self.inject, 0);
        SetWindowTextW(self.status, wide(&format!("Injecting into PID {pid}…")).as_ptr());
    }

    unsafe fn tick(&mut self) {
        if self.job.as_ref().is_some_and(|job| job.is_finished()) {
            let success = self.job.take().and_then(|job| job.join().ok()) == Some(ExitCode::SUCCESS);
            let status = if success {
                "Logic is running. Press Right Shift in Minecraft."
            } else {
                "Injection failed. See the launcher and native logs."
            };
            SetWindowTextW(self.status, wide(status).as_ptr());
            if !success {
                let log = paths::root().join("logs").join(format!("launcher-{}.log", std::process::id()));
                let details = std::fs::read_to_string(log).ok()
                    .and_then(|text| text.lines().last().map(str::to_owned))
                    .unwrap_or_else(|| status.to_owned());
                MessageBoxW(self.status, wide(&details).as_ptr(), wide("Logic launcher").as_ptr(), MB_ICONERROR);
            }
        }
        self.refresh();
    }
}

unsafe extern "system" fn window_proc(window: HWND, message: u32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
    match message {
        WM_COMMAND | WM_TIMER => {
            APP.with(|state| {
                if let Ok(mut state) = state.try_borrow_mut() {
                    if let Some(app) = state.as_mut() {
                        match (message, wparam & 0xffff) {
                            (WM_TIMER, _) => app.tick(),
                            (WM_COMMAND, INJECT) => app.inject(),
                            (WM_COMMAND, REFRESH) => app.refresh(),
                            _ => {}
                        }
                    }
                }
            });
            0
        }
        WM_DESTROY => {
            PostQuitMessage(0);
            0
        }
        _ => DefWindowProcW(window, message, wparam, lparam),
    }
}

pub fn launch() -> ExitCode {
    unsafe {
        FreeConsole();
        let title = wide("Logic Launcher");
        let dll = match prepare_runtime(true, false) {
            Ok(path) => path,
            Err(error) => {
                MessageBoxW(null_mut(), wide(&error).as_ptr(), title.as_ptr(), MB_ICONERROR);
                return ExitCode::FAILURE;
            }
        };
        let instance = GetModuleHandleW(null());
        let class = WNDCLASSW {
            lpfnWndProc: Some(window_proc),
            hInstance: instance,
            hCursor: LoadCursorW(null_mut(), IDC_ARROW),
            hbrBackground: (COLOR_WINDOW + 1) as _,
            lpszClassName: title.as_ptr(),
            ..std::mem::zeroed()
        };
        if RegisterClassW(&class) == 0 {
            return ExitCode::FAILURE;
        }
        let window = CreateWindowExW(0, title.as_ptr(), title.as_ptr(),
            WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX, CW_USEDEFAULT, CW_USEDEFAULT, 550, 360,
            null_mut(), null_mut(), instance, null());
        if window.is_null() {
            return ExitCode::FAILURE;
        }
        let control = |class: &str, text: &str, style: u32, x, y, width, height, id: usize| {
            let handle = CreateWindowExW(0, wide(class).as_ptr(), wide(text).as_ptr(),
                WS_CHILD | WS_VISIBLE | style, x, y, width, height, window, id as _, instance, null());
            SendMessageW(handle, WM_SETFONT, GetStockObject(DEFAULT_GUI_FONT) as _, 0);
            handle
        };
        control("STATIC", "Select a running Minecraft 26.2 or 26.3 Fabric game.", 0, 16, 16, 500, 22, 0);
        let list = control("LISTBOX", "", WS_BORDER | WS_VSCROLL | WS_TABSTOP, 16, 44, 500, 195, 0);
        let inject = control("BUTTON", "Inject", WS_TABSTOP, 16, 250, 100, 30, INJECT);
        control("BUTTON", "Refresh", WS_TABSTOP, 124, 250, 100, 30, REFRESH);
        let status = control("STATIC", "Start the game, then select its window.", 0, 16, 290, 500, 25, 0);
        let mut app = App { list, status, inject, dll, processes: Vec::new(), job: None };
        app.refresh();
        APP.with(|state| *state.borrow_mut() = Some(app));
        SetTimer(window, 1, 750, None);
        ShowWindow(window, SW_SHOW);
        let mut message: MSG = std::mem::zeroed();
        loop {
            match GetMessageW(&mut message, null_mut(), 0, 0) {
                -1 => return ExitCode::FAILURE,
                0 => return ExitCode::SUCCESS,
                _ if IsDialogMessageW(window, &message) != 0 => {}
                _ => {
                    TranslateMessage(&message);
                    DispatchMessageW(&message);
                }
            }
        }
    }
}
