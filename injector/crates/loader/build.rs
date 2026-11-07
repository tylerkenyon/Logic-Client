 
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
}

fn newest_remapped_jar() -> PathBuf {
    PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap()).join("../../../build/injection/logic-payload.jar")
}
