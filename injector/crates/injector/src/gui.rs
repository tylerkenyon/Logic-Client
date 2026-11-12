use super::{paths, platform, prepare_runtime, run};
use std::cell::RefCell;
use std::path::PathBuf;
use std::process::ExitCode;
use std::ptr::{null, null_mut};
use std::thread::JoinHandle;
use windows_sys::Win32::{
    Foundation::{COLORREF, HWND, LPARAM, LRESULT, RECT, WPARAM},
    Graphics::{
        Dwm::{DwmSetWindowAttribute, DWMWA_USE_IMMERSIVE_DARK_MODE},
        Gdi::*,
    },
    System::{Console::FreeConsole, LibraryLoader::GetModuleHandleW},
    UI::{
        Accessibility::{HCF_HIGHCONTRASTON, HIGHCONTRASTW},
        Controls::{DRAWITEMSTRUCT, ODS_DISABLED, ODS_FOCUS, ODS_SELECTED},
        Input::KeyboardAndMouse::EnableWindow,
        WindowsAndMessaging::*,
    },
};

const INJECT: usize = 101;
const REFRESH: usize = 102;
const SUBTITLE: usize = 103;
const STATUS: usize = 104;
const WINDOW_WIDTH: i32 = 536;
const WINDOW_HEIGHT: i32 = 366;

const fn rgb(red: u8, green: u8, blue: u8) -> COLORREF {
    red as u32 | (green as u32) << 8 | (blue as u32) << 16
}

const BACKGROUND: COLORREF = rgb(19, 25, 35);
const SURFACE: COLORREF = rgb(32, 41, 55);
const BORDER: COLORREF = rgb(65, 80, 100);
const TEXT: COLORREF = rgb(238, 244, 252);
const MUTED: COLORREF = rgb(172, 188, 208);
const ACCENT: COLORREF = rgb(144, 199, 255);

thread_local! {
    static APP: RefCell<Option<App>> = const { RefCell::new(None) };
    static HIGH_CONTRAST: std::cell::Cell<bool> = const { std::cell::Cell::new(false) };
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

unsafe fn fill(dc: HDC, rect: &RECT, color: COLORREF) {
    SetDCBrushColor(dc, color);
    FillRect(dc, rect, GetStockObject(DC_BRUSH));
}

unsafe fn draw_button(item: &DRAWITEMSTRUCT) {
    let (background, foreground) = if item.itemState & ODS_DISABLED != 0 {
        (SURFACE, MUTED)
    } else if item.CtlID as usize == INJECT {
        let background = if item.itemState & ODS_SELECTED != 0 { rgb(108, 169, 230) } else { ACCENT };
        (background, BACKGROUND)
    } else {
        let background = if item.itemState & ODS_SELECTED != 0 { BORDER } else { SURFACE };
        (background, TEXT)
    };
    let mut rect = item.rcItem;
    fill(item.hDC, &rect, background);
    if item.CtlID as usize == REFRESH {
        SetDCBrushColor(item.hDC, BORDER);
        FrameRect(item.hDC, &rect, GetStockObject(DC_BRUSH));
    }
    if item.itemState & ODS_FOCUS != 0 {
        let mut focus = rect;
        InflateRect(&mut focus, -3, -3);
        SetDCBrushColor(item.hDC, foreground);
        FrameRect(item.hDC, &focus, GetStockObject(DC_BRUSH));
    }
    let mut label = [0u16; 32];
    let length = GetWindowTextW(item.hwndItem, label.as_mut_ptr(), label.len() as i32);
    let font = SendMessageW(item.hwndItem, WM_GETFONT, 0, 0);
    let previous_font = SelectObject(item.hDC, font as _);
    SetBkMode(item.hDC, TRANSPARENT as i32);
    SetTextColor(item.hDC, foreground);
    DrawTextW(item.hDC, label.as_ptr(), length, &mut rect, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
    SelectObject(item.hDC, previous_font);
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
        WM_ERASEBKGND if !HIGH_CONTRAST.get() => {
            let dc = wparam as HDC;
            let mut bounds = std::mem::zeroed();
            GetClientRect(window, &mut bounds);
            fill(dc, &bounds, BACKGROUND);
            fill(dc, &RECT { left: 26, top: 24, right: 30, bottom: 76 }, ACCENT);
            fill(dc, &RECT { left: 26, top: 320, right: 510, bottom: 321 }, BORDER);
            1
        }
        WM_CTLCOLORSTATIC | WM_CTLCOLORLISTBOX if !HIGH_CONTRAST.get() => {
            let dc = wparam as HDC;
            let background = if message == WM_CTLCOLORLISTBOX { SURFACE } else { BACKGROUND };
            let foreground = match GetDlgCtrlID(lparam as HWND) as usize {
                SUBTITLE | STATUS => MUTED,
                _ => TEXT,
            };
            SetTextColor(dc, foreground);
            SetBkColor(dc, background);
            SetDCBrushColor(dc, background);
            GetStockObject(DC_BRUSH) as LRESULT
        }
        WM_DRAWITEM => {
            draw_button(&*(lparam as *const DRAWITEMSTRUCT));
            1
        }
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
        let mut contrast = HIGHCONTRASTW { cbSize: std::mem::size_of::<HIGHCONTRASTW>() as u32, ..std::mem::zeroed() };
        SystemParametersInfoW(SPI_GETHIGHCONTRAST, contrast.cbSize, (&mut contrast as *mut HIGHCONTRASTW).cast(), 0);
        let high_contrast = contrast.dwFlags & HCF_HIGHCONTRASTON != 0;
        HIGH_CONTRAST.set(high_contrast);
        let style = WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX;
        let mut bounds = RECT { left: 0, top: 0, right: WINDOW_WIDTH, bottom: WINDOW_HEIGHT };
        AdjustWindowRectEx(&mut bounds, style, 0, 0);
        let window = CreateWindowExW(0, title.as_ptr(), title.as_ptr(),
            style, CW_USEDEFAULT, CW_USEDEFAULT, bounds.right - bounds.left, bounds.bottom - bounds.top,
            null_mut(), null_mut(), instance, null());
        if window.is_null() {
            return ExitCode::FAILURE;
        }
        if !high_contrast {
            let dark = 1i32;
            DwmSetWindowAttribute(window, DWMWA_USE_IMMERSIVE_DARK_MODE as u32,
                (&dark as *const i32).cast(), std::mem::size_of_val(&dark) as u32);
        }
        let face = wide("Segoe UI");
        let font = |height: i32, weight| CreateFontW(-height, 0, 0, 0, weight, 0, 0, 0,
            DEFAULT_CHARSET as u32, 0, 0, CLEARTYPE_QUALITY as u32, 0, face.as_ptr());
        let body = font(15, 400);
        let heading = font(28, 600);
        let control = |class: &str, text: &str, style: u32, x, y, width, height, id: usize, font: HFONT| {
            let handle = CreateWindowExW(0, wide(class).as_ptr(), wide(text).as_ptr(),
                WS_CHILD | WS_VISIBLE | style, x, y, width, height, window, id as _, instance, null());
            let font = if font.is_null() { GetStockObject(DEFAULT_GUI_FONT) } else { font };
            SendMessageW(handle, WM_SETFONT, font as _, 0);
            handle
        };
        control("STATIC", "Logic Launcher", 0, 42, 21, 468, 36, 0, heading);
        control("STATIC", "Minecraft 26.2 or 26.3 with Fabric", 0, 43, 57, 467, 21, SUBTITLE, body);
        control("STATIC", "Running Java windows", 0, 26, 99, 484, 21, 0, body);
        let list = control("LISTBOX", "", WS_BORDER | WS_VSCROLL | WS_TABSTOP | LBS_NOINTEGRALHEIGHT as u32,
            26, 124, 484, 136, 0, body);
        let button = WS_TABSTOP | if high_contrast { 0 } else { BS_OWNERDRAW as u32 };
        control("BUTTON", "Refresh", button, 294, 274, 90, 34, REFRESH, body);
        let inject = control("BUTTON", "Inject", button, 394, 274, 116, 34, INJECT, body);
        let status = control("STATIC", "Start Minecraft, then select its window.", 0,
            26, 333, 484, 22, STATUS, body);
        let mut app = App { list, status, inject, dll, processes: Vec::new(), job: None };
        app.refresh();
        APP.with(|state| *state.borrow_mut() = Some(app));
        SetTimer(window, 1, 750, None);
        ShowWindow(window, SW_SHOW);
        let mut message: MSG = std::mem::zeroed();
        let result = loop {
            match GetMessageW(&mut message, null_mut(), 0, 0) {
                -1 => {
                    DestroyWindow(window);
                    break ExitCode::FAILURE;
                }
                0 => break ExitCode::SUCCESS,
                _ if IsDialogMessageW(window, &message) != 0 => {}
                _ => {
                    TranslateMessage(&message);
                    DispatchMessageW(&message);
                }
            }
        };
        DeleteObject(body);
        DeleteObject(heading);
        result
    }
}
