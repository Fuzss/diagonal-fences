plugins {
    id("fuzs.multiloader.multiloader-convention-plugins-common")
}

dependencies {
    modCompileOnlyApi(libs.puzzleslib.common)
    modCompileOnlyApi(libs.diagonalblocks.common)

    // The multiloader convention plugin wires no test framework, so JUnit is declared here.
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // `modCompileOnlyApi` reaches testCompileClasspath but by design never reaches any runtime
    // classpath. The published diagonalblocks jar is already in named (Mojang) mappings, so it
    // can be added directly for test runtime without a loom remap.
    testRuntimeOnly(libs.diagonalblocks.common)
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
