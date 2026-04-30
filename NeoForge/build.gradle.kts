plugins {
    id("fuzs.multiloader.multiloader-convention-plugins-neoforge")
}

dependencies {
    modApi(sharedLibs.puzzleslib.neoforge)
    modApi(sharedLibs.diagonalblocks.neoforge)
    include(sharedLibs.diagonalblocks.neoforge)
}
