use super::{paths, platform, prepare_runtime, run};
use serde::{Deserialize, Serialize};
use std::cell::{Cell, RefCell};
use std::path::PathBuf;
use std::process::ExitCode;
use std::ptr::{null, null_mut};
use std::thread::JoinHandle;
use windows_sys::Win32::{
    Foundation::{COLORREF, HWND, LPARAM, LRESULT, POINT, RECT, WPARAM},
    Graphics::{
        Dwm::{
            DwmSetWindowAttribute, DWMWA_BORDER_COLOR, DWMWA_USE_IMMERSIVE_DARK_MODE,
            DWMWA_WINDOW_CORNER_PREFERENCE, DWMWCP_ROUND,
        },
        Gdi::*,
    },
    System::{
        Console::{AllocConsole, FreeConsole, GetConsoleWindow, SetConsoleTitleW},
        LibraryLoader::GetModuleHandleW,
        SystemServices::{SS_CENTER, SS_CENTERIMAGE, SS_ENDELLIPSIS},
    },
    UI::{
        Accessibility::{HCF_HIGHCONTRASTON, HIGHCONTRASTW},
        Controls::{
            BST_CHECKED, BST_UNCHECKED, DRAWITEMSTRUCT, MEASUREITEMSTRUCT, ODS_COMBOBOXEDIT,
            ODS_DISABLED, ODS_FOCUS, ODS_NOFOCUSRECT, ODS_SELECTED, ODT_COMBOBOX,
        },
        Input::KeyboardAndMouse::{EnableWindow, GetFocus},
        Shell::{DefSubclassProc, SetWindowSubclass, ShellExecuteW},
        WindowsAndMessaging::*,
    },
};

const INJECT: usize = 101;
const MINIMIZE: usize = 102;
const CLOSE: usize = 105;
const SUBTITLE: usize = 103;
const SETTINGS: usize = 104;
const GITHUB: usize = 106;
const UPDATE: usize = 107;
const VERSION: usize = 108;
const CHOICE_BASE: usize = 200;
const OPTIONS: [&[&str]; 6] = [
    &["Dark", "Light"],
    &["85%", "100%", "115%", "130%"],
    &["100%", "90%", "80%", "70%"],
    &["Off", "On"],
    &["Off", "On"],
    &["Off", "On"],
];
const CHOICE_END: usize = CHOICE_BASE + OPTIONS.len() * 4;
const PROCESS_TIMER: usize = 1;
const ANIMATION_TIMER: usize = 2;
const SCALES: [i32; 4] = [85, 100, 115, 130];
const OPACITIES: [i32; 4] = [100, 90, 80, 70];
const WINDOW_WIDTH: i32 = 520;
const WINDOW_HEIGHT: i32 = 352;
const SETTINGS_HEIGHT: i32 = WINDOW_HEIGHT + 84;
const SETTINGS_INSET: i32 = 28;
const SETTINGS_RIGHT: i32 = WINDOW_WIDTH - SETTINGS_INSET;
const CHOICES_LEFT: i32 = 248;
const CHOICE_HEIGHT: i32 = 34;
const ROW_TOP: i32 = 77;
const ROW_STEP: i32 = 42;

const fn rgb(red: u8, green: u8, blue: u8) -> COLORREF {
    red as u32 | (green as u32) << 8 | (blue as u32) << 16
}

const BACKGROUND: COLORREF = rgb(24, 25, 27);
const SURFACE: COLORREF = rgb(34, 36, 38);
const BORDER: COLORREF = rgb(52, 55, 58);
const TEXT: COLORREF = rgb(241, 242, 243);
const MUTED: COLORREF = rgb(164, 167, 172);
const ACCENT: COLORREF = rgb(217, 225, 248);

thread_local! {
    static PREFS: Cell<Preferences> = Cell::new(Preferences::default());
    static SETTINGS_OPEN: Cell<bool> = const { Cell::new(false) };
    static CHOSEN: Cell<[usize; 6]> = const { Cell::new([0, 1, 0, 0, 0, 0]) };
    static CHECKING: Cell<bool> = const { Cell::new(false) };
    static CONNECTING: Cell<bool> = const { Cell::new(false) };
    static SPINNER_STEP: Cell<u32> = const { Cell::new(0) };
    static APP: RefCell<Option<App>> = const { RefCell::new(None) };
    static HIGH_CONTRAST: std::cell::Cell<bool> = const { std::cell::Cell::new(false) };
}

#[derive(Clone, Copy, PartialEq, Serialize, Deserialize)]
#[serde(default)]
struct Preferences {
    light: bool,
    scale: usize,
    opacity: usize,
    capture_privacy: bool,
    close_after_injection: bool,
    debug_console: bool,
}

impl Default for Preferences {
    fn default() -> Self {
        Self {
            light: false,
            scale: 1,
            opacity: 0,
            capture_privacy: false,
            close_after_injection: false,
            debug_console: false,
        }
    }
}

impl Preferences {
    fn load() -> Self {
        let mut prefs: Self = std::fs::read(Self::path())
            .ok()
            .and_then(|bytes| serde_json::from_slice(&bytes).ok())
            .unwrap_or_default();
        prefs.scale = prefs.scale.min(SCALES.len() - 1);
        prefs.opacity = prefs.opacity.min(OPACITIES.len() - 1);
        prefs
    }

    fn path() -> PathBuf {
        paths::root().join("configs").join("launcher.json")
    }

    fn save(self) -> std::io::Result<()> {
        let path = Self::path();
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        std::fs::write(path, serde_json::to_vec_pretty(&self)?)
    }
}

fn draft_preferences() -> Preferences {
    let [theme, scale, opacity, privacy, close, debug] = CHOSEN.get();
    Preferences {
        light: theme == 1,
        scale,
        opacity,
        capture_privacy: privacy == 1,
        close_after_injection: close == 1,
        debug_console: debug == 1,
    }
}
fn scaled(value: i32) -> i32 {
    (value * SCALES[PREFS.get().scale] + 50) / 100
}

fn themed(dark: COLORREF, light: COLORREF) -> COLORREF {
    if PREFS.get().light {
        light
    } else {
        dark
    }
}

fn background() -> COLORREF {
    themed(BACKGROUND, rgb(244, 245, 246))
}
fn surface() -> COLORREF {
    themed(SURFACE, rgb(227, 230, 233))
}
fn border() -> COLORREF {
    themed(BORDER, rgb(201, 205, 209))
}
fn foreground() -> COLORREF {
    themed(TEXT, rgb(32, 35, 38))
}
fn muted() -> COLORREF {
    themed(MUTED, rgb(83, 91, 99))
}

struct Control {
    window: HWND,
    bounds: [i32; 4],
    font: usize,
    settings_only: bool,
}

struct App {
    list: HWND,
    window: HWND,
    controls: Vec<Control>,
    fonts: [HFONT; 3],
    settings_button: HWND,
    update_button: HWND,
    update_info: HWND,
    update_job: Option<JoinHandle<Result<Option<semver::Version>, String>>>,
    feedback: HWND,
    headline: HWND,
    detail: HWND,
    inject: HWND,
    dll: PathBuf,
    processes: Vec<platform::JavaProcess>,
    job: Option<(u32, JoinHandle<ExitCode>)>,
    failed_pid: Option<u32>,
    reported_injected: Option<u32>,
}

fn wide(value: &str) -> Vec<u16> {
    value.encode_utf16().chain(Some(0)).collect()
}

fn is_minecraft_window(title: &str) -> bool {
    let title = title.trim().to_ascii_lowercase();
    title.contains("minecraft")
        || title
            .strip_prefix("lunar client ")
            .is_some_and(|version| version.starts_with(|c: char| c.is_ascii_digit()))
}

#[cfg(test)]
#[test]
fn recognizes_lunar_game_windows() {
    assert!(is_minecraft_window("Lunar Client 26.3 (72ec502/canary)"));
    assert!(is_minecraft_window("Minecraft 26.3"));
    assert!(!is_minecraft_window("Lunar Client Launcher"));
    assert!(!is_minecraft_window("IntelliJ IDEA"));
}

unsafe fn fill(dc: HDC, rect: &RECT, color: COLORREF) {
    SetDCBrushColor(dc, color);
    FillRect(dc, rect, GetStockObject(DC_BRUSH));
}

unsafe fn draw_logo(dc: HDC) {
    const WIDTH: i32 = 94;
    const HEIGHT: i32 = 90;
    let logo: &[u8; (WIDTH * HEIGHT * 4) as usize] = if PREFS.get().light {
        include_bytes!("../assets/lc-light.bgrx")
    } else {
        include_bytes!("../assets/lc-dark.bgrx")
    };
    let mut info: BITMAPINFO = std::mem::zeroed();
    info.bmiHeader.biSize = std::mem::size_of::<BITMAPINFOHEADER>() as u32;
    info.bmiHeader.biWidth = WIDTH;
    info.bmiHeader.biHeight = -HEIGHT;
    info.bmiHeader.biPlanes = 1;
    info.bmiHeader.biBitCount = 32;
    let [x, y, width, height] = if SETTINGS_OPEN.get() {
        [23, 12, 38, 36]
    } else {
        [(WINDOW_WIDTH - WIDTH) / 2, 76, WIDTH, HEIGHT]
    }
    .map(scaled);
    SetStretchBltMode(dc, HALFTONE);
    SetBrushOrgEx(dc, 0, 0, null_mut());
    StretchDIBits(
        dc,
        x,
        y,
        width,
        height,
        0,
        0,
        WIDTH,
        HEIGHT,
        logo.as_ptr().cast(),
        &info,
        DIB_RGB_COLORS,
        SRCCOPY,
    );
}

fn blend(base: COLORREF, ink: COLORREF, alpha: u32) -> COLORREF {
    [0, 8, 16]
        .into_iter()
        .map(|shift| {
            let b = (base >> shift) & 255;
            let i = (ink >> shift) & 255;
            ((b * (255 - alpha) + i * alpha + 127) / 255) << shift
        })
        .sum()
}

unsafe fn draw_icon(dc: HDC, rect: RECT, base: COLORREF, ink: COLORREF, mask: &[u8; 48 * 48]) {
    const SIDE: i32 = 48;
    let pixels = mask.map(|alpha| {
        let c = blend(base, ink, alpha as u32);
        ((c & 255) << 16) | (c & 0xff00) | ((c >> 16) & 255)
    });
    let mut info: BITMAPINFO = std::mem::zeroed();
    info.bmiHeader.biSize = std::mem::size_of::<BITMAPINFOHEADER>() as u32;
    info.bmiHeader.biWidth = SIDE;
    info.bmiHeader.biHeight = -SIDE;
    info.bmiHeader.biPlanes = 1;
    info.bmiHeader.biBitCount = 32;
    let size = scaled(20);
    SetStretchBltMode(dc, HALFTONE);
    SetBrushOrgEx(dc, 0, 0, null_mut());
    StretchDIBits(
        dc,
        (rect.left + rect.right - size) / 2,
        (rect.top + rect.bottom - size) / 2,
        size,
        size,
        0,
        0,
        SIDE,
        SIDE,
        pixels.as_ptr().cast(),
        &info,
        DIB_RGB_COLORS,
        SRCCOPY,
    );
}

unsafe fn draw_spinner(dc: HDC, x: i32, y: i32, base: COLORREF, ink: COLORREF) {
    let pen = SelectObject(dc, GetStockObject(NULL_PEN));
    let brush = SelectObject(dc, GetStockObject(DC_BRUSH));
    let radius = f64::from(scaled(6));
    let dot = scaled(3);
    for index in 0..10 {
        let angle = f64::from(index) * std::f64::consts::TAU / 10.0;
        let left = x + (radius * angle.cos()).round() as i32 - dot / 2;
        let top = y + (radius * angle.sin()).round() as i32 - dot / 2;
        let age = (SPINNER_STEP.get() + 10 - index) % 10;
        SetDCBrushColor(dc, blend(base, ink, 235 - age * 23));
        Ellipse(dc, left, top, left + dot, top + dot);
    }
    SelectObject(dc, brush);
    SelectObject(dc, pen);
}

unsafe fn rounded(dc: HDC, rect: RECT, color: COLORREF) {
    let brush = SelectObject(dc, GetStockObject(DC_BRUSH));
    let pen = SelectObject(dc, GetStockObject(DC_PEN));
    SetDCBrushColor(dc, color);
    SetDCPenColor(dc, color);
    let radius = scaled(12);
    RoundRect(
        dc,
        rect.left,
        rect.top,
        rect.right,
        rect.bottom,
        radius,
        radius,
    );
    SelectObject(dc, brush);
    SelectObject(dc, pen);
}

unsafe fn draw_combo_text(dc: HDC, window: HWND, index: usize, mut rect: RECT) {
    let len = SendMessageW(window, CB_GETLBTEXTLEN, index, 0);
    if len < 0 {
        return;
    }
    let mut label = vec![0u16; len as usize + 1];
    SendMessageW(window, CB_GETLBTEXT, index, label.as_mut_ptr() as LPARAM);
    let font = SendMessageW(window, WM_GETFONT, 0, 0);
    let old = SelectObject(dc, font as _);
    SetBkMode(dc, TRANSPARENT as i32);
    SetTextColor(dc, foreground());
    rect.left += scaled(10);
    rect.right -= scaled(8);
    DrawTextW(
        dc,
        label.as_ptr(),
        len as i32,
        &mut rect,
        DT_SINGLELINE | DT_VCENTER | DT_END_ELLIPSIS | DT_NOPREFIX,
    );
    SelectObject(dc, old);
}

unsafe fn draw_combo(item: &DRAWITEMSTRUCT) {
    let rect = item.rcItem;
    fill(item.hDC, &rect, surface());
    if item.itemState & ODS_COMBOBOXEDIT == 0 && item.itemState & ODS_SELECTED != 0 {
        let inset = scaled(3);
        rounded(
            item.hDC,
            RECT {
                left: rect.left + inset,
                top: rect.top + 1,
                right: rect.right - inset,
                bottom: rect.bottom - 1,
            },
            blend(surface(), foreground(), 28),
        );
    }
    if item.itemID != u32::MAX {
        draw_combo_text(item.hDC, item.hwndItem, item.itemID as usize, rect);
    }
}

unsafe extern "system" fn combo_proc(
    window: HWND,
    message: u32,
    wparam: WPARAM,
    lparam: LPARAM,
    _id: usize,
    _data: usize,
) -> LRESULT {
    if message == WM_ERASEBKGND {
        return 1;
    }
    if message == WM_PAINT {
        let mut paint: PAINTSTRUCT = std::mem::zeroed();
        let dc = BeginPaint(window, &mut paint);
        let mut rect: RECT = std::mem::zeroed();
        GetClientRect(window, &mut rect);
        fill(dc, &rect, background());
        let open = SendMessageW(window, CB_GETDROPPEDSTATE, 0, 0) != 0;
        let fill_color = if open || GetFocus() == window {
            blend(surface(), foreground(), 22)
        } else {
            surface()
        };
        rounded(dc, rect, fill_color);
        let center = rect.right - scaled(15);
        let y = rect.bottom / 2;
        let direction = if open { -1 } else { 1 };
        let half = scaled(4);
        let points = [
            POINT {
                x: center - half,
                y: y - direction * scaled(2),
            },
            POINT {
                x: center,
                y: y + direction * scaled(2),
            },
            POINT {
                x: center + half,
                y: y - direction * scaled(2),
            },
        ];
        let pen = SelectObject(dc, GetStockObject(DC_PEN));
        SetDCPenColor(dc, muted());
        Polyline(dc, points.as_ptr(), points.len() as i32);
        SelectObject(dc, pen);
        let selection = SendMessageW(window, CB_GETCURSEL, 0, 0);
        if selection >= 0 {
            rect.right -= scaled(26);
            draw_combo_text(dc, window, selection as usize, rect);
        }
        EndPaint(window, &paint);
        return 0;
    }
    let result = DefSubclassProc(window, message, wparam, lparam);
    if matches!(
        message,
        WM_SETFOCUS | WM_KILLFOCUS | WM_KEYDOWN | WM_LBUTTONUP | CB_SETCURSEL | CB_SHOWDROPDOWN
    ) {
        InvalidateRect(window, null(), 0);
    }
    result
}

unsafe fn draw_button(item: &DRAWITEMSTRUCT) {
    let id = item.CtlID as usize;
    let option = (CHOICE_BASE..CHOICE_END).contains(&id);
    let selected = option && CHOSEN.get()[(id - CHOICE_BASE) / 4] == (id - CHOICE_BASE) % 4;
    let chrome = matches!(id, SETTINGS | CLOSE | MINIMIZE);
    let pressed = item.itemState & ODS_SELECTED != 0;
    let (mut button_color, foreground) = if item.itemState & ODS_DISABLED != 0 {
        (surface(), muted())
    } else if id == INJECT {
        let button_color = if item.itemState & ODS_SELECTED != 0 {
            rgb(186, 201, 237)
        } else {
            ACCENT
        };
        (button_color, rgb(28, 36, 56))
    } else if option {
        if selected {
            (themed(rgb(63, 73, 96), rgb(210, 221, 244)), foreground())
        } else {
            (if pressed { border() } else { surface() }, muted())
        }
    } else if chrome && !pressed {
        (background(), foreground())
    } else {
        let button_color = if item.itemState & ODS_SELECTED != 0 {
            border()
        } else {
            surface()
        };
        (button_color, foreground())
    };
    if item.itemState & (ODS_FOCUS | ODS_NOFOCUSRECT) == ODS_FOCUS {
        button_color = blend(button_color, foreground, 32);
    }
    let mut rect = item.rcItem;
    fill(
        item.hDC,
        &rect,
        if option { surface() } else { background() },
    );
    rounded(item.hDC, rect, button_color);
    let x = (rect.left + rect.right) / 2;
    let y = (rect.top + rect.bottom) / 2;
    if id == SETTINGS && !SETTINGS_OPEN.get() {
        draw_icon(
            item.hDC,
            rect,
            button_color,
            foreground,
            include_bytes!("../assets/settings.alpha"),
        );
        return;
    }
    if id == GITHUB {
        draw_icon(
            item.hDC,
            RECT {
                left: rect.left + scaled(9),
                right: rect.left + scaled(29),
                ..rect
            },
            button_color,
            foreground,
            include_bytes!("../assets/github.alpha"),
        );
        rect.left += scaled(24);
    }
    if (id == UPDATE && CHECKING.get()) || (id == INJECT && CONNECTING.get()) {
        draw_spinner(item.hDC, x, y, button_color, foreground);
        return;
    }
    if chrome {
        let pen = SelectObject(item.hDC, GetStockObject(DC_PEN));
        SetDCPenColor(item.hDC, foreground);
        let d = scaled(4);
        let line = |x1, y1, x2, y2| {
            MoveToEx(item.hDC, x1, y1, null_mut());
            LineTo(item.hDC, x2, y2);
        };
        match id {
            SETTINGS => {
                line(x + 2, y - d, x - 3, y);
                line(x - 3, y, x + 3, y + d + 1);
            }
            MINIMIZE => line(x - d, y + 2, x + d + 1, y + 2),
            CLOSE => {
                line(x - d, y - d, x + d + 1, y + d + 1);
                line(x - d, y + d, x + d + 1, y - d - 1);
            }
            _ => {}
        }
        SelectObject(item.hDC, pen);
        return;
    }
    let mut label = [0u16; 32];
    let length = GetWindowTextW(item.hwndItem, label.as_mut_ptr(), label.len() as i32);
    let font = SendMessageW(item.hwndItem, WM_GETFONT, 0, 0);
    let previous_font = SelectObject(item.hDC, font as _);
    SetBkMode(item.hDC, TRANSPARENT as i32);
    SetTextColor(item.hDC, foreground);
    DrawTextW(
        item.hDC,
        label.as_ptr(),
        length,
        &mut rect,
        DT_CENTER | DT_VCENTER | DT_SINGLELINE,
    );
    SelectObject(item.hDC, previous_font);
}

unsafe fn set_text(window: HWND, text: &str) {
    let text = wide(text);
    let mut current = vec![0; text.len()];
    let length = GetWindowTextLengthW(window);
    if length as usize == text.len() - 1 {
        GetWindowTextW(window, current.as_mut_ptr(), current.len() as i32);
    }
    if current != text {
        SetWindowTextW(window, text.as_ptr());
    }
}

unsafe fn debug_console(enabled: bool) -> bool {
    if !enabled {
        return FreeConsole() != 0;
    }
    if AllocConsole() == 0 {
        return false;
    }
    SetConsoleTitleW(wide("Logic Launcher · Debug").as_ptr());
    let menu = GetSystemMenu(GetConsoleWindow(), 0);
    if !menu.is_null() {
        EnableMenuItem(menu, SC_CLOSE, MF_BYCOMMAND | MF_GRAYED);
    }
    platform::enable_ansi();
    super::step(
        "debug",
        "Debug console enabled. Turn it off in Launcher Settings.",
    );
    super::step("logs", &paths::root().join("logs").display().to_string());
    true
}

impl App {
    unsafe fn selected_process(&self) -> Option<&platform::JavaProcess> {
        let index = SendMessageW(self.list, CB_GETCURSEL, 0, 0);
        self.processes.get(index as usize)
    }

    unsafe fn refresh(&mut self) {
        let mut processes = platform::java_processes();
        processes.retain(|process| is_minecraft_window(&process.title));
        processes.sort_unstable_by_key(|process| process.pid);
        if processes != self.processes {
            let selection = SendMessageW(self.list, CB_GETCURSEL, 0, 0);
            let selected_pid = self
                .processes
                .get(selection as usize)
                .map(|process| process.pid);
            SendMessageW(self.list, CB_RESETCONTENT, 0, 0);
            for process in &processes {
                let label = wide(&format!("{}  (PID {})", process.title, process.pid));
                SendMessageW(self.list, CB_ADDSTRING, 0, label.as_ptr() as LPARAM);
            }
            if !processes.is_empty() {
                let index = processes
                    .iter()
                    .position(|process| Some(process.pid) == selected_pid)
                    .unwrap_or(0);
                SendMessageW(self.list, CB_SETCURSEL, index, 0);
            }
            self.processes = processes;
        }
        let pid = self.selected_process().map(|process| process.pid);
        let busy = self.job.is_some();
        let injected = !busy && pid.is_some_and(platform::is_injected);
        let active_pid = pid.filter(|_| injected);
        if self.reported_injected != active_pid {
            if let Some(pid) = active_pid {
                super::step("status", &format!("Logic is injected into process {pid}."));
            }
            self.reported_injected = active_pid;
        }
        if SETTINGS_OPEN.get() {
            return;
        }
        let ready = pid.is_some();
        let choose = self.processes.len() > 1 && !busy;
        CONNECTING.set(busy);
        ShowWindow(self.list, if choose { SW_SHOWNA } else { SW_HIDE });
        ShowWindow(self.detail, if choose { SW_HIDE } else { SW_SHOWNA });
        ShowWindow(
            self.inject,
            if busy || (ready && !injected) {
                SW_SHOWNA
            } else {
                SW_HIDE
            },
        );
        EnableWindow(self.inject, (ready && !busy && !injected) as i32);
        set_text(self.inject, if busy { "Injecting…" } else { "Inject" });

        let (headline, detail) = if busy {
            ("Injecting…", "Keep Minecraft open.")
        } else if injected {
            ("Injected", "Press Right Shift in Minecraft.")
        } else if ready && pid == self.failed_pid {
            ("Injection failed", "See the launcher log for details.")
        } else if let Some(process) = self.selected_process() {
            ("Minecraft detected", process.title.as_str())
        } else {
            (
                "Waiting for Minecraft",
                "Open Minecraft Java Edition to continue.",
            )
        };
        set_text(self.headline, headline);
        set_text(self.detail, detail);
    }

    unsafe fn toggle_settings(&mut self) {
        let open = !SETTINGS_OPEN.get();
        SETTINGS_OPEN.set(open);
        set_text(self.settings_button, if open { "Back" } else { "Settings" });
        if open {
            let prefs = PREFS.get();
            CHOSEN.set([
                usize::from(prefs.light),
                prefs.scale,
                prefs.opacity,
                usize::from(prefs.capture_privacy),
                usize::from(prefs.close_after_injection),
                usize::from(prefs.debug_console),
            ]);
            if HIGH_CONTRAST.get() {
                for (row, option) in CHOSEN.get().into_iter().enumerate() {
                    self.refresh_options(row, option);
                }
            }
            set_text(self.feedback, "");
        }
        if !self.apply_appearance(PREFS.get()) {
            set_text(self.feedback, "Couldn’t change window opacity.");
        }
        for control in &self.controls {
            let window = control.window;
            if control.settings_only {
                ShowWindow(window, if open { SW_SHOWNA } else { SW_HIDE });
            } else if [self.list, self.detail, self.inject, self.headline].contains(&window) {
                ShowWindow(window, if open { SW_HIDE } else { SW_SHOWNA });
            }
        }
        if !open {
            self.refresh();
        }
        InvalidateRect(self.window, null(), 1);
    }

    unsafe fn layout(&mut self) {
        let face = wide("Segoe UI");
        let new_fonts = [14, 24, 12].map(|height| {
            CreateFontW(
                -scaled(height),
                0,
                0,
                0,
                if height == 24 { 600 } else { 400 },
                0,
                0,
                0,
                DEFAULT_CHARSET as u32,
                0,
                0,
                CLEARTYPE_QUALITY as u32,
                0,
                face.as_ptr(),
            )
        });
        let style = GetWindowLongW(self.window, GWL_STYLE) as u32;
        let ex_style = GetWindowLongW(self.window, GWL_EXSTYLE) as u32;
        let mut bounds = RECT {
            left: 0,
            top: 0,
            right: scaled(WINDOW_WIDTH),
            bottom: scaled(if SETTINGS_OPEN.get() {
                SETTINGS_HEIGHT
            } else {
                WINDOW_HEIGHT
            }),
        };
        AdjustWindowRectEx(&mut bounds, style, 0, ex_style);
        SetWindowPos(
            self.window,
            null_mut(),
            0,
            0,
            bounds.right - bounds.left,
            bounds.bottom - bounds.top,
            SWP_NOMOVE | SWP_NOZORDER | SWP_NOACTIVATE,
        );
        for control in &self.controls {
            let [x, mut y, w, h] = control.bounds;
            if SETTINGS_OPEN.get() && GetDlgCtrlID(control.window) as usize == VERSION {
                y += SETTINGS_HEIGHT - WINDOW_HEIGHT;
            }
            let [x, y, w, h] = [x, y, w, h].map(scaled);
            MoveWindow(control.window, x, y, w, h, 1);
            if !HIGH_CONTRAST.get() && control.window == self.list {
                SendMessageW(
                    control.window,
                    CB_SETITEMHEIGHT,
                    usize::MAX,
                    scaled(28) as LPARAM,
                );
                SendMessageW(control.window, CB_SETITEMHEIGHT, 0, scaled(28) as LPARAM);
            }
            let font = new_fonts[control.font];
            SendMessageW(
                control.window,
                WM_SETFONT,
                if font.is_null() {
                    GetStockObject(DEFAULT_GUI_FONT) as _
                } else {
                    font as _
                },
                1,
            );
        }
        for font in std::mem::replace(&mut self.fonts, new_fonts) {
            if !font.is_null() {
                DeleteObject(font);
            }
        }
        let dark = i32::from(!PREFS.get().light && !HIGH_CONTRAST.get());
        DwmSetWindowAttribute(
            self.window,
            DWMWA_USE_IMMERSIVE_DARK_MODE as u32,
            (&dark as *const i32).cast(),
            std::mem::size_of_val(&dark) as u32,
        );
        if !HIGH_CONTRAST.get() {
            let outline = border();
            DwmSetWindowAttribute(
                self.window,
                DWMWA_BORDER_COLOR as u32,
                (&outline as *const COLORREF).cast(),
                std::mem::size_of_val(&outline) as u32,
            );
        }
        RedrawWindow(
            self.window,
            null(),
            null_mut(),
            RDW_INVALIDATE | RDW_ERASE | RDW_ALLCHILDREN,
        );
    }

    unsafe fn apply_appearance(&mut self, prefs: Preferences) -> bool {
        let old = PREFS.get();
        let opacity = |index: usize| {
            let percent = OPACITIES[index];
            let style = GetWindowLongW(self.window, GWL_EXSTYLE) as u32;
            let next = if percent == 100 {
                style & !WS_EX_LAYERED
            } else {
                style | WS_EX_LAYERED
            };
            if next != style {
                SetWindowLongW(self.window, GWL_EXSTYLE, next as i32);
            }
            percent == 100
                || SetLayeredWindowAttributes(
                    self.window,
                    0,
                    (255 * percent / 100) as u8,
                    LWA_ALPHA,
                ) != 0
        };
        let privacy = |enabled| {
            SetWindowDisplayAffinity(
                self.window,
                if enabled {
                    WDA_EXCLUDEFROMCAPTURE
                } else {
                    WDA_NONE
                },
            ) != 0
        };
        if !opacity(if SETTINGS_OPEN.get() {
            0
        } else {
            prefs.opacity
        }) || ((prefs.capture_privacy || old.capture_privacy) && !privacy(prefs.capture_privacy))
            || (prefs.debug_console != old.debug_console && !debug_console(prefs.debug_console))
        {
            opacity(if SETTINGS_OPEN.get() { 0 } else { old.opacity });
            privacy(old.capture_privacy);
            return false;
        }
        PREFS.set(prefs);
        self.layout();
        true
    }

    unsafe fn select_option(&mut self, id: usize) {
        let row = (id - CHOICE_BASE) / 4;
        let option = (id - CHOICE_BASE) % 4;
        if option >= OPTIONS[row].len() {
            return;
        }
        let mut chosen = CHOSEN.get();
        let previous = chosen[row];
        if previous == option {
            return;
        }
        chosen[row] = option;
        CHOSEN.set(chosen);
        let prefs = draft_preferences();
        let feedback = if !self.apply_appearance(prefs) {
            chosen[row] = previous;
            CHOSEN.set(chosen);
            "Windows couldn’t apply this setting."
        } else if prefs.save().is_err() {
            "Applied, but couldn’t save."
        } else if row == 2 {
            "Saved · Opacity applies on the main screen."
        } else {
            "Saved"
        };
        self.refresh_options(row, CHOSEN.get()[row]);
        set_text(self.feedback, feedback);
    }

    unsafe fn refresh_options(&self, row: usize, selected: usize) {
        for index in 0..OPTIONS[row].len() {
            let control = GetDlgItem(self.window, (CHOICE_BASE + row * 4 + index) as i32);
            if HIGH_CONTRAST.get() {
                let check = if index == selected {
                    BST_CHECKED
                } else {
                    BST_UNCHECKED
                };
                SendMessageW(control, BM_SETCHECK, check as WPARAM, 0);
            } else {
                InvalidateRect(control, null(), 1);
            }
        }
    }

    unsafe fn open_repository(&self) {
        let url = wide("https://github.com/tylerkenyon/Logic-Client");
        let result = ShellExecuteW(
            self.window,
            wide("open").as_ptr(),
            url.as_ptr(),
            null(),
            null(),
            SW_SHOWNORMAL,
        );
        if result as isize <= 32 {
            set_text(self.feedback, "Couldn’t open GitHub.");
        }
    }

    unsafe fn inject(&mut self) {
        if self.job.is_some() {
            return;
        }
        let Some(process) = self.selected_process() else {
            return;
        };
        let pid = process.pid;
        if platform::is_injected(pid) {
            self.refresh();
            return;
        }
        super::step("game", &format!("{} (PID {pid})", process.title));
        let dll = self.dll.clone();
        self.job = Some((pid, std::thread::spawn(move || run(pid, &dll))));
        CONNECTING.set(true);
        SetTimer(self.window, ANIMATION_TIMER, 75, None);
        self.failed_pid = None;
        self.refresh();
    }

    unsafe fn check_update(&mut self) {
        if self.update_job.is_some() {
            return;
        }
        self.update_job = Some(std::thread::spawn(super::update::available_version));
        CHECKING.set(true);
        EnableWindow(self.update_button, 0);
        set_text(self.update_info, "Checking for updates…");
        SetTimer(self.window, ANIMATION_TIMER, 75, None);
    }

    unsafe fn animate(&mut self) {
        SPINNER_STEP.set((SPINNER_STEP.get() + 1) % 10);
        for button in [self.inject, self.update_button] {
            if IsWindowVisible(button) != 0 {
                InvalidateRect(button, null(), 0);
            }
        }
        if self
            .update_job
            .as_ref()
            .is_some_and(|job| job.is_finished())
        {
            let result = self.update_job.take().and_then(|job| job.join().ok());
            let message = match result {
                Some(Ok(Some(version))) => format!("Version {version} is available."),
                Some(Ok(None)) => "You’re up to date.".into(),
                _ => "Couldn’t check for updates.".into(),
            };
            CHECKING.set(false);
            set_text(self.update_info, &message);
            EnableWindow(self.update_button, 1);
            InvalidateRect(self.update_button, null(), 0);
        }
        if self.job.is_none() && self.update_job.is_none() {
            KillTimer(self.window, ANIMATION_TIMER);
        }
    }

    unsafe fn tick(&mut self) {
        if self.job.as_ref().is_some_and(|(_, job)| job.is_finished()) {
            let (pid, job) = self.job.take().unwrap();
            let success = job.join().ok() == Some(ExitCode::SUCCESS);
            self.failed_pid = (!success).then_some(pid);
            CONNECTING.set(false);
            if success && platform::is_injected(pid) && PREFS.get().close_after_injection {
                super::step("launcher", "Injection confirmed. Closing the launcher.");
                PostMessageW(self.window, WM_CLOSE, 0, 0);
                return;
            }
        }
        self.refresh();
    }
}

unsafe extern "system" fn window_proc(
    window: HWND,
    message: u32,
    wparam: WPARAM,
    lparam: LPARAM,
) -> LRESULT {
    match message {
        WM_ERASEBKGND if !HIGH_CONTRAST.get() => {
            let dc = wparam as HDC;
            let mut bounds = std::mem::zeroed();
            GetClientRect(window, &mut bounds);
            fill(dc, &bounds, background());
            SetDCBrushColor(dc, border());
            FrameRect(dc, &bounds, GetStockObject(DC_BRUSH));
            draw_logo(dc);
            if SETTINGS_OPEN.get() {
                for y in [62, 337, 391] {
                    fill(
                        dc,
                        &RECT {
                            left: scaled(SETTINGS_INSET),
                            top: scaled(y),
                            right: scaled(SETTINGS_RIGHT),
                            bottom: scaled(y) + 1,
                        },
                        border(),
                    );
                }
                for row in 0..OPTIONS.len() {
                    let y = ROW_TOP + row as i32 * ROW_STEP;
                    rounded(
                        dc,
                        RECT {
                            left: scaled(CHOICES_LEFT),
                            top: scaled(y),
                            right: scaled(SETTINGS_RIGHT),
                            bottom: scaled(y + CHOICE_HEIGHT),
                        },
                        surface(),
                    );
                }
            }
            1
        }
        WM_CTLCOLORSTATIC | WM_CTLCOLORLISTBOX | WM_CTLCOLOREDIT if !HIGH_CONTRAST.get() => {
            let dc = wparam as HDC;
            let background = if message != WM_CTLCOLORSTATIC {
                surface()
            } else {
                background()
            };
            let foreground = match GetDlgCtrlID(lparam as HWND) as usize {
                SUBTITLE | VERSION => muted(),
                _ => foreground(),
            };
            SetTextColor(dc, foreground);
            SetBkColor(dc, background);
            SetDCBrushColor(dc, background);
            GetStockObject(DC_BRUSH) as LRESULT
        }
        WM_DRAWITEM => {
            let item = &*(lparam as *const DRAWITEMSTRUCT);
            if item.CtlType == ODT_COMBOBOX {
                draw_combo(item);
            } else {
                draw_button(item);
            }
            1
        }
        WM_MEASUREITEM => {
            let item = &mut *(lparam as *mut MEASUREITEMSTRUCT);
            if item.CtlType != ODT_COMBOBOX {
                return DefWindowProcW(window, message, wparam, lparam);
            }
            item.itemHeight = scaled(28) as u32;
            1
        }
        WM_COMMAND | WM_TIMER => {
            APP.with(|state| {
                if let Ok(mut state) = state.try_borrow_mut() {
                    if let Some(app) = state.as_mut() {
                        match (message, wparam & 0xffff) {
                            (WM_TIMER, PROCESS_TIMER) => app.tick(),
                            (WM_TIMER, ANIMATION_TIMER) => app.animate(),
                            (WM_COMMAND, UPDATE) => app.check_update(),
                            (WM_COMMAND, INJECT) => app.inject(),
                            (WM_COMMAND, SETTINGS) => app.toggle_settings(),
                            (WM_COMMAND, GITHUB) => app.open_repository(),
                            (WM_COMMAND, id) if (CHOICE_BASE..CHOICE_END).contains(&id) => {
                                app.select_option(id)
                            }
                            (WM_COMMAND, MINIMIZE) => {
                                ShowWindow(window, SW_MINIMIZE);
                            }
                            (WM_COMMAND, CLOSE) => {
                                PostMessageW(window, WM_CLOSE, 0, 0);
                            }
                            _ => {}
                        }
                    }
                }
            });
            0
        }
        WM_NCHITTEST if !HIGH_CONTRAST.get() => {
            let mut point = windows_sys::Win32::Foundation::POINT {
                x: lparam as i16 as i32,
                y: (lparam >> 16) as i16 as i32,
            };
            ScreenToClient(window, &mut point);
            if (0..scaled(52)).contains(&point.y) && (0..scaled(402)).contains(&point.x) {
                HTCAPTION as LRESULT
            } else {
                DefWindowProcW(window, message, wparam, lparam)
            }
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
        let title = wide("Logic Launcher");
        let dll = match prepare_runtime(true, false) {
            Ok(path) => path,
            Err(error) => {
                MessageBoxW(
                    null_mut(),
                    wide(&error).as_ptr(),
                    title.as_ptr(),
                    MB_ICONERROR,
                );
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
        let mut contrast = HIGHCONTRASTW {
            cbSize: std::mem::size_of::<HIGHCONTRASTW>() as u32,
            ..std::mem::zeroed()
        };
        SystemParametersInfoW(
            SPI_GETHIGHCONTRAST,
            contrast.cbSize,
            (&mut contrast as *mut HIGHCONTRASTW).cast(),
            0,
        );
        let high_contrast = contrast.dwFlags & HCF_HIGHCONTRASTON != 0;
        HIGH_CONTRAST.set(high_contrast);
        let style = if high_contrast {
            WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX
        } else {
            WS_POPUP | WS_SYSMENU | WS_MINIMIZEBOX
        };
        let mut bounds = RECT {
            left: 0,
            top: 0,
            right: WINDOW_WIDTH,
            bottom: WINDOW_HEIGHT,
        };
        AdjustWindowRectEx(&mut bounds, style, 0, 0);
        let mut work: RECT = std::mem::zeroed();
        let (x, y) =
            if SystemParametersInfoW(SPI_GETWORKAREA, 0, (&mut work as *mut RECT).cast(), 0) != 0 {
                (
                    (work.left + work.right - (bounds.right - bounds.left)) / 2,
                    (work.top + work.bottom - (bounds.bottom - bounds.top)) / 2,
                )
            } else {
                (0, 0)
            };
        let window = CreateWindowExW(
            0,
            title.as_ptr(),
            title.as_ptr(),
            style,
            x,
            y,
            bounds.right - bounds.left,
            bounds.bottom - bounds.top,
            null_mut(),
            null_mut(),
            instance,
            null(),
        );
        if window.is_null() {
            return ExitCode::FAILURE;
        }
        if !high_contrast {
            let dark = 1i32;
            DwmSetWindowAttribute(
                window,
                DWMWA_USE_IMMERSIVE_DARK_MODE as u32,
                (&dark as *const i32).cast(),
                std::mem::size_of_val(&dark) as u32,
            );
            let round = DWMWCP_ROUND;
            DwmSetWindowAttribute(
                window,
                DWMWA_WINDOW_CORNER_PREFERENCE as u32,
                (&round as *const i32).cast(),
                std::mem::size_of_val(&round) as u32,
            );
        }
        let mut controls = Vec::new();
        let mut control = |class: &str,
                           text: &str,
                           style: u32,
                           bounds: [i32; 4],
                           id: usize,
                           font: usize,
                           settings_only: bool| {
            let [x, y, width, height] = bounds;
            let visible = if settings_only { 0 } else { WS_VISIBLE };
            let handle = CreateWindowExW(
                0,
                wide(class).as_ptr(),
                wide(text).as_ptr(),
                WS_CHILD | visible | style,
                x,
                y,
                width,
                height,
                window,
                id as _,
                instance,
                null(),
            );
            if !high_contrast && class == "COMBOBOX" && !handle.is_null() {
                SetWindowSubclass(handle, Some(combo_proc), 1, 0);
            }
            controls.push(Control {
                window: handle,
                bounds,
                font,
                settings_only,
            });
            handle
        };
        let button = WS_TABSTOP
            | if high_contrast {
                0
            } else {
                BS_OWNERDRAW as u32
            };
        let settings_button = control(
            "BUTTON",
            "Settings",
            button,
            [402, 13, 32, 34],
            SETTINGS,
            0,
            false,
        );
        if !high_contrast {
            control(
                "BUTTON",
                "Minimize",
                button,
                [436, 13, 32, 34],
                MINIMIZE,
                0,
                false,
            );
            control(
                "BUTTON",
                "Close",
                button,
                [470, 13, 32, 34],
                CLOSE,
                0,
                false,
            );
        }
        let headline = control("STATIC", "", SS_CENTER, [23, 171, 474, 33], 0, 1, false);
        let detail = control(
            "STATIC",
            "",
            SS_CENTER | SS_ENDELLIPSIS,
            [23, 209, 474, 24],
            SUBTITLE,
            0,
            false,
        );
        let combo = WS_VSCROLL
            | WS_TABSTOP
            | CBS_DROPDOWNLIST as u32
            | if high_contrast {
                0
            } else {
                (CBS_OWNERDRAWFIXED | CBS_HASSTRINGS) as u32
            };
        let list = control("COMBOBOX", "", combo, [104, 208, 312, 208], 0, 0, false);
        let inject = control(
            "BUTTON",
            "Connect",
            button,
            [189, 255, 142, 36],
            INJECT,
            0,
            false,
        );
        control(
            "STATIC",
            "Settings",
            SS_CENTERIMAGE,
            [71, 10, 280, 40],
            0,
            1,
            true,
        );
        for (row, (label, options)) in [
            "Theme",
            "Scale",
            "Opacity",
            "Capture privacy",
            "Close after injection",
            "Debug console",
        ]
        .into_iter()
        .zip(OPTIONS)
        .enumerate()
        {
            let y = ROW_TOP + row as i32 * ROW_STEP;
            control(
                "STATIC",
                label,
                SS_CENTERIMAGE,
                [SETTINGS_INSET, y, 200, CHOICE_HEIGHT],
                0,
                0,
                true,
            );
            let width = SETTINGS_RIGHT - CHOICES_LEFT - 8;
            let count = options.len() as i32;
            for (index, option) in options.iter().enumerate() {
                let left = CHOICES_LEFT + 4 + width * index as i32 / count;
                let right = CHOICES_LEFT + 4 + width * (index as i32 + 1) / count;
                control(
                    "BUTTON",
                    option,
                    if high_contrast {
                        WS_TABSTOP | (BS_RADIOBUTTON | BS_PUSHLIKE) as u32
                    } else {
                        button
                    },
                    [left + 1, y + 4, right - left - 2, CHOICE_HEIGHT - 8],
                    CHOICE_BASE + row * 4 + index,
                    0,
                    true,
                );
            }
        }
        control(
            "BUTTON",
            "GitHub",
            button,
            [400, 399, 92, 29],
            GITHUB,
            0,
            true,
        );
        let feedback = control(
            "STATIC",
            "",
            SS_CENTERIMAGE | SS_ENDELLIPSIS,
            [96, 399, 294, 29],
            SUBTITLE,
            2,
            true,
        );
        control(
            "STATIC",
            concat!("v", env!("LOGIC_VERSION")),
            SS_CENTERIMAGE,
            [SETTINGS_INSET, 315, 64, 29],
            VERSION,
            2,
            false,
        );
        let update_button = control(
            "BUTTON",
            "Check for updates",
            button,
            [348, 348, 144, 32],
            UPDATE,
            0,
            true,
        );
        control(
            "STATIC",
            "Updates",
            SS_CENTERIMAGE,
            [SETTINGS_INSET, 348, 68, 32],
            0,
            0,
            true,
        );
        let update_info = control(
            "STATIC",
            "",
            SS_CENTERIMAGE | SS_ENDELLIPSIS,
            [108, 348, 230, 32],
            SUBTITLE,
            2,
            true,
        );
        let mut app = App {
            window,
            controls,
            fonts: [null_mut(); 3],
            settings_button,
            update_button,
            update_info,
            update_job: None,
            feedback,
            list,
            headline,
            detail,
            inject,
            dll,
            processes: Vec::new(),
            job: None,
            failed_pid: None,
            reported_injected: None,
        };
        if !app.apply_appearance(Preferences::load()) {
            app.layout();
            set_text(feedback, "Saved settings couldn't be applied.");
        }
        app.refresh();
        APP.with(|state| *state.borrow_mut() = Some(app));
        SetTimer(window, PROCESS_TIMER, 750, None);
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
        if let Some(app) = APP.with(|state| state.borrow_mut().take()) {
            for font in app.fonts {
                if !font.is_null() {
                    DeleteObject(font);
                }
            }
        }
        result
    }
}
