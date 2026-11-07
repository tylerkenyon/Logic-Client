fn main() {
    let properties = "../../../gradle.properties";
    println!("cargo:rerun-if-changed={properties}");
    let contents = std::fs::read_to_string(properties).expect("read gradle.properties");
    let version = contents
        .lines()
        .find_map(|line| line.strip_prefix("version="))
        .expect("mod_version is required")
        .trim();
    println!("cargo:rustc-env=PRESTIGE_VERSION={version}");
}
