plugins {
    id("fuzs.multiloader.multiloader-convention-plugins-neoforge")
}

dependencies {
    modCompileOnly(sharedLibs.puzzleslib.common)
    modApi(sharedLibs.puzzleslib.neoforge)
    modCompileOnly(sharedLibs.diagonalblocks.common)
    modApi(sharedLibs.diagonalblocks.neoforge)
    include(sharedLibs.diagonalblocks.neoforge)
}
