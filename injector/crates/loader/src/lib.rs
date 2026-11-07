 
 
 
 
 
mod bridge;
mod jni;
mod log;
#[path = "../../paths.rs"]
mod paths;
mod worker;

use std::ffi::c_void;

#[cfg(windows)]
const DLL_PROCESS_ATTACH: u32 = 1;

#[cfg(windows)]
extern "system" {
    fn DisableThreadLibraryCalls(module: *mut c_void) -> i32;
}

#[cfg(windows)]
pub(crate) static mut MODULE: *mut c_void = std::ptr::null_mut();

#[cfg(windows)]
#[no_mangle]
pub extern "system" fn DllMain(module: *mut c_void, reason: u32, _reserved: *mut c_void) -> i32 {
    if reason == DLL_PROCESS_ATTACH {
        unsafe {
            MODULE = module;
            DisableThreadLibraryCalls(module);
        }
         
        let _ = std::thread::Builder::new().name("logic-loader".into()).spawn(worker::run);
    }
    1
}

 
#[cfg(unix)]
pub(crate) static VM: std::sync::atomic::AtomicPtr<c_void> = std::sync::atomic::AtomicPtr::new(std::ptr::null_mut());

#[cfg(unix)]
fn start_worker(vm: *mut c_void) -> i32 {
    VM.store(vm, std::sync::atomic::Ordering::SeqCst);
    match std::thread::Builder::new().name("logic-loader".into()).spawn(worker::run) {
        Ok(_) => 0,
        Err(_) => -1,
    }
}

 
#[cfg(unix)]
#[no_mangle]
pub extern "C" fn Agent_OnAttach(vm: *mut c_void, _options: *mut std::ffi::c_char, _reserved: *mut c_void) -> i32 {
    start_worker(vm)
}

 
#[cfg(unix)]
#[no_mangle]
pub extern "C" fn Agent_OnLoad(vm: *mut c_void, _options: *mut std::ffi::c_char, _reserved: *mut c_void) -> i32 {
    start_worker(vm)
}
