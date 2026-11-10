 
use crate::bridge::{init_jvmti, native_class_bytes, native_class_bytes_batch, native_log, native_redefine, native_redefine_batch, native_release};
use crate::jni::*;
use crate::log;
#[cfg(windows)]
use std::ffi::c_char;
use std::ffi::c_void;
use std::path::PathBuf;
#[cfg(windows)]
use std::time::Duration;

#[cfg(windows)]
extern "system" {
    fn GetModuleHandleA(name: *const c_char) -> *mut c_void;
    fn GetProcAddress(module: *mut c_void, name: *const c_char) -> *mut c_void;
}

static PAYLOAD: &[u8] = include_bytes!(env!("LOGIC_PAYLOAD_JAR"));
static RUNTIME: &[u8] = include_bytes!(env!("LOGIC_RUNTIME_JAR"));

const NATIVE_BRIDGE: &str = "dev.technix.logic.inject.NativeBridge";
const BOOTSTRAP: &str = "dev.technix.logic.inject.Bootstrap";

pub fn run() {
    log!("worker started, pid {}", std::process::id());
    match unsafe { install() } {
        Ok(()) => log!("bootstrap returned"),
        Err(e) => log!("FAILED: {e}"),
    }
}

unsafe fn install() -> Result<(), String> {
    let data_root = crate::paths::initialize().map_err(|error| format!("create Logic data directory: {error}"))?;
    let vm = find_vm()?;
    let env = attach_current_thread(vm)?;
    struct Detach(VmPtr);
    impl Drop for Detach {
        fn drop(&mut self) {
            unsafe { detach_current_thread(self.0) }
        }
    }
    let _detach = Detach(vm);
    let jni = Jni::new(env);
    log!("attached to the JVM");

    init_jvmti(vm)?;
    log!("JVMTI ready");

    let runtime = write_runtime()?;
    let parent = add_to_knot(&jni, &runtime)?;
    let host = class_for_name(&jni, "dev.technix.logic.host.RuntimeHost", parent, true)?;
    #[cfg(payload_26_3)]
    let payload = {
        let method = jni.get_static_method_id(host, "minecraftVersion", "()Ljava/lang/String;");
        jni.check("RuntimeHost.minecraftVersion lookup")?;
        let version = jni.call_static_object(host, method, &[]);
        jni.check("RuntimeHost.minecraftVersion")?;
        let version = jni.get_string_utf(jni_non_null(version)?);
        log!("target Minecraft {version}");
        match version.as_str() {
            "26.2" => PAYLOAD,
            "26.3" => include_bytes!(env!("LOGIC_PAYLOAD_26_3_JAR")).as_slice(),
            _ => return Err(format!("Minecraft {version} is unsupported. Choose a 26.2 or 26.3 Fabric game.")),
        }
    };
    #[cfg(not(payload_26_3))]
    let payload = PAYLOAD;
    let jar = write_payload(payload)?;
    log!("using payload at {}", jar.display());
    let open = jni.get_static_method_id(host, "openPayload", "(Ljava/lang/String;Ljava/lang/ClassLoader;)Ljava/lang/ClassLoader;");
    jni.check("RuntimeHost.openPayload lookup")?;
    let jar_path = jni.new_string(&jar.to_string_lossy());
    let loader = jni.call_static_object(host, open, &[obj(jar_path), obj(parent)]);
    jni.check("RuntimeHost.openPayload")?;
    if loader.is_null() { return Err("payload class loader is null".into()); }
    log!("payload opened in disposable class loader: {}", describe(&jni, loader));

    register_natives(&jni, loader)?;
    log!("natives registered");

    let bootstrap = class_for_name(&jni, BOOTSTRAP, loader, true)?;
    #[cfg(payload_26_3)]
    {
        let start = jni.get_static_method_id(bootstrap, "start", "(Ljava/lang/String;)V");
        jni.check("resolve Bootstrap.start")?;
        let root = jni.new_string(&data_root.to_string_lossy());
        jni.call_static_void(bootstrap, start, &[obj(root)]);
    }
    #[cfg(not(payload_26_3))]
    {
        let _ = data_root;
        let start = jni.get_static_method_id(bootstrap, "start", "()V");
        jni.check("resolve Bootstrap.start")?;
        jni.call_static_void(bootstrap, start, &[]);
    }
    jni.check("Bootstrap.start")?;
    Ok(())
}

 
#[cfg(unix)]
unsafe fn find_vm() -> Result<VmPtr, String> {
    let vm = crate::VM.load(std::sync::atomic::Ordering::SeqCst) as VmPtr;
    if vm.is_null() {
        return Err("no JavaVM was passed to the agent".into());
    }
    Ok(vm)
}

 
#[cfg(windows)]
unsafe fn find_vm() -> Result<VmPtr, String> {
    let mut module = std::ptr::null_mut();
    for _ in 0..600 {
        module = GetModuleHandleA(c"jvm.dll".as_ptr());
        if !module.is_null() {
            break;
        }
        std::thread::sleep(Duration::from_millis(100));
    }
    if module.is_null() {
        return Err("jvm.dll is not loaded in this process".into());
    }
    let get_vms = GetProcAddress(module, c"JNI_GetCreatedJavaVMs".as_ptr());
    if get_vms.is_null() {
        return Err("JNI_GetCreatedJavaVMs export not found".into());
    }
    let get_vms: unsafe extern "system" fn(*mut VmPtr, JInt, *mut JInt) -> JInt = std::mem::transmute(get_vms);
    for _ in 0..600 {
        let mut vm: VmPtr = std::ptr::null_mut();
        let mut count: JInt = 0;
        if get_vms(&mut vm, 1, &mut count) == JNI_OK && count > 0 && !vm.is_null() {
            return Ok(vm);
        }
        std::thread::sleep(Duration::from_millis(100));
    }
    Err("no JavaVM was created".into())
}

fn write_payload(payload: &[u8]) -> Result<PathBuf, String> {
    let dir = crate::paths::initialize().map_err(|e| format!("create Logic data directory: {e}"))?
        .join("runtime").join("payloads");
    std::fs::create_dir_all(&dir).map_err(|e| format!("create {}: {e}", dir.display()))?;
    let generation = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH)
        .map_err(|e| e.to_string())?.as_nanos();
    let path = dir.join(format!("logic-payload-{}-{generation}.jar", std::process::id()));
    std::fs::write(&path, payload).map_err(|e| format!("write {}: {e}", path.display()))?;
    Ok(path)
}

fn write_runtime() -> Result<PathBuf, String> {
    use std::hash::{Hash, Hasher};
    let mut hash = std::collections::hash_map::DefaultHasher::new();
    RUNTIME.hash(&mut hash);
    let dir = crate::paths::initialize().map_err(|e| e.to_string())?.join("runtime").join("shared");
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let path = dir.join(format!("logic-runtime-{:016x}.jar", hash.finish()));
    if std::fs::read(&path).ok().as_deref() != Some(RUNTIME) {
        std::fs::write(&path, RUNTIME).map_err(|e| format!("write runtime: {e}"))?;
    }
    Ok(path)
}

 
 
unsafe fn add_to_knot(jni: &Jni, jar: &std::path::Path) -> Result<JObject, String> {
    jni.ensure_local_capacity(4096);
    let (base, fabric_loader) = locate_fabric(jni)?;
    let get_launcher = jni.get_static_method_id(base, "getLauncher", "()Lnet/fabricmc/loader/impl/launch/FabricLauncher;");
    jni.check("FabricLauncherBase.getLauncher")?;
    let launcher = jni.call_static_object(base, get_launcher, &[]);
    jni.check("getLauncher()")?;
    if launcher.is_null() {
        return Err("Fabric launcher is not available yet".into());
    }

    let launcher_class = load_fabric_class(jni, "net.fabricmc.loader.impl.launch.FabricLauncher", fabric_loader)?;
    let add = jni.get_method_id(launcher_class, "addToClassPath", "(Ljava/nio/file/Path;[Ljava/lang/String;)V");
    jni.check("FabricLauncher.addToClassPath")?;
    let target = jni.get_method_id(launcher_class, "getTargetClassLoader", "()Ljava/lang/ClassLoader;");
    jni.check("FabricLauncher.getTargetClassLoader")?;

     
    let file_class = jni.find_class("java/io/File");
    let file_ctor = jni.get_method_id(file_class, "<init>", "(Ljava/lang/String;)V");
    let to_path = jni.get_method_id(file_class, "toPath", "()Ljava/nio/file/Path;");
    jni.check("java.io.File")?;
    let jar_string = jni.new_string(&jar.to_string_lossy());
    let file = jni.new_object(file_class, file_ctor, &[obj(jar_string)]);
    let path = jni.call_object(file, to_path, &[]);
    jni.check("File.toPath")?;

    let string_class = jni.find_class("java/lang/String");
    let prefixes = jni.new_object_array(0, string_class, std::ptr::null_mut());
    jni.call_void(launcher, add, &[obj(path), obj(prefixes)]);
    jni.check("addToClassPath")?;

    let loader = jni.call_object(launcher, target, &[]);
    jni.check("getTargetClassLoader()")?;
    if loader.is_null() {
        return Err("Knot target class loader is null".into());
    }
    Ok(loader)
}

const FABRIC_BASE: &str = "net.fabricmc.loader.impl.launch.FabricLauncherBase";

 
 
unsafe fn locate_fabric(jni: &Jni) -> Result<(JClass, JObject), String> {
    let (render, loaders) = thread_loaders(jni)?;
    if let Some(loader) = render {
        if let Ok(class) = class_for_name(jni, FABRIC_BASE, loader, false) {
            log!("fabric-loader found through the render thread: {}", describe(jni, loader));
            return Ok((class, loader));
        }
    }
    let base = jni.find_class(&FABRIC_BASE.replace('.', "/"));
    if jni.take_exception().is_none() && !base.is_null() {
        return Ok((base, std::ptr::null_mut()));
    }
    log!("FabricLauncherBase is not visible to the system class loader, probing thread context class loaders");
    for &loader in &loaders {
        if render.is_some_and(|r| jni.is_same_object(r, loader)) {
            continue;
        }
        if let Ok(class) = class_for_name(jni, FABRIC_BASE, loader, false) {
            log!("fabric-loader found through {}", describe(jni, loader));
            return Ok((class, loader));
        }
    }
    let seen: Vec<String> = loaders.iter().map(|&l| describe(jni, l)).collect();
    Err(format!(
        "net.fabricmc.loader is not visible to any class loader ({} probed: {}); is this a Fabric instance?",
        seen.len(),
        seen.join(" | ")
    ))
}

 
unsafe fn thread_loaders(jni: &Jni) -> Result<(Option<JObject>, Vec<JObject>), String> {
    let thread_class = jni.find_class("java/lang/Thread");
    let get_all = jni.get_static_method_id(thread_class, "getAllStackTraces", "()Ljava/util/Map;");
    let get_context = jni.get_method_id(thread_class, "getContextClassLoader", "()Ljava/lang/ClassLoader;");
    let get_name = jni.get_method_id(thread_class, "getName", "()Ljava/lang/String;");
    let map_class = jni.find_class("java/util/Map");
    let key_set = jni.get_method_id(map_class, "keySet", "()Ljava/util/Set;");
    let set_class = jni.find_class("java/util/Set");
    let to_array = jni.get_method_id(set_class, "toArray", "()[Ljava/lang/Object;");
    jni.check("Thread/Map/Set lookup")?;
    let map = jni.call_static_object(thread_class, get_all, &[]);
    jni.check("Thread.getAllStackTraces")?;
    let set = jni.call_object(jni_non_null(map)?, key_set, &[]);
    let threads = jni.call_object(jni_non_null(set)?, to_array, &[]);
    jni.check("thread list")?;
    let mut loaders: Vec<JObject> = Vec::new();
    let mut render = None;
    for i in 0..jni.get_array_length(jni_non_null(threads)?) {
        let thread = jni.get_object_array_element(threads, i);
        if thread.is_null() {
            continue;
        }
        let loader = jni.call_object(thread, get_context, &[]);
        if jni.take_exception().is_some() || loader.is_null() {
            continue;
        }
        let name = jni.call_object(thread, get_name, &[]);
        if jni.take_exception().is_none() && !name.is_null() {
            if matches!(jni.get_string_utf(name).as_str(), "Render thread" | "Client thread") {
                render = Some(loader);
            }
        }
        if !loaders.iter().any(|&known| jni.is_same_object(known, loader)) {
            loaders.push(loader);
        }
    }
    Ok((render, loaders))
}

unsafe fn jni_non_null(o: JObject) -> Result<JObject, String> {
    if o.is_null() { Err("unexpected null from the JVM".into()) } else { Ok(o) }
}

 
unsafe fn describe(jni: &Jni, o: JObject) -> String {
    let class = jni.get_object_class(o);
    let to_string = jni.get_method_id(class, "toString", "()Ljava/lang/String;");
    if to_string.is_null() {
        jni.exception_clear();
        return "<loader>".into();
    }
    let s = jni.call_object(o, to_string, &[]);
    if jni.take_exception().is_some() || s.is_null() { "<loader>".into() } else { jni.get_string_utf(s) }
}

 
unsafe fn load_fabric_class(jni: &Jni, name: &str, loader: JObject) -> Result<JClass, String> {
    if loader.is_null() {
        let class = jni.find_class(&name.replace('.', "/"));
        jni.check(name)?;
        return Ok(class);
    }
    class_for_name(jni, name, loader, true)
}

 
unsafe fn class_for_name(jni: &Jni, name: &str, loader: JObject, initialize: bool) -> Result<JClass, String> {
    let class_class = jni.find_class("java/lang/Class");
    let for_name = jni.get_static_method_id(class_class, "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;");
    jni.check("Class.forName")?;
    let name_string = jni.new_string(name);
    let class = jni.call_static_object(class_class, for_name, &[obj(name_string), boolean(initialize), obj(loader)]);
    jni.check(&format!("load {name}"))?;
    if class.is_null() {
        return Err(format!("{name} not found"));
    }
    Ok(class)
}

unsafe fn register_natives(jni: &Jni, loader: JObject) -> Result<(), String> {
    let class = class_for_name(jni, NATIVE_BRIDGE, loader, true)?;
    let methods = [
        NativeMethod {
            name: c"classBytes".as_ptr(),
            signature: c"(Ljava/lang/Class;)[B".as_ptr(),
            fn_ptr: native_class_bytes as *const () as usize as *mut c_void,
        },
        NativeMethod {
            name: c"redefine".as_ptr(),
            signature: c"(Ljava/lang/Class;[B)I".as_ptr(),
            fn_ptr: native_redefine as *const () as usize as *mut c_void,
        },
        NativeMethod {
            name: c"classBytesBatch".as_ptr(),
            signature: c"([Ljava/lang/Class;)[[B".as_ptr(),
            fn_ptr: native_class_bytes_batch as *const () as usize as *mut c_void,
        },
        NativeMethod {
            name: c"redefineBatch".as_ptr(),
            signature: c"([Ljava/lang/Class;[[B)I".as_ptr(),
            fn_ptr: native_redefine_batch as *const () as usize as *mut c_void,
        },
        NativeMethod {
            name: c"release".as_ptr(),
            signature: c"()V".as_ptr(),
            fn_ptr: native_release as *const () as usize as *mut c_void,
        },
        NativeMethod {
            name: c"log".as_ptr(),
            signature: c"(Ljava/lang/String;)V".as_ptr(),
            fn_ptr: native_log as *const () as usize as *mut c_void,
        },
    ];
    let rc = jni.register_natives(class, &methods);
    if rc != JNI_OK {
        let _ = jni.take_exception();
        return Err(format!("RegisterNatives failed ({rc})"));
    }
    Ok(())
}
