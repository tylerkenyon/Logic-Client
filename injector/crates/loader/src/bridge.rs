 
use crate::jni::*;
use std::ffi::{c_char, c_void};
use std::sync::atomic::{AtomicPtr, Ordering};
use std::sync::Mutex;

static JVMTI: AtomicPtr<*const *const c_void> = AtomicPtr::new(std::ptr::null_mut());
 
 
static CAPTURE_TARGETS: Mutex<Vec<usize>> = Mutex::new(Vec::new());
 
static CAPTURED: Mutex<Vec<Option<Vec<u8>>>> = Mutex::new(Vec::new());
 
static CAPTURE_LOCK: Mutex<()> = Mutex::new(());

 
pub unsafe fn init_jvmti(vm: VmPtr) -> Result<(), String> {
    let jvmti = get_jvmti(vm)?;
    let mut caps = Capabilities::default();
    for bit in [CAN_REDEFINE_CLASSES, CAN_REDEFINE_ANY_CLASS, CAN_GENERATE_ALL_CLASS_HOOK_EVENTS, CAN_RETRANSFORM_CLASSES, CAN_RETRANSFORM_ANY_CLASS] {
        caps.set(bit);
    }
    let rc = add_capabilities(jvmti, &caps);
    if rc != 0 {
        return Err(format!("AddCapabilities failed ({rc})"));
    }
    install_class_file_load_hook(jvmti, class_file_load_hook)?;
    JVMTI.store(jvmti, Ordering::SeqCst);
    Ok(())
}

 
unsafe extern "system" fn class_file_load_hook(
    _jvmti: JvmtiPtr,
    env: EnvPtr,
    class_being_redefined: JClass,
    _loader: JObject,
    _name: *const c_char,
    _domain: JObject,
    data_len: JInt,
    data: *const u8,
    _new_len: *mut JInt,
    _new_data: *mut *mut u8,
) {
    if class_being_redefined.is_null() || data.is_null() {
        return;
    }
    let targets: Vec<usize> = match CAPTURE_TARGETS.lock() {
        Ok(t) if !t.is_empty() => t.clone(),
        _ => return,
    };
    let jni = Jni::new(env);
    for (i, &target) in targets.iter().enumerate() {
        if jni.is_same_object(class_being_redefined, target as JObject) {
            if let Ok(mut slots) = CAPTURED.lock() {
                if let Some(slot) = slots.get_mut(i) {
                    *slot = Some(std::slice::from_raw_parts(data, data_len as usize).to_vec());
                }
            }
            return;
        }
    }
}

 
pub unsafe extern "system" fn native_class_bytes(env: EnvPtr, _cls: JClass, target: JClass) -> JObject {
    let jvmti = JVMTI.load(Ordering::SeqCst);
    if jvmti.is_null() {
        return std::ptr::null_mut();
    }
    match capture(jvmti, &[target]).and_then(|mut v| v.pop().flatten()) {
        Some(bytes) => to_byte_array(&Jni::new(env), &bytes),
        None => std::ptr::null_mut(),
    }
}

 
unsafe fn capture(jvmti: JvmtiPtr, classes: &[JClass]) -> Option<Vec<Option<Vec<u8>>>> {
    let _serial = CAPTURE_LOCK.lock();
    if let Ok(mut c) = CAPTURED.lock() {
        *c = vec![None; classes.len()];
    }
    if let Ok(mut t) = CAPTURE_TARGETS.lock() {
        *t = classes.iter().map(|&c| c as usize).collect();
    }
    let enabled = set_class_file_load_hook_enabled(jvmti, true);
    let rc = if enabled.is_ok() { retransform_classes(jvmti, classes) } else { -1 };
    let _ = set_class_file_load_hook_enabled(jvmti, false);
    if let Ok(mut t) = CAPTURE_TARGETS.lock() {
        t.clear();
    }
    if let Err(e) = enabled {
        crate::log!("classBytes: {e}");
        return None;
    }
    if rc != 0 {
        crate::log!("RetransformClasses failed ({rc})");
        return None;
    }
    CAPTURED.lock().ok().map(|mut c| std::mem::take(&mut *c))
}

unsafe fn to_byte_array(jni: &Jni, bytes: &[u8]) -> JObject {
    let array = jni.new_byte_array(bytes.len() as JInt);
    if !array.is_null() {
        jni.set_byte_array_region(array, 0, bytes.len() as JInt, bytes.as_ptr());
    }
    array
}

 
 
pub unsafe extern "system" fn native_class_bytes_batch(env: EnvPtr, _cls: JClass, targets: JObject) -> JObject {
    let jvmti = JVMTI.load(Ordering::SeqCst);
    if jvmti.is_null() || targets.is_null() {
        return std::ptr::null_mut();
    }
    let jni = Jni::new(env);
    let n = jni.get_array_length(targets);
    if n <= 0 {
        return std::ptr::null_mut();
    }
    jni.ensure_local_capacity(2 * n + 16);
    let classes: Vec<JClass> = (0..n).map(|i| jni.get_object_array_element(targets, i)).collect();
    let Some(captured) = capture(jvmti, &classes) else {
        return std::ptr::null_mut();
    };
    let byte_array_class = jni.find_class("[B");
    let result = jni.new_object_array(n, byte_array_class, std::ptr::null_mut());
    if result.is_null() {
        return std::ptr::null_mut();
    }
    for (i, bytes) in captured.iter().enumerate() {
        if let Some(bytes) = bytes {
            let array = to_byte_array(&jni, bytes);
            if !array.is_null() {
                jni.set_object_array_element(result, i as JInt, array);
            }
        }
    }
    result
}

 
 
pub unsafe extern "system" fn native_redefine_batch(env: EnvPtr, _cls: JClass, targets: JObject, bytes: JObject) -> JInt {
    let jvmti = JVMTI.load(Ordering::SeqCst);
    if jvmti.is_null() || targets.is_null() || bytes.is_null() {
        return -1;
    }
    let jni = Jni::new(env);
    let n = jni.get_array_length(targets);
    if n <= 0 || jni.get_array_length(bytes) != n {
        return -1;
    }
    jni.ensure_local_capacity(2 * n + 16);
    let mut buffers: Vec<Vec<u8>> = Vec::with_capacity(n as usize);
    let mut classes: Vec<JClass> = Vec::with_capacity(n as usize);
    for i in 0..n {
        let array = jni.get_object_array_element(bytes, i);
        if array.is_null() {
            return -1;
        }
        let len = jni.get_array_length(array);
        if len < 0 {
            return -1;
        }
        let mut buf = vec![0u8; len as usize];
        jni.get_byte_array_region(array, 0, len, buf.as_mut_ptr());
        buffers.push(buf);
        classes.push(jni.get_object_array_element(targets, i));
    }
    let defs: Vec<ClassDefinition> = classes
        .iter()
        .zip(&buffers)
        .map(|(&klass, buf)| ClassDefinition { klass, class_byte_count: buf.len() as JInt, class_bytes: buf.as_ptr() })
        .collect();
    let rc = redefine_classes(jvmti, &defs);
    if rc != 0 {
        crate::log!("RedefineClasses (batch of {n}) failed ({rc})");
    }
    rc
}

 
pub unsafe extern "system" fn native_redefine(env: EnvPtr, _cls: JClass, target: JClass, bytes: JObject) -> JInt {
    let jvmti = JVMTI.load(Ordering::SeqCst);
    if jvmti.is_null() || bytes.is_null() {
        return -1;
    }
    let jni = Jni::new(env);
    let len = jni.get_array_length(bytes);
    if len < 0 {
        return -1;
    }
    let mut buf = vec![0u8; len as usize];
    jni.get_byte_array_region(bytes, 0, len, buf.as_mut_ptr());
    let def = ClassDefinition { klass: target, class_byte_count: len, class_bytes: buf.as_ptr() };
    let rc = redefine_class(jvmti, &def);
    if rc != 0 {
        crate::log!("RedefineClasses failed ({rc})");
    }
    rc
}

 
pub unsafe extern "system" fn native_log(env: EnvPtr, _cls: JClass, message: JObject) {
    if message.is_null() {
        return;
    }
    let text = Jni::new(env).get_string_utf(message);
    crate::log!("[java] {text}");
}

#[cfg(windows)]
extern "system" {
    fn FreeLibraryAndExitThread(module: *mut c_void, exit_code: u32) -> !;
}

 
 
pub unsafe extern "system" fn native_release(env: EnvPtr, cls: JClass) {
    let Ok(_capture_guard) = CAPTURE_LOCK.lock() else {
        crate::log!("release: capture lock unavailable; retaining native code");
        return;
    };
    let jvmti = JVMTI.swap(std::ptr::null_mut(), Ordering::SeqCst);
    if !jvmti.is_null() {
        if let Err(e) = disable_class_file_load_hook(jvmti) {
            crate::log!("release: {e}");
            JVMTI.store(jvmti, Ordering::SeqCst);
            return;
        }
    }
     
    let rc = Jni::new(env).unregister_natives(cls);
    if rc != JNI_OK {
        crate::log!("release: UnregisterNatives failed ({rc})");
         
        return;
    }
    #[cfg(windows)]
    {
        crate::log!("[java] released, unloading the loader");
        let module = crate::MODULE as usize;
         
        let _ = std::thread::Builder::new().name("logic-unload".into()).spawn(move || {
            std::thread::sleep(std::time::Duration::from_millis(2000));
            FreeLibraryAndExitThread(module as *mut c_void, 0);
        });
    }
     
     
    #[cfg(unix)]
    crate::log!("[java] released (library stays resident on this platform)");
}
