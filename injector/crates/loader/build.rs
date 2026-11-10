 
use std::env;
use std::path::PathBuf;

fn main() {
    println!("cargo:rerun-if-env-changed=LOGIC_RUNTIME");
    let runtime = env::var("LOGIC_RUNTIME").map(PathBuf::from).unwrap_or_else(|_|
        PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap()).join("../../../build/injection/logic-runtime.jar"));
    let runtime = runtime.canonicalize().expect("build logic-runtime.jar before the injector");
    println!("cargo:rerun-if-changed={}", runtime.display());
    println!("cargo:rustc-env=LOGIC_RUNTIME_JAR={}", runtime.display());
    println!("cargo:rerun-if-env-changed=LOGIC_PAYLOAD");
    let jar = match env::var("LOGIC_PAYLOAD") {
        Ok(p) => PathBuf::from(p),
        Err(_) => newest_remapped_jar(),
    };
    if !jar.is_file() {
        panic!(
            "payload jar not found: {} (run build-injector.ps1 or set LOGIC_PAYLOAD)",
            jar.display()
        );
    }
    let jar = jar.canonicalize().expect("resolve payload path");
    println!("cargo:rerun-if-changed={}", jar.display());
    println!("cargo:rustc-env=LOGIC_PAYLOAD_JAR={}", jar.display());
    println!("cargo:rerun-if-env-changed=LOGIC_PAYLOAD_26_3");
    if let Some(path) = env::var_os("LOGIC_PAYLOAD_26_3") {
        let path = PathBuf::from(path).canonicalize().expect("resolve 26.3 payload");
        println!("cargo:rerun-if-changed={}", path.display());
        println!("cargo:rustc-env=LOGIC_PAYLOAD_26_3_JAR={}", path.display());
        println!("cargo:rustc-check-cfg=cfg(payload_26_3)");
        println!("cargo:rustc-cfg=payload_26_3");
    } else {
        println!("cargo:rustc-check-cfg=cfg(payload_26_3)");
    }
}

fn newest_remapped_jar() -> PathBuf {
    PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap()).join("../../../build/injection/logic-payload.jar")
}
